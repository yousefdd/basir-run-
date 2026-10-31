"""End-to-end virtual path evaluation on SANPO frames with reference geometry (frame_geometry.csv).
For each method a path is produced and its points are looked up in the human labels:
  on_walkable  - share of path points on sidewalk/crosswalk/paved trail/other walkable/terrain
  on_hazard    - share on road, curb, obstacle, pedestrian, vehicle, pole, wall, vegetation, water, stairs
A frame is 'safe path' when >= 90% of its points are walkable and none is on an obstacle class;
'no path' frames are counted separately (the engine then stops, which is safe but unhelpful).
Methods: current engine (centre of its image-space region), the engine's FreeSpacePlanner on the
ADE20K-B0 walkable mask, and the same planner on the human mask (upper bound for the planner)."""
import csv, os, subprocess, sys, collections
import numpy as np
from PIL import Image
R = "/home/user/basir-run-/BasirRun_Research"
S = "/tmp/claude-0/-home-user-basir-run-/db717bb8-cb66-5ecd-9762-13ebad485a32/scratchpad"
GEO = {(r["session"], r["frame"]): r for r in csv.DictReader(open(R + "/datasets/sanpo/frame_geometry.csv"))}
cat = {r["session"]: r["category"] for r in csv.DictReader(open(R + "/datasets/sanpo/selected_sessions.csv"))}
WALK = [3, 5, 6, 17, 30]
HAZ = [1, 2, 4, 7, 8, 12, 13, 14, 15, 16, 18, 19, 20, 21, 24, 25, 26, 28, 29]
OBST = [4, 7, 8, 12, 13, 14, 20, 21, 24, 25, 26, 28]
frames = [l.strip() for l in open(R + "/benchmarks/sanpo_list.txt") if l.strip()]
frames = [f for f in frames if (f.split("/")[-2], os.path.basename(f)[:-4]) in GEO]
out = R + "/experiment_results/sanpo/paths"; os.makedirs(out, exist_ok=True)
# Human walkable masks as planner input (upper bound)
gtdir = out + "/gt_masks"; os.makedirs(gtdir, exist_ok=True)
for f in frames:
    sem = np.asarray(Image.open(f[:-4] + "_sem.png"))
    Image.fromarray((np.isin(sem, WALK) * 255).astype(np.uint8)).save(f"{gtdir}/{f.split('/')[-2]}__{os.path.basename(f)[:-4]}.png")
def run_planner(maskdir, tag):
    jobs = out + f"/jobs_{tag}.csv"
    with open(jobs, "w") as fh:
        for f in frames:
            g = GEO[(f.split("/")[-2], os.path.basename(f)[:-4])]
            fh.write(f"{maskdir}/{f.split('/')[-2]}__{os.path.basename(f)[:-4]}.png,{g['hfov_deg']},{g['height_m']},{g['pitch_deg']}\n")
    res = out + f"/routes_{tag}.csv"
    subprocess.run(["java", "-cp", f"{R}/tools/kt/planner.jar:{S}/kc/kotlinc/lib/kotlin-stdlib.jar", "PlannerHarnessKt", jobs, res], check=True, capture_output=True)
    rows = {}
    for r in csv.DictReader(open(res)):
        key = os.path.basename(r["mask"])[:-4]
        pts = [] if r["status"] != "ROUTE" else [tuple(map(float, p.split(":"))) for p in r["points"].split()]
        rows[key] = [(u, v) for u, v, fwd in pts if fwd >= 0.8]  # points from 0.8 m ahead (the first visible ground)
    return rows
def current_engine_paths():
    rows = {}
    for f in frames:
        key = f"{f.split('/')[-2]}__{os.path.basename(f)[:-4]}"
        m = np.asarray(Image.open(f"{R}/experiment_results/sanpo/current_engine/{key}.png")) > 0
        h, w = m.shape; pts = []
        for v in np.linspace(0.6, 0.98, 12):
            xs = np.nonzero(m[int(v * h)])[0]
            if len(xs): pts.append(((xs.min() + xs.max()) / 2 / w, v))
        rows[key] = pts
    return rows
