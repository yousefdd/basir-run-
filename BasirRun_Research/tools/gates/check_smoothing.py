"""Re-runs the filter comparison and checks all filters were scored on all three inputs; One Euro (the default) within 0.02 of the best."""
import subprocess, re
R = "/home/user/basir-run-/BasirRun_Research"
out = subprocess.run(["python3", R + "/tools/smoothing_grid.py"], capture_output=True, text=True, check=True).stdout
rows = [l.split(" | ") for l in out.splitlines() if l.count(" | ") == 6 and not l.startswith("signal")]
got = {(r[0], r[1]): float(r[3]) for r in rows}
for sig in ("offset", "heading", "curv"):
    for f in ("NONE", "EMA", "ONE_EURO", "KALMAN", "MEDIAN"):
        assert (sig, f) in got, (sig, f)
    best = min(v for (s, f), v in got.items() if s == sig and f != "NONE")
    assert got[(sig, "ONE_EURO")] <= best + 0.02, f"{sig}: One Euro {got[(sig, 'ONE_EURO')]} vs best {best}"
    assert got[(sig, "ONE_EURO")] < got[(sig, "NONE")], "filter must beat no filter"
print("SMOOTHING_OK")
