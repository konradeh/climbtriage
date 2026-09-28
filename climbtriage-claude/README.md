# ClimbTriage

ClimbTriage is an Android-first climbing analysis app. **M1** (this slice)
works as follows. You record or import a clip of one boulderer filmed from a
phone on a stand. The app then:

- runs on-device pose detection,
- lets you tap the climber, and tracks that person with explicit
  loss/ambiguity marking,
- builds a climber-free wall image,
- proposes hold candidates, either in the cloud via SAM 3.1 through your own
  backend or offline with a labelled colour heuristic,
- lets you edit holds and route membership (IDs are immutable, and corrections
  are logged separately from raw output),
- replays the video with the skeleton and holds aligned on real timestamps,
- saves and reopens the session.

The specs are in [`docs/`](docs):

1. [Product spec](docs/01-product-spec.md)
2. [Architecture and data flow](docs/02-architecture.md)
3. [Stack decisions](docs/03-stack-decisions.md)
4. [Reference feature coverage](docs/04-reference-coverage.md)
5. [Contracts](docs/05-contracts.md)
6. [Backlog](docs/06-backlog.md)
7. [Evaluation plan](docs/07-evaluation-plan.md)
8. [Assumptions and risks](docs/08-assumptions-risks.md)

## Status: what is verified and what is not

| Area | State | Evidence |
|---|---|---|
| Specification, contracts, backlog, evaluation plan | written | `docs/`, `contracts/schema/climbtriage.v1.schema.json` |
| Backend: resumable upload, idempotent jobs, separate worker, bounded retries, cancel, checkpoints, tombstoned delete, content cache | **tested** | `backend/tests`: 30 pytest tests |
| Backend ↔ Kotlin client wire contract | **tested live over HTTP** | `BackendClientIntegrationTest` against a running API and worker, with the fixture provider |
| SAM 3.1 adapter (VLM Run gateway) | parsing and error classification tested against the documented envelope | **Not called live: no `VLMRUN_API_KEY` in this environment** |
| Evaluation scorers (hold P/R @ IoU, contact interval P/R, boundary error, per-scenario report) | **tested** | `eval/tests`: 6 tests. They validate the arithmetic only. |
| Android pure logic (transforms, rotation/mirror/letterbox, polygons, split/merge, correction fold, versions, display numbering, route suggestion, colour-assist, person tracker, stillness, wall median, PTS sampling, replay lookup, golden contract) | **tested on the JVM** | 42 unit tests (41 plus the live contract test) |
| Android app build | **builds, lint has 0 errors** | `android/app/build/outputs/apk/debug/app-debug.apk` (debug, about 80 MB, arm64-v8a + x86_64, pose model bundled and SHA-256 pinned) |
| Android app **running on a device** | **not verified** | No device or emulator was available. See the device checklist below. |
| Pose/hold quality on real climbing footage | **not measured** | No consented, annotated footage yet (`docs/07`). |

Nothing in this repository presents fixture or simulated output as real
inference. Fixture results carry `provenance.kind = "fixture"`, and the app
shows a warning for them.

## Layout

```
docs/            specification set (start here)
contracts/       JSON Schema (source of truth) + golden example shared by both codebases
android/         Kotlin/Compose app (single :app module, package-layered)
  app/src/main/java/app/climbtriage/
    contracts/   Kotlin mirror of the schema, ULID ids
    geometry/    Transform2D, FrameGeometry (rotation/mirror), Viewport (letterbox), polygons, Lab
    media/       MediaProbe (real PTS, rotation), FrameSource (frame_norm decoding)
    perception/  MediaPipe PoseEngine, PersonTracker, WallImage (median), StillnessCheck
    holds/       HoldMap (raw ⊕ corrections fold), ColorAssist heuristic, RouteSuggest, BackendClient
    analysis/    SessionAnalyzer (on-device pipeline)
    data/        Room + app-private JSON artefacts
    events/      ContactInferencer interface (M2 boundary, not implemented)
    ui/          Compose screens + overlay drawing
backend/         FastAPI API + worker, provider adapters (SAM 3.1 / ViTPose+ via VLM Run), SQLite dev store
eval/            evaluation scorers and per-scenario report
tools/           setup-toolchain.sh, check.sh
```

