"""Old vs new guidance controller, and the four smoothing filters, on 5 real clips (cached walkable masks):
4 SANPO chest-camera sequences (CC BY 4.0) and the owner's night video. Writes
benchmarks/results/guidance_compare.txt and smoothing_compare.txt.
Smoothing metrics, on the controller's three inputs (offset m, heading deg, curvature 1/m):
  jitter  = median |frame-to-frame change| of the filtered signal (lower = calmer cues)
  rms_ref = RMS distance to a zero-phase reference (centred Gaussian, sigma 3 frames, of the raw
            signal; it cannot run live but shows the 'true' slow signal) -> captures lag + residual noise
  lag     = frames of delay that best align filtered to reference (cross-correlation, 0..10)"""
import csv, os, subprocess, collections, numpy as np
R = "/home/user/basir-run-/BasirRun_Research"; S = "/tmp/claude-0/-home-user-basir-run-/db717bb8-cb66-5ecd-9762-13ebad485a32/scratchpad"
CLIPS = [("sanpo_gravel_5n1pzdkv", 1.4), ("sanpo_terrain_park_-F9N8JhM", 1.4), ("sanpo_jogging_0X2rC_H2", 2.5), ("sanpo_running_1uta1HvN", 2.8), ("20261030_225434", 1.4)]
SM = ["NONE", "EMA", "ONE_EURO", "KALMAN", "MEDIAN"]
def run(clip, speed):
    d = f"{R}/experiment_results/masks_cache/{clip}"; out = f"{d}/guidance.csv"
    subprocess.run(["java", "-cp", f"{R}/tools/kt/guidance.jar:{S}/kc/kotlinc/lib/kotlin-stdlib.jar", "GuidanceHarnessKt", f"{d}/jobs.csv", out, str(speed)], check=True, capture_output=True)
    return list(csv.DictReader(open(out)))
def cmd_metrics(rows, cmdk, sidek, feltk):
    cmds = [r[cmdk] + ("-" + r[sidek] if r[sidek] else "") for r in rows]
    changes = sum(a != b for a, b in zip(cmds, cmds[1:]))
    dirs = [r[sidek] for r in rows if r[cmdk] in ("CORRECT", "CURVE") and r[sidek]]
    flips = sum(a != b for a, b in zip(dirs, dirs[1:]))
    # a flip is only counted when the opposite cue comes within 1 s (15 frames)
    fast_flips = 0; last = None
    for i, r in enumerate(rows):
        if r[cmdk] in ("CORRECT", "CURVE") and r[sidek]:
            if last and last[1] != r[sidek] and i - last[0] <= 15: fast_flips += 1
            last = (i, r[sidek])
    stops = sum(r[cmdk] == "STOP" for r in rows)
    stop_go = sum(a == "STOP" and b != "STOP" for a, b in zip([r[cmdk] for r in rows], [r[cmdk] for r in rows][1:]))
    felt = [r[feltk] for r in rows if r[feltk]]
    corr = sum(f.startswith("correct") for f in felt); curve = sum(f.startswith("curve") for f in felt)
    return dict(changes=changes, flips=flips, fast_flips=fast_flips, stops=stops, stop_go=stop_go, felt=len(felt), felt_correct=corr, felt_curve=curve)
def gauss(x, s=3):
    k = np.exp(-0.5 * (np.arange(-3 * s, 3 * s + 1) / s) ** 2); k /= k.sum()
    xp = np.pad(x, 3 * s, mode="edge"); return np.convolve(xp, k, mode="valid")
