"""Depth Anything V2 Small (int8 ONNX, as the app downloads it) on the 280 SANPO frames, preprocessed as
DepthEstimator does (stretch to 252x252, ImageNet mean/std, bilinear resample to 160 x h), saved as
float32 for the app's own FloorPlanner/DepthHazards (run by tools/kt/DepthHarness.kt).
Ground truth from the human labels: an obstacle component whose foot (lowest pixel per column) lies
within the body-wide corridor (|lateral| <= 0.6 m) at forward <= 4.0 m (warn) / <= 2.5 m (stop), using
each frame's reference camera geometry (frame_geometry.csv)."""
import csv, math, os, numpy as np, onnxruntime as ort
from PIL import Image
from scipy import ndimage
R = "/home/user/basir-run-/BasirRun_Research"; OUT = R + "/experiment_results/sanpo/depth"
LAT = float(os.environ.get("LAT", "0.6"))  # half-width of the corridor used for ground truth
GEO = {(r["session"], r["frame"]): r for r in csv.DictReader(open(R + "/datasets/sanpo/frame_geometry.csv"))}
STATIC = [4, 20, 22, 23, 24, 25, 26]; DYN = [12, 13, 14, 21]; WALLS = [7, 8, 28]
so = ort.InferenceSession(R + "/pretrained_models/depth/da2s_q.onnx", providers=["CPUExecutionProvider"])
MEAN = np.array([0.485, 0.456, 0.406]); STD = np.array([0.229, 0.224, 0.225])
frames = [l.strip() for l in open(R + "/benchmarks/sanpo_list.txt") if l.strip()]
rows = []
for f in frames:
    sess, fr = f.split("/")[-2], os.path.basename(f)[:-4]
    g = GEO.get((sess, fr))
    if not g: continue
    img = Image.open(f).convert("RGB"); W0, H0 = img.size
    x = ((np.asarray(img.resize((252, 252), Image.BILINEAR), np.float32) / 255 - MEAN) / STD).transpose(2, 0, 1)[None].astype(np.float32)
    d = so.run(None, {"pixel_values": x})[0][0]
    w, h = 160, round(160 * H0 / W0)
    disp = np.asarray(Image.fromarray(d.astype(np.float32), mode="F").resize((w, h), Image.BILINEAR), np.float32)
    path = f"{OUT}/{sess}__{fr}.f32"; disp.astype("<f4").tofile(path)
    gt = np.asarray(Image.open(f[:-4] + "_sem.png"))
    hf, height, pitch = float(g["hfov_deg"]), float(g["height_m"]), float(g["pitch_deg"])
    fpx = (W0 / 2) / math.tan(math.radians(hf) / 2); cy = (H0 - 1) / 2; cx = (W0 - 1) / 2
    def ground(u, v):
        a = math.radians(pitch) + math.atan((v - cy) / fpx)
        if a <= 0.01: return None
        rng = height / math.tan(a); slant = height / math.sin(a)
        return rng, (u - cx) / fpx * slant * math.cos(math.atan((v - cy) / fpx))
    res = {}
    for name, ids in (("static", STATIC), ("dynamic", DYN), ("wall", WALLS)):
        lab, n = ndimage.label(np.isin(gt, ids)); near = None
        for k in range(1, n + 1):
            ys, xs = np.nonzero(lab == k)
            if len(ys) < 0.001 * W0 * H0: continue
            foot = {}
            for yy, xx in zip(ys, xs): foot[xx] = max(foot.get(xx, 0), yy)
            for xx, yy in list(foot.items())[::3]:
                p = ground(xx, yy)
                if p and abs(p[1]) <= LAT: near = p[0] if near is None else min(near, p[0])
        res[name] = near
    rows.append(dict(bin=path, w=w, h=h, hfov=hf, height=height, pitch=pitch, session=sess, frame=fr,
                     static_m=res["static"] or "", dynamic_m=res["dynamic"] or "", wall_m=res["wall"] or ""))
with open(OUT + ("/jobs.csv" if LAT == 0.6 else f"/jobs_lat{LAT}.csv"), "w", newline="") as fh:
    wr = csv.DictWriter(fh, list(rows[0].keys())); wr.writeheader(); wr.writerows(rows)
print(len(rows), "frames;", sum(1 for r in rows if r["static_m"] != "" and float(r["static_m"]) <= 4), "with a static obstacle in the corridor within 4 m")
