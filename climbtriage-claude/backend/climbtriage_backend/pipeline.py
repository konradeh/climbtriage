"""Post-processing: provider instances → contract `Hold`s on the uploaded wall image.

Pure functions, so a checkpointed provider payload can be re-processed without a
second (paid) call. Containment suppression is adapted from vision-demos
rock_climbing/src/holds.py `suppress_contained` (Apache-2.0, commit 6e45309): IoU is
blind to a small phantom nested inside a real hold, containment is not.
"""

from __future__ import annotations

import cv2
import numpy as np

from .contracts import Hold, HoldColor, Provenance, new_id
from .providers.base import Instance

POLYGON_EPSILON_FRAC = 0.004      # of the image diagonal; keeps outlines faithful but small
MIN_AREA_FRAC = 0.00002           # drop specks below this share of the image
MAX_CONTAINMENT = 0.85            # a same-kind instance this much inside a better one is a duplicate


def kind_for_prompt(prompt: str) -> str:
    return "volume" if "volume" in prompt.lower() else "hold"


def polygon_from_mask(mask: np.ndarray) -> np.ndarray | None:
    """Largest external contour, simplified, in pixels. None when degenerate."""
    contours, _ = cv2.findContours(mask.astype(np.uint8), cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    if not contours:
        return None
    contour = max(contours, key=cv2.contourArea)
    h, w = mask.shape
    eps = POLYGON_EPSILON_FRAC * float(np.hypot(w, h))
    poly = cv2.approxPolyDP(contour, eps, True).reshape(-1, 2)
    if len(poly) < 3:
        return None
    return poly.astype(np.float64)


def median_lab(image_bgr: np.ndarray, mask: np.ndarray) -> tuple[list[float], str] | None:
    """Median CIELAB colour of the mask interior (eroded to keep edge and shadow pixels out)."""
    inner = cv2.erode(mask.astype(np.uint8), np.ones((3, 3), np.uint8), iterations=1).astype(bool)
    if inner.sum() < 10:
        inner = mask
    pixels = image_bgr[inner]
    if len(pixels) == 0:
        return None
    bgr = np.median(pixels, axis=0).astype(np.uint8)
    lab8 = cv2.cvtColor(bgr.reshape(1, 1, 3), cv2.COLOR_BGR2LAB).reshape(3).astype(float)
    lab = [lab8[0] * 100.0 / 255.0, lab8[1] - 128.0, lab8[2] - 128.0]
    hexcode = "#{:02X}{:02X}{:02X}".format(int(bgr[2]), int(bgr[1]), int(bgr[0]))
    return [round(v, 2) for v in lab], hexcode


def _containment(inner: np.ndarray, outer: np.ndarray) -> float:
    area = inner.sum()
    return float((inner & outer).sum() / area) if area else 0.0


def suppress_contained(instances: list[Instance], *, max_containment: float = MAX_CONTAINMENT) -> list[Instance]:
    """Drop an instance mostly inside a higher-scoring one of the same prompt kind.

    Holds bolted onto volumes are legitimately inside a volume's mask, so the test is
    only applied within one kind.
    """
    order = sorted(range(len(instances)),
                   key=lambda i: -(instances[i].score if instances[i].score is not None else 0.0))
    kept: list[int] = []
    for i in order:
        a = instances[i]
        if any(kind_for_prompt(instances[j].label) == kind_for_prompt(a.label)
               and _containment(a.mask, instances[j].mask) >= max_containment for j in kept):
            continue
        kept.append(i)
    return [instances[i] for i in sorted(kept)]


def holds_from_instances(instances: list[Instance], image_bgr: np.ndarray, *, provenance: Provenance,
                         min_score: float) -> list[Hold]:
    h, w = image_bgr.shape[:2]
    usable = [i for i in instances
              if (i.score is None or i.score >= min_score) and i.mask.sum() >= MIN_AREA_FRAC * w * h]
    holds: list[Hold] = []
    for inst in suppress_contained(usable):
        poly = polygon_from_mask(inst.mask)
        if poly is None:
            continue
        norm = poly / [w, h]
        x0, y0 = norm.min(axis=0)
        x1, y1 = norm.max(axis=0)
        colour = median_lab(image_bgr, inst.mask)
        holds.append(Hold(
            id=new_id("h"),
            kind=kind_for_prompt(inst.label),
            coords="frame_norm",
            polygon=[[round(float(x), 5), round(float(y), 5)] for x, y in norm],
            bbox=[round(float(v), 5) for v in (x0, y0, x1 - x0, y1 - y0)],
            color=HoldColor(lab=colour[0], hex=colour[1], method="median_lab.v1") if colour else None,
            confidence=inst.score,
            provenance=provenance,
        ))
    return holds
