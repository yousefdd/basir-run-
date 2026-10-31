"""YOLOP (hustvl/YOLOP, MIT, BDD100K car-camera training) on the same 280 SANPO chest frames.
Its drivable-area head is scored as 'walkable' with the scorer of bench_walkable.py (same GT, same corridor).
Preprocessing follows the repo's test_onnx.py (letterbox to square, ImageNet mean/std)."""
import csv, json, os, sys, time
import numpy as np, onnxruntime as ort
from PIL import Image
R = "/home/user/basir-run-/BasirRun_Research"
GT_WALK = [3, 5, 6, 17, 30]
def corridor(h, w):
    m = np.zeros((h, w), bool); m[int(h * 0.55):, int(w * 0.3):int(w * 0.7)] = True; return m
def score(pred, gt):
    valid = gt != 0; g = np.isin(gt, GT_WALK); out = {}
    for name, region in [("all", valid), ("corridor", valid & corridor(*gt.shape))]:
        p, q = pred & region, g & region
        out[name] = dict(tp=int((p & q).sum()), fp=int((p & ~q).sum()), fn=int((~p & q).sum()), neg=int((region & ~q).sum()), n=int(region.sum()))
    return out
def letterbox(img, s):
    w, h = img.size; r = min(s / w, s / h); nw, nh = round(w * r), round(h * r)
    canvas = Image.new("RGB", (s, s), (114, 114, 114)); dw, dh = (s - nw) // 2, (s - nh) // 2
    canvas.paste(img.resize((nw, nh), Image.BILINEAR), (dw, dh)); return canvas, dw, dh, nw, nh
frames = [l.strip() for l in open(R + "/benchmarks/sanpo_list.txt") if l.strip()]
cat = {r["session"]: r["category"] for r in csv.DictReader(open(R + "/datasets/sanpo/selected_sessions.csv"))}
rows = []
for s in (320, 640):
    sess_o = ort.InferenceSession(f"{R}/pretrained_models/yolop/yolop-{s}-{s}.onnx", providers=["CPUExecutionProvider"])
    for f in frames:
        sess = f.split("/")[-2]; name = os.path.basename(f)[:-4]
        img = Image.open(f).convert("RGB"); gt = np.asarray(Image.open(f[:-4] + "_sem.png"))
        c, dw, dh, nw, nh = letterbox(img, s)
        x = ((np.asarray(c, np.float32) / 255 - [0.485, 0.456, 0.406]) / [0.229, 0.224, 0.225]).transpose(2, 0, 1)[None].astype(np.float32)
        t0 = time.perf_counter(); _, da, ll = sess_o.run(["det_out", "drive_area_seg", "lane_line_seg"], {"images": x}); ms = (time.perf_counter() - t0) * 1000
        da = da[0].argmax(0)[dh:dh + nh, dw:dw + nw]; ll = ll[0].argmax(0)[dh:dh + nh, dw:dw + nw]
        for key, m in [(f"yolop_{s}_drivable", da == 1), (f"yolop_{s}_drivable_or_lane", (da == 1) | (ll == 1))]:
            m = np.asarray(Image.fromarray(m.astype(np.uint8) * 255).resize(gt.shape[::-1], Image.NEAREST)) > 0
            rows.append(dict(method=key, session=sess, category=cat[sess], frame=name, ms=round(ms, 1), **{k: json.dumps(v) for k, v in score(m, gt).items()}))
    print(s, "done", flush=True)
with open(R + "/experiment_results/sanpo/per_frame_yolop.csv", "w", newline="") as fh:
    w = csv.DictWriter(fh, list(rows[0].keys())); w.writeheader(); w.writerows(rows)
