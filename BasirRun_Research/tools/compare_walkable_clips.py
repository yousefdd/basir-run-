"""Shipped walkable channel (SegFormer-B0 ADE20K 256 + under-the-feet memory) vs SegFormer-B0 Cityscapes 512
(sidewalk always, road/terrain when under the feet) on unlabelled clips. There is no ground truth here, so
this measures behaviour only: how much of the corridor straight ahead each model calls walkable, and in how
many frames the corridor ahead looks blocked (< half walkable). A person must look at the overlays before
reading any of it as right or wrong. Side-by-side overlays are written next to each frames dir (cmp/).
Usage: python compare_walkable_clips.py <frames dir> [<frames dir> ...]   (frames from visual_test.py, .../f)"""
import glob, os, sys, numpy as np, onnxruntime as ort, torch, transformers
from PIL import Image
sys.path.insert(0, os.path.dirname(__file__)); from underfoot import UnderfootMemory
R = "/home/user/basir-run-/BasirRun_Research"
MEAN = np.array([0.485, 0.456, 0.406]); STD = np.array([0.229, 0.224, 0.225])
ROAD, SIDEWALK, TERRAIN = 0, 1, 9
CS = ["road", "sidewalk", "building", "wall", "fence", "pole", "light", "sign", "vegetation", "terrain", "sky", "person",
      "rider", "car", "truck", "bus", "train", "motorcycle", "bicycle"]
ade = ort.InferenceSession(R + "/pretrained_models/segformer_b0_ade_256.onnx", providers=["CPUExecutionProvider"])
torch.set_num_threads(4)
cs = transformers.SegformerForSemanticSegmentation.from_pretrained("nvidia/segformer-b0-finetuned-cityscapes-1024-1024", cache_dir=R + "/pretrained_models/hf/hub").eval()

def prep(img, s): return ((np.asarray(img.resize((s, s), Image.BILINEAR), np.float32) / 255 - MEAN) / STD).transpose(2, 0, 1)[None].astype(np.float32)

def cs_walk(lab):
    h, w = lab.shape; foot = lab[int(h * 0.8):, int(w * 0.35):int(w * 0.65)]
    return np.isin(lab, [SIDEWALK] + [c for c in (ROAD, TERRAIN) if (foot == c).mean() >= 0.15])

def corridor(m): h, w = m.shape; return m[int(h * .55):, int(w * .3):int(w * .7)]

for d in sys.argv[1:]:
    frames = sorted(glob.glob(d + "/*.jpg"))[::5]; mem = UnderfootMemory(); res = {"ade": [], "cs": []}; cls = {}
    os.makedirs(d + "/../cmp", exist_ok=True)
    for k, f in enumerate(frames):
        img = Image.open(f).convert("RGB")
        a = mem.mask(ade.run(None, {"pixel_values": prep(img, 256)})[0][0].argmax(0), k * 500)
        with torch.no_grad(): lab = cs(pixel_values=torch.from_numpy(prep(img, 512))).logits[0].argmax(0).numpy()
        c = cs_walk(lab)
        res["ade"].append(corridor(a).mean()); res["cs"].append(corridor(c).mean())
        for v, n in zip(*np.unique(corridor(lab), return_counts=True)): cls[CS[v]] = cls.get(CS[v], 0) + n
        if k % 4 == 0:                                                           # side-by-side overlay every 2 s
            out = []
            for m in (a, c):
                mm = np.asarray(Image.fromarray(m.astype(np.uint8) * 255).resize(img.size, Image.NEAREST)) > 0
                b = np.asarray(img).astype(np.float32); b[mm] = b[mm] * .55 + np.array([40, 200, 90]) * .45; out.append(b.astype(np.uint8))
            Image.fromarray(np.concatenate(out, 1)).save(f"{d}/../cmp/{k:04d}.jpg", quality=85)
    tot = sum(cls.values()); top = sorted(cls.items(), key=lambda x: -x[1])[:4]
    name = d.rstrip("/").split("/")[-2]
    print(f"{name}: {len(frames)} frames | corridor called walkable: shipped ADE {np.mean(res['ade']):.0%}, Cityscapes {np.mean(res['cs']):.0%}"
          f" | frames with corridor < half walkable: ADE {np.mean(np.array(res['ade']) < .5):.0%}, Cityscapes {np.mean(np.array(res['cs']) < .5):.0%}"
          f" | Cityscapes classes in corridor: " + ", ".join(f"{n} {v / tot:.0%}" for n, v in top), flush=True)
