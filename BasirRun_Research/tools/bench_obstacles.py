"""Obstacle / person detection on the 280 human-labelled SANPO chest frames, as the app runs it:
D-FINE-N ONNX (exported by tools/models/export_dfine.py, verified against PyTorch), letterbox 640 with
black padding, /255, sigmoid, score >= 0.4 (ObjectDetectorOnnx.MIN_SCORE).
Ground truth: connected components of SANPO obstacle classes (no instance ids in the labels, so
touching objects of one class merge) that reach into the corridor ahead (rows 35-100 %, central 40 %)
with >= 0.2 % of the image. Groups: people (pedestrian, rider), vehicle, animal, static obstacle
(pole, sign, light, bus stop, bike rack, barrier, generic obstacle).
A component is detected when one box covers >= 30 % of its pixels.
'Silent miss' = undetected AND the shipped walkable mask calls >= 50 % of its corridor pixels walkable
(nothing in the system marks it). Also writes walkable masks with detected boxes carved out, so the
end-to-end path test (eval_paths.py) includes the detector as the app does."""
import csv, os, time, collections, numpy as np, onnxruntime as ort
from PIL import Image
from scipy import ndimage
R = "/home/user/basir-run-/BasirRun_Research"
GROUPS = {"people": [12, 13], "vehicle": [21], "animal": [14], "static": [4, 20, 22, 23, 24, 25, 26]}
so = ort.InferenceSession(R + "/pretrained_models/dfine/dfine_n_coco.onnx", providers=["CPUExecutionProvider"])
def detect(img):
    w0, h0 = img.size; r = min(640 / w0, 640 / h0); nw, nh = round(w0 * r), round(h0 * r); L, T = (640 - nw) // 2, (640 - nh) // 2
    c = Image.new("RGB", (640, 640)); c.paste(img.resize((nw, nh), Image.BILINEAR), (L, T))
    x = (np.asarray(c, np.float32) / 255).transpose(2, 0, 1)[None]
    t0 = time.perf_counter(); lg, bx = so.run(None, {"pixel_values": x}); ms = (time.perf_counter() - t0) * 1000
    p = 1 / (1 + np.exp(-lg[0])); cls = p.argmax(1); sc = p.max(1); out = []
    for k in np.nonzero(sc >= 0.4)[0]:
        cx, cy, bw, bh = bx[0][k] * 640
        x1, y1, x2, y2 = [(v - o) / r for v, o in ((cx - bw / 2, L), (cy - bh / 2, T), (cx + bw / 2, L), (cy + bh / 2, T))]
        out.append((int(cls[k]), float(sc[k]), max(0, x1), max(0, y1), min(w0, x2), min(h0, y2)))
    return out, ms
frames = [l.strip() for l in open(R + "/benchmarks/sanpo_list.txt") if l.strip()]
cat = {r["session"]: r["category"] for r in csv.DictReader(open(R + "/datasets/sanpo/selected_sessions.csv"))}
st = collections.defaultdict(collections.Counter); fp = 0; nboxes = 0; times = []; per_cat = collections.defaultdict(collections.Counter)
for d in ("masks_underfoot_dfine", "masks_and_dfine"): os.makedirs(f"{R}/experiment_results/sanpo/{d}", exist_ok=True)
for f in frames:
    sess = f.split("/")[-2]; name = os.path.basename(f)[:-4]
    img = Image.open(f).convert("RGB"); gt = np.asarray(Image.open(f[:-4] + "_sem.png")); H, W = gt.shape
    walk = np.asarray(Image.open(f"{R}/experiment_results/sanpo/masks_ade_b0_256_underfoot/{sess}__{name}.png").resize((W, H), Image.NEAREST)) > 0
    andm = np.asarray(Image.open(f"{R}/experiment_results/sanpo/masks_seg_and_yolop/{sess}__{name}.png").resize((W, H), Image.NEAREST)) > 0
    boxes, ms = detect(img); times.append(ms)
    boxmask = np.zeros((H, W), bool)
    for _, _, x1, y1, x2, y2 in boxes: boxmask[int(y1):int(np.ceil(y2)), int(x1):int(np.ceil(x2))] = True
    for d, m in (("masks_underfoot_dfine", walk & ~boxmask), ("masks_and_dfine", andm & ~boxmask)):
        Image.fromarray(m.astype(np.uint8) * 255).save(f"{R}/experiment_results/sanpo/{d}/{sess}__{name}.png")
    corr = np.zeros((H, W), bool); corr[int(H * .35):, int(W * .3):int(W * .7)] = True
    anyobst = np.isin(gt, sum(GROUPS.values(), []))
    for g, ids in GROUPS.items():
        lab, n = ndimage.label(np.isin(gt, ids))
        for k in range(1, n + 1):
            comp = lab == k
            if comp.sum() < 0.002 * H * W or not (comp & corr).any(): continue
            hit = any(comp[int(y1):int(np.ceil(y2)), int(x1):int(np.ceil(x2))].sum() >= 0.3 * comp.sum() for _, _, x1, y1, x2, y2 in boxes)
            cc = comp & corr; silent = (not hit) and walk[cc].mean() >= 0.5; silent_and = (not hit) and andm[cc].mean() >= 0.5
            for key in (g, "all"):
                st[key]["gt"] += 1; st[key]["hit"] += hit; st[key]["silent_miss"] += silent; st[key]["silent_and"] += silent_and
            per_cat[cat[sess]]["gt"] += 1; per_cat[cat[sess]]["hit"] += hit
    for c, s, x1, y1, x2, y2 in boxes:
        bm = np.zeros((H, W), bool); bm[int(y1):int(np.ceil(y2)), int(x1):int(np.ceil(x2))] = True
        if not (bm & corr).any(): continue
        nboxes += 1
        if anyobst[bm].mean() < 0.1 and np.isin(gt[bm], [7, 8, 9, 28, 29, 27]).mean() < 0.5: fp += 1  # box on nothing that blocks
lines = [f"D-FINE-N on {len(frames)} SANPO frames, CPU onnxruntime median {np.median(times):.0f} ms/frame (desktop CPU, not a phone)",
         "group | GT objects in corridor | detected | recall | silent misses with shipped mask | silent misses with SegFormer AND YOLOP"]
for g in ["all"] + list(GROUPS):
    s = st[g]; lines.append(f"{g} | {s['gt']} | {s['hit']} | {s['hit']/max(1,s['gt']):.2f} | {s['silent_miss']} | {s['silent_and']}")
lines.append(f"boxes in corridor {nboxes}, of which on nothing that blocks (false positives) {fp} ({fp/max(1,nboxes):.2f})")
lines.append("recall by scene category: " + "; ".join(f"{c} {s['hit']}/{s['gt']}" for c, s in sorted(per_cat.items())))
open(R + "/benchmarks/results/obstacles_dfine_sanpo.txt", "w").write("\n".join(lines) + "\n"); print("\n".join(lines))
