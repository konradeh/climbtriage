# 04 — Reference feature coverage: `vision-demos`

- Repository: https://github.com/jeremyipark/vision-demos
- Reviewed commit: `6e45309f9ab7f9b7b8707b128bb84294317609e8`. On 2026-09-28
  this was still `HEAD`, verified by clone.
- Licence: Apache-2.0, repository root `LICENSE`. Attribution is in `/NOTICE`,
  and every file that ports reference logic names its source in a header.
- Abbreviations: **RC** = `rock_climbing/`, **3D** = `rock_climbing_3d/`.
  `file:line` refers to the reviewed commit.

## What the reference is, and is not

Both projects are **offline Python batch pipelines**. Each uploads a whole clip
to the VLM Run gateway (OpenAI-compatible chat completions, `extra_body.method`)
and renders an annotated MP4. They show that SAM 3.1 plus ViTPose+ **can** read
a route from real footage. They do **not** provide:

- mobile performance evidence,
- an evaluation set or accuracy numbers (the README reports one tuned handheld
  clip: 13 holds, 9 used, $0.05),
- identity guarantees (the climber track is auto-picked by wall overlap),
- interactive correction.

We treat the reference as a baseline to evaluate, not as proof.

## Coverage matrix

Milestones (M1–M6) are defined in `06-backlog.md`.

