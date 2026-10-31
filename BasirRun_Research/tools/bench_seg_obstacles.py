"""Fixed obstacles from the segmentation the app already runs (SegFormer-B0 ADE20K at 256): blobs of
obstacle classes (pole, signboard, streetlight, ...) placed on the ground by their base and tested
against the same corridor ground truth as the depth benchmark (arm reach 0.6 m, <= 4 m).
Session halves are scored separately (choice must hold on both)."""
import csv, glob, hashlib, math, os, sys, numpy as np, onnxruntime as ort
from PIL import Image
from scipy import ndimage
R = "/home/user/basir-run-/BasirRun_Research"; D = R + "/experiment_results/sanpo/depth"
os.makedirs(R + "/experiment_results/sanpo/ade_labels", exist_ok=True)
so = ort.InferenceSession(R + "/pretrained_models/segformer_b0_ade_256.onnx", providers=["CPUExecutionProvider"])
MEAN = np.array([0.485, 0.456, 0.406]); STD = np.array([0.229, 0.224, 0.225])
SETS = {"street_furniture": [93, 43, 87, 136, 42, 69, 125, 138, 41, 19, 110, 104],
        "+fence_railing": [93, 43, 87, 136, 42, 69, 125, 138, 41, 19, 110, 104, 32, 38],
        "+tree": [93, 43, 87, 136, 42, 69, 125, 138, 41, 19, 110, 104, 4]}
jobs = list(csv.DictReader(open(D + "/jobs.csv")))
paths = {(p.split("/")[-2], os.path.basename(p)[:-4]): p for p in open(R + "/benchmarks/sanpo_list.txt").read().split()}
labels = {}
for j in jobs:
    key = (j["session"], j["frame"]); cache = f"{R}/experiment_results/sanpo/ade_labels/{key[0]}__{key[1]}.npy"
    if not os.path.exists(cache):
        img = Image.open(paths[key]).convert("RGB")
        x = ((np.asarray(img.resize((256, 256)), np.float32) / 255 - MEAN) / STD).transpose(2, 0, 1)[None].astype(np.float32)
        np.save(cache, so.run(None, {"pixel_values": x})[0][0].argmax(0).astype(np.uint8))
    labels[key] = np.load(cache)
def blobs_in_corridor(lab, j, ids, min_px, max_m):
    hf, height, pitch = float(j["hfov"]), float(j["height"]), float(j["pitch"])
    H, W = lab.shape  # SegFormer outputs a quarter of its input: 64 x 64 for 256 (the frame squashed from 16:9)
    fxl = (W / 2) / math.tan(math.radians(hf) / 2)                 # horizontal focal in label columns
    fy = fxl * (H / W) * (16 / 9)                                   # vertical focal in label rows (16:9 frame)
    cy = (H - 1) / 2; cx = (W - 1) / 2; hits = []
    comp, n = ndimage.label(np.isin(lab, ids))
    for k in range(1, n + 1):
        ys, xs = np.nonzero(comp == k)
        if len(ys) < min_px: continue
        foot = ys.max(); u = xs[ys >= foot - 2].mean()
        a = math.radians(pitch) + math.atan((foot - cy) / fy)
        if a <= 0.02: continue
        fwd = height / math.tan(a); slant = height / math.sin(a)
        lat = (u - cx) / fxl * slant
        # widest part of the blob near its base also counts (a bench across the way)
        x0, x1 = xs[ys >= foot - 4].min(), xs[ys >= foot - 4].max()
        l0, l1 = (x0 - cx) / fxl * slant, (x1 - cx) / fxl * slant
        if fwd <= max_m and (l0 <= 0.6 and l1 >= -0.6): hits.append(fwd)
    return hits
f = lambda x: float(x) if x else None
lines = ["class set | min px | half | static <= 4 m recall | clear-frame false alarms"]
best = None
for name, ids in SETS.items():
    for min_px in (2, 4, 8):
        res = []
        for half in (0, 1):
            js = [j for j in jobs if int(hashlib.md5(j["session"].encode()).hexdigest(), 16) % 2 == half]
            s4 = [j for j in js if f(j["static_m"]) is not None and f(j["static_m"]) <= 4]
            cl = [j for j in js if all(f(j[c]) is None or f(j[c]) > 6 for c in ("static_m", "dynamic_m", "wall_m"))]
            rec = sum(bool(blobs_in_corridor(labels[(j["session"], j["frame"])], j, ids, min_px, 4.5)) for j in s4)
            fa = sum(bool(blobs_in_corridor(labels[(j["session"], j["frame"])], j, ids, min_px, 4.0)) for j in cl)
            res.append((rec, len(s4), fa, len(cl)))
            lines.append(f"{name} | {min_px} | {half} | {rec}/{len(s4)} | {fa}/{len(cl)}")
open(R + "/benchmarks/results/seg_obstacles_sweep.txt", "w").write("\n".join(lines) + "\n"); print("\n".join(lines))
