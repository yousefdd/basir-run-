"""Owner request 2: the stop vibration must not keep going. Checks the named tests and, on the owner's
night video with the full chain, that every felt stop is >= 5 s from the previous one and that the
stop pattern vibrates <= 1.1 s (x2 with the longest pulse setting = 2.2 s)."""
import csv, os, subprocess, sys
R = "/home/user/basir-run-/BasirRun_Research"; S = "/tmp/claude-0/-home-user-basir-run-/db717bb8-cb66-5ecd-9762-13ebad485a32/scratchpad"
t = subprocess.run(["bash", R + "/tools/gates/run_engine_tests.sh"], capture_output=True, text=True, env=dict(os.environ, SHOW="1")).stdout
for name in ("a long stop vibrates once and twice more as a reminder, then stays quiet", "a lasting obstacle warning is felt three times at most, a new side at once"):
    assert ("PASS StableGuidanceTest." + name) in t, name
src = open("/home/user/basir-run-/engine/src/main/kotlin/run/basir/engine/Haptics.kt").read()
seg = src.split("STOP_SEGMENTS = listOf(")[1].split(")\n")[0]
on = sum(int(x.split("L")[0]) for x in [p.strip().lstrip("(") for p in seg.split(", ") if "to 255" in p])
assert on <= 1100, on
d = f"{R}/visual_reports/visual_test/20261030_225434_segformer256_ONE_EURO_cl0"
subprocess.run(["java", "-cp", f"{R}/tools/kt/visual.jar:{S}/kc/kotlinc/lib/kotlin-stdlib.jar", "VisualHarnessKt", d + "/jobs.csv", d + "/check2.csv", "1.4", "ONE_EURO", "0"], check=True, capture_output=True)
rows = list(csv.DictReader(open(d + "/check2.csv"))); os.remove(d + "/check2.csv")
st = [int(r["frame"]) / 15 for r in rows if r["felt"].startswith("stop")]
assert all(b - a >= 5 for a, b in zip(st, st[1:])), st
print(f"stop pattern vibrates {on} ms (x2 at the longest setting); felt stops at {st} s")
print("STOP_VIBRATION_OK")
