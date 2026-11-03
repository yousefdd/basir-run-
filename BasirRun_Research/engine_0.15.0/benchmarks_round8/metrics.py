"""
Guidance metrics for one replay (JSON Lines from tools/replay) of one clip, before/after comparisons.

Streams measured (per frame of the replay):
  haptic  = the command that reaches the vibration scheduler (after every decision layer)
  played  = the pattern that actually vibrated (after the scheduler's own rules)

References:
  synthetic clips  truth.json: exact path, corner phases/sides, barrier distance
  SANPO-Real       source.json: the recorded walker's camera poses (where a sighted walker actually went)
  owner's video    no reference: counts only

Usage: metrics.py <clip_dir> <replay.jsonl>  -> prints one JSON object
"""
import json, math, os, sys
import numpy as np

DIR_KINDS = {"CORRECT", "CURVE", "CORNER", "OBSTACLE", "STOP"}
TURN_KINDS = {"CURVE", "CORNER"}


def load(clip, rep):
    frames = [json.loads(l) for l in open(rep)]
    meta = json.load(open(os.path.join(clip, "meta.json")))
    truth = json.load(open(os.path.join(clip, "truth.json"))) if os.path.exists(os.path.join(clip, "truth.json")) else None
    src = json.load(open(os.path.join(clip, "source.json"))) if os.path.exists(os.path.join(clip, "source.json")) else None
    return frames, meta, truth, src


