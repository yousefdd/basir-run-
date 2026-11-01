"""Round 4 labelled benchmark on the 280 human-labelled SANPO chest frames, split by SESSION into dev (tune)
and test (report): md5(session) even -> dev, odd -> test. Fusion rules are chosen on dev only.

Walkable channel candidates (same frames, same ground truth, same corridor):
  shipped_ade      SegFormer-B0 ADE20K 256 + under-the-feet rule (current app)
  cityscapes512    SegFormer-B0 Cityscapes 512: sidewalk always, road/terrain under the feet
  offroad512       SegFormer-B2 RELLIS+GOOSE (roboticslaburjc, Apache-2.0 stated): smooth ground always,
                   rough ground / low vegetation only under the feet
  terrain_*        Terrain Understanding fusions of ADE and off-road classes (see FUSIONS)
Metrics per method and split: corridor IoU, Dice, precision, recall, F1, false-walkable rate, boundary F1
(2 px tolerance at 160x90, lower half), plus fixed-obstacle recall / false-negative rate / false-alarm rate
within 4 m in arm reach (same ground truth as bench_depth_eval), alone and OR the app's depth channel.
Writes benchmarks/results/terrain_labelled.csv and .txt."""
import csv, hashlib, math, os, sys, time, numpy as np
from PIL import Image
from scipy import ndimage
R = "/home/user/basir-run-/BasirRun_Research"; S = R + "/experiment_results/sanpo/"
sys.path.insert(0, os.path.dirname(__file__))
frames = [l.strip() for l in open(R + "/benchmarks/sanpo_list.txt") if l.strip()]
key = lambda f: (f.split("/")[-2], os.path.basename(f)[:-4])
split = lambda sess: "dev" if int(hashlib.md5(sess.encode()).hexdigest(), 16) % 2 == 0 else "test"
GT = [3, 5, 6, 17, 30]
MEAN = np.array([0.485, 0.456, 0.406]); STD = np.array([0.229, 0.224, 0.225])
# off-road ontology (meaning inferred from co-occurrence with SANPO / RUGD human labels, see report)
DYN, WATER, OBST, HIGHVEG, LOWVEG, ROUGH, SMOOTH, SKY = range(8)

def offroad_cache():
    out = S + "offroad_b2_512/"; os.makedirs(out, exist_ok=True); m = None; times = []
    for f in frames:
        k = key(f); p = f"{out}{k[0]}__{k[1]}.npz"
        if os.path.exists(p): continue
        if m is None:
            import torch; from offroad_model import OffroadSegformer
            torch.set_num_threads(4); m = OffroadSegformer(R + "/pretrained_models/offroad/segformer_mit-b2_8xb1-160k_cit.pt")
        import torch
        img = Image.open(f).convert("RGB")
        x = torch.from_numpy(((np.asarray(img.resize((512, 512), Image.BILINEAR), np.float32) / 255 - MEAN) / STD).transpose(2, 0, 1)[None].astype(np.float32))
        t0 = time.perf_counter(); pr = torch.softmax(m(x)[0], 0).numpy(); times.append((time.perf_counter() - t0) * 1000)
        np.savez_compressed(p, lab=pr.argmax(0).astype(np.uint8), conf=(pr.max(0) * 255).astype(np.uint8))
    if times: open(out + "time_ms.txt", "w").write(f"{np.median(times):.0f}\n")
    return out

def underfoot(lab, cls, share=0.15):
    h, w = lab.shape; foot = lab[int(h * 0.8):, int(w * 0.35):int(w * 0.65)]
    return [c for c in cls if (foot == c).mean() >= share]

def ade_labels(k):
    p = f"{S}ade_labels/{k[0]}__{k[1]}.npy"
    return np.load(p) if os.path.exists(p) else None

def rs(m, shape): return np.asarray(Image.fromarray(m.astype(np.uint8) * 255).resize(shape[::-1], Image.NEAREST)) > 0

