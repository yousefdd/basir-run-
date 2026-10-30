"""
Tests for the Blind Navigation skill — no models or network needed.

Scenes are ray-cast with known geometry (tests/synthetic.py), so distances
and decisions can be checked against ground truth.

    python -m pytest tests/ -v      (or: python tests/test_blind_navigation.py)
"""

import base64
import json
import os
import subprocess
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
SCRIPTS = HERE.parent / "scripts"
sys.path[:0] = [str(SCRIPTS), str(HERE)]

from guidance import GuidanceEngine, distance_phrase  # noqa: E402
from path_planner import CameraGeometry, PathPlanner  # noqa: E402
from scene_change import SceneChangeDetector  # noqa: E402
from synthetic import render_disparity  # noqa: E402


def analyze(**scene):
    return PathPlanner().analyze(render_disparity(**scene))


class PlannerTests(unittest.TestCase):
    def test_empty_floor_is_clear(self):
        a = analyze()
        self.assertTrue(a.floor_valid)
        self.assertEqual(a.action, "forward")
        self.assertGreaterEqual(a.clear_distance_m, 4.9)
        self.assertIsNone(a.nearest_obstacle)

    def test_obstacle_distance_matches_geometry(self):
        a = analyze(boxes=[(-0.3, 0.3, 0, 1.0, 1.5, 1.9)])
        self.assertAlmostEqual(a.clear_distance_m, 1.5, delta=0.15)
        self.assertAlmostEqual(a.nearest_obstacle["distance_m"], 1.5, delta=0.15)
        self.assertEqual(a.action, "slow")

    def test_affine_invariant(self):
        """Monocular depth has unknown scale/shift — results must not depend on it."""
        box = [(-0.3, 0.3, 0, 1.0, 2.5, 2.9)]
        a = analyze(boxes=box)
        b = analyze(boxes=box, scale=37.0, shift=4.0)
        self.assertEqual(a.action, b.action)
        self.assertAlmostEqual(a.clear_distance_m, b.clear_distance_m, delta=0.05)

    def test_obstacle_on_left_keeps_straight_path(self):
        a = analyze(boxes=[(-1.2, -0.45, 0, 1.0, 1.5, 1.9)])
        self.assertEqual(a.action, "forward")
        self.assertIsNotNone(a.sectors["left"])
        self.assertIsNone(a.sectors["right"])

    def test_detour_around_narrow_obstacle(self):
        a = analyze(boxes=[(-0.45, 0.15, 0, 1.0, 1.8, 2.1)])
        self.assertIn(a.action, ("slight_right", "right"))
        self.assertGreater(a.target_heading_deg, 0)

    def test_close_obstacle_stops(self):
        a = analyze(boxes=[(-0.35, 0.35, 0, 1.5, 1.0, 1.3)])
        self.assertTrue(a.floor_valid)
        self.assertEqual(a.action, "stop")

    def test_blocked_view_is_not_reported_clear(self):
        a = analyze(boxes=[(-5, 5, 0, 3, 0.6, 0.8)])
        self.assertIn(a.action, ("stop", "caution"))

    def test_head_level_obstacle(self):
        a = analyze(boxes=[(-0.5, 0.5, 1.3, 1.9, 1.5, 1.7)])
        self.assertIsNotNone(a.head_level)
        self.assertAlmostEqual(a.head_level["distance_m"], 1.5, delta=0.2)

    def test_ceiling_level_ignored(self):
        a = analyze(boxes=[(-0.5, 0.5, 2.3, 2.6, 1.5, 1.7)])
        self.assertIsNone(a.head_level)
        self.assertEqual(a.action, "forward")

    def test_drop_ahead_is_never_reported_clear(self):
        """A deep drop looks like 'near floor raised above the real floor' → stop/slow, or a drop warning."""
        for depth in (0.25, 0.5, 1.0):
            a = analyze(holes=[(-1.0, 1.0, 1.6, 3.5)], hole_depth=depth)
            self.assertTrue(a.drop is not None or a.action in ("stop", "slow"), (depth, a.action))

    def test_table_top_is_not_mistaken_for_floor(self):
        """Regression from real photos: a table right in front was fitted as the floor → 'path clear'."""
        for pitch in (20.0, None):
            a = PathPlanner(CameraGeometry(pitch_deg=pitch)).analyze(
                render_disparity(boxes=[(-0.8, 0.8, 0.70, 0.75, 0.3, 1.6)]))
            self.assertEqual(a.action, "stop")
            self.assertAlmostEqual(a.nearest_obstacle["height_m"], 0.75, delta=0.1)

    def test_low_table_ahead(self):
        a = analyze(boxes=[(-0.6, 0.6, 0.40, 0.45, 0.9, 1.8)])
        self.assertIn(a.action, ("stop", "slow"))

    def test_raised_surface_beside_path(self):
        a = analyze(boxes=[(-2.0, -0.5, 0.0, 0.5, 0.5, 2.5)])
        self.assertEqual(a.action, "forward")
        self.assertIsNotNone(a.sectors["left"])

    def test_auto_pitch_recovers_tilt(self):
        for pitch in (5.0, 12.0, 30.0):
            a = PathPlanner(CameraGeometry(pitch_deg=None)).analyze(
                render_disparity(pitch_deg=pitch, boxes=[(-0.3, 0.3, 0, 1.0, 2.5, 2.9)]))
            self.assertEqual(a.pitch_source, "image")
            self.assertAlmostEqual(a.pitch_deg, pitch, delta=0.5)
            self.assertAlmostEqual(a.clear_distance_m, 2.5, delta=0.15)

    def test_no_false_drop_on_flat_floor(self):
        self.assertIsNone(analyze().drop)

    def test_box_distance_for_detection(self):
        planner = PathPlanner()
        a = planner.analyze(render_disparity(boxes=[(-0.3, 0.3, 0, 1.0, 2.0, 2.4)]))
        cols = np.where(a.obstacle_mask.any(0))[0]
        rows = np.where(a.obstacle_mask.any(1))[0]
        d = planner.box_distance(a, (cols[0], rows[0], cols[-1] + 1, rows[-1] + 1))
        self.assertAlmostEqual(d, 2.0, delta=0.25)

    def test_serializable(self):
        json.dumps(analyze(boxes=[(-0.3, 0.3, 0, 1.0, 1.5, 1.9)]).to_dict())


