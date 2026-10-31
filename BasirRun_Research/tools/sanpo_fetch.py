"""Downloads human-annotated chest-camera frames (+ semantic mask + CREStereo depth) of the selected
SANPO-Real sessions, evenly spaced, downscaled to 960x540 to keep the set small. Source: CC BY 4.0."""
import csv, json, urllib.request, gzip, io, os, sys
import numpy as np
from PIL import Image
from concurrent.futures import ThreadPoolExecutor
RAW = "https://storage.googleapis.com/gresearch/sanpo_dataset/v0/sanpo-real/"
root = sys.argv[1]; per = int(sys.argv[2]) if len(sys.argv) > 2 else 10
os.makedirs(root, exist_ok=True)
sel = list(csv.DictReader(open(os.path.join(root, "selected_sessions.csv"))))
def get(u): return urllib.request.urlopen(u, timeout=60).read()
def fetch(s):
    sid = s["session"]; base = RAW + sid + "/camera_chest/left/"
    types = json.loads(get(base + "frame_segmentation_annotation_type.json"))
    human = sorted(int(k) for k, v in types.items() if v == "HUMAN_ANNOTATED")
    if not human: return sid, 0
    step = max(1, len(human) // per); picks = human[::step][:per]
    out = os.path.join(root, "frames", sid); os.makedirs(out, exist_ok=True)
    n = 0
    for f in picks:
        name = f"{f:06d}"
        dst = os.path.join(out, name + ".jpg")
        if os.path.exists(dst): n += 1; continue
        try:
            img = Image.open(io.BytesIO(get(base + "video_frames/" + name + ".png"))).convert("RGB")
            mask = np.asarray(Image.open(io.BytesIO(get(base + "segmentation_masks/" + name + ".png"))))[..., 0]
            img.resize((960, 540), Image.BILINEAR).save(dst, quality=92)
            Image.fromarray(mask).resize((960, 540), Image.NEAREST).save(os.path.join(out, name + "_sem.png"))
            try:
                raw = gzip.decompress(get(base + "depth_maps/" + name + ".float16.gz"))
                d = np.frombuffer(raw, dtype=np.float16)
                h, w = mask.shape
                if d.size == h * w:
                    d = d.reshape(h, w)[::4, ::4]
                    np.save(os.path.join(out, name + "_depth.npy"), d)
            except Exception:
                pass
            n += 1
        except Exception as e:
            print("skip", sid, name, e)
    return sid, n
with ThreadPoolExecutor(8) as ex:
    for sid, n in ex.map(fetch, sel): print(sid, n)
