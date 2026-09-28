# 08 — Assumptions, provider verification, open constraints

## Provider and model verification (checked 2026-09-28)

| Item | What was verified | Source | Status / open question |
|---|---|---|---|
| VLM Run gateway | OpenAI-compatible base URL `https://gateway.vlm.run/v1/openai`; methods selected via `extra_body` | docs.vlm.run/gateway/models/facebook-sam3.1 | **Pricing and ToS are not on the model pages.** Get them in writing before production. Data-retention terms for uploaded images are unknown. |
| `facebook/sam3.1` on the gateway | Methods `segment` (image), `track` (video), `segment_box`. Sampled frames capped at 1–128. PNG label-map masks, background 0, instance ids 1–255. | same | This matches the reference code (`TRACK_MAX_FRAMES = 128`). The resolution limit is not documented. **It has not been called from this environment, because no API key was available.** |
| SAM 3.1 licence | Weights on Hugging Face `facebook/sam3.1` under Meta's **SAM License**, a custom licence that permits commercial use with restrictions | huggingface.co/facebook/sam3.1; github.com/facebookresearch/sam3 | Legal review is required before self-hosting or redistributing. Calling a hosted provider moves the obligations to the provider's terms. |
| `usyd-community/vitpose-plus-large` | 17 COCO keypoints; video output `vid.pose.kpts` with `frame_id` and `track_id`; one clip per request | docs.vlm.run/.../usyd-community-vitpose-plus-large | Weights are **Apache-2.0** (HF card). Training data includes AI Challenger and MPII, which have their own terms, so review before commercial use. COCO-17 has **no toe or heel points**. |
| MediaPipe Pose Landmarker | Android support; IMAGE/VIDEO/LIVE_STREAM modes; `numPoses` default 1; 33 landmarks including heels (29/30) and foot index (31/32); lite, full and heavy `.task` models | developers.google.com/edge/mediapipe/solutions/vision/pose_landmarker | Model card (BlazePose GHUM 3D): **Apache-2.0**, intended for **single-person** video. No cross-frame identity in the API, so ClimbTriage implements its own tracker. |
| `com.google.mediapipe:tasks-vision` | Latest published version 0.10.35 on Google Maven. Maven metadata also lists `0.20230731` and `1.0.0`, which are older or irregular tags. | dl.google.com/android/maven2 metadata | We pin `0.10.35`, and the build verifies it. |
| Reference repository | Apache-2.0, HEAD = reviewed commit | git clone | Attribution in `NOTICE`. |

## Assumptions

1. **M1 capture is stationary.** The stillness check verifies this rather than
   trusting it. When the camera moved, overlays are suppressed for the moved
   frames. We do not attempt registration.
2. `MediaMetadataRetriever.getFrameAtIndex` returns display-order frames.
   Whether it applies the container rotation varies across Android versions,
   so the decoder detects the returned orientation and normalises it.
3. Only back-camera recordings are made in-app, so `mirrored = false`.
   Imported front-camera clips cannot be detected reliably from metadata,
   and the user can toggle "mirrored" in session info.
4. Analysis cadence defaults to ~10 Hz, which trades offline analysis time
   against contact resolution. It is configurable and recorded in the run.
5. The ≤3 people per frame cap is enough for bouldering areas. Additional
   people cause `AMBIGUOUS` samples rather than silent switches.

## Unresolved constraints and risks

| Risk | Impact | Mitigation / next step |
|---|---|---|
| MediaPipe recall on inverted, overhanging or occluded climbing poses | Skeleton gaps, so contacts become `UNKNOWN` | Measure it per scenario. Fall back to backend ViTPose+ for offline analysis if it is materially better. |
| The colour-assist heuristic fails on chalked or multi-colour holds | Many manual edits offline | It is labelled as a heuristic. Cloud SAM 3.1 is the recommended path. The on-device model comes in M4, gated. |
| Gateway cost, latency and availability | Blocks the cloud path | Cache by content hash. The provider adapter can be swapped to self-hosted SAM 3.1 after legal review. |
| `getFrameAtIndex` performance on long clips | Slow analysis | Bounded by cadence and capped. Move to `MediaCodec` + `ImageReader` if too slow. |
| Device thermal throttling (M4 live) | Frame drops | Measured by the ten-minute test before any live claim. |
| ARCore depth with a **static** camera and a moving climber | Depth quality unknown | Evaluate in M5. Depth stays optional. Meters are never shown without validated calibration. |
| No annotated climbing set yet | No accuracy claims possible | The M1 exit gate requires starting consented collection. |
| No physical Android device in this environment | Runtime behaviour is unverified | See `README.md` for what was and was not verified. |