class GuidanceTests(unittest.TestCase):
    def test_arabic_distances(self):
        self.assertEqual(distance_phrase(0.6), "أقل من متر")
        self.assertEqual(distance_phrase(1.0), "متر")
        self.assertEqual(distance_phrase(1.6), "متر ونصف")
        self.assertEqual(distance_phrase(2.1), "مترين")
        self.assertEqual(distance_phrase(3.4), "3 أمتار ونصف")
        self.assertEqual(distance_phrase(2.0, "en"), "2 meters")

    def test_stop_is_immediate_and_repeats(self):
        g = GuidanceEngine()
        out = g.update({"action": "stop"}, [], now=0.0)
        self.assertTrue(out["speak"])
        self.assertEqual(out["priority"], 3)
        self.assertFalse(g.update({"action": "stop"}, [], now=1.0)["speak"])
        self.assertTrue(g.update({"action": "stop"}, [], now=2.5)["speak"])

    def test_direction_needs_confirmation(self):
        g = GuidanceEngine(confirm_frames=2)
        g.update({"action": "forward"}, [], now=0.0)
        self.assertEqual(g.update({"action": "slight_left"}, [], now=0.3)["action"], "forward")
        out = g.update({"action": "slight_left"}, [], now=0.6)
        self.assertEqual(out["action"], "slight_left")
        self.assertTrue(out["speak"])
        self.assertIn("اليسار", out["text"])

    def test_vehicle_warning(self):
        g = GuidanceEngine()
        out = g.update({"action": "forward"}, [{"class": "car", "distance_m": 4.0, "bearing_deg": 20}], now=0.0)
        self.assertIn("سيارة", out["text"])
        self.assertIn("يمينك", out["text"])

    def test_object_named_in_detour(self):
        g = GuidanceEngine(confirm_frames=1)
        out = g.update({"action": "slight_right"}, [{"class": "person", "distance_m": 2.0, "bearing_deg": 0}], now=0)
        self.assertTrue(out["text"].startswith("شخص أمامك على بعد مترين"))

    def test_english(self):
        out = GuidanceEngine(lang="en").update({"action": "stop"}, [], now=0)
        self.assertIn("Stop", out["text"])


