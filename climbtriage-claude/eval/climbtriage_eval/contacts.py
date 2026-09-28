"""Per-limb contact precision/recall: same limb, same target, interval IoU >= threshold.

Predicted `UNKNOWN` intervals are not predictions of contact and are reported separately.
Annotations marked `unknown` are excluded from both sides of the score, and the time they
cover is reported as the annotators' own uncertainty.
"""

from __future__ import annotations

from dataclasses import dataclass


def interval_iou(a: tuple[int, int], b: tuple[int, int]) -> float:
    inter = max(0, min(a[1], b[1]) - max(a[0], b[0]))
    union = max(a[1], b[1]) - min(a[0], b[0])
    return inter / union if union > 0 else 0.0


def _target(event: dict) -> str:
    t = event.get("target") or {}
    if "holdId" in t and t.get("holdId"):
        return f"hold:{t['holdId']}"
    if "surface" in t:
        return f"surface:{t['surface']}"
    return "unknown"


@dataclass
class ContactScore:
    tp: int
    n_pred: int
    n_truth: int
    pred_unknown_us: int
    truth_unknown_us: int
    timing_errors_us: list[int]

    @property
    def precision(self):
        return self.tp / self.n_pred if self.n_pred else None

    @property
    def recall(self):
        return self.tp / self.n_truth if self.n_truth else None

    def as_dict(self) -> dict:
        return {"tp": self.tp, "predicted": self.n_pred, "truth": self.n_truth,
                "precision": self.precision, "recall": self.recall,
                "predictedUnknownSeconds": self.pred_unknown_us / 1e6,
                "annotatedUnknownSeconds": self.truth_unknown_us / 1e6,
                "startEndErrorsSeconds": [e / 1e6 for e in self.timing_errors_us]}


def score_contacts(pred: list[dict], truth: list[dict], *, iou: float = 0.5) -> ContactScore:
    p_known = [e for e in pred if e.get("state", "CONTACT") == "CONTACT"]
    p_unknown = sum(e["endUs"] - e["startUs"] for e in pred if e.get("state") == "UNKNOWN")
    t_known = [e for e in truth if _target(e) != "unknown" and not e.get("unknown")]
    t_unknown = sum(e["endUs"] - e["startUs"] for e in truth if _target(e) == "unknown" or e.get("unknown"))

    candidates = []
    for i, p in enumerate(p_known):
        for j, t in enumerate(t_known):
            if p["limb"] != t["limb"] or _target(p) != _target(t):
                continue
            s = interval_iou((p["startUs"], p["endUs"]), (t["startUs"], t["endUs"]))
            if s >= iou:
                candidates.append((s, i, j))
    used_p, used_t, errors, tp = set(), set(), [], 0
    for s, i, j in sorted(candidates, reverse=True):
        if i in used_p or j in used_t:
            continue
        used_p.add(i); used_t.add(j); tp += 1
        errors += [abs(p_known[i]["startUs"] - t_known[j]["startUs"]),
                   abs(p_known[i]["endUs"] - t_known[j]["endUs"])]
    return ContactScore(tp, len(p_known), len(t_known), p_unknown, t_unknown, errors)
