"""Writes full-resolution walkable masks (PNG, 0/255) for every benchmark frame from a model (onnxruntime)."""
import sys, os, numpy as np, onnxruntime as ort
from PIL import Image
onnx_path, size, ids, listfile, outdir = sys.argv[1], int(sys.argv[2]), [int(x) for x in sys.argv[3].split(",")], sys.argv[4], sys.argv[5]
os.makedirs(outdir, exist_ok=True)
o = ort.SessionOptions(); o.intra_op_num_threads = 4
s = ort.InferenceSession(onnx_path, o, providers=["CPUExecutionProvider"])
MEAN = np.array([0.485, 0.456, 0.406]); STD = np.array([0.229, 0.224, 0.225])
for f in [l.strip() for l in open(listfile) if l.strip()]:
    img = Image.open(f).convert("RGB")
    x = ((np.asarray(img.resize((size, size)), dtype=np.float32) / 255 - MEAN) / STD).transpose(2, 0, 1)[None].astype(np.float32)
    lab = s.run(None, {"pixel_values": x})[0][0].argmax(0)
    m = Image.fromarray((np.isin(lab, ids) * 255).astype(np.uint8)).resize(img.size, Image.NEAREST)
    m.save(os.path.join(outdir, f.split("/")[-2] + "__" + os.path.basename(f)[:-4] + ".png"))