| # | Capability | RC source | 3D source | Reuse in ClimbTriage | Missing or must change | Target |
|---|---|---|---|---|---|---|
| 1 | Gateway client, auth via `.env` | `src/env.py:29`, `config.py:151` (`https://gateway.vlm.run/v1/openai`) | same | Contract reused in `backend/.../providers/vlmrun.py`. Key server-side only. | The phone must never hold the key. Pricing and ToS are not published on the model pages. | M1 |
| 2 | SAM 3.1 **video `track`** of a colour prompt | `src/holds.py:100` `request_track`, `:135` `unwrap`, `:181` `observations`; cap 128 frames `:84` | `src/holds.py:63` | Envelope parsing (`vid.segment.masks`, PNG label map where pixel = id) ported. | We prompt all holds generically and keep route membership separate. Tracking is needed only for handheld (M3). | M3 |
| 3 | SAM 3.1 **image `segment`** (reference uses it for the floor) | `src/holds.py:289` `segment_frames`, `:246` `instance_mask`; `src/floor.py:207` | — | **The M1 hold path.** Segment one climber-free median wall image. `instance_id` → label map. | Route membership, volumes, confidence calibration. | M1 |
| 4 | SAM 3.1 **`segment_box`**: missed-hold recovery at limb dwell sites | `src/recover.py:86` `candidates`, `:194` `_segment_box`, `:285` `recover` | — | Adapter method declared (`segment_box`). The M1 editor has a "segment here" hook. | Needs contact dwell (M2) to find sites. | M3 |
| 5 | Camera track: SIFT + MAGSAC homography of every frame to one reference, stillness probe | `src/camera.py:260` `choose_reference`, `:355` `track`, `:221` `register` | replaced by RGB-D odometry | **Concept only in M1**: an on-device stillness check (block matching) suppresses overlays when the camera moved. | Full per-frame registration, and a quality check (residual by depth band) before the overlay is trusted. | M3 |
| 6 | Parallax check (is one homography valid?) | `tools/parallax_check.py:57` | — | Becomes the registration QA gate in M3. | Automatic, not a manual tool. | M3 |
| 7 | Wall mosaic: per-pixel median over warped frames, climber removed | `src/mosaic.py:78` `build`, `:167` `_median` | `src/lidar.py:560` `fuse` (3D) | **M1 uses the stationary special case**: an on-device per-pixel median of sampled frames produces the wall image. | A multi-view mosaic for panning (M3). | M1 (still), M3 (mosaic) |
| 8 | Hold consolidation: per-track canvas median, drift rejection, IoU merge, pixel vote | `src/holds.py:471` `consolidate`, `:519` `merge`, `:561` `shape`, `:360` `_vote` | 3D lifts instead: `src/lidar.py:631` `lift`, `:126` `merge_lifted` | Algorithm noted for M3 (multi-frame). | M1 segments a single image, so there is nothing to consolidate. | M3 |
| 9 | Nested-hold suppression (containment, not IoU) | `src/holds.py:625` `suppress_contained` | — | **Ported** to the backend post-processing. | — | M1 |
| 10 | Numbering bottom→top along the route lean | `src/holds.py:658` `route_lean`, `:670` `assign_ids` | `src/holds.py:198`, `:210` | Idea reused for **display numbers only**. | The reference uses these numbers as identity. We keep immutable IDs separate. | M1 |
| 11 | Drop holds outside the climber's hull | `src/holds.py:762` `filter_by_pose_region` | — | Not used as a filter, because it would silently drop real route holds. It may be offered as a suggestion. | The user confirms route membership. | M2 (suggestion) |
| 12 | Floor segmentation → canvas ground line for the start rule | `src/floor.py:81`, `:153` `consensus_canvas` | floor plane from depth `src/lidar.py:848` `ground_plane` | Deferred. | Start rules depend on route type and must be correctable. | M2 |
| 13 | ViTPose+ video pose, COCO-17, gateway `track_id` | `src/pose.py:65` `build_request`, `:84` `request_poses`, `:103` `unwrap` | `src/pose.py:65` | **Adapter ported** (`providers/vitpose.py`) for offline baseline comparison against MediaPipe. | COCO-17 has no toes or heels (the reference offsets the ankle by `ANKLE_TO_TOE_OFFSET`, `config.py:398`). | M1 (adapter), M2 (eval) |
| 14 | Climber selection = track with most wall overlap | `src/pose.py:143` `pick_track` | `src/pose.py:143` | Not reused as the authority. | **Explicit user selection plus our own tracker with loss marking.** The reference cannot detect a mid-clip identity switch within one gateway track. | M1 |
| 15 | Keypoint smoothing (Gaussian, per contiguous run) | `src/pose.py:231` `smooth` | `src/pose.py:237` | Principle adopted: never smooth across gaps. | Must use PTS spacing, not sample index. Offline only. | M2 |
| 16 | Contact inference: enter/release hysteresis, dwell, graze, deeper-hold transfer | `src/climb.py:221` `analyze`, `:117` `HoldGeometry`, margins `config.py:328-366` | `src/climb.py:222` plus `src/lidar.py:992` `ContactGate` | Design reused (two thresholds, dwell, transfer rule). | **Dwell uses `seconds × fps` frame counts** (`climb.py:231-245`), which breaks on variable-frame-rate clips. We use PTS. There are no `UNKNOWN` intervals: a missing limb closes contact (`climb.py:296`). | M2 |
| 17 | Clock start/stop: feet off the floor with a hand on a hold; both wrists on the top hold | `src/climb.py:221` (final hold `= min(... bbox y)` at `:247`) | same | Rules become route-type-specific and correctable. | **The reference treats the highest hold as the finish.** We require an explicit finish hold from the route version. | M2 |
| 18 | Utilisation, per-limb sequence, hold times | `src/climb.py:560` `Utilization`, `:743` `limb_sequence`, `:793` `hold_times` | `src/climb.py:589` | Output shapes inform M2 metrics and CSV. | Metrics must carry coverage (missing ≠ 0). | M2 |
| 19 | Attempt comparison across clips (wall-to-wall registration, centroid alignment, drift warnings) | `src/compare.py:53` `align`, `:218` `build`; `main.py:797` `_onto_wall`, `:826` `compare_runs` | `main.py:946` **"Not yet valid"**: disabled by default, no cross-capture registration | Design reused for M3. | 3D cross-capture rigid registration is unfinished at the reviewed commit. | M3 (2D), M5 (3D) |
| 20 | Route path spline / movement trace | `src/climb.py:839` `route_path` | `src/space.py:732` `body_travel` | Idea for M3 traces and heatmaps. | Label it the hip-midpoint proxy. | M3 |
| 21 | Rendered MP4 with panels, JSON/CSV/TXT outputs, run provenance | `src/render.py:648`, `main.py:974` `deliver` | `src/render.py` | Output inventory informs M2 export. | Export on the phone uses Media3 Transformer. | M2 |
| 22 | Caching keyed on source and settings, contract version in the key | `src/holds.py:832`, `src/pose.py:340`, `CONTRACT` constants | same | **Adopted**: content hash + provider + model + config + schema version. | — | M1 |
| 23 | Throughput and cost metrics | `src/timing.py:124` `Metrics` | same | Job telemetry fields. | — | M1 (basic) |
| 24 | HDR tone-mapping, transcode | `src/video.py:71`, `:161` | `src/video.py` | Backend-only concern if video upload is added. | The phone decodes natively. HDR must be checked on-device. | M2 |
| 25 | Depth capture format: `video.mov` + `depth.bin` float16 320×240 + intrinsics | — | `src/lidar.py:171` `Capture`, `:236` `load` | **Manifest fields** for depth, intrinsics and timebase follow this contract. | Android depth (ARCore Raw Depth or ToF) varies by device. A static camera limits ARCore depth. The recording must coordinate with the camera session. | M5 |
| 26 | RGB-D odometry (PnP against keyframe map) | — | `src/lidar.py:310` `odometry`, `:426` `_localize` | Later. | — | M5 |
| 27 | 3D holds and skeleton, contact gate in meters | — | `src/lidar.py:631` `lift`, `:762` `lift_skeleton`, `:992` `ContactGate` (hand gate **off** by default, `config.py:341`) | Later. The foot gate at 0.25 m is a design reference. | Needs validated depth. | M5 |
| 28 | Gravity and wall frame from the floor plane | — | `src/lidar.py:848`, `:944` `wall_axes`; `src/space.py:66` `wall_frame` | Later. | — | M5 |
| 29 | 3D visualisation (orbit, PLY export) | — | `src/space.py:561-730`, `src/render.py` | Optional. | — | M6 |
| 30 | Chin-up counting and timing | `chin_ups/src/reps.py` | — | Optional backlog. | — | Backlog |
| 31 | Running cadence and gait | `running/src/gait.py` | — | Optional backlog. | — | Backlog |
| 32 | Dance similarity | `dance_sync/src/similarity.py` | — | Optional backlog. | — | Backlog |

## Reference behaviours we deliberately do *not* copy

1. Converting between time and frames with nominal fps (`climb.py:231-245`).
   We use PTS instead.
2. Treating the top-most hold as the finish (`climb.py:247`). The finish is an
   explicit route attribute instead.
3. Auto-picking the climber by wall overlap as the only identity mechanism
   (`pose.py:143`). We use user selection plus loss marking instead.
4. A colour prompt as the route definition (`README`, `HOLD_COLOR`). Detected
   holds and route membership are separate.
5. A missing limb closing contact (`climb.py:296`). A missing limb creates an
   `UNKNOWN` interval instead.