methods = {"current_engine": current_engine_paths(),
           "planner_on_ade_b0_256 (fixed classes)": run_planner(R + "/experiment_results/sanpo/masks_ade_b0_256", "ade"),
           "planner_on_ade_b0_256 (surface underfoot)": run_planner(R + "/experiment_results/sanpo/masks_ade_b0_256_underfoot", "ade_uf"),
           "planner_on_yolop320_drivable": run_planner(R + "/experiment_results/sanpo/masks_yolop320", "yolop"),
           "planner_on_segformer_AND_yolop": run_planner(R + "/experiment_results/sanpo/masks_seg_and_yolop", "and"),
           "planner_on_underfoot_plus_dfine (shipped chain)": run_planner(R + "/experiment_results/sanpo/masks_underfoot_dfine", "uf_dfine"),
           "planner_on_AND_plus_dfine": run_planner(R + "/experiment_results/sanpo/masks_and_dfine", "and_dfine"),
           "planner_on_veto_0.005_plus_dfine": run_planner(R + "/experiment_results/sanpo/masks_veto_0.005", "veto_0.005"),
           "planner_on_veto_0.01_plus_dfine": run_planner(R + "/experiment_results/sanpo/masks_veto_0.01", "veto_0.01"),
           "planner_on_veto_0.02_plus_dfine": run_planner(R + "/experiment_results/sanpo/masks_veto_0.02", "veto_0.02"),
           "planner_on_veto_0.04_plus_dfine": run_planner(R + "/experiment_results/sanpo/masks_veto_0.04", "veto_0.04"),
           "planner_on_continuity_plus_dfine": run_planner(R + "/experiment_results/sanpo/masks_continuity_dfine", "continuity"),
           "planner_on_segformer_cityscapes_768_plus_dfine": run_planner(R + "/experiment_results/sanpo/masks_segformer_b0_cityscapes_768_dfine", "cs768"),
           "planner_on_segformer_cityscapes_512_plus_dfine": run_planner(R + "/experiment_results/sanpo/masks_segformer_b0_cityscapes_512_dfine", "cs512"),
           "planner_on_human_mask (upper bound)": run_planner(gtdir, "gt")}
summary = []
for name, rows in methods.items():
    stats = collections.defaultdict(lambda: collections.Counter())
    for f in frames:
        sess = f.split("/")[-2]; key = f"{sess}__{os.path.basename(f)[:-4]}"
        sem = np.asarray(Image.open(f[:-4] + "_sem.png")); h, w = sem.shape
        pts = rows.get(key, [])
        labs = [sem[min(h - 1, int(v * h)), min(w - 1, int(u * w))] for u, v in pts if 0 <= u < 1 and 0 <= v < 1]
        labs = [l for l in labs if l != 0]
        for c in ("all", cat[sess]):
            s = stats[c]; s["frames"] += 1
            if len(labs) < 3: s["no_path"] += 1; continue
            walk = np.mean(np.isin(labs, WALK)); obst = np.any(np.isin(labs, OBST))
            s["walk_sum"] += walk; s["with_path"] += 1
            if walk >= 0.9 and not obst: s["safe"] += 1
            if obst: s["through_obstacle"] += 1
    for c, s in sorted(stats.items(), key=lambda kv: kv[0] != "all"):
        wp = max(1, s["with_path"])
        summary.append(dict(method=name, category=c, frames=s["frames"], no_path=s["no_path"], safe_path=s["safe"],
                            through_obstacle=s["through_obstacle"], mean_on_walkable=round(s["walk_sum"] / wp, 3)))
with open(out + "/path_summary.csv", "w", newline="") as fh:
    wtr = csv.DictWriter(fh, list(summary[0].keys())); wtr.writeheader(); wtr.writerows(summary)
for r in summary:
    if r["category"] == "all": print(r)
