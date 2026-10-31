"""Runs the current engine and the new chain (ADE20K-B0 walkable mask -> engine FreeSpacePlanner) on every
frame of a clip and writes (1) an overlay video, (2) per-frame results, (3) stability metrics:
path availability, and the frame-to-frame change of the steering bearing (jitter).
Camera geometry for SANPO chest clips: 1.3 m, 10 deg tilt, 58.4 deg HFOV (SANPO medians)."""
import csv, glob, os, subprocess, sys, tempfile, numpy as np, onnxruntime as ort
from PIL import Image, ImageDraw
R = "/home/user/basir-run-/BasirRun_Research"; S = "/tmp/claude-0/-home-user-basir-run-/db717bb8-cb66-5ecd-9762-13ebad485a32/scratchpad"
video, label = sys.argv[1], sys.argv[2]
H_M, PITCH, HFOV = sys.argv[3] if len(sys.argv) > 3 else "1.3", sys.argv[4] if len(sys.argv) > 4 else "10", "58.4"
work = tempfile.mkdtemp(); fr = work + "/f"; os.makedirs(fr)
subprocess.run(["ffmpeg", "-loglevel", "error", "-i", video, "-vf", "scale=960:540", f"{fr}/%05d.jpg"], check=True)
frames = sorted(glob.glob(fr + "/*.jpg"))
# 1. walkable masks
o = ort.SessionOptions(); o.intra_op_num_threads = 4
s = ort.InferenceSession(R + "/pretrained_models/segformer_b0_ade_256.onnx", o, providers=["CPUExecutionProvider"])
MEAN = np.array([0.485, 0.456, 0.406]); STD = np.array([0.229, 0.224, 0.225]); WALK = [3, 9, 11, 13, 29, 46, 52, 91]
md = work + "/m"; os.makedirs(md)
for f in frames:
    x = ((np.asarray(Image.open(f).resize((256, 256)), dtype=np.float32) / 255 - MEAN) / STD).transpose(2, 0, 1)[None].astype(np.float32)
    lab = s.run(None, {"pixel_values": x})[0][0].argmax(0)
    Image.fromarray((np.isin(lab, WALK) * 255).astype(np.uint8)).resize((960, 540), Image.NEAREST).save(f"{md}/{os.path.basename(f)[:-4]}.png")
# 2. planner on masks, current engine on frames
jobs = work + "/jobs.csv"
open(jobs, "w").write("".join(f"{md}/{os.path.basename(f)[:-4]}.png,{HFOV},{H_M},{PITCH}\n" for f in frames))
cp = f"{R}/tools/kt/planner.jar:{S}/kc/kotlinc/lib/kotlin-stdlib.jar"
subprocess.run(["java", "-cp", cp, "PlannerHarnessKt", jobs, work + "/routes.csv"], check=True, capture_output=True)
lst = work + "/list.txt"; open(lst, "w").write("\n".join(frames))
subprocess.run(["java", "-cp", f"{R}/tools/kt/baseline.jar:{S}/kc/kotlinc/lib/kotlin-stdlib.jar", "BaselineHarnessKt", lst, work + "/cur"], check=True, capture_output=True)
routes = {os.path.basename(r["mask"])[:-4]: r for r in csv.DictReader(open(work + "/routes.csv"))}
# 3. overlay + metrics
ov = work + "/o"; os.makedirs(ov); new_steer, cur_off = [], []
for f in frames:
    k = os.path.basename(f)[:-4]
    im = Image.open(f).convert("RGB"); m = np.asarray(Image.open(f"{md}/{k}.png")) > 0
    a = np.asarray(im).astype(np.float32); a[m] = a[m] * 0.6 + np.array([40, 200, 120]) * 0.4
    im = Image.fromarray(a.astype(np.uint8)); d = ImageDraw.Draw(im)
    cur = np.asarray(Image.open(f"{work}/cur/f__{k}.png")) > 0
    pts = []
    for v in np.linspace(0.6, 0.98, 12):
        xs = np.nonzero(cur[int(v * 540)])[0]
        if len(xs): pts.append(((xs.min() + xs.max()) / 2, v * 540))
    if len(pts) > 1: d.line(pts, fill=(255, 80, 80), width=5)
    cur_off.append((pts[-1][0] / 960 - 0.5) if pts else None)
    r = routes.get(k)
    if r and r["status"] == "ROUTE":
        rp = [(float(p.split(":")[0]) * 960, float(p.split(":")[1]) * 540) for p in r["points"].split()]
        if len(rp) > 1: d.line(rp, fill=(255, 230, 40), width=7)
        new_steer.append(float(r["steer_deg"]))
    else:
        new_steer.append(None); d.text((20, 20), "STOP: no verified path", fill=(255, 60, 60))
    d.text((20, 500), "yellow: new virtual path   red: current engine centre   green: walkable ground", fill=(255, 255, 255))
    im.save(f"{ov}/{k}.jpg", quality=88)
os.makedirs(R + "/visual_reports", exist_ok=True)
dst = f"{R}/visual_reports/{label}_overlay.mp4"
subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-framerate", "15", "-i", f"{ov}/%05d.jpg", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "24", dst], check=True)
Image.open(sorted(glob.glob(ov + "/*.jpg"))[len(frames) // 2]).save(f"{R}/visual_reports/{label}_mid.jpg")
def jitter(xs, scale=1.0):
    d = [abs(b - a) * scale for a, b in zip(xs, xs[1:]) if a is not None and b is not None]
    return (np.median(d), np.percentile(d, 90)) if d else (float("nan"), float("nan"))
nj = jitter(new_steer); cj = jitter(cur_off, scale=60.0)  # image offset -> degrees across the 58 deg view (approx.)
print(f"{label}: frames {len(frames)} | new path available {sum(x is not None for x in new_steer)/len(frames):.0%}, steer jitter median {nj[0]:.1f} deg p90 {nj[1]:.1f} | current engine path {sum(x is not None for x in cur_off)/len(frames):.0%}, jitter median {cj[0]:.1f} deg p90 {cj[1]:.1f}")
