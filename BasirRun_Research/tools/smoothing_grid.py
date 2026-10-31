"""Fair filter comparison: each filter's main parameters tuned on a small grid (same formulas as
engine/StableGuidance.kt), scored on the raw controller inputs of the 5 clips by RMS to the
zero-phase reference, with lag reported. Clips split: tune on the 4 SANPO clips, report on the
owner's night video too (held out), so the choice is not fitted to one clip."""
import csv, math, itertools, numpy as np
R = "/home/user/basir-run-/BasirRun_Research"
TUNE = ["sanpo_gravel_5n1pzdkv", "sanpo_terrain_park_-F9N8JhM", "sanpo_jogging_0X2rC_H2", "sanpo_running_1uta1HvN"]; HOLD = ["20261030_225434"]
dt = 1 / 15
def ema(x, a):
    y = [x[0]]
    for v in x[1:]: y.append(y[-1] + a * (v - y[-1]))
    return np.array(y)
def one_euro(x, mc, beta, dc=1.0):
    al = lambda c: 1 / (1 + 1 / (2 * math.pi * c) / dt)
    y = [x[0]]; dx0 = 0.0
    for v in x[1:]:
        dx = (v - y[-1]) / dt; dx0 += al(dc) * (dx - dx0); y.append(y[-1] + al(mc + beta * abs(dx0)) * (v - y[-1]))
    return np.array(y)
def kalman(x, q, r):
    xs, v, p00, p01, p11 = x[0], 0.0, r, 0.0, 1.0; y = [x[0]]
    for z in x[1:]:
        xs += v * dt; p00 += dt * (2 * p01 + dt * p11) + q * dt ** 3 / 3; p01 += dt * p11 + q * dt * dt / 2; p11 += q * dt
        s = p00 + r; k0, k1 = p00 / s, p01 / s; e = z - xs; xs += k0 * e; v += k1 * e
        p11 -= k1 * p01; p01 -= k0 * p01; p00 -= k0 * p00; y.append(xs)
    return np.array(y)
def median(x, n): return np.array([np.median(x[max(0, i - n + 1):i + 1]) for i in range(len(x))])
def gauss(x, s=3):
    k = np.exp(-0.5 * (np.arange(-3 * s, 3 * s + 1) / s) ** 2); k /= k.sum(); return np.convolve(np.pad(x, 3 * s, mode="edge"), k, mode="valid")
def runs(clip, key):
    rows = list(csv.DictReader(open(f"{R}/experiment_results/masks_cache/{clip}/guidance.csv"))); out, cur = [], []
    for r in rows:
        if r[key]: cur.append(float(r[key]))
        elif cur: out.append(np.array(cur)); cur = []
    if cur: out.append(np.array(cur))
    return [o for o in out if len(o) >= 15]
def score(fn, clips, key):
    err, lag, n = 0.0, 0.0, 0
    for c in clips:
        for x in runs(c, key):
            f, ref = fn(x), gauss(x)
            err += np.sum((f - ref) ** 2) / np.var(x if np.var(x) > 0 else [0, 1]); n += len(x)  # scale-free per run
            lag += len(x) * int(np.argmin([np.mean((f[k:] - ref[:len(ref) - k]) ** 2) for k in range(11)]))
    return math.sqrt(err / n), lag / n
GRID = {"NONE": [((), lambda x: x)],
        "EMA": [((a,), (lambda a: lambda x: ema(x, a))(a)) for a in (0.15, 0.25, 0.35, 0.5, 0.7)],
        "ONE_EURO": [((m, b), (lambda m, b: lambda x: one_euro(x, m, b))(m, b)) for m in (0.3, 0.6, 1.0, 2.0) for b in (0.0, 0.05, 0.3, 1.0)],
        "KALMAN": [((q, r), (lambda q, r: lambda x: kalman(x, q, r))(q, r)) for q in (0.01, 0.1, 1.0, 10.0) for r in (0.001, 0.01, 0.1)],
        "MEDIAN": [((n,), (lambda n: lambda x: median(x, n))(n)) for n in (3, 5, 7)]}
lines = ["Inputs normalised by each run's variance, so offset/heading/curvature weigh alike. Lower 'error' is better.",
         "signal | filter | best params (tuned on 4 SANPO clips) | error on SANPO | lag frames | error on owner's video (held out) | lag frames"]
for key in ("offset_raw", "heading_raw", "curv_raw"):
    for name, grid in GRID.items():
        best = min(grid, key=lambda g: score(g[1], TUNE, key)[0])
        e1, l1 = score(best[1], TUNE, key); e2, l2 = score(best[1], HOLD, key)
        lines.append(f"{key[:-4]} | {name} | {best[0]} | {e1:.3f} | {l1:.1f} | {e2:.3f} | {l2:.1f}")
open(R + "/benchmarks/results/smoothing_grid.txt", "w").write("\n".join(lines) + "\n"); print("\n".join(lines))
