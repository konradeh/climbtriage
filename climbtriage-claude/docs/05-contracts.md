# 05 — Data and API contracts (schema `climbtriage.v1`)

The machine-readable source of truth is `contracts/schema/climbtriage.v1.schema.json`
(JSON Schema 2020-12). It is mirrored by:

- Kotlin: `android/app/src/main/java/app/climbtriage/contracts/Contracts.kt`
  (kotlinx.serialization)
- Python: `backend/climbtriage_backend/contracts.py` (Pydantic v2)

Both sides are tested against the same golden example in
`contracts/examples/session.v1.json`.

## Versioning rules

- Every top-level document carries `"schema": "climbtriage.v1"`.
- Additive, optional fields keep the same version. Renames, removals and
  semantic changes bump it to `v2` and ship with a migration.
- Readers reject unknown **major** versions rather than guessing.
- Cache keys include the schema version, so a contract change can never
  reuse a stale result.

## Cross-cutting fields on every observation

| Field | Meaning |
|---|---|
| `tUs` | Presentation timestamp in µs on the capture's timebase. **Never** derived from frame count. |
| `frameIndex` | Display-order frame index. It is informational; `tUs` is authoritative. |
| `coords` | One of `frame_norm`, `wall_norm`, `inference_norm`, `video_px`. |
| `validity` | `VALID` · `LOW_CONFIDENCE` · `AMBIGUOUS` · `LOST` · `NOT_ANALYZED`. Missing is not zero. |
| `confidence` | [0,1] from the model, or `null` when the model provides none. |
| `provenance` | `{kind: model|heuristic|user|fixture, name, version, runId, config hash}`. `fixture` output must never be presented as real inference. |

## Entities

### Capture (manifest)
The `id`, `createdAt`, `source` (`recorded`/`imported`) and `mediaUri` are
app-private. The content hash is `mediaSha256`. `video` holds
`{widthPx, heightPx, rotationDeg, mirrored, displayWidthPx, displayHeightPx,
durationUs, codec, nominalFps?}`. `timebase` holds
`{kind: "media_pts", ptsUs: [..] | ptsRef}` with every sample time in display
order. `device` holds `{manufacturer, model, sdkInt}`. The camera fields are
`cameraFacing` and `stationaryClaim` (what the user said) and
`stillness: {verdict: STILL|MOVED|UNKNOWN, maxShiftNorm, method}` (what we
measured).
Optional, currently always absent: `depth`
`{kind, widthPx, heightPx, units, ref, intrinsics, alignedTo}`,
`intrinsics {fx, fy, cx, cy, widthPx, heightPx}`, `cameraPose[]` and
`calibration {validated: bool, method}`.

### WallVersion
The fields are `id`, `wallId`, `version` (a monotonically increasing integer),
`createdAt`, `parentVersionId?`, `reason` (`initial`/`correction`/`reset`),
`coords: wall_norm`, the `referenceCaptureId`, `holds: Hold[]`, the
`sourceRunIds[]` it folds, and `correctionSeq`, the last correction folded in.
A **wall reset** starts a new `wallId`. A correction creates a new version of
the same wall.

### Hold
`id` is immutable and wall-scoped (`h_<26-char ULID>`). `kind` is
`hold`/`volume`/`unknown`. `polygon` is `[[x,y],...]` in `wall_norm`, with
`bbox`. `color` is `{lab: [L,a,b], hex, method}` or null. `confidence` is
nullable. The remaining fields are `provenance`, `parents: [holdId]` (from
split/merge) and `retired: bool`.

### RouteVersion
The fields are `id`, `routeId`, `version`, `wallVersionId`, `name?`, `grade?`,
`colorHint?` and `routeType` (`boulder`/`toprope`/`lead`/`circuit`). `members`
holds hold ids and `startHoldIds` and `finishHoldIds` are set explicitly.
`displayNumbers: {holdId: n}` is derived, bottom to top, and is **never**
identity. `rules` holds `{startRule, finishRule}` and is overridable per route
type.

