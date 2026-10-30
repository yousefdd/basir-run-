// Checks mobile/planner.js + mobile/guidance.js against the Python reference.
//   node --test tests/test_planner_js.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { PathPlanner } from '../mobile/planner.js';
import { GuidanceEngine, distancePhrase } from '../mobile/guidance.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const python = process.env.PYTHON || 'python3';
const scenes = JSON.parse(execFileSync(python, [path.join(here, 'dump_scenes.py')], { maxBuffer: 1 << 28 }));

for (const s of scenes) {
  test(`planner matches Python: ${s.name}`, () => {
    const js = new PathPlanner({ pitchDeg: s.auto ? null : 20 }).analyze(Float32Array.from(s.disparity), s.width, s.height);
    const py = s.python;
    assert.equal(js.floorValid, py.floor_valid);
    assert.equal(js.action, py.action);
    assert.equal(js.targetHeadingDeg, py.target_heading_deg);
    assert.equal(js.raisedSurface ?? false, py.raised_surface);
    assert.equal(js.pitchSource, py.pitch_source);
    if (py.pitch_deg != null) assert.ok(Math.abs(js.pitchDeg - py.pitch_deg) <= 0.3, `pitch ${js.pitchDeg} vs ${py.pitch_deg}`);
    const close = (a, b, tol = 0.1) => (a == null || b == null ? assert.equal(a, b) : assert.ok(Math.abs(a - b) <= tol, `${a} vs ${b}`));
    close(js.clearDistanceM, py.clear_distance_m);
    close(js.headLevel?.distanceM ?? null, py.head_level?.distance_m ?? null);
    close(js.drop?.distanceM ?? null, py.drop?.distance_m ?? null, 0.2);
    close(js.nearestObstacle?.distanceM ?? null, py.nearest_obstacle?.distance_m ?? null);
    for (const k of ['left', 'center', 'right']) close(js.sectors[k] ?? null, py.sectors[k] ?? null);
  });
}

test('guidance wording', () => {
  assert.equal(distancePhrase(1.6), 'متر ونصف');
  assert.equal(distancePhrase(3.4), '3 أمتار ونصف');
  const g = new GuidanceEngine({ confirmFrames: 1 });
  const out = g.update({ action: 'slight_right' }, [{ class: 'person', distanceM: 2, bearingDeg: 0 }], 0);
  assert.equal(out.text, 'شخص أمامك على بعد مترين، انحرف قليلاً إلى اليمين');
  assert.equal(out.speak, true);
  const stop = g.update({ action: 'stop' }, [], 0.5);
  assert.equal(stop.priority, 3);
  assert.equal(g.update({ action: 'stop' }, [], 1.0).speak, false);
});
