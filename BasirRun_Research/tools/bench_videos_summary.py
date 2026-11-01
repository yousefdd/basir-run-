"""Summarises visual_reports/round4: per clip and engine run, behaviour metrics measured on the same frames.
  felt stop / obstacle vibrations per minute, direction cues per minute and side flips per minute,
  share of frames in STOP_REQUIRED / VISION_UNCERTAIN / TRACK_LOST, share of frames whose corridor ahead is
  less than half walkable (from the walkable masks), median per-stage desktop times and their sum,
  terrain-zone shares and rough-surface cues (round-4 engine only).
Writes benchmarks/results/video_benchmark.csv and video_benchmark.txt."""
import csv, glob, os, numpy as np
from PIL import Image
R = "/home/user/basir-run-/BasirRun_Research"; FPS = 5.0
URBAN = {"walking", "wet_surfaces", "obstacles", "difficult_lighting", "low_contrast"}
rows = []
for d in sorted(glob.glob(R + "/visual_reports/round4/*/*/")):
    cat = d.rstrip("/").split("/")[-2]; run = d.rstrip("/").split("/")[-1]
    variant = "offroad512" if "_offroad512_" in run else "segformer256"
    clip = run.split("_segformer256_")[0].split("_offroad512_")[0]
    masks = sorted(glob.glob(d + "m/*.png"))
    blocked = None
    if masks:
        b = []
        for p in masks:
            m = np.asarray(Image.open(p)) > 0; h, w = m.shape
            b.append(m[int(h * .55):, int(w * .3):int(w * .7)].mean() < 0.5)
        blocked = float(np.mean(b))
    terr = {}
    if os.path.exists(d + "terrain_visual.csv"):
        t = list(csv.DictReader(open(d + "terrain_visual.csv")))
        terr = dict(surface_cues=sum(r["surface_cue"] == "true" for r in t), corr_caution=np.mean([float(r["corr_caution"]) for r in t]),
                    corr_unclear=np.mean([float(r["corr_unclear"]) for r in t]), corr_unverified=np.mean([float(r["corr_unverified"]) for r in t]),
                    foot_caution=np.mean([float(r["foot_caution"]) for r in t]))
    for eng_csv in sorted(glob.glob(d + "engine_*.csv")):
        tag = os.path.basename(eng_csv)[len("engine_"):-4]
        e = list(csv.DictReader(open(eng_csv))); fr = list(csv.DictReader(open(d + f"frames_{tag}.csv")))
        n = len(e); mins = n / FPS / 60
        felt = [r["felt"] for r in e if r["felt"]]
        dirs = [f for f in felt if f.startswith(("correct_", "curve_", "obstacle_left", "obstacle_right"))]
        side = lambda f: "L" if "left" in f else "R" if "right" in f else None
        flips = sum(1 for a, b in zip(dirs, dirs[1:]) if side(a) and side(b) and side(a) != side(b))
        st = lambda s: sum(r["state"] == s for r in e) / n
        med = lambda k: float(np.median([float(r[k]) for r in fr]))
        row = dict(category=cat, clip=clip, walkable_channel=variant, engine="0.12.0" if tag == "visual_base" else "round4", frames=n, minutes=round(mins, 2),
                   felt_stops_per_min=round(sum(f == "stop" for f in felt) / mins, 2), felt_stops=sum(f == "stop" for f in felt),
                   felt_obstacle_per_min=round(sum(f.startswith("obstacle") for f in felt) / mins, 2), felt_obstacles=sum(f.startswith("obstacle") for f in felt),
                   direction_cues_per_min=round(len(dirs) / mins, 2), side_flips_per_min=round(flips / mins, 2),
                   stop_required_share=round(st("STOP_REQUIRED"), 3), uncertain_share=round(st("VISION_UNCERTAIN"), 3), lost_share=round(st("TRACK_LOST"), 3),
                   blocked_corridor_share=round(blocked, 3) if blocked is not None else "",
                   ms_walkable=round(med("ms_walkable"), 1), ms_line=round(med("ms_line"), 1), ms_detector=round(med("ms_detector"), 1),
                   ms_engine=round(med("ms_grid") + med("ms_plan") + med("ms_guide"), 2),
                   ms_sum_sequential=round(med("ms_walkable") + med("ms_line") + med("ms_detector") + med("ms_grid") + med("ms_plan") + med("ms_guide"), 1))
        if tag == "visual" and variant == "segformer256": row.update({k: (round(v, 3) if isinstance(v, float) else v) for k, v in terr.items()})
        rows.append(row)
