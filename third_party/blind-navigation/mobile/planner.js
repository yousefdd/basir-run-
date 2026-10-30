// Walking-path planner — JavaScript port of
// ../scripts/path_planner.py (keep in sync) — for on-device use in mobile apps
// (React Native, Capacitor, WebView, browser). Verified against Python by tests/test_planner_js.mjs.
//
// Relative depth (disparity, larger = closer) → floor plane (RANSAC, d = a*v + b*u + c)
// → every pixel gets a metric position from camera height/tilt → obstacles,
// head-level hazards, drops → free distance per heading → one action.

const DEG = Math.PI / 180;

// pitchDeg: null = estimate from each frame (send the phone's sensor tilt when available)
export const DEFAULT_GEOMETRY = { heightM: 1.3, pitchDeg: 20, hfovDeg: 70 };

export const DEFAULT_CONFIG = {
  bodyWidthM: 0.75,
  safeDistanceM: 2.0,
  stopDistanceM: 1.2,
  lookaheadM: 5.0,
  minObstacleHeightM: 0.08,
  maxObstacleHeightM: 2.1,
  headLevelHeightM: 1.1,
  closerRatio: 0.8,
  noiseMargin: 0.03,
  dropRatio: 1.25,
  dropRangeM: 3.0,
  detectDrops: true,
  headingsDeg: [-35, -20, -10, 0, 10, 20, 35],
  minPixels: 8,
  ransacIters: 150,
  ransacTau: 0.015,
  minFloorInliers: 0.3,
  turnPenaltyMPerDeg: 0.01,
  fallbackPitchDeg: 20,
};

const round = (x, nd = 2) => (x == null || !Number.isFinite(x) ? null : Math.round(x * 10 ** nd) / 10 ** nd);

export function headingToAction(h) {
  if (Math.abs(h) <= 5) return 'forward';
  if (h < 0) return h >= -20 ? 'slight_left' : 'left';
  return h <= 20 ? 'slight_right' : 'right';
}

