"""Angle audit: the independent 3-D test passes by name, MotionMonitor uses the tested formula, the
activity is portrait-only, and no angle check / AngleStop / recalibrate phrase is left in the app."""
import os, re, subprocess
B = "/home/user/basir-run-"
t = subprocess.run(["bash", B + "/BasirRun_Research/tools/gates/run_engine_tests.sh"], capture_output=True, text=True, env=dict(os.environ, SHOW="1")).stdout
for n in ("sensor angles and the ground projection agree for any tilt and roll", "something to the right on the ground is to the right in the image and in the path"):
    assert "PASS AngleConventionTest." + n in t, n
mm = open(B + "/app/src/main/java/com/basir/run/sensors/MotionMonitor.kt").read()
assert "GravityTilt.rollDeg" in mm and "GravityTilt.pitchBelowHorizonDeg" in mm
assert 'android:screenOrientation="portrait"' in open(B + "/app/src/main/AndroidManifest.xml").read()
src = "".join(open(os.path.join(d, f)).read() for d, _, fs in os.walk(B + "/app/src/main/java") for f in fs if f.endswith(".kt"))
for bad in ("AngleStop", "Check.Angle", "أعد المعايرة"):
    assert bad not in src, bad
print("ANGLES_OK")
