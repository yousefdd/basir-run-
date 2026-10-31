"""Regenerates the vibration table from the engine and checks the committed docs/haptics_table.md is
identical, lists every meaning the brief asks for, and has no 'confirm' lesson left in the training screen."""
import subprocess, tempfile, os
S = "/tmp/claude-0/-home-user-basir-run-/db717bb8-cb66-5ecd-9762-13ebad485a32/scratchpad"; B = "/home/user/basir-run-"
t = tempfile.mkdtemp()
subprocess.run(f"{S}/kc/kotlinc/bin/kotlinc -nowarn -d {t}/ht.jar {B}/BasirRun_Research/tools/kt/HapticTable.kt {B}/engine/src/main/kotlin/run/basir/engine/*.kt", shell=True, check=True, capture_output=True)
subprocess.run(["java", "-cp", f"{t}/ht.jar:{S}/kc/kotlinc/lib/kotlin-stdlib.jar", "HapticTableKt", f"{t}/table.md"], check=True, capture_output=True)
new, old = open(f"{t}/table.md").read(), open(f"{B}/docs/haptics_table.md").read()
assert new == old, "docs/haptics_table.md is out of date"
for name in ("correct_left_low", "correct_left_medium", "correct_left_high", "correct_right_low", "correct_right_medium", "correct_right_high",
             "curve_", "obstacle", "stop", "caution", "back_on_path"):
    assert f"`{name}" in new, name
assert "نبضة التأكيد" not in open(f"{B}/app/src/main/java/com/basir/run/HapticTrainingScreen.kt").read()
print("HAPTIC_TABLE_OK")
