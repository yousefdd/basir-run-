"""Downloads continuous chest-camera clips (every frame, 15 fps) from SANPO-Real sessions and encodes
them as 960x540 H.264 MP4 for temporal tests. Usage: sanpo_clips.py <outdir> <session:label:start:frames> ..."""
import io, os, subprocess, sys, tempfile, urllib.request
from concurrent.futures import ThreadPoolExecutor
from PIL import Image
RAW = "https://storage.googleapis.com/gresearch/sanpo_dataset/v0/sanpo-real/"
out = sys.argv[1]; os.makedirs(out, exist_ok=True)
for spec in sys.argv[2:]:
    sess, label, start, n = spec.split(":"); start, n = int(start), int(n)
    tmp = tempfile.mkdtemp()
    def get(i):
        try:
            b = urllib.request.urlopen(RAW + f"{sess}/camera_chest/left/video_frames/{i:06d}.png", timeout=60).read()
            Image.open(io.BytesIO(b)).convert("RGB").resize((960, 540), Image.BILINEAR).save(f"{tmp}/{i - start:05d}.jpg", quality=92)
            return True
        except Exception:
            return False
    with ThreadPoolExecutor(8) as ex: ok = sum(ex.map(get, range(start, start + n)))
    dst = f"{out}/sanpo_{label}_{sess[:8]}.mp4"
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-framerate", "15", "-i", f"{tmp}/%05d.jpg", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "23", dst], check=True)
    print(dst, ok, "frames", os.path.getsize(dst) // 1024, "KB", flush=True)
