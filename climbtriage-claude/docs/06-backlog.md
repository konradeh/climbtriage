# 06 — Prioritised backlog

Every item has an exit criterion that someone can check. "Validated" always
means measured against the annotated set in `07`, never against mocked
detections.

## M1 — First slice (this delivery)

| ID | Item | Exit criterion | State |
|---|---|---|---|
| M1.1 | Record with CameraX to app-private storage; import via SAF (copy + SHA-256) | A clip opens in the session list with a manifest that holds real PTS | built; device run pending |
| M1.2 | Capture manifest: rotation, mirror, display size, PTS list, device | Golden-file and unit tests pass | built + tested (JVM) |
| M1.3 | Rotation-normalised frame decoder at the requested analysis cadence, addressed by PTS | Correct orientation on 0/90/180/270 sources | built; device check pending |
| M1.4 | MediaPipe multi-person pose detection (IMAGE, ≤3), with raw detections persisted | Detections stored with provenance and coverage | built; device run pending |
| M1.5 | Person selection with a tap plus `PersonTracker` (forward/backward, loss and ambiguity marking, reacquire rule) | Unit tests for crossing, gaps, ambiguity | built + tested (JVM) |
| M1.6 | Stillness check; suppress the overlay when the camera moved | Unit test on synthetic shifted frames | built + tested (JVM) |
| M1.7 | Median wall image (climber removed) | Unit test | built + tested (JVM) |
| M1.8 | Hold candidates: backend SAM 3.1 adapter (cloud, opt-in) plus the on-device colour-assist heuristic (offline, labelled) | Backend tests on the documented envelope. **A live call needs `VLMRUN_API_KEY`.** | built; live provider unverified |
| M1.9 | Hold editor: add/remove/move/scale/split/merge, kind, route members, start/finish; immutable IDs and a correction log folded over raw | Unit tests for fold, split, merge, lineage, versions | built + tested (JVM) |
| M1.10 | Route suggestion by Lab colour distance from a picked seed hold | Unit test | built + tested (JVM) |
| M1.11 | Replay: Media3 plus a Compose overlay (skeleton, hold outlines, route highlight, lost-interval timeline) | Transform unit tests; alignment by eye on a real clip | built; device check pending |
| M1.12 | Save/reopen: Room metadata plus JSON artefacts | Reopen shows an identical state | built; device check pending |
| M1.13 | Backend: resumable upload, idempotent jobs, worker, retries, cancel, checkpoints, tombstoned delete, content cache | Pytest suite, plus a live Kotlin client ↔ API/worker contract test over HTTP | built + tested |
| M1.14 | Evaluation harness: hold P/R at IoU 0.5, contact interval metrics, boundary error, per-scenario report | Pytest on scorer logic | built + tested |

## M2 — Contact and timing (priority 2)

1. `ContactInferencer`: distance to the hold polygon in hold-height units,
   relative motion, confidence, dwell in µs, separate enter/release thresholds,
   a transfer-to-deeper-hold rule (ported from reference `climb.py`), and
   `UNKNOWN` intervals when a limb is missing. Feet use heel and foot-index
   landmarks when visible; otherwise the event is labelled `ankle_approx`.
2. Surfaces: wall smears and volume contacts as `target.surface`, which never
   creates a hold.
3. Attempts: route-type start and finish rules with user correction; tracking
   loss → `truncated`/`unknown`, never "fell".
4. Metrics: duration, overall and per-limb sequences, contact time, limb
   usage, and coverage for each.
5. Correction tools on the timeline (move a boundary, relabel a contact, mark
   unknown).
6. Export JSON (contracts), CSV (hold times, limb usage) and MP4 with the
   overlay (Media3 Transformer).
7. Evaluation: first contact P/R and boundary error numbers on the annotated set.

## M3 — Handheld and comparison (priority 3)

Per-frame registration (feature matching + homography) with a quality gate
modelled on the reference's `parallax_check`, and bad-registration
suppression. After that come wall mosaics, missed-hold recovery via
`segment_box` at dwell sites, same-route attempt comparison after wall-to-wall
registration, pause/rest-candidate intervals (low motion; shake-outs allowed;
no recovery claims), hip-midpoint traces and heatmaps. Multi-plane or
piecewise registration is added only where the evaluation shows a single
homography fails.

## M4 — Live and offline models (priority 4)

A live pose overlay (`ImageAnalysis`, keep-only-latest, causal filter,
coverage and overlay-age telemetry) and validated live contact feedback. An
on-device hold segmenter (ONNX Runtime Mobile) is added only after the data,
exportability, licensing and device benchmarks clear the gates in `07`.

## M5 — Depth, 3D and hardware (priority 4, after M4)

Depth capture with camera-session coordination (ARCore or ToF where
available), manifest depth, intrinsics and camera pose, and metric displays
only with validated calibration. 3D cross-capture registration is unfinished
in the reference. External camera and hardware adapters.

## M6 — iOS

A shared contracts/geometry core (Kotlin Multiplatform, or a port plus golden
files). Capture on iOS with AVFoundation and optional LiDAR (reference `3D`
format).

## Optional backlog (never blocks climbing)

Chin-up counting and timing (`chin_ups/`), running cadence and gait
(`running/`), dance comparison (`dance_sync/`). An optional language-model
summary may only cite stored metrics and timestamps.
