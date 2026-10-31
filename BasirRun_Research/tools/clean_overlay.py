"""Removes a dev-mode overlay burned into a video: crops the text band and inpaints the drawn marks
(opaque blue lane marks, bright green/pink path, yellow arrows) from their surroundings, keeping the
real white lines, grass and track. Writes the clean frames + MP4 and the share of pixels inpainted.
Usage: clean_overlay.py <in.mp4> <out_dir> <image_rows>"""
import glob, os, subprocess, sys, cv2, numpy as np
src, out, rows = sys.argv[1], sys.argv[2], int(sys.argv[3])
os.makedirs(out + "/raw", exist_ok=True); os.makedirs(out + "/clean", exist_ok=True); os.makedirs(out + "/mask", exist_ok=True)
for f in glob.glob(out + "/raw/*.png") + glob.glob(out + "/clean/*.png"): os.remove(f)
fps = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v", "-show_entries", "stream=r_frame_rate", "-of", "csv=p=0", src], capture_output=True, text=True).stdout.strip()
subprocess.run(["ffmpeg", "-loglevel", "error", "-i", src, out + "/raw/%04d.png"], check=True)
shares = []
for f in sorted(glob.glob(out + "/raw/*.png")):
    a = cv2.imread(f)[:rows]; B, G, R = [a[..., k].astype(int) for k in range(3)]
    # colour signatures of the drawn marks that the track (red), grass (olive), sky and white lines never have
    blue = (B > 190) & (B - R > 80) & (G > 110)              # lane marks (80,180,255)
    green = (G > 160) & (G - R > 60) & (G - B > 50)          # path (84,219,112)
    pink = (R > 170) & (B > 140) & (G < 150) & (B - G > 40)  # path part (212,124,207)
    yellow = (R > 200) & (G > 150) & (B < 110) & (R - B > 120)  # arrows
    core = (blue | green | pink | yellow).astype(np.uint8) * 255
    # anti-aliased fringes sit within 2 px of a mark; white line pixels there are kept (the real lane line)
    near = cv2.dilate(core, np.ones((5, 5), np.uint8), 1) > 0
    white = np.minimum(np.minimum(R, G), B) > 150
    m = ((near & ~white) | (core > 0)).astype(np.uint8) * 255
    clean = cv2.inpaint(a, m, 3, cv2.INPAINT_TELEA)
    n = os.path.basename(f); cv2.imwrite(out + "/clean/" + n, clean); cv2.imwrite(out + "/mask/" + n, m)
    shares.append((m > 0).mean())
subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-framerate", fps, "-i", out + "/clean/%04d.png", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "18", out + "/clean.mp4"], check=True)
print(f"{len(shares)} frames at {fps} fps, {rows} rows kept; inpainted pixels per frame: median {np.median(shares)*100:.2f}%, max {max(shares)*100:.2f}%")
