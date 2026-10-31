"""Clip stability of the engine chain, before (engine at HEAD: raw free route) and after (free route
smoothed by the path tracker), on ADE20K-B0 masks with the 'surface under the runner' rule.
Metrics: share of frames with a path, frame-to-frame change of the path bearing 3 m ahead, and the
number of left/right flips of the guidance command."""
import glob, os, subprocess, sys, tempfile, csv, numpy as np, onnxruntime as ort
from PIL import Image
sys.path.insert(0, os.path.dirname(__file__)); from underfoot import underfoot_mask
R = "/home/user/basir-run-/BasirRun_Research"; S = "/tmp/claude-0/-home-user-basir-run-/db717bb8-cb66-5ecd-9762-13ebad485a32/scratchpad"
s = ort.InferenceSession(R + "/pretrained_models/segformer_b0_ade_256.onnx", providers=["CPUExecutionProvider"])
MEAN = np.array([0.485, 0.456, 0.406]); STD = np.array([0.229, 0.224, 0.225])
def stats(path):
    rows = list(csv.DictReader(open(path)))
    b = [float(r["path_bearing3"]) if r["path_bearing3"] else None for r in rows]
    have = sum(x is not None for x in b) / len(b)
    d = [abs(y - x) for x, y in zip(b, b[1:]) if x is not None and y is not None]
    sides = [r["side"] for r in rows if r["cmd"] in ("CORRECT", "CURVE")]
    flips = sum(1 for x, y in zip(sides, sides[1:]) if x != y)
    stops = sum(r["cmd"] == "STOP" for r in rows)
    return have, (np.median(d) if d else float("nan")), (np.percentile(d, 90) if d else float("nan")), flips, stops
for video in sys.argv[1:]:
    t = tempfile.mkdtemp(); os.makedirs(t + "/f"); os.makedirs(t + "/m")
    subprocess.run(["ffmpeg", "-loglevel", "error", "-i", video, "-vf", "scale=960:540", t + "/f/%05d.jpg"], check=True)
    fs = sorted(glob.glob(t + "/f/*.jpg"))
    for f in fs:
        x = ((np.asarray(Image.open(f).resize((256, 256)), dtype=np.float32) / 255 - MEAN) / STD).transpose(2, 0, 1)[None].astype(np.float32)
        lab = s.run(None, {"pixel_values": x})[0][0].argmax(0)
        Image.fromarray((underfoot_mask(lab) * 255).astype(np.uint8)).resize((960, 540), Image.NEAREST).save(t + "/m/" + os.path.basename(f)[:-4] + ".png")
    jobs = t + "/jobs.csv"; open(jobs, "w").write("".join(f"{t}/m/{os.path.basename(f)[:-4]}.png,58.4,1.3,10\n" for f in fs))
    res = {}
    for tag, jar in [("before", "seq_head.jar"), ("after", "seq.jar")]:
        subprocess.run(["java", "-cp", f"{R}/tools/kt/{jar}:{S}/kc/kotlinc/lib/kotlin-stdlib.jar", "SequenceHarnessKt", jobs, f"{t}/{tag}.csv"], check=True, capture_output=True)
        res[tag] = stats(f"{t}/{tag}.csv")
        os.makedirs(R + "/experiment_results/sequences", exist_ok=True)
        subprocess.run(["cp", f"{t}/{tag}.csv", f"{R}/experiment_results/sequences/{os.path.basename(video)[:-4]}_{tag}.csv"])
    for tag, (have, med, p90, flips, stops) in res.items():
        print(f"{os.path.basename(video)[:-4]:28s} {tag:6s} path {have:4.0%} | bearing change median {med:4.1f} deg p90 {p90:4.1f} | left/right flips {flips:2d} | stop frames {stops}")
