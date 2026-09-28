import hashlib
import sys
from pathlib import Path

import cv2
import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from climbtriage_backend.api import create_app  # noqa: E402
from climbtriage_backend.blobs import LocalBlobStore  # noqa: E402
from climbtriage_backend.providers.fixture import FixtureSegmenter  # noqa: E402
from climbtriage_backend.settings import Settings  # noqa: E402
from climbtriage_backend.store import Store  # noqa: E402
from climbtriage_backend.worker import Worker  # noqa: E402

REPO = Path(__file__).resolve().parents[2]


def wall_jpeg() -> bytes:
    """A synthetic wall: grey background, three saturated blobs. Tests the pipeline, not a model."""
    img = np.full((240, 320, 3), 128, np.uint8)
    cv2.circle(img, (60, 180), 14, (40, 180, 40), -1)
    cv2.circle(img, (160, 110), 12, (40, 180, 40), -1)
    cv2.rectangle(img, (240, 30), (270, 55), (30, 30, 200), -1)
    ok, buf = cv2.imencode(".jpg", img, [cv2.IMWRITE_JPEG_QUALITY, 95])
    assert ok
    return buf.tobytes()


@pytest.fixture
def env(tmp_path):
    settings = Settings(data_dir=tmp_path, backoff_base_s=0.0, lease_s=60.0)
    from fastapi.testclient import TestClient
    app = create_app(settings, provider_configured=True, provider_id="fixture:fixture-blobs")
    client = TestClient(app)
    seg = FixtureSegmenter()
    worker = Worker(app.state.store, app.state.blobs, seg, settings)
    return client, worker, seg, app.state.store, app.state.blobs


def upload(client, data: bytes, chunk: int = 1000) -> str:
    sha = hashlib.sha256(data).hexdigest()
    r = client.post("/v1/uploads", json={"sizeBytes": len(data), "sha256": sha, "contentType": "image/jpeg"})
    assert r.status_code == 201, r.text
    uid = r.json()["uploadId"]
    for start in range(0, len(data), chunk):
        end = min(start + chunk, len(data)) - 1
        r = client.put(f"/v1/uploads/{uid}", content=data[start:end + 1],
                       headers={"Content-Range": f"bytes {start}-{end}/{len(data)}"})
        assert r.status_code == 200, r.text
    return uid
