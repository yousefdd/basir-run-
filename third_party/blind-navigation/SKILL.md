---
name: blind-navigation
description: "Walking assistant for blind and visually impaired users — obstacles, free path, head-level and drop hazards, spoken Arabic/English guidance"
version: 1.0.0
category: accessibility
entry: scripts/navigate.py
deploy: deploy.sh

requirements:
  python: ">=3.9"
  onnxruntime: ">=1.17.0"
  platforms: ["linux", "macos", "windows"]

parameters:
  - name: language
    label: "Guidance Language"
    type: select
    options: ["ar", "en"]
    default: "ar"
    group: Guidance

  - name: camera_height_m
    label: "Camera Height (m)"
    type: number
    min: 0.5
    max: 2.0
    default: 1.3
    group: Camera

  - name: camera_pitch_deg
    label: "Camera Tilt Down (° or auto)"
    type: string
    default: "auto"
    group: Camera

  - name: camera_hfov_deg
    label: "Horizontal Field of View (°)"
    type: number
    min: 40
    max: 120
    default: 70
    group: Camera

  - name: safe_distance_m
    label: "Safe Distance (m)"
    type: number
    min: 1.0
    max: 5.0
    default: 2.0
    group: Guidance

  - name: stop_distance_m
    label: "Stop Distance (m)"
    type: number
    min: 0.5
    max: 3.0
    default: 1.2
    group: Guidance

  - name: detect_drops
    label: "Detect Steps Down / Holes (experimental)"
    type: boolean
    default: true
    group: Hazards

  - name: detect_objects
    label: "Name Obstacles (YOLO26)"
    type: boolean
    default: true
    group: Hazards

  - name: describe_scenes
    label: "Describe Surroundings (VLM)"
    type: select
    options: ["on_change", "on_request", "off"]
    default: "on_change"
    group: Guidance

  - name: fps
    label: "Frame Rate"
    type: select
    options: [1, 3, 5, 10]
    default: 3
    group: Performance

  - name: depth_precision
    label: "Depth Model Precision"
    type: select
    options: ["fp32", "int8"]
    default: "fp32"
    group: Performance

capabilities:
  live_navigation:
    script: scripts/navigate.py
    description: "Obstacle detection, walking path and spoken guidance on a live camera"
---

# Blind Navigation (مساعد المشي للمكفوفين)

Turns a body-worn or hand-held camera into a walking assistant. Every frame it
answers four questions and speaks one short instruction:

1. **Where is the floor, and how far can I walk straight?**
2. **Which way is free** — keep going, bear left/right, slow down, or stop?
3. **What is in the way** — a person, a car, a bench — and how far?
4. **Hidden hazards** a white cane misses — head-level branches/signs — and
   steps down / holes (experimental).

When the user enters a new place, the Aegis VLM describes it in one or two
sentences ("a corridor with a door on the right…").

> ⚠️ An aid, not a replacement for a white cane, guide dog, or orientation &
> mobility training. Monocular depth can miss glass, thin poles and fast
> objects, and distances are approximate.

## How it works

| Stage | Model / method | Output |
|-------|----------------|--------|
| Depth | Depth Anything v2 Small (ONNX) | relative depth map |
| Floor | RANSAC plane fit in inverse-depth space | walkable floor + metric scale from camera height/tilt |
| Obstacles | pixels closer than the floor behind them | distance, side, height above ground |
| Path | 0.75 m corridor swept at −20…+20° | free meters per heading → action |
| Objects | YOLO26n (ONNX, bundled) | names + distance of obstacles, vehicle warnings |
| New place | PySceneDetect content score vs. keyframe + HSV histogram | triggers VLM scene description |
| Speech | hysteresis + rate limits, urgent messages first | one short Arabic/English sentence |