## Setup and checks

Everything installs locally, with no admin rights and nothing system-wide.

```bash
# Python 3.11+ (tested with 3.14)
python -m venv .venv
.venv/Scripts/python -m pip install -e backend[dev] numpy opencv-python-headless

# Portable JDK 21 + Android SDK + Gradle settings in ./.toolchain (~2 GB)
./tools/setup-toolchain.sh

# All reproducible checks: backend + eval pytest, Android unit tests + lint + debug APK
./tools/check.sh
```

### Install on a phone

Enable USB debugging, then run:

```bash
.toolchain/android-sdk/platform-tools/adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

### Run the backend (optional, for cloud hold detection)

```bash
cd backend
cp .env.example .env                  # put VLMRUN_API_KEY=... here (server-side only)
../.venv/Scripts/python -m uvicorn climbtriage_backend.api:create_app --factory --port 8000
../.venv/Scripts/python -m climbtriage_backend.worker          # separate process
```

The phone reaches it as follows:

- Emulator: `http://10.0.2.2:8000` (the default).
- USB phone: run `adb reverse tcp:8000 tcp:8000` and set `http://localhost:8000`
  in Settings.

Cleartext HTTP is allowed only for those local hosts. Without a key, hold jobs
fail with a clear "no provider configured" error. `CLIMBTRIAGE_PROVIDER=fixture`
exercises the job machinery with a labelled test double.

To run the live client ↔ backend contract test:

```bash
CLIMBTRIAGE_PROVIDER=fixture <start API + worker on :8765>
cd android && source env.sh
CLIMBTRIAGE_BACKEND_URL=http://localhost:8765 ./gradlew testDebugUnitTest --tests '*BackendClientIntegrationTest*'
```

## Device checklist (M1 exit, still to do)

Use a permitted clip of an indoor boulder filmed from a stand:

1. Import a **portrait** phone clip (rotation 90) and a **landscape** clip.
   Check that the replay skeleton sits on the body in both, including after
   rotating the phone (letterbox changes).
2. Record in-app for 30 s. Check that the session list shows it, and that the
   manifest PTS count matches the frame count reported by `ffprobe`.
3. Analyse. Note the analysis time and the coverage line. The wall image
   should not contain the climber.
4. Select the climber in a frame with a second person present. Walk the
   second person past the climber. The timeline must show amber (ambiguous)
   rather than the skeleton jumping to them.
5. Holds: run colour assist, and cloud detection if a key is available.
   Add, move, split, merge and remove holds. Mark route, start and finish.
   Save. Kill the app, reopen it, and check the state is identical.
6. Nudge the phone mid-clip. The stillness verdict should be `MOVED`, and the
   holds should be hidden near those times in replay.
7. Record the device model, Android version, analysis seconds per clip
   second, and any misalignment, in `docs/07` results.

## Limitations (M1)

- Stationary camera only. Movement is detected and the overlay suppressed, not
  compensated.
- The median wall image keeps the climber in pixels where they stayed for more
  than half of the sampled frames, for example a long rest at the start.
- The colour-assist heuristic is weak on chalked, multi-colour or
  low-contrast holds. It is labelled and has not been evaluated.
- MediaPipe's climbing performance (inverted poses, occlusion, overhangs) is
  unmeasured.
- There is no contact inference, attempt timing, metrics or export video yet
  (M2). Replay aligns to `ExoPlayer.currentPosition + firstPts`. Files with
  unusual edit lists need to be checked on the device.

## Attribution

This project adapts request contracts and algorithms from
[vision-demos](https://github.com/jeremyipark/vision-demos) (Apache-2.0,
commit `6e45309`). See [`NOTICE`](NOTICE) and
[`docs/04-reference-coverage.md`](docs/04-reference-coverage.md).
