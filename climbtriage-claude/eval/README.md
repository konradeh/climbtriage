# ClimbTriage evaluation harness

Scorers for `docs/07-evaluation-plan.md`. They take predictions in `climbtriage.v1`
JSON and human annotations in the format below, and they produce per-scenario tables.

```bash
../.venv/Scripts/python -m pytest -q          # tests the scorers
../.venv/Scripts/python -m climbtriage_eval.report data/manifest.json > report.md
```

The unit tests use hand-built inputs, so they validate the scoring logic only.
**No accuracy result exists yet.** That needs the consented, annotated dataset
described in the plan.

## Annotation format (one file per clip)

```json
{
  "holds":    [{"polygon": [[x,y],...], "visible": true, "kind": "hold", "routeMember": true}],
  "contacts": [{"limb": "left_hand", "target": {"holdId": "<truth hold id>"} | {"surface": "wall"} | {},
                "startUs": 0, "endUs": 0, "unknown": false}],
  "attempt":  {"startUs": 0, "endUs": 0, "ambiguous": false},
  "identity": [{"tUs": 0, "bbox": [x,y,w,h]}]
}
```

Polygons use normalised `wall_norm` coordinates. Truth `holdId`s are matched to
predicted IDs through the hold matching result before contacts are scored. The
report builder takes the mapping from `score_holds().matches`. For M1 there are
no predicted contacts, so the contact columns are empty.
