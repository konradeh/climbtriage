import hashlib
import time

import pytest
from fastapi.testclient import TestClient

from climbtriage.api import create_app
from climbtriage.store import Conflict, Gone, Store
from climbtriage.worker import tick


@pytest.fixture
def store(tmp_path):
    return Store(tmp_path / "test.sqlite")


def upload(store, data=b"contract-test-bytes"):
    ident = store.create_upload(hashlib.sha256(data).hexdigest(), len(data), "image/png")["id"]
    store.append(ident, 0, data)
    return ident


def request(ident):
    return {"upload_id": ident, "timestamp_us": 1234567,
            "coordinate_space": "upright_video_normalized", "consent_to_provider": True}


def test_upload_resume_hash_and_offset_are_atomic(store):
    data = b"abcdef"
    ident = store.create_upload(hashlib.sha256(data).hexdigest(), 6, "image/png")["id"]
    store.append(ident, 0, b"abc")
    reopened = Store(store.path)
    assert reopened.upload_info(ident)["offset"] == 3
    with pytest.raises(Conflict):
        reopened.append(ident, 0, b"abc")
    with pytest.raises(Conflict):
        reopened.append(ident, 3, b"xxx")
    assert reopened.upload_info(ident)["offset"] == 3
    assert reopened.append(ident, 3, b"def")["complete"]


def test_idempotency_and_configuration_conflicts(store):
    body = request(upload(store))
    first = store.new_job(body, "once")
    assert store.new_job(body, "once")["id"] == first["id"]
    with pytest.raises(Conflict):
        store.new_job(dict(body, timestamp_us=1), "once")


def test_deleted_upload_cannot_be_resurrected_by_worker(store):
    ident = upload(store)
    job = store.new_job(request(ident), "once")
    claim = store.claim()
    store.delete(ident)
    assert not store.finish(claim, {"must": "not appear"})
    assert not store.checkpoint(claim, {"remote": "id"}, "waiting", 1)
    with pytest.raises(Gone):
        store.job(job["id"])
    with pytest.raises(Gone):
        store.new_job(request(ident), "new-key")
    with store.db() as db:
        assert db.execute("SELECT data FROM uploads").fetchone()[0] == b""
        assert db.execute("SELECT result FROM jobs").fetchone()[0] is None


def test_cancel_and_expired_lease_fence_old_worker(store):
    job = store.new_job(request(upload(store)), "once")
    old = store.claim()
    assert store.claim() is None
    with store.db() as db:
        db.execute("UPDATE jobs SET lease_until=?", (time.time() - 1,))
    fresh = Store(store.path).claim()
    assert old["token"] != fresh["token"]
    assert not store.finish(old, {})
    store.cancel(job["id"])
    assert not store.finish(fresh, {})
    assert store.job(job["id"])["status"] == "cancelled"


def test_bounded_retries(store):
    job = store.new_job(request(upload(store)), "once")
    for _ in range(3):
        claim = store.claim()
        store.fail(claim, "network", retryable=True)
        with store.db() as db:
            db.execute("UPDATE jobs SET next_at=0")
    assert store.job(job["id"])["status"] == "failed"
    assert store.claim() is None


def test_worker_resumes_provider_checkpoint_without_resubmission(store):
    # Fake proves only worker orchestration, never real model quality.
    class ContractProvider:
        submissions = 0

        def submit(self, *_):
            ContractProvider.submissions += 1
            return {"request_id": "contract-fixture"}

        def status(self, checkpoint):
            assert checkpoint["request_id"] == "contract-fixture"
            return "COMPLETED"

        def result(self, checkpoint, image, timestamp):
            return {"schema_version": 1, "holds": [], "timestamp_us": timestamp}

    job = store.new_job(request(upload(store)), "once")
    tick(store, ContractProvider)
    with store.db() as db:
        db.execute("UPDATE jobs SET next_at=0")
    tick(Store(store.path), ContractProvider)
    assert ContractProvider.submissions == 1
    assert store.job(job["id"])["result"]["holds"] == []


def test_api_consent_required_and_real_progress(store):
    client = TestClient(create_app(store))
    body = request(upload(store))
    assert client.post("/v1/jobs", json=dict(body, consent_to_provider=False), headers={"Idempotency-Key": "x"}).status_code == 422
    response = client.post("/v1/jobs", json=body, headers={"Idempotency-Key": "x"})
    assert response.status_code == 202
    job = response.json()
    assert job["completed_units"] == 0
    assert job["result"] is None
    assert client.post(f"/v1/jobs/{job['id']}/cancel").json()["status"] == "cancelled"


def test_missing_credential_fails_without_detections(store, monkeypatch):
    monkeypatch.delenv("FAL_KEY", raising=False)
    job = store.new_job(request(upload(store)), "no-key")
    tick(store)
    result = store.job(job["id"])
    assert result["status"] == "failed"
    assert "FAL_KEY" in result["error"]
    assert result["result"] is None


def test_worker_does_not_run_new_config_under_an_old_cache_key(store):
    job = store.new_job(request(upload(store)), "old-config")
    with store.db() as db:
        db.execute("UPDATE jobs SET configuration='{}'")
    tick(store)
    assert store.job(job["id"])["status"] == "failed"
    assert "configuration" in store.job(job["id"])["error"]
