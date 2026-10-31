"""Turn direction of a clip from optical flow (Farneback) of the upper half of the frame (far scene):
camera yawing right moves the scene left. Prints cumulative yaw in degrees (approx., from the
horizontal field of view) per clip, plus per-second values. Independent of the vision engine."""
import sys, cv2, numpy as np
HFOV = 58.4
for path in sys.argv[1:]:
    cap = cv2.VideoCapture(path); prev = None; yaw = []; W = None
    while True:
        ok, f = cap.read()
        if not ok: break
        g = cv2.cvtColor(cv2.resize(f, (320, 180)), cv2.COLOR_BGR2GRAY); W = 320
        if prev is not None:
            fl = cv2.calcOpticalFlowFarneback(prev, g, None, 0.5, 3, 21, 3, 5, 1.1, 0)
            dx = np.median(fl[:80, :, 0])  # top part: far scene, little parallax
            yaw.append(-dx / W * HFOV)
        prev = g
    y = np.cumsum(yaw)
    print(f"{path.split('/')[-1]} total_yaw_deg {y[-1]:+.1f} per_2s " + " ".join(f"{v:+.0f}" for v in y[29::30]))
