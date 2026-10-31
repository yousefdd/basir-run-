// Writes the unified vibration table (docs/haptics_table.md) straight from the engine's HapticLanguage
// and SchedulerConfig, so the document can never drift from what the phone plays.
import run.basir.engine.*
import java.io.File

fun main(a: Array<String>) {
    val sc = SchedulerConfig()
    fun describe(p: HapticPattern): String {
        val segs = p.timingsMs.indices.drop(1).joinToString(" · ") { i -> if (p.amplitudes[i] > 0) "${p.timingsMs[i]} ms @${p.amplitudes[i]}" else "pause ${p.timingsMs[i]}" }
        return segs
    }
    fun repeat(name: String) = when {
        name.startsWith("stop") -> "once, then at most ${sc.stopMaxReminders} reminders ${sc.stopRepeatMs / 1000.0} s apart"
        name.startsWith("obstacle") -> "once, then at most ${sc.obstacleMaxReminders} reminders ${sc.obstacleRepeatMs / 1000.0} s apart"
        name.startsWith("correct") && name.endsWith("high") -> "every ${sc.correctRepeatMs.getValue(Level.HIGH) / 1000.0} s while needed"
        name.startsWith("correct") && name.endsWith("medium") -> "every ${sc.correctRepeatMs.getValue(Level.MEDIUM) / 1000.0} s while needed"
        name.startsWith("correct") -> "every ${sc.correctRepeatMs.getValue(Level.LOW) / 1000.0} s while needed"
        name.startsWith("curve") -> "on entering the bend, every ${sc.curveRepeatMs / 1000.0} s, quiet after 2 s inside"
        name == "caution" -> "every ${sc.cautionRepeatMs / 1000.0} s while the path is unclear"
        name == "back_on_path" -> "once after a correction"
        else -> "-"
    }
    val out = StringBuilder("# Basir-Run vibration table\n\nGenerated from `engine/.../Haptics.kt` by `BasirRun_Research/tools/kt/HapticTable.kt`. Priority: higher wins; a lower one never cuts a higher one. Strength 0-255 (phones without strength control play every pulse at full strength and scale its length instead).\n\n")
    out.append("| Priority | Pattern | Meaning (Arabic) | Meaning | Pulses | Repeat |\n|---|---|---|---|---|---|\n")
    for ((p, ar, en) in HapticLanguage(true).catalog().sortedByDescending { it.first.priority })
        out.append("| ${p.priority} | `${p.name}` | $ar | $en | ${describe(p)} | ${repeat(p.name)} |\n")
    File(a[0]).writeText(out.toString())
    println("rows ${HapticLanguage(true).catalog().size}")
}
