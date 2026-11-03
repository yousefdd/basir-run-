"""
Prepares one replay clip for tools/replay (ReplayMain) from a folder of upright RGB frames.

Rebuilt for this round: the original BasirRun_Research/benchmarks/bench_models.py is not part of the engine zip, so this
re-implements it from what ReplayMain reads and from how the app itself feeds each model (app_vision/seg/SurfaceModels.kt,
app_vision/depth/OnnxModels.kt, app_vision/depth/DeepChannel.kt, app_vision/GuidancePipeline.kt):

  frames.yuv        I420, width x height per frame
  seg_labels.bin    uint8 ADE20K label per cell of SegFormer-B0's 64x64 output (input 256x256, ImageNet norm)
  line_prob.bin     float32 guide-line probability, Project Guideline model at 513x513 (uint8 NHWC), box-averaged to
                    LineEngine.WIDTH (240) x proportional height, as GuidelineSegmenter.line()
  disparity.bin     float32 Depth Anything V2 Small at 252x252, bilinear to DeepChannel.WORK_WIDTH (160) x proportional height
  detections.jsonl  D-FINE-N, letterboxed to 640, sigmoid >= 0.4, boxes as fractions: [label, score, l, t, r, b]
  meta.json

Usage: bench_models.py <frames_dir with 000000.jpg ...> <out_dir> --fps 15 --hfov 58.4 [--height 1.3] [--pitch DEG]
"""
import argparse, glob, json, os
import numpy as np, cv2, onnxruntime as ort

MODELS = os.path.join(os.path.dirname(__file__), "..", "..", "BasirRun_Detection_Engine_0.14.0", "models")
MEAN = np.array([0.485, 0.456, 0.406], np.float32); STD = np.array([0.229, 0.224, 0.225], np.float32)


def session(name):
    p = os.path.join(MODELS, name)
    so = ort.SessionOptions(); so.intra_op_num_threads = 4
    try:
        return ort.InferenceSession(p, so, providers=["CPUExecutionProvider"])
    except Exception:
        so.add_session_config_entry("session.disable_quant_qdq", "1")
        return ort.InferenceSession(p, so, providers=["CPUExecutionProvider"])


def box_average(src, ow, oh):
    h, w = src.shape; out = np.zeros((oh, ow), np.float32)
    for y in range(oh):
        y0 = y * h // oh; y1 = max(y0 + 1, (y + 1) * h // oh)
        for x in range(ow):
            x0 = x * w // ow; x1 = max(x0 + 1, (x + 1) * w // ow)
            out[y, x] = src[y0:y1, x0:x1].mean()
    return out


def box_average_fast(src, ow, oh):
    # Same integer cell bounds as box_average (and the app's average()), vectorised with an integral image.
    h, w = src.shape
    ii = np.zeros((h + 1, w + 1), np.float64); ii[1:, 1:] = src.cumsum(0).cumsum(1)
    ys = np.arange(oh); y0 = ys * h // oh; y1 = np.maximum(y0 + 1, (ys + 1) * h // oh)
    xs = np.arange(ow); x0 = xs * w // ow; x1 = np.maximum(x0 + 1, (xs + 1) * w // ow)
    s = ii[y1][:, x1] - ii[y0][:, x1] - ii[y1][:, x0] + ii[y0][:, x0]
    return (s / ((y1 - y0)[:, None] * (x1 - x0)[None, :])).astype(np.float32)


