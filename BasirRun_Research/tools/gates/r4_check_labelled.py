"""G8: terrain_labelled.csv has IoU, Dice, precision, recall, F1, false-walkable and boundary F1 for >=4 methods on
both splits, terrain_obstacles.csv has recall / FNR / FPR on both splits; the shipped channel's test IoU is
recomputed here independently from the masks and the human labels and must match the table."""
import csv, hashlib, os, sys, numpy as np
from PIL import Image
R = "/home/user/basir-run-/BasirRun_Research"; S = R + "/experiment_results/sanpo/"
rows = list(csv.DictReader(open(R + "/benchmarks/results/terrain_labelled.csv")))
need = ["IoU", "Dice", "precision", "recall", "F1", "false_walkable", "boundary_F1"]
if any(k not in rows[0] for k in need): sys.exit("missing metric columns")
methods = {r["method"] for r in rows}
if len(methods) < 4 or any({(m, s) for s in ("dev", "test")} - {(r["method"], r["split"]) for r in rows} for m in methods): sys.exit("methods/splits incomplete")
ob = list(csv.DictReader(open(R + "/benchmarks/results/terrain_obstacles.csv")))
if any(k not in ob[0] for k in ("recall", "false_negative_rate", "false_positive_rate")) or {r["split"] for r in ob} != {"dev", "test"}: sys.exit("obstacle table incomplete")
tp = fp = fn = 0
for f in [l.strip() for l in open(R + "/benchmarks/sanpo_list.txt") if l.strip()]:
    sess = f.split("/")[-2]
    if int(hashlib.md5(sess.encode()).hexdigest(), 16) % 2 == 0: continue
    gt = np.asarray(Image.open(f[:-4] + "_sem.png")); H, W = gt.shape
    m = np.asarray(Image.open(f"{S}masks_ade_b0_256_underfoot/{sess}__{os.path.basename(f)[:-4]}.png").resize((W, H), Image.NEAREST)) > 0
    reg = np.zeros((H, W), bool); reg[int(H * .55):, int(W * .3):int(W * .7)] = True; reg &= gt != 0; q = np.isin(gt, [3, 5, 6, 17, 30])
    tp += (m & q & reg).sum(); fp += (m & ~q & reg).sum(); fn += (~m & q & reg).sum()
iou = round(tp / (tp + fp + fn), 3)
tab = float(next(r["IoU"] for r in rows if r["method"] == "shipped_ade" and r["split"] == "test"))
if abs(iou - tab) > 0.0015: sys.exit(f"recomputed {iou} vs table {tab}")
print(f"{len(methods)} methods x 2 splits; shipped test IoU recomputed {iou}"); print("LABELLED_OK")