glines = ["clip | speed | controller | command changes | left/right changes (all) | opposite cue within 1 s | stop frames | stop->go | vibrations felt | of which correction | of which bend"]
tot = collections.defaultdict(collections.Counter)
smooth = collections.defaultdict(lambda: collections.defaultdict(list))
for clip, speed in CLIPS:
    rows = run(clip, speed)
    sets = [("old (pure pursuit)", "old_cmd", "old_side", "old_felt")] + [(f"new {s}", f"{s}_cmd", f"{s}_side", f"{s}_felt") for s in SM] + [("new ONE_EURO + centreline", "cl_cmd", "cl_side", "cl_felt")]
    for name, ck, sk, fk in sets:
        m = cmd_metrics(rows, ck, sk, fk); tot[name].update(m)
        glines.append(f"{clip} | {speed} | {name} | {m['changes']} | {m['flips']} | {m['fast_flips']} | {m['stops']} | {m['stop_go']} | {m['felt']} | {m['felt_correct']} | {m['felt_curve']}")
    have = [r for r in rows if r["offset_raw"]]
    # contiguous runs with a path, so filters are compared on the same frames
    for sig, rawk, sfx in [("offset", "offset_raw", "offset"), ("heading", "heading_raw", "heading"), ("curvature", "curv_raw", "curv")]:
        raw = np.array([float(r[rawk]) for r in have])
        if len(raw) < 20: continue
        ref = gauss(raw)
        for s in SM:
            f = np.array([float(r[f"{s}_{sfx}"]) for r in have])
            jit = np.median(np.abs(np.diff(f))); rms = np.sqrt(np.mean((f - ref) ** 2))
            lag = int(np.argmin([np.mean((f[k:] - ref[:len(ref) - k]) ** 2) for k in range(0, 11)]))
            smooth[sig][s].append((jit, rms, lag, len(raw)))
glines.append("\nTOTAL over 5 clips (" + str(sum(1 for _ in CLIPS)) + ")")
for name, m in tot.items():
    glines.append(f"{name} | command changes {m['changes']} | left/right changes {m['flips']} | opposite within 1 s {m['fast_flips']} | stop frames {m['stops']} | stop->go {m['stop_go']} | felt {m['felt']} (correction {m['felt_correct']}, bend {m['felt_curve']})")
open(R + "/benchmarks/results/guidance_compare.txt", "w").write("\n".join(glines) + "\n")
slines = ["signal | filter | jitter (median |change|/frame) | RMS to zero-phase reference | lag (frames at 15 fps)"]
for sig, d in smooth.items():
    for s in SM:
        v = d[s]; w = np.array([x[3] for x in v], float)
        slines.append(f"{sig} | {s} | {np.average([x[0] for x in v], weights=w):.4f} | {np.average([x[1] for x in v], weights=w):.4f} | {np.average([x[2] for x in v], weights=w):.1f}")
# Real curved walks (turn side from optical flow, benchmarks/results/curve_clips_flow_yaw.txt), frames 30-119
CURVES = [("sanpo_curve4_L_pP-G4Xoz", "RIGHT", 92), ("sanpo_curve5_R_hnVXDPEU", "LEFT", 50), ("sanpo_curve3_R_-F9N8JhM", "RIGHT", 29)]
clines = ["Real curved walks (SANPO chest camera; turn side measured by optical flow, not by the engine). Frames 30-119 = the turn.",
          "clip | turn | chain | frames cueing the turn side | frames cueing the opposite side | as bend cue | as correction | felt toward / opposite | stop frames | median path curvature (1/m)"]
for clip, side, deg in CURVES:
    rows = run(clip, 1.4)[30:]
    for name, ck, sk, fk, kk in [("old (pure pursuit)", "old_cmd", "old_side", "old_felt", "curv_raw"), ("new (stable)", "ONE_EURO_cmd", "ONE_EURO_side", "ONE_EURO_felt", "curv_raw"),
                                 ("new + centreline", "cl_cmd", "cl_side", "cl_felt", "cl_curv_raw")]:
        toward = sum(r[sk] == side and r[ck] in ("CORRECT", "CURVE") for r in rows); opp = sum(r[sk] not in ("", side) and r[ck] in ("CORRECT", "CURVE") for r in rows)
        bend = sum(r[ck] == "CURVE" and r[sk] == side for r in rows); corr = sum(r[ck] == "CORRECT" and r[sk] == side for r in rows)
        s_ = side.lower(); o_ = "left" if s_ == "right" else "right"
        ft = sum(1 for r in rows if r[fk] and s_ in r[fk]); fo = sum(1 for r in rows if r[fk] and o_ in r[fk])
        k = sorted(float(r[kk]) for r in rows if r[kk])
        clines.append(f"{clip} | {side} {deg} | {name} | {toward}/{len(rows)} | {opp} | {bend} | {corr} | {ft} / {fo} | {sum(r[ck] == 'STOP' for r in rows)} | {k[len(k)//2] if k else 0:+.3f}")
open(R + "/benchmarks/results/curves_real.txt", "w").write("\n".join(clines) + "\n"); print("\n".join(clines)); print()
open(R + "/benchmarks/results/smoothing_compare.txt", "w").write("\n".join(slines) + "\n")
print("\n".join(glines[-7:])); print(); print("\n".join(slines))
