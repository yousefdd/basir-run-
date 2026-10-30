# blind-navigation (reference copy)

The walking-assistant tool handed over for integration (see `HANDOFF.md`). It is kept here as
the reference for the Android port; none of these files is built into the app.

What the app uses from it:

| Part of the tool | In the app |
|---|---|
| `mobile/planner.js` / `scripts/path_planner.py` (floor, obstacles, head level, drops, free headings) | `app/src/main/java/com/basir/run/vision/depth/FloorPlanner.kt`, checked against the Python results in `FloorPlannerTest` |
| `tests/synthetic.py` | `app/src/test/java/com/basir/run/vision/depth/SyntheticDepth.kt` |
| Depth Anything V2 Small (ONNX) | downloaded by the build, run with ONNX Runtime in `DepthEstimator` |
| `models/yolo26n.onnx` + names | `app/src/main/assets/`, run in `YoloDetector` |

What the app deliberately does differently, for a runner rather than a walker:
- distances are never spoken (they are geometric estimates, not measurements);
- the planner's suggested turn is not used: no way around an obstacle is offered;
- wider thresholds (4.0 m caution, 2.5 m stop) because a runner covers about 3 m a second;
- the speech module (`guidance.py` / `guidance.js`) is replaced by the app's vibration-first policy.

Licences: Depth Anything V2 Small is Apache 2.0. YOLO26n is published by Ultralytics under
AGPL-3.0; distributing the app with it means distributing the app's source under AGPL-3.0
(or obtaining an Ultralytics licence).
