"""VLM Run gateway adapters: SAM 3.1 image segmentation (holds) and ViTPose+ (pose baseline).

Adapted from vision-demos (Apache-2.0, commit 6e45309f9ab7f9b7b8707b128bb84294317609e8):
  rock_climbing/src/holds.py   segment_frames, _decode_label_map, instance_mask, unwrap
  rock_climbing/src/recover.py _segment_box
  rock_climbing/src/pose.py    request_poses, unwrap
The request/response contract is the gateway's OpenAI-compatible chat-completions API
with the method selected in the body (the OpenAI SDK's `extra_body` merges into the
top level), verified against docs.vlm.run on 2026-09-28. Reimplemented on httpx so the
backend does not depend on the OpenAI SDK, and so transient failures are classified
for bounded retries.

The API key is read from the server environment only. It must never reach the phone.
"""

from __future__ import annotations

import base64
import json

import cv2
import httpx
import numpy as np

from .base import Instance, ProviderError, SegmentationResult

GATEWAY_BASE_URL = "https://gateway.vlm.run/v1/openai"
SAM_MODEL = "facebook/sam3.1"
VITPOSE_MODEL = "usyd-community/vitpose-plus-large"

CONTENT_IMAGE_MASKS = "img.segment.masks"
CONTENT_VIDEO_KPTS = "vid.pose.kpts"

COCO17 = [
    "nose", "left_eye", "right_eye", "left_ear", "right_ear",
    "left_shoulder", "right_shoulder", "left_elbow", "right_elbow",
    "left_wrist", "right_wrist", "left_hip", "right_hip",
    "left_knee", "right_knee", "left_ankle", "right_ankle",
]


def _post(client: httpx.Client, api_key: str, body: dict) -> tuple[dict, dict | None]:
    try:
        response = client.post("/chat/completions", json=body,
                               headers={"Authorization": f"Bearer {api_key}"})
    except (httpx.TimeoutException, httpx.NetworkError) as exc:
        raise ProviderError(f"gateway unreachable: {type(exc).__name__}", transient=True) from exc
    if response.status_code == 429 or response.status_code >= 500:
        raise ProviderError(f"gateway HTTP {response.status_code}", transient=True)
    if response.status_code >= 400:
        # 4xx other than 429 will not succeed on retry; include a bounded snippet, never headers.
        raise ProviderError(f"gateway HTTP {response.status_code}: {response.text[:300]}", transient=False)
    try:
        envelope = response.json()
        payload = json.loads(envelope["choices"][0]["message"]["content"])
    except (ValueError, KeyError, IndexError, TypeError) as exc:
        raise ProviderError(f"unexpected gateway envelope: {response.text[:300]}", transient=False) from exc
    return payload, envelope.get("usage")


def decode_label_map(mask: dict | None) -> np.ndarray | None:
    """A label map as uint8: pixel = instance id (1..255), 0 = background."""
    if not isinstance(mask, dict) or mask.get("format") != "png":
        return None
    data = mask.get("data")
    if not isinstance(data, str):
        return None
    raw = base64.b64decode(data.split(",", 1)[-1])
    labels = cv2.imdecode(np.frombuffer(raw, dtype=np.uint8), cv2.IMREAD_UNCHANGED)
    if labels is None:
        return None
    if labels.ndim == 3:
        labels = labels[:, :, 0]
    return labels.astype(np.uint8)


def image_content(payload: dict) -> dict:
    content = payload.get("content")
    if not isinstance(content, dict):
        raise ProviderError(f"unexpected payload: {json.dumps(payload)[:300]}", transient=False)
    if content.get("object") != CONTENT_IMAGE_MASKS:
        raise ProviderError(f"expected {CONTENT_IMAGE_MASKS}, got {content.get('object')!r}", transient=False)
    return content


def instances_from_payload(payload: dict, *, label: str, width: int, height: int) -> list[Instance]:
    """Cut each item's boolean mask out of the shared label map (pixel == instance_id)."""
    content = image_content(payload)
    labels = decode_label_map(content.get("mask"))
    out: list[Instance] = []
    if labels is None:
        return out
    if labels.shape != (height, width):
        labels = cv2.resize(labels, (width, height), interpolation=cv2.INTER_NEAREST)
    for item in content.get("items") or []:
        instance_id = item.get("instance_id")
        if instance_id is None:
            continue
        mask = labels == int(instance_id)
        if not mask.any():
            continue
        score = item.get("score")
        out.append(Instance(mask=mask, score=None if score is None else float(score), label=label))
    return out


