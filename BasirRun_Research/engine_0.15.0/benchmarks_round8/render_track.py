"""
Synthetic chest-camera clips of sports grounds with exactly known geometry (ground truth for corner tests).

Ray-casts a flat ground plane for every pixel (2x supersampled), textured from world-space rasters at 1 cm: a tartan or
asphalt running band around a football pitch, painted lines, grass, tree shadows, optional faded paint and a vertical
barrier billboard. The camera rides a runner who follows the path with a human turn radius, with stride bob, sway and
optional strong shake. Writes upright portrait JPEG frames plus truth.json (per-frame phase, corner side, distance to the
next corner, heading, curvature) for the corner metrics.

These clips are SYNTHETIC: they test geometry and timing (corners, bends, consecutive turns, faded lines, shadows,
shake), not the models' realism. Real behaviour is measured on SANPO-Real and the owner's video.

Usage: render_track.py <scene> <out_dir>
"""
import json, math, os, sys
import numpy as np, cv2

RES = 0.01  # m per raster pixel
W, H = 360, 640  # portrait, as the app's upright frames
SS = 2


def fillet_polyline(pts, radius, closed=False, step=0.05):
    """Polyline with every inner vertex replaced by a circular arc of [radius] (0 = sharp). Returns dense points."""
    pts = [np.array(p, float) for p in pts]
    if closed: pts = pts + [pts[0], pts[1]]
    out = [pts[0]]
    for i in range(1, len(pts) - 1):
        a, b, c = pts[i - 1], pts[i], pts[i + 1]
        u = (a - b) / np.linalg.norm(a - b); v = (c - b) / np.linalg.norm(c - b)
        ang = math.acos(np.clip(np.dot(u, v), -1, 1))
        if radius <= 0 or ang > math.pi - 1e-3:
            out.append(b); continue
        t = radius / math.tan(ang / 2)
        p1 = b + u * t; p2 = b + v * t
        bis = (u + v) / np.linalg.norm(u + v); centre = b + bis * radius / math.sin(ang / 2)
        a1 = math.atan2(*(p1 - centre)[::-1]); a2 = math.atan2(*(p2 - centre)[::-1])
        d = (a2 - a1 + math.pi) % (2 * math.pi) - math.pi
        n = max(2, int(abs(d) * radius / step))
        out += [centre + radius * np.array([math.cos(a1 + d * k / n), math.sin(a1 + d * k / n)]) for k in range(n + 1)]
    if not closed: out.append(pts[-1])
    return resample(np.array(out), step)


def resample(p, step):
    seg = np.linalg.norm(np.diff(p, axis=0), axis=1); s = np.concatenate([[0], np.cumsum(seg)])
    t = np.arange(0, s[-1], step)
    return np.stack([np.interp(t, s, p[:, 0]), np.interp(t, s, p[:, 1])], 1)


def offset(p, d):
    """Offset a dense polyline sideways by d (+ = right of travel direction)."""
    t = np.gradient(p, axis=0); t /= np.linalg.norm(t, axis=1, keepdims=True)
    n = np.stack([t[:, 1], -t[:, 0]], 1)  # right normal for x east, y north
    return p + n * d


def heading_curv(p):
    t = np.gradient(p, axis=0); hd = np.unwrap(np.arctan2(t[:, 0], t[:, 1]))  # compass: from +y toward +x
    ds = np.linalg.norm(t, axis=1)
    return hd, np.gradient(hd) / np.maximum(ds, 1e-9)