def bilinear(src, ow, oh):
    h, w = src.shape
    sy = np.clip((np.arange(oh) + 0.5) * h / oh - 0.5, 0, h - 1); sx = np.clip((np.arange(ow) + 0.5) * w / ow - 0.5, 0, w - 1)
    y0 = sy.astype(int); x0 = sx.astype(int); y1 = np.minimum(y0 + 1, h - 1); x1 = np.minimum(x0 + 1, w - 1)
    fy = (sy - y0)[:, None]; fx = (sx - x0)[None, :]
    top = src[y0][:, x0] * (1 - fx) + src[y0][:, x1] * fx; bot = src[y1][:, x0] * (1 - fx) + src[y1][:, x1] * fx
    return (top * (1 - fy) + bot * fy).astype(np.float32)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("frames"); ap.add_argument("out")
    ap.add_argument("--fps", type=int, required=True); ap.add_argument("--hfov", type=float, required=True)
    ap.add_argument("--height", type=float, default=1.3); ap.add_argument("--pitch", type=float, default=None)
    ap.add_argument("--roll", type=float, default=0.0)
    a = ap.parse_args()
    files = sorted(glob.glob(os.path.join(a.frames, "*.jpg")) + glob.glob(os.path.join(a.frames, "*.png")))
    os.makedirs(a.out, exist_ok=True)
    first = cv2.imread(files[0]); h, w = first.shape[:2]
    w -= w % 2; h -= h % 2
    lineW = 240; lineH = int(h / (w / lineW)); depthW = 160; depthH = int(round(depthW * h / w))
    seg, line, depth, det = session("walkable_segformer_b0_ade_256.onnx"), session("guideline_segmentation.onnx"), \
        session("depth_anything_v2_small.onnx"), session("dfine_n_coco.onnx")
    names = json.load(open(os.path.join(MODELS, "coco80_names.json")))
    f_yuv = open(os.path.join(a.out, "frames.yuv"), "wb"); f_seg = open(os.path.join(a.out, "seg_labels.bin"), "wb")
    f_line = open(os.path.join(a.out, "line_prob.bin"), "wb"); f_disp = open(os.path.join(a.out, "disparity.bin"), "wb")
    f_det = open(os.path.join(a.out, "detections.jsonl"), "w")
    segW = segH = 64
    for fp in files:
        bgr = cv2.imread(fp)[:h, :w]; rgb = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)
        f_yuv.write(cv2.cvtColor(bgr, cv2.COLOR_BGR2YUV_I420).tobytes())
        # SegFormer
        x = (cv2.resize(rgb, (256, 256), interpolation=cv2.INTER_LINEAR).astype(np.float32) / 255 - MEAN) / STD
        logits = seg.run(None, {"pixel_values": x.transpose(2, 0, 1)[None]})[0][0]
        lab = logits.argmax(0).astype(np.uint8); segH, segW = lab.shape
        f_seg.write(lab.tobytes())
        # Guideline
        g = cv2.resize(rgb, (513, 513), interpolation=cv2.INTER_LINEAR)[None]
        prob = line.run(None, {"PreprocessedImageTensor": g})[0][0, :, :, 1]
        f_line.write(box_average_fast(prob, lineW, lineH).astype("<f4").tobytes())
        # Depth Anything V2 Small at 252
        x = (cv2.resize(rgb, (252, 252), interpolation=cv2.INTER_LINEAR).astype(np.float32) / 255 - MEAN) / STD
        d = depth.run(None, {"pixel_values": x.transpose(2, 0, 1)[None]})[0][0]
        f_disp.write(bilinear(d, depthW, depthH).astype("<f4").tobytes())
        # D-FINE-N, letterbox 640
        r = min(640 / h, 640 / w); nw, nh = round(w * r), round(h * r); left, top = (640 - nw) // 2, (640 - nh) // 2
        sq = np.zeros((640, 640, 3), np.uint8); sq[top:top + nh, left:left + nw] = cv2.resize(rgb, (nw, nh), interpolation=cv2.INTER_LINEAR)
        lg, bx = det.run(None, {"pixel_values": (sq.astype(np.float32) / 255).transpose(2, 0, 1)[None]})
        lg = lg[0]; bx = bx[0]; out = []
        best = lg.argmax(1); sc = 1 / (1 + np.exp(-lg[np.arange(len(best)), best]))
        for i in np.nonzero(sc >= 0.4)[0]:
            cx, cy, bw, bh = bx[i] * 640
            x1 = np.clip((cx - bw / 2 - left) / r, 0, w); y1 = np.clip((cy - bh / 2 - top) / r, 0, h)
            x2 = np.clip((cx + bw / 2 - left) / r, 0, w); y2 = np.clip((cy + bh / 2 - top) / r, 0, h)
            if x2 - x1 < 2 or y2 - y1 < 2: continue
            out.append([names.get(str(int(best[i])), str(int(best[i]))), float(sc[i]), float(x1 / w), float(y1 / h), float(x2 / w), float(y2 / h)])
        f_det.write(json.dumps(out) + "\n")
    for f in (f_yuv, f_seg, f_line, f_disp, f_det): f.close()
    json.dump({"width": w, "height": h, "frames": len(files), "fps": a.fps, "hfovDeg": a.hfov, "rollDeg": a.roll,
               "heightM": a.height, "pitchDeg": a.pitch, "depthW": depthW, "depthH": depthH, "segW": int(segW), "segH": int(segH),
               "lineW": lineW, "lineH": lineH}, open(os.path.join(a.out, "meta.json"), "w"), indent=1)


if __name__ == "__main__":
    main()
