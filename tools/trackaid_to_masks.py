#!/usr/bin/env python3
"""
TrackAid-DT (CC BY 4.0, https://github.com/vrai-group/TrackAid-DT) → evaluation set for Basir-Run.

The Roboflow export holds 3 augmented copies of every source frame (flip, rotation inside black
borders, exposure, blur, salt-and-pepper noise). Portrait phone videos are also letterboxed with
black side bars. For evaluation we keep ONE copy per source frame: the one whose content is an
upright rectangle (letterbox bars are cropped; rotated copies are skipped), and we record the
source video so results can be reported per video (frames of one video are strongly correlated).

  python -I tools/trackaid_to_masks.py /path/to/TrackAid-DT/dataset/train  out_dir
writes out_dir/images/*.jpg, out_dir/masks/*.png (255 = annotated running lane) and out_dir/manifest.csv
"""
import csv
import json
import re
import sys
from pathlib import Path

import cv2
import numpy as np

src, out = Path(sys.argv[1]), Path(sys.argv[2])
(out / "images").mkdir(parents=True, exist_ok=True)
(out / "masks").mkdir(parents=True, exist_ok=True)
coco = json.loads((src / "_annotations.coco.json").read_text())
anns = {}
for a in coco["annotations"]:
    anns.setdefault(a["image_id"], []).append(a)

best = {}  # source frame name → (border fraction, image record)
for im in coco["images"]:
    path = src / im["file_name"]
    img = cv2.imread(str(path))
    if img is None or im["id"] not in anns:
        continue
    content = (img.max(axis=2) >= 12).astype(np.uint8)
    content = cv2.morphologyEx(content, cv2.MORPH_CLOSE, np.ones((9, 9), np.uint8))  # salt & pepper
    ys, xs = np.nonzero(content)
    if xs.size == 0:
        continue
    x0, x1, y0, y1 = xs.min(), xs.max() + 1, ys.min(), ys.max() + 1
    rect = content[y0:y1, x0:x1].mean()          # 1.0 = upright rectangle, lower = rotated
    key = im["extra"]["name"]
    if key not in best or rect > best[key][0]:
        best[key] = (rect, im, (x0, y0, x1, y1))

rows = []
for key, (rect, im, (x0, y0, x1, y1)) in sorted(best.items()):
    if rect < 0.97:            # every copy of this frame is rotated → skip
        continue
    img = cv2.imread(str(src / im["file_name"]))
    mask = np.zeros(img.shape[:2], np.uint8)
    for a in anns[im["id"]]:
        for poly in a["segmentation"]:
            pts = np.array(poly, np.float32).reshape(-1, 2).round().astype(np.int32)
            cv2.fillPoly(mask, [pts], 255)
    img, mask = img[y0:y1, x0:x1], mask[y0:y1, x0:x1]
    stem = Path(key).stem
    cv2.imwrite(str(out / "images" / f"{stem}.jpg"), img, [cv2.IMWRITE_JPEG_QUALITY, 95])
    cv2.imwrite(str(out / "masks" / f"{stem}.png"), mask)
    video = re.sub(r"_mp4-\d+$", "", stem)
    rows.append({"stem": stem, "video": video, "width": img.shape[1], "height": img.shape[0]})

with open(out / "manifest.csv", "w", newline="") as f:
    w = csv.DictWriter(f, fieldnames=["stem", "video", "width", "height"])
    w.writeheader()
    w.writerows(rows)
from collections import Counter
print(len(rows), "frames from", len(set(r["video"] for r in rows)), "videos")
print(Counter(r["video"] for r in rows).most_common())
