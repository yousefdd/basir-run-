"""Builds the Arabic before/after tables of the round-8 report from out/summary.json (every number is measured)."""
import json, sys

AR = {
    "stops": "قرارات التوقف (عدد)",
    "falseStops": "توقفات خاطئة (اصطناعي: لا حاجز خلال 6 م)",
    "stopsWhileWalkerContinued": "توقفات بينما واصل الماشي المسجَّل سيره (SANPO)",
    "lrFlipsPerMin": "انتقالات يمين↔يسار في الدقيقة",
    "lrFlipsUnjustifiedPerMin": "انتقالات يمين↔يسار لا تبررها هندسة الطريق (اصطناعي)",
    "commandChangesPerMin": "تغييرات الأمر في الدقيقة",
    "unnecessaryChangesPerMin": "تغييرات غير ضرورية في الدقيقة (أمر دام أقل من ثانية)",
    "conflictingVibrationsPerMin": "اهتزازات متعارضة في الدقيقة (جهتان خلال ثانيتين)",
    "vibrationsPerMin": "عدد الاهتزازات في الدقيقة",
    "playedPatternChangesPerMin": "تغيّر النمط المحسوس في الدقيقة",
    "meanCommandDurationS": "متوسط زمن استقرار القرار (ث)",
    "meanDirectionalPersistenceS": "متوسط استمرار الأمر الاتجاهي (ث)",
    "meanGapBetweenVibrationsS": "متوسط الفاصل بين الاهتزازات (ث) — فترة التهدئة الفعلية",
    "guidanceLossShare": "فقدان التوجيه (نسبة الإطارات)",
    "warningShare": "نسبة الإطارات تحت تحذير عائق",
    "hazardEpisodes": "نوبات عوائق/عمق في الرؤية (إطاران فأكثر)",
    "hazardMissed": "نوبات عوائق بلا تحذير ولا توقف",
    "hazardFeltMissed": "نوبات عوائق لم يُحَسّ فيها أي اهتزاز تحذير أو توقف",
    "wayClosedEpisodes": "نوبات «الطريق مغلق» من المخطِّط",
    "wayClosedUncovered": "«طريق مغلق» بلا تحذير ولا توقف ولا أمر انعطاف موثَّق",
    "wayClosedCoveredByTurn": "«طريق مغلق» عند زاوية موثَّقة وُجِّه كانعطاف",
    "hazardReactionMeanS": "زمن الاستجابة للخطر، المتوسط (ث)",
    "hazardReactionMaxS": "زمن الاستجابة للخطر، الأقصى (ث)",
    "criticalNotPassedFrames": "قرارات حرجة لم تصل إلى الاهتزاز (إطارات)",
    "dirAgreement": "توافق جهة الأوامر مع انعطاف الماشي الحقيقي (SANPO)",
    "walkerTurns": "انعطافات حقيقية ≥ 60° (SANPO)",
    "walkerTurnsCued": "انعطافات حقيقية نُبِّه إليها بالجهة الصحيحة",
    "wrongSideFramesInTurns": "إطارات أوامر بالجهة الخاطئة أثناء الانعطافات الحقيقية",
    "corners": "زوايا اصطناعية",
    "cornersDetected": "زوايا نُبِّه إليها بالجهة الصحيحة",
    "cornersAnnouncedBeforeTurn": "زوايا أُعلنت قبل بدء الانعطاف",
    "cornerCueLatencyMeanS": "زمن أمر الزاوية بعد بداية الاقتراب (ث)",
    "wrongSideFramesInCorners": "إطارات أوامر بالجهة الخاطئة في الزوايا",
    "turnCuesOnStraights": "أوامر انعطاف/منحنى على المستقيمات (اصطناعي)",
    "cornerPhaseAgreement": "تطابق مرحلة الزاوية مع الحقيقة",
    "falseCornerFrames": "إطارات زاوية كاذبة (اصطناعي)",
    "cornerFramesOutsideTurns": "إطارات زاوية خارج الانعطافات الحقيقية (SANPO)",
    "barrierWarnedAtM": "أول تحذير من الحاجز (م قبله)",
    "barrierStoppedAtM": "التوقف عند الحاجز (م قبله)",
}
GROUPS = {
    "All clips": "كل المقاطع",
    "Real video: SANPO-Real (chest camera, walking/jogging/running)": "فيديو حقيقي: SANPO-Real (كاميرا على الصدر، مشي/هرولة/جري)",
    "Real video: owner's night garden": "فيديو حقيقي: حديقة المالك ليلًا",
    "Synthetic sports grounds (exact ground truth)": "ملاعب اصطناعية (حقيقة أرضية دقيقة)",
}


