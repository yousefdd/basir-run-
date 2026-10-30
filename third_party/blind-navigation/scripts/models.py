"""
ONNX Runtime wrappers for the Blind Navigation skill.

- YoloOnnx: YOLO26 ONNX detector. Output layouts handled:
    * onnx-community / DeepCamera export: logits [1,300,C] + pred_boxes [1,300,4] (cx,cy,w,h in 0..1)
    * ultralytics end-to-end export: [1,N,6] (x1,y1,x2,y2,score,class)
    * ultralytics raw head: [1,4+C,N] (cx,cy,w,h + class scores, needs NMS)
- DepthOnnx: Depth Anything v2 (onnx-community export) → relative disparity map

Both pick the best execution provider: CUDA → TensorRT → CoreML → DirectML → CPU.
"""

from __future__ import annotations

import ast
import json
import sys
from pathlib import Path

import numpy as np

SKILL_DIR = Path(__file__).resolve().parent.parent
REPO_SKILLS_DIR = SKILL_DIR.parent.parent
# Shared with the depth-estimation skill so the model is downloaded once
DEPTH_MODELS_DIR = Path.home() / ".aegis-ai" / "models" / "feature-extraction"
DEPTH_REPO = "onnx-community/depth-anything-v2-small"
DEPTH_FILES = {"fp32": "onnx/model.onnx", "int8": "onnx/model_quantized.onnx"}
# Mirror used when HuggingFace is unreachable (same Depth Anything V2 Small weights, fixed 518×518 input)
DEPTH_GITHUB_URL = ("https://github.com/fabio-sim/Depth-Anything-ONNX/releases/download/v2.0.0/"
                    "depth_anything_v2_vits.onnx")
DEPTH_GITHUB_SHA256 = "d2b11a11c1d4a12b47608fa65a17ee9a4c605b55ee1730c8e3b526304f2562be"

EP_PRIORITY = [
    ("CUDAExecutionProvider", "cuda"),
    ("TensorrtExecutionProvider", "tensorrt"),
    ("CoreMLExecutionProvider", "coreml"),
    ("DmlExecutionProvider", "directml"),
    ("CPUExecutionProvider", "cpu"),
]


def log(msg: str):
    print(f"[BlindNav] {msg}", file=sys.stderr, flush=True)


def make_session(model_path: str, device: str = "auto"):
    import onnxruntime as ort
    available = ort.get_available_providers()
    if device == "cpu":
        providers = ["CPUExecutionProvider"]
    else:
        providers = [ep for ep, _ in EP_PRIORITY if ep in available] or ["CPUExecutionProvider"]
    opts = ort.SessionOptions()
    opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    sess = ort.InferenceSession(model_path, sess_options=opts, providers=providers)
    active = sess.get_providers()[0]
    device_name = next((d for ep, d in EP_PRIORITY if ep == active), "cpu")
    return sess, device_name


def find_yolo_model(explicit: str = "") -> Path:
    candidates = [Path(explicit)] if explicit else []
    candidates += [
        SKILL_DIR / "models" / "yolo26n.onnx",
        REPO_SKILLS_DIR / "detection" / "yolo-detection-2026" / "yolo26n.onnx",
    ]
    for c in candidates:
        if c and c.is_file():
            return c
    raise FileNotFoundError("yolo26n.onnx not found — run deploy.sh or set yolo_model_path")


def find_depth_model(precision: str = "fp32", explicit: str = "") -> Path:
    if explicit:
        p = Path(explicit)
        if p.is_file():
            return p
        raise FileNotFoundError(f"Depth model not found: {explicit}")
    filename = DEPTH_FILES.get(precision, DEPTH_FILES["fp32"])
    local = DEPTH_MODELS_DIR / Path(filename).name
    if local.is_file():
        return local
    mirror = DEPTH_MODELS_DIR / Path(DEPTH_GITHUB_URL).name
    if mirror.is_file():
        return mirror
    try:
        from huggingface_hub import hf_hub_download
        log(f"Downloading {DEPTH_REPO}/{filename} ...")
        return Path(hf_hub_download(DEPTH_REPO, filename))
    except Exception as e:
        log(f"HuggingFace download failed ({e}); trying the GitHub mirror")
    return _download_verified(DEPTH_GITHUB_URL, mirror, DEPTH_GITHUB_SHA256)


def _download_verified(url: str, target: Path, sha256: str) -> Path:
    import hashlib
    import urllib.request
    target.parent.mkdir(parents=True, exist_ok=True)
    tmp = target.with_suffix(".part")
    log(f"Downloading {url} ...")
    digest = hashlib.sha256()
    with urllib.request.urlopen(url, timeout=120) as resp, open(tmp, "wb") as out:
        for chunk in iter(lambda: resp.read(1 << 20), b""):
            digest.update(chunk)
            out.write(chunk)
    if digest.hexdigest() != sha256:
        tmp.unlink(missing_ok=True)
        raise RuntimeError(f"Checksum mismatch for {url}")
    tmp.replace(target)
    return target


