#!/usr/bin/env python3
"""
Parity fixtures for FreeSpacePlanner.kt: occupancy grids of ray-cast scenes and the routes the
reference Python planner (blind-navigation skill, scripts/route_planner.py) finds on them.

  python tools/make_route_fixtures.py /path/to/skills/accessibility/blind-navigation \
      engine/src/test/resources/fixtures/routes.json
"""
import json
import sys
from pathlib import Path

skill = Path(sys.argv[1])
sys.path[:0] = [str(skill / "scripts"), str(skill / "tests")]
import path_planner  # noqa: E402
import route_planner  # noqa: E402
from path_planner import PathPlanner, PlannerConfig  # noqa: E402
from synthetic import render_disparity  # noqa: E402

CFG = route_planner.RouteConfig(cell_m=0.1, half_width_m=3.0, comfort_clearance_m=0.9, clearance_weight=2.0,
                                progress_gain=1.6, min_points_per_cell=2, pursuit_m=2.0, min_progress_m=0.3,
                                waypoint_step_m=0.25)
SCENES = {
    "open_floor": ({}, 0.0),
    "corridor_off_center": ({"boxes": [(-3, -0.4, 0, 2.5, 0.3, 7), (1.4, 3, 0, 2.5, 0.3, 7)]}, 0.0),
    "obstacle_ahead": ({"boxes": [(-0.3, 0.3, 0, 1.0, 1.8, 2.2)]}, 0.0),
    "two_obstacles": ({"boxes": [(-0.6, 0.0, 0, 1.0, 1.6, 1.9), (0.3, 1.2, 0, 1.0, 3.0, 3.3)]}, 0.0),
    "goal_right": ({}, 25.0),
}
grids = []
orig = path_planner.build_grid
def capture(*a, **k):
    free, occ = orig(*a, **k)
    grids.append((free, occ))
    return free, occ
path_planner.build_grid = capture

out = []
for name, (scene, goal) in SCENES.items():
    grids.clear()
    PathPlanner(config=PlannerConfig(lookahead_m=6.0)).analyze(render_disparity(**scene))
    free, occ = grids[-1]
    r = route_planner.plan_route(free, occ, 0.4, goal, CFG)
    out.append({"name": name, "goal": goal, "rows": int(free.shape[0]), "cols": int(free.shape[1]),
                "free": "".join("1" if v else "0" for v in free.ravel()),
                "occupied": "".join("1" if v else "0" for v in occ.ravel()),
                "route": r})
Path(sys.argv[2]).write_text(json.dumps(out))
print("wrote", len(out), "fixtures:", [(o["name"], o["route"] and o["route"]["steer_deg"]) for o in out])