SCENES = {
    # name: dict(path vertices of the GUIDE LINE, corner radius, runner offset from the line (+ right), speed, extras)
    "rect_right_sharp": dict(line=[(0, -12), (0, 30), (40, 30), (40, -12)], r=0.0, off=0.0, v=2.5, frames=330),
    # a full lap of a small pitch: four right-hand 90-degree corners in one run (task 2, section 8)
    "rect_loop_4_corners": dict(line=[(9, 0), (0, 0), (0, 14), (25, 14), (25, 0), (12, 0)], r=0.0, off=0.0, v=2.5, frames=460),
    "rect_left_sharp": dict(line=[(40, -12), (40, 30), (0, 30), (0, -12)], r=0.0, off=0.0, v=2.5, frames=330),
    "rect_right_rounded": dict(line=[(0, -12), (0, 30), (40, 30), (40, -12)], r=3.0, off=0.0, v=2.5, frames=330),
    "rect_parallel_walk": dict(line=[(0, -6), (0, 14), (25, 14), (25, -6)], r=0.0, off=-0.7, v=1.4, frames=330),
    "athletics_bend": dict(bend=36.5, off=0.0, v=3.0, frames=330),
    "zigzag_turns": dict(line=[(0, -8), (0, 10), (8, 10), (8, 18), (16, 18), (16, 30)], r=0.5, off=0.0, v=2.2, frames=300),
    "faded_corner": dict(line=[(0, -12), (0, 30), (40, 30), (40, -12)], r=0.0, off=0.0, v=2.5, frames=330, fade=3.5),
    "shadows_corner": dict(line=[(0, -12), (0, 30), (40, 30), (40, -12)], r=1.0, off=0.0, v=2.5, frames=330, shadows=True),
    "lowcontrast_corner": dict(line=[(0, -12), (0, 30), (-40, 30), (-40, -12)], r=0.0, off=0.0, v=2.0, frames=330, low=True),
    "shake_corner": dict(line=[(0, -12), (0, 30), (40, 30), (40, -12)], r=0.0, off=0.0, v=2.5, frames=330, shake=2.5),
    "pitch_change_straight": dict(line=[(0, -5), (0, 90)], r=0.0, off=0.0, v=2.5, frames=300, pitchRamp=(22, 40)),
    "barrier_before_corner": dict(line=[(0, -12), (0, 30), (40, 30), (40, -12)], r=0.0, off=0.0, v=2.0, frames=300,
                                  barrier=(0.0, 18.0)),
}


def build_line(sc):
    if "bend" in sc:
        R = sc["bend"]; pts = [(0, -10), (0, 20)]
        p = resample(np.array(pts, float), 0.05)
        ang = np.linspace(0, math.pi, int(math.pi * R / 0.05))
        arc = np.stack([R - R * np.cos(ang), 20 + R * np.sin(ang)], 1)  # bends right (clockwise) around (R, 20)
        tail = resample(np.array([(2 * R, 20), (2 * R, -10)], float), 0.05)
        return np.concatenate([p, arc, tail])
    return fillet_polyline(sc["line"], sc["r"])