class SceneChangeTests(unittest.TestCase):
    def test_new_place_fires_after_persistence(self):
        rng = np.random.default_rng(0)
        indoor = np.full((120, 160, 3), (40, 120, 200), np.uint8)
        outdoor = np.full((120, 160, 3), (200, 160, 60), np.uint8)
        det = SceneChangeDetector(min_frames=3, min_interval_s=0)
        self.assertTrue(det.update(indoor, now=0))  # first frame describes the place
        for t in range(1, 5):
            noisy = np.clip(indoor + rng.integers(-8, 8, indoor.shape), 0, 255).astype(np.uint8)
            self.assertFalse(det.update(noisy, now=t))
        fired = [det.update(outdoor, now=10 + t) for t in range(4)]
        self.assertEqual(fired, [False, False, True, False])

    def test_min_interval(self):
        a = np.zeros((120, 160, 3), np.uint8)
        b = np.full((120, 160, 3), 255, np.uint8)
        det = SceneChangeDetector(min_frames=1, min_interval_s=20)
        det.update(a, now=0)
        self.assertFalse(det.update(b, now=5))
        self.assertTrue(det.update(b, now=25))


def write_stand_in_models(tmp: Path):
    """Tiny stand-in 'models' module: depth returns a synthetic scene (box 1.5 m ahead), YOLO a person on it."""
    disp = render_disparity(width=320, height=240, boxes=[(-0.3, 0.3, 0, 1.0, 1.5, 1.9)])
    np.save(tmp / "disp.npy", disp)
    (tmp / "models.py").write_text(textwrap.dedent(f"""
        import numpy as np
        class DepthOnnx:
            device = "cpu"
            def __init__(self, *a, **k): self.d = np.load(r"{tmp / 'disp.npy'}")
            def __call__(self, bgr): return self.d
        class YoloOnnx:
            def __init__(self, *a, **k): pass
            def __call__(self, bgr): return [{{"class": "person", "confidence": 0.9, "bbox": [140, 60, 180, 200]}}]
        def find_depth_model(*a): return "fake"
        def find_yolo_model(*a): return "fake"
    """))


def start_navigate(tmp: Path, args: list, **popen_kw):
    runner = tmp / "run.py"
    runner.write_text(f"import sys; sys.path.insert(0, r'{tmp}'); import models; sys.argv=['navigate.py'] + {args!r};"
                      f"import runpy; runpy.run_path(r'{SCRIPTS / 'navigate.py'}', run_name='__main__')")
    env = dict(os.environ, PYTHONPATH=str(tmp))
    env.pop("AEGIS_VLM_URL", None)
    env.update(popen_kw.pop("env", {}))
    return subprocess.Popen([sys.executable, str(runner)], env=env, text=True, **popen_kw)


class AegisProtocolTest(unittest.TestCase):
    """Run navigate.py as Aegis would, with tiny stand-in models (no downloads)."""

    def test_frame_round_trip(self):
        import cv2
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            frame = tmp / "frame.jpg"
            cv2.imwrite(str(frame), np.full((240, 320, 3), 128, np.uint8))
            write_stand_in_models(tmp)
            stdin = "\n".join([
                json.dumps({"event": "frame", "frame_id": 1, "camera_id": "chest", "frame_path": str(frame)}),
                json.dumps({"event": "frame", "frame_id": 2, "camera_id": "chest", "frame_path": "/nope.jpg"}),
                json.dumps({"command": "config-update", "config": {"language": "en", "safe_distance_m": "1.2"}}),
                json.dumps({"event": "frame", "frame_id": 3, "camera_id": "chest", "frame_path": str(frame)}),
                json.dumps({"command": "stop"}),
            ]) + "\n"
            proc = start_navigate(tmp, [], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                  env={"AEGIS_SKILL_PARAMS": json.dumps({"emit_overlay": "true"})})
            stdout, stderr = proc.communicate(stdin, timeout=60)
            proc = subprocess.CompletedProcess(proc.args, proc.returncode, stdout, stderr)
            self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
            events = [json.loads(line) for line in proc.stdout.splitlines() if line.startswith("{")]
            kinds = [e["event"] for e in events]
            self.assertEqual(kinds[:2], ["progress", "ready"])

            nav = [e for e in events if e["event"] == "navigation"]
            self.assertEqual([e["frame_id"] for e in nav], [1, 3])
            self.assertEqual(nav[0]["path"]["action"], "slow")
            self.assertTrue(nav[0]["guidance"]["speak"])
            self.assertIn("شخص", nav[0]["guidance"]["text"])
            # After config-update: English, and 1.5 m of room is now enough to walk on
            self.assertEqual(nav[1]["path"]["action"], "forward")

            dets = [e for e in events if e["event"] == "detections"]
            self.assertEqual(len(dets), 2)
            self.assertAlmostEqual(dets[0]["objects"][0]["distance_m"], 1.5, delta=0.3)
            self.assertTrue(any(e["event"] == "transform" for e in events))
            self.assertTrue(any(e["event"] == "error" and e.get("frame_id") == 2 for e in events))
            # Every frame event ends with a detections event (Aegis request/response)
            frame_ends = [e["event"] for e in events if e["event"] in ("detections", "error")]
            self.assertEqual(len(frame_ends), 3)