// Deterministic PRNG so results are reproducible (mulberry32)
function rng(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function percentile(sorted, p) {
  if (!sorted.length) return 0;
  const idx = (sorted.length - 1) * p / 100;
  const lo = Math.floor(idx), hi = Math.ceil(idx);
  return sorted[lo] + (sorted[hi] - sorted[lo]) * (idx - lo);
}

// kth smallest (0-based) — partial selection
function kthSmallest(arr, k) {
  const a = Float64Array.from(arr);
  let lo = 0, hi = a.length - 1;
  while (lo < hi) {
    const pivot = a[(lo + hi) >> 1];
    let i = lo, j = hi;
    while (i <= j) {
      while (a[i] < pivot) i++;
      while (a[j] > pivot) j--;
      if (i <= j) { const t = a[i]; a[i] = a[j]; a[j] = t; i++; j--; }
    }
    if (k <= j) hi = j; else if (k >= i) lo = i; else return a[k];
  }
  return a[k];
}

function solve3(A, b) {
  // Cramer's rule for a 3×3 system; null if singular
  const [a, b1, c] = A[0], [d, e, f] = A[1], [g, h, i] = A[2];
  const det = a * (e * i - f * h) - b1 * (d * i - f * g) + c * (d * h - e * g);
  if (Math.abs(det) < 1e-12) return null;
  const dx = b[0] * (e * i - f * h) - b1 * (b[1] * i - f * b[2]) + c * (b[1] * h - e * b[2]);
  const dy = a * (b[1] * i - f * b[2]) - b[0] * (d * i - f * g) + c * (d * b[2] - b[1] * g);
  const dz = a * (e * b[2] - b[1] * h) - b1 * (d * b[2] - b[1] * g) + b[0] * (d * h - e * g);
  return [dx / det, dy / det, dz / det];
}

export class Geometry {
  constructor(g = DEFAULT_GEOMETRY) { Object.assign(this, DEFAULT_GEOMETRY, g); }
  focalPx(width) { return (width / 2) / Math.tan(this.hfovDeg * DEG / 2); }
  // (down, forward) components of the ray through image row v, for camera z = 1
  ray(v, height, f) {
    const th = this.pitchDeg * DEG;
    const y = (v - (height - 1) / 2) / f;
    return [y * Math.cos(th) + Math.sin(th), Math.cos(th) - y * Math.sin(th)];
  }
  floorDepth(v, height, f) {
    const [down] = this.ray(v, height, f);
    return down > 1e-4 ? this.heightM / down : Infinity;
  }
  groundDistance(v, height, f) {
    const [down, fwd] = this.ray(v, height, f);
    return down > 1e-4 ? (this.heightM / down) * fwd : Infinity;
  }
  horizonRow(height, f) { return (height - 1) / 2 - f * Math.tan(this.pitchDeg * DEG); }
}

export function normalizeDisparity(disp, returnRange = false) {
  const step = Math.max(1, Math.floor(disp.length / 4000));
  const sample = [];
  for (let i = 0; i < disp.length; i += step) sample.push(disp[i]);
  sample.sort((x, y) => x - y);
  const lo = percentile(sample, 2), hi = percentile(sample, 98);
  const out = new Float32Array(disp.length);
  const s = 1 / (hi - lo + 1e-6);
  for (let i = 0; i < disp.length; i++) out[i] = Math.min(1.2, Math.max(-0.2, (disp[i] - lo) * s));
  return returnRange ? [out, lo, hi] : out;
}

export function fitFloorPlane(d, w, h, vStart, cfg) {
  if (vStart >= h - 4) return [null, 0];
  const V = [], U = [], Z = [];
  for (let v = vStart; v < h; v += 2) for (let u = 0; u < w; u += 2) { V.push(v); U.push(u); Z.push(d[v * w + u]); }
  const n = Z.length;
  if (n < 30) return [null, 0];
  const rand = rng(0);
  const minSlope = 0.15 / h;
  const weight = V.map((v) => 0.5 + (v - vStart) / Math.max(h - vStart, 1));
  const ok = (c) => c && c[0] >= minSlope && Math.abs(c[1]) <= 0.5 * c[0];

  let best = null, bestScore = -1;
  for (let it = 0; it < cfg.ransacIters; it++) {
    const i0 = Math.floor(rand() * n), i1 = Math.floor(rand() * n), i2 = Math.floor(rand() * n);
    if (i0 === i1 || i1 === i2 || i0 === i2) continue;
    const c = solve3([[V[i0], U[i0], 1], [V[i1], U[i1], 1], [V[i2], U[i2], 1]], [Z[i0], Z[i1], Z[i2]]);
    if (!ok(c)) continue;
    let score = 0;
    for (let i = 0; i < n; i++) if (Math.abs(c[0] * V[i] + c[1] * U[i] + c[2] - Z[i]) < cfg.ransacTau) score += weight[i];
    if (score > bestScore) { best = c; bestScore = score; }
  }
  if (!best) return [null, 0];

  // Least-squares refit on inliers (normal equations)
  const tau = cfg.ransacTau * 1.5;
  const M = [[0, 0, 0], [0, 0, 0], [0, 0, 0]], r = [0, 0, 0];
  let cnt = 0;
  for (let i = 0; i < n; i++) {
    if (Math.abs(best[0] * V[i] + best[1] * U[i] + best[2] - Z[i]) >= tau) continue;
    const x = [V[i], U[i], 1];
    for (let a = 0; a < 3; a++) { r[a] += x[a] * Z[i]; for (let b = 0; b < 3; b++) M[a][b] += x[a] * x[b]; }
    cnt++;
  }
  if (cnt < 10) return [null, 0];
  const coef = solve3(M, r);
  if (!ok(coef)) return [null, 0];
  // Narrow corridors: what matters is the floor right in front (bottom rows, central columns)
  let inl = 0, front = 0, frontInl = 0;
  for (let i = 0; i < n; i++) {
    const ok = Math.abs(coef[0] * V[i] + coef[1] * U[i] + coef[2] - Z[i]) < tau;
    if (ok) inl++;
    if (V[i] >= 0.7 * h && Math.abs(U[i] - (w - 1) / 2) < 0.25 * w) { front++; if (ok) frontInl++; }
  }
  return [coef, Math.max(inl / n, front ? 0.6 * frontInl / front : 0)];
}

/** Camera tilt from the image: Depth Anything disparity ≈ 0 at infinity, so the floor reaches 0 at the horizon. */
export function estimatePitch(d, w, h, lo, hi, hfovDeg, cfg) {
  const [coef, frac] = fitFloorPlane(d, w, h, Math.floor(0.5 * h), cfg);
  if (!coef || frac < cfg.minFloorInliers) return null;
  const [a, b, c] = coef;
  const zero = -lo / (hi - lo + 1e-6);
  const vH = (zero - b * (w - 1) / 2 - c) / a;
  const f = (w / 2) / Math.tan(hfovDeg * DEG / 2);
  const pitch = Math.atan(((h - 1) / 2 - vH) / f) / DEG;
  return pitch >= -5 && pitch <= 60 ? pitch : null;
}

/**
 * The fitted plane may be a table / bed top. All horizontal surfaces share the horizon:
 * d = t + k (v - vH + r (u - cx)), k ∝ 1 / height below camera. The floor is the lowest one
 * (smallest significant k). Returns [coef, switched].
 */
export function lowestHorizontalPlane(d, w, h, coef, vH, cfg) {
  const [a, b, c] = coef;
  const cx = (w - 1) / 2, r = b / a, t = a * vH + b * cx + c;
  const k = [];
  for (let v = Math.ceil(0.35 * h); v < h; v++) for (let u = 0; u < w; u++) {
    const den = v - vH + r * (u - cx);
    if (den > 0.15 * h) k.push((d[v * w + u] - t) / den);
  }
  if (k.length < 50) return [coef, false];
  const minSupport = Math.max(0.05 * k.length, 6 * cfg.minPixels);
  let kFloor = a, switched = false;
  for (let it = 0; it < 3; it++) {
    const logk = k.filter((x) => x > 0.2 * kFloor && x < 0.85 * kFloor).map(Math.log);
    if (logk.length < minSupport) break;
    let mn = Infinity, mx = -Infinity;
    for (const x of logk) { if (x < mn) mn = x; if (x > mx) mx = x; }
    const bins = new Array(48).fill(0), bw = (mx - mn) / 48 || 1e-9;
    for (const x of logk) bins[Math.min(47, Math.floor((x - mn) / bw))]++;
    const i = bins.indexOf(Math.max(...bins));
    const center = mn + (i + 0.5) * bw;
    const members = logk.filter((x) => Math.abs(x - center) < 0.05).map(Math.exp).sort((p, q) => p - q);
    if (members.length < minSupport) break;
    kFloor = percentile(members, 50);
    switched = true;
  }
  if (!switched) return [coef, false];
  return [[kFloor, kFloor * r, t - kFloor * vH - kFloor * r * cx], true];
}

export class PathPlanner {
  constructor(geometry = {}, config = {}) {
    this.geo = new Geometry(geometry);
    this.cfg = { ...DEFAULT_CONFIG, ...config };
  }

  /** disparity: Float32Array of w*h (row-major), larger = closer. */
  analyze(disparity, w, h) {
    const cfg = this.cfg;
    let geo = this.geo;
    const [d, lo, hi] = normalizeDisparity(disparity, true);
    let pitchSource = 'config';
    if (geo.pitchDeg == null) {
      const est = estimatePitch(d, w, h, lo, hi, geo.hfovDeg, cfg);
      pitchSource = est != null ? 'image' : 'default';
      geo = new Geometry({ heightM: geo.heightM, pitchDeg: est ?? cfg.fallbackPitchDeg, hfovDeg: geo.hfovDeg });
    }
    const pitchDeg = round(geo.pitchDeg, 1);
    const f = geo.focalPx(w);
    const vH = geo.horizonRow(h, f);
    const vStart = Math.floor(Math.max(0.5 * h, vH + 0.05 * h));
    const [firstPlane, inlierFrac] = fitFloorPlane(d, w, h, vStart, cfg);
    if (!firstPlane || inlierFrac < cfg.minFloorInliers) {
      return { floorValid: false, floorConfidence: round(inlierFrac), action: 'caution', targetHeadingDeg: 0,
        clearDistanceM: null, headings: [], sectors: {}, nearestObstacle: null, headLevel: null, drop: null,
        pitchDeg, pitchSource, raisedSurface: false, width: w, height: h };
    }
    const [coef, switched] = lowestHorizontalPlane(d, w, h, firstPlane, vH, cfg);
    const [a, b, c] = coef;
    const [a0, b0, c0] = firstPlane;

    // Per-row ray terms (no roll assumed)
    const rowDown = new Float64Array(h), rowFwd = new Float64Array(h), zFloorRow = new Float64Array(h), distFloorRow = new Float64Array(h);
    for (let v = 0; v < h; v++) {
      const [dn, fw] = geo.ray(v, h, f);
      rowDown[v] = dn; rowFwd[v] = fw;
      zFloorRow[v] = geo.floorDepth(v, h, f);
      distFloorRow[v] = geo.groundDistance(v, h, f);
    }
    const th = geo.pitchDeg * DEG, cosT = Math.cos(th), sinT = Math.sin(th), cy = (h - 1) / 2, cx = (w - 1) / 2;

    const obstacle = new Uint8Array(w * h), floor = new Uint8Array(w * h), dropMask = new Uint8Array(w * h);
    const pointForward = new Float32Array(w * h).fill(Infinity);
    const oF = [], oL = [], oH = [];
    const dF = [], dL = [], nF = [], nL = [];
    for (let v = 0; v < h; v++) {
      for (let u = 0; u < w; u++) {
        const i = v * w + u;
        const resid = d[i] - (a * v + b * u + c);
        if (Math.abs(resid) < cfg.ransacTau * 1.5) floor[i] = 1;
        // Same disparity as the floor at eqRow → same camera depth
        const eqRow = (d[i] - b * u - c) / a;
        const yEq = (eqRow - cy) / f;
        const downEq = yEq * cosT + sinT;
        const zEq = downEq > 1e-4 ? geo.heightM / downEq : Infinity;
        const zFl = zFloorRow[v];
        const xk = (u - cx) / f;
        if (Number.isFinite(zEq)) {
          const fwd = zEq * rowFwd[v], lat = zEq * xk, hgt = geo.heightM - zEq * rowDown[v];
          // On a raised surface (table/bed top, or the floor before a drop) = in the way
          const closer = zEq < cfg.closerRatio * zFl ||
            (switched && Math.abs(d[i] - (a0 * v + b0 * u + c0)) < cfg.ransacTau * 1.5);
          if (resid > cfg.noiseMargin && closer && fwd <= cfg.lookaheadM &&
              hgt >= cfg.minObstacleHeightM && hgt <= cfg.maxObstacleHeightM) {
            obstacle[i] = 1; pointForward[i] = fwd;
            oF.push(fwd); oL.push(lat); oH.push(hgt);
          }
        }
        if (v > vH && distFloorRow[v] < cfg.dropRangeM) {
          const df = distFloorRow[v];
          nF.push(df); nL.push(df * xk);
          if (cfg.detectDrops && resid < -cfg.noiseMargin && zEq > cfg.dropRatio * zFl) {
            dropMask[i] = 1; dF.push(df); dL.push(df * xk);
          }
        }
      }
    }

    const halfFov = geo.hfovDeg / 2;
    const headings = [];
    const high = oH.map((x) => x >= cfg.headLevelHeightM);
    for (const psi of cfg.headingsDeg) {
      if (Math.abs(psi) > halfFov - 5) continue;
      const entry = {
        headingDeg: psi,
        freeM: round(this._corridorFree(oF, oL, psi, null)),
        freeLowM: round(this._corridorFree(oF, oL, psi, high, false)),
        freeHighM: round(this._corridorFree(oF, oL, psi, high, true)),
      };
      if (cfg.detectDrops && dF.length) {
        const inDrop = this._corridorForward(dF, dL, psi);
        const nFloor = this._corridorForward(nF, nL, psi).length;
        if (inDrop.length >= Math.max(cfg.minPixels, 0.15 * Math.max(nFloor, 1))) {
          entry.dropM = round(kthSmallest(inDrop, cfg.minPixels - 1));
        }
      }
      headings.push(entry);
    }

    const straight = headings.find((e) => e.headingDeg === 0) ||
      headings.reduce((p, e) => (Math.abs(e.headingDeg) < Math.abs(p.headingDeg) ? e : p));
    const clear = straight.freeM;
    let action, target;
    if (clear >= cfg.safeDistanceM) {
      action = 'forward'; target = straight.headingDeg;
    } else {
      const score = (e) => e.freeM - cfg.turnPenaltyMPerDeg * Math.abs(e.headingDeg);
      const best = headings.reduce((p, e) => (score(e) > score(p) ? e : p));
      if (best.freeM >= cfg.safeDistanceM) { target = best.headingDeg; action = headingToAction(target); }
      else if (clear > cfg.stopDistanceM) { target = best.freeM > clear + 0.5 ? best.headingDeg : straight.headingDeg; action = 'slow'; }
      else { target = best.headingDeg; action = 'stop'; }
    }

    let nearestObstacle = null;
    if (oF.length >= cfg.minPixels) {
      const kth = kthSmallest(oF, cfg.minPixels - 1);
      const k = oF.indexOf(kth);
      nearestObstacle = { distanceM: round(oF[k]), bearingDeg: round(Math.atan2(oL[k], oF[k]) / DEG, 1), heightM: round(oH[k]) };
    }
    let headLevel = null;
    if (straight.freeHighM < cfg.safeDistanceM && straight.freeHighM < straight.freeLowM - 0.4) {
      headLevel = { distanceM: straight.freeHighM, headingDeg: straight.headingDeg };
    }
    const drop = straight.dropM != null ? { distanceM: straight.dropM, experimental: true } : null;

    return {
      floorValid: true, floorConfidence: round(inlierFrac), action, targetHeadingDeg: target, clearDistanceM: round(clear),
      headings, sectors: this._sectors(oF, oL), nearestObstacle, headLevel, drop,
      pitchDeg, pitchSource, raisedSurface: switched,
      width: w, height: h, obstacle, floor, dropMask, pointForward,
    };
  }

  _rot(f, l, psi) {
    const p = psi * DEG;
    return [f * Math.cos(p) + l * Math.sin(p), -f * Math.sin(p) + l * Math.cos(p)];
  }

  _corridorForward(F, L, psi, mask = null, want = true) {
    const out = [];
    const half = this.cfg.bodyWidthM / 2;
    for (let i = 0; i < F.length; i++) {
      if (mask && mask[i] !== want) continue;
      const [f2, l2] = this._rot(F[i], L[i], psi);
      if (Math.abs(l2) < half && f2 > 0) out.push(f2);
    }
    return out;
  }

  _corridorFree(F, L, psi, mask, want = true) {
    const blk = this._corridorForward(F, L, psi, mask, want);
    const k = this.cfg.minPixels;
    if (blk.length < k) return this.cfg.lookaheadM;
    return Math.min(kthSmallest(blk, k - 1), this.cfg.lookaheadM);
  }

  _sectors(F, L) {
    const k = this.cfg.minPixels;
    const groups = { left: [], center: [], right: [] };
    for (let i = 0; i < F.length; i++) {
      const b = Math.atan2(L[i], F[i]) / DEG;
      (b < -12 ? groups.left : b > 12 ? groups.right : groups.center).push(F[i]);
    }
    const out = {};
    for (const [name, vals] of Object.entries(groups)) out[name] = vals.length >= k ? round(kthSmallest(vals, k - 1)) : null;
    return out;
  }

  /** Distance of an object box given in planner (work) pixel coordinates. */
  boxDistance(analysis, [x1, y1, x2, y2]) {
    if (!analysis.floorValid) return null;
    const { width: w, height: h, pointForward } = analysis;
    x1 = Math.max(0, Math.round(x1)); x2 = Math.min(w, Math.max(Math.round(x2), x1 + 1));
    y1 = Math.max(0, Math.round(y1)); y2 = Math.min(h, Math.max(Math.round(y2), y1 + 1));
    const vals = [];
    for (let v = y1; v < y2; v++) for (let u = x1; u < x2; u++) {
      const x = pointForward[v * w + u];
      if (Number.isFinite(x)) vals.push(x);
    }
    if (vals.length >= 4) { vals.sort((p, q) => p - q); return round(percentile(vals, 20)); }
    if (y2 < h - 1) {
      const geo = new Geometry({ heightM: this.geo.heightM, pitchDeg: analysis.pitchDeg, hfovDeg: this.geo.hfovDeg });
      const dist = geo.groundDistance(y2, h, geo.focalPx(w));
      // Near the horizon the foot-point distance explodes — beyond ~30 m it is meaningless
      return Number.isFinite(dist) && dist <= 30 ? round(dist) : null;
    }
    return null;
  }

  bearingDeg(xCenter, width) {
    return Math.atan((xCenter - (width - 1) / 2) / this.geo.focalPx(width)) / DEG;
  }
}
