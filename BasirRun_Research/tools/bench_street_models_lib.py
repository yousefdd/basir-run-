"""blobs_in_corridor from bench_street_models.py without the benchmark side effects."""
import math, numpy as np
from scipy import ndimage

def blobs_in_corridor(lab, j, ids, min_px, max_m):
    hf, height, pitch = float(j["hfov"]), float(j["height"]), float(j["pitch"])
    H, W = lab.shape; fxl = (W / 2) / math.tan(math.radians(hf) / 2); fy = fxl * (H / W) * (16 / 9)
    cy, cx = (H - 1) / 2, (W - 1) / 2; comp, n = ndimage.label(np.isin(lab, ids)); hits = []
    for k in range(1, n + 1):
        ys, xs = np.nonzero(comp == k)
        if len(ys) < min_px: continue
        foot = ys.max(); a = math.radians(pitch) + math.atan((foot - cy) / fy)
        if a <= 0.02: continue
        fwd = height / math.tan(a); slant = height / math.sin(a)
        x0, x1 = xs[ys >= foot - max(1, H // 64)].min(), xs[ys >= foot - max(1, H // 64)].max()
        if fwd <= max_m and (x0 - cx) / fxl * slant <= 0.6 and (x1 - cx) / fxl * slant >= -0.6: hits.append(fwd)
    return hits