def main(name, out):
    sc = SCENES[name]; rng = np.random.default_rng(sum(name.encode()) * 7919)
    line = build_line(sc)
    # Runner path: the guide line offset sideways, then filleted with a human turn radius at sharp vertices
    if "bend" in sc:
        run = offset(line, sc["off"])
    else:
        verts = [np.array(v, float) for v in sc["line"]]
        if sc["off"] != 0:
            run = offset(line, sc["off"])
            run = resample(cv2.GaussianBlur(run.reshape(-1, 1, 2).astype(np.float32), (1, 61), 0).reshape(-1, 2).astype(float), 0.05)
        else:
            run = fillet_polyline(verts, max(sc["r"], 1.2))
    hd, kappa = heading_curv(run)
    # World rasters
    allp = np.concatenate([line, run]); x0, y0 = allp.min(0) - 8; x1, y1 = allp.max(0) + 45
    rw, rh = int((x1 - x0) / RES), int((y1 - y0) / RES)
    def px(p): return ((p[:, 0] - x0) / RES).astype(np.int32), ((p[:, 1] - y0) / RES).astype(np.int32)
    band = np.zeros((rh, rw), np.uint8); paint = np.zeros((rh, rw), np.uint8)
    xs, ys = px(line); pl = np.stack([xs, ys], 1).reshape(-1, 1, 2)
    cv2.polylines(band, [pl], False, 255, int(2.6 / RES), cv2.LINE_8)
    lw = 0.06
    if "fade" in sc and "line" in sc:
        # paint missing within `fade` m of every corner vertex
        keep = np.ones(len(line), bool)
        for v in sc["line"][1:-1]:
            keep &= np.linalg.norm(line - np.array(v), axis=1) > sc["fade"]
        seg = []
        for k in range(len(line)):
            if keep[k]: seg.append(k)
            elif seg:
                cv2.polylines(paint, [pl[seg]], False, 255, max(1, int(lw / RES)), cv2.LINE_AA); seg = []
        if seg: cv2.polylines(paint, [pl[seg]], False, 255, max(1, int(lw / RES)), cv2.LINE_AA)
    else:
        cv2.polylines(paint, [pl], False, 255, max(1, int(lw / RES)), cv2.LINE_AA)
    # second, outer lane line 1.22 m away for the athletics bend (lane lines), as on a real track
    if "bend" in sc:
        for k in (1, 2):
            o = offset(line, -1.22 * k); ox, oy = px(o)
            cv2.polylines(paint, [np.stack([ox, oy], 1).reshape(-1, 1, 2)], False, 255, int(0.05 / RES), cv2.LINE_AA)
    shadow = np.zeros((rh // 4, rw // 4), np.float32)
    if sc.get("shadows"):
        for _ in range(140):
            k = rng.integers(len(run)); c = run[k] + rng.normal(0, 3.0, 2)
            cx, cy = int((c[0] - x0) / RES / 4), int((c[1] - y0) / RES / 4)
            cv2.ellipse(shadow, (cx, cy), (int(rng.uniform(0.4, 2.0) / RES / 4), int(rng.uniform(0.15, 0.6) / RES / 4)),
                        float(rng.uniform(0, 180)), 0, 360, 1.0, -1)
        shadow = cv2.GaussianBlur(shadow, (0, 0), 3)
        shadow = np.clip(shadow, 0, 1) * 0.6
    shadow = cv2.resize(shadow, (rw, rh), interpolation=cv2.INTER_LINEAR)
    noise = cv2.GaussianBlur(rng.normal(0, 1, (1024, 1024)).astype(np.float32), (0, 0), 1.2)
    noise2 = cv2.GaussianBlur(rng.normal(0, 1, (1024, 1024)).astype(np.float32), (0, 0), 12)
    grain = cv2.GaussianBlur(rng.normal(0, 1, (1024, 1024)).astype(np.float32), (0, 0), 0.6)  # rubber granules / grass blades, ~3 mm
    if sc.get("low"):
        band_col = np.array([105, 108, 112], np.float32); grass = np.array([95, 110, 85], np.float32); paint_col = np.array([135, 138, 140], np.float32)
    else:
        band_col = np.array([60, 70, 165], np.float32); grass = np.array([60, 125, 55], np.float32); paint_col = np.array([235, 238, 240], np.float32)  # BGR
    # Camera
    f = (W * SS / 2) / math.tan(math.radians(45 / 2)); cx, cy = W * SS / 2, H * SS / 2
    uu, vv = np.meshgrid(np.arange(W * SS) + 0.5, np.arange(H * SS) + 0.5)
    a = (uu - cx) / f; b = (vv - cy) / f
    fps = 15; v = sc["v"]; step_hz = 2.8 if v >= 2.0 else 1.8; shake = sc.get("shake", 1.0)
    s_run = np.concatenate([[0], np.cumsum(np.linalg.norm(np.diff(run, axis=0), axis=1))])
    os.makedirs(out + "/rgb", exist_ok=True)
    truth = []
    jitter = np.zeros(3)
    corners = []
    if "line" in sc:
        k_prev = 0
        for vtx in sc["line"][1:-1]:
            # corners in order along the path (a lap may pass near an earlier point again)
            k = k_prev + int(np.argmin(np.linalg.norm(run[k_prev:] - np.array(vtx), axis=1))); k_prev = k
            # side from the line's turn at this vertex
            i = sc["line"].index(vtx); p0, p1, p2 = [np.array(sc["line"][j], float) for j in (i - 1, i, i + 1)]
            cross = (p1 - p0)[0] * (p2 - p1)[1] - (p1 - p0)[1] * (p2 - p1)[0]
            corners.append((s_run[k], "RIGHT" if cross < 0 else "LEFT"))
    barrier = None
    if "barrier" in sc:
        bx, by = sc["barrier"]; barrier = (np.array([bx, by], float), 1.2, 1.0)  # centre, width, height (faces south)
    for i in range(int(os.environ.get("MAXF", sc["frames"]))):
        t = i / fps; s = 1.0 + v * t
        k = min(int(np.searchsorted(s_run, s)), len(run) - 1)
        pos = run[k].copy(); psi = hd[k]
        ph = 2 * math.pi * step_hz * t
        jitter = 0.85 * jitter + 0.15 * rng.normal(0, 1, 3) * (shake - 1.0)
        yaw = psi + math.radians(2.0 * shake * math.sin(ph / 2) + jitter[0])
        pitch0 = 30.0 if "pitchRamp" not in sc else sc["pitchRamp"][0] + (sc["pitchRamp"][1] - sc["pitchRamp"][0]) * i / sc["frames"]
        pitch = math.radians(pitch0 + 1.5 * shake * math.sin(ph) + jitter[1])
        roll = math.radians(2.0 * shake * math.sin(ph / 2 + 0.7) + jitter[2])
        hgt = 1.3 + 0.03 * shake * math.sin(ph)
        sway = 0.05 * shake * math.sin(ph / 2)
        hvec = np.array([math.sin(yaw), math.cos(yaw), 0.0]); rvec = np.array([math.cos(yaw), -math.sin(yaw), 0.0]); up = np.array([0, 0, 1.0])
        pos3 = np.array([pos[0], pos[1], hgt]) + np.array([math.cos(psi), -math.sin(psi), 0]) * sway
        z1 = math.cos(pitch) * hvec - math.sin(pitch) * up; y1 = -math.cos(pitch) * up - math.sin(pitch) * hvec
        x2 = math.cos(roll) * rvec + math.sin(roll) * y1; y2 = -math.sin(roll) * rvec + math.cos(roll) * y1
        d = a[..., None] * x2 + b[..., None] * y2 + z1
        dz = d[..., 2]; ground = dz < -1e-4
        tg = np.where(ground, -pos3[2] / np.minimum(dz, -1e-4), np.inf)
        X = np.where(ground, pos3[0] + np.where(ground, tg, 0) * d[..., 0], 0.0); Y = np.where(ground, pos3[1] + np.where(ground, tg, 0) * d[..., 1], 0.0)
        mx = ((X - x0) / RES).astype(np.float32); my = ((Y - y0) / RES).astype(np.float32)
        bnd = cv2.remap(band, mx, my, cv2.INTER_LINEAR, borderValue=0).astype(np.float32) / 255
        pnt = cv2.remap(paint, mx, my, cv2.INTER_LINEAR, borderValue=0).astype(np.float32) / 255
        shd = cv2.remap(shadow, mx, my, cv2.INTER_LINEAR, borderValue=0)
        nmx = np.mod(X / 0.01, 1024).astype(np.float32); nmy = np.mod(Y / 0.01, 1024).astype(np.float32)
        nz = cv2.remap(noise, nmx, nmy, cv2.INTER_LINEAR, borderMode=cv2.BORDER_WRAP)
        nz2 = cv2.remap(noise2, np.mod(X / 0.05, 1024).astype(np.float32), np.mod(Y / 0.05, 1024).astype(np.float32), cv2.INTER_LINEAR, borderMode=cv2.BORDER_WRAP)
        gr = np.nan_to_num(cv2.remap(grain, np.mod(X / 0.003, 1024).astype(np.float32), np.mod(Y / 0.003, 1024).astype(np.float32), cv2.INTER_LINEAR, borderMode=cv2.BORDER_WRAP))
        col = grass * (1 + 0.10 * nz2[..., None] + 0.08 * nz[..., None] + 0.22 * gr[..., None])
        col = col * (1 - bnd[..., None]) + band_col * (1 + 0.06 * nz[..., None] + 0.04 * nz2[..., None] + 0.16 * gr[..., None]) * bnd[..., None]
        col = col * (1 - pnt[..., None]) + paint_col * pnt[..., None]
        col = col * (1 - shd[..., None])
        dist = np.where(ground, tg, 200)
        haze = np.clip((dist - 25) / 60, 0, 1)[..., None]
        sky = np.stack([np.full_like(b, 230), 205 + 0 * b, 170 + 0 * b], -1) - (b[..., None] + 0.3) * 40
        col = np.where(ground[..., None], col * (1 - haze) + np.array([200, 200, 195]) * haze, sky)
        # distant tree line / stands (a band just above the horizon) so the horizon is not a perfect edge
        horizon_band = (~ground) & (dz > -0.002) & (dz < 0.06 + 0.02 * np.sin(uu / 37.0))
        col = np.where(horizon_band[..., None], np.array([55, 85, 60]) * (1 + 0.15 * nz[..., None]), col)
        if barrier is not None:
            c, bw, bh = barrier
            # vertical plane y = c.y facing south, x in [c.x - bw/2, c.x + bw/2], z in [0, bh]
            dy = d[..., 1]; tb = np.where(np.abs(dy) > 1e-6, (c[1] - pos3[1]) / dy, np.inf)
            bxw = pos3[0] + tb * d[..., 0]; bz = pos3[2] + tb * d[..., 2]
            hit = (tb > 0) & (tb < tg) & (np.abs(bxw - c[0]) <= bw / 2) & (bz >= 0) & (bz <= bh)
            stripe = (np.floor((bxw - c[0]) / 0.2 + bz / 0.2) % 2)[..., None]
            bc = np.array([30, 120, 240]) * stripe + np.array([235, 235, 235]) * (1 - stripe)
            col = np.where(hit[..., None], bc * (0.85 + 0.15 * np.clip(bz / bh, 0, 1)[..., None]), col)
        img = np.clip(col, 0, 255).astype(np.uint8)
        img = cv2.resize(img, (W, H), interpolation=cv2.INTER_AREA)
        img = np.clip(img.astype(np.float32) + rng.normal(0, 3.5, img.shape), 0, 255).astype(np.uint8)
        cv2.imwrite(f"{out}/rgb/{i:06d}.jpg", img, [cv2.IMWRITE_JPEG_QUALITY, 92])
        # Truth
        nxt = [(cs - s, side) for cs, side in corners if cs - s > -6.0]
        phase = "STRAIGHT"; side = None; dist_next = None
        if nxt:
            dc, side = nxt[0]; dist_next = dc
            rt = max(sc.get("r", 0), 1.2); arc = rt * math.pi / 2
            if dc > arc / 2 + 8.0: phase = "STRAIGHT"; side = None
            elif dc > arc / 2: phase = "APPROACH"
            elif dc > -arc / 2: phase = "TURNING"
            elif dc > -arc / 2 - 4.0: phase = "EXIT"
            else: phase = "STRAIGHT"; side = None
        if "bend" in sc and abs(kappa[k]) > 0.01: phase = "BEND"; side = "RIGHT" if kappa[k] > 0 else "LEFT"
        truth.append({"i": i, "s": s, "x": float(pos[0]), "y": float(pos[1]), "headingDeg": math.degrees(psi), "curvature": float(kappa[k]),
                      "phase": phase, "side": side, "distToCorner": dist_next, "barrierDist": (float(barrier[0][1] - pos[1]) if barrier is not None else None)})
    json.dump({"scene": name, "synthetic": True, "fps": fps, "hfovDeg": 45.0, "heightM": 1.3, "pitchDeg": 30.0 if "pitchRamp" not in sc else None,
               "speedMps": v, "corners": corners, "frames": truth}, open(out + "/truth.json", "w"))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
