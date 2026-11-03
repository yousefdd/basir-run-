// Replays the guidance commands recorded on the 45 library clips (visual_reports/round4/*/*/engine_r5.csv, 5 fps,
// the field-test stop rule) through the app's real scheduler (priorities, cooldown, repeat limits, latched stop) and
// the belt language, for several cooldown values, and measures what the runner would feel:
//   cues per minute by class, the closest two cues, direction changes that came sooner than the cooldown (must be 0
//   except for urgent ones), left/right flips within 2 s, two cues overlapping (must be 0), share of time vibrating.
// Usage: BeltReplayKt <round4 dir> <out.txt>
import run.basir.engine.*
import java.io.File

private class Rec(val lang: BeltLanguage) : HapticOutput {
    override val hasAmplitudeControl = true
    var cmd: GuidanceCommand? = null
    var now = 0L
    val played = ArrayList<Triple<Long, BeltPattern, GuidanceCommand?>>()
    override fun play(pattern: HapticPattern) { lang.forPattern(pattern.name, cmd)?.let { played += Triple(now, it, cmd) } }
    override fun cancel() {}
    override fun playedDurationMs(pattern: HapticPattern) = lang.forPattern(pattern.name, cmd)?.durationMs ?: pattern.durationMs
}

private fun commands(f: File): List<GuidanceCommand> = f.readLines().drop(1).mapNotNull { line ->
    val c = line.split(",")
    val kind = runCatching { CommandKind.valueOf(c[4]) }.getOrNull() ?: return@mapNotNull null
    GuidanceCommand(kind, side = c[5].takeIf { it.isNotEmpty() }?.let { Side.valueOf(it) },
        level = c[6].takeIf { it.isNotEmpty() }?.let { Level.valueOf(it) })
}

fun main(args: Array<String>) {
    val dir = File(args.getOrElse(0) { "." })
    val clips = dir.walkTopDown().filter { it.name == "engine_r5.csv" }.sortedBy { it.path }.toList()
    val lang = BeltLanguage()
    val out = StringBuilder()
    out.appendLine("Belt replay: ${clips.size} clips, recorded guidance commands at 5 fps, app scheduler + belt language")
    out.appendLine("setting | min | cues/min (direction / warning / stop / continue) | closest cues s | early direction changes | L/R flips <2 s | overlaps | vibrating %")
    val beltRepeat = mapOf(Level.LOW to 1400L, Level.MEDIUM to 1100L, Level.HIGH to 950L)   // = HapticEngine.BELT_SCHEDULER
    for ((label, cooldown, repeat) in listOf(Triple("phone repeats", 1200L, null)) + listOf(800L, 1200L, 1600L, 2000L).map { Triple("belt", it, beltRepeat) }) {
        var minutes = 0.0; var dir = 0; var warn = 0; var stop = 0; var cont = 0
        var closest = Long.MAX_VALUE; var early = 0; var flips = 0; var overlaps = 0; var onMs = 0L
        for (f in clips) {
            val cmds = commands(f)
            val rec = Rec(lang)
            val base = SchedulerConfig(onPathOnlyOnConfirm = true, minChangeGapMs = cooldown)
            val s = HapticScheduler(rec, HapticLanguage(true), if (repeat != null) base.copy(correctRepeatMs = repeat) else base)
            cmds.forEachIndexed { i, c -> rec.now = i * 200L; rec.cmd = c; s.onCommand(c, rec.now) }
            minutes += cmds.size * 0.2 / 60
            for ((_, p, _) in rec.played) when (p.priority) {
                BeltPriority.DIRECTION -> dir++; BeltPriority.DANGER -> warn++; BeltPriority.EMERGENCY -> stop++; else -> cont++
            }
            rec.played.zipWithNext().forEach { (a, b) ->
                val gap = b.first - a.first
                closest = minOf(closest, gap)
                val urgent = b.second.priority >= BeltPriority.DANGER || b.third?.level == Level.HIGH && b.third?.kind == CommandKind.CORRECT
                // a real change of instruction (another side or another kind); the same direction at another strength is not one
                fun key(p: BeltPattern) = p.name.substringBeforeLast("_").takeIf { p.name.startsWith("correct_") || p.name.startsWith("curve_") } ?: p.name
                if (b.second.priority == BeltPriority.DIRECTION && key(a.second) != key(b.second) && gap < cooldown && !urgent) early++
                if (gap < a.second.durationMs && b.second.priority < a.second.priority) overlaps++
                val sa = a.third?.side; val sb = b.third?.side
                if (sa != null && sb != null && sa != sb && gap < 2000) flips++
            }
            onMs += rec.played.sumOf { it.second.onMs }
        }
        fun pm(n: Int) = "%.2f".format(n / minutes)
        out.appendLine("$label ${cooldown} ms | ${"%.1f".format(minutes)} | ${pm(dir + warn + stop + cont)} (${pm(dir)} / ${pm(warn)} / ${pm(stop)} / ${pm(cont)}) | " +
            "${"%.2f".format(closest / 1000.0)} | $early | $flips | $overlaps | ${"%.1f".format(100.0 * onMs / (minutes * 60_000))}")
    }
    File(args.getOrElse(1) { "belt_replay.txt" }).writeText(out.toString())
    print(out)
}