keys = list(dict.fromkeys(k for r in rows for k in r))
with open(R + "/benchmarks/results/video_benchmark.csv", "w", newline="") as f:
    w = csv.DictWriter(f, keys); w.writeheader(); [w.writerow(r) for r in rows]

def total(sel, k): return sum(float(r[k]) for r in sel)
L = [f"Round-4 video benchmark: {len({r['clip'] for r in rows})} clips, 5 fps, same frames/masks/detections for both engines (desktop CPU)."]
base = [r for r in rows if r["engine"] == "0.12.0"]; new = [r for r in rows if r["engine"] == "round4" and r["walkable_channel"] == "segformer256"]
for name, sel in (("0.12.0 engine", base), ("round-4 engine", new)):
    m = total(sel, "minutes")
    L.append(f"{name}: {m:.1f} min, felt stops {int(total(sel, 'felt_stops'))} ({total(sel, 'felt_stops') / m:.2f}/min), felt obstacle warnings {int(total(sel, 'felt_obstacles'))} "
             f"({total(sel, 'felt_obstacles') / m:.2f}/min), direction cues {sum(r['direction_cues_per_min'] * r['minutes'] for r in sel) / m:.2f}/min, side flips {sum(r['side_flips_per_min'] * r['minutes'] for r in sel) / m:.2f}/min")
gain = [(r["clip"], int(n_["felt_stops"]) - int(r["felt_stops"])) for r in base for n_ in new if n_["clip"] == r["clip"]]
L.append("clips whose felt stops changed (round4 - 0.12.0): " + (", ".join(f"{c} {d:+d}" for c, d in gain if d) or "none"))
urban_run = lambda r: r["category"] == "running" and r["clip"].startswith("sanpo_") and "66XHCmoc" not in r["clip"] and "OLLkkVZ1" not in r["clip"]  # same rule as make_decision.py
cues = [(r["clip"], "urban" if r["category"] in URBAN or urban_run(r) else r["category"], r.get("surface_cues", 0)) for r in new]
m = total(new, "minutes")
L.append(f"rough-surface cues: {sum(c for _, _, c in cues)} in {m:.1f} min ({sum(c for _, _, c in cues) / m:.2f}/min); on urban clips: {sum(c for _, cat, c in cues if cat == "urban")}; clips with a cue: " + (", ".join(f"{c} ({n})" for c, _, n in cues if n) or "none"))
L.append("")
L.append("trail-like clips, walkable channel shipped ADE vs off-road B2 (round-4 engine): blocked-corridor share | felt stops | stop share")
for r in new:
    o = next((x for x in rows if x["clip"] == r["clip"] and x["walkable_channel"] == "offroad512"), None)
    if o: L.append(f"{r['clip']}: ADE {r['blocked_corridor_share']} | {r['felt_stops']} | {r['stop_required_share']}   off-road {o['blocked_corridor_share']} | {o['felt_stops']} | {o['stop_required_share']}   (walkable ms {r['ms_walkable']} vs {o['ms_walkable']})")
L.append("")
L.append("per clip (round-4 engine, shipped walkable channel): clip | category | min | stops/min | obstacle/min | direction/min | flips/min | stop share | uncertain share | blocked corridor | ms sum")
for r in new:
    L.append(f"{r['clip']} | {r['category']} | {r['minutes']} | {r['felt_stops_per_min']} | {r['felt_obstacle_per_min']} | {r['direction_cues_per_min']} | {r['side_flips_per_min']} | {r['stop_required_share']} | {r['uncertain_share']} | {r['blocked_corridor_share']} | {r['ms_sum_sequential']}")
open(R + "/benchmarks/results/video_benchmark.txt", "w").write("\n".join(L) + "\n"); print("\n".join(L[:12]))
