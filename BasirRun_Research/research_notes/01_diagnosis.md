# Basir-Run vision engine: diagnosis before changes

Version examined: app 0.9.23 (commit 23902e7 and later, local). Read: `engine/` (Virtual Path
Engine, pure Kotlin), `app/.../vision/**`, `app/.../engine/**` (decision, readiness, haptics
bridge), `MotionMonitor`, `CameraView`, `GuidancePipeline`. Field report from the user
(2026-10-30): easy track worked until a small change of phone tilt; rocky ground never started
(waited, then stopped); a far wall on the right caused a stop; night not yet described.

## Pipeline as it runs today

```
CameraX 640x480 YUV ──► GuidancePipeline.process (camera thread)
   ├─ FrameQuality (light, blur, glare, blocked)
   ├─ LaneDetector + PathRegionDetector ─► EnvironmentSelector ─► PathTracker (image space)   [fallback]
   ├─ DeepChannel (worker): D-FINE-N objects + Depth Anything V2 S ─► FloorPlanner ─► DepthHazards
   ├─ EfficientDet-Lite0 every 3rd frame when D-FINE-N is not live
   └─ LineEngine: 240 px RGB ─► ContrastLineDetector ─► LineExtractor ─► IntelligentPathPlanner
                                 (CIELAB stripe test)     (chains per band)   (line / lane / parallel)
                                 ─► GuidanceController (pure pursuit) ─► GuidanceCommand
DecisionEngine (main thread): angle, latency, depth hazard, line command, image path ─► Decision
HapticBridge ─► HapticScheduler (engine) ─► vibration;  AnnouncementPolicy ─► recorded voice
```

## Findings, by user-visible problem

| # | Problem | Cause in code | File / unit |
|---|---------|---------------|-------------|
| 1 | Small tilt change breaks guidance, never recovers | `DecisionEngine` stops with `AngleStop` when pitch differs from the pitch saved at start by more than 15° for 1 s. A chest-mounted phone swings more than that while running. Recovery only happens if the pitch returns near the saved value; there is no re-reference during a run. | `app/.../engine/DecisionEngine.kt` (`pitchToleranceDeg`, `calibratedPitchDeg`), `GuidanceSession` |
| 1b | Lines jump laterally with each stride | Pitch is the raw gravity angle per sample (no smoothing, no gyro fusion). Roll is ignored by the ground projection (`CameraModel` has pitch only), so a rolled phone maps a straight line to a slanted one: lateral error grows with distance and the tracker's 0.6 m gate at 3 m rejects the measurement → coasting → LOST. | `sensors/MotionMonitor.kt`, `engine/Geometry.kt` (`CameraModel`), `engine/PathTracker.kt` |
| 2 | Rocky / unmarked ground: waits forever, then stops | `LineEngine` calls the planner with `freeRoute = null, freeGrid = null`: the engine's `FreeSpacePlanner` is never fed, so without a line the planner can only return `STOP(NO_PATH)`. The image-space fallback (`LaneDetector`, `PathRegionDetector`) needs lane edges or a uniform surface; stones give neither. `Readiness` requires the Path check, so the run never starts. | `vision/line/LineEngine.kt`, `engine/IntelligentPathPlanner.kt`, `engine/Readiness.kt` |
| 2b | Rocky ground also breaks the depth floor | `FloorPlanner` fits one plane in disparity (RANSAC τ=0.015, ≥30 % inliers). Uneven stones lower the inlier share → `floorValid=false` → the Depth readiness check fails too. | `vision/depth/FloorPlanner.kt`, `DepthHazards.kt` |
| 3 | Far wall on the right stops the runner | Depth hazards are measured in a straight body-wide corridor (heading 0°) up to 6 m, not along the path being followed. When the path bends along a wall, the straight corridor runs into the wall: `clear < 4 m` warns, `≤ 2.5 m` stops. Roll (1b) makes it worse. Head-level test (`freeHighM`) also counts the top of a wall seen ahead. | `vision/depth/FloorPlanner.kt` (`straight`), `DepthHazards.assess`, `DecisionEngine` |
| 4 | Shadows taken for lines | `ContrastLineDetector` weights lightness at 0.25 in colour scenes, which suppresses most shadows, but a narrow shadow (pole, branch) with a slight colour cast still passes the "stripe differs from both sides" test. Night/colourless scenes switch lightness to full weight, where every shadow edge pair qualifies. | `engine/ContrastLineDetector.kt` |
| 5 | One line chosen among several | Planner takes `lines.firstOrNull { |lateral| ≤ 0.6 m }` (highest score first). With two close parallel lines the choice can flip between frames; hysteresis exists only for mode changes. | `engine/IntelligentPathPlanner.kt` |
| 6 | Line colour close to the ground | The detector needs ΔE ≥ 12 (CIELAB) between line and both sides. Faded paint, grey on grey, or a line under dust is below threshold by design; there is no learned model to fall back on. | `engine/ContrastLineDetector.kt` |
| 7 | Line lost on curves | `LineExtractor` predicts the next band with a slope clamped to ±2 and a gate of 0.30 m + 0.15 m/m. Tight curves leave the gate at range; the chain splits into two short chains below `minCoverageM=1.5 m`. | `engine/LineExtractor.kt` |
| 8 | Guidance from old results | Mostly guarded: `maxCoastFrames=15` with decaying confidence, planner needs ≥0.5 confidence, `DecisionEngine` stops on stale frames. Residual risk: the image-space fallback keeps its own smoothed state and can steer for a moment after the line engine has stopped. | `vision/PathTracker.kt`, `DecisionEngine` |
| 9 | Moving people | People come only from D-FINE-N on the depth worker (~1–3 Hz on mid-range phones) or EfficientDet every 3rd frame; tracked in ground coordinates with constant velocity. Latency between a person stepping in and a confirmed obstacle can exceed 1 s. | `DeepChannel`, `engine/Obstacles.kt` |
| 10 | Processing time | Per frame on the camera thread: YUV copy, two image-space detectors, CIELAB conversion of 240×180, line detection, plus a bitmap conversion every frame the worker is idle. Measured p50/p90 are logged per session but no on-device numbers have been collected from the field yet. | `GuidancePipeline` |

## What is sound and should be kept

* Haptic language and scheduler (priorities, latched STOP, no opposite-side flicker, stretched-pulse timing fixed in 0.9.23).
* `LineExtractor` / `PathTracker` structure (chains in ground coordinates, Kalman on curve coefficients, coasting).
* Safety rules: low confidence never gives a direction; STOP on stale camera.

## Not yet testable without data

Night behaviour, wet/glossy ground, worn lines, crowds: need recorded frames (see dataset manifest).