### PersonTrack
The fields are `id`, `captureId` and `seed: {tUs, detectionIndex, selectedBy:
user}`. `samples: PoseSample[]` holds one sample per analysed frame, including
`LOST`/`AMBIGUOUS`. `lostIntervals: [{startUs, endUs, reason}]`. The tracker
config and version are recorded in `provenance`.

### PoseSample
The fields are `tUs`, `frameIndex`, `validity`, `coords: frame_norm` and
`landmarks: [{name, x, y, z?, visibility?, presence?}]`, which is **empty**
when the sample is not `VALID`/`LOW_CONFIDENCE`. The skeleton is
`mediapipe33` or `coco17`. `bbox` and `associationCost?` are also stored.

### ContactEvent (M2; declared now)
The fields are `id`, `personTrackId`, `limb`
(`left_hand|right_hand|left_foot|right_foot`), `target`
(`{holdId}` | `{surface: wall|volume, holdId?}` | `unknown`),
`startUs`, `endUs`, `state` (`CONTACT`/`UNKNOWN`), `evidence`
`{minDistanceHoldUnits, dwellUs, motionRelToHold}`, `footPoint` (`toe_heel` or
`ankle_approx`, labelled), `confidence` and `provenance`. A 2D contact is **not**
a claim of physical contact or force.

### Attempt (M2; declared now)
The fields are `id`, `captureId`, `routeVersionId`, `startUs?` and `endUs?`,
each with `boundarySource: auto|user`, and `outcome`
(`unknown`/`sent`/`not_sent`/`truncated`). **Tracking loss is not a fall.**

### AnalysisRun
The fields are `id`, `kind` (`pose_detection`/`hold_candidates`/`tracking`/
`contact`), `inputSha256`, `provider`, `model`, `modelVersion`, `configHash`,
`schema`, `startedAt`, `finishedAt`, `status`, `coverage`
`{framesRequested, framesAnalyzed, framesDropped}`, and `outputRef`. Outputs
are immutable.

### Correction
The fields are `id`, `seq` (monotonic per wall), `createdAt`, `author`
(`local-user` in M1), `target` (`wall`/`route`/`track`) and `op`, one of:
`ADD_HOLD`, `REMOVE_HOLD`, `EDIT_HOLD`, `SPLIT_HOLD`, `MERGE_HOLDS`,
`SET_HOLD_KIND`, `ADD_ROUTE_MEMBER`, `REMOVE_ROUTE_MEMBER`, `SET_START`,
`SET_FINISH`, `SET_ROUTE_TYPE`, `RESELECT_PERSON`. `payload` is op-specific.
**Raw outputs are never mutated.** The effective state is
`fold(rawRuns, corrections[0..seq])`.

## Backend HTTP API (v1)

| Method | Path | Notes |
|---|---|---|
| `POST` | `/v1/uploads` | `{sizeBytes, sha256, contentType}` → `{uploadId, offset: 0}` |
| `PUT` | `/v1/uploads/{id}` | Body = chunk, `Content-Range: bytes a-b/total`. The server rejects a gap. Resend from `offset`. |
| `GET` | `/v1/uploads/{id}` | `{offset, complete}` |
| `POST` | `/v1/jobs` | Header `Idempotency-Key`. Body `{kind: "hold_candidates", uploadId, params: {prompts, minScore}}`. A replay with the same key returns the same job; a different body returns 409. |
| `GET` | `/v1/jobs/{id}` | `{status, step, stepsTotal, stepName, attempts, error?, result?}` |
| `POST` | `/v1/jobs/{id}/cancel` | Cooperative. The job ends `cancelled`. |
| `DELETE` | `/v1/jobs/{id}` | Tombstones the job and deletes its artefacts. Late workers cannot recreate them. |
| `GET` | `/v1/health` | Includes `provider: configured|missing`. It never echoes secrets. |

A job result (`hold_candidates`) is an `AnalysisRun` plus `holds: Hold[]` in
`frame_norm` of the uploaded wall image, with `provenance.kind = model`. The
job fails when no real provider is configured. The fixture provider can be
enabled **only** with `CLIMBTRIAGE_PROVIDER=fixture`, and its output is
stamped `provenance.kind = fixture`.
