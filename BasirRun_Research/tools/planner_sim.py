"""Path-following algorithms under one human-runner model with discrete vibration cues (SIMULATION).

Why a simulation: no recording exists of a blind runner reacting to cues, so the algorithms are
compared on a model of what matters for Basir-Run: the runner only gets discrete cues
(correct L/R small/medium/high, follow bend L/R, obstacle, stop), reacts after ~0.5 s, turns at a
limited rate, drifts; the camera sees the path only ahead (<= 6 m) and inside its field of view
(58 deg), with measurement noise. Every algorithm gets the same perception, the same cue
quantiser (deadband 4 deg, one cue per 0.8 s unless HIGH) and the same runner.

Algorithms: pure pursuit, Stanley, sampled MPC over cue sequences, DWA, potential fields,
A* on a local grid + pursuit, curvature-feedforward with offset feedback and hysteresis (the
StableGuidance design), risk-aware A* (unseen cells cost more).
Scenarios: straight with offset, athletics bend (R 36.5 m, lane 1.22 m), park S-curve (R 10 m),
tight bend (R 6 m), obstacle on a 3 m path, narrow 1.2 m path; walking 1.4 m/s and running 2.8 m/s.
Metrics: RMS cross-track, share of time with the body past the edge, collisions, cues per minute,
opposite-side cues within 1 s, did not finish (left the path by > 1.5 m)."""
import heapq, math, json, sys
import numpy as np

DT = 1 / 15; FOV = math.radians(58.4) / 2; LOOK = 6.0; BODY = 0.3; REACT = 0.5

# ---------- scenarios: centreline polyline (world), half width, obstacles ----------
def arc_path(segs, step=0.1):
    pts = [(0.0, 0.0)]; h = 0.0
    for length, radius in segs:
        n = int(length / step)
        for _ in range(n):
            if radius: h += step / radius
            x, y = pts[-1]; pts.append((x + step * math.cos(h), y + step * math.sin(h)))
    return np.array(pts)
SCEN = {
    "straight_offset": dict(path=arc_path([(80, None)]), half=1.0, obst=[], start_off=0.5),
    "athletics_bend_R36.5": dict(path=arc_path([(15, None), (57, -36.5), (15, None)]), half=0.61, obst=[], start_off=0.0),
    "park_S_curve_R10": dict(path=arc_path([(10, None), (14, -10), (14, 10), (10, None)]), half=1.0, obst=[], start_off=0.0),
    "tight_bend_R6": dict(path=arc_path([(10, None), (9, 6), (12, None)]), half=1.0, obst=[], start_off=0.0),
    "obstacle_on_3m_path": dict(path=arc_path([(40, None)]), half=1.5, obst=[(12.0, 0.0, 0.35)], start_off=0.0),
    "narrow_1.2m": dict(path=arc_path([(10, None), (20, 20), (10, None)]), half=0.6, obst=[], start_off=0.0),
}

def nearest(path, x, y):
    d = np.hypot(path[:, 0] - x, path[:, 1] - y); i = int(np.argmin(d))
    j = min(i + 1, len(path) - 1); i0 = max(j - 1, 0)
    tx, ty = path[j] - path[i0]; n = math.hypot(tx, ty) or 1
    side = np.sign((x - path[i, 0]) * (-ty / n) + (y - path[i, 1]) * (tx / n))  # + = left of path
    return i, d[i] * side

# ---------- perception (runner frame: f forward, l right) ----------
def perceive(sc, st, rng):
    x, y, h = st; c, s = math.cos(h), math.sin(h)
    rel = sc["path"] - [x, y]
    f = rel[:, 0] * c + rel[:, 1] * s; l = rel[:, 0] * s - rel[:, 1] * c  # l positive = right
    m = (f > 0.8) & (f < LOOK) & (np.abs(np.arctan2(l, f)) < FOV + np.arctan2(sc["half"], np.maximum(f, 0.1)))
    if m.sum() < 8: return None
    ff, ll = f[m], l[m] + rng.normal(0, 0.06, m.sum())
    co = np.polyfit(ff, ll, 2, w=np.ones_like(ff))
    a, b, k = co[2] + rng.normal(0, 0.05), co[1] + rng.normal(0, 0.03), co[0] + rng.normal(0, 0.004)
    obs = []
    for ox, oy, r in sc["obst"]:
        of = (ox - x) * c + (oy - y) * s; ol = (ox - x) * s - (oy - y) * c
        if 0 < of < LOOK and abs(math.atan2(ol, of)) < FOV: obs.append((of, ol, r))
    return dict(off=a, slope=b, kappa=2 * k, far=float(ff.max()), half=sc["half"], obs=obs)
