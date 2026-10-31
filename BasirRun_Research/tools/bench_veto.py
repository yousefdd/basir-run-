"""'Small-blob veto': SegFormer underfoot mask minus the small connected regions where YOLOP-320 says
not-drivable (objects standing on the way), keeping large disagreements (a whole sidewalk YOLOP was
never trained to call drivable). D-FINE boxes carved as in the app. Writes masks for eval_paths.py and
counts silent misses of static obstacles (same definition as bench_obstacles.py)."""
import csv, os, sys, numpy as np, onnxruntime as ort
from PIL import Image
from scipy import ndimage
R = "/home/user/basir-run-/BasirRun_Research"
frames = [l.strip() for l in open(R + "/benchmarks/sanpo_list.txt") if l.strip()]
STATIC = [4, 20, 22, 23, 24, 25, 26]; ALLOB = STATIC + [12, 13, 14, 21]
so = ort.InferenceSession(R + "/pretrained_models/dfine/dfine_n_coco.onnx", providers=["CPUExecutionProvider"])
sys.argv += []
def boxes_of(img):
    w0, h0 = img.size; r = min(640 / w0, 640 / h0); nw, nh = round(w0 * r), round(h0 * r); L, T = (640 - nw) // 2, (640 - nh) // 2
    c = Image.new("RGB", (640, 640)); c.paste(img.resize((nw, nh), Image.BILINEAR), (L, T))
    lg, bx = so.run(None, {"pixel_values": (np.asarray(c, np.float32) / 255).transpose(2, 0, 1)[None]})
    p = 1 / (1 + np.exp(-lg[0])); out = []
    for k in np.nonzero(p.max(1) >= 0.4)[0]:
        cx, cy, bw, bh = bx[0][k] * 640; out.append(((cx - bw / 2 - L) / r, (cy - bh / 2 - T) / r, (cx + bw / 2 - L) / r, (cy + bh / 2 - T) / r))
    return out
TH = [0.005, 0.01, 0.02, 0.04]
for t in TH: os.makedirs(f"{R}/experiment_results/sanpo/masks_veto_{t}", exist_ok=True)
silent = {t: 0 for t in TH}; carved = {t: [] for t in TH}
for f in frames:
    sess = f.split("/")[-2]; name = os.path.basename(f)[:-4]
    gt = np.asarray(Image.open(f[:-4] + "_sem.png")); H, W = gt.shape
    seg = np.asarray(Image.open(f"{R}/experiment_results/sanpo/masks_ade_b0_256_underfoot/{sess}__{name}.png").resize((W, H), Image.NEAREST)) > 0
    yp = np.asarray(Image.open(f"{R}/experiment_results/sanpo/masks_yolop320/{sess}__{name}.png").resize((W, H), Image.NEAREST)) > 0
    bm = np.zeros((H, W), bool)
    for x1, y1, x2, y2 in boxes_of(Image.open(f).convert("RGB")): bm[max(0, int(y1)):int(np.ceil(y2)), max(0, int(x1)):int(np.ceil(x2))] = True
    lab, n = ndimage.label(seg & ~yp); areas = ndimage.sum(np.ones_like(lab), lab, range(1, n + 1))
    corr = np.zeros((H, W), bool); corr[int(H * .35):, int(W * .3):int(W * .7)] = True
    for t in TH:
        small = np.isin(lab, [i + 1 for i, a in enumerate(areas) if a < t * H * W])
        m = seg & ~small & ~bm; carved[t].append(small.mean())
        Image.fromarray(m.astype(np.uint8) * 255).save(f"{R}/experiment_results/sanpo/masks_veto_{t}/{sess}__{name}.png")
        ol, on = ndimage.label(np.isin(gt, STATIC))
        for k in range(1, on + 1):
            comp = ol == k
            if comp.sum() < 0.002 * H * W or not (comp & corr).any(): continue
            if bm[comp].mean() >= 0.3: continue
            if m[comp & corr].mean() >= 0.5: silent[t] += 1
for t in TH: print(f"veto blobs < {t*100:.1f}% of image: static silent misses {silent[t]}, mean share of image carved {np.mean(carved[t])*100:.2f}%")
