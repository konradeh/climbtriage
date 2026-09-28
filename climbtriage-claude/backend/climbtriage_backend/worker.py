"""The analysis worker: a separate process that claims jobs and runs them step by step.

Run: `python -m climbtriage_backend.worker`. Steps report real completion, the provider
payload is checkpointed before post-processing (a retry never pays twice), transient
provider errors are retried with bounded exponential backoff, cancellation is checked
between steps, and the result only becomes visible through `Store.commit_result`, which
refuses if the job was deleted meanwhile.
"""

from __future__ import annotations

import datetime as dt
import json
import logging
import time

import cv2
import numpy as np

from .blobs import LocalBlobStore
from .contracts import AnalysisRun, HoldCandidatesResult, Provenance, new_id
from .pipeline import holds_from_instances
from .providers.base import HoldSegmenter, ProviderError
from .settings import Settings
from .store import Job, Store

log = logging.getLogger("climbtriage.worker")

HOLD_STEPS = ["load_input", "provider_call", "postprocess", "commit"]


def upload_key(upload_id: str) -> str:
    return f"uploads/{upload_id}.bin"


def job_prefix(job_id: str) -> str:
    return f"jobs/{job_id}"


def _now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")


class Cancelled(Exception):
    pass


class Worker:
    def __init__(self, store: Store, blobs: LocalBlobStore, segmenter: HoldSegmenter | None, settings: Settings):
        self.store, self.blobs, self.segmenter, self.settings = store, blobs, segmenter, settings

    def _step(self, job: Job, index: int) -> None:
        if not self.store.progress(job.id, index, HOLD_STEPS[index - 1] if index else "claimed",
                                   lease_s=self.settings.lease_s):
            raise Cancelled()

    def run_once(self) -> bool:
        """Claim and process one job. Returns False when the queue had nothing runnable."""
        job = self.store.claim(lease_s=self.settings.lease_s)
        if job is None:
            return False
        try:
            self._run_holds(job)
        except Cancelled:
            self.store.finish(job.id, "cancelled")
            self.blobs.delete_prefix(job_prefix(job.id))
        except ProviderError as exc:
            if exc.transient and job.attempts < job.max_attempts:
                delay = self.settings.backoff_base_s * (2 ** (job.attempts - 1))
                log.warning("job %s transient failure (%s); retry %d/%d in %.1fs",
                            job.id, exc, job.attempts, job.max_attempts, delay)
                self.store.retry_later(job.id, str(exc), delay)
            else:
                self.store.finish(job.id, "failed", error=str(exc))
        except Exception as exc:        # a bug: fail loudly, never retry forever
            log.exception("job %s crashed", job.id)
            self.store.finish(job.id, "failed", error=f"internal error: {type(exc).__name__}")
        return True

    def _run_holds(self, job: Job) -> None:
        # Step 1: input.
        cached = self.store.cached(job.cache_key)
        image_bytes = self.blobs.get(upload_key(job.upload_id))
        image = cv2.imdecode(np.frombuffer(image_bytes, np.uint8), cv2.IMREAD_COLOR)
        if image is None:
            raise ProviderError("upload is not a decodable image", transient=False)
        self._step(job, 1)

        result_key = f"{job_prefix(job.id)}/result.json"
        if cached and self.blobs.exists(cached):
            # Same input bytes, provider, model, config and schema: reuse, no provider call.
            self.blobs.copy(cached, result_key)
            self._step(job, 2)
            self._step(job, 3)
            if not self.store.commit_result(job.id, result_key, cache_key=None, steps_total=len(HOLD_STEPS)):
                self.blobs.delete(result_key)
            return

        # Step 2: provider call, checkpointed.
        if self.segmenter is None:
            raise ProviderError("no hold segmentation provider is configured on the server "
                                "(set VLMRUN_API_KEY)", transient=False)
        checkpoint = f"{job_prefix(job.id)}/provider.json"
        started = _now()
        if self.blobs.exists(checkpoint):
            raw = json.loads(self.blobs.get(checkpoint))
        else:
            raw = self.segmenter.segment(image_bytes, job.params["prompts"])
            self.blobs.put(checkpoint, json.dumps(raw).encode())
        self._step(job, 2)

        # Step 3: post-process (pure; re-runnable from the checkpoint).
        h, w = image.shape[:2]
        seg = self.segmenter.parse(raw, w, h)
        run_id = new_id("run")
        provenance = Provenance(kind=self.segmenter.provenance_kind, name=self.segmenter.model,
                                version="gateway" if self.segmenter.provenance_kind == "model" else "test",
                                runId=run_id, configHash=job.cache_key[:16])
        holds = holds_from_instances(seg.instances, image, provenance=provenance,
                                     min_score=float(job.params.get("minScore", 0.3)))
        run = AnalysisRun(id=run_id, kind="hold_candidates", inputSha256=job.cache_key.split(":")[0],
                          provider=self.segmenter.provider, model=self.segmenter.model,
                          configHash=job.cache_key[:16], startedAt=started, finishedAt=_now(),
                          status="succeeded", outputRef=result_key, provenance=provenance)
        result = HoldCandidatesResult(run=run, imageWidthPx=w, imageHeightPx=h, holds=holds)
        self.blobs.put(result_key, json.dumps(result.dump()).encode())
        self._step(job, 3)

        # Step 4: commit, guarded by the tombstone.
        if not self.store.commit_result(job.id, result_key, cache_key=job.cache_key, steps_total=len(HOLD_STEPS)):
            self.blobs.delete_prefix(job_prefix(job.id))


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    from .providers import segmenter_from_env
    settings = Settings.from_env()          # also loads ./.env before providers read the environment
    store = Store(settings.db_path)
    blobs = LocalBlobStore(settings.blob_root)
    segmenter = segmenter_from_env()
    if segmenter is None:
        log.warning("no provider configured: hold jobs will fail with a clear error (set VLMRUN_API_KEY)")
    worker = Worker(store, blobs, segmenter, settings)
    log.info("worker started (db=%s)", settings.db_path)
    while True:
        if not worker.run_once():
            time.sleep(settings.poll_s)


if __name__ == "__main__":
    main()
