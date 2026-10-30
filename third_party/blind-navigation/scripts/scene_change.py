"""
"New place" detector — decides when the surroundings changed enough to be
worth describing again (entering a room, stepping outside, reaching a crossing).

The frame score is PySceneDetect's ContentDetector metric (mean absolute
difference of hue, saturation and luma, equal weights) —
https://github.com/Breakthrough/PySceneDetect (BSD-3-Clause).

PySceneDetect compares *consecutive* frames to find hard cuts in edited video.
A walking camera never cuts; places change gradually and the view shakes. So
here the score is taken against a *keyframe* (the frame at the last
description), on small blurred frames to absorb sway, combined with an HSV
histogram correlation (like PySceneDetect's HistogramDetector, which tolerates
viewpoint shifts), and a change must persist for ``min_frames`` before it
fires. The keyframe is then reset.
"""

from __future__ import annotations

import time
from typing import Optional

import numpy as np


class SceneChangeDetector:
    def __init__(self, content_threshold: float = 35.0, hist_threshold: float = 0.6,
                 min_frames: int = 3, min_interval_s: float = 20.0, size=(64, 48)):
        self.content_threshold = content_threshold
        self.hist_threshold = hist_threshold
        self.min_frames = min_frames
        self.min_interval_s = min_interval_s
        self.size = size
        self._key_hsv = None
        self._key_hist = None
        self._streak = 0
        self._last_fire = -1e9
        self.last_score = 0.0
        self.last_hist_corr = 1.0

    def _features(self, bgr: np.ndarray):
        import cv2
        small = cv2.resize(bgr, self.size, interpolation=cv2.INTER_AREA)
        small = cv2.GaussianBlur(small, (5, 5), 0)
        hsv = cv2.cvtColor(small, cv2.COLOR_BGR2HSV)
        hist = cv2.calcHist([hsv], [0, 1], None, [16, 8], [0, 180, 0, 256])
        cv2.normalize(hist, hist)
        return hsv.astype(np.int16), hist

    @staticmethod
    def content_score(hsv_a: np.ndarray, hsv_b: np.ndarray) -> float:
        """PySceneDetect ContentDetector score with default weights (hue, sat, lum = 1, edges = 0)."""
        diffs = [np.abs(hsv_a[..., i] - hsv_b[..., i]).mean() for i in range(3)]
        return float(sum(diffs) / 3.0)

    def update(self, bgr: np.ndarray, now: Optional[float] = None) -> bool:
        """Feed a frame; returns True when a new place should be described."""
        import cv2
        now = time.monotonic() if now is None else now
        hsv, hist = self._features(bgr)
        if self._key_hsv is None:
            self._key_hsv, self._key_hist = hsv, hist
            self._last_fire = now
            return True  # first frame: describe where we are

        self.last_score = self.content_score(hsv, self._key_hsv)
        self.last_hist_corr = float(cv2.compareHist(hist, self._key_hist, cv2.HISTCMP_CORREL))
        changed = self.last_score >= self.content_threshold or self.last_hist_corr <= self.hist_threshold
        self._streak = self._streak + 1 if changed else 0

        if self._streak >= self.min_frames and now - self._last_fire >= self.min_interval_s:
            self._key_hsv, self._key_hist = hsv, hist
            self._streak = 0
            self._last_fire = now
            return True
        return False

    def reset(self):
        self._key_hsv = self._key_hist = None
        self._streak = 0
