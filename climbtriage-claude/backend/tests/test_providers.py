"""Gateway adapter parsing against the documented envelope, and HTTP error classification.

The envelopes here are constructed from the gateway documentation and the reference
implementation's parser. They verify *our parsing*, not the live service.
"""

import base64
import json

import cv2
import httpx
import numpy as np
import pytest

from climbtriage_backend.pipeline import holds_from_instances, suppress_contained
from climbtriage_backend.contracts import Provenance
from climbtriage_backend.providers.base import Instance, ProviderError
from climbtriage_backend.providers.vlmrun import (VlmRunSam31Segmenter, instances_from_payload,
                                                  parse_vitpose_video, COCO17)


def _payload(labels: np.ndarray, items: list[dict]) -> dict:
    ok, buf = cv2.imencode(".png", labels)
    return {"content": {"object": "img.segment.masks", "items": items,
                        "mask": {"format": "png", "data": base64.b64encode(buf.tobytes()).decode()}}}


def test_instances_cut_from_label_map():
    labels = np.zeros((50, 80), np.uint8)
    labels[10:20, 10:20] = 1
    labels[30:40, 50:70] = 2
    inst = instances_from_payload(_payload(labels, [{"instance_id": 1, "score": 0.9},
                                                    {"instance_id": 2, "score": 0.4},
                                                    {"instance_id": 7, "score": 0.9}]),
                                  label="climbing hold", width=80, height=50)
    assert [i.mask.sum() for i in inst] == [100, 200]      # id 7 has no pixels → dropped
    assert inst[0].score == 0.9


def test_wrong_object_tag_is_rejected():
    with pytest.raises(ProviderError):
        instances_from_payload({"content": {"object": "vid.segment.masks"}}, label="x", width=1, height=1)


def test_containment_suppression_is_per_kind():
    big = np.zeros((40, 40), bool); big[5:35, 5:35] = True
    small = np.zeros((40, 40), bool); small[10:15, 10:15] = True
    kept = suppress_contained([Instance(big, 0.9, "climbing hold"), Instance(small, 0.5, "climbing hold")])
    assert len(kept) == 1
    # A hold on a volume is legitimately inside the volume's mask.
    kept = suppress_contained([Instance(big, 0.9, "climbing volume"), Instance(small, 0.5, "climbing hold")])
    assert len(kept) == 2


def test_holds_have_normalised_polygons_and_colour():
    img = np.full((100, 200, 3), 120, np.uint8)
    img[20:60, 40:100] = (40, 180, 40)                     # BGR green
    mask = np.zeros((100, 200), bool); mask[20:60, 40:100] = True
    prov = Provenance(kind="model", name="facebook/sam3.1", version="gateway")
    holds = holds_from_instances([Instance(mask, 0.8, "climbing hold")], img, provenance=prov, min_score=0.3)
    assert len(holds) == 1
    h = holds[0]
    assert h.coords == "frame_norm" and h.kind == "hold"
    xs = [p[0] for p in h.polygon]; ys = [p[1] for p in h.polygon]
    assert 0.19 <= min(xs) <= 0.21 and 0.49 <= max(xs) <= 0.51
    assert 0.19 <= min(ys) <= 0.21 and 0.58 <= max(ys) <= 0.60
    assert h.color is not None and h.color.lab[1] < -20     # green: negative a*
    low = holds_from_instances([Instance(mask, 0.1, "climbing hold")], img, provenance=prov, min_score=0.3)
    assert low == []


def _transport(status: int, body: dict | str):
    def handler(request: httpx.Request):
        sent = json.loads(request.content)
        assert sent["method"] == "segment" and sent["model"] == "facebook/sam3.1"
        assert request.headers["Authorization"] == "Bearer test-key"
        text = body if isinstance(body, str) else json.dumps(body)
        return httpx.Response(status, text=text)
    return httpx.MockTransport(handler)


def _seg(transport):
    s = VlmRunSam31Segmenter("test-key", base_url="https://gw.example/v1/openai")
    s._client = httpx.Client(base_url="https://gw.example/v1/openai", transport=transport)
    return s


def test_request_shape_and_success():
    labels = np.zeros((10, 10), np.uint8); labels[2:5, 2:5] = 1
    envelope = {"choices": [{"message": {"content": json.dumps(_payload(labels, [{"instance_id": 1, "score": 1}]))}}],
                "usage": {"total_tokens": 1}}
    raw = _seg(_transport(200, envelope)).segment(b"jpeg", ["climbing hold"])
    assert "climbing hold" in raw["payloads"] and raw["usage"]


@pytest.mark.parametrize("status,transient", [(429, True), (503, True), (400, False), (401, False)])
def test_http_errors_are_classified(status, transient):
    with pytest.raises(ProviderError) as info:
        _seg(_transport(status, "nope")).segment(b"jpeg", ["climbing hold"])
    assert info.value.transient is transient


def test_vitpose_parser_checks_joint_order_and_drops_sentinels():
    kpts = [[0.5, 0.5]] * 17
    kpts[15] = [0.0, 0.0]
    payload = {"content": {"object": "vid.pose.kpts", "kpts_labels": COCO17,
                           "frames": [{"frame_id": 0}],
                           "items": [{"frame_id": 0, "track_id": 3, "bbox_xywh": [0, 0, 1, 1],
                                      "kpts_xy": kpts, "kpts_score": [0.9] * 17}]}}
    records, frames = parse_vitpose_video(payload)
    assert frames == [0]
    names = [lm["name"] for lm in records[0]["landmarks"]]
    assert "left_ankle" not in names and len(names) == 16
    bad = json.loads(json.dumps(payload)); bad["content"]["kpts_labels"] = list(reversed(COCO17))
    with pytest.raises(ProviderError):
        parse_vitpose_video(bad)
