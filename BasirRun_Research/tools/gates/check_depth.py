"""Runs the app's depth chain with the shipped RUNNING config (no overrides) and with the old values
(body 0.75 m, closer 0.8) on the 280 labelled SANPO frames; the shipped one must warn on more frames
with a fixed obstacle within 4 m in arm reach, in both halves of the sessions, with at most 2 more
false alarms on clear frames. Prints the measured numbers."""
import csv, hashlib, subprocess
R = "/home/user/basir-run-/BasirRun_Research"; S = "/tmp/claude-0/-home-user-basir-run-/db717bb8-cb66-5ecd-9762-13ebad485a32/scratchpad"; D = R + "/experiment_results/sanpo/depth/"
subprocess.run(["bash", "-c", f"""cd /home/user/basir-run- && SRC=$(grep -L "^import android\\|^import androidx\\|^import com.google\\|^import ai.onnx\\|^import java.util.concurrent\\|R\\.string" app/src/main/java/com/basir/run/vision/*.kt app/src/main/java/com/basir/run/vision/depth/*.kt) && {S}/kc/kotlinc/bin/kotlinc -nowarn -d {R}/tools/kt/depth.jar {R}/tools/kt/DepthHarness.kt engine/src/main/kotlin/run/basir/engine/*.kt $SRC"""], check=True, capture_output=True)
def run(args, out):
    subprocess.run(["java", "-cp", f"{R}/tools/kt/depth.jar:{S}/kc/kotlinc/lib/kotlin-stdlib.jar", "DepthHarnessKt", D + "jobs.csv", D + out] + args, check=True, capture_output=True)
    rows = list(csv.DictReader(open(D + out))); res = {}
    for half in (0, 1):
        rs = [x for x in rows if int(hashlib.md5(x["session"].encode()).hexdigest(), 16) % 2 == half]
        s4 = [x for x in rs if x["static_m"] and float(x["static_m"]) <= 4]
        cl = [x for x in rs if all((not x[c] or float(x[c]) > 6) for c in ("static_m", "dynamic_m", "wall_m"))]
        w = lambda x: x["level"] in ("Possible", "Hazard")
        res[half] = (sum(map(w, s4)), len(s4), sum(map(w, cl)), len(cl))
    return res
shipped = run([], "check_shipped.csv"); old = run(["0.75", "0.8", "8"], "check_old.csv")
print("old", old, "shipped", shipped)
for h in (0, 1):
    assert shipped[h][0] > old[h][0], f"half {h}: recall did not improve"
assert sum(shipped[h][2] for h in (0, 1)) <= sum(old[h][2] for h in (0, 1)) + 2, "false alarms"
print("DEPTH_OK")
