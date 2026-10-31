"""Re-runs the D-FINE-N measurement on the labelled SANPO frames and checks the report has recall per group."""
import subprocess, re
R = "/home/user/basir-run-/BasirRun_Research"
out = subprocess.run(["/tmp/tfl/bin/python", R + "/tools/bench_obstacles.py"], capture_output=True, text=True, check=True).stdout
g = {l.split(" | ")[0]: l.split(" | ") for l in out.splitlines() if l.count(" | ") == 5}
for k in ("all", "people", "vehicle", "static"):
    assert k in g and int(g[k][1]) > 0, k
    float(g[k][3])
print(out.splitlines()[0]); print("people recall", g["people"][3], "static recall", g["static"][3], "silent misses", g["all"][4])
print("OBSTACLES_OK")