def lat(p, f): return p["off"] + p["slope"] * f + p["kappa"] / 2 * f * f

# ---------- algorithms: return desired heading change (deg, + right) or a full cue ----------
def pure_pursuit(p, v, mem):
    L = min(max(0.8 * v, 1.5), 3.0, p["far"]); return math.degrees(math.atan2(lat(p, L), L))
def stanley(p, v, mem):
    return math.degrees(math.atan(p["slope"]) + math.atan2(1.0 * p["off"], v + 0.5))
def potential(p, v, mem):
    L = min(2.5, p["far"]); gx, gy = L, lat(p, L); fx, fy = gx / math.hypot(gx, gy), gy / math.hypot(gx, gy)
    fy += 0.25 * p["off"]  # pull toward the path centre (edges act through it)
    for of, ol, r in p["obs"]:
        d = max(0.2, math.hypot(of, ol) - r); fy += -np.sign(ol or 1e-3) * 1.5 / d ** 2; fx -= 0.5 / d ** 2
    return math.degrees(math.atan2(fy, max(fx, 0.1)))
def grid_plan(p, v, risk):
    # local grid 6 m x 4 m, 0.2 m; free = inside the path strip, minus obstacles; unseen cells cost more if risk
    res = 0.2; nf, nl = 31, 21
    def cost(i, j):
        f, l = i * res, (j - 10) * res
        if abs(l - lat(p, f)) > p["half"] - BODY: return None
        for of, ol, r in p["obs"]:
            if math.hypot(f - of, l - ol) < r + BODY + 0.1: return None
        seen = f < 0.8 or abs(math.atan2(l, max(f, 0.1))) < FOV
        return 1.0 + (3.0 if (risk and not seen) else 0.0)
    start = (0, 10); goal_i = nf - 1
    pq = [(0, start)]; dist = {start: 0}; par = {}
    best = None
    while pq:
        d, (i, j) = heapq.heappop(pq)
        if d > dist.get((i, j), 1e9): continue
        if i == goal_i or (best is None or i > best[0]): best = (i, j)
        if i == goal_i: break
        for di, dj in ((1, 0), (1, -1), (1, 1), (0, -1), (0, 1)):
            ni, nj = i + di, j + dj
            if not (0 <= ni < nf and 0 <= nj < nl): continue
            c = cost(ni, nj)
            if c is None: continue
            nd = d + c * math.hypot(di, dj) + 0.3 * abs(dj)
            if nd < dist.get((ni, nj), 1e9): dist[(ni, nj)] = nd; par[(ni, nj)] = (i, j); heapq.heappush(pq, (nd, (ni, nj)))
    if best is None or best[0] < 3: return None
    node = best; pts = []
    while node in par: pts.append(node); node = par[node]
    pts = pts[::-1]; L = min(max(0.8 * v, 1.5), 3.0)
    tgt = next((q for q in pts if q[0] * res >= L), pts[-1])
    return math.degrees(math.atan2((tgt[1] - 10) * res, tgt[0] * res))
def astar(p, v, mem): return grid_plan(p, v, False)
def risk_astar(p, v, mem): return grid_plan(p, v, True)
def dwa(p, v, mem):
    best, bs = 0.0, -1e9
    for rate in np.radians(np.linspace(-40, 40, 17)):
        f = l = h = 0.0; score = 0.0; ok = True
        for _ in range(int(1.5 / DT)):
            h += rate * DT; f += v * DT * math.cos(h); l += v * DT * math.sin(h)
            e = l - lat(p, f)
            if abs(e) > p["half"] - BODY: score -= 5
            for of, ol, r in p["obs"]:
                if math.hypot(f - of, l - ol) < r + BODY: ok = False
        if not ok: continue
        score += -abs(l - lat(p, f)) * 2 - abs(h - math.atan(p["slope"] + p["kappa"] * f)) + 0.3 * f
        if score > bs: bs, best = score, rate
    return math.degrees(best * REACT * 2)
def mpc(p, v, mem):
    # choose the cue (none / correct L,R x 3 levels) whose predicted runner response minimises cost over 2 s
    best, bc = None, 1e9
    for cue in [0, -5, 5, -10, 10, -20, 20]:
        f = l = 0.0; h = 0.0; cost = 0.0
        for k in range(int(2.0 / DT)):
            t = k * DT
            if REACT <= t < REACT + 0.5: h += math.radians(cue) / 0.5 * DT
            f += v * DT * math.cos(h); l += v * DT * math.sin(h)
            e = l - lat(p, f); cost += e * e
            for of, ol, r in p["obs"]:
                if math.hypot(f - of, l - ol) < r + BODY: cost += 50
        cost += 0.4 * (cue != 0)
        if cost < bc: bc, best = cost, cue
    return float(best)
