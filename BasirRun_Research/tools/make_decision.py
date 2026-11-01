"""Applies benchmarks/decision_criteria_round4.md to the result files and writes
benchmarks/results/integration_decision.txt (one line per component: ADOPT / NOT ADOPTED and the numbers)."""
import csv, sys
R = "/home/user/basir-run-/BasirRun_Research"
lab = {(r["method"], r["split"]): r for r in csv.DictReader(open(R + "/benchmarks/results/terrain_labelled.csv"))}
f = lambda m, s, k: float(lab[(m, s)][k])
out = {}
# 1 walkable channel: dev-selected fusion must pass on test
c1 = f("terrain_selected", "test", "IoU") >= f("shipped_ade", "test", "IoU") - 0.01
c2 = f("terrain_selected", "test", "false_walkable") <= f("shipped_ade", "test", "false_walkable")
out["walkable_fusion"] = (c1 and c2, f"test IoU {f('terrain_selected', 'test', 'IoU')} vs shipped {f('shipped_ade', 'test', 'IoU')} (need >= -0.01: {c1}); "
                          f"false-walkable {f('terrain_selected', 'test', 'false_walkable')} vs {f('shipped_ade', 'test', 'false_walkable')} (need <=: {c2})")
# 2 obstacle class channel: no dev rule met FPR <= 0.08 (bench_terrain dev grid printed None)
ob = {(r["method"], r["split"]): r for r in csv.DictReader(open(R + "/benchmarks/results/terrain_obstacles.csv"))}
d = ob[("offroad obstacle class OR depth", "dev")]
out["offroad_obstacle_class"] = (False, f"no dev parameter set reached false-positive rate <= 0.08 with depth (untuned dev FPR {d['false_positive_rate']}, recall {d['recall']})")
# 3, 4 from the video benchmark
vb = list(csv.DictReader(open(R + "/benchmarks/results/video_benchmark.csv")))
base = {r["clip"]: r for r in vb if r["engine"] == "0.12.0"}; new = {r["clip"]: r for r in vb if r["engine"] == "round4" and r["walkable_channel"] == "segformer256"}
clips = sorted(set(base) & set(new))
sb = sum(int(base[c]["felt_stops"]) for c in clips); sn = sum(int(new[c]["felt_stops"]) for c in clips)
ob_b = sum(int(base[c]["felt_obstacles"]) for c in clips); ob_n = sum(int(new[c]["felt_obstacles"]) for c in clips)
worst = max(int(new[c]["felt_stops"]) - int(base[c]["felt_stops"]) for c in clips)
out["detection_filter_v2"] = (sn <= sb and ob_n <= ob_b and worst <= 1, f"{len(clips)} clips: felt stops {sb} -> {sn}, felt obstacle warnings {ob_b} -> {ob_n}, worst clip change {worst:+d}")
URBAN = {"walking", "wet_surfaces", "obstacles", "difficult_lighting", "low_contrast"}
URBAN_RUN = {c for c in clips if new[c]["category"] == "running" and c.startswith("sanpo_") and "66XHCmoc" not in c and "OLLkkVZ1" not in c}
mins = sum(float(new[c]["minutes"]) for c in clips); cues = {c: int(new[c].get("surface_cues") or 0) for c in clips}
urban = sum(v for c, v in cues.items() if new[c]["category"] in URBAN or c in URBAN_RUN)
fired = cues.get("ia_cc0_forest_path_a", 0) >= 1
out["terrain_surface_cue"] = (sum(cues.values()) / mins <= 1 and urban <= 1 and fired,
                              f"{sum(cues.values())} cues in {mins:.1f} min ({sum(cues.values()) / mins:.2f}/min), urban {urban}, forest_path_a {cues.get('ia_cc0_forest_path_a', 0)}")
lines = [f"{k}: {'ADOPT' if v[0] else 'NOT ADOPTED'} — {v[1]}" for k, v in out.items()]
open(R + "/benchmarks/results/integration_decision.txt", "w").write("\n".join(lines) + "\n"); print("\n".join(lines))