def method_masks(k, shape):
    out = {}
    out["shipped_ade"] = np.asarray(Image.open(f"{S}masks_ade_b0_256_underfoot/{k[0]}__{k[1]}.png").resize(shape[::-1], Image.NEAREST)) > 0
    cs = np.load(f"{S}street_segformer_b0_cityscapes_512/{k[0]}__{k[1]}.npy")
    out["cityscapes512"] = rs(np.isin(cs, [1] + underfoot(cs, [0, 9])), shape)
    z = np.load(f"{S}offroad_b2_512/{k[0]}__{k[1]}.npz"); ol, oc = z["lab"], z["conf"] / 255.0
    out["offroad512"] = rs(np.isin(ol, [SMOOTH] + underfoot(ol, [ROUGH, LOWVEG])), shape)
    ade = out["shipped_ade"]; olr = rs(np.ones_like(ol, bool), shape)
    ground = rs(np.isin(ol, [SMOOTH, ROUGH, LOWVEG]), shape)
    smooth_hi = rs((ol == SMOOTH) & (oc >= 0.8), shape)
    rough_uf = underfoot(ol, [ROUGH]); rough_hi = rs((ol == ROUGH) & (oc >= 0.7), shape) if rough_uf else np.zeros(shape, bool)
    out["terrain_veto"] = ade & ground                                   # ADE walkable, vetoed where off-road says obstacle / tall vegetation / water / person
    out["terrain_veto_plus_smooth"] = (ade & ground) | smooth_hi         # + confident smooth ground ADE missed
    out["terrain_full"] = (ade & ground) | smooth_hi | rough_hi          # + confident rough trail when the feet are on it
    # chosen on dev (decision_criteria_round4.md): highest dev IoU with dev false-walkable <= shipped
    out["terrain_selected"] = (ade & ~rs(np.isin(ol, [DYN, WATER, OBST]), shape)) | rs((ol == SMOOTH) & (oc >= 0.95), shape)
    return out, (ol, oc)

def boundary_f1(pred, gt, tol=2):
    sm = lambda m: np.asarray(Image.fromarray(m.astype(np.uint8) * 255).resize((160, 90), Image.NEAREST)) > 0
    p, g = sm(pred), sm(gt); p[:45] = False; g[:45] = False
    bp = p ^ ndimage.binary_erosion(p); bg = g ^ ndimage.binary_erosion(g)
    if bg.sum() == 0 and bp.sum() == 0: return 1.0
    dg = ndimage.distance_transform_edt(~bg); dp = ndimage.distance_transform_edt(~bp)
    prec = (dg[bp] <= tol).mean() if bp.any() else 0.0; rec = (dp[bg] <= tol).mean() if bg.any() else 0.0
    return 0.0 if prec + rec == 0 else 2 * prec * rec / (prec + rec)

def obstacle_hit(ol, j, ids, max_m):
    from bench_street_models_lib import blobs_in_corridor
    return bool(blobs_in_corridor(ol, j, ids, max(2, int(ol.size * 0.0004)), max_m))

