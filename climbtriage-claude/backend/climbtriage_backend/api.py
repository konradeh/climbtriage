"""HTTP API v1 (see docs/05-contracts.md). Run: `uvicorn climbtriage_backend.api:create_app --factory`.

The API never runs inference; it validates, stores and enqueues. Workers are separate
processes (`python -m climbtriage_backend.worker`).
"""

from __future__ import annotations

import hashlib
import json
import re

from fastapi import Depends, FastAPI, Header, HTTPException, Request
from pydantic import BaseModel, Field

from . import SCHEMA
from .blobs import LocalBlobStore
from .settings import Settings
from .store import TERMINAL, Store
from .worker import HOLD_STEPS, job_prefix, upload_key

ALLOWED_CONTENT_TYPES = {"image/jpeg"}
DEFAULT_PROMPTS = ["climbing hold", "climbing volume"]
PIPELINE_VERSION = "holds-pipeline.v1"
_RANGE = re.compile(r"^bytes (\d+)-(\d+)/(\d+)$")


class UploadCreate(BaseModel):
    sizeBytes: int = Field(gt=0)
    sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    contentType: str


class JobParams(BaseModel):
    prompts: list[str] = Field(default_factory=lambda: list(DEFAULT_PROMPTS), min_length=1, max_length=4)
    minScore: float = Field(default=0.3, ge=0, le=1)


class JobCreate(BaseModel):
    kind: str = Field(pattern=r"^hold_candidates$")
    uploadId: str
    params: JobParams = Field(default_factory=JobParams)


