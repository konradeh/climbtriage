import hashlib

from fastapi.testclient import TestClient

from climbtriage.api import create_app
from climbtriage.store import Store
from climbtriage.worker import tick


def test_http_resumable_upload_job_delete_lifecycle(tmp_path):
    store = Store(tmp_path / "test.sqlite")
    client = TestClient(create_app(store))
    data = b"synthetic contract fixture, not inference footage"
    created = client.post("/v1/uploads", json={"sha256": hashlib.sha256(data).hexdigest(), "size_bytes": len(data), "content_type": "image/png"})
    assert created.status_code == 201
    ident = created.json()["id"]
    path = f"/v1/uploads/{ident}"
    assert client.patch(path, content=data[:10], headers={"Upload-Offset": "0"}).json()["offset"] == 10
    assert client.patch(path, content=data[10:], headers={"Upload-Offset": "0"}).status_code == 409
    assert client.patch(path, content=data[10:], headers={"Upload-Offset": "10"}).json()["complete"]
    body = {"upload_id": ident, "timestamp_us": 777777, "coordinate_space": "upright_video_normalized", "consent_to_provider": True}
    job = client.post("/v1/jobs", json=body, headers={"Idempotency-Key": "key"}).json()
    assert client.post("/v1/jobs", json=body, headers={"Idempotency-Key": "key"}).json()["id"] == job["id"]
    claimed = store.claim()
    assert client.delete(path).status_code == 204
    assert not store.finish(claimed, {"holds": []})
    assert client.get(f"/v1/jobs/{job['id']}").status_code == 410
    assert client.get(path).status_code == 410


def test_http_rejects_oversized_chunk_and_unknown_fields(tmp_path):
    client = TestClient(create_app(Store(tmp_path / "test.sqlite")))
    payload = {"sha256": "0" * 64, "size_bytes": 2_000_000, "content_type": "image/png"}
    assert client.post("/v1/uploads", json=dict(payload, provider_key="forbidden")).status_code == 422
    ident = client.post("/v1/uploads", json=payload).json()["id"]
    assert client.patch(f"/v1/uploads/{ident}", content=b"0" * (1024 * 1024 + 1), headers={"Upload-Offset": "0"}).status_code == 413


def test_unexpected_adapter_failure_does_not_loop_forever(tmp_path):
    store = Store(tmp_path / "test.sqlite")
    data = b"x"
    upload = store.create_upload(hashlib.sha256(data).hexdigest(), 1, "image/png")["id"]
    store.append(upload, 0, data)
    job = store.new_job({"upload_id": upload, "timestamp_us": 0, "consent_to_provider": True}, "key")

    class BrokenAdapter:
        def submit(self, *_):
            raise RuntimeError("sensitive diagnostics must not leak")

    tick(store, BrokenAdapter)
    assert store.job(job["id"])["status"] == "failed"
    assert "sensitive" not in store.job(job["id"])["error"]
    assert store.claim() is None