Distances come from flat-ground geometry: set **camera height** to match how the
camera is worn (chest strap ≈ 1.3 m, glasses ≈ 1.6 m). The **tilt** is estimated
from each frame by default (the floor plane's horizon), or taken from the phone's
motion sensor (`pitch` on the HTTP API), or fixed with `camera_pitch_deg`.

The floor is the *lowest* horizontal surface in view, so table/bed tops in front
of the camera are reported as obstacles rather than walkable floor.

**Validated on 150 real indoor photos** (hallways and labs, walking-height
camera, hand-labeled obstacles): 92% of near obstacles detected; see `HANDOFF.md`.

## Protocol

### Aegis → Skill (stdin)
```jsonl
{"event": "frame", "frame_id": 42, "camera_id": "chest", "frame_path": "/tmp/aegis_detection/frame_chest.jpg", "timestamp": "..."}
{"command": "describe"}
{"command": "config-update", "config": {"language": "en", "safe_distance_m": 2.5}}
{"command": "stop"}
```

### Skill → Aegis (stdout)
```jsonl
{"event": "ready", "model": "depth-anything-v2-small + yolo26n", "device": "cuda", "language": "ar", "fps": 3, "scene_description": true}
{"event": "navigation", "frame_id": 42, "camera_id": "chest", "guidance": {"action": "slight_right", "key": "slight_right", "text": "شخص أمامك على بعد مترين، انحرف قليلاً إلى اليمين", "priority": 2, "speak": true}, "path": {"floor_valid": true, "action": "slight_right", "target_heading_deg": 10.0, "clear_distance_m": 2.0, "headings": [...], "sectors": {"left": 2.1, "center": 2.0, "right": null}, "nearest_obstacle": {...}, "head_level": null, "drop": null}, "new_place": false}
{"event": "detections", "frame_id": 42, "camera_id": "chest", "objects": [{"class": "person", "confidence": 0.91, "bbox": [410, 120, 560, 620], "distance_m": 2.0, "bearing_deg": -3.1}]}
{"event": "scene_description", "text": "ممر داخلي طويل، يوجد باب مفتوح على يمينك.", "speak": true}
{"event": "transform", "frame_id": 42, "camera_id": "chest", "transform_data": "<base64 JPEG overlay>"}
```

Each frame produces exactly one `detections` (or `error`) event, last, so the
request/response flow of the detection protocol is preserved. Speak
`guidance.text` when `guidance.speak` is true; `priority: 3` is urgent (stop,
head-level obstacle, drop).

| `action` | Meaning |
|----------|---------|
| `forward` | ≥ safe distance free straight ahead |
| `slight_left` / `slight_right` | ~10° turn finds a free path |
| `left` / `right` | ~20° turn needed |
| `slow` | obstacle within safe distance, no free heading |
| `stop` | obstacle within stop distance |
| `caution` | floor not visible (something very close, or camera pointed up) — treated as stop |

## Setup

```bash
./deploy.sh            # creates .venv, installs onnxruntime, bundles YOLO26n, downloads the depth model
```

Manual:
```bash
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt            # NVIDIA: requirements_cuda.txt
mkdir -p models && cp ../../detection/yolo-detection-2026/yolo26n{.onnx,_names.json} models/
```

## Standalone use (no Aegis)

```bash
# Phone as camera (e.g. an IP-webcam app) or a USB/chest camera, Arabic speech via espeak-ng
python scripts/navigate.py --source 0 --speak
python scripts/navigate.py --source http://PHONE_IP:8080/video --speak --show   # press d = describe, q = quit
# Analyze a photo and write an overlay (green floor, red obstacles, arrow)
python scripts/navigate.py --image street.jpg --out street_nav.jpg
```

## HTTP API (mobile apps)

```bash
python scripts/navigate.py --serve 8765 --token CHANGE_ME [--vlm-url http://localhost:11434 --vlm-model qwen2.5vl]
```

| Endpoint | Body | Returns |
|----------|------|---------|
| `POST /analyze?session=<id>&pitch=<deg>&lang=ar` | JPEG/PNG bytes, or JSON `{"image": "<base64>"}` | `guidance`, `path`, `objects`, `new_place`, `description` |
| `POST /describe?session=<id>` | optional frame | `{"text": "...", "source": "vlm" \| "local"}` |
| `GET /health` | — | status |

`session` keeps guidance hysteresis per user; `pitch` is the live camera tilt
from the phone's motion sensor (portrait: `90 − beta`) and makes distances far
more accurate. Without a VLM, descriptions are built offline from the analysis.
On-device alternative: `mobile/planner.js` + `mobile/guidance.js` (JS ports,
checked against Python by `tests/test_planner_js.mjs`). See `HANDOFF.md`.

## Tests

```bash
python tests/test_blind_navigation.py
node --test tests/test_planner_js.mjs
```

The planner is tested on ray-cast synthetic scenes with known geometry
(obstacle distances, detours, stop, head-level, drop, depth scale/shift
invariance), plus guidance wording, scene-change triggering and a full Aegis
stdin/stdout round trip with stand-in models.
