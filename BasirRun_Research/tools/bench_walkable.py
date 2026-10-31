"""Walkable-area benchmark on SANPO-Real human-annotated chest frames.
Each method yields a binary 'walkable' mask; scored against SANPO labels.
GT walkable = sidewalk, crosswalk, paved trail, other walkable surface, terrain. Unlabeled (0) ignored.
Also scored inside the runner's corridor (bottom 45% of the frame, central 40% of the width):
false-walkable there is the dangerous error (the system thinks the way ahead is runnable when it is not)."""
import csv, glob, os, sys, time, json
import numpy as np
from PIL import Image
import torch
from transformers import SegformerForSemanticSegmentation
torch.set_num_threads(4)
R = "/home/user/basir-run-/BasirRun_Research"
HF = R + "/pretrained_models/hf/hub"
GT_WALK = [3, 5, 6, 17, 30]
def snap(m): return glob.glob(f"{HF}/models--{m.replace('/', '--')}/snapshots/*")[0]
MODELS = {
    # id2label in its config is reversed: class 0 is the walkable one (checked against SANPO labels).
    "segformer_b0_sanpo_walkable": ("blind-assist/Segf0rmer_SANPO", 512, [0]),
    "segformer_b0_sanpo_walkable_224": ("blind-assist/Segf0rmer_SANPO", 224, [0]),
    "segformer_b0_ade20k": ("nvidia/segformer-b0-finetuned-ade-512-512", 512, [3, 9, 11, 13, 29, 46, 52, 91]),
    "segformer_b0_sidewalk": ("malra/segformer-b0-finetuned-segments-sidewalk-4", 512, [2, 3, 29]),
    "segformer_b5_ade20k_ref": ("nvidia/segformer-b5-finetuned-ade-640-640", 640, [3, 9, 11, 13, 29, 46, 52, 91]),
}
MEAN = np.array([0.485, 0.456, 0.406]); STD = np.array([0.229, 0.224, 0.225])
def run(model, img, size):
    x = (np.asarray(img.resize((size, size), Image.BILINEAR), dtype=np.float32) / 255 - MEAN) / STD
    t = torch.from_numpy(x.transpose(2, 0, 1)[None].astype(np.float32))
    t0 = time.perf_counter()
    with torch.no_grad(): logits = model(pixel_values=t).logits
    ms = (time.perf_counter() - t0) * 1000
    return logits[0].argmax(0).numpy(), ms
def corridor(h, w):
    m = np.zeros((h, w), bool); m[int(h * 0.55):, int(w * 0.3):int(w * 0.7)] = True; return m
def score(pred, gt):
    valid = gt != 0; g = np.isin(gt, GT_WALK)
    out = {}
    for name, region in [("all", valid), ("corridor", valid & corridor(*gt.shape))]:
        p, q = pred & region, g & region
        tp = (p & q).sum(); fp = (p & ~q).sum(); fn = (~p & q).sum(); neg = (region & ~q).sum()
        out[name] = dict(tp=int(tp), fp=int(fp), fn=int(fn), neg=int(neg), n=int(region.sum()))
    return out
frames = [l.strip() for l in open(R + "/benchmarks/sanpo_list.txt") if l.strip()]
cat = {r["session"]: r["category"] for r in csv.DictReader(open(R + "/datasets/sanpo/selected_sessions.csv"))}
rows = []
outdir = R + "/experiment_results/sanpo"
# current engine masks (already produced by the Kotlin harness)
for f in frames:
    sess = f.split("/")[-2]; name = os.path.basename(f)[:-4]
    gt = np.asarray(Image.open(f[:-4] + "_sem.png"))
    m = np.asarray(Image.open(f"{outdir}/current_engine/{sess}__{name}.png").resize(gt.shape[::-1], Image.NEAREST)) > 0
    rows.append(dict(method="current_engine", session=sess, category=cat[sess], frame=name, ms=None, **{k: json.dumps(v) for k, v in score(m, gt).items()}))
for key, (mid, size, walk_ids) in MODELS.items():
    model = SegformerForSemanticSegmentation.from_pretrained(snap(mid)).eval()
    os.makedirs(f"{outdir}/{key}", exist_ok=True)
    for i, f in enumerate(frames):
        sess = f.split("/")[-2]; name = os.path.basename(f)[:-4]
        img = Image.open(f).convert("RGB"); gt = np.asarray(Image.open(f[:-4] + "_sem.png"))
        lab, ms = run(model, img, size)
        m = np.isin(lab, walk_ids)
        m = np.asarray(Image.fromarray(m.astype(np.uint8) * 255).resize(gt.shape[::-1], Image.NEAREST)) > 0
        if i % 10 == 0: Image.fromarray(m.astype(np.uint8) * 255).save(f"{outdir}/{key}/{sess}__{name}.png")
        rows.append(dict(method=key, session=sess, category=cat[sess], frame=name, ms=round(ms, 1), **{k: json.dumps(v) for k, v in score(m, gt).items()}))
    print(key, "done", flush=True)
with open(f"{outdir}/per_frame.csv", "w", newline="") as fh:
    w = csv.DictWriter(fh, list(rows[0].keys())); w.writeheader(); w.writerows(rows)
