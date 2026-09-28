"""A deterministic test double. Its output is stamped provenance.kind = "fixture".

It exists so the job machinery can be exercised end to end without credentials. It
is only constructed when CLIMBTRIAGE_PROVIDER=fixture, and nothing it returns may be
presented as real inference: the app shows fixture provenance as a warning.
"""

from __future__ import annotations

import base64

import cv2
import numpy as np

from .base import ProviderError, SegmentationResult
from .vlmrun import instances_from_payload


def _png_b64(labels: np.ndarray) -> str:
    ok, buf = cv2.imencode(".png", labels)
    assert ok
    return base64.b64encode(buf.tobytes()).decode("ascii")


class FixtureSegmenter:
    provider = "fixture"
    model = "fixture-blobs"
    provenance_kind = "fixture"

    def __init__(self, *, fail_transient_times: int = 0):
        self.calls = 0
        self._fail = fail_transient_times

    def segment(self, image_jpeg: bytes, prompts: list[str]) -> dict:
        self.calls += 1
        if self._fail > 0:
            self._fail -= 1
            raise ProviderError("fixture transient failure", transient=True)
        image = cv2.imdecode(np.frombuffer(image_jpeg, np.uint8), cv2.IMREAD_COLOR)
        if image is None:
            raise ProviderError("not a decodable image", transient=False)
        h, w = image.shape[:2]
        labels = np.zeros((h, w), np.uint8)
        items = []
        # Saturated blobs stand in for holds: good enough to drive the pipeline, useless as a model.
        hsv = cv2.cvtColor(image, cv2.COLOR_BGR2HSV)
        blobs = ((hsv[:, :, 1] > 120) & (hsv[:, :, 2] > 60)).astype(np.uint8)
        n, comp, stats, _ = cv2.connectedComponentsWithStats(blobs)
        next_id = 1
        for i in range(1, n):
            if stats[i, cv2.CC_STAT_AREA] < 30 or next_id > 255:
                continue
            labels[comp == i] = next_id
            x, y, bw, bh = stats[i, :4]
            items.append({"instance_id": next_id, "score": 0.5,
                          "bbox_xywh": [x / w, y / h, bw / w, bh / h]})
            next_id += 1
        payload = {"content": {"object": "img.segment.masks", "items": items,
                               "mask": {"format": "png", "data": _png_b64(labels)}}}
        return {"payloads": {prompts[0]: payload}, "usage": []}

    def parse(self, raw: dict, width: int, height: int) -> SegmentationResult:
        instances = []
        for prompt, payload in raw["payloads"].items():
            instances.extend(instances_from_payload(payload, label=prompt, width=width, height=height))
        return SegmentationResult(width=width, height=height, instances=instances, raw=raw)