class YoloOnnx:
    def __init__(self, model_path: Path, confidence: float = 0.4, iou: float = 0.5, device: str = "auto"):
        model_path = Path(model_path)
        self.session, self.device = make_session(str(model_path), device)
        inp = self.session.get_inputs()[0]
        self.input_name = inp.name
        shape = inp.shape
        self.size = (shape[2] if isinstance(shape[2], int) else 640, shape[3] if isinstance(shape[3], int) else 640)
        self.confidence = confidence
        self.iou = iou
        self.names = self._load_names(model_path)

    def _load_names(self, model_path: Path) -> dict:
        meta = self.session.get_modelmeta().custom_metadata_map
        if "names" in meta:
            try:
                return {int(k): v for k, v in ast.literal_eval(meta["names"]).items()}
            except (ValueError, SyntaxError):
                pass
        names_file = model_path.with_name(model_path.stem + "_names.json")
        if names_file.is_file():
            return {int(k): v for k, v in json.loads(names_file.read_text()).items()}
        return {}

    def __call__(self, bgr: np.ndarray) -> list:
        import cv2
        h0, w0 = bgr.shape[:2]
        th, tw = self.size
        r = min(th / h0, tw / w0)
        nh, nw = int(round(h0 * r)), int(round(w0 * r))
        top, left = (th - nh) // 2, (tw - nw) // 2
        canvas = np.full((th, tw, 3), 114, dtype=np.uint8)
        canvas[top:top + nh, left:left + nw] = cv2.resize(bgr, (nw, nh), interpolation=cv2.INTER_LINEAR)
        x = cv2.cvtColor(canvas, cv2.COLOR_BGR2RGB).astype(np.float32).transpose(2, 0, 1)[None] / 255.0

        outputs = self.session.run(None, {self.input_name: x})
        out = np.squeeze(outputs[0], 0)
        if len(outputs) == 2 and outputs[1].shape[-1] == 4:
            # DETR-style head (the yolo26n.onnx shipped with DeepCamera): sigmoid logits + normalized boxes
            probs = 1.0 / (1.0 + np.exp(-out))
            classes = probs.argmax(1)
            scores = probs[np.arange(len(classes)), classes]
            keep = scores >= self.confidence
            cxcywh = np.squeeze(outputs[1], 0)[keep] * np.array([tw, th, tw, th], dtype=np.float32)
            scores, classes = scores[keep], classes[keep]
            boxes = np.stack([cxcywh[:, 0] - cxcywh[:, 2] / 2, cxcywh[:, 1] - cxcywh[:, 3] / 2,
                              cxcywh[:, 0] + cxcywh[:, 2] / 2, cxcywh[:, 1] + cxcywh[:, 3] / 2], 1)
        elif out.ndim == 2 and out.shape[1] == 6:
            # End-to-end (NMS-free) export: x1, y1, x2, y2, score, class
            boxes, scores, classes = out[:, :4], out[:, 4], out[:, 5].astype(int)
            keep = scores >= self.confidence
            boxes, scores, classes = boxes[keep], scores[keep], classes[keep]
        else:
            # Raw head: [4 + C, N] with cx, cy, w, h
            if out.shape[0] < out.shape[1]:
                out = out.T
            cls_scores = out[:, 4:]
            classes = cls_scores.argmax(1)
            scores = cls_scores[np.arange(len(classes)), classes]
            keep = scores >= self.confidence
            cxcywh, scores, classes = out[keep, :4], scores[keep], classes[keep]
            boxes = np.stack([cxcywh[:, 0] - cxcywh[:, 2] / 2, cxcywh[:, 1] - cxcywh[:, 3] / 2,
                              cxcywh[:, 0] + cxcywh[:, 2] / 2, cxcywh[:, 1] + cxcywh[:, 3] / 2], 1)
            if len(boxes):
                idx = cv2.dnn.NMSBoxes(
                    [[float(b[0]), float(b[1]), float(b[2] - b[0]), float(b[3] - b[1])] for b in boxes],
                    scores.tolist(), self.confidence, self.iou)
                idx = np.array(idx).reshape(-1)
                boxes, scores, classes = boxes[idx], scores[idx], classes[idx]

        objects = []
        for b, s, c in zip(boxes, scores, classes):
            x1 = float(np.clip((b[0] - left) / r, 0, w0))
            y1 = float(np.clip((b[1] - top) / r, 0, h0))
            x2 = float(np.clip((b[2] - left) / r, 0, w0))
            y2 = float(np.clip((b[3] - top) / r, 0, h0))
            if x2 - x1 < 2 or y2 - y1 < 2:
                continue
            objects.append({
                "class": self.names.get(int(c), str(int(c))),
                "confidence": round(float(s), 3),
                "bbox": [int(x1), int(y1), int(x2), int(y2)],
            })
        return objects


class DepthOnnx:
    MEAN = np.array([0.485, 0.456, 0.406], dtype=np.float32)
    STD = np.array([0.229, 0.224, 0.225], dtype=np.float32)

    def __init__(self, model_path: Path, input_size: int = 518, device: str = "auto"):
        self.session, self.device = make_session(str(model_path), device)
        inp = self.session.get_inputs()[0]
        self.input_name = inp.name
        shape = inp.shape
        # Fixed-size exports dictate the size; dynamic ones use a multiple of 14 (ViT patch)
        if isinstance(shape[2], int) and isinstance(shape[3], int):
            self.size = (shape[2], shape[3])
        else:
            s = max(14, int(round(input_size / 14)) * 14)
            self.size = (s, s)

    def __call__(self, bgr: np.ndarray) -> np.ndarray:
        """Relative disparity (larger = closer) at model resolution."""
        import cv2
        h, w = self.size
        rgb = cv2.cvtColor(cv2.resize(bgr, (w, h), interpolation=cv2.INTER_LINEAR), cv2.COLOR_BGR2RGB)
        x = ((rgb.astype(np.float32) / 255.0 - self.MEAN) / self.STD).transpose(2, 0, 1)[None]
        out = self.session.run(None, {self.input_name: x.astype(np.float32)})[0]
        return np.squeeze(out).astype(np.float32)
