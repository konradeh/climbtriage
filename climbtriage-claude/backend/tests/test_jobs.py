"""Job machinery: resumable upload, idempotency, retries, cancellation, checkpoints, deletion, cache.

These use the fixture provider on purpose: they test the machinery, and they assert
that fixture output is stamped `provenance.kind == "fixture"` so it can never pass
as real inference.
"""

import hashlib

from climbtriage_backend.providers.base import ProviderError
from conftest import upload, wall_jpeg


def _job(client, uid, key="k1", **params):
    return client.post("/v1/jobs", json={"kind": "hold_candidates", "uploadId": uid, "params": params},
                       headers={"Idempotency-Key": key})


def test_health_never_echoes_secrets(env, monkeypatch):
    client, *_ = env
    monkeypatch.setenv("VLMRUN_API_KEY", "sk-secret-value")
    body = client.get("/v1/health").text
    assert "sk-secret" not in body


def test_end_to_end_fixture_job(env):
    client, worker, seg, *_ = env
    uid = upload(client, wall_jpeg())
    job = _job(client, uid).json()
    assert job["status"] == "queued" and job["stepsTotal"] == 4
    assert worker.run_once()
    out = client.get(f"/v1/jobs/{job['id']}").json()
    assert out["status"] == "succeeded" and out["step"] == 4
    result = out["result"]
    assert result["schema"] == "climbtriage.v1"
    assert len(result["holds"]) == 3
    assert all(h["provenance"]["kind"] == "fixture" for h in result["holds"])
    assert result["run"]["provenance"]["kind"] == "fixture"
    ids = {h["id"] for h in result["holds"]}
    assert len(ids) == 3 and all(i.startswith("h_") for i in ids)


def test_resumable_upload_rejects_gaps_and_resumes(env):
    client, *_ = env
    data = wall_jpeg()
    sha = hashlib.sha256(data).hexdigest()
    uid = client.post("/v1/uploads", json={"sizeBytes": len(data), "sha256": sha,
                                           "contentType": "image/jpeg"}).json()["uploadId"]
    first = client.put(f"/v1/uploads/{uid}", content=data[:500],
                       headers={"Content-Range": f"bytes 0-499/{len(data)}"})
    assert first.json()["offset"] == 500
    gap = client.put(f"/v1/uploads/{uid}", content=data[600:700],
                     headers={"Content-Range": f"bytes 600-699/{len(data)}"})
    assert gap.status_code == 409 and gap.json()["detail"]["offset"] == 500
    # "network failure": the client asks where to resume from
    assert client.get(f"/v1/uploads/{uid}").json()["offset"] == 500
    rest = client.put(f"/v1/uploads/{uid}", content=data[500:],
                      headers={"Content-Range": f"bytes 500-{len(data) - 1}/{len(data)}"})
    assert rest.json()["complete"] is True


def test_upload_hash_mismatch_is_discarded(env):
    client, *_ = env
    data = wall_jpeg()
    uid = client.post("/v1/uploads", json={"sizeBytes": len(data), "sha256": "0" * 64,
                                           "contentType": "image/jpeg"}).json()["uploadId"]
    r = client.put(f"/v1/uploads/{uid}", content=data,
                   headers={"Content-Range": f"bytes 0-{len(data) - 1}/{len(data)}"})
    assert r.status_code == 422
    assert client.get(f"/v1/uploads/{uid}").json()["state"] == "corrupt"


def test_idempotency_same_key_same_job_different_body_conflicts(env):
    client, *_ = env
    uid = upload(client, wall_jpeg())
    a = _job(client, uid, key="abc").json()
    b = _job(client, uid, key="abc").json()
    assert a["id"] == b["id"] and b["created"] is False
    c = _job(client, uid, key="abc", minScore=0.9)
    assert c.status_code == 409


def test_transient_failures_are_retried_then_bounded(env):
    client, worker, seg, store, _ = env
    seg._fail = 5                       # more failures than max_attempts (3)
    uid = upload(client, wall_jpeg())
    job = _job(client, uid).json()
    for _ in range(3):
        assert worker.run_once()
    out = client.get(f"/v1/jobs/{job['id']}").json()
    assert out["status"] == "failed" and out["attempts"] == 3
    assert "transient" in out["error"]
    assert worker.run_once() is False   # nothing left to run


