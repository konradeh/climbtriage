"""Visible-hold precision/recall at mask IoU >= threshold, one-to-one greedy matching."""

from __future__ import annotations

from dataclasses import dataclass

import cv2
import numpy as np

RASTER_LONG_EDGE = 1024


def rasterize(polygon: list[list[float]], width: int, height: int) -> np.ndarray:
    mask = np.zeros((height, width), np.uint8)
    pts = np.round(np.asarray(polygon, dtype=np.float64) * [width, height]).astype(np.int32)
    if len(pts) >= 3:
        cv2.fillPoly(mask, [pts], 1)
    return mask.astype(bool)


def iou_matrix(pred: list[list[list[float]]], truth: list[list[list[float]]], *, aspect: float) -> np.ndarray:
    """IoU between normalised polygons, rasterised on a grid with the image's aspect (w/h)."""
    if aspect >= 1:
        w, h = RASTER_LONG_EDGE, max(1, round(RASTER_LONG_EDGE / aspect))
    else:
        w, h = max(1, round(RASTER_LONG_EDGE * aspect)), RASTER_LONG_EDGE
    pm = [rasterize(p, w, h) for p in pred]
    tm = [rasterize(t, w, h) for t in truth]
    out = np.zeros((len(pm), len(tm)))
    for i, a in enumerate(pm):
        for j, b in enumerate(tm):
            union = (a | b).sum()
            out[i, j] = (a & b).sum() / union if union else 0.0
    return out


def greedy_match(scores: np.ndarray, threshold: float) -> list[tuple[int, int, float]]:
    pairs = sorted(((scores[i, j], i, j) for i in range(scores.shape[0]) for j in range(scores.shape[1])
                    if scores[i, j] >= threshold), reverse=True)
    used_i, used_j, out = set(), set(), []
    for s, i, j in pairs:
        if i in used_i or j in used_j:
            continue
        used_i.add(i); used_j.add(j); out.append((i, j, float(s)))
    return out


@dataclass
class HoldScore:
    tp: int
    n_pred: int
    n_truth: int
    matches: list[tuple[int, int, float]]

    @property
    def precision(self) -> float | None:
        return self.tp / self.n_pred if self.n_pred else None

    @property
    def recall(self) -> float | None:
        return self.tp / self.n_truth if self.n_truth else None

    def as_dict(self) -> dict:
        return {"tp": self.tp, "predicted": self.n_pred, "truth": self.n_truth,
                "precision": self.precision, "recall": self.recall}


def score_holds(pred: list[dict], truth: list[dict], *, aspect: float, iou: float = 0.5) -> HoldScore:
    """`truth` items: {"polygon": ..., "visible": bool}. Only visible truth holds count."""
    visible = [t for t in truth if t.get("visible", True)]
    live = [p for p in pred if not p.get("retired", False)]
    matches = greedy_match(iou_matrix([p["polygon"] for p in live], [t["polygon"] for t in visible],
                                      aspect=aspect), iou)
    return HoldScore(tp=len(matches), n_pred=len(live), n_truth=len(visible), matches=matches)
