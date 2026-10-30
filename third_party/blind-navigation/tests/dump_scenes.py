"""Emit synthetic scenes + Python planner results as JSON (used by test_planner_js.mjs)."""
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path[:0] = [str(HERE.parent / "scripts"), str(HERE)]

from path_planner import CameraGeometry, PathPlanner  # noqa: E402
from synthetic import render_disparity  # noqa: E402

SCENES = {
    "empty": {},
    "box_ahead": {"boxes": [(-0.3, 0.3, 0, 1.0, 1.5, 1.9)]},
    "box_left": {"boxes": [(-1.2, -0.45, 0, 1.0, 1.5, 1.9)]},
    "detour": {"boxes": [(-0.45, 0.15, 0, 1.0, 1.8, 2.1)]},
    "stop": {"boxes": [(-0.35, 0.35, 0, 1.5, 1.0, 1.3)]},
    "wall": {"boxes": [(-5, 5, 0, 3, 0.6, 0.8)]},
    "head_level": {"boxes": [(-0.5, 0.5, 1.3, 1.9, 1.5, 1.7)]},
    "drop": {"holes": [(-1.0, 1.0, 1.6, 3.5)]},
    "affine": {"boxes": [(-0.3, 0.3, 0, 1.0, 2.5, 2.9)], "scale": 37.0, "shift": 4.0},
    "table_top": {"boxes": [(-0.8, 0.8, 0.70, 0.75, 0.3, 1.6)]},
    "low_table": {"boxes": [(-0.6, 0.6, 0.40, 0.45, 0.9, 1.8)]},
    "deep_drop": {"holes": [(-1.0, 1.0, 1.6, 3.5)], "hole_depth": 0.3},
}
# Same scenes again with the tilt estimated from the image (pitch unknown)
AUTO = {f"{k}_auto": dict(v, pitch_deg_true=12.0) for k, v in SCENES.items() if k != "affine"}

out = []
for name, kw in {**SCENES, **AUTO}.items():
    kw = dict(kw)
    auto = "pitch_deg_true" in kw
    if auto:
        kw["pitch_deg"] = kw.pop("pitch_deg_true")
    disp = render_disparity(**kw)
    a = PathPlanner(CameraGeometry(pitch_deg=None if auto else 20.0)).analyze(disp).to_dict()
    out.append({"name": name, "auto": auto, "width": disp.shape[1], "height": disp.shape[0],
                "disparity": [round(float(x), 6) for x in disp.ravel()], "python": a})
json.dump(out, sys.stdout)
