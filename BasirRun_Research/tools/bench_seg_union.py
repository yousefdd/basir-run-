"""Union of the app's depth warning (shipped config) and segmentation obstacle blobs, per frame, on the
same ground truth: does the blob source add fixed obstacles depth misses, and at what false-alarm cost?
Also scored on the 'silent' frames (no D-FINE box and the shipped mask calls the obstacle's foot walkable)."""
import csv, hashlib, os, sys, numpy as np
sys.argv = [sys.argv[0]]
src = open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "bench_seg_obstacles.py")).read().split("f = lambda x")[0]
exec(src)
R = "/home/user/basir-run-/BasirRun_Research"; D = R + "/experiment_results/sanpo/depth/"
dep = {(r["session"], r["frame"]): r["level"] in ("Possible", "Hazard") for r in csv.DictReader(open(D + "check_shipped.csv"))}
f = lambda x: float(x) if x else None
# silent frames from bench_depth_eval (recomputed the same way)
ev = {}
exec(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "bench_depth_eval.py")).read().replace('rows = list(csv.DictReader(open(D + f"/hazards{SUFFIX}.csv")))', 'rows = list(csv.DictReader(open(D + "/check_shipped.csv")))').replace('jobs = {(r["session"], r["frame"]): r for r in csv.DictReader(open(D + f"/jobs{SUFFIX}.csv"))}', 'jobs = {(r["session"], r["frame"]): r for r in csv.DictReader(open(D + "/jobs.csv"))}').split("lines = [")[0], ev)
silent_keys = {(r["session"], r["frame"]) for r in ev["silent"]}
out = ["source | static <= 4 m recall (both halves) | clear-frame false alarms | silent frames caught"]
for name, ids, mp in [("depth only (shipped)", None, 0), ("blobs street furniture", SETS["street_furniture"], 8), ("blobs +fence/railing", SETS["+fence_railing"], 8),
                      ("depth OR street furniture", SETS["street_furniture"], 8), ("depth OR +fence/railing", SETS["+fence_railing"], 8)]:
    def hit(j, m):
        k = (j["session"], j["frame"]); b = bool(ids) and bool(blobs_in_corridor(labels[k], j, ids, mp, m))
        d = dep[k]
        return (d or b) if name.startswith("depth OR") else (d if ids is None else b)
    s4 = [j for j in jobs if f(j["static_m"]) is not None and f(j["static_m"]) <= 4]
    cl = [j for j in jobs if all(f(j[c]) is None or f(j[c]) > 6 for c in ("static_m", "dynamic_m", "wall_m"))]
    sil = [j for j in jobs if (j["session"], j["frame"]) in silent_keys]
    halves = [sum(hit(j, 4.5) for j in s4 if int(hashlib.md5(j["session"].encode()).hexdigest(), 16) % 2 == h) for h in (0, 1)]
    out.append(f"{name} | {sum(halves)}/{len(s4)} ({halves[0]}+{halves[1]}) | {sum(hit(j, 4.0) for j in cl)}/{len(cl)} | {sum(hit(j, 4.5) for j in sil)}/{len(sil)}")
open(R + "/benchmarks/results/seg_obstacles_union.txt", "w").write("\n".join(out) + "\n"); print("\n".join(out))
