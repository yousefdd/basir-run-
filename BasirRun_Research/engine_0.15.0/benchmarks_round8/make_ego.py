"""Gyroscope stand-in for the replay (ReplayMain --pose-yaw reads ego.json: per-frame turn, rad, + right).
SANPO-Real: from the recorded chest-camera orientation (calibrated: a right turn decreases the pose heading).
Synthetic: from the rendered camera yaw (truth.json heading, + right = clockwise)."""
import json, os, sys, math
import numpy as np
clip = sys.argv[1]
if os.path.exists(os.path.join(clip, "truth.json")):
    T = json.load(open(os.path.join(clip, "truth.json")))["frames"]
    hd = np.radians([t["headingDeg"] for t in T]); d = np.diff(hd, prepend=hd[0])
else:
    src = json.load(open(os.path.join(clip, "source.json")))
    Q = np.array([[float(v) for v in r[4:8]] for r in src["poses"]])
    x, y, z, w = Q.T
    hd = np.unwrap(np.arctan2(1 - 2 * (y * y + z * z), 2 * (x * y + z * w)))
    d = -np.diff(hd, prepend=hd[0])
json.dump([{"yawRad": float(v)} for v in d], open(os.path.join(clip, "ego.json"), "w"))
print(clip, "net turn deg %.0f" % math.degrees(d.sum()))
