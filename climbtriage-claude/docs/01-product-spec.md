# 01 — Product specification

Status: draft v0.1 (2026-09-28). Owner: principal engineer. The project plan
`ClimbTriage-project-plan.md` was not available. This spec is derived from the
engineering brief alone.

## Problem

Climbers and coaches film boulder attempts on a phone and then scrub through
the video by hand. They want to know which holds were used, by which limb, in
what order and for how long, and how one attempt differs from another. Today
that means watching and taking notes.

## First useful product (M1)

This is an Android app for **indoor bouldering** with **one selected climber**, filmed
from a **stationary, externally positioned phone** (tripod, bottle or bag).
The user records a clip in the app or imports one. The user can then:

1. **Select the climber** by tapping them on a frame. The app tracks that
   person through the clip and marks the intervals where the track is lost.
   It does not switch to someone else and does not invent hidden limbs.
2. **See the skeleton** of that person overlaid on playback, aligned to the
   video, using the real frame timestamps.
3. **Get hold candidates automatically.** Cloud mode sends one climber-free wall
   image (a per-pixel median of frames) to the ClimbTriage backend, which uses
   SAM 3.1. Offline mode offers a labeled on-device *colour-assist* heuristic.
   Holds can always be added by hand.
4. **Separate wall holds from the route.** The app suggests route membership
   from colour similarity to a hold the user picks. The user confirms, adds
   or removes members, and sets start and finish holds explicitly.
5. **Correct holds** with add, remove, edit (move and scale), split and merge.
   Corrections are stored separately from the raw detections.
6. **Replay** with the skeleton, hold outlines and route highlights aligned. If
   the camera moved, the replay suppresses the overlay instead of drawing it
   in the wrong place.
7. **Save and reopen** the session. Everything stays on the device unless the
   user explicitly chooses cloud detection.

### Explicit non-goals for M1

Contact inference, attempt timing, metrics, export, handheld/panning cameras,
multi-attempt comparison, live overlay during recording, depth/3D, and iOS.
Each is scheduled in `06-backlog.md`.

## Users and modes

| Mode | Needs network | Pose | Holds | Status in M1 |
|---|---|---|---|---|
| Guest / local | no | on-device MediaPipe (bundled model) | manual + colour-assist heuristic + saved maps | built |
| Cloud-assisted | yes, and an explicit per-session opt-in | on-device | SAM 3.1 via backend (server-held credentials) | built; live provider call unverified without a key |
| Signed-in sharing | yes | — | — | not in M1 |

No sign-in is required. Only the climber-free wall image is uploaded, and only
when the user taps "Detect holds (cloud)" and has confirmed the upload.

## Product rules (normative)

These rules carry across milestones. Code that contradicts them is a bug.

- **Identity is explicit.** The selected person has a `PersonTrack`. When the
  association is ambiguous or absent, the samples are `LOST` with no
  landmarks, so missing is never shown as zero. Re-acquisition requires an
  unambiguous match, and the user can reselect at any timestamp.
- **Time comes from presentation timestamps.** Elapsed time never comes from
  frame count × nominal fps. Variable-frame-rate clips must work.
- **Coordinates are declared.** Every stored point names its coordinate system
  (`03`/`05` define them). Rotation, mirroring and aspect ratio go into the
  capture manifest and into transforms, never into ad-hoc math in UI code.
- **Wall holds ≠ route.** A `Hold` belongs to a `WallVersion`. A `RouteVersion`
  references hold IDs and carries start/finish holds, route type, and colour.
  Same-colour neighbouring routes and volumes are supported because membership
  is an explicit list and not a colour filter.
- **Hold IDs are immutable and wall-scoped.** Display numbers are derived per
  route version and never used as identity. Split and merge mint new IDs and
  record their parents.
- **Raw ≠ corrected.** Provider output is immutable (`AnalysisRun`). User
  edits are an append-only `Correction` log. The effective state is a fold of
  the two.
- **No invented physics.** The app does not report force, weight distribution,
  centre of mass, fatigue or a universal technique score. The hip midpoint is
  labelled "hip-midpoint proxy". The app shows meters only with validated
  calibration or depth, which M1 never has.
- **Tracking loss is not a fall.** The highest visible hold is not the finish.
  A pause is not proof of rest.
- **Honest offline mode.** Offline hold detection is a labeled heuristic until
  a real on-device model passes the evaluation plan.

## Success criteria for M1

- A real clip, recorded or imported on a physical Android phone, goes through
  select person → pose → hold candidates → edit → replay → save → reopen.
- The overlay stays aligned in portrait and landscape clips, rotated sources,
  and letterboxed views. Unit tests cover the transforms, and a real clip
  checks them by eye.
- Tracking-loss intervals are visible on the replay timeline.
- The evaluation harness exists and runs on annotated data. M1 does **not**
  claim any accuracy target until a consented, annotated set exists.
