"""For each downloaded SANPO frame: fetch the CREStereo metric depth (first two float16 values are
height and width), back-project the pixels labelled walkable, fit the ground plane with RANSAC and
derive the camera height, pitch (down positive) and roll. These are reference geometry values used
to evaluate path planning independently of the phone's sensors."""
import csv, gzip, json, os, urllib.request, glob
import numpy as np
from PIL import Image
from multiprocessing import Pool
R = "/home/user/basir-run-/BasirRun_Research"; D = R + "/datasets/sanpo"
RAW = "https://storage.googleapis.com/gresearch/sanpo_dataset/v0/sanpo-real/"
def intr(sess):
    d = json.loads(urllib.request.urlopen(RAW + sess + "/description.json").read())
    for c in d["session_camera_details"]:
        p = c["left_camera_params"]
        if p.get("image_width"): return p
def geom(f):
    sess = f.split("/")[-2]; name = os.path.basename(f)[:-4]
    try:
        raw = gzip.decompress(urllib.request.urlopen(RAW + sess + f"/camera_chest/left/depth_maps/{name}.float16.gz", timeout=60).read())
        a = np.frombuffer(raw, dtype=np.float16); h, w = int(a[0]), int(a[1]); dep = a[2:].reshape(h, w)[::2, ::2].astype(np.float32); h, w = dep.shape; del a, raw
        p = INTR[sess]; s = w / p["image_width"]; fx, fy, cx, cy = p["fx"] * s, p["fy"] * s, p["cx"] * s, p["cy"] * s
        sem = np.asarray(Image.open(f[:-4] + "_sem.png").resize((w, h), Image.NEAREST))
        ys, xs = np.nonzero(np.isin(sem, [1, 3, 5, 6, 17, 30]) & (dep > 0.5) & (dep < 15))
        if len(ys) < 500: return name, sess, None
        idx = np.random.default_rng(0).integers(0, len(ys), min(4000, len(ys))); ys, xs = ys[idx], xs[idx]
        z = dep[ys, xs]; X = np.stack([(xs - cx) / fx * z, (ys - cy) / fy * z, z], 1)  # x right, y down, z forward
        best = None; rng = np.random.default_rng(1)
        for _ in range(200):
            P = X[rng.integers(0, len(X), 3)]; n = np.cross(P[1] - P[0], P[2] - P[0]); nn = np.linalg.norm(n)
            if nn < 1e-9: continue
            n /= nn; d0 = -n @ P[0]; inl = np.abs(X @ n + d0) < 0.05
            if best is None or inl.sum() > best[0]: best = (inl.sum(), n, d0, inl)
        Xi = X[best[3]]; c = Xi.mean(0); n = np.linalg.svd(Xi - c)[2][-1]
        if n[1] < 0: n = -n                      # normal pointing down (towards the ground, y down)
        height = abs(n @ c)
        pitch = np.degrees(np.arcsin(np.clip(n[2], -1, 1)))   # forward axis tilted toward the ground
        roll = np.degrees(np.arctan2(-n[0], n[1]))
        return name, sess, dict(height=round(float(height), 3), pitch=round(float(pitch), 2), roll=round(float(roll), 2), inliers=round(float(best[0] / len(X)), 3), hfov=p["h_fov"])
    except Exception as e:
        return name, sess, None
frames = [l.strip() for l in open(R + "/benchmarks/sanpo_list.txt") if l.strip()]
INTR = {s: intr(s) for s in sorted({f.split("/")[-2] for f in frames})}
if __name__ == "__main__":
    with Pool(6) as pool:
        res = []
        for k, r in enumerate(pool.imap(geom, frames, chunksize=2)):
            res.append(r)
            if k % 40 == 0: print("done", k, flush=True)
    with open(D + "/frame_geometry.csv", "w", newline="") as fh:
        w = csv.writer(fh); w.writerow(["session", "frame", "height_m", "pitch_deg", "roll_deg", "plane_inliers", "hfov_deg"])
        for name, sess, g in res:
            if g: w.writerow([sess, name, g["height"], g["pitch"], g["roll"], g["inliers"], g["hfov"]])
    ok = [g for _, _, g in res if g]
    print(len(ok), "frames with geometry; height median %.2f m, pitch median %.1f deg (range %.1f..%.1f), roll median %.1f" % (
        np.median([g["height"] for g in ok]), np.median([g["pitch"] for g in ok]), min(g["pitch"] for g in ok), max(g["pitch"] for g in ok), np.median([g["roll"] for g in ok])))
