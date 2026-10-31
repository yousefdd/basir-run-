"""Finds real curved walks in SANPO-Real (test split) from the published chest-camera trajectories:
6 s windows (90 frames at 15 fps) where the walking direction (from positions, not camera yaw)
turns 20-100 deg steadily while moving >= 0.7 m/s. Vertical axis = axis with least positional
spread. Output: datasets/sanpo/curve_windows.csv (session, start, turn_deg, radius_m, speed)."""
import csv, io, os, urllib.request, numpy as np
from concurrent.futures import ThreadPoolExecutor
R = "/home/user/basir-run-/BasirRun_Research"; RAW = "https://storage.googleapis.com/gresearch/sanpo_dataset/v0/sanpo-real/"
ids = [l.strip() for l in open(R + "/datasets/sanpo/test_ids.txt") if l.strip()]
def get(s):
    p = f"{R}/datasets/sanpo/poses/{s}.csv"
    if not os.path.exists(p):
        try: open(p, "wb").write(urllib.request.urlopen(RAW + f"{s}/camera_chest/camera_poses.csv", timeout=60).read())
        except Exception: return s, None
    return s, p
found = []
for s, p in ThreadPoolExecutor(8).map(get, ids):
    if not p: continue
    rows = [r for r in csv.DictReader(open(p))]
    P = np.array([[float(r["pos_x"]), float(r["pos_y"]), float(r["pos_z"])] for r in rows])
    Q = np.array([[float(r["q_x"]), float(r["q_y"]), float(r["q_z"]), float(r["q_w"])] for r in rows])
    ok = np.array([r["tracking_state"].endswith("READY") and not r["tracking_state"].endswith("NOT_READY") for r in rows])
    def rot(q):
        x, y, z, w = q
        return np.array([[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
                         [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
                         [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])
    for st in range(0, len(P) - 90, 15):
        if not ok[st:st + 90].all(): continue
        w = P[st:st + 90]
        d = w[15::15] - w[:-15:15]  # 1 s travel steps (per-frame steps are inflated by tracking jitter)
        speed = np.sum(np.linalg.norm(d, axis=1)) / (len(d))
        if speed < 0.6: continue
        if np.linalg.norm(d, axis=1).min() < 0.4: continue
        u = d / np.linalg.norm(d, axis=1, keepdims=True)
        step_ang = np.degrees(np.arccos(np.clip(np.sum(u[1:] * u[:-1], 1), -1, 1)))
        turn = np.degrees(np.arccos(np.clip(u[0] @ u[-1], -1, 1)))
        if not (20 <= turn <= 100) or step_ang.sum() > 1.5 * turn: continue
        # side of the turn in the camera frame at the window start (x = image right)
        R0 = rot(Q[st]); a, b = R0.T @ u[0], R0.T @ u[-1]
        side = "right" if (b[0] - a[0]) > 0 else "left"
        path_len = speed * len(d)
        found.append(dict(session=s, start=st, turn_deg=round(turn, 1) * (1 if side == "right" else -1), radius_m=round(path_len / np.radians(turn), 1), speed=round(speed, 2)))
# one window per session (the strongest), non-overlapping
best = {}
for f in found:
    if f["session"] not in best or abs(f["turn_deg"]) > abs(best[f["session"]]["turn_deg"]): best[f["session"]] = f
out = sorted(best.values(), key=lambda f: -abs(f["turn_deg"]))
with open(R + "/datasets/sanpo/curve_windows.csv", "w", newline="") as fh:
    wr = csv.DictWriter(fh, ["session", "start", "turn_deg", "radius_m", "speed"]); wr.writeheader(); wr.writerows(out)
print(len(ids), "test sessions,", len(out), "with a steady curved walk"); [print(f) for f in out[:12]]
