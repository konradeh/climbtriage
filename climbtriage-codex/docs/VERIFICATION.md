# Verification record

Date: 2026-09-28. This record separates source implementation, automated checks and real inference evidence.

## Confirmed so far

* Reference cloned and inspected locally; HEAD exactly `6e45309f9ab7f9b7b8707b128bb84294317609e8`.
* No `ClimbTriage-project-plan.md` or applicable `AGENTS.md` found in the workspace/ancestor paths checked.
* User confirmed no climbing clip is available. No reference media was assumed consented.
* Python 3.14.7; backend dependencies installed into project `.venv`, versions captured in `backend/requirements.lock`.
* 19 backend contract tests passed; JUnit evidence is in `docs/evidence/backend-tests.xml`. One upstream Starlette warning notes future TestClient migration from httpx to httpx2; it does not affect these results.
* Tests cover offset/digest atomicity, idempotency conflicts, deletion/cancellation publication fences, expired leases, bounded retries, provider checkpoint restart, configuration/cache fencing, required upload choice, missing credential failure, bounded image allocation, mask scale/color checks, disconnected parts and URL allowlisting.

## Pending real evidence

No SAM request was authenticated; no hold detections on climbing footage are claimed. No pose inference on real climbing footage, selected-person benchmark, contact benchmark, Android latency, thermal/capture measurement or physical-device alignment test is claimed. Model download and code compilation do not establish these results.

## Android build evidence

* **Built successfully:** Gradle `wrapper testDebugUnitTest assembleDebug lintDebug`, final run completed successfully in 3m37s. JDK 17 (Temurin 17.0.20.1+1), Gradle 8.14.3, AGP 8.9.2, Kotlin 2.1.20, SDK/build tools 35.
* **7 JVM tests passed**, zero failures/errors. They cover letterbox round trips/bar rejection, synthetic ambiguous crossings, long gaps/reselection, VFR stale/future sample suppression, immutable hold identity/version edits, route-reference cleanup and invalid polygons. Evidence: `docs/evidence/android-jvm-tests.xml`.
* **Lint: 0 errors, 10 warnings.** All remaining warnings report newer dependency versions; the intentionally pinned baseline needs a planned dependency/16 KB native-library compatibility review before release. No lint baseline or error suppression was used. Evidence: `docs/evidence/android-lint.txt`.
* **APK:** `artifacts/ClimbTriage-0.1.0-debug.apk`, 64,494,174 bytes, package `app.climbtriage`, API 26 minimum / target 35. APK Signature Scheme v2 verifies; evidence: `docs/evidence/apk-signature.txt`.
* APK SHA-256: `9ac30cc3a971e082074bb0a8d5023b0b4df78647af2a0cd07fd868fd54cda73e`.
* The actual packaged pose task was extracted from the APK for a hash comparison and matches the official downloaded/pinned asset: `59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a`. Download metadata: `docs/pose-model-download.json`.
* `adb devices -l` returned an empty device list. No APK installation, launch, MediaCodec decode, Room persistence round trip or native MediaPipe inference was executed on an Android device/emulator here.
* Setup encountered Google's new SDK CLI package syntax, a nonzero SDK-install exit despite installed artifacts, and Windows Gradle cache rename failures. Installed SDK files were checked by the actual compiler. With Gradle stopped, the complete `media3-ui` extraction was moved to the exact destination already named in the Gradle error. Its content was unchanged; subsequent compilation/tests/lint passed. File watching is disabled; host security settings were not changed.

`artifacts/build-manifest.json` records artifact hashes and the achieved/pending checks in machine-readable form. The reference checkout remains unchanged at the reviewed commit.

## Known prototype limits

* Import only, no CameraX recording/live mode; 2-minute / 300 MiB SDR clips, API 26+.
* Native decoder YUV access and Media3 timeline/rotation alignment need the physical-device matrix. Unsupported geometry fails; content movement is not automatically detected.
* Conservative torso-distance association can still misassociate a single replacement person. It abstains on detected ambiguity/gaps but is not validated appearance-based re-identification. Reselect after loss. No hidden-joint interpolation.
* Local pose work survives configuration changes through ViewModel but is not a resumable background service. OS process termination requires reimport; partial imports never become completed library records. WorkManager/checkpoint cleanup is M1b.
* Wall candidates come from one first analyzed frame; occluded/missed holds require manual correction. Provider scale/binary format variations fail the adapter contract pending a verified live response.
* Mask outer contours are shown in the editor; original binary PNG masks retain holes on the backend. Simplified outlines are unsuitable for contact inference until the dense-mask contract is used in M2.
* Route color/geometry suggestion thresholds are unvalidated; same-color neighboring routes/volumes need correction.
* Local SQLite contains keyframe bytes and raw provider results for transactional deletion. Production object storage/PostgreSQL/queue adapters, tenant auth and provider retention controls are not implemented.
* No exactly-once provider billing guarantee across a crash between submission and checkpoint. Retry count is bounded, checkpoints retain remote request IDs; local late-worker publication is fenced.
* Undo is available for edits in the current review; persisted immutable version history remains on disk. A full historical revision browser is later work.
* Future contact/attempt metrics, CSV/video export, handheld registration, depth and hardware are intentionally outside this slice.

## Physical-device gate

Use Pixel 8 and Galaxy A54 5G as proposed phones, record exact OS/SKU/thermal conditions. Inspect original-vs-overlay alignment at portrait/landscape rotations and during aspect/letterbox changes. Test VFR/B-frames, seek backward/forward, first PTS offsets, gaps/crossings, missing limbs, neighboring routes, split/merge/reopen, network interruption/duplicate requests/cancellation/deletion. Collect startup and analysis time, sample coverage and memory. Add CameraX before running the 15 updates/s / p95 <200 ms live and ten-minute thermal targets; none are measured in this offline slice.
