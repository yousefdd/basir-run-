"""Round 5 (owner's field-test rule): stops only when the way is closed for more than 1 s; commands and path
source change only after about a second. Runs the modified engine (tools/kt/visual_r5.jar) on the SAME saved
engine inputs (jobs.csv: frames, walkable masks, detections) of every round-4 library clip, and compares it with
the published engine (engine_visual_base.csv, 0.12.1 engine) frame for frame.
Writes benchmarks/results/stop_policy_r5.csv and .txt."""
import csv, glob, os, subprocess
R = "/home/user/basir-run-/BasirRun_Research"
K = "/tmp/claude-0/-home-user-basir-run-/db717bb8-cb66-5ecd-9762-13ebad485a32/scratchpad/kc/kotlinc/lib/kotlin-stdlib.jar"
FPS = 5.0
DIRS = ("correct_", "curve_", "obstacle_left", "obstacle_right")

def side(f): return "L" if "left" in f else "R" if "right" in f else None

def metrics(rows):
    n = len(rows); mins = n / FPS / 60
    felt = [r["felt"] for r in rows if r["felt"]]
    dirs = [f for f in felt if f.startswith(DIRS)]
    flips = sum(1 for a, b in zip(dirs, dirs[1:]) if side(a) and side(b) and side(a) != side(b))
    # a command change: the felt cue differs from the previous felt cue
    changes = sum(1 for a, b in zip(felt, felt[1:]) if a != b)
    modes = [r["mode"] for r in rows]
    mode_sw = sum(1 for a, b in zip(modes, modes[1:]) if a != b and "STOP" not in (a, b))
    return dict(frames=n, minutes=mins, stops=sum(f == "stop" for f in felt), obstacle=sum(f.startswith("obstacle") for f in felt),
                caution=sum(f == "caution" for f in felt), directions=len(dirs), flips=flips, changes=changes, mode_switches=mode_sw,
                stop_share=sum(r["state"] == "STOP_REQUIRED" for r in rows) / n)

out = []
for d in sorted(glob.glob(R + "/visual_reports/round4/*/*_segformer256_*/")):
    if "offroad" in d or not os.path.exists(d + "jobs.csv") or not os.path.exists(d + "engine_visual_base.csv"): continue
    cat = d.rstrip("/").split("/")[-2]; clip = d.rstrip("/").split("/")[-1].split("_segformer256_")[0]
    speed = "2.8" if cat == "running" else "1.4"
    subprocess.run(["java", "-cp", f"{R}/tools/kt/visual_r5.jar:{K}", "VisualHarnessKt", d + "jobs.csv", d + "engine_r5.csv", speed, "ONE_EURO", "0"],
                   check=True, capture_output=True)
    base = metrics(list(csv.DictReader(open(d + "engine_visual_base.csv"))))
    new = metrics(list(csv.DictReader(open(d + "engine_r5.csv"))))
    out.append(dict(category=cat, clip=clip, minutes=round(base["minutes"], 2), **{f"{k}_0121": v for k, v in base.items() if k not in ("frames", "minutes")},
                    **{f"{k}_r5": v for k, v in new.items() if k not in ("frames", "minutes")}))

keys = list(out[0])
with open(R + "/benchmarks/results/stop_policy_r5.csv", "w", newline="") as f:
    w = csv.DictWriter(f, keys); w.writeheader(); [w.writerow({k: (round(v, 3) if isinstance(v, float) else v) for k, v in r.items()}) for r in out]
m = sum(r["minutes"] for r in out)
L = [f"Round 5 stop policy: {len(out)} clips, {m:.1f} min, 5 fps, same saved inputs; 0.12.1 engine vs modified engine (desktop)."]
for k, name in (("stops", "felt stops"), ("obstacle", "felt obstacle warnings"), ("caution", "felt cautions (slow down)"), ("directions", "direction cues"),
                ("flips", "left/right flips"), ("changes", "command changes"), ("mode_switches", "path-source switches")):
    a = sum(r[f"{k}_0121"] for r in out); b = sum(r[f"{k}_r5"] for r in out)
    L.append(f"{name}: {a} ({a / m:.2f}/min) -> {b} ({b / m:.2f}/min)")
a = sum(r["stop_share_0121"] * r["minutes"] for r in out) / m; b = sum(r["stop_share_r5"] * r["minutes"] for r in out) / m
L.append(f"time in STOP_REQUIRED: {a:.1%} -> {b:.1%}")
L.append("clips that still stop (modified engine): " + (", ".join(f"{r['clip']} ({r['stops_r5']})" for r in out if r["stops_r5"]) or "none"))
open(R + "/benchmarks/results/stop_policy_r5.txt", "w").write("\n".join(L) + "\n"); print("\n".join(L))
