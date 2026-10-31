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


class UnderfootMemory:
    """Same rule with the memory the app uses (engine UnderfootClasses): a class that was under the feet
    stays runnable for hold_ms, or while it still covers stay of the foot area; it enters at share."""
    def __init__(self, share=0.15, stay=0.03, hold_ms=2000):
        self.share, self.stay, self.hold, self.last = share, stay, hold_ms, {}
    def mask(self, lab, now_ms):
        h, w = lab.shape
        foot = lab[int(h * 0.8):, int(w * 0.35):int(w * 0.65)]
        keep = list(ALWAYS)
        for c in CONDITIONAL:
            s = (foot == c).mean(); seen = self.last.get(c)
            if s >= self.share or (seen is not None and s >= self.stay): self.last[c] = now_ms; keep.append(c)
            elif seen is not None and now_ms - seen <= self.hold: keep.append(c)
            else: self.last.pop(c, None)
        return np.isin(lab, keep)


def surface_continuity(lab, rgb64, walk, max_de=14.0, protect=(12, 13 + 1000, 14)):
    """Grows the walkable mask over pixels that look like the surface under the runner (CIELAB distance
    to its median colour < max_de) or like a painted line on it (bright, colourless), when they are
    connected to the foot area through such pixels and lie below the top 40 % of the frame. People and
    animals (ADE 12 person, 126 animal) are never grown over. Needs a confident foot area (>= 50 %
    walkable). Returns the grown mask (64x64)."""
    import cv2
    from scipy import ndimage
    h, w = lab.shape
    fy0, fx0, fx1 = int(h * 0.8), int(w * 0.35), int(w * 0.65)
    foot = walk[fy0:, fx0:fx1]
    if foot.mean() < 0.5: return walk
    L = cv2.cvtColor(rgb64.astype(np.uint8), cv2.COLOR_RGB2LAB).astype(float)
    ref = np.median(L[fy0:, fx0:fx1][foot], axis=0)
    de = np.sqrt(((L - ref) ** 2).sum(-1)) * (100 / 255)  # approx CIELAB scale
    chroma = np.hypot(L[..., 1] - 128, L[..., 2] - 128); line = (L[..., 0] > 170) & (chroma < 18)
    cand = ((de < max_de) | line) & ~np.isin(lab, [12, 126]); cand[: int(h * 0.4)] = False
    comp, n = ndimage.label(cand | walk)
    seeds = set(np.unique(comp[fy0:, fx0:fx1][foot])) - {0}
    grown = np.isin(comp, list(seeds)) & (cand | walk)
    return walk | grown


class SurfaceContinuity:
    """Stateful version: remembers the colour of the confirmed surface under the feet for hold_ms, so a
    frame where the model names even the foot area wrongly ("wall" on an athletics track) still grows
    from the foot pixels that keep that colour."""
    def __init__(self, max_de=14.0, hold_ms=2000):
        self.max_de, self.hold, self.ref, self.t = max_de, hold_ms, None, None
    def apply(self, lab, rgb64, walk, now_ms):
        import cv2
        from scipy import ndimage
        h, w = lab.shape; fy0, fx0, fx1 = int(h * 0.8), int(w * 0.35), int(w * 0.65)
        L = cv2.cvtColor(rgb64.astype(np.uint8), cv2.COLOR_RGB2LAB).astype(float)
        foot = walk[fy0:, fx0:fx1]
        if foot.mean() >= 0.5:
            self.ref = np.median(L[fy0:, fx0:fx1][foot], axis=0); self.t = now_ms
        if self.ref is None or now_ms - self.t > self.hold: return walk
        de = np.sqrt(((L - self.ref) ** 2).sum(-1)) * (100 / 255)
        chroma = np.hypot(L[..., 1] - 128, L[..., 2] - 128); line = (L[..., 0] > 170) & (chroma < 18)
        surf = de < self.max_de
        cand = (surf | line) & ~np.isin(lab, [12, 126]); cand[: int(h * 0.4)] = False
        seedmask = np.zeros_like(walk); seedmask[fy0:, fx0:fx1] = (walk[fy0:, fx0:fx1] | surf[fy0:, fx0:fx1])
        if seedmask[fy0:, fx0:fx1].mean() < 0.5: return walk      # the feet are not on that surface any more
        comp, n = ndimage.label(cand | walk)
        seeds = set(np.unique(comp[seedmask])) - {0}
        return walk | (np.isin(comp, list(seeds)) & (cand | walk))
