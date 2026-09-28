"""The shared JSON Schema, the golden example, and what the backend emits must agree."""

import json

import jsonschema
import pytest

from climbtriage_backend.contracts import (AnalysisRun, Hold, HoldCandidatesResult, Provenance, new_id, ulid)
from conftest import REPO

SCHEMA = json.loads((REPO / "contracts/schema/climbtriage.v1.schema.json").read_text())
GOLDEN = json.loads((REPO / "contracts/examples/session.v1.json").read_text())


def _validator(defn: str | None = None):
    schema = SCHEMA if defn is None else {"$ref": f"#/$defs/{defn}", "$defs": SCHEMA["$defs"]}
    return jsonschema.Draft202012Validator(schema)


def test_golden_example_is_valid():
    _validator().validate(GOLDEN)


def test_lost_samples_may_not_carry_landmarks():
    bad = json.loads(json.dumps(GOLDEN))
    bad["personTracks"][0]["samples"][1]["landmarks"] = [{"name": "nose", "x": 0, "y": 0}]
    with pytest.raises(jsonschema.ValidationError):
        _validator().validate(bad)


def test_unknown_major_version_rejected():
    bad = dict(GOLDEN, schema="climbtriage.v2")
    with pytest.raises(jsonschema.ValidationError):
        _validator().validate(bad)


def test_ulid_ids_match_hold_pattern():
    assert len(ulid()) == 26
    hold_id = new_id("h")
    _validator("Hold").validate({"id": hold_id, "kind": "hold", "coords": "frame_norm",
                                 "polygon": [[0, 0], [1, 0], [1, 1]],
                                 "provenance": {"kind": "user", "name": "x", "version": "1"}})


def test_backend_result_validates_against_schema():
    prov = Provenance(kind="model", name="facebook/sam3.1", version="gateway", runId="run_x")
    hold = Hold(id=new_id("h"), kind="hold", coords="frame_norm", polygon=[[0, 0], [0.1, 0], [0.1, 0.1]],
                bbox=[0, 0, 0.1, 0.1], provenance=prov)
    run = AnalysisRun(id="run_x", kind="hold_candidates", inputSha256="a" * 64, provider="p", model="m",
                      status="succeeded", provenance=prov)
    dumped = HoldCandidatesResult(run=run, imageWidthPx=10, imageHeightPx=10, holds=[hold]).dump()
    _validator("AnalysisRun").validate(dumped["run"])
    for h in dumped["holds"]:
        _validator("Hold").validate(h)
    assert dumped["schema"] == "climbtriage.v1"
