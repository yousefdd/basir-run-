"""Checks the saved simulation covers 8 algorithms x 6 scenarios x 2 speeds with numbers."""
R = "/home/user/basir-run-/BasirRun_Research"
lines = open(R + "/benchmarks/results/planner_sim.txt").read().splitlines()
cells = [l.split(" | ") for l in lines if l.count(" | ") == 8 and not l.startswith("scenario")]
algos = {c[2] for c in cells}; scen = {c[0] for c in cells}; speeds = {c[1] for c in cells}
assert len(algos) == 8 and len(scen) == 6 and speeds == {"1.4", "2.8"}, (len(algos), len(scen), speeds)
assert len(cells) == 96
for c in cells: float(c[3]); int(c[5].split("/")[0])
assert "SIMULATION" in lines[0]
print("PLANNERS_OK")
