"""What the runner would feel on a clip: engine chain -> guidance command -> the app's haptic scheduler.
Prints the felt vibrations second by second and counts changes. Masks are cached per clip.
Usage: felt_bench.py <video> <W> <H> <hfov> <height> <pitch> <jar> [<jar> ...]"""
import csv, glob, os, subprocess, sys, collections, numpy as np, onnxruntime as ort
from PIL import Image
sys.path.insert(0, os.path.dirname(__file__)); from underfoot import underfoot_mask
R = "/home/user/basir-run-/BasirRun_Research"; S = "/tmp/claude-0/-home-user-basir-run-/db717bb8-cb66-5ecd-9762-13ebad485a32/scratchpad"
video, W, H, hfov, height, pitch = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), sys.argv[4], sys.argv[5], sys.argv[6]
jars = sys.argv[7:]
cache = f"{R}/experiment_results/masks_cache/{os.path.basename(video)[:-4]}"
if not os.path.isdir(cache + "/m"):
    os.makedirs(cache + "/f"); os.makedirs(cache + "/m")
    subprocess.run(["ffmpeg", "-loglevel", "error", "-i", video, "-vf", f"fps=15,scale={W}:{H}", cache + "/f/%05d.jpg"], check=True)
    sess = ort.InferenceSession(R + "/pretrained_models/segformer_b0_ade_256.onnx", providers=["CPUExecutionProvider"])
    MEAN = np.array([0.485, 0.456, 0.406]); STD = np.array([0.229, 0.224, 0.225])
    for f in sorted(glob.glob(cache + "/f/*.jpg")):
        x = ((np.asarray(Image.open(f).resize((256, 256)), dtype=np.float32) / 255 - MEAN) / STD).transpose(2, 0, 1)[None].astype(np.float32)
        lab = sess.run(None, {"pixel_values": x})[0][0].argmax(0)
        Image.fromarray((underfoot_mask(lab) * 255).astype(np.uint8)).resize((W, H), Image.NEAREST).save(cache + "/m/" + os.path.basename(f)[:-4] + ".png")
ms = sorted(glob.glob(cache + "/m/*.png"))
jobs = cache + "/jobs.csv"; open(jobs, "w").write("".join(f"{m},{hfov},{height},{pitch}\n" for m in ms))
for jar in jars:
    out = cache + f"/seq_{os.path.basename(jar)[:-4]}.csv"
    subprocess.run(["java", "-cp", f"{jar}:{S}/kc/kotlinc/lib/kotlin-stdlib.jar", "SequenceHarnessKt", jobs, out], check=True, capture_output=True)
    rows = list(csv.DictReader(open(out)))
    cmds = [r["cmd"] + ("-" + r["side"] if r["side"] else "") for r in rows]
    changes = sum(1 for a, b in zip(cmds, cmds[1:]) if a != b)
    felt = [(int(r["frame"]) / 15, r["felt"]) for r in rows if r["felt"]]
    kinds = [f.split("_")[0] for _, f in felt]
    felt_changes = sum(1 for a, b in zip(kinds, kinds[1:]) if a != b)
    stop_go = sum(1 for a, b in zip(cmds, cmds[1:]) if a == "STOP" and b != "STOP")
    print(f"== {os.path.basename(jar)}: {len(rows)} frames ({len(rows)/15:.1f} s) | command changes {changes} | stop->go {stop_go} | vibrations felt {len(felt)} | changes between felt kinds {felt_changes}")
    line = []
    for sec in range(int(len(rows) / 15) + 1):
        f = [n for t, n in felt if sec <= t < sec + 1]
        line.append(f"{sec}s:" + ("+".join(f) if f else "-"))
    print("   " + "  ".join(line))