class CurvatureFF:
    """StableGuidance design: offset+heading feedback with hysteresis and dwell, bends from curvature."""
    T = 1.6; DWELL = 0.6; CONF = 3  # horizon chosen by the sweep in benchmarks/results/planner_sim.txt
    def __init__(self): self.state = "C"; self.t = 0.0; self.cand = None; self.n = 0; self.k = 0.0; self.o = 0.0; self.hd = 0.0
    def __call__(self, p, v, mem):
        a = 0.3; self.o += a * (p["off"] - self.o); self.hd += a * (math.degrees(math.atan(p["slope"])) - self.hd); self.k += 0.35 * (p["kappa"] - self.k)
        band = max(0.0, p["half"] - 0.4 - 0.4)
        pred = self.o + v * self.T * math.tan(math.radians(self.hd)); corr = 0.0 if abs(pred) <= band else pred - band * np.sign(pred)
        if p["obs"]:
            of, ol, r = min(p["obs"]); side = -np.sign(ol) if abs(ol) < r + BODY + 0.3 else 0
            if side: return ("obstacle", int(side))
        correcting = self.state in ("CL", "CR"); incurve = self.state in ("KL", "KR")
        need = abs(corr) >= (0.15 if correcting else 0.30)
        counter = abs(self.hd) >= 8 and np.sign(self.hd) != np.sign(self.k)
        bend = abs(self.k) >= (0.03 if incurve else 0.05) and not counter
        lvl = 3 if abs(corr) >= 1.0 else 2 if abs(corr) >= 0.6 else 1
        if need and not (bend and lvl == 1 and np.sign(corr) == np.sign(self.k)): want = "CR" if corr > 0 else "CL"
        elif bend: want = "KR" if self.k > 0 else "KL"
        else: want = "C"
        self.t += DT
        if want != self.state:
            self.n = self.n + 1 if want == self.cand else 1; self.cand = want
            flip = want[1:] and self.state[1:] and want[1] != self.state[1]
            if (lvl == 3 and want[0] == "C" and len(want) == 2) or (self.n >= (6 if flip else self.CONF) and self.t >= self.DWELL):
                self.state, self.t, self.n = want, 0.0, 0
        if self.state in ("CL", "CR"): return ("correct", 1 if self.state == "CR" else -1, lvl)
        if self.state in ("KL", "KR"):
            kl = 3 if abs(self.k) >= 0.15 else 2 if abs(self.k) >= 0.08 else 1
            return ("curve", 1 if self.state == "KR" else -1, kl, abs(self.k))
        return ("center",)

ALGOS = {"pure_pursuit": lambda: pure_pursuit, "stanley": lambda: stanley, "mpc_sampled": lambda: mpc, "dwa": lambda: dwa,
         "potential_fields": lambda: potential, "astar_local": lambda: astar, "curvature_ff_hysteresis (StableGuidance)": CurvatureFF,
         "risk_aware_astar": lambda: risk_astar}

