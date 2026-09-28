# 07 — Evaluation plan

## Principles

- We report **automatic quality before corrections**. Correction effort is a
  separate metric (edits per route).
- A **target** and an **achieved** number are never in the same column.
  Nothing here has been achieved yet, because no annotated set exists.
- Every metric reports **coverage**: the share of frames or holds that were
  analysable. Missing data is excluded and counted; it is never scored as a
  negative or as zero.
- Mocked or fixture detections test the *scorer*. They never validate a model.

## Dataset (to collect; consent required)

| Axis | Minimum spread |
|---|---|
| Gyms / walls | ≥ 5 gyms, ≥ 3 wall angles (slab, vertical, overhang), ≥ 1 with volumes |
| Lighting | daylight, mixed, low, backlit |
| Climbers | ≥ 20 people with varied body size, skin tone and clothing (including clothing that matches the hold colour) |
| Occlusion | self-occlusion, other climbers crossing, spotters |
| Hold state | fresh, chalked-over, same-colour neighbouring routes |
| Devices | ≥ 4 Android phones across 3 manufacturers, including one low-end |
| Capture | tripod portrait and landscape, rotated sources, VFR, HDR |

Consent must cover recording, annotation, storage and use in evaluation.
Bystanders are blurred or excluded. Clips are stored under access control.

**Splits** are made by *climber × route × gym* groups, so no climber, route or
gym appears in both tuning and test. The test split is frozen before any
tuning.

## Annotations

- Holds: polygon masks on the wall reference image, kind (hold/volume), and
  route membership, including start and finish.
- Identity: the climber's bbox per annotated frame, plus crossing events.
- Contacts: per limb, `(holdId | surface, startUs, endUs)`, and `unknown` where
  annotators cannot tell.
- Attempts: start and end timestamps on clips that contain an unambiguous full
  attempt.
- Pauses: annotator-marked low-motion intervals (not "rest").

Double annotation on 20% of clips measures inter-annotator agreement. A target
tighter than human agreement is revised.

## Metrics and provisional targets

| Metric | Definition | Provisional target |
|---|---|---|
| Visible-hold precision / recall | Greedy one-to-one matching by mask IoU ≥ 0.5, on holds visible in the reference image | ≥ 90% / ≥ 90% |
| Route-membership accuracy | After automatic suggestion, before correction | report only (M1) |
| Per-limb contact P / R | A match needs the same limb, the same hold (or surface) and interval IoU ≥ 0.5 | P ≥ 90%, R ≥ 85% |
| Attempt boundary error | \|pred − truth\| per boundary on unambiguous complete clips | median ≤ 0.5 s |
| Identity switches | Frames the tracker assigns to a non-climber / annotated frames | report; target 0 on crossings |
| Loss honesty | Among `LOST`/`AMBIGUOUS` samples, share where the annotator also marks the climber occluded or ambiguous | report |
| Pose coverage | Frames with `VALID` climber pose / frames with the climber visible | report per scenario |
| Live pose rate (M4) | Pose updates per second on named reference phones | ≥ 15 |
| Overlay age p95 (M4) | Display time − capture time of the frame drawn | < 200 ms |
| Thermal (M4) | Ten-minute capture: fps, dropped frames, and throttling state over time | report |

Results are reported **per scenario** (wall angle, lighting, crossings,
device, …) together with the aggregate. Timing errors are reported as
distributions, not only as means.

## Robustness checks (each a scripted scenario)

Overlay alignment during rotation and crop; wrong-person crossings;
adjacent-hold transfers; long pose gaps; variable frame rate; app
interruption while recording or analysing; network failure mid-upload
(resume); duplicate job submission (idempotency).

## Harness

`eval/` contains the scorers (`holds.py`, `contacts.py`, `attempts.py`), the
per-scenario report builder, and pytest coverage of the scorers themselves.
Input is `climbtriage.v1` JSON for predictions and the annotation format in
`eval/README.md`.

## Gates

- **M1 exit**: the harness runs end-to-end on at least one consented,
  annotated clip, and the numbers are published even if they are bad.
- **On-device hold model (M4)**: the model reaches within 5 points of the
  cloud baseline recall on the test split, has an exportable licence, and
  p95 latency ≤ 1.5 s on the low-end reference phone.
- **Live contact feedback (M4)**: the offline contact targets must be met
  first. Live then has to reach them within 5 points.
