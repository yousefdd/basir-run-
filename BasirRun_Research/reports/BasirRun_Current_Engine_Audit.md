# Basir-Run — Current Engine Audit (round 4, before any change)

Written before round-4 changes. Engine version: app 0.12.0 (code 35), local commit `cc93aa5`. Every number
below was measured by the tools under `BasirRun_Research/tools` on the files named. All timings are from a
desktop CPU. Nothing here was measured on a phone.

## 1. What the engine is made of

| Layer | Files | What it does |
|---|---|---|
| Camera + frames | `app/.../CameraView.kt`, `vision/GuidancePipeline.kt` | CameraX analysis frames → per-frame pipeline; portrait only (`AndroidManifest.xml`) |
| Gravity / tilt | `sensors/MotionMonitor.kt`, `engine/Geometry.kt` (`GravityTilt`, `CameraModel`) | Pitch and roll from the accelerometer, so each pixel row maps to a ground distance |
| Painted line | `engine/ContrastLineDetector.kt`, `engine/LineExtractor.kt`, model `guideline.onnx` (Project Guideline) | Finds a painted guide line; contrast detector at 240 px plus Google's guideline segmentation |
| Walkable ground | `vision/seg/SurfaceModels.kt` (`WalkableSegmenter`), model `walkable_segformer_b0_ade_256.onnx` (SegFormer-B0 ADE20K, NVIDIA research licence), `engine/UnderfootClasses.kt`, `engine/WalkableMemory.kt` | ADE20K argmax at 256×256. Classes floor/sidewalk/path/dirt-track always count; road/grass/earth/field/sand only while under the feet |
| Free space + path | `engine/FreeSpacePlanner.kt` (ground grid), `engine/IntelligentPathPlanner.kt` (modes LINE / LANE_CENTER / LINE_PARALLEL / FREE_PATH / DETOUR / STOP), `engine/PathTracker.kt` (Kalman, 15-frame coast), `engine/PathCurve.kt`, `vision/VirtualPath.kt` | Projects the mask onto a metric grid, searches for routes, picks the mode, tracks the path over time |
| Objects + people | `vision/depth/OnnxModels.kt` (D-FINE-N COCO, Apache-2.0), `engine/Obstacles.kt` (`DetectionFilter`, `Receding`), `vision/depth/DepthHazards.kt` + `FloorPlanner.kt` (Depth Anything V2 Small int8) | Boxes → ground footprint in the corridor; depth finds unlabelled bumps and walls |
| Decision | `engine/StableGuidance.kt` (9 states), `app/.../engine/DecisionEngine.kt`, `AnnouncementPolicy.kt`, `Readiness.kt` | States TRACK_CENTERED, CORRECT_L/R, CURVE_L/R, OBSTACLE_WARNING, STOP_REQUIRED, TRACK_LOST, VISION_UNCERTAIN, with hysteresis, dwell and side persistence |
| Vibration | `engine/Haptics.kt` (16 patterns, `HapticScheduler`), `app/.../HapticBridge.kt`, `docs/haptics_table.md` | Event → pattern, with priorities and repeat limits (stop: once + 2 reminders) |

Answer to "lines only, or ground understanding?": the engine is **not** lines-only. It has a walkable-ground
channel (semantic segmentation) and a free-space planner, so it can follow a path that has no painted
line. But that channel only knows ADE20K *object categories*. It knows nothing about *surface quality*
(smooth or rough, gravel, mud, wet). It also has **no confidence or "unclear" zone**: each pixel is
either walkable or not.

## 2. The twelve problems, one by one

