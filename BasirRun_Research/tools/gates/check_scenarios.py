"""Re-runs the 16-scenario before/after suite (0.10.0 engine vs current, same inputs) and checks the
owner's goals: fewer felt stops and stop frames, no opposite cues within 1 s, the bench still handled,
the right turn cued toward its side and never against it, and no scenario with more felt stops."""
import csv, subprocess
R = "/home/user/basir-run-/BasirRun_Research"
subprocess.run(["/tmp/tfl/bin/python", R + "/tools/scenario_suite.py"], check=True, capture_output=True)
rows = [r for r in csv.DictReader(open(R + "/benchmarks/results/scenario_suite.csv")) if r["version"] in ("0.10.0", "current")]
tot = {v: {k: sum(int(r[k]) for r in rows if r["version"] == v) for k in ("stop_vibrations", "stop_frames", "opposite_within_1s")} for v in ("0.10.0", "current")}
print(tot)
assert tot["current"]["stop_vibrations"] < tot["0.10.0"]["stop_vibrations"] and tot["current"]["stop_frames"] < tot["0.10.0"]["stop_frames"]
assert tot["current"]["opposite_within_1s"] == 0
by = {(r["scenario"], r["version"]): r for r in rows}
assert by[("11_static_obstacle_bench", "current")]["obstacle_handled"] == "True", "bench must still be handled"
cr = by[("03_curve_right", "current")]
assert int(cr["cues_toward_turn"]) >= 1 and int(cr["cues_against_turn"]) == 0, cr
worse = [s for (s, v) in by if v == "current" and int(by[(s, "current")]["stop_vibrations"]) > int(by[(s, "0.10.0")]["stop_vibrations"])]
assert not worse, worse
assert sum(1 for r in csv.DictReader(open(R + "/benchmarks/results/scenario_suite.csv")) if r["scenario"].startswith("05_athletics")) == 1
print("SCENARIOS_OK")