class HttpApiTest(unittest.TestCase):
    """The mobile-app API: POST a frame, get guidance back."""

    def test_analyze_and_describe(self):
        import socket
        import urllib.error
        import urllib.request

        import cv2
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            port = sock.getsockname()[1]
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            write_stand_in_models(tmp)
            proc = start_navigate(tmp, ["--serve", str(port), "--host", "127.0.0.1", "--token", "s3cret"],
                                  stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
            try:
                self.assertIn('"serving"', proc.stdout.readline() + proc.stdout.readline() + proc.stdout.readline())
                base = f"http://127.0.0.1:{port}"
                ok, jpg = cv2.imencode(".jpg", np.full((240, 320, 3), 128, np.uint8))

                def post(path, body, token="s3cret", ctype="image/jpeg"):
                    req = urllib.request.Request(base + path, data=body, method="POST",
                                                 headers={"Content-Type": ctype, "Authorization": f"Bearer {token}"})
                    with urllib.request.urlopen(req, timeout=30) as r:
                        return json.loads(r.read())

                with urllib.request.urlopen(base + "/health", timeout=10) as r:
                    self.assertEqual(json.loads(r.read())["status"], "ok")
                with self.assertRaises(urllib.error.HTTPError) as ctx:
                    post("/analyze?session=a", jpg.tobytes(), token="wrong")
                self.assertEqual(ctx.exception.code, 401)

                first = post("/analyze?session=a&pitch=20", jpg.tobytes())
                self.assertEqual(first["path"]["action"], "slow")
                self.assertTrue(first["guidance"]["speak"])
                self.assertIn("شخص", first["guidance"]["text"])
                self.assertAlmostEqual(first["objects"][0]["distance_m"], 1.5, delta=0.3)
                # First frame of a session = new place → offline description (no VLM configured)
                self.assertTrue(first["new_place"])
                self.assertEqual(first["description"]["source"], "local")

                # Same session: rate-limited, no repeat; other session (English) speaks right away
                self.assertFalse(post("/analyze?session=a&pitch=20", jpg.tobytes())["guidance"]["speak"])
                other = post("/analyze?session=b&lang=en", json.dumps(
                    {"image": base64.b64encode(jpg.tobytes()).decode()}).encode(), ctype="application/json")
                self.assertTrue(other["guidance"]["speak"])
                self.assertIn("person", other["guidance"]["text"])

                # Phone tilt changes the metric scale: steeper tilt → different distance
                tilted = post("/analyze?session=c&pitch=30", jpg.tobytes())
                self.assertNotAlmostEqual(tilted["path"]["clear_distance_m"], first["path"]["clear_distance_m"], 1)

                desc = post("/describe?session=a", b"")
                self.assertIn("أمامك", desc["text"])
                with self.assertRaises(urllib.error.HTTPError) as ctx:
                    post("/analyze?session=a", b"not an image")
                self.assertEqual(ctx.exception.code, 400)
            finally:
                proc.terminate()
                proc.wait(timeout=10)
                proc.stdout.close()


if __name__ == "__main__":
    unittest.main(verbosity=2)