def walker_track(src, n, fps):
    """Recorded walker from SANPO camera poses: ground positions (z-up frame), heading of the chest camera (its +x axis
    looks forward; calibrated against measured image motion, corr -0.95..-1.00 on the turn clips) smoothed over 1 s,
    and walking speed. In this frame a right turn DEcreases the heading (SIGN = -1 below)."""
    P = np.array([[float(v) for v in r[1:4]] for r in src["poses"]])[:n]
    Q = np.array([[float(v) for v in r[4:8]] for r in src["poses"]])[:n]
    x, y, z, w = Q.T
    fx = 1 - 2 * (y * y + z * z); fy = 2 * (x * y + z * w)          # camera +x axis in the world, horizontal part
    hd = np.unwrap(np.arctan2(fx, fy))
    k = fps
    hd = np.convolve(np.pad(hd, (k // 2, k - 1 - k // 2), mode="edge"), np.ones(k) / k, "valid")
    xy = P[:, :2]
    h = max(1, int(fps * 0.5))
    d = np.zeros_like(xy); d[h:-h] = xy[2 * h:] - xy[:-2 * h]; d[:h] = d[h]; d[-h:] = d[-h - 1]
    sp = np.linalg.norm(d, axis=1) / (2 * h / fps)
    return xy, hd, sp


def runs(seq):
    out = []; cur = None; n = 0
    for s in seq:
        if s == cur: n += 1
        else:
            if cur is not None: out.append((cur, n))
            cur = s; n = 1
    if cur is not None: out.append((cur, n))
    return out


def main(clip, rep):
    F, meta, truth, src = load(clip, rep)
    fps = meta["fps"]; n = len(F); minutes = n / fps / 60
    # silence and the faint on-path tick mean the same to the runner ("keep going"): one calm state
    hk = [(f["haptic"]["kind"], f["haptic"].get("side")) if f["haptic"] and f["haptic"]["kind"] != "ON_PATH" else ("CALM", None) for f in F]
    played = [f.get("played") for f in F]
    res = {"clip": os.path.basename(clip.rstrip("/")), "frames": n, "seconds": round(n / fps, 1)}

    # 1. Stops
    stop_on = [i for i in range(n) if hk[i][0] == "STOP" and (i == 0 or hk[i - 1][0] != "STOP")]
    res["stops"] = len(stop_on)
    causes = {}
    for i in stop_on:
        k = F[i]["final"]["cue"]
        causes[k] = causes.get(k, 0) + 1
    res["stopCauses"] = causes
    res["stopShare"] = round(sum(1 for k in hk if k[0] == "STOP") / n, 3)
    res["stopsPlayed"] = sum(1 for p in played if p and p.startswith("stop"))
    res["warningShare"] = round(sum(1 for k in hk if k[0] == "OBSTACLE") / n, 3)

    # 2. Direction flips (left <-> right between consecutive directional commands, within 3 s)
    flips = 0; last = None
    for i, (k, s) in enumerate(hk):
        if k in DIR_KINDS and s:
            if last and last[1] != s and (i - last[0]) / fps <= 3.0: flips += 1
            last = (i, s)
    res["lrFlipsPerMin"] = round(flips / minutes, 2)
    # played opposite-side patterns within 2 s (conflicting vibrations)
    ps = [(i, p) for i, p in enumerate(played) if p]
    def pside(p):
        return "LEFT" if p.endswith("left") or "_left_" in p else ("RIGHT" if p.endswith("right") or "_right_" in p else None)
    conf = 0
    for a in range(len(ps)):
        for b in range(a + 1, len(ps)):
            if (ps[b][0] - ps[a][0]) / fps > 2.0: break
            sa, sb = pside(ps[a][1]), pside(ps[b][1])
            if sa and sb and sa != sb: conf += 1
    res["conflictingVibrationsPerMin"] = round(conf / minutes, 2)
    res["vibrationsPerMin"] = round(len(ps) / minutes, 1)

    # 3. Decision changes and stability
    rr = runs(hk)
    changes = len(rr) - 1
    res["commandChangesPerMin"] = round(changes / minutes, 1)
    short = sum(1 for k in range(1, len(rr) - 1) if rr[k][1] / fps < 1.0)  # a command that lasted < 1 s then changed again
    res["unnecessaryChangesPerMin"] = round(short / minutes, 1)
    res["meanCommandDurationS"] = round(float(np.mean([r[1] for r in rr])) / fps, 2)
    dr = [r for r in rr if r[0][0] in DIR_KINDS - {"STOP"} and r[0][1]]
    res["meanDirectionalPersistenceS"] = round(float(np.mean([r[1] for r in dr])) / fps, 2) if dr else None
    # felt changes: consecutive played patterns that differ (ignoring the faint on-path tick)
    pp = [p for _, p in ps if p != "on_path"]
    res["playedPatternChangesPerMin"] = round(sum(1 for k in range(1, len(pp)) if pp[k] != pp[k - 1]) / minutes, 1)
    gaps = [(ps[k + 1][0] - ps[k][0]) / fps for k in range(len(ps) - 1)]
    res["meanGapBetweenVibrationsS"] = round(float(np.mean(gaps)), 2) if gaps else None

    # 4. Guidance loss: no usable direction (caution, or stopped/held because no path)
    lost = sum(1 for f, k in zip(F, hk) if k[0] == "CAUTION" or (f["decision"]["mode"] == "STOP" and f["decision"]["stopReason"] in ("NO_PATH", "POOR_VISIBILITY", "LOW_CONFIDENCE")))
    res["guidanceLossShare"] = round(lost / n, 3)

    # 5. Hazard reaction. Two kinds of the vision pipeline's own evidence (independent of the decision layers):
    #   objects / depth hazards (combinedObstacle == Hazard): must ALWAYS get a warning or a stop;
    #   "way closed" readings of the path planner (OBSTACLE_NO_ALTERNATIVE / PATH_UNSAFE): covered by a warning, a stop,
    #   or — when the way goes on around a verified corner — by the corner's turn cue.
    def episodes(flags):
        out = []; i = 0
        while i < n:
            if flags[i]:
                j = i
                while j < n and flags[j]: j += 1
                if j - i >= 2: out.append((i, j))
                i = j
            else: i += 1
        return out
    obj = episodes([f["combinedObstacle"] == "Hazard" for f in F])
    way = episodes([f["decision"]["stopReason"] in ("OBSTACLE_NO_ALTERNATIVE", "PATH_UNSAFE") and f["combinedObstacle"] != "Hazard" for f in F])
    lat = []; missed = 0
    for a, b in obj:
        hit = next((k for k in range(a, min(n, b + 1)) if hk[k][0] in ("OBSTACLE", "STOP")), None)
        if hit is None: missed += 1
        else: lat.append((hit - a) / fps)
    way_unc = 0; way_turn = 0
    for a, b in way:
        ks = range(a, min(n, b + 1))
        if any(hk[k][0] in ("OBSTACLE", "STOP") for k in ks): continue
        if any((F[k]["haptic"] or {}).get("corner") in ("PREPARE_TURN", "TURNING") for k in ks): way_turn += 1
        else: way_unc += 1
    res["hazardEpisodes"] = len(obj); res["hazardMissed"] = missed
    # what the runner actually FEELS: an obstacle or stop pattern must play within the episode (+0.5 s), or have played
    # within the 2.5 s before it (the same warning still fresh)
    felt_missed = 0
    for a, b in obj:
        lo = max(0, a - int(2.5 * fps)); hi = min(n, b + fps // 2)
        if not any(played[k] and (played[k].startswith("obstacle") or played[k].startswith("stop")) for k in range(lo, hi)): felt_missed += 1
    res["hazardFeltMissed"] = felt_missed
    res["hazardReactionMeanS"] = round(float(np.mean(lat)), 3) if lat else None
    res["hazardReactionMaxS"] = round(float(np.max(lat)), 3) if lat else None
    res["wayClosedEpisodes"] = len(way); res["wayClosedUncovered"] = way_unc; res["wayClosedCoveredByTurn"] = way_turn
    # critical decisions of the decision layer that did not reach the vibration layer as STOP/OBSTACLE in the same frame
    crit = [i for i, f in enumerate(F) if f["final"]["severity"] == "Critical"]
    res["criticalDecisionFrames"] = len(crit)
    res["criticalNotPassedFrames"] = sum(1 for i in crit if hk[i][0] not in ("STOP", "OBSTACLE"))

    # 6. Reference-based accuracy
    if truth is not None:
        T = truth["frames"][:n]
        bad_dir = 0; dir_cues = 0; spurious = 0
        for f, k, t in zip(F, hk, T):
            if k[0] in ("CORRECT", "CURVE", "CORNER") and k[1]:
                dir_cues += 1
                if t["phase"] in ("APPROACH", "TURNING", "BEND") and k[1] != t["side"]: bad_dir += 1
                # a bend/corner cue on a straight with no real turn of that side 2 m behind .. 10 m ahead
                if t["phase"] == "STRAIGHT" and k[0] in ("CURVE", "CORNER") and \
                        not any(sc == k[1] and -2.0 <= cs - t["s"] <= 10.0 for cs, sc in truth.get("corners", [])): spurious += 1
        res["directionalCues"] = dir_cues
        # left<->right flips the geometry does not justify (the new side is not the side of a real turn/bend there)
        unj = 0; last = None
        for i, (k, sdd) in enumerate(hk):
            if k in DIR_KINDS and sdd:
                near_turn = any(sc == sdd and -2.0 <= cs - T[i]["s"] <= 10.0 for cs, sc in truth.get("corners", [])) or \
                    (T[i]["phase"] == "BEND" and T[i]["side"] == sdd)
                if last and last[1] != sdd and (i - last[0]) / fps <= 3.0 and not near_turn:
                    unj += 1
                last = (i, sdd)
        res["lrFlipsUnjustifiedPerMin"] = round(unj / minutes, 2)
        res["wrongSideCuesInTurns"] = bad_dir
        res["turnCuesOnStraights"] = spurious
        # corners
        corners = truth.get("corners", [])
        det = 0; early = 0; lat_c = []; wrong = 0
        for cs, side in corners:
            idx = [i for i, t in enumerate(T) if t["distToCorner"] is not None and abs(t["distToCorner"] - (cs - t["s"])) < 1e-6 and t["phase"] != "STRAIGHT" and abs(cs - t["s"] - t["distToCorner"]) < 1e-6]
            idx = [i for i, t in enumerate(T) if t["phase"] in ("APPROACH", "TURNING", "EXIT") and abs((cs - t["s"]) - t["distToCorner"]) < 1e-6]
            if not idx: continue
            a, b = idx[0], idx[-1]
            turning = [i for i in idx if T[i]["phase"] == "TURNING"]
            cue = [i for i in range(a, b + 1) if hk[i][0] in TURN_KINDS | {"CORRECT"} and hk[i][1] == side]
            wrong += sum(1 for i in range(a, b + 1) if hk[i][0] in TURN_KINDS | {"CORRECT"} and hk[i][1] and hk[i][1] != side)
            if cue:
                det += 1; lat_c.append((cue[0] - a) / fps)
                if turning and cue[0] < turning[0]: early += 1
        res["corners"] = sum(1 for cs, _ in corners if any(t["phase"] in ("APPROACH", "TURNING") and abs((cs - t["s"]) - t["distToCorner"]) < 1e-6 for t in T))
        res["cornersDetected"] = det; res["cornersAnnouncedBeforeTurn"] = early
        res["cornerCueLatencyMeanS"] = round(float(np.mean(lat_c)), 2) if lat_c else None
        res["wrongSideFramesInCorners"] = wrong
        # phase agreement (only when the engine reports a corner phase)
        ph = [(f.get("corner") or {}).get("phase") for f in F]
        if any(ph):
            mp = {"APPROACH_CORNER": "APPROACH", "PREPARE_TURN": "APPROACH", "TURNING": "TURNING", "EXIT_CORNER": "EXIT",
                  "STABLE_STRAIGHT": "STRAIGHT", None: "STRAIGHT", "NONE": "STRAIGHT"}
            rel = [i for i, t in enumerate(T) if t["phase"] != "BEND"]
            agree = sum(1 for i in rel if mp.get(ph[i], "STRAIGHT") == T[i]["phase"])
            res["cornerPhaseAgreement"] = round(agree / max(1, len(rel)), 3)
            # a corner phase is false only if no real corner of that side lies 2 m behind .. 10 m ahead
            def real_corner_near(i, side):
                return any(sd == side and -2.0 <= cs - T[i]["s"] <= 10.0 for cs, sd in corners)
            sd = [(f.get("corner") or {}).get("side") for f in F]
            res["falseCornerFrames"] = sum(1 for i, t in enumerate(T) if ph[i] in ("APPROACH_CORNER", "PREPARE_TURN", "TURNING") and not real_corner_near(i, sd[i]))
        if any(t.get("barrierDist") is not None for t in T):
            first = next((i for i, (k, t) in enumerate(zip(hk, T)) if k[0] in ("OBSTACLE", "STOP") and t["barrierDist"] is not None and 0 < t["barrierDist"] < 12), None)
            res["barrierWarnedAtM"] = round(T[first]["barrierDist"], 2) if first is not None else None
            res["barrierStoppedAtM"] = next((round(T[i]["barrierDist"], 2) for i, k in enumerate(hk) if k[0] == "STOP" and T[i]["barrierDist"] is not None and T[i]["barrierDist"] > -1), None)
        res["falseStops"] = sum(1 for i in stop_on if not (T[i].get("barrierDist") is not None and 0 < T[i]["barrierDist"] < 6))
    elif src is not None:
        xy, hd, sp = walker_track(src, n, fps)
        # stops while the recorded walker kept walking for the next 2 s (proxy for a false stop on SANPO)
        res["stopsWhileWalkerContinued"] = sum(1 for i in stop_on if sp[i:i + 2 * fps].mean() >= 0.6)
        # direction agreement: the side the recorded walker actually turned toward over the next 2 s (camera yaw,
        # calibrated against image motion); cues while the walker kept straight (< 15 deg) are not judged. SANPO
        # positions are not used for this: their motion direction disagrees with the camera heading by up to ~100 deg.
        agree = disagree = 0
        for i, k in enumerate(hk):
            if k[0] not in ("CORRECT", "CURVE", "CORNER") or not k[1]: continue
            j = min(n - 1, i + 2 * fps)
            turn = math.degrees(hd[j] - hd[i]) * SIGN_H
            if abs(turn) < 15: continue
            want = "RIGHT" if turn > 0 else "LEFT"
            if k[1] == want: agree += 1
            else: disagree += 1
        res["dirCuesJudged"] = agree + disagree
        res["dirAgreement"] = round(agree / (agree + disagree), 3) if agree + disagree else None
        # true turns (walker heading change >= 60 deg within 5 s) and whether a turn cue of that side came
        turns = []; i = 0; w = 5 * fps
        while i < n - w:
            dh = math.degrees(hd[i + w] - hd[i])
            if abs(dh) >= 60 and sp[i:i + w].mean() >= 0.5:
                side = "RIGHT" if dh * SIGN_H > 0 else "LEFT"
                turns.append((i, i + w, side)); i += w
            else: i += fps // 3
        det = 0; wrong = 0; lat_t = []
        for a, b, side in turns:
            lo = max(0, a - 2 * fps)
            cue = [k for k in range(lo, b) if hk[k][0] in TURN_KINDS | {"CORRECT"} and hk[k][1] == side]
            wrong += sum(1 for k in range(lo, b) if hk[k][0] in TURN_KINDS | {"CORRECT"} and hk[k][1] and hk[k][1] != side)
            if cue: det += 1; lat_t.append((cue[0] - a) / fps)
        res["walkerTurns"] = len(turns); res["walkerTurnsCued"] = det; res["wrongSideFramesInTurns"] = wrong
        res["turnCueLatencyMeanS"] = round(float(np.mean(lat_t)), 2) if lat_t else None
        ph = [(f.get("corner") or {}).get("phase") for f in F]
        if any(ph):
            in_turn = np.zeros(n, bool)
            for a, b, _ in turns: in_turn[max(0, a - 2 * fps):b] = True
            res["cornerFramesOutsideTurns"] = int(sum(1 for i in range(n) if ph[i] in ("APPROACH_CORNER", "PREPARE_TURN", "TURNING") and not in_turn[i]))
    print(json.dumps(res))


# Orientation of SANPO's ground axes relative to the engine's "+ right": calibrated once against the engine's own
# visual yaw (tools/calibrate_sign.py); set by the environment so the calibration is explicit.
SIGN = float(os.environ.get("SANPO_LAT_SIGN", "-1"))
SIGN_H = float(os.environ.get("SANPO_HEADING_SIGN", "-1"))

if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
