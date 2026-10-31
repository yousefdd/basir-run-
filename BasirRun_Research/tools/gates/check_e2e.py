"""Re-runs old vs new controller on the 5 real clips and checks: fewer command changes, no left/right flips, no more stop frames."""
import subprocess, re
R = "/home/user/basir-run-/BasirRun_Research"
subprocess.run(["python3", R + "/tools/guidance_bench.py"], capture_output=True, text=True, check=True)
txt = open(R + "/benchmarks/results/guidance_compare.txt").read()
def tot(name):
    l = next(l for l in txt.splitlines() if l.startswith(name + " | command changes"))
    n = [int(x) for x in re.findall(r"(?:command changes|left/right changes|opposite within 1 s|stop frames|felt) (\d+)", l)]
    return dict(changes=n[0], lr=n[1], fast=n[2], stops=n[3], felt=n[4])
old, new = tot("old (pure pursuit)"), tot("new ONE_EURO")
print("old", old, "new", new)
assert new["changes"] < old["changes"] and new["lr"] == 0 and new["fast"] == 0 and new["stops"] <= old["stops"] and new["felt"] < old["felt"]
print("E2E_OK")
