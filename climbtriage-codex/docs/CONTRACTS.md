# Version 1 contracts

Wire JSON uses snake_case, integer microseconds, UUID identifiers and explicit schema_version=1. Android local JSON and backend OpenAPI are executable narrower M1 schemas; the remaining records below specify module boundaries for M2+. Unsupported schema versions fail rather than silently migrate. Immutable files contain raw candidates; version files/corrections never overwrite them.

| Record | Required fields / invariants |
|---|---|
| Capture | id, content_sha256, original media, duration_us, timebase, first_pts_us, width/height, rotation_degrees, mirrored, pixel_aspect, inference_to_video, optional depth/calibration manifest; original preserved; no assumed depth |
| WallVersion | wall_id, version_id, parent_version_id?, capture_id/reference_frame_us, hold geometries; wall reset uses new wall_id; uncalibrated stationary wall space only |
| RouteVersion | route_id, version_id, parent_version_id?, wall_version_id, member hold UUIDs, start/finish UUIDs, type/rules; starts/finishes subset of membership |
| Hold | immutable wall-scoped id, kind=hold/volume, polygon(s) in reference frame, source observation, display_number separate, retired parent IDs for split/merge |
| PersonTrack | id, capture_id, explicit seed(timestamp,candidate), segments, state=selected/lost/unknown, association algorithm version; no automatic reacquisition after declared loss in M1 |
| PoseSample | timestamp_us, track_id?, coordinate_space, landmarks(name/index,x,y,nullable z,visibility,presence,valid), validity, nullable confidence, provenance; no fabricated hidden joints |
| ContactEvent | id, track_id, limb, target={hold UUID/volume UUID/wall surface/unknown}, [start_us,end_us), boundary uncertainty, evidence references, validity/confidence, algorithm config; unknown intervals explicit |
| Attempt | id, capture_id, route_version_id, start/end optional, censored flags, rule_version, outcome=complete/incomplete/unknown, corrections; missing boundary never zero |
| AnalysisRun | id, input hashes, schema/model/algorithm/config versions, status/progress, checkpoints, raw outputs, coverage, timestamps, error; model alias and revision uncertainty explicit |
| Correction | id, capture_id, parent version, operation, affected IDs, before/after payload or version refs, timestamp, author=local_user, reason optional; immutable append/version chain |

All observations carry timestamp_us, coordinate_space, validity, confidence (null allowed), and provenance {provider,model,model_revision,algorithm,config_hash}. Model revision may explicitly be `unversioned-alias`; it must not silently pretend to pin weights. Wall polygons may contain multiple rings; M1 simple editor uses one outer ring per user polygon; merge preserves separate parts rather than filling empty wall between holds.

## Local backend API

The local backend handles only explicitly uploaded keyframe JPEG/PNG data in M1, not the entire video. Loopback-only prototype; use `adb reverse tcp:8000 tcp:8000` for physical-device development. Public deployment is out of scope until authentication/tenant authorization/HTTPS/quotas are implemented.

* `POST /v1/uploads` {sha256,size_bytes,content_type} -> id,offset. Maximum 12 MiB.
* `GET /v1/uploads/{id}` -> offset/status. Resume without retransmitting accepted bytes.
* `PATCH /v1/uploads/{id}` with `Upload-Offset` and binary chunk -> new offset; mismatches 409; maximum 1 MiB/chunk; digest checked on completion.
* `POST /v1/jobs` {upload_id,timestamp_us,coordinate_space,consent_to_provider:true} plus `Idempotency-Key` -> job. Same key/different payload is 409. Cache identity includes input hash, provider/config/schema epoch and is scoped to live data. No cross-user global cache.
* `GET /v1/jobs/{id}` -> status, phase, completed_units,total_units, retry_count,error/result. Waiting on a provider is indeterminate, not fabricated percent progress.
* `POST /v1/jobs/{id}/cancel` fences publication. Already-completed results remain completed; deletion removes them.
* `DELETE /v1/uploads/{id}` tombstones upload and dependent jobs transactionally, removes bytes/results. A worker with an old lease cannot publish after deletion.

Worker lease, attempt token and persisted request checkpoint fence stale completions. Retries are bounded and backed off. A crash after remote submission but before the remote ID checkpoint may create an orphan provider request; no exactly-once billing claim. Persisted remote request IDs permit queue polling across restarts. Cancellation/deletion prevents local resurrection; provider-side deletion/retention must follow the provider's verified contract before production rollout.

Production maps local SQLite metadata to PostgreSQL, binary files to Blob/object storage, and durable polling to Service Bus. Add authenticated owner_id to every lookup and cache key; generations/tombstones gate object publication. Resumable video uploads and recorded backend pose analysis extend Capture and the same jobs protocol in M2, rather than coupling provider calls to HTTP handlers.

Executable HTTP request schemas are exported in `contracts/openapi-v1.json`. The mobile-facing result omits dense `raw_masks_png_base64` and `raw_provider_response` to bound phone memory; the backend retains them unchanged in the job's result record until deletion. The Android raw-hold file preserves the normalized provider observations before correction. It does not pretend to contain the provider's dense masks.

## Transform conventions

Canonical point is [x,y,1] in upright normalized video space. Frame decoding preserves crop origin and rotation; `inference_to_video` maps inference pixels into this space. For fit preview: scale=min(view_w/video_w,view_h/video_h), offset=(view-size*scale)/2. Drawing and hit-testing share the inverse pair. Reject taps in letterbox bars. Pose replay uses the latest causal sample within 150 ms; do not bridge loss or draw a stale pose. Any later interpolation needs explicit validity across both endpoints.

## Correction semantics

Removing a hold retires it in the new wall version and removes membership/start/finish references in the new route version. Editing a polygon retains the hold's identity. Split creates two new UUIDs with one parent; merge creates one new UUID retaining all parts and both parent IDs. Derived route membership transfers if a parent was selected, while start/finish are cleared and must be confirmed on new children. Save writes a new immutable version bundle then atomically advances the Room session index. A crash may leave an unreferenced version, but must not corrupt the current one. Raw inference remains unchanged.
