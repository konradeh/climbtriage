# ClimbTriage

Android-first indoor bouldering review. This folder is independent of the other projects in `projektid`.

Start with [the specification](docs/SPECIFICATION.md), [reference coverage and provider review](docs/REFERENCE_REVIEW.md), [versioned contracts](docs/CONTRACTS.md), and [verification record](docs/VERIFICATION.md).

Built artifact: [ClimbTriage 0.1.0 debug APK](artifacts/ClimbTriage-0.1.0-debug.apk). Checks achieved: 19 backend tests, 7 Android JVM tests, successful APK build/signature verification and lint with zero errors (10 dependency-update notices). No connected device or consented climbing footage was available; real inference and device performance remain unvalidated.

## Scope of this slice

The Android source connects short SDR video import, real MediaPipe Pose Landmarker inference, explicit person selection with conservative tracking/loss, optional server SAM hold candidates, polygon and route edits, Media3 overlays and Room-indexed saved replay. It retains original media, immutable observations and correction/version files privately on the phone. Color/geometry route suggestions require acceptance; they can include same-color neighboring routes.

The Python service implements a durable local upload/job queue and a replaceable fal SAM 3 adapter. No fixture detector is used at runtime. Missing credentials fail explicitly. Cloud SAM 3 differs from the reference's VLM Run SAM 3.1; its published API was checked independently, but no paid inference was run here. Read the verification record for achieved results and outstanding dependencies.

CameraX recording, contact/attempt metrics, live feedback, automatic offline holds, moving-camera registration, 3D and export are later milestones. Import is the selected first capture adapter. The first UI is a functional engineering prototype.

## Backend setup (Windows PowerShell)

Python 3.14 was used for the checked dependency lock. The service and worker must share their working directory or the same absolute `CLIMBTRIAGE_DATA` path. No Azure account or cloud infrastructure is required.

```powershell
cd 'C:\Users\timok\Desktop\projektid\climbtriage codex'
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r backend\requirements.lock
cd backend
..\.venv\Scripts\python.exe -m uvicorn climbtriage.api:create_app --factory --host 127.0.0.1 --port 8000
```

In a second terminal, set `FAL_KEY` securely in the worker's environment (never in Android or source control), then run:

```powershell
cd 'C:\Users\timok\Desktop\projektid\climbtriage codex\backend'
..\.venv\Scripts\python.exe -m climbtriage.worker
```

API docs: `http://127.0.0.1:8000/docs`. `/health` shows schema/local mode. Do not expose this single-user prototype publicly: tenant authentication/authorization, TLS, quotas and retention policy are production gates. Dockerfile provides a portable process image; bind published ports to host loopback and mount a shared `/data` volume for API/worker. Container execution is not part of the local verification.

## Android build

Android Studio with JDK 17, SDK 35 and Gradle 8.14.3 can open `android/`. Minimum device API is 26. Versions are pinned in Gradle files. The pose model download uses Google's versioned URL and records its SHA-256; no provider key is needed for pose or manual review.

```powershell
.\scripts\fetch-model.ps1
```

For a machine without a toolchain, `scripts/bootstrap-android.ps1` downloads JDK/Gradle/Android SDK under `.tools`, verifies distribution checksums, fetches the official pose task and builds/tests. Review [Android SDK terms](https://developer.android.com/studio#command-tools) before using its explicit `-AcceptAndroidSdkLicenses` switch.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\bootstrap-android.ps1 -AcceptAndroidSdkLicenses
```

Expected build output: `android/app/build/outputs/apk/debug/app-debug.apk`. A debug APK is for local testing, not production release signing. For subsequent checks, `scripts/build-android.ps1` selects the JDK under `.tools/jdk-extracted`, sets the project-local Gradle cache and runs the test/build/lint tasks.

The checked-in `android/gradlew` / `gradlew.bat` wrapper provides Gradle 8.14.3 for ordinary Android Studio/CI setups. On Windows this environment encountered a Gradle dependency-cache rename failure; after the daemon stopped, the already-completed `media3-ui` extraction was moved to the exact cache destination named in Gradle's error. No dependency content or application check was changed. File watching is disabled and worker count bounded for this local build. See the verification record for final build status.

## Device walkthrough (requires your consented footage)

1. Install the debug APK on an Android phone. Import a stationary-camera SDR clip <=2 minutes / 300 MiB. Recording is not implemented yet. HDR/unsupported geometry fails with instructions instead of guessed transforms.
2. After on-device analysis, pause or seek to a clear frame. In **Person** mode, tap the climber's torso. The skeleton in review follows that segment; loss stays lost until another explicit selection. Purple candidate skeletons in Person mode are proposals.
3. Add holds manually by drawing polygons, or start the local backend/worker, set its provider credential, connect the phone via USB and run `adb reverse tcp:8000 tcp:8000`. Choose **Propose holds from wall frame**, review the upload notice, then explicitly upload. Only the first analyzed JPEG is sent. Emulator connection uses `10.0.2.2`.
4. In **Hold** mode, tap outlines to toggle route/start/finish or mark volumes. **Suggest route** previews a color/position heuristic in blue; acceptance adds those members. Green means route membership, never physical contact.
5. **Reshape** replaces the selected outline. **Split** collects two new outlines with parent lineage. **Merge** selects two objects and preserves both sets of parts. Manual split geometry is unconstrained by the old mask so you can correct a bad merged detection; verify the children visually. Start/finish must be reconfirmed after split/merge.
6. Play/scrub the clip, save, return to Sessions, and reopen. Edits also autosave. Turn off the static hold map if the camera moved. Use a stationary recording until registration confidence is implemented.
7. If the network fails, retry the hold action: the client queries the accepted offset and resumes the persisted remote job. Cancellation fences local publication; a provider request already running may still incur cost. Deletion requires connection to remove a recorded backend upload before local erasure, and does not promise third-party retention deletion.

## Reproducible checks

```powershell
$env:PYTHONPATH = 'backend'
.\.venv\Scripts\python.exe -m pytest backend\tests -q
.\.venv\Scripts\python.exe -m compileall -q backend\climbtriage
```

Android JVM checks: `gradle testDebugUnitTest`; compile/package: `gradle assembleDebug`; static Android checks: `gradle lintDebug`. Contract tests use explicitly synthetic inputs only for geometry, queue and failure semantics. They are not climbing demonstrations or model benchmarks. No consented climbing footage was supplied, and none was taken from the reference's images/videos.

## Layout

* `android/app/.../capture` — timestamped recorded RGB adapter.
* `perception` — MediaPipe pose and optional route suggestion baseline.
* `domain` — contracts, geometry, selected-person association and correction semantics.
* `data` — Room/private sessions and resumable local-backend client.
* `backend/climbtriage` — API, durable queue, separate worker and SAM provider boundary.
* `docs` — specification, reference review, contracts, validation plan/evidence.
* `reference/vision-demos` — unchanged inspected checkout, ignored from new-project version control.

The next highest-value work is a physical-device run with consented climbing clips and an authenticated hold request, fixing identity/alignment failure cases before implementing contact analytics. See [NOTICE](NOTICE.md) for attribution and model-license distinctions.
