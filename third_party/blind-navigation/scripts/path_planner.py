"""
Walking-path planner for the Blind Navigation skill.

Turns a monocular relative-depth map (Depth Anything v2 disparity, larger =
closer) into obstacles, free walking distance per heading, and a single
navigation action. Pure numpy so it can be unit-tested without any model.

Method
------
1. Floor fit — the floor is a plane, and for a plane inverse depth is affine
   in image coordinates: d = a*v + b*u + c. We fit it with RANSAC on the lower
   part of the image (robust to obstacles covering part of the floor).
   Affine-invariant disparity (unknown scale/shift) keeps this relation, so
   no metric depth is needed.
2. Equivalent row — any pixel with disparity d sits at the same depth as the
   floor at row r = (d - b*u - c) / a. Camera height + pitch turn that row
   into a ground distance (flat-ground geometry), which gives every pixel an
   approximate metric distance, lateral offset and height above the floor.
3. Obstacles — pixels clearly closer than the floor behind them, between
   ``min_obstacle_height`` and ``max_obstacle_height`` above the ground and
   within ``lookahead_m``. Points above ``head_level_height_m`` are tracked
   separately: a white cane cannot detect them.
4. Drops (experimental) — floor pixels that are clearly *farther* than the
   floor plane predicts (stairs down, holes, platform edges).
5. Headings — for each candidate heading, a corridor of ``body_width_m`` is
   swept forward; the nearest blocking point gives the free distance. The
   action prefers walking straight, then the smallest turn with enough room.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field, asdict
from typing import Optional

import numpy as np


ACTIONS = ("forward", "slight_left", "left", "slight_right", "right", "slow", "stop", "caution")


@dataclass
class CameraGeometry:
    """Where the camera sits on the user. Defaults: chest-mounted phone, tilted down."""
    height_m: float = 1.3      # lens height above the ground
    pitch_deg: Optional[float] = 20.0  # downward tilt of the optical axis; None = estimate from the image
    hfov_deg: float = 70.0     # horizontal field of view

    def focal_px(self, width: int) -> float:
        return (width / 2.0) / math.tan(math.radians(self.hfov_deg) / 2.0)

    def _ray(self, rows, height: int, focal: float):
        """(down, forward) components of the ray through row(s), for a ray with camera z = 1."""
        th = math.radians(self.pitch_deg)
        y = (np.asarray(rows, dtype=np.float64) - (height - 1) / 2.0) / focal
        return y * math.cos(th) + math.sin(th), math.cos(th) - y * math.sin(th)

    def floor_depth(self, rows, height: int, focal: float):
        """Camera depth (along the optical axis) of the floor seen at row(s); inf above the horizon."""
        down, _ = self._ray(rows, height, focal)
        with np.errstate(divide="ignore"):
            return np.where(down > 1e-4, self.height_m / np.maximum(down, 1e-4), np.inf)

    def ground_distance(self, rows, height: int, focal: float):
        """Horizontal distance to the floor point seen at row(s); inf above the horizon."""
        down, fwd = self._ray(rows, height, focal)
        with np.errstate(invalid="ignore"):
            return np.where(down > 1e-4, self.floor_depth(rows, height, focal) * fwd, np.inf)

    def point(self, depth, rows, cols, width: int, height: int, focal: float):
        """World (forward, lateral, height-above-floor) of pixels at a given camera depth."""
        down, fwd = self._ray(rows, height, focal)
        x = (np.asarray(cols, dtype=np.float64) - (width - 1) / 2.0) / focal
        with np.errstate(invalid="ignore"):
            return depth * fwd, depth * x, self.height_m - depth * down

    def horizon_row(self, height: int, focal: float) -> float:
        cy = (height - 1) / 2.0
        return cy - focal * math.tan(math.radians(self.pitch_deg))


@dataclass
class PlannerConfig:
    body_width_m: float = 0.75          # corridor the user needs to pass
    safe_distance_m: float = 2.0        # free distance needed to keep walking
    stop_distance_m: float = 1.2        # closer than this → stop (≈1 s of walking + speech delay)
    lookahead_m: float = 5.0            # ignore obstacles farther than this
    min_obstacle_height_m: float = 0.08 # ignore floor texture / tiny bumps
    max_obstacle_height_m: float = 2.1  # ignore ceilings and tree canopies
    head_level_height_m: float = 1.1    # above this, a cane will not find it
    closer_ratio: float = 0.8           # obstacle if distance < ratio × floor distance behind it
    noise_margin: float = 0.03          # min disparity excess (normalized units)
    drop_ratio: float = 1.25            # drop if floor looks ≥ ratio × farther (≈ a 0.3 m step down)
    drop_range_m: float = 3.0           # only look for drops this close
    detect_drops: bool = True
    headings_deg: tuple = (-35.0, -20.0, -10.0, 0.0, 10.0, 20.0, 35.0)
    min_pixels: int = 8                 # robust min: kth-nearest point in a corridor
    ransac_iters: int = 150
    ransac_tau: float = 0.015
    min_floor_inliers: float = 0.3      # fraction of candidate pixels on the floor plane
    turn_penalty_m_per_deg: float = 0.01
    fallback_pitch_deg: float = 20.0    # used when the tilt is unknown and cannot be estimated


@dataclass
class PathAnalysis:
    floor_valid: bool
    floor_confidence: float
    action: str
    target_heading_deg: float
    clear_distance_m: Optional[float]
    headings: list = field(default_factory=list)
    sectors: dict = field(default_factory=dict)
    nearest_obstacle: Optional[dict] = None
    head_level: Optional[dict] = None
    drop: Optional[dict] = None
    pitch_deg: Optional[float] = None
    pitch_source: str = "config"        # config | sensor | image | default
    raised_surface: bool = False        # a table/bed top (or a drop edge) was found above the floor
    # Arrays for overlays / object fusion — not serialized
    obstacle_mask: Optional[np.ndarray] = None
    floor_mask: Optional[np.ndarray] = None
    drop_mask: Optional[np.ndarray] = None
    point_forward: Optional[np.ndarray] = None
    shape: tuple = (0, 0)

    def to_dict(self) -> dict:
        d = asdict(self)
        for k in ("obstacle_mask", "floor_mask", "drop_mask", "point_forward", "shape"):
            d.pop(k, None)
        return d


def _r(x, nd=2):
    if x is None or not np.isfinite(x):
        return None
    return round(float(x), nd)


def heading_to_action(heading_deg: float) -> str:
    if abs(heading_deg) <= 5:
        return "forward"
    if heading_deg < 0:
        return "slight_left" if heading_deg >= -20 else "left"
    return "slight_right" if heading_deg <= 20 else "right"


def normalize_disparity(disp: np.ndarray, return_range: bool = False):
    disp = np.asarray(disp, dtype=np.float32)
    lo, hi = np.percentile(disp, [2, 98])
    d = np.clip((disp - lo) / (hi - lo + 1e-6), -0.2, 1.2)
    return (d, float(lo), float(hi)) if return_range else d


def estimate_pitch(d: np.ndarray, lo: float, hi: float, hfov_deg: float, cfg: "PlannerConfig") -> Optional[float]:
    """
    Camera tilt from the image alone. Depth Anything's relative disparity is
    close to 0 at infinity, so the floor plane reaches disparity 0 at the
    horizon row; the horizon's offset from the image center gives the tilt.
    Checked on real hallway photos (horizon lands on the vanishing point).
    """
    h, w = d.shape
    coef, frac = fit_floor_plane(d, int(0.5 * h), cfg)
    if coef is None or frac < cfg.min_floor_inliers:
        return None
    a, b, c = coef
    zero = -lo / (hi - lo + 1e-6)              # raw disparity 0 in normalized units
    v_h = (zero - b * (w - 1) / 2.0 - c) / a
    f = (w / 2.0) / math.tan(math.radians(hfov_deg) / 2.0)
    pitch = math.degrees(math.atan(((h - 1) / 2.0 - v_h) / f))
    return pitch if -5.0 <= pitch <= 60.0 else None


def fit_floor_plane(d: np.ndarray, v_start: int, cfg: PlannerConfig, rng=None):
    """RANSAC fit of d = a*v + b*u + c on rows ≥ v_start. Returns (coeffs, inlier_frac) or (None, 0)."""
    h, w = d.shape
    if v_start >= h - 4:
        return None, 0.0
    vv, uu = np.mgrid[v_start:h:2, 0:w:2]
    v = vv.ravel().astype(np.float64)
    u = uu.ravel().astype(np.float64)
    z = d[v_start:h:2, 0:w:2].ravel().astype(np.float64)
    n = z.size
    if n < 30:
        return None, 0.0

    rng = rng or np.random.default_rng(0)
    A = np.stack([v, u, np.ones_like(v)], axis=1)
    # Pixels near the bottom are the floor right in front of the user — weight them up
    weight = 0.5 + (v - v_start) / max(h - v_start, 1)
    min_slope = 0.15 / h   # floor disparity must grow toward the bottom of the image

    best, best_score = None, -1.0
    for _ in range(cfg.ransac_iters):
        idx = rng.choice(n, 3, replace=False)
        try:
            coef = np.linalg.solve(A[idx], z[idx])
        except np.linalg.LinAlgError:
            continue
        a, b, _ = coef
        if a < min_slope or abs(b) > 0.5 * a:
            continue
        inl = np.abs(A @ coef - z) < cfg.ransac_tau
        score = float(weight[inl].sum())
        if score > best_score:
            best, best_score = coef, score

    if best is None:
        return None, 0.0
    inl = np.abs(A @ best - z) < cfg.ransac_tau * 1.5
    if inl.sum() < 10:
        return None, 0.0
    coef, *_ = np.linalg.lstsq(A[inl], z[inl], rcond=None)
    a, b, _ = coef
    if a < min_slope or abs(b) > 0.5 * a:
        return None, 0.0
    inl = np.abs(A @ coef - z) < cfg.ransac_tau * 1.5
    # Narrow corridors: walls fill most of the lower image, but the floor right in
    # front of the user (bottom rows, central columns) is what matters
    front = (v >= 0.7 * h) & (np.abs(u - (w - 1) / 2.0) < 0.25 * w)
    frac_front = float(inl[front].mean()) if front.any() else 0.0
    return coef, max(float(inl.mean()), 0.6 * frac_front)


def lowest_horizontal_plane(d: np.ndarray, coef, v_h: float, cfg: "PlannerConfig"):
    """
    The RANSAC plane may be a table / bed / desk top instead of the floor — all
    horizontal surfaces share the horizon, so in disparity each one is
    d = t + k * (v - v_h + r * (u - cx)) with its own k ∝ 1 / (height below the
    camera). The floor is the lowest surface: the significant one with the
    smallest k. Picking it is also the safe choice at a drop-off (the near,
    higher floor then shows up as a close obstacle → stop).
    Returns (coef, switched).
    """
    h, w = d.shape
    a, b, c = coef
    cx = (w - 1) / 2.0
    r = b / a
    t = a * v_h + b * cx + c                     # disparity of every horizontal plane at the horizon
    vv, uu = np.mgrid[0:h, 0:w].astype(np.float64)
    denom = vv - v_h + r * (uu - cx)
    sel = (denom > 0.15 * h) & (vv >= 0.35 * h)
    if sel.sum() < 50:
        return coef, False
    k = (d[sel] - t) / denom[sel]
    k_floor = a
    min_support = max(0.05 * sel.sum(), 6 * cfg.min_pixels)
    switched = False
    for _ in range(3):
        cand = k[(k > 0.2 * k_floor) & (k < 0.85 * k_floor)]
        if cand.size < min_support:
            break
        logk = np.log(cand)
        hist, edges = np.histogram(logk, bins=48)
        i = int(np.argmax(hist))
        center = 0.5 * (edges[i] + edges[i + 1])
        members = cand[np.abs(logk - center) < 0.05]
        if members.size < min_support:
            break
        k_floor = float(np.median(members))
        switched = True
    if not switched:
        return coef, False
    return np.array([k_floor, k_floor * r, t - k_floor * v_h - k_floor * r * cx]), True


class PathPlanner:
    def __init__(self, geometry: CameraGeometry | None = None, config: PlannerConfig | None = None):
        self.geo = geometry or CameraGeometry()
        self.cfg = config or PlannerConfig()

    def analyze(self, disparity: np.ndarray) -> PathAnalysis:
        cfg, geo = self.cfg, self.geo
        d, lo, hi = normalize_disparity(disparity, return_range=True)
        h, w = d.shape
        pitch_source = "config"
        if geo.pitch_deg is None:
            est = estimate_pitch(d, lo, hi, geo.hfov_deg, cfg)
            pitch_source = "image" if est is not None else "default"
            geo = CameraGeometry(geo.height_m, est if est is not None else cfg.fallback_pitch_deg, geo.hfov_deg)
        pitch = round(float(geo.pitch_deg), 1)
        f = geo.focal_px(w)
        v_h = geo.horizon_row(h, f)

        # Fit the floor below the horizon, never above mid-image
        v_start = int(max(0.5 * h, v_h + 0.05 * h))
        coef, inlier_frac = fit_floor_plane(d, v_start, cfg)
        if coef is None or inlier_frac < cfg.min_floor_inliers:
            return PathAnalysis(
                floor_valid=False, floor_confidence=round(inlier_frac, 2),
                action="caution", target_heading_deg=0.0, clear_distance_m=None,
                pitch_deg=pitch, pitch_source=pitch_source, shape=(h, w),
            )
        first_plane = coef
        coef, switched = lowest_horizontal_plane(d, coef, v_h, cfg)
        a, b, c = coef

        vv, uu = np.mgrid[0:h, 0:w].astype(np.float64)
        floor_pred = a * vv + b * uu + c
        resid = d - floor_pred
        # Same disparity as the floor at row eq_row → same camera depth
        eq_row = (d - b * uu - c) / a
        z_eq = geo.floor_depth(eq_row, h, f)
        z_floor = geo.floor_depth(vv, h, f)
        fwd_all, lat_all, height = geo.point(np.where(np.isfinite(z_eq), z_eq, 0.0), vv, uu, w, h, f)
        fwd_all = np.where(np.isfinite(z_eq), fwd_all, np.inf)
        dist_floor = geo.ground_distance(vv, h, f)

        floor_mask = np.abs(resid) < cfg.ransac_tau * 1.5
        closer = z_eq < cfg.closer_ratio * z_floor
        if switched:
            # The surface first taken for the floor is a raised one (table, bed, or the
            # floor the user stands on before a drop) — all of it is in the way
            a0, b0, c0 = first_plane
            closer |= np.abs(d - (a0 * vv + b0 * uu + c0)) < cfg.ransac_tau * 1.5
        obstacle_mask = (
            (resid > cfg.noise_margin)
            & closer
            & (fwd_all <= cfg.lookahead_m)
            & (height >= cfg.min_obstacle_height_m)
            & (height <= cfg.max_obstacle_height_m)
        )

        fwd = fwd_all[obstacle_mask]
        lat = lat_all[obstacle_mask]
        hgt = height[obstacle_mask]
        high = hgt >= cfg.head_level_height_m

        drop_mask = np.zeros_like(obstacle_mask)
        if cfg.detect_drops:
            drop_mask = (
                (resid < -cfg.noise_margin)
                & (z_eq > cfg.drop_ratio * z_floor)
                & (dist_floor < cfg.drop_range_m)
                & (vv > v_h)
            )
        drop_fwd = dist_floor[drop_mask]
        lateral_k = (uu - (w - 1) / 2.0) / f
        drop_lat = drop_fwd * lateral_k[drop_mask]
        near_floor = (vv > v_h) & (dist_floor < cfg.drop_range_m)
        floor_fwd = dist_floor[near_floor]
        floor_lat = floor_fwd * lateral_k[near_floor]

        half_fov = geo.hfov_deg / 2.0
        headings = []
        for psi in cfg.headings_deg:
            if abs(psi) > half_fov - 5:
                continue
            free_all = self._corridor_free(fwd, lat, psi)
            free_low = self._corridor_free(fwd[~high], lat[~high], psi)
            free_high = self._corridor_free(fwd[high], lat[high], psi)
            entry = {
                "heading_deg": psi,
                "free_m": _r(free_all),
                "free_low_m": _r(free_low),
                "free_high_m": _r(free_high),
            }
            if cfg.detect_drops and drop_fwd.size:
                in_drop = self._in_corridor(drop_fwd, drop_lat, psi)
                in_floor = self._in_corridor(floor_fwd, floor_lat, psi)
                n_drop = int(in_drop.sum())
                if n_drop >= max(cfg.min_pixels, 0.15 * max(int(in_floor.sum()), 1)):
                    entry["drop_m"] = _r(np.sort(self._rot(drop_fwd, drop_lat, psi)[0][in_drop])[cfg.min_pixels - 1])
            headings.append(entry)

        by_psi = {e["heading_deg"]: e for e in headings}
        straight = by_psi.get(0.0) or min(headings, key=lambda e: abs(e["heading_deg"]))
        clear = straight["free_m"]

        # Choose: straight if enough room, else the best-scoring turn
        if clear >= cfg.safe_distance_m:
            target = straight["heading_deg"]
            action = "forward"
        else:
            best = max(headings, key=lambda e: e["free_m"] - cfg.turn_penalty_m_per_deg * abs(e["heading_deg"]))
            if best["free_m"] >= cfg.safe_distance_m:
                target = best["heading_deg"]
                action = heading_to_action(target)
            elif clear > cfg.stop_distance_m:
                target = best["heading_deg"] if best["free_m"] > clear + 0.5 else straight["heading_deg"]
                action = "slow"
            else:
                target = best["heading_deg"]
                action = "stop"

        nearest = None
        if fwd.size >= cfg.min_pixels:
            k = np.argpartition(fwd, cfg.min_pixels - 1)[cfg.min_pixels - 1]
            nearest = {
                "distance_m": _r(fwd[k]),
                "bearing_deg": _r(math.degrees(math.atan2(lat[k], fwd[k])), 1),
                "height_m": _r(hgt[k]),
            }

        head_level = None
        hs = straight
        if hs["free_high_m"] < cfg.safe_distance_m and hs["free_high_m"] < hs["free_low_m"] - 0.4:
            head_level = {"distance_m": hs["free_high_m"], "heading_deg": hs["heading_deg"]}

        drop = None
        if "drop_m" in straight:
            drop = {"distance_m": straight["drop_m"], "experimental": True}

        return PathAnalysis(
            floor_valid=True,
            floor_confidence=round(inlier_frac, 2),
            action=action,
            target_heading_deg=float(target),
            clear_distance_m=_r(clear),
            headings=headings,
            sectors=self._sectors(fwd, lat),
            nearest_obstacle=nearest,
            head_level=head_level,
            drop=drop,
            pitch_deg=pitch,
            pitch_source=pitch_source,
            raised_surface=bool(switched),
            obstacle_mask=obstacle_mask,
            floor_mask=floor_mask,
            drop_mask=drop_mask,
            point_forward=np.where(obstacle_mask, fwd_all, np.inf),
            shape=(h, w),
        )

    # ── helpers ──────────────────────────────────────────────────────────

    @staticmethod
    def _rot(fwd, lat, psi_deg):
        p = math.radians(psi_deg)
        return fwd * math.cos(p) + lat * math.sin(p), -fwd * math.sin(p) + lat * math.cos(p)

    def _in_corridor(self, fwd, lat, psi_deg):
        f2, l2 = self._rot(fwd, lat, psi_deg)
        return (np.abs(l2) < self.cfg.body_width_m / 2.0) & (f2 > 0)

    def _corridor_free(self, fwd, lat, psi_deg) -> float:
        if fwd.size == 0:
            return self.cfg.lookahead_m
        f2, _ = self._rot(fwd, lat, psi_deg)
        blk = f2[self._in_corridor(fwd, lat, psi_deg)]
        k = self.cfg.min_pixels
        if blk.size < k:
            return self.cfg.lookahead_m
        return float(min(np.partition(blk, k - 1)[k - 1], self.cfg.lookahead_m))

    def _sectors(self, fwd, lat) -> dict:
        """Nearest obstacle on the left / center / right (scene summary)."""
        out = {}
        if fwd.size == 0:
            return {"left": None, "center": None, "right": None}
        bearing = np.degrees(np.arctan2(lat, fwd))
        for name, sel in (("left", bearing < -12), ("center", np.abs(bearing) <= 12), ("right", bearing > 12)):
            vals = fwd[sel]
            k = self.cfg.min_pixels
            out[name] = _r(np.partition(vals, k - 1)[k - 1]) if vals.size >= k else None
        return out

    def box_distance(self, analysis: PathAnalysis, box_work) -> Optional[float]:
        """Distance of an object box (in planner/work coordinates) from obstacle depth or its foot point."""
        if not analysis.floor_valid or analysis.point_forward is None:
            return None
        h, w = analysis.shape
        x1, y1, x2, y2 = [int(round(t)) for t in box_work]
        x1, x2 = max(0, x1), min(w, max(x2, x1 + 1))
        y1, y2 = max(0, y1), min(h, max(y2, y1 + 1))
        vals = analysis.point_forward[y1:y2, x1:x2]
        vals = vals[np.isfinite(vals)]
        if vals.size >= 4:
            return _r(np.percentile(vals, 20))
        # Fall back to where the object touches the ground
        if y2 < h - 1:
            geo = CameraGeometry(self.geo.height_m, analysis.pitch_deg, self.geo.hfov_deg)
            dist = float(geo.ground_distance(y2, h, geo.focal_px(w)))
            # Near the horizon the foot-point distance explodes — beyond ~30 m it is meaningless
            return _r(dist) if np.isfinite(dist) and dist <= 30.0 else None
        return None

    def bearing_deg(self, x_center_work: float, width: int) -> float:
        f = self.geo.focal_px(width)
        return math.degrees(math.atan(((x_center_work - (width - 1) / 2.0)) / f))
