"""Per-scenario report. Usage: python -m climbtriage_eval.report manifest.json > report.md

manifest.json: {"clips": [{"id", "scenarios": [..], "aspect": w/h,
                           "pred": "<climbtriage.v1 json path>", "truth": "<annotation json path>"}]}
Annotation format: see eval/README.md.
"""

from __future__ import annotations

import json
import sys
from collections import defaultdict
from pathlib import Path

from .attempts import boundary_errors
from .contacts import score_contacts
from .holds import score_holds

TARGETS = {"holdPrecision": 0.90, "holdRecall": 0.90, "contactPrecision": 0.90, "contactRecall": 0.85,
           "attemptMedianSeconds": 0.5}


def _sum(scores: list[dict]) -> dict:
    tp = sum(s["tp"] for s in scores); n_p = sum(s["predicted"] for s in scores); n_t = sum(s["truth"] for s in scores)
    return {"tp": tp, "predicted": n_p, "truth": n_t,
            "precision": tp / n_p if n_p else None, "recall": tp / n_t if n_t else None}


def evaluate(manifest: dict, base: Path) -> dict:
    by_scenario: dict[str, dict[str, list]] = defaultdict(lambda: defaultdict(list))
    for clip in manifest["clips"]:
        pred = json.loads((base / clip["pred"]).read_text())
        truth = json.loads((base / clip["truth"]).read_text())
        holds = [h for wv in pred.get("wallVersions", [])[-1:] for h in wv["holds"] if not h.get("retired")]
        truth_holds = [t for t in truth.get("holds", []) if t.get("visible", True)]
        hold_score = score_holds(holds, truth_holds, aspect=clip["aspect"])
        hs = hold_score.as_dict()
        # Contacts are compared in the annotators' hold ids: map each matched predicted id across.
        to_truth = {holds[i].get("id"): truth_holds[j].get("id") for i, j, _ in hold_score.matches}
        contacts = []
        for e in pred.get("contactEvents", []):
            target = dict(e.get("target") or {})
            if target.get("holdId"):
                target["holdId"] = to_truth.get(target["holdId"], f"unmatched:{target['holdId']}")
            contacts.append({**e, "target": target})
        cs = score_contacts(contacts, truth.get("contacts", [])).as_dict()
        pa = (pred.get("attempts") or [{}])[0]
        ta = truth.get("attempt") or {}
        for scenario in ["all", *clip.get("scenarios", [])]:
            bucket = by_scenario[scenario]
            bucket["holds"].append(hs); bucket["contacts"].append(cs)
            if ta and not ta.get("ambiguous"):
                bucket["attempts"].append((pa, ta))
    return {s: {"clips": len(b["holds"]), "holds": _sum(b["holds"]), "contacts": _sum(b["contacts"]),
                "attempts": boundary_errors(b["attempts"])} for s, b in by_scenario.items()}


def _fmt(v):
    return "—" if v is None else f"{v:.3f}"


def markdown(result: dict) -> str:
    lines = ["| scenario | clips | hold P | hold R | contact P | contact R | attempt median s | missing bounds |",
             "|---|---|---|---|---|---|---|---|"]
    for s, r in sorted(result.items(), key=lambda kv: (kv[0] != "all", kv[0])):
        lines.append(f"| {s} | {r['clips']} | {_fmt(r['holds']['precision'])} | {_fmt(r['holds']['recall'])} | "
                     f"{_fmt(r['contacts']['precision'])} | {_fmt(r['contacts']['recall'])} | "
                     f"{_fmt(r['attempts']['medianSeconds'])} | {r['attempts']['missing']} |")
    lines.append("")
    lines.append("Targets (provisional, not achieved results): " + ", ".join(f"{k} {v}" for k, v in TARGETS.items()))
    return "\n".join(lines)


if __name__ == "__main__":
    path = Path(sys.argv[1])
    print(markdown(evaluate(json.loads(path.read_text()), path.parent)))
