"""'Surface under the runner' rule on top of the ADE20K class map (64x64 at 256 input):
path-like classes are always runnable (sidewalk, path, dirt track, floor); road, grass, earth,
field, sand count only when they are a real part of what is under the runner's feet."""
import numpy as np
ALWAYS = [3, 11, 52, 91]              # floor, sidewalk, path, dirt track
CONDITIONAL = [6, 9, 13, 29, 46]      # road, grass, earth, field, sand
def underfoot_mask(lab, share=0.15):
    h, w = lab.shape
    foot = lab[int(h * 0.8):, int(w * 0.35):int(w * 0.65)]
    keep = list(ALWAYS)
    for c in CONDITIONAL:
        if (foot == c).mean() >= share: keep.append(c)
    return np.isin(lab, keep)
