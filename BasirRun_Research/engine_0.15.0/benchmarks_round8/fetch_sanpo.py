"""Fetch a window of SANPO-Real chest-camera frames (CC BY 4.0), downscale to 640x360 JPEG, plus per-frame poses."""
import sys, os, json, csv, ssl, urllib.request, concurrent.futures as cf
import numpy as np, cv2
BASE = "https://storage.googleapis.com/gresearch/sanpo_dataset/v0/sanpo-real/"
ctx = ssl.create_default_context(cafile=os.environ.get("SSL_CERT_FILE", "/root/.ccr/ca-bundle.crt"))
def get(url):
    for k in range(4):
        try:
            with urllib.request.urlopen(url, context=ctx, timeout=60) as r: return r.read()
        except Exception as e:
            err = e
    raise err
def main(sid, start, count, out):
    os.makedirs(out + "/rgb", exist_ok=True)
    def one(i):
        p = f"{out}/rgb/{i - start:06d}.jpg"
        if os.path.exists(p): return
        b = get(f"{BASE}{sid}/camera_chest/left/video_frames/{i:06d}.png")
        img = cv2.imdecode(np.frombuffer(b, np.uint8), cv2.IMREAD_COLOR)
        cv2.imwrite(p, cv2.resize(img, (640, 360), interpolation=cv2.INTER_AREA), [cv2.IMWRITE_JPEG_QUALITY, 93])
    with cf.ThreadPoolExecutor(12) as ex: list(ex.map(one, range(start, start + count)))
    rows = list(csv.reader(get(f"{BASE}{sid}/camera_chest/camera_poses.csv").decode().splitlines()))
    json.dump({"session": sid, "start": start, "count": count, "header": rows[0], "poses": rows[1 + start:1 + start + count],
               "description": json.loads(get(f"{BASE}{sid}/description.json"))}, open(out + "/source.json", "w"))
if __name__ == "__main__":
    main(sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), sys.argv[4])
