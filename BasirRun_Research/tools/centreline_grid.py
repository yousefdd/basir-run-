"""Tunes the centreline gate (bounded-row share, widest strip, bend confirmation frames) on the SANPO
clips (4 straight-ish + 3 real curves) and checks the choice on the owner's night video (held out).
Goal: keep real bends as bend cues, add no left/right changes and no extra command changes on the rest."""
import csv, subprocess, itertools
R = "/home/user/basir-run-/BasirRun_Research"; S = "/tmp/claude-0/-home-user-basir-run-/db717bb8-cb66-5ecd-9762-13ebad485a32/scratchpad"
TUNE_STRAIGHT = [("sanpo_gravel_5n1pzdkv", 1.4), ("sanpo_terrain_park_-F9N8JhM", 1.4), ("sanpo_jogging_0X2rC_H2", 2.5), ("sanpo_running_1uta1HvN", 2.8)]
CURVES = [("sanpo_curve4_L_pP-G4Xoz", "RIGHT"), ("sanpo_curve5_R_hnVXDPEU", "LEFT"), ("sanpo_curve3_R_-F9N8JhM", "RIGHT")]
HOLD = [("20261030_225434", 1.4)]
def run(clip, speed, share, mh, cc):
    d = f"{R}/experiment_results/masks_cache/{clip}"; out = f"{d}/g_{share}_{mh}_{cc}.csv"
    subprocess.run(["java", "-cp", f"{R}/tools/kt/guidance.jar:{S}/kc/kotlinc/lib/kotlin-stdlib.jar", "GuidanceHarnessKt", f"{d}/jobs.csv", out, str(speed), str(share), str(mh), str(cc)], check=True, capture_output=True)
    rows = list(csv.DictReader(open(out))); import os; os.remove(out); return rows
def m(rows, ck="cl_cmd", sk="cl_side"):
    cmds = [r[ck] + r[sk] for r in rows]; ch = sum(a != b for a, b in zip(cmds, cmds[1:]))
    dirs = [r[sk] for r in rows if r[ck] in ("CORRECT", "CURVE") and r[sk]]; fl = sum(a != b for a, b in zip(dirs, dirs[1:]))
    return ch, fl
res = []
for share, mh, cc in itertools.product([0.5, 0.7, 0.85], [99.0, 2.0, 1.5], [3, 8, 12]):
    ch = fl = 0; ch_old = 0
    for c, sp in TUNE_STRAIGHT:
        rows = run(c, sp, share, mh, cc); a, b = m(rows); ch += a; fl += b; ch_old += m(rows, "old_cmd", "old_side")[0]
    bend = opp = 0
    for c, side in CURVES:
        rows = run(c, 1.4, share, mh, cc)[30:]
        bend += sum(r["cl_cmd"] == "CURVE" and r["cl_side"] == side for r in rows); opp += sum(r["cl_side"] not in ("", side) and r["cl_cmd"] in ("CORRECT", "CURVE") for r in rows)
    res.append((share, mh, cc, ch, fl, bend, opp, ch_old))
lines = ["share | max half-width | bend confirm frames | command changes (straight SANPO) | left/right changes | bend-cue frames on real curves (toward) | opposite-side frames on curves | old controller command changes (straight)"]
for r in res: lines.append(" | ".join(str(x) for x in r))
# choice: no left/right change, no opposite cue on curves, command changes not above the old controller, then most bend frames
ok = [r for r in res if r[4] == 0 and r[6] == 0 and r[3] <= r[7]]
best = max(ok, key=lambda r: (r[5], -r[3])) if ok else None
lines.append(f"chosen: {best}")
if best:
    for c, sp in HOLD:
        rows = run(c, sp, best[0], best[1], best[2]); a, b = m(rows); o = m(rows, "old_cmd", "old_side")
        lines.append(f"held-out {c}: command changes {a} (old controller {o[0]}), left/right changes {b} (old {o[1]})")
open(R + "/benchmarks/results/centreline_grid.txt", "w").write("\n".join(lines) + "\n"); print("\n".join(lines[-12:]))