def create_app(settings: Settings | None = None, *, provider_configured: bool | None = None,
               provider_id: str | None = None) -> FastAPI:
    settings = settings or Settings.from_env()          # also loads ./.env
    store = Store(settings.db_path)
    blobs = LocalBlobStore(settings.blob_root)
    if provider_configured is None or provider_id is None:
        import os
        kind = os.environ.get("CLIMBTRIAGE_PROVIDER", "vlmrun")
        provider_id = provider_id or (f"{kind}:facebook/sam3.1" if kind == "vlmrun" else f"{kind}:fixture-blobs")
        if provider_configured is None:
            provider_configured = kind == "fixture" or bool(os.environ.get("VLMRUN_API_KEY"))

    app = FastAPI(title="ClimbTriage backend", version="0.1.0")
    app.state.store, app.state.blobs, app.state.settings = store, blobs, settings

    def auth(authorization: str | None = Header(default=None)):
        if settings.api_token and authorization != f"Bearer {settings.api_token}":
            raise HTTPException(401, "missing or wrong bearer token")

    @app.get("/v1/health")
    def health():
        return {"status": "ok", "schema": SCHEMA,
                "provider": "configured" if provider_configured else "missing",
                "providerId": provider_id}

    # ── resumable uploads ───────────────────────────────────────────────────
    @app.post("/v1/uploads", status_code=201, dependencies=[Depends(auth)])
    def create_upload(body: UploadCreate):
        if body.contentType not in ALLOWED_CONTENT_TYPES:
            raise HTTPException(415, f"contentType must be one of {sorted(ALLOWED_CONTENT_TYPES)}")
        if body.sizeBytes > settings.max_upload_bytes:
            raise HTTPException(413, "upload too large")
        upload_id = store.create_upload(body.sizeBytes, body.sha256, body.contentType)
        return {"uploadId": upload_id, "offset": 0}

    @app.get("/v1/uploads/{upload_id}", dependencies=[Depends(auth)])
    def upload_status(upload_id: str):
        row = store.upload(upload_id)
        if not row or row["state"] == "deleted":
            raise HTTPException(404, "no such upload")
        return {"uploadId": upload_id, "offset": row["offset"], "sizeBytes": row["size"],
                "complete": row["state"] == "complete", "state": row["state"]}

    @app.put("/v1/uploads/{upload_id}", dependencies=[Depends(auth)])
    async def upload_chunk(upload_id: str, request: Request, content_range: str = Header(alias="Content-Range")):
        row = store.upload(upload_id)
        if not row or row["state"] == "deleted":
            raise HTTPException(404, "no such upload")
        if row["state"] != "open":
            raise HTTPException(409, {"error": f"upload is {row['state']}", "offset": row["offset"]})
        match = _RANGE.match(content_range or "")
        if not match:
            raise HTTPException(400, "Content-Range must be 'bytes a-b/total'")
        start, end, total = (int(g) for g in match.groups())
        if total != row["size"] or end < start or end >= total:
            raise HTTPException(400, "Content-Range does not fit the declared size")
        if start != row["offset"]:
            # A gap or an overlap: tell the client where to resume from.
            raise HTTPException(409, {"error": "offset mismatch", "offset": row["offset"]})
        data = await request.body()
        if len(data) != end - start + 1:
            raise HTTPException(400, "chunk length does not match Content-Range")
        key = upload_key(upload_id)
        blobs.truncate(key, start)          # discard any tail from an interrupted earlier write
        blobs.append(key, data)
        if not store.advance_upload(upload_id, start, end + 1):
            raise HTTPException(409, {"error": "concurrent write", "offset": store.upload(upload_id)["offset"]})
        complete = end + 1 == total
        if complete:
            digest = hashlib.sha256(blobs.get(key)).hexdigest()
            if digest != row["sha256"]:
                store.set_upload_state(upload_id, "corrupt")
                blobs.delete(key)
                raise HTTPException(422, "sha256 mismatch; upload discarded, start a new one")
            store.set_upload_state(upload_id, "complete")
        return {"uploadId": upload_id, "offset": end + 1, "complete": complete}

    # ── jobs ────────────────────────────────────────────────────────────────
    @app.post("/v1/jobs", status_code=202, dependencies=[Depends(auth)])
    def create_job(body: JobCreate, idempotency_key: str | None = Header(default=None, alias="Idempotency-Key")):
        row = store.upload(body.uploadId)
        if not row or row["state"] != "complete":
            raise HTTPException(409, "upload is not complete")
        params = body.params.model_dump()
        request_hash = hashlib.sha256(json.dumps(body.model_dump(), sort_keys=True).encode()).hexdigest()
        config_hash = hashlib.sha256(json.dumps(
            {"params": params, "pipeline": PIPELINE_VERSION, "provider": provider_id, "schema": SCHEMA},
            sort_keys=True).encode()).hexdigest()
        cache_key = f"{row['sha256']}:{config_hash}"
        try:
            job, created = store.create_job(kind=body.kind, upload_id=body.uploadId, params=params,
                                            idem_key=idempotency_key, request_hash=request_hash,
                                            cache_key=cache_key, steps_total=len(HOLD_STEPS),
                                            max_attempts=settings.max_attempts)
        except ValueError as exc:
            raise HTTPException(409, str(exc)) from exc
        return {**job.public(), "created": created}

    def _load(job_id: str):
        job = store.job(job_id)
        if not job or job.deleted:
            raise HTTPException(404, "no such job")
        return job

    @app.get("/v1/jobs/{job_id}", dependencies=[Depends(auth)])
    def get_job(job_id: str):
        job = _load(job_id)
        out = job.public()
        if job.status == "succeeded" and job.result_ref and blobs.exists(job.result_ref):
            out["result"] = json.loads(blobs.get(job.result_ref))
        return out

    @app.post("/v1/jobs/{job_id}/cancel", dependencies=[Depends(auth)])
    def cancel_job(job_id: str):
        job = _load(job_id)
        if job.status in TERMINAL:
            return job.public()
        return store.request_cancel(job_id).public()

    @app.delete("/v1/jobs/{job_id}", status_code=204, dependencies=[Depends(auth)])
    def delete_job(job_id: str):
        job = _load(job_id)
        store.tombstone(job_id)                       # first: late workers now cannot commit
        blobs.delete_prefix(job_prefix(job_id))
        blobs.delete(upload_key(job.upload_id))
        store.set_upload_state(job.upload_id, "deleted")
        return None

    return app
