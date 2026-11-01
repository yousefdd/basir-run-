"""G10: re-applies the pre-registered criteria (tools/make_decision.py, run here) and checks the code matches each
decision: the rough-surface cue is in the app only if ADOPT; DetectionFilter v2 is in the engine only if ADOPT;
no off-road model is shipped as an app asset (walkable fusion / obstacle class not adopted -> must be absent)."""
import os, subprocess, sys, glob
B = "/home/user/basir-run-"; R = B + "/BasirRun_Research"
txt = subprocess.run(["python3", R + "/tools/make_decision.py"], capture_output=True, text=True, check=True).stdout
dec = {l.split(":")[0]: "NOT ADOPTED" not in l.split("—")[0] for l in txt.strip().splitlines()}
app = open(B + "/app/src/main/java/com/basir/run/engine/DecisionEngine.kt").read()
eng = open(B + "/engine/src/main/kotlin/run/basir/engine/Obstacles.kt").read()
if dec["terrain_surface_cue"] != ("Cue.RoughSurface" in app): sys.exit(f"surface cue decision {dec['terrain_surface_cue']} but code has it: {'Cue.RoughSurface' in app}")
if dec["detection_filter_v2"] != ("WILD_ANIMALS" in eng): sys.exit("detection filter decision does not match the engine")
shipped = glob.glob(B + "/app/src/main/assets/*offroad*")
if (dec["walkable_fusion"] or dec["offroad_obstacle_class"]) != bool(shipped): sys.exit("off-road model asset does not match the decision")
print(txt.strip()); print("DECISION_OK")