# ---------- cue quantiser + runner ----------
def simulate(sc, algo, v, seed):
    rng = np.random.default_rng(seed); ctrl = ALGOS[algo]()
    path = sc["path"]; h0 = math.atan2(path[5, 1] - path[0, 1], path[5, 0] - path[0, 0])
    x, y = path[0, 0] - math.sin(h0) * sc["start_off"] * -1, path[0, 1] + math.cos(h0) * sc["start_off"] * -1
    h = h0; pending = []; turns = []; last_key_t = {}; last_cue_t = -9; last_side = None; last_side_t = -9
    cues = 0; flips = 0; errs = []; off_edge = 0; hit = False; dnf = False; t = 0.0; lost = 0
    maxrate = math.radians(40 if v < 2 else 25); end = len(path) - 30
    while True:
        i, e = nearest(path, x, y)
        if i >= end: break
        errs.append(e)
        if abs(e) > sc["half"] - BODY: off_edge += 1
        if abs(e) > sc["half"] + 1.5 or t > 120: dnf = True; break
        for ox, oy, r in sc["obst"]:
            if math.hypot(x - ox, y - oy) < r + BODY: hit = True
        p = perceive(sc, (x, y, h), rng)
        cue = None
        if p is None: lost += 1
        else:
            out = ctrl(p, v, None)
            if isinstance(out, tuple): cue = out
            elif out is not None and abs(out) >= 4: cue = ("correct", 1 if out > 0 else -1, 3 if abs(out) >= 20 else 2 if abs(out) >= 10 else 1)
            elif out is None: cue = ("stop",)
        # the app's scheduler: repeat limits per cue, a change of cue waits 0.8 s unless strong or an obstacle
        if cue and cue[0] != "center":
            key = (cue[0], cue[1] if len(cue) > 1 else 0)
            high = cue[0] == "correct" and cue[2] == 3
            repeat = 1.2 if cue[0] == "obstacle" else 0.65 if high else 0.8 if cue[0] == "correct" else 1.6
            urgent = high or cue[0] == "obstacle"
            if t - last_key_t.get(key, -9) >= repeat and (urgent or t - last_cue_t >= 0.8):
                side = cue[1] if len(cue) > 1 else None
                if side and last_side and side != last_side and t - last_side_t < 1.0: flips += 1
                if side: last_side, last_side_t = side, t
                last_key_t[key] = t; last_cue_t = t; cues += 1; pending.append((t + REACT, cue))
        # the runner acts on each cue after the reaction time: a heading change spread over a moment
        while pending and pending[0][0] <= t:
            _, c = pending.pop(0)
            if c[0] == "correct": turns.append([math.radians({1: 6, 2: 12, 3: 22}[c[2]]) * c[1], 0.5])
            elif c[0] == "obstacle": turns.append([math.radians(25) * c[1], 0.6])
            elif c[0] == "curve": turns.append([math.radians({1: 10, 2: 20, 3: 35}[c[2]]) * c[1], 1.5])
        dh = 0.0
        for tr in turns:
            step = tr[0] / max(tr[1], DT) * DT; dh += step; tr[0] -= step; tr[1] -= DT
        turns = [tr for tr in turns if tr[1] > 1e-9]
        dh = max(-maxrate * DT, min(maxrate * DT, dh))
        h -= dh  # + right means clockwise (world y up => heading decreases)
        h += rng.normal(0, math.radians(2.0) * math.sqrt(DT))  # drift
        x += v * DT * math.cos(h); y += v * DT * math.sin(h); t += DT
    errs = np.array(errs) if errs else np.array([0.0])
    return dict(rms=float(np.sqrt(np.mean(errs ** 2))), off_edge=off_edge / max(1, len(errs)), hit=hit, dnf=dnf, cues_per_min=cues / max(t, 1e-3) * 60, flips=flips)

if __name__ == "__main__":
    runs = int(sys.argv[1]) if len(sys.argv) > 1 else 8
    rows = []
    for name, sc in SCEN.items():
        for v in (1.4, 2.8):
            for algo in ALGOS:
                rs = [simulate(sc, algo, v, s) for s in range(runs)]
                rows.append(dict(scenario=name, speed=v, algo=algo, rms=np.mean([r["rms"] for r in rs]), off_edge=np.mean([r["off_edge"] for r in rs]),
                                 hits=sum(r["hit"] for r in rs), dnf=sum(r["dnf"] for r in rs), cues=np.mean([r["cues_per_min"] for r in rs]), flips=np.mean([r["flips"] for r in rs]), n=runs))
    out = ["SIMULATION (model of a runner who only gets discrete cues; not a field measurement). " + str(runs) + " seeds per cell.",
           "scenario | speed | algorithm | RMS cross-track m | time past edge | collisions | did not finish | cues/min | opposite cue within 1 s"]
    for r in rows: out.append(f"{r['scenario']} | {r['speed']} | {r['algo']} | {r['rms']:.2f} | {r['off_edge']:.0%} | {r['hits']}/{r['n']} | {r['dnf']}/{r['n']} | {r['cues']:.0f} | {r['flips']:.1f}")
    out.append("\nOVERALL (mean over scenarios and speeds)")
    for a in ALGOS:
        rr = [r for r in rows if r["algo"] == a]
        out.append(f"{a} | RMS {np.mean([r['rms'] for r in rr]):.2f} m | past edge {np.mean([r['off_edge'] for r in rr]):.0%} | collisions {sum(r['hits'] for r in rr)} | did not finish {sum(r['dnf'] for r in rr)} | cues/min {np.mean([r['cues'] for r in rr]):.0f} | fast opposite cues {np.mean([r['flips'] for r in rr]):.2f}")
    open("/home/user/basir-run-/BasirRun_Research/benchmarks/results/planner_sim.txt", "w").write("\n".join(out) + "\n")
    print("\n".join(out[-10:]))