class VlmRunSam31Segmenter:
    provider = "vlmrun-gateway"
    model = SAM_MODEL
    provenance_kind = "model"

    def __init__(self, api_key: str, *, base_url: str = GATEWAY_BASE_URL, timeout_s: float = 120.0):
        if not api_key:
            raise ValueError("VLMRUN_API_KEY is not configured")
        self._api_key = api_key
        self._client = httpx.Client(base_url=base_url, timeout=timeout_s)

    def _body(self, image_jpeg: bytes, method: str, params: dict) -> dict:
        b64 = base64.b64encode(image_jpeg).decode("ascii")
        return {
            "model": self.model,
            "messages": [{"role": "user", "content": [
                {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{b64}"}}]}],
            "response_format": {"type": "json_object"},
            "method": method,
            "method_params": params,
        }

    def segment(self, image_jpeg: bytes, prompts: list[str]) -> dict:
        raw: dict = {"payloads": {}, "usage": []}
        for prompt in prompts:
            payload, usage = _post(self._client, self._api_key, self._body(
                image_jpeg, "segment", {"prompt": prompt, "mask_format": "png"}))
            image_content(payload)          # validate the tag before it is checkpointed as good
            raw["payloads"][prompt] = payload
            if usage:
                raw["usage"].append(usage)
        return raw

    def segment_box(self, image_jpeg: bytes, bbox_xywh_norm: list[float]) -> dict:
        """Box-prompted segmentation (no colour opinion); used for missed-hold recovery (M3)."""
        payload, usage = _post(self._client, self._api_key, self._body(
            image_jpeg, "segment_box", {"bbox_xywh": [float(v) for v in bbox_xywh_norm],
                                        "mask_format": "png"}))
        return {"payloads": {"<box>": payload}, "usage": [usage] if usage else []}

    def parse(self, raw: dict, width: int, height: int) -> SegmentationResult:
        instances: list[Instance] = []
        for prompt, payload in (raw.get("payloads") or {}).items():
            instances.extend(instances_from_payload(payload, label=prompt, width=width, height=height))
        return SegmentationResult(width=width, height=height, instances=instances, raw=raw,
                                  usage=raw.get("usage") or [])


def parse_vitpose_video(payload: dict) -> tuple[list[dict], list[int]]:
    """ViTPose+ video payload → (person records, sampled frame ids). Joint order is checked.

    Adapter for the offline pose baseline (docs/07). Records keep the gateway's own
    `track_id`; ClimbTriage does not trust it as identity (see docs/02, PersonTracker).
    """
    content = payload.get("content")
    if not isinstance(content, dict) or content.get("object") != CONTENT_VIDEO_KPTS:
        raise ProviderError(f"expected {CONTENT_VIDEO_KPTS} payload", transient=False)
    labels = content.get("kpts_labels")
    if labels is not None and list(labels) != COCO17:
        raise ProviderError(f"unexpected joint layout: {labels}", transient=False)
    frames = [int(f["frame_id"]) for f in content.get("frames") or []]
    records = []
    for item in content.get("items") or []:
        kpts = item.get("kpts_xy") or []
        scores = item.get("kpts_score") or []
        landmarks = []
        for i, name in enumerate(COCO17):
            if i >= len(kpts):
                break
            x, y = float(kpts[i][0]), float(kpts[i][1])
            if x == 0.0 and y == 0.0:          # the model's "not visible" sentinel, not a point
                continue
            score = scores[i] if i < len(scores) else None
            landmarks.append({"name": name, "x": x, "y": y,
                              "visibility": None if score is None else float(score)})
        records.append({"frameId": int(item.get("frame_id", -1)), "gatewayTrackId": item.get("track_id"),
                        "bbox": item.get("bbox_xywh"), "landmarks": landmarks})
    return records, frames
