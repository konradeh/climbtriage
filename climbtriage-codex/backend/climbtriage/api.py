from __future__ import annotations

import os
from pathlib import Path
from typing import Annotated, Literal

from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field

from .store import Conflict, Gone, MAX_CHUNK, MAX_UPLOAD, Store


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class Upload(StrictModel):
    sha256: str = Field(pattern=r"^[a-f0-9]{64}$")
    size_bytes: int = Field(gt=0, le=MAX_UPLOAD)
    content_type: Literal["image/jpeg", "image/png"]


class Job(StrictModel):
    upload_id: str = Field(min_length=36, max_length=36)
    timestamp_us: int = Field(ge=0)
    coordinate_space: Literal["upright_video_normalized"]
    consent_to_provider: Literal[True]


class UploadInfo(StrictModel):
    id: str
    offset: int = Field(ge=0)
    complete: bool


class Provenance(StrictModel):
    provider: str
    model: str
    model_revision: str
    algorithm: str
    config_hash: str


UnitFloat = Annotated[float, Field(ge=0, le=1, allow_inf_nan=False)]


class HoldCandidate(StrictModel):
    id: str
    display_number: int = Field(ge=1)
    kind: Literal["hold", "volume"]
    parts: list[list[tuple[UnitFloat, UnitFloat]]]
    confidence: UnitFloat | None
    validity: Literal["candidate"]
    timestamp_us: int = Field(ge=0)
    coordinate_space: Literal["upright_video_normalized"]
    provenance: Provenance
    parents: list[str]


class AnalysisResult(StrictModel):
    schema_version: Literal[1]
    holds: list[HoldCandidate]
    provenance: Provenance
    warning: str


class JobInfo(StrictModel):
    id: str
    status: Literal["queued", "running", "succeeded", "failed", "cancelled"]
    phase: str
    completed_units: int = Field(ge=0, le=3)
    total_units: Literal[3]
    retry_count: int = Field(ge=0)
    error: str | None
    result: AnalysisResult | None


def create_app(store: Store | None = None):
    store = store or Store(Path(os.environ.get("CLIMBTRIAGE_DATA", "data")) / "analysis.sqlite")
    app = FastAPI(title="ClimbTriage local keyframe analysis", version="1.0.0")

    @app.exception_handler(Conflict)
    async def conflict(_request, error):
        return JSONResponse(status_code=409, content={"detail": str(error)})

    @app.exception_handler(Gone)
    async def gone(_request, error):
        return JSONResponse(status_code=410, content={"detail": str(error)})

    @app.get("/health")
    def health():
        return {"schema_version": 1, "mode": "local-only", "provider_configured": bool(os.getenv("FAL_KEY"))}

    @app.post("/v1/uploads", status_code=201, response_model=UploadInfo)
    def create_upload(body: Upload):
        return store.create_upload(body.sha256, body.size_bytes, body.content_type)

    @app.get("/v1/uploads/{ident}", response_model=UploadInfo)
    def upload_info(ident: str):
        return store.upload_info(ident)

    @app.patch("/v1/uploads/{ident}", response_model=UploadInfo)
    async def append(ident: str, request: Request, upload_offset: int = Header(ge=0)):
        data = bytearray()
        async for part in request.stream():
            data.extend(part)
            if len(data) > MAX_CHUNK:
                raise HTTPException(413, "Chunk exceeds 1 MiB")
        return store.append(ident, upload_offset, bytes(data))

    @app.post("/v1/jobs", status_code=202, response_model=JobInfo)
    def job(body: Job, idempotency_key: str = Header(min_length=1, max_length=128)):
        return store.new_job(body.model_dump(), idempotency_key)

    @app.get("/v1/jobs/{ident}", response_model=JobInfo)
    def get_job(ident: str):
        return store.job(ident)

    @app.post("/v1/jobs/{ident}/cancel", response_model=JobInfo)
    def cancel(ident: str):
        return store.cancel(ident)

    @app.delete("/v1/uploads/{ident}", status_code=204)
    def delete(ident: str):
        store.delete(ident)

    return app
