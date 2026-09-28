"""Verified documented fal SAM 3 image/queue contract; no synthetic fallback."""
from __future__ import annotations

import base64
import hashlib
import json
import math
import os
import struct
import uuid
from typing import Protocol
from urllib.parse import urlparse

import cv2
import httpx
import numpy as np

from .store import CONFIG


class ProviderContractError(Exception):
    pass


class HoldProvider(Protocol):
    def submit(self, image: bytes, mime: str) -> dict: ...
    def status(self, checkpoint: dict) -> str: ...
    def result(self, checkpoint: dict, image: bytes, timestamp_us: int) -> dict: ...


def image_dimensions(data: bytes):
    """Bound decoded allocation before passing untrusted compressed bytes to OpenCV."""
    if data.startswith(b"\x89PNG\r\n\x1a\n") and len(data) >= 24 and data[12:16] == b"IHDR":
        width, height = struct.unpack(">II", data[16:24])
    elif data.startswith(b"\xff\xd8"):
        offset = 2
        width = height = 0
        while offset + 4 <= len(data):
            if data[offset] != 0xFF:
                break
            while offset < len(data) and data[offset] == 0xFF:
                offset += 1
            if offset >= len(data):
                break
            marker = data[offset]
            offset += 1
            if marker in (0xD8, 0xD9) or 0xD0 <= marker <= 0xD7:
                continue
            if marker == 0xDA or offset + 2 > len(data):
                break
            length = int.from_bytes(data[offset:offset + 2], "big")
            if length < 2 or offset + length > len(data):
                break
            if marker in (0xC0, 0xC1, 0xC2):
                if length < 8:
                    break
                height, width = struct.unpack(">HH", data[offset + 3:offset + 7])
                break
            offset += length
    else:
        raise ProviderContractError("Expected JPEG or PNG image")
    if width <= 0 or height <= 0 or width * height > 8_000_000:
        raise ProviderContractError("Invalid image or keyframe exceeds 8 megapixels")
    return width, height


def decode_image(data: bytes):
    image_dimensions(data)
    image = cv2.imdecode(np.frombuffer(data, np.uint8), cv2.IMREAD_UNCHANGED)
    if image is None or image.shape[0] * image.shape[1] > 8_000_000:
        raise ProviderContractError("Invalid image or keyframe exceeds 8 megapixels")
    return image