def main(path):
    d = json.load(open(path))
    out = []
    for g, rows in d["groups"].items():
        clips = [c for c in d["clips"] if (g == "All clips") or
                 (g.startswith("Real video: SANPO") and not c.startswith("syn_") and c != "owner_night_garden") or
                 (g.endswith("night garden") and c == "owner_night_garden") or (g.startswith("Synthetic") and c.startswith("syn_"))]
        secs = sum(d["clips"][c]["base"]["seconds"] for c in clips)
        out.append(f"\n### {GROUPS.get(g, g)} — {len(clips)} مقطعًا، {secs:.0f} ثانية\n")
        out.append("| المقياس | 0.14.0 | الجولة 8 |\n|---|---:|---:|")
        for k, (b, n) in rows.items():
            out.append(f"| {AR.get(k, k)} | {b} | {n} |")
    # stop causes over all clips
    AR_CUE = {"CameraStop": "إطار متأخر/كاميرا (توقف كاميرا)", "LatencyStop": "بطء معالجة مستمر (محاكاة الهاتف)",
              "HeadLevelStop": "عائق على مستوى الرأس", "DropStop": "درج نازل أو حفرة", "Obstacle": "طريق مغلق (لا جانب مفتوح)",
              "TurnStop": "طريق مغلق مع جهة انعطاف", "LostStop": "فقدان المسار", "VisibilityStop": "رؤية ضعيفة"}
    tot = {"base": {}, "new": {}}
    for c, v in d["clips"].items():
        for k in ("base", "new"):
            for cue, n in v[k].get("stopCauses", {}).items(): tot[k][cue] = tot[k].get(cue, 0) + n
    out.append("\n### أسباب قرارات التوقف (كل المقاطع)\n")
    out.append("| السبب | 0.14.0 | الجولة 8 |\n|---|---:|---:|")
    for cue in sorted(set(tot["base"]) | set(tot["new"]), key=lambda c: -tot["base"].get(c, 0)):
        out.append(f"| {AR_CUE.get(cue, cue)} | {tot['base'].get(cue, 0)} | {tot['new'].get(cue, 0)} |")
    out.append("\n### كل مقطع على حدة (كل خانة: 0.14.0 / الجولة 8)\n")
    out.append("| المقطع | التوقفات | يمين↔يسار/د | تغييرات/د | اهتزازات/د | فقدان التوجيه | عوائق فائتة | زوايا/انعطافات نُبِّه إليها |")
    out.append("|---|---|---|---|---|---|---|---|")
    for c, v in d["clips"].items():
        b, n = v["base"], v["new"]
        cu = lambda m: (f"{m.get('cornersDetected')}/{m.get('corners')}" if "corners" in m else (f"{m.get('walkerTurnsCued')}/{m.get('walkerTurns')}" if "walkerTurns" in m else "-"))
        out.append(f"| {c} | {b['stops']} / {n['stops']} | {b['lrFlipsPerMin']} / {n['lrFlipsPerMin']} | {b['commandChangesPerMin']} / {n['commandChangesPerMin']} | "
                   f"{b['vibrationsPerMin']} / {n['vibrationsPerMin']} | {b['guidanceLossShare']} / {n['guidanceLossShare']} | {b['hazardMissed']} / {n['hazardMissed']} | {cu(b)} / {cu(n)} |")
    print("\n".join(out))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "out/summary.json")
