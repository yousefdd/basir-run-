"""Walkable-area benchmark on RUGD (5-label groups, test split, off-road park and trail).
GT walkable = stable (4) + unstable ground such as dirt/grass/gravel (3); background (0) ignored;
obstacle (1) and water (2) are not walkable. None of the models was trained on RUGD."""
import csv, glob, os, json, time, sys
import numpy as np
from PIL import Image
import torch
from transformers import SegformerForSemanticSegmentation
sys.path.insert(0, os.path.dirname(__file__))
torch.set_num_threads(4)
R = "/home/user/basir-run-/BasirRun_Research"; HF = R + "/pretrained_models/hf/hub"
snap = lambda m: glob.glob(f"{HF}/models--{m.replace('/', '--')}/snapshots/*")[0]
MODELS = {
    "segformer_b0_sanpo_walkable_224": ("blind-assist/Segf0rmer_SANPO", 224, [0]),
    "segformer_b0_sanpo_walkable": ("blind-assist/Segf0rmer_SANPO", 512, [0]),
    "segformer_b0_ade20k": ("nvidia/segformer-b0-finetuned-ade-512-512", 512, [3, 9, 11, 13, 29, 46, 52, 91]),
}
MEAN = np.array([0.485, 0.456, 0.406]); STD = np.array([0.229, 0.224, 0.225])
def corridor(h, w):
    m = np.zeros((h, w), bool); m[int(h * 0.55):, int(w * 0.3):int(w * 0.7)] = True; return m
def score(pred, gt):
    valid = gt != 0; g = np.isin(gt, [3, 4]); out = {}
    for name, region in [("all", valid), ("corridor", valid & corridor(*gt.shape))]:
        p, q = pred & region, g & region
        out[name] = json.dumps(dict(tp=int((p & q).sum()), fp=int((p & ~q).sum()), fn=int((~p & q).sum()), neg=int((region & ~q).sum()), n=int(region.sum())))
    return out
frames = [l.strip() for l in open(R + "/benchmarks/rugd_list.txt") if l.strip()]
gtp = lambda f: f.replace("image_res", "labelids_res")[:-4] + "_group5.png"
rows = []
for f in frames:
    gt = np.asarray(Image.open(gtp(f))); name = os.path.basename(f)[:-4]
    m = np.asarray(Image.open(f"{R}/experiment_results/rugd/current_engine/image_res__{name}.png").resize(gt.shape[::-1], Image.NEAREST)) > 0
    rows.append(dict(method="current_engine", session=name.split("_")[0], category=name.split("_")[0], frame=name, ms="", **score(m, gt)))
for key, (mid, size, ids) in MODELS.items():
    model = SegformerForSemanticSegmentation.from_pretrained(snap(mid)).eval()
    for f in frames:
        img = Image.open(f).convert("RGB"); gt = np.asarray(Image.open(gtp(f))); name = os.path.basename(f)[:-4]
        x = (np.asarray(img.resize((size, size)), dtype=np.float32) / 255 - MEAN) / STD
        t0 = time.perf_counter()
        with torch.no_grad(): lab = model(pixel_values=torch.from_numpy(x.transpose(2, 0, 1)[None].astype(np.float32))).logits[0].argmax(0).numpy()
        ms = (time.perf_counter() - t0) * 1000
        m = np.asarray(Image.fromarray(np.isin(lab, ids).astype(np.uint8)).resize(gt.shape[::-1], Image.NEAREST)) > 0
        rows.append(dict(method=key, session=name.split("_")[0], category=name.split("_")[0], frame=name, ms=round(ms, 1), **score(m, gt)))
    print(key, "done", flush=True)
os.makedirs(R + "/experiment_results/rugd", exist_ok=True)
with open(R + "/experiment_results/rugd/per_frame.csv", "w", newline="") as fh:
    w = csv.DictWriter(fh, list(rows[0].keys())); w.writeheader(); w.writerows(rows)
