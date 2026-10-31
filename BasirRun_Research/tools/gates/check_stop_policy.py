"""Owner request 1: no stop except in extreme cases, guide the turn instead.
Checks (a) the engine tests for that rule pass by name, (b) the app rule (depth object + verified
detour = turn) passes, (c) on real clips with the detector in the loop, as the app runs: the curved
park path has no stop at all and the owner's night video stops once, at the bench (last 3 s)."""
import csv, os, subprocess
R = "/home/user/basir-run-/BasirRun_Research"; S = "/tmp/claude-0/-home-user-basir-run-/db717bb8-cb66-5ecd-9762-13ebad485a32/scratchpad"
t = subprocess.run(["bash", R + "/tools/gates/run_engine_tests.sh"], capture_output=True, text=True, env=dict(os.environ, SHOW="1")).stdout
for name in ("a blocked way turns the runner toward the open side and stops only in extreme cases",
             "a still object at or past the end of the walkable ground is no second warning, a moving one is",
             "a still object stays still when the runner walks toward it",
             "scene-sized boxes and things that are never on a footpath do not become obstacles"):
    assert f"PASS " in t and name in t and f"FAIL" not in t.split(name)[0][-60:], name
assert "ENGINE_TESTS_OK" in t
# app rule: compile + run the app JVM tests and require the named test not to fail
a = subprocess.run(["bash", "-c", f"""cd /home/user/basir-run- && SRC=$(grep -L "^import android\\|^import androidx\\|^import com.google\\|^import ai.onnx\\|^import java.util.concurrent\\|R\\.string" app/src/main/java/com/basir/run/engine/*.kt app/src/main/java/com/basir/run/vision/*.kt app/src/main/java/com/basir/run/vision/depth/*.kt); TT=app/src/test/java/com/basir/run; D=$(mktemp -d); {S}/kc/kotlinc/bin/kotlinc -nowarn -Xallow-kotlin-package -cp /tmp/json.jar engine/src/main/kotlin $SRC $TT/engine/*.kt $TT/vision/depth/*.kt $TT/vision/*.kt {S}/kt/JunitStub.kt /tmp/apptest/Run.kt /tmp/apptest/Assume.kt -d $D/a.jar >/dev/null 2>&1; CLS=$(cd app/src/test/java && ls com/basir/run/engine/*Test.kt com/basir/run/vision/*Test.kt com/basir/run/vision/depth/*Test.kt | sed 's#/#.#g; s#\\.kt$##'); cd app && java -cp $D/a.jar:{S}/kc/kotlinc/lib/kotlin-stdlib.jar:/tmp/json.jar RunKt $CLS 2>&1"""], capture_output=True, text=True).stdout
assert "ran: 1" in a and "aCloseGroundObjectWithAVerifiedWayAroundIsATurnNotAStop" not in a, a[-400:]
fails = [l for l in a.splitlines() if l.startswith("FAIL") and "AssumptionViolated" not in l]
assert not fails, fails
def run(dirname, cl):
    d = f"{R}/visual_reports/visual_test/{dirname}"
    subprocess.run(["java", "-cp", f"{R}/tools/kt/visual.jar:{S}/kc/kotlinc/lib/kotlin-stdlib.jar", "VisualHarnessKt", d + "/jobs.csv", d + "/check.csv", "1.4", "ONE_EURO", cl], check=True, capture_output=True)
    rows = list(csv.DictReader(open(d + "/check.csv"))); os.remove(d + "/check.csv"); return rows
curve = run("sanpo_curve4_L_pP-G4Xoz_segformer256_ONE_EURO_cl1", "1")
assert not any(r["cmd"] == "STOP" for r in curve), "stop on the curved park path"
night = run("20261030_225434_segformer256_ONE_EURO_cl0", "0")
stops = [int(r["frame"]) for r in night if r["felt"].startswith("stop")]
assert len(stops) == 1 and stops[0] >= len(night) - 45, stops
print(f"curved path: 0 stop frames of {len(curve)}; night video: felt stop(s) at frame {stops} of {len(night)} (bench)")
print("STOP_POLICY_OK")
