"""ADE20K SegFormer-B0 at several input sizes on both benchmarks; also exports ONNX and times onnxruntime (CPU, 4 threads)."""
import glob, json, time, os, numpy as np, torch, onnxruntime as ort
from PIL import Image
from transformers import SegformerForSemanticSegmentation
torch.set_num_threads(4)
R = "/home/user/basir-run-/BasirRun_Research"; HF = R + "/pretrained_models/hf/hub"
snap = lambda m: glob.glob(f"{HF}/models--{m.replace('/', '--')}/snapshots/*")[0]
model = SegformerForSemanticSegmentation.from_pretrained(snap("nvidia/segformer-b0-finetuned-ade-512-512")).eval()
WALK = [3, 9, 11, 13, 29, 46, 52, 91]; MEAN = np.array([0.485, 0.456, 0.406]); STD = np.array([0.229, 0.224, 0.225])
def corridor(h, w):
    m = np.zeros((h, w), bool); m[int(h * 0.55):, int(w * 0.3):int(w * 0.7)] = True; return m
sets = {
    "sanpo": ([l.strip() for l in open(R + "/benchmarks/sanpo_list.txt")], lambda f: f[:-4] + "_sem.png", [3, 5, 6, 17, 30]),
    "rugd": ([l.strip() for l in open(R + "/benchmarks/rugd_list.txt")], lambda f: f.replace("image_res", "labelids_res")[:-4] + "_group5.png", [3, 4]),
}
res = {}
for size in [256, 320, 384, 512]:
    for sname, (frames, gtp, gw) in sets.items():
        tp = fp = fn = 0
        for f in frames[::2]:
            gt = np.asarray(Image.open(gtp(f))); img = Image.open(f).convert("RGB")
            x = (np.asarray(img.resize((size, size)), dtype=np.float32) / 255 - MEAN) / STD
            with torch.no_grad(): lab = model(pixel_values=torch.from_numpy(x.transpose(2, 0, 1)[None].astype(np.float32))).logits[0].argmax(0).numpy()
            m = np.asarray(Image.fromarray(np.isin(lab, WALK).astype(np.uint8)).resize(gt.shape[::-1], Image.NEAREST)) > 0
            reg = (gt != 0) & corridor(*gt.shape); g = np.isin(gt, gw)
            tp += (m & g & reg).sum(); fp += (m & ~g & reg).sum(); fn += (~m & g & reg).sum()
        res[(size, sname)] = tp / (tp + fp + fn)
    # ONNX export + timing
    path = f"{R}/pretrained_models/segformer_b0_ade_{size}.onnx"
    torch.onnx.export(model, torch.zeros(1, 3, size, size), path, input_names=["pixel_values"], output_names=["logits"], opset_version=17, dynamo=False)
    s = ort.InferenceSession(path, providers=["CPUExecutionProvider"], sess_options=(lambda o: (setattr(o, "intra_op_num_threads", 4), o)[1])(ort.SessionOptions()))
    x = np.zeros((1, 3, size, size), np.float32); s.run(None, {"pixel_values": x})
    t0 = time.perf_counter(); [s.run(None, {"pixel_values": x}) for _ in range(10)]; ms = (time.perf_counter() - t0) * 100
    print(f"size {size}: corridor IoU sanpo {res[(size,'sanpo')]:.3f} rugd {res[(size,'rugd')]:.3f} | onnxruntime CPU {ms:.1f} ms | onnx {os.path.getsize(path)/1e6:.1f} MB", flush=True)
