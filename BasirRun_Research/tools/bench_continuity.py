"""Safety check of 'surface continuity' on the 280 human-labelled SANPO frames (single frames, so the
seed must come from the frame itself): corridor false-walkable and the end-to-end path test with
D-FINE boxes carved, against the shipped underfoot mask. Writes masks for eval_paths.py."""
import os, sys, json, numpy as np, onnxruntime as ort
from PIL import Image
sys.path.insert(0, os.path.dirname(__file__)); from underfoot import underfoot_mask, SurfaceContinuity
R = "/home/user/basir-run-/BasirRun_Research"; GT = [3, 5, 6, 17, 30]
s = ort.InferenceSession(R + "/pretrained_models/segformer_b0_ade_256.onnx", providers=["CPUExecutionProvider"])
M = np.array([0.485, 0.456, 0.406]); SD = np.array([0.229, 0.224, 0.225])
frames = [l.strip() for l in open(R + "/benchmarks/sanpo_list.txt") if l.strip()]
out = R + "/experiment_results/sanpo/masks_continuity_dfine"; os.makedirs(out, exist_ok=True)
acc = {"shipped": [0, 0, 0, 0], "continuity": [0, 0, 0, 0]}
for f in frames:
    sess, fr = f.split("/")[-2], os.path.basename(f)[:-4]
    img = Image.open(f).convert("RGB"); gt = np.asarray(Image.open(f[:-4] + "_sem.png")); H, W = gt.shape
    x = ((np.asarray(img.resize((256, 256)), np.float32) / 255 - M) / SD).transpose(2, 0, 1)[None].astype(np.float32)
    lab = s.run(None, {"pixel_values": x})[0][0].argmax(0)
    w0 = underfoot_mask(lab); g = SurfaceContinuity().apply(lab, np.asarray(img.resize((64, 64))), w0, 0)
    carved = np.asarray(Image.open(f"{R}/experiment_results/sanpo/masks_underfoot_dfine/{sess}__{fr}.png").resize((W, H), Image.NEAREST)) > 0
    shipped = np.asarray(Image.open(f"{R}/experiment_results/sanpo/masks_ade_b0_256_underfoot/{sess}__{fr}.png").resize((W, H), Image.NEAREST)) > 0
    boxes = shipped & ~carved                     # what D-FINE carved in the shipped chain
    gm = np.asarray(Image.fromarray(g.astype(np.uint8) * 255).resize((W, H), Image.NEAREST)) > 0
    Image.fromarray(((gm & ~boxes) * 255).astype(np.uint8)).save(f"{out}/{sess}__{fr}.png")
    corr = np.zeros((H, W), bool); corr[int(H * .55):, int(W * .3):int(W * .7)] = True; reg = corr & (gt != 0); q = np.isin(gt, GT)
    for k, m in (("shipped", shipped), ("continuity", gm)):
        a = acc[k]; a[0] += (m & q & reg).sum(); a[1] += (m & ~q & reg).sum(); a[2] += (~m & q & reg).sum(); a[3] += (reg & ~q).sum()
for k, (tp, fp, fn, neg) in acc.items():
    print(f"{k}: corridor IoU {tp/(tp+fp+fn):.3f}, false-walkable {fp/neg:.3f}")
