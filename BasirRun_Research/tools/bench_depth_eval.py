"""Scores the app's depth hazards (DepthHarness output) against the human labels.
  recall  : frames with a static obstacle in the body-wide corridor within 4 m that get a depth warning
            (Possible or Hazard); within 2.5 m that get a Hazard (stop) -> per the RUNNING config
  false   : frames with nothing (static, people, vehicles, walls) in the corridor within 6 m that warn
  rescue  : frames where the static obstacle is silent for the rest of the system (no D-FINE box over its
            foot and the shipped walkable mask calls the foot walkable) and depth warns."""
import csv, math, os, numpy as np
from PIL import Image
from scipy import ndimage
R = "/home/user/basir-run-/BasirRun_Research"; D = R + "/experiment_results/sanpo/depth"
SUFFIX = os.environ.get("SUFFIX", "")
rows = list(csv.DictReader(open(D + f"/hazards{SUFFIX}.csv"))); jobs = {(r["session"], r["frame"]): r for r in csv.DictReader(open(D + f"/jobs{SUFFIX}.csv"))}
STATIC = [4, 20, 22, 23, 24, 25, 26]
def f(x): return float(x) if x else None
warn = lambda r: r["level"] in ("Possible", "Hazard")
s4 = [r for r in rows if f(r["static_m"]) is not None and f(r["static_m"]) <= 4]
s25 = [r for r in rows if f(r["static_m"]) is not None and f(r["static_m"]) <= 2.5]
clear = [r for r in rows if all((f(r[k]) is None or f(r[k]) > 6) for k in ("static_m", "dynamic_m", "wall_m"))]
silent = []
for r in s4:
    j = jobs[(r["session"], r["frame"])]
    sem = np.asarray(Image.open(f"{R}/datasets/sanpo/frames/{r['session']}/{r['frame']}_sem.png")) if os.path.exists(f"{R}/datasets/sanpo/frames/{r['session']}/{r['frame']}_sem.png") else None
    if sem is None:
        cand = [p for p in open(R + "/benchmarks/sanpo_list.txt").read().split() if r["session"] in p and p.endswith(r["frame"] + ".jpg")]
        sem = np.asarray(Image.open(cand[0][:-4] + "_sem.png"))
    H, W = sem.shape
    carved = np.asarray(Image.open(f"{R}/experiment_results/sanpo/masks_underfoot_dfine/{r['session']}__{r['frame']}.png").resize((W, H), Image.NEAREST)) > 0
    lab, n = ndimage.label(np.isin(sem, STATIC)); found = False
    for k in range(1, n + 1):
        ys, xs = np.nonzero(lab == k)
        if len(ys) < 0.001 * W * H: continue
        foot = {}
        for yy, xx in zip(ys, xs): foot[xx] = max(foot.get(xx, 0), yy)
        fx = np.array(list(foot.keys())); fy = np.array(list(foot.values()))
        inside = (fx > W * 0.3) & (fx < W * 0.7)
        if not inside.any(): continue
        below = np.clip(fy[inside] + 2, 0, H - 1)
        if carved[below, fx[inside]].mean() >= 0.5: found = True
    if found: silent.append(r)
lines = [f"App depth chain (Depth Anything V2 Small int8 at 252, FloorPlanner + DepthHazards RUNNING) on {len(rows)} SANPO frames",
         f"floor plane found: {sum(r['floor_valid']=='true' for r in rows)}/{len(rows)}",
         f"static obstacle in corridor <= 4 m: {len(s4)} frames, depth warns in {sum(map(warn, s4))} ({sum(map(warn, s4))/max(1,len(s4)):.0%})",
         f"static obstacle in corridor <= 2.5 m: {len(s25)} frames, depth stop-level in {sum(r['level']=='Hazard' for r in s25)} ({sum(r['level']=='Hazard' for r in s25)/max(1,len(s25)):.0%}), any warning {sum(map(warn, s25))}",
         f"nothing in corridor within 6 m: {len(clear)} frames, depth warns in {sum(map(warn, clear))} ({sum(map(warn, clear))/max(1,len(clear)):.0%}) (false alarms)",
         f"silent for detector + mask (static <= 4 m): {len(silent)} frames, depth warns in {sum(map(warn, silent))} ({sum(map(warn, silent))/max(1,len(silent)):.0%})"]
open(R + f"/benchmarks/results/depth_static_obstacles{SUFFIX}.txt", "w").write("\n".join(lines) + "\n"); print("\n".join(lines))