def test_retry_succeeds_after_one_transient_failure(env):
    client, worker, seg, *_ = env
    seg._fail = 1
    uid = upload(client, wall_jpeg())
    job = _job(client, uid).json()
    worker.run_once()
    assert client.get(f"/v1/jobs/{job['id']}").json()["status"] == "queued"
    worker.run_once()
    out = client.get(f"/v1/jobs/{job['id']}").json()
    assert out["status"] == "succeeded" and out["attempts"] == 2


def test_checkpoint_prevents_second_provider_call(env, monkeypatch):
    client, worker, seg, store, blobs = env
    uid = upload(client, wall_jpeg())
    job = _job(client, uid).json()

    import climbtriage_backend.worker as wmod
    real = wmod.holds_from_instances
    calls = {"n": 0}

    def flaky(*a, **k):
        calls["n"] += 1
        if calls["n"] == 1:
            raise ProviderError("post-processing hiccup", transient=True)
        return real(*a, **k)

    monkeypatch.setattr(wmod, "holds_from_instances", flaky)
    worker.run_once()                   # provider called, checkpoint written, postprocess fails
    worker.run_once()                   # resumes from the checkpoint
    assert seg.calls == 1
    assert client.get(f"/v1/jobs/{job['id']}").json()["status"] == "succeeded"


def test_cancel_queued_job(env):
    client, worker, *_ = env
    uid = upload(client, wall_jpeg())
    job = _job(client, uid).json()
    assert client.post(f"/v1/jobs/{job['id']}/cancel").json()["status"] == "cancelled"
    assert worker.run_once() is False


def test_cancel_while_running_stops_between_steps(env):
    client, worker, seg, store, blobs = env
    uid = upload(client, wall_jpeg())
    job = _job(client, uid).json()
    original = seg.segment

    def cancel_mid_call(*a, **k):
        store.request_cancel(job["id"])
        return original(*a, **k)

    seg.segment = cancel_mid_call
    worker.run_once()
    out = client.get(f"/v1/jobs/{job['id']}").json()
    assert out["status"] == "cancelled" and "result" not in out


def test_delete_during_run_prevents_late_worker_from_recreating_results(env):
    client, worker, seg, store, blobs = env
    uid = upload(client, wall_jpeg())
    job = _job(client, uid).json()
    original = seg.segment

    def delete_mid_call(*a, **k):
        payload = original(*a, **k)
        assert client.delete(f"/v1/jobs/{job['id']}").status_code == 204
        return payload

    seg.segment = delete_mid_call
    worker.run_once()
    assert client.get(f"/v1/jobs/{job['id']}").status_code == 404
    assert not blobs.exists(f"jobs/{job['id']}/result.json")
    assert not blobs.exists(f"jobs/{job['id']}/provider.json")
    assert store.job(job["id"]).result_ref is None


def test_commit_refuses_after_tombstone(env):
    client, worker, seg, store, blobs = env
    uid = upload(client, wall_jpeg())
    job = _job(client, uid).json()
    claimed = store.claim(lease_s=60)
    store.tombstone(claimed.id)
    assert store.commit_result(claimed.id, "jobs/x/result.json", cache_key="k", steps_total=4) is False
    assert store.cached("k") is None


def test_cache_hit_skips_provider(env):
    client, worker, seg, *_ = env
    data = wall_jpeg()
    first = _job(client, upload(client, data), key="one").json()
    worker.run_once()
    second = _job(client, upload(client, data), key="two").json()
    worker.run_once()
    assert seg.calls == 1
    a = client.get(f"/v1/jobs/{first['id']}").json()["result"]
    b = client.get(f"/v1/jobs/{second['id']}").json()["result"]
    assert a == b


def test_missing_provider_fails_clearly(env):
    client, worker, *_ = env
    worker.segmenter = None
    uid = upload(client, wall_jpeg())
    job = _job(client, uid).json()
    worker.run_once()
    out = client.get(f"/v1/jobs/{job['id']}").json()
    assert out["status"] == "failed" and "VLMRUN_API_KEY" in out["error"]


def test_expired_lease_is_reclaimed(env):
    client, worker, seg, store, _ = env
    uid = upload(client, wall_jpeg())
    job = _job(client, uid).json()
    crashed = store.claim(lease_s=0.0)            # a worker that died right after claiming
    assert crashed.id == job["id"]
    assert worker.run_once()
    assert client.get(f"/v1/jobs/{job['id']}").json()["status"] == "succeeded"
