"""Re-computes the round-2 metrics and checks each requested family is present with numbers."""
import subprocess, re
R = "/home/user/basir-run-/BasirRun_Research"
out = subprocess.run(["/tmp/tfl/bin/python", R + "/tools/metrics_round2.py"], capture_output=True, text=True, check=True).stdout
assert re.search(r"segformer_underfoot \(shipped\) \| 0\.\d+ \| 0\.\d+ \| 0\.\d+ \| 0\.\d+", out), "Dice/F1"
assert re.search(r"segformer_underfoot \(shipped\) \| \d+ \| [\d.]+ \(n=\d+\) \| [\d.]+ \(n=\d+\)", out), "centreline error"
assert len(re.findall(r"\| \d+% \| \d+% \|", out)) >= 8, "continuity / tracking failure per clip"
for k in ("ms_walkable", "ms_detector", "ms_plan"): assert re.search(k + r": [\d.]+", out), k
assert len(re.findall(r"resident memory growth after load \+ one run \d+ MB", out)) == 4, "memory"
assert "not a fair test" in out
print("METRICS_OK")
