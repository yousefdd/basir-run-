#!/usr/bin/env python3
"""
Blind Navigation Skill — obstacle detection and walking-path guidance for
blind and visually impaired users, for SharpAI Aegis or standalone.

Pipeline per frame:
  Depth Anything v2 (relative depth) → floor plane fit → obstacles / drops /
  head-level hazards → free distance per heading → action (forward, bear
  left/right, slow, stop) → short spoken guidance (Arabic or English).
  YOLO26 names the obstacles (person, car, bench …) and their distance.
  A PySceneDetect-style scene-change detector triggers an optional VLM
  description of the surroundings when the user reaches a new place.

Aegis mode (default) — JSON lines over stdin/stdout:
  stdin:  {"event": "frame", "frame_id": N, "camera_id": "...", "frame_path": "..."}
          {"command": "describe"} | {"command": "config-update", "config": {...}} | {"command": "stop"}
  stdout: {"event": "navigation", ...}  then  {"event": "detections", ...}

Standalone:
  python navigate.py --source 0 --speak            # webcam / phone stream, spoken Arabic
  python navigate.py --source walk.mp4 --show
  python navigate.py --image street.jpg --out street_nav.jpg
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import queue
import shutil
import signal
import subprocess
import sys
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

_script_dir = Path(__file__).resolve().parent
sys.path.insert(0, str(_script_dir))

from guidance import GuidanceEngine, describe_locally  # noqa: E402
from path_planner import CameraGeometry, PathPlanner, PlannerConfig  # noqa: E402
from scene_change import SceneChangeDetector  # noqa: E402

PERF_STATS_INTERVAL = 50
WORK_WIDTH = 160

DEFAULTS = {
    "language": "ar",
    "camera_height_m": 1.3,
    "camera_pitch_deg": "auto",       # degrees, or "auto" = estimate from each frame
    "camera_hfov_deg": 70.0,
    "body_width_m": 0.75,
    "safe_distance_m": 2.0,
    "stop_distance_m": 1.2,
    "lookahead_m": 5.0,
    "detect_drops": True,
    "detect_objects": True,
    "confidence": 0.4,
    "depth_precision": "fp32",
    "depth_input_size": 518,
    "device": "auto",
    "fps": 3,
    "describe_scenes": "on_change",   # off | on_change | on_request
    "emit_overlay": False,
    "local_tts": False,
    "yolo_model_path": "",
    "depth_model_path": "",
}

DESCRIBE_PROMPT = {
    "ar": ("أنت مساعد لشخص كفيف يمشي. صف المكان في هذه الصورة بجملتين قصيرتين بالعربية: "
           "نوع المكان، وأهم ما يحتاج معرفته للمشي بأمان (ممرات، أبواب، درج، سيارات، أشخاص). "
           "لا تذكر الألوان إلا إذا كانت مفيدة."),
    "en": ("You assist a blind person who is walking. Describe this place in two short sentences: "
           "what kind of place it is and what matters for walking safely (paths, doors, stairs, cars, people)."),
}


def emit(event: dict):
    print(json.dumps(event, ensure_ascii=False), flush=True)


def log(msg: str):
    print(f"[BlindNav] {msg}", file=sys.stderr, flush=True)


def _as_bool(v) -> bool:
    if isinstance(v, str):
        return v.strip().lower() in ("true", "1", "yes", "on")
    return bool(v)


def load_config(args) -> dict:
    cfg = dict(DEFAULTS)
    env_params = os.environ.get("AEGIS_SKILL_PARAMS")
    if env_params:
        try:
            cfg.update(json.loads(env_params))
        except json.JSONDecodeError:
            log("AEGIS_SKILL_PARAMS is not valid JSON — ignored")
    if args.config and Path(args.config).is_file():
        cfg.update(json.loads(Path(args.config).read_text()))
    for key in ("language", "device"):
        val = getattr(args, key, None)
        if val:
            cfg[key] = val
    if args.speak:
        cfg["local_tts"] = True
    return coerce_config(cfg)


def coerce_config(cfg: dict) -> dict:
    """Aegis passes every value as a string — normalize types in place."""
    for key in ("detect_drops", "detect_objects", "emit_overlay", "local_tts"):
        cfg[key] = _as_bool(cfg[key])
    pitch = cfg.get("camera_pitch_deg")
    cfg["camera_pitch_deg"] = None if pitch in (None, "", "auto") else float(pitch)
    for key in ("camera_height_m", "camera_hfov_deg", "body_width_m", "safe_distance_m",
                "stop_distance_m", "lookahead_m", "confidence", "fps"):
        cfg[key] = float(cfg[key])
    cfg["depth_input_size"] = int(cfg["depth_input_size"])
    return cfg


# ───────────────────────────────────────────────────────────────────────────────
# Speech (standalone / wearable use) — Aegis mode normally speaks via the app
# ───────────────────────────────────────────────────────────────────────────────

class Speaker:
    """Background TTS. Urgent messages interrupt; stale routine ones are dropped."""

    def __init__(self, lang: str = "ar"):
        self.lang = lang
        self.cmd = self._find_backend()
        self._q: queue.Queue = queue.Queue(maxsize=1)
        self._proc = None
        self._lock = threading.Lock()
        threading.Thread(target=self._run, daemon=True).start()
        log(f"TTS backend: {self.cmd[0] if self.cmd else 'none (text only)'}")

    def _find_backend(self):
        if sys.platform == "darwin" and shutil.which("say"):
            return ["say", "-v", "Majed"] if self.lang == "ar" else ["say"]
        for exe in ("espeak-ng", "espeak"):
            if shutil.which(exe):
                return [exe, "-v", "ar" if self.lang == "ar" else "en", "-s", "155"]
        return None

    def say(self, text: str, urgent: bool = False):
        if not text:
            return
        if not self.cmd:
            log(f"SAY: {text}")
            return
        if urgent:
            with self._lock:
                if self._proc and self._proc.poll() is None:
                    self._proc.terminate()
        try:
            self._q.get_nowait()
        except queue.Empty:
            pass
        self._q.put_nowait(text)

    def _run(self):
        while True:
            text = self._q.get()
            with self._lock:
                self._proc = subprocess.Popen(self.cmd + [text], stdout=subprocess.DEVNULL,
                                              stderr=subprocess.DEVNULL)
            self._proc.wait()


# ───────────────────────────────────────────────────────────────────────────────
# Scene description through the Aegis VLM (OpenAI-compatible endpoint)
# ───────────────────────────────────────────────────────────────────────────────

class SceneDescriber:
    def __init__(self, lang: str):
        self.base = os.environ.get("AEGIS_VLM_URL", "").rstrip("/")
        if self.base.endswith("/v1"):
            self.base = self.base[:-3]
        self.model = os.environ.get("AEGIS_VLM_MODEL", "")
        self.lang = lang
        self._busy = threading.Event()

    @property
    def available(self) -> bool:
        return bool(self.base)

    def describe_async(self, bgr, callback):
        if not self.available or self._busy.is_set():
            return False
        import cv2
        ok, jpg = cv2.imencode(".jpg", cv2.resize(bgr, (640, int(640 * bgr.shape[0] / bgr.shape[1]))),
                               [cv2.IMWRITE_JPEG_QUALITY, 80])
        if not ok:
            return False
        self._busy.set()
        threading.Thread(target=self._request, args=(base64.b64encode(jpg).decode(), callback), daemon=True).start()
        return True

    def describe_sync(self, bgr, lang: str | None = None) -> str:
        """Blocking VLM call (HTTP server mode). Raises on failure."""
        import cv2
        ok, jpg = cv2.imencode(".jpg", cv2.resize(bgr, (640, int(640 * bgr.shape[0] / bgr.shape[1]))),
                               [cv2.IMWRITE_JPEG_QUALITY, 80])
        if not ok:
            raise ValueError("JPEG encoding failed")
        return self._call(base64.b64encode(jpg).decode(), lang)

    def _request(self, b64: str, callback):
        try:
            callback(self._call(b64), None)
        except Exception as e:  # network / server errors must never stop navigation
            callback(None, str(e))
        finally:
            self._busy.clear()

    def _call(self, b64: str, lang: str | None = None) -> str:
        lang = lang or self.lang
        body = {
            "messages": [{"role": "user", "content": [
                {"type": "text", "text": DESCRIBE_PROMPT.get(lang, DESCRIBE_PROMPT["en"])},
                {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{b64}"}},
            ]}],
            "max_tokens": 120,
            "temperature": 0.2,
        }
        if self.model:
            body["model"] = self.model
        req = urllib.request.Request(f"{self.base}/v1/chat/completions", data=json.dumps(body).encode(),
                                     headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=60) as resp:
            return json.loads(resp.read())["choices"][0]["message"]["content"].strip()


# ───────────────────────────────────────────────────────────────────────────────
# Pipeline
# ───────────────────────────────────────────────────────────────────────────────

class UserSession:
    """Per-user state: guidance hysteresis / rate limits and the scene-change keyframe."""

    def __init__(self, lang: str):
        self.guidance = GuidanceEngine(lang=lang)
        self.scenes = SceneChangeDetector()
        self.last_seen = time.monotonic()
        self.last_frame = None
        self.last_result = None
        self.pending_description: queue.Queue = queue.Queue()


class NavigationPipeline:
    def __init__(self, cfg: dict):
        from models import DepthOnnx, YoloOnnx, find_depth_model, find_yolo_model
        self.cfg = cfg
        self.depth = DepthOnnx(find_depth_model(cfg["depth_precision"], cfg["depth_model_path"]),
                               cfg["depth_input_size"], cfg["device"])
        self.yolo = None
        if cfg["detect_objects"]:
            self.yolo = YoloOnnx(find_yolo_model(cfg["yolo_model_path"]), cfg["confidence"], device=cfg["device"])
        self.planner = PathPlanner()
        self.apply_config(cfg)
        self.session = UserSession(cfg["language"])
        self.describer = SceneDescriber(cfg["language"])
        self.descriptions: queue.Queue = queue.Queue()
        self.last_frame = None

    def apply_config(self, cfg: dict):
        self.cfg.update(cfg)
        c = self.cfg
        self.planner.geo = CameraGeometry(c["camera_height_m"], c["camera_pitch_deg"], c["camera_hfov_deg"])
        self.planner.cfg = PlannerConfig(
            body_width_m=c["body_width_m"], safe_distance_m=c["safe_distance_m"],
            stop_distance_m=c["stop_distance_m"], lookahead_m=c["lookahead_m"], detect_drops=c["detect_drops"])
        if hasattr(self, "session"):
            self.session.guidance.lang = c["language"]
            self.describer.lang = c["language"]

    def process(self, bgr, timings: dict | None = None, session: UserSession | None = None,
                pitch_deg: float | None = None) -> dict:
        """pitch_deg: live camera tilt from the phone's motion sensor (overrides the configured tilt)."""
        import cv2
        timings = timings if timings is not None else {}
        session = session or self.session
        geo = self.planner.geo
        if pitch_deg is not None:
            self.planner.geo = CameraGeometry(geo.height_m, float(pitch_deg), geo.hfov_deg)
        h0, w0 = bgr.shape[:2]
        work_h = max(16, int(round(WORK_WIDTH * h0 / w0)))

        t0 = time.perf_counter()
        disp = self.depth(bgr)
        disp = cv2.resize(disp, (WORK_WIDTH, work_h), interpolation=cv2.INTER_AREA)
        timings["depth"] = (time.perf_counter() - t0) * 1000

        t0 = time.perf_counter()
        objects = self.yolo(bgr) if self.yolo else []
        timings["detect"] = (time.perf_counter() - t0) * 1000

        t0 = time.perf_counter()
        try:
            analysis = self.planner.analyze(disp)
        finally:
            self.planner.geo = geo
        if pitch_deg is not None:
            analysis.pitch_source = "sensor"
        sx, sy = WORK_WIDTH / w0, work_h / h0
        for obj in objects:
            x1, y1, x2, y2 = obj["bbox"]
            box = (x1 * sx, y1 * sy, x2 * sx, y2 * sy)
            obj["distance_m"] = self.planner.box_distance(analysis, box)
            obj["bearing_deg"] = round(self.planner.bearing_deg((box[0] + box[2]) / 2, WORK_WIDTH), 1)
        result = analysis.to_dict()
        guide = session.guidance.update(result, objects)
        timings["plan"] = (time.perf_counter() - t0) * 1000

        self.last_frame = session.last_frame = bgr
        new_place = session.scenes.update(bgr)
        if new_place and self.cfg["describe_scenes"] == "on_change":
            self.request_description(bgr)

        out = {"analysis": analysis, "path": result, "objects": objects, "guidance": guide, "new_place": new_place}
        session.last_result = out
        return out

    def request_description(self, bgr=None) -> bool:
        bgr = bgr if bgr is not None else self.last_frame
        if bgr is None:
            return False
        return self.describer.describe_async(bgr, lambda text, err: self.descriptions.put((text, err)))

    def render_overlay(self, bgr, out: dict):
        """Debug/companion view: red = obstacles, green = walkable floor, yellow = drop."""
        import cv2
        import numpy as np
        a = out["analysis"]
        h0, w0 = bgr.shape[:2]
        vis = bgr.copy()
        if a.floor_valid:
            color = np.zeros_like(vis)
            for mask, c in ((a.floor_mask, (0, 180, 0)), (a.obstacle_mask, (0, 0, 255)), (a.drop_mask, (0, 220, 255))):
                m = cv2.resize(mask.astype(np.uint8), (w0, h0), interpolation=cv2.INTER_NEAREST).astype(bool)
                color[m] = c
            vis = cv2.addWeighted(vis, 1.0, color, 0.45, 0)
            # Arrow for the chosen heading
            f = self.planner.geo.focal_px(w0)
            x = int(w0 / 2 + f * np.tan(np.radians(a.target_heading_deg)))
            arrow_color = (0, 0, 255) if a.action in ("stop", "slow") else (0, 255, 0)
            cv2.arrowedLine(vis, (w0 // 2, h0 - 10), (x, int(h0 * 0.6)), arrow_color, 4, tipLength=0.15)
        for obj in out["objects"]:
            x1, y1, x2, y2 = obj["bbox"]
            cv2.rectangle(vis, (x1, y1), (x2, y2), (255, 200, 0), 2)
            d = obj.get("distance_m")
            label = f'{obj["class"]} {d:.1f}m' if d is not None else obj["class"]
            cv2.putText(vis, label, (x1, max(15, y1 - 5)), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (255, 200, 0), 2)
        clear = a.clear_distance_m
        status = f"{a.action}  clear={clear:.1f}m" if clear is not None else a.action
        cv2.putText(vis, status, (10, 28), cv2.FONT_HERSHEY_SIMPLEX, 0.8, (255, 255, 255), 2)
        return vis


# ───────────────────────────────────────────────────────────────────────────────
# Entry points
# ───────────────────────────────────────────────────────────────────────────────

class PerfTracker:
    def __init__(self):
        self.total_frames = 0
        self.errors = 0
        self._t: dict[str, list] = {}

    def add(self, timings: dict):
        for k, v in timings.items():
            self._t.setdefault(k, []).append(v)
        self.total_frames += 1
        if self.total_frames % PERF_STATS_INTERVAL == 0:
            self.flush()

    def flush(self):
        if not self._t:
            return
        stats = {}
        for k, vals in self._t.items():
            s = sorted(vals)
            stats[k] = {"avg": round(sum(s) / len(s), 2), "p50": round(s[len(s) // 2], 2),
                        "p95": round(s[int(len(s) * 0.95)], 2), "max": round(s[-1], 2)}
        emit({"event": "perf_stats", "total_frames": self.total_frames, "errors": self.errors, "timings_ms": stats})
        self._t.clear()


def navigation_event(out: dict, frame_id=None, camera_id=None, timestamp="") -> dict:
    return {
        "event": "navigation",
        "frame_id": frame_id,
        "camera_id": camera_id,
        "timestamp": timestamp,
        "guidance": out["guidance"],
        "path": out["path"],
        "new_place": out["new_place"],
    }


def run_aegis(pipe: NavigationPipeline, cfg: dict):
    import cv2
    perf = PerfTracker()
    speaker = Speaker(cfg["language"]) if cfg["local_tts"] else None

    def handle_signal(signum, frame):
        perf.flush()
        sys.exit(0)
    signal.signal(signal.SIGTERM, handle_signal)
    signal.signal(signal.SIGINT, handle_signal)

    def flush_descriptions():
        while not pipe.descriptions.empty():
            text, err = pipe.descriptions.get()
            if text:
                emit({"event": "scene_description", "text": text, "speak": True})
                if speaker:
                    speaker.say(text)
            else:
                log(f"Scene description failed: {err}")

    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            msg = json.loads(line)
        except json.JSONDecodeError:
            continue
        flush_descriptions()

        cmd = msg.get("command")
        if cmd == "stop":
            break
        if cmd == "config-update":
            pipe.apply_config(coerce_config({**pipe.cfg, **msg.get("config", {})}))
            emit({"event": "config_updated"})
            continue
        if cmd == "describe":
            if pipe.describer.available and pipe.request_description():
                continue
            last = pipe.session.last_result
            if last is None:
                emit({"event": "error", "message": "No frame analyzed yet", "retriable": True})
            else:
                emit({"event": "scene_description", "source": "local", "speak": True,
                      "text": describe_locally(last["path"], last["objects"], pipe.cfg["language"])})
            continue

        if msg.get("event") != "frame":
            continue
        frame_id, camera_id = msg.get("frame_id"), msg.get("camera_id", "unknown")
        frame_path = msg.get("frame_path")
        t_start = time.perf_counter()
        bgr = cv2.imread(frame_path) if frame_path and Path(frame_path).exists() else None
        if bgr is None:
            perf.errors += 1
            emit({"event": "error", "frame_id": frame_id, "message": f"Frame not readable: {frame_path}",
                  "retriable": True})
            continue
        try:
            timings = {}
            out = pipe.process(bgr, timings)
        except Exception as e:
            perf.errors += 1
            emit({"event": "error", "frame_id": frame_id, "message": f"Processing error: {e}", "retriable": True})
            continue

        g = out["guidance"]
        if speaker and g["speak"]:
            speaker.say(g["text"], urgent=g["priority"] >= 3)
        emit(navigation_event(out, frame_id, camera_id, msg.get("timestamp", "")))
        if cfg["emit_overlay"]:
            ok, jpg = cv2.imencode(".jpg", pipe.render_overlay(bgr, out), [cv2.IMWRITE_JPEG_QUALITY, 75])
            if ok:
                emit({"event": "transform", "frame_id": frame_id, "camera_id": camera_id,
                      "transform_data": base64.b64encode(jpg).decode()})
        emit({"event": "detections", "frame_id": frame_id, "camera_id": camera_id,
              "timestamp": msg.get("timestamp", ""), "objects": out["objects"]})
        timings["total"] = (time.perf_counter() - t_start) * 1000
        perf.add(timings)

    flush_descriptions()
    perf.flush()


def run_stream(pipe: NavigationPipeline, cfg: dict, source: str, show: bool):
    import cv2
    cap = cv2.VideoCapture(int(source) if source.isdigit() else source)
    if not cap.isOpened():
        log(f"Cannot open source: {source}")
        sys.exit(1)
    speaker = Speaker(cfg["language"]) if cfg["local_tts"] else None
    period = 1.0 / max(cfg["fps"], 0.1)
    is_file = not source.isdigit() and Path(source).is_file()
    src_fps = cap.get(cv2.CAP_PROP_FPS) or 30
    frame_idx = 0
    while True:
        t0 = time.monotonic()
        ok, bgr = cap.read()
        if not ok:
            break
        frame_idx += 1
        if is_file and frame_idx % max(1, int(round(src_fps * period))) != 0:
            continue  # sample video files at the processing rate
        out = pipe.process(bgr)
        g = out["guidance"]
        if g["speak"]:
            log(f"{out['path']['action']:>12}  {g['text']}")
            if speaker:
                speaker.say(g["text"], urgent=g["priority"] >= 3)
        while not pipe.descriptions.empty():
            text, err = pipe.descriptions.get()
            if text:
                log(f"PLACE: {text}")
                if speaker:
                    speaker.say(text)
        emit(navigation_event(out, frame_idx, source))
        if show:
            cv2.imshow("Blind Navigation", pipe.render_overlay(bgr, out))
            key = cv2.waitKey(1) & 0xFF
            if key == ord("q"):
                break
            if key == ord("d"):
                pipe.request_description(bgr)
        if not is_file:
            time.sleep(max(0.0, period - (time.monotonic() - t0)))
    cap.release()


def run_images(pipe: NavigationPipeline, paths: list, out_path: str | None):
    import cv2
    for i, p in enumerate(paths):
        bgr = cv2.imread(p)
        if bgr is None:
            log(f"Cannot read image: {p}")
            continue
        pipe.session = UserSession(pipe.cfg["language"])
        out = pipe.process(bgr)
        emit(navigation_event(out, i, p) | {"objects": out["objects"]})
        if out_path:
            target = out_path if len(paths) == 1 else str(Path(out_path).with_stem(f"{Path(out_path).stem}_{i}"))
            cv2.imwrite(target, pipe.render_overlay(bgr, out))
            log(f"Overlay written to {target}")


# ───────────────────────────────────────────────────────────────────────────────
# HTTP API for mobile apps: the phone posts a camera frame, gets guidance back
# ───────────────────────────────────────────────────────────────────────────────

SESSION_IDLE_S = 600


def run_server(pipe: NavigationPipeline, host: str, port: int, token: str = ""):
    """
    POST /analyze?session=<id>&pitch=<deg>&lang=ar   body: JPEG bytes (or JSON {"image": "<base64>"})
    POST /describe?session=<id>                      body: optional JPEG; describes the surroundings
    GET  /health
    """
    import cv2
    import numpy as np

    lock = threading.Lock()
    sessions: dict[str, UserSession] = {}

    def get_session(sid: str, lang: str | None) -> UserSession:
        now = time.monotonic()
        for k in [k for k, s in sessions.items() if now - s.last_seen > SESSION_IDLE_S]:
            del sessions[k]
        sess = sessions.get(sid) or sessions.setdefault(sid, UserSession(lang or pipe.cfg["language"]))
        if lang in ("ar", "en"):
            sess.guidance.lang = lang
        sess.last_seen = now
        return sess

    def describe(frame, result, lang: str) -> dict:
        """VLM description when configured, else the offline summary. Called outside the lock."""
        if pipe.describer.available and frame is not None:
            try:
                return {"text": pipe.describer.describe_sync(frame, lang), "source": "vlm"}
            except Exception as e:
                log(f"VLM description failed, using local summary: {e}")
        return {"text": describe_locally(result["path"], result["objects"], lang), "source": "local"}

    class Handler(BaseHTTPRequestHandler):
        server_version = "BlindNavigation/1.0"

        def log_message(self, fmt, *args):
            log("HTTP " + fmt % args)

        def _send(self, code: int, payload: dict):
            body = json.dumps(payload, ensure_ascii=False).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(body)

        def do_OPTIONS(self):
            self.send_response(204)
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            self.send_header("Access-Control-Allow-Headers", "Content-Type, Authorization")
            self.end_headers()

        def _authorized(self) -> bool:
            if token and self.headers.get("Authorization", "") != f"Bearer {token}":
                self._send(401, {"error": "unauthorized"})
                return False
            return True

        def do_GET(self):
            if urlparse(self.path).path == "/health":
                self._send(200, {"status": "ok", "device": pipe.depth.device, "objects": pipe.yolo is not None,
                                 "vlm": pipe.describer.available, "language": pipe.cfg["language"]})
            else:
                self._send(404, {"error": "not found"})

        def _read_image(self):
            length = int(self.headers.get("Content-Length") or 0)
            if length <= 0:
                return None
            if length > 20 * 1024 * 1024:
                raise ValueError("image too large (max 20 MB)")
            raw = self.rfile.read(length)
            if self.headers.get("Content-Type", "").startswith("application/json"):
                raw = base64.b64decode(json.loads(raw)["image"].split(",")[-1])
            bgr = cv2.imdecode(np.frombuffer(raw, np.uint8), cv2.IMREAD_COLOR)
            if bgr is None:
                raise ValueError("body is not a decodable image")
            return bgr

        def do_POST(self):
            if not self._authorized():
                return
            url = urlparse(self.path)
            q = {k: v[0] for k, v in parse_qs(url.query).items()}
            lang = q.get("lang")
            try:
                bgr = self._read_image()
                pitch = float(q["pitch"]) if "pitch" in q else None
            except (ValueError, KeyError) as e:
                self._send(400, {"error": str(e)})
                return
            with lock:
                sess = get_session(q.get("session", "default"), lang)
                lang = sess.guidance.lang
                if url.path == "/analyze":
                    if bgr is None:
                        self._send(400, {"error": "POST a JPEG/PNG frame as the request body"})
                        return
                    timings: dict = {}
                    t0 = time.perf_counter()
                    try:
                        out = pipe.process(bgr, timings, session=sess, pitch_deg=pitch)
                    except Exception as e:
                        self._send(500, {"error": f"processing failed: {e}"})
                        return
                    timings["total"] = (time.perf_counter() - t0) * 1000
                    resp = {"guidance": out["guidance"], "path": out["path"], "objects": out["objects"],
                            "new_place": out["new_place"], "description": None,
                            "timings_ms": {k: round(v, 1) for k, v in timings.items()}}
                    if out["new_place"] and pipe.cfg["describe_scenes"] == "on_change":
                        if not pipe.describer.available:
                            resp["description"] = describe(None, out, lang)
                        else:
                            pipe.describer.describe_async(
                                bgr, lambda text, err, s=sess: s.pending_description.put(text) if text else None)
                    if resp["description"] is None and not sess.pending_description.empty():
                        resp["description"] = {"text": sess.pending_description.get(), "source": "vlm"}
                    self._send(200, resp)
                elif url.path == "/describe":
                    if bgr is not None:
                        pipe.process(bgr, session=sess, pitch_deg=pitch)
                    frame, result = sess.last_frame, sess.last_result
                else:
                    self._send(404, {"error": "not found"})
                    return
            if url.path == "/describe":
                if result is None:
                    self._send(400, {"error": "send a frame first (body or a previous /analyze)"})
                else:
                    self._send(200, describe(frame, result, lang))

    httpd = ThreadingHTTPServer((host, port), Handler)
    emit({"event": "serving", "host": host, "port": port, "auth": bool(token)})
    log(f"HTTP API on http://{host}:{port}  (POST /analyze, POST /describe, GET /health)")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        httpd.server_close()


def parse_args():
    p = argparse.ArgumentParser(description="Blind Navigation — obstacles and walking path guidance")
    p.add_argument("--config", type=str, help="Path to config JSON file")
    p.add_argument("--language", choices=["ar", "en"])
    p.add_argument("--device", choices=["auto", "cpu", "cuda"])
    p.add_argument("--source", type=str, help="Camera index, video file or RTSP/HTTP stream (standalone mode)")
    p.add_argument("--image", nargs="+", help="Analyze image file(s) and exit")
    p.add_argument("--out", type=str, help="Write overlay image (with --image)")
    p.add_argument("--speak", action="store_true", help="Speak guidance with the local TTS (espeak-ng / say)")
    p.add_argument("--show", action="store_true", help="Show the overlay window (standalone mode)")
    p.add_argument("--serve", type=int, metavar="PORT", help="Run the HTTP API for mobile apps on this port")
    p.add_argument("--host", default="0.0.0.0", help="HTTP API bind address (default: all interfaces)")
    p.add_argument("--token", default=os.environ.get("BLIND_NAV_TOKEN", ""),
                   help="Require 'Authorization: Bearer <token>' (or set BLIND_NAV_TOKEN)")
    p.add_argument("--vlm-url", help="OpenAI-compatible VLM base URL for scene descriptions (default: AEGIS_VLM_URL)")
    p.add_argument("--vlm-model", help="VLM model name (default: AEGIS_VLM_MODEL)")
    return p.parse_args()


def main():
    args = parse_args()
    if args.vlm_url:
        os.environ["AEGIS_VLM_URL"] = args.vlm_url
    if args.vlm_model:
        os.environ["AEGIS_VLM_MODEL"] = args.vlm_model
    cfg = load_config(args)
    emit({"event": "progress", "stage": "model", "message": "Loading depth and detection models..."})
    try:
        pipe = NavigationPipeline(cfg)
    except Exception as e:
        emit({"event": "error", "message": f"Failed to load models: {e}", "retriable": False})
        sys.exit(1)
    emit({
        "event": "ready",
        "model": "depth-anything-v2-small" + (" + yolo26n" if pipe.yolo else ""),
        "device": pipe.depth.device,
        "language": cfg["language"],
        "fps": cfg["fps"],
        "scene_description": pipe.describer.available and cfg["describe_scenes"] != "off",
    })
    if args.image:
        run_images(pipe, args.image, args.out)
    elif args.serve:
        run_server(pipe, args.host, args.serve, args.token)
    elif args.source is not None:
        run_stream(pipe, cfg, args.source, args.show)
    else:
        run_aegis(pipe, cfg)


if __name__ == "__main__":
    main()
