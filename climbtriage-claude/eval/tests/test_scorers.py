import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from climbtriage_eval.attempts import boundary_errors  # noqa: E402
from climbtriage_eval.contacts import interval_iou, score_contacts  # noqa: E402
from climbtriage_eval.holds import score_holds  # noqa: E402
from climbtriage_eval.report import evaluate, markdown  # noqa: E402


def sq(x, y, s):
    return [[x, y], [x + s, y], [x + s, y + s], [x, y + s]]


def test_hold_scoring_one_to_one_and_threshold():
    truth = [{"polygon": sq(0.1, 0.1, 0.1)}, {"polygon": sq(0.5, 0.5, 0.1)},
             {"polygon": sq(0.8, 0.8, 0.05), "visible": False}]
    pred = [{"polygon": sq(0.1, 0.1, 0.1)},          # exact
            {"polygon": sq(0.105, 0.1, 0.1)},        # duplicate of the same hold: only one may match
            {"polygon": sq(0.55, 0.55, 0.1)},        # IoU 0.14 < 0.5
            {"polygon": sq(0.3, 0.3, 0.1), "retired": True}]
    s = score_holds(pred, truth, aspect=1.0)
    assert (s.tp, s.n_pred, s.n_truth) == (1, 3, 2)
    assert abs(s.precision - 1 / 3) < 1e-9 and s.recall == 0.5


def test_hold_scoring_respects_aspect():
    # A square in normalised coords of a 2:1 image is a 2:1 rectangle in pixels; IoU is unchanged
    # but rasterisation must not crash or distort matching.
    s = score_holds([{"polygon": sq(0.1, 0.1, 0.2)}], [{"polygon": sq(0.1, 0.1, 0.2)}], aspect=2.0)
    assert s.tp == 1


def test_interval_iou():
    assert interval_iou((0, 10), (5, 15)) == 5 / 15
    assert interval_iou((0, 10), (10, 20)) == 0.0


def test_contact_scoring_limb_target_and_unknowns():
    truth = [
        {"limb": "left_hand", "target": {"holdId": "h1"}, "startUs": 0, "endUs": 1_000_000},
        {"limb": "right_foot", "target": {"surface": "wall"}, "startUs": 0, "endUs": 1_000_000},
        {"limb": "right_hand", "target": {}, "startUs": 0, "endUs": 500_000},       # annotator unsure
    ]
    pred = [
        {"limb": "left_hand", "target": {"holdId": "h1"}, "startUs": 100_000, "endUs": 1_000_000, "state": "CONTACT"},
        {"limb": "right_hand", "target": {"holdId": "h1"}, "startUs": 0, "endUs": 1_000_000, "state": "CONTACT"},
        {"limb": "right_foot", "target": {"holdId": "h9"}, "startUs": 0, "endUs": 1_000_000, "state": "CONTACT"},
        {"limb": "left_foot", "target": {}, "startUs": 0, "endUs": 2_000_000, "state": "UNKNOWN"},
    ]
    s = score_contacts(pred, truth)
    assert (s.tp, s.n_pred, s.n_truth) == (1, 3, 2)
    assert s.pred_unknown_us == 2_000_000 and s.truth_unknown_us == 500_000
    assert s.timing_errors_us == [100_000, 0]


def test_attempt_boundaries_missing_is_not_zero():
    out = boundary_errors([({"startUs": 1_200_000, "endUs": None}, {"startUs": 1_000_000, "endUs": 9_000_000}),
                           ({"startUs": 0, "endUs": 5_000_000}, {"startUs": None, "endUs": 5_300_000})])
    assert out["boundaries"] == 3 and out["missing"] == 1
    assert out["errorsSeconds"] == [0.2, 0.3]
    assert abs(out["medianSeconds"] - 0.25) < 1e-9


def test_report_is_per_scenario(tmp_path):
    pred = {"schema": "climbtriage.v1", "wallVersions": [{"holds": [{"polygon": sq(0.1, 0.1, 0.1)}]}],
            "contactEvents": [], "attempts": [{"startUs": 0, "endUs": 1_000_000}]}
    truth = {"holds": [{"polygon": sq(0.1, 0.1, 0.1)}, {"polygon": sq(0.6, 0.6, 0.1)}],
             "contacts": [], "attempt": {"startUs": 100_000, "endUs": 1_000_000}}
    (tmp_path / "p.json").write_text(json.dumps(pred)); (tmp_path / "t.json").write_text(json.dumps(truth))
    manifest = {"clips": [{"id": "c1", "scenarios": ["overhang"], "aspect": 0.5625, "pred": "p.json", "truth": "t.json"}]}
    result = evaluate(manifest, tmp_path)
    assert set(result) == {"all", "overhang"}
    assert result["overhang"]["holds"]["recall"] == 0.5
    assert "overhang" in markdown(result) and "not achieved" in markdown(result)
