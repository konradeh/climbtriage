"""Attempt boundary error on unambiguous, complete clips. A missing prediction is missing, not zero."""

from __future__ import annotations

import statistics


def boundary_errors(pairs: list[tuple[dict, dict]]) -> dict:
    """`pairs` = [(prediction, truth)], each {"startUs": int|None, "endUs": int|None}.

    Truth boundaries that are None (ambiguous) are skipped; predicted None counts as missing.
    """
    errors: list[float] = []
    missing = 0
    scored = 0
    for pred, truth in pairs:
        for key in ("startUs", "endUs"):
            if truth.get(key) is None:
                continue
            scored += 1
            if pred.get(key) is None:
                missing += 1
                continue
            errors.append(abs(pred[key] - truth[key]) / 1e6)
    ordered = sorted(errors)
    p90 = ordered[min(len(ordered) - 1, int(round(0.9 * (len(ordered) - 1))))] if ordered else None
    return {"boundaries": scored, "missing": missing, "medianSeconds": statistics.median(errors) if errors else None,
            "p90Seconds": p90, "errorsSeconds": errors}