if __name__ == "__main__":
    offroad_cache()
    stats = {}; bf = {}
    for f in frames:
        k = key(f); sp = split(k[0]); gt = np.asarray(Image.open(f[:-4] + "_sem.png")); H, W = gt.shape
        masks, _ = method_masks(k, (H, W))
        corr = np.zeros((H, W), bool); corr[int(H * .55):, int(W * .3):int(W * .7)] = True; reg = corr & (gt != 0); q = np.isin(gt, GT)
        for name, m in masks.items():
            s = stats.setdefault((name, sp), np.zeros(4)); s += [(m & q & reg).sum(), (m & ~q & reg).sum(), (~m & q & reg).sum(), (reg & ~q).sum()]
            bf.setdefault((name, sp), []).append(boundary_f1(m & (gt != 0), q & (gt != 0)))
    # fixed obstacles (same ground truth as the depth benchmark)
    jobs = {(j["session"], j["frame"]): j for j in csv.DictReader(open(S + "depth/jobs.csv"))}
    dep = {(r["session"], r["frame"]): r["level"] in ("Possible", "Hazard") for r in csv.DictReader(open(S + "depth/check_shipped.csv"))}
    fnum = lambda x: float(x) if x else None
    s4 = [j for j in jobs.values() if fnum(j["static_m"]) is not None and fnum(j["static_m"]) <= 4]
    cl = [j for j in jobs.values() if all(fnum(j[c]) is None or fnum(j[c]) > 6 for c in ("static_m", "dynamic_m", "wall_m"))]
    ob = {}
    for j in s4 + cl:
        k = (j["session"], j["frame"]); z = np.load(f"{S}offroad_b2_512/{k[0]}__{k[1]}.npz")
        ob[k] = (obstacle_hit(z["lab"], j, [OBST], 4.5), obstacle_hit(z["lab"], j, [OBST], 4.0))
    rows = []
    for (name, sp), (tp, fp, fn, neg) in sorted(stats.items()):
        p = tp / (tp + fp) if tp + fp else 0; r = tp / (tp + fn) if tp + fn else 0
        rows.append(dict(method=name, split=sp, IoU=round(tp / (tp + fp + fn), 3), Dice=round(2 * tp / (2 * tp + fp + fn), 3), precision=round(p, 3), recall=round(r, 3),
                         F1=round(2 * p * r / (p + r), 3) if p + r else 0, false_walkable=round(fp / neg, 3), boundary_F1=round(float(np.mean(bf[(name, sp)])), 3)))
    obs_rows = []
    for sp in ("dev", "test"):
        a = [j for j in s4 if split(j["session"]) == sp]; c = [j for j in cl if split(j["session"]) == sp]
        k_ = lambda j: (j["session"], j["frame"])
        for name, hit, fa in (("depth only (shipped)", lambda j: dep[k_(j)], lambda j: dep[k_(j)]),
                              ("offroad obstacle class", lambda j: ob[k_(j)][0], lambda j: ob[k_(j)][1]),
                              ("offroad obstacle class OR depth", lambda j: ob[k_(j)][0] or dep[k_(j)], lambda j: ob[k_(j)][1] or dep[k_(j)])):
            h = sum(map(hit, a)); f_ = sum(map(fa, c))
            obs_rows.append(dict(method=name, split=sp, obstacle_frames=len(a), recall=round(h / max(len(a), 1), 3), false_negative_rate=round(1 - h / max(len(a), 1), 3),
                                 clear_frames=len(c), false_positive_rate=round(f_ / max(len(c), 1), 3)))
    with open(R + "/benchmarks/results/terrain_labelled.csv", "w", newline="") as fh:
        w = csv.DictWriter(fh, list(rows[0])); w.writeheader(); w.writerows(rows)
    with open(R + "/benchmarks/results/terrain_obstacles.csv", "w", newline="") as fh:
        w = csv.DictWriter(fh, list(obs_rows[0])); w.writeheader(); w.writerows(obs_rows)
    t = open(S + "offroad_b2_512/time_ms.txt").read().strip() if os.path.exists(S + "offroad_b2_512/time_ms.txt") else "?"
    lines = [f"Terrain benchmark, 280 SANPO chest frames, split by session (dev = tune, test = report). Off-road B2 512: {t} ms/frame desktop CPU.",
             "method | split | IoU | Dice | precision | recall | F1 | false-walkable | boundary F1"]
    lines += [" | ".join(str(r[k]) for k in r) for r in rows]
    lines += ["", "fixed obstacles within 4 m (frames) | split | obstacle frames | recall | false-negative rate | clear frames | false-positive rate"]
    lines += [" | ".join(str(r[k]) for k in r) for r in obs_rows]
    open(R + "/benchmarks/results/terrain_labelled.txt", "w").write("\n".join(lines) + "\n"); print("\n".join(lines))
