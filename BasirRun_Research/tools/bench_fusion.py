"""Shipped walkable model (SegFormer-B0 ADE20K 256 + underfoot rule) vs YOLOP-320 drivable vs their fusions,
on the same 280 SANPO frames. Scores: corridor IoU and corridor false-walkable (share of non-walkable
corridor pixels called walkable), plus per-frame 'false clear' = frames where >20 % of the non-walkable
corridor pixels were called walkable."""
import csv, os, numpy as np, onnxruntime as ort
from PIL import Image
R = "/home/user/basir-run-/BasirRun_Research"; GT_WALK = [3, 5, 6, 17, 30]
def letterbox(img, s):
    w, h = img.size; r = min(s / w, s / h); nw, nh = round(w * r), round(h * r)
    c = Image.new("RGB", (s, s), (114, 114, 114)); dw, dh = (s - nw) // 2, (s - nh) // 2; c.paste(img.resize((nw, nh), Image.BILINEAR), (dw, dh)); return c, dw, dh, nw, nh
so = ort.InferenceSession(f"{R}/pretrained_models/yolop/yolop-320-320.onnx", providers=["CPUExecutionProvider"])
frames = [l.strip() for l in open(R + "/benchmarks/sanpo_list.txt") if l.strip()]
cat = {r["session"]: r["category"] for r in csv.DictReader(open(R + "/datasets/sanpo/selected_sessions.csv"))}
acc = {}; out = []
for f in frames:
    sess = f.split("/")[-2]; name = os.path.basename(f)[:-4]
    gt = np.asarray(Image.open(f[:-4] + "_sem.png")); H, W = gt.shape
    seg = np.asarray(Image.open(f"{R}/experiment_results/sanpo/masks_ade_b0_256_underfoot/{sess}__{name}.png").resize((W, H), Image.NEAREST)) > 0
    c, dw, dh, nw, nh = letterbox(Image.open(f).convert("RGB"), 320)
    x = ((np.asarray(c, np.float32) / 255 - [0.485, 0.456, 0.406]) / [0.229, 0.224, 0.225]).transpose(2, 0, 1)[None].astype(np.float32)
    da = so.run(["drive_area_seg"], {"images": x})[0][0].argmax(0)[dh:dh + nh, dw:dw + nw]
    yp = np.asarray(Image.fromarray((da == 1).astype(np.uint8) * 255).resize((W, H), Image.NEAREST)) > 0
    for d, m in (("masks_yolop320", yp), ("masks_seg_and_yolop", seg & yp)):
        os.makedirs(f"{R}/experiment_results/sanpo/{d}", exist_ok=True); Image.fromarray(m.astype(np.uint8) * 255).save(f"{R}/experiment_results/sanpo/{d}/{sess}__{name}.png")
    corr = np.zeros((H, W), bool); corr[int(H * .55):, int(W * .3):int(W * .7)] = True
    region = corr & (gt != 0); g = np.isin(gt, GT_WALK)
    for key, m in [("segformer_underfoot (shipped)", seg), ("yolop320_drivable", yp), ("AND (both agree)", seg & yp), ("OR (either)", seg | yp)]:
        p, q = m & region, g & region
        tp, fp, fn, neg = (p & q).sum(), (p & ~q).sum(), (~p & q).sum(), (region & ~q).sum()
        a = acc.setdefault(key, [0, 0, 0, 0, 0, 0]); a[0] += tp; a[1] += fp; a[2] += fn; a[3] += neg
        if neg > 500: a[4] += 1; a[5] += fp / neg > 0.2
lines = ["method | corridor IoU | corridor precision | corridor recall | corridor false-walkable | frames with false clear (>20% of blocked corridor called walkable)"]
for k, (tp, fp, fn, neg, nb, bad) in acc.items():
    lines.append(f"{k} | {tp/(tp+fp+fn):.3f} | {tp/(tp+fp):.3f} | {tp/(tp+fn):.3f} | {fp/neg:.3f} | {bad}/{nb}")
open(R + "/benchmarks/results/walkable_fusion_sanpo.txt", "w").write("\n".join(lines) + "\n"); print("\n".join(lines))