| # | Problem | Status in 0.12.0 | Evidence |
|---|---|---|---|
| 1 | No road found without painted lines | **Partly solved.** FREE_PATH mode follows the walkable mask with no line | SANPO 280 frames: walkable corridor IoU 0.739 (`benchmarks/results/metrics_round2.txt`). **New failure:** on a forest dirt trail (archive.org CC0 clip, `videos/_ia_raw` / `experiment_results/external_clips/cc0_walking_path.mp4`) ADE labels the trail under the feet as **"tree" (93 %)** and the trail ahead as "grass" (68 %). Result: 120/600 frames STOP_REQUIRED, 7 felt stop vibrations in 60 s |
| 2 | Real lines confused with shadows | **Mitigated.** The contrast detector weighs lightness at half, and a short dropout is held quietly (`StableGuidance.kt:217`, `ContrastLineDetector.kt:80`) | Owner night clip: tracking failure 6 % (`metrics_round2.txt` §3). No shadow-specific labelled set exists |
| 3 | Path lost on curves | **Open.** Curves are mostly followed as repeated corrections. Centreline curve estimation was tried and disabled (`CorridorCenterline.kt`, `benchmarks/results/centreline_grid.txt`) | Scenario suite: a right curve is cued at 0.7 s (`scenario_suite.txt`). On the owner's athletics clip the lane was lost on the bend until the coasting rule was added |
| 4 | Walkable area unknown | **Partly solved**, see #1. Weak on trails, grass and forest | Seen on the trail clip; RUGD off-road walkable IoU is low for ADE (`rugd_walkable_summary.txt`) |
| 5 | Virtual path unstable | **Mitigated.** One Euro smoothing plus Kalman tracker | Smoothing grid (`smoothing_grid.txt`), opposite cues within 1 s = 0 in the suite |
| 6 | Contradictory instructions | **Mitigated.** Side persistence, opposite-side guard 1 s, dwell 0.6 s | Scenario suite: opposite_within_1s 0, direction changes 2 |
| 7 | Unstable obstacle detection | **Partly.** Tracker with runner advance, Receding filter, DetectionFilter. **New false classes found** in round 4: **"bear"** on a forest trail (30 stop frames) and **"tie"** in a running crowd (8 frames) | `visual_reports/external_*/*/engine.csv`, column `blocking` |
| 8 | Ground types not understood | **Open.** Labels are ADE objects (floor, road, grass, earth). There is no smooth/rough/gravel/wet distinction and no "caution" zone | Code: `UnderfootClasses.kt` lists only the ADE ids {3,11,52,91} and {6,9,13,29,46} |
| 9 | Camera angle / perspective errors | **Mitigated.** Gravity pitch/roll and an independent 3-D angle test (`AngleConventionTest`). The phone-angle readiness check was removed at the owner's request | 72 engine tests pass. Clips filmed with a sideways camera (Tokyo walk) give STOP/UNCERTAIN, which is correct because no ground is visible |
| 10 | Delay between frame and vibration | **Not measured on a phone.** Desktop medians: walkable 45–93 ms, line 35–65 ms, detector 83–218 ms per frame (four internet clips, `visual_reports/external_*`) | G14 (phone timing) is still an open handoff |
| 11 | Unnecessary stops | **Improved, not solved.** Suite: felt stops 35 → 18 against 0.10.0. On new internet clips: forest trail 7 felt stops/min (wrong ground class), running crowd 3 per 57 s (person ahead, same direction) | `visual_reports/external_*/*/engine.csv` (`felt`) |
| 12 | Poor generalisation to unseen places | **Main weakness.** ADE B0 at 256 px fails on forest dirt (#1). The Cityscapes model fixes the athletics track but raises false-walkable (0.366 → 0.422) | `benchmarks/results/street_models_sanpo.txt` |

## 3. Behaviour of the current engine on the first four internet clips

Same code as 0.12.0, run by `tools/visual_test.py` at 10 fps. The overlay videos are in `visual_reports/external_*`.

| Clip | Frames | STOP_REQUIRED | VISION_UNCERTAIN | OBSTACLE_WARNING | Felt stops | Main reason |
|---|---|---|---|---|---|---|
| whittier_5k_run (running race, portrait phone) | 573 | 198 | 363 | 7 | 3 | runners ahead (`person`), walkable ends |
| pitt_bridge_walk (boardwalk) | 600 | 11 | 25 | 290 | 3 | walking tour group ahead |
| cc0_walking_path (forest dirt trail) | 600 | 120 | 226 | 43 | 7 | trail labelled tree/grass; false "bear" |
| tokyo_walk (shopping street) | 600 | 172 | 265 | 157 | 8 | camera filmed shop fronts sideways (not a valid forward view) |

## 4. Memory and performance

Model files (approximate size in the APK): D-FINE-N 15 MB, Depth Anything V2 Small int8 ≈ 26 MB,
SegFormer-B0 256 ≈ 15 MB, guideline ≈ 0.6 MB, EfficientDet fallback ≈ 4 MB. All models run on CPU through
ONNX Runtime 1.20 (`app/build.gradle.kts:77`). No phone memory or battery measurement exists (G14 handoff).

## 5. Fixed in earlier rounds (kept, not re-opened)

- Stop vibration ran continuously → now once plus 2 reminders.
- The obstacle tracker ignored the runner's own advance → fixed.
- "train" false detection stopped the walker → DetectionFilter.
- Underfoot class flicker (grass ↔ earth) → UnderfootClasses memory.
- Lane lost on the track bend → coasting rule.
- Stop on block → turn-away DETOUR when the open side is verified.

## 6. What round 4 must answer (from this audit)

1. A ground model that knows trails and surface types, tested on the same clips and the same labels.
2. Zones with doubt: walkable / caution / obstacle / unclear / unverified, instead of yes/no.
3. Filtering of implausible detector classes (bear, tie and similar) on the running corridor.
4. A wider, licensed library of real walking and running clips, so that #11 and #12 are measured on more than four clips.