def mask_polygons(mask_bytes: bytes, width: int, height: int):
    mask = decode_image(mask_bytes)
    if mask.shape[:2] != (height, width):
        raise ProviderContractError("Mask size differs from input; adapter needs a verified transform")
    if mask.ndim == 3:
        if not np.array_equal(mask[:, :, 0], mask[:, :, 1]) or not np.array_equal(mask[:, :, 0], mask[:, :, 2]):
            raise ProviderContractError("Expected binary masks, received colored image")
        mask = mask[:, :, 0]
    values = np.unique(mask)
    if not set(values.tolist()).issubset({0, 1, 255}):
        raise ProviderContractError("Non-binary mask; refusing to guess a threshold")
    binary = (mask > 0).astype(np.uint8)
    contours, _ = cv2.findContours(binary, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    parts = []
    for contour in contours:
        if cv2.contourArea(contour) < 4:
            continue
        points = cv2.approxPolyDP(contour, .75, True).reshape(-1, 2)
        if len(points) >= 3:
            parts.append([[float(x) / width, float(y) / height] for x, y in points])
    return parts


class FalSam3:
    endpoint = "https://queue.fal.run/fal-ai/sam-3/image"

    def __init__(self):
        key = os.getenv("FAL_KEY")
        if not key:
            raise ProviderContractError("FAL_KEY is missing on the server. Manual holds remain available.")
        self.client = httpx.Client(timeout=25, headers={"Authorization": f"Key {key}"}, follow_redirects=False)

    def close(self):
        self.client.close()

    @staticmethod
    def queue_url(url):
        parsed = urlparse(url)
        if parsed.scheme != "https" or parsed.netloc != "queue.fal.run" or not parsed.path.startswith("/fal-ai/sam-3/"):
            raise ProviderContractError("Unexpected provider queue URL")
        return url

    def submit(self, image, mime):
        decode_image(image)
        response = self.client.post(self.endpoint, json={
            "image_url": f"data:{mime};base64," + base64.b64encode(image).decode(),
            "prompt": CONFIG["prompt"], "apply_mask": False, "output_format": "png",
            "return_multiple_masks": True, "max_masks": CONFIG["max_masks"], "include_scores": True,
        })
        response.raise_for_status()
        obj = response.json()
        for field in ("status_url", "response_url", "cancel_url"):
            self.queue_url(obj[field])
        return {field: obj[field] for field in ("request_id", "status_url", "response_url", "cancel_url")}

    def status(self, checkpoint):
        response = self.client.get(self.queue_url(checkpoint["status_url"]))
        response.raise_for_status()
        obj = response.json()
        if obj.get("error"):
            raise ProviderContractError("Provider returned a failed request; inspect provider dashboard")
        status = obj.get("status")
        if status not in ("IN_QUEUE", "IN_PROGRESS", "COMPLETED"):
            raise ProviderContractError("Unknown provider queue status")
        return status

    @staticmethod
    def download_mask(url):
        if url.startswith("data:image/png;base64,"):
            if len(url) > 24 * 1024 * 1024:
                raise ProviderContractError("Mask too large")
            return base64.b64decode(url.split(",", 1)[1], validate=True)
        parsed = urlparse(url)
        host = parsed.hostname or ""
        if parsed.scheme != "https" or parsed.port not in (None, 443) or parsed.username or not (host == "fal.media" or host.endswith(".fal.media")):
            raise ProviderContractError("Unexpected mask host; update allowlist only after verification")
        # Separate client: never forward the API key to a media host.
        with httpx.stream("GET", url, timeout=25, follow_redirects=False) as response:
            response.raise_for_status()
            out = bytearray()
            for chunk in response.iter_bytes():
                out.extend(chunk)
                if len(out) > 16 * 1024 * 1024:
                    raise ProviderContractError("Mask download too large")
            return bytes(out)

    def result(self, checkpoint, image, timestamp_us):
        response = self.client.get(self.queue_url(checkpoint["response_url"]))
        response.raise_for_status()
        raw = response.json()
        masks = raw.get("masks")
        if not isinstance(masks, list) or len(masks) > CONFIG["max_masks"]:
            raise ProviderContractError("Missing/invalid masks array")
        scores = raw.get("scores")
        if scores is not None and (not isinstance(scores, list) or len(scores) != len(masks)):
            raise ProviderContractError("Mask/score length mismatch")
        height, width = decode_image(image).shape[:2]
        provenance = {"provider": "fal", "model": CONFIG["model"], "model_revision": "unversioned-alias",
                      "algorithm": CONFIG["adapter"], "config_hash": hashlib.sha256(json.dumps(CONFIG, sort_keys=True).encode()).hexdigest()}
        holds, raw_masks = [], []
        total_bytes = 0
        for index, mask in enumerate(masks):
            score = scores[index] if scores is not None else None
            if score is not None and (isinstance(score, bool) or not isinstance(score, (int, float)) or not math.isfinite(score) or not 0 <= score <= 1):
                raise ProviderContractError("Invalid mask score")
            data = self.download_mask(mask["url"])
            total_bytes += len(data)
            if total_bytes > 32 * 1024 * 1024:
                raise ProviderContractError("Combined masks exceed local result limit")
            raw_masks.append(base64.b64encode(data).decode())
            parts = mask_polygons(data, width, height)
            if parts:
                holds.append({"id": str(uuid.uuid4()), "display_number": len(holds) + 1, "kind": "hold",
                              "parts": parts, "confidence": score, "validity": "candidate",
                              "timestamp_us": timestamp_us, "coordinate_space": "upright_video_normalized",
                              "provenance": provenance, "parents": []})
        return {"schema_version": 1, "holds": holds, "raw_provider_response": raw,
                "raw_masks_png_base64": raw_masks, "provenance": provenance,
                "warning": "Candidates only; not route membership. Review missed holds and volumes."}
