"""
Ray-cast tiny synthetic scenes (floor + boxes + holes) into a disparity map,
so the planner can be tested against known geometry without any model.

World frame: X right, Y up, Z forward (meters). Camera at (0, height, 0),
pitched down by ``pitch_deg``.
"""

import math

import numpy as np


def render_disparity(width=160, height=120, cam_height=1.3, pitch_deg=20.0, hfov_deg=70.0,
                     boxes=(), holes=(), hole_depth=0.5, scale=1.0, shift=0.0, max_dist=60.0):
    """
    boxes: (x0, x1, y0, y1, z0, z1) solid obstacles.
    holes: (x0, x1, z0, z1) floor regions that drop by ``hole_depth`` (stairs down).
    Returns affine-transformed disparity (scale / z_cam + shift), like a monocular model.
    """
    f = (width / 2.0) / math.tan(math.radians(hfov_deg) / 2.0)
    cx, cy = (width - 1) / 2.0, (height - 1) / 2.0
    th = math.radians(pitch_deg)
    vv, uu = np.mgrid[0:height, 0:width].astype(np.float64)
    x = (uu - cx) / f
    y = (vv - cy) / f
    # Ray directions (camera z = 1, so t equals depth along the optical axis)
    dx = x
    dy = -y * math.cos(th) - math.sin(th)
    dz = -y * math.sin(th) + math.cos(th)

    t = np.full(x.shape, max_dist)

    def floor_hit(level):
        with np.errstate(divide="ignore", invalid="ignore"):
            tt = (level - cam_height) / dy
        return np.where((dy < 0) & (tt > 0), tt, np.inf)

    t_floor = floor_hit(0.0)
    px, pz = dx * t_floor, dz * t_floor
    in_hole = np.zeros_like(t_floor, dtype=bool)
    for (x0, x1, z0, z1) in holes:
        in_hole |= (px >= x0) & (px <= x1) & (pz >= z0) & (pz <= z1)
    t_low = floor_hit(-hole_depth)
    t = np.minimum(t, np.where(in_hole, t_low, t_floor))

    origin = np.array([0.0, cam_height, 0.0])
    for (x0, x1, y0, y1, z0, z1) in boxes:
        tmin = np.full(x.shape, -np.inf)
        tmax = np.full(x.shape, np.inf)
        for d, o, lo, hi in ((dx, origin[0], x0, x1), (dy, origin[1], y0, y1), (dz, origin[2], z0, z1)):
            with np.errstate(divide="ignore", invalid="ignore"):
                t1 = (lo - o) / d
                t2 = (hi - o) / d
            near = np.where(np.abs(d) < 1e-12, np.where((o >= lo) & (o <= hi), -np.inf, np.inf), np.minimum(t1, t2))
            far = np.where(np.abs(d) < 1e-12, np.where((o >= lo) & (o <= hi), np.inf, -np.inf), np.maximum(t1, t2))
            tmin = np.maximum(tmin, near)
            tmax = np.minimum(tmax, far)
        hit = (tmax >= tmin) & (tmin > 0)
        t = np.where(hit, np.minimum(t, tmin), t)

    return scale / t + shift
