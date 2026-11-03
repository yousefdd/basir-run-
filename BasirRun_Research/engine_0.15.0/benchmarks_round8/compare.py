"""Aggregates out/<clip>_{base,new}.metrics.json into one before/after table (markdown) and a JSON summary."""
import json, glob, os, sys

ROWS = [
    ("stops", "STOP decisions (count)", "sum"),
    ("falseStops", "False stops (synthetic: no barrier within 6 m)", "sum"),
    ("stopsWhileWalkerContinued", "Stops while the recorded walker kept walking (SANPO)", "sum"),
    ("lrFlipsPerMin", "Left<->right flips per minute", "mean"),
    ("lrFlipsUnjustifiedPerMin", "Left<->right flips not justified by the geometry (synthetic)", "mean"),
    ("commandChangesPerMin", "Command changes per minute", "mean"),
    ("unnecessaryChangesPerMin", "Unnecessary changes per minute (cue lasted < 1 s)", "mean"),
    ("conflictingVibrationsPerMin", "Conflicting vibrations per minute (opposite sides < 2 s)", "mean"),
    ("vibrationsPerMin", "Vibrations per minute", "mean"),
    ("playedPatternChangesPerMin", "Felt pattern changes per minute", "mean"),
    ("meanCommandDurationS", "Mean decision stability time (s)", "mean"),
    ("meanDirectionalPersistenceS", "Mean directional cue persistence (s)", "mean"),
    ("meanGapBetweenVibrationsS", "Mean gap between vibrations (s)", "mean"),
    ("guidanceLossShare", "Guidance loss (share of frames)", "mean"),
    ("warningShare", "Share of frames under an obstacle warning", "mean"),
    ("hazardEpisodes", "Object/depth hazard episodes (vision evidence >= 2 frames)", "sum"),
    ("hazardMissed", "Object/depth hazard episodes without a warning/stop", "sum"),
    ("hazardFeltMissed", "Object/depth hazard episodes with no obstacle/stop vibration felt", "sum"),
    ("wayClosedEpisodes", "Planner 'way closed' episodes", "sum"),
    ("wayClosedUncovered", "'Way closed' episodes without warning, stop or verified turn cue", "sum"),
    ("wayClosedCoveredByTurn", "'Way closed' at a verified corner, guided as the turn", "sum"),
    ("hazardReactionMeanS", "Hazard reaction time, mean (s)", "mean"),
    ("hazardReactionMaxS", "Hazard reaction time, max (s)", "max"),
    ("criticalNotPassedFrames", "Critical decisions not passed to vibration (frames)", "sum"),
    ("dirAgreement", "Direction cues agreeing with the walker's real path (SANPO)", "mean"),
    ("walkerTurns", "Real turns >= 60 deg (SANPO)", "sum"),
    ("walkerTurnsCued", "Real turns cued with the right side", "sum"),
    ("wrongSideFramesInTurns", "Wrong-side cue frames in real turns", "sum"),
    ("corners", "Synthetic corners", "sum"),
    ("cornersDetected", "Corners cued with the right side", "sum"),
    ("cornersAnnouncedBeforeTurn", "Corners announced before the turn", "sum"),
    ("cornerCueLatencyMeanS", "Corner cue time after approach start (s)", "mean"),
    ("wrongSideFramesInCorners", "Wrong-side cue frames in corners", "sum"),
    ("turnCuesOnStraights", "Turn/bend cues on straights (synthetic)", "sum"),
    ("cornerPhaseAgreement", "Corner phase agreement with truth", "mean"),
    ("falseCornerFrames", "False corner frames (synthetic)", "sum"),
    ("cornerFramesOutsideTurns", "Corner frames outside real turns (SANPO)", "sum"),
    ("barrierWarnedAtM", "Barrier first warned at (m before it)", "mean"),
    ("barrierStoppedAtM", "Barrier stop at (m before it)", "mean"),
]


def agg(vals, how):
    v = [x for x in vals if x is not None]
    if not v: return None
    if how == "sum": return round(sum(v), 3)
    if how == "max": return round(max(v), 3)
    return round(sum(v) / len(v), 3)


def main(out_dir, groups):
    data = {}
    for f in sorted(glob.glob(os.path.join(out_dir, "*_base.metrics.json"))):
        g = f.replace("_base.metrics.json", "_new.metrics.json")
        if not os.path.exists(g): continue
        b, n = json.load(open(f)), json.load(open(g))
        data[b["clip"]] = (b, n)
    res = {}
    lines = []
    for gname, pred in groups:
        clips = [c for c in data if pred(c)]
        if not clips: continue
        lines.append(f"\n### {gname} ({len(clips)} clips, {sum(data[c][0]['seconds'] for c in clips):.0f} s)\n")
        lines.append("| Metric | 0.14.0 | Round 8 |\n|---|---:|---:|")
        res[gname] = {}
        for k, label, how in ROWS:
            bv = agg([data[c][0].get(k) for c in clips], how); nv = agg([data[c][1].get(k) for c in clips], how)
            if bv is None and nv is None: continue
            res[gname][k] = (bv, nv)
            lines.append(f"| {label} | {bv} | {nv} |")
    per = ["\n### Per clip\n", "| Clip | stops | flips/min | changes/min | vib/min | loss | hazards missed | corners/turns cued |", "|---|---|---|---|---|---|---|---|"]
    for c, (b, n) in data.items():
        cu = lambda m: (f"{m.get('cornersDetected')}/{m.get('corners')}" if "corners" in m else (f"{m.get('walkerTurnsCued')}/{m.get('walkerTurns')}" if "walkerTurns" in m else "-"))
        per.append(f"| {c} | {b['stops']}→{n['stops']} | {b['lrFlipsPerMin']}→{n['lrFlipsPerMin']} | {b['commandChangesPerMin']}→{n['commandChangesPerMin']} | "
                   f"{b['vibrationsPerMin']}→{n['vibrationsPerMin']} | {b['guidanceLossShare']}→{n['guidanceLossShare']} | {b['hazardMissed']}→{n['hazardMissed']} | {cu(b)}→{cu(n)} |")
    print("\n".join(lines + per))
    json.dump({"groups": res, "clips": {c: {"base": b, "new": n} for c, (b, n) in data.items()}}, open(os.path.join(out_dir, "summary.json"), "w"), indent=1)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "out", [
        ("All clips", lambda c: True),
        ("Real video: SANPO-Real (chest camera, walking/jogging/running)", lambda c: not c.startswith("syn_") and c != "owner_night_garden"),
        ("Real video: owner's night garden", lambda c: c == "owner_night_garden"),
        ("Synthetic sports grounds (exact ground truth)", lambda c: c.startswith("syn_")),
    ])
