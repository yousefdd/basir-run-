// Writes firmware/test/vectors.txt: a long random but reproducible session (patterns, Bluetooth delays and stalls,
// repeated and old sequence numbers, corrupted bytes, config changes, disconnects) run through the Kotlin reference
// model (run.basir.engine.BeltPlayerModel). belt_core_test.cpp replays the same lines through the C++ firmware core
// and must reach the same result for every frame and the same motor duties at every check.
//   lines:  C                      connect
//           X                      disconnect
//           F <hex> <beltMs> <RES> frame received at beltMs, expected result
//           D <beltMs> d1 .. d5    expected motor duties at beltMs
import run.basir.engine.*
import java.io.File
import java.util.Random

fun main(args: Array<String>) {
    val out = File(args.getOrElse(0) { "vectors.txt" })
    val r = Random(20261103)
    val lang = BeltLanguage()
    val pats = lang.catalog() + Motor.values().map { lang.motorTest(it) }
    val model = BeltPlayerModel()
    val lines = ArrayList<String>()
    val clockOffset = 3_000_000L + r.nextInt(1_000_000)   // belt clock ahead of the phone clock
    var phone = 10_000L
    var seq = 0
    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    fun deliver(b: ByteArray, delay: Long) {
        val at = phone + clockOffset + delay
        val res = model.receive(b, at)
        lines += "F ${hex(b)} $at ${res.name}"
        for (k in 0 until 4) {
            val t = at + r.nextInt(900)
            lines += "D $t " + model.duties(t).joinToString(" ")
        }
    }
    model.onConnect(); lines += "C"
    repeat(4000) {
        phone += 30 + r.nextInt(400)
        val delay = when (r.nextInt(20)) { 0 -> 300L + r.nextInt(1500); 1, 2 -> 100L + r.nextInt(300); else -> 8L + r.nextInt(40) }
        when (r.nextInt(40)) {
            0 -> { model.onDisconnect(); lines += "X"; model.onConnect(); lines += "C" }
            1 -> deliver(BeltProtocol.config(BeltConfig(10 + r.nextInt(91), List(5) { 50 + r.nextInt(101) }, 20 + r.nextInt(81), 300 + r.nextInt(2500)), (++seq) and 0xFFFF, phone), delay)
            2 -> deliver(BeltProtocol.silence((++seq) and 0xFFFF, phone), delay)
            3, 4 -> deliver(BeltProtocol.heartbeat((++seq) and 0xFFFF, phone), delay)
            5 -> deliver(BeltProtocol.play(pats[r.nextInt(pats.size)], (seq - r.nextInt(3)) and 0xFFFF, phone), delay) // old / repeated seq
            6 -> { val b = BeltProtocol.play(pats[r.nextInt(pats.size)], (++seq) and 0xFFFF, phone); b[r.nextInt(b.size)] = (b[0].toInt() xor (1 shl r.nextInt(8))).toByte(); deliver(b, delay) }
            7 -> { seq = (seq + 30000 + r.nextInt(5000)) and 0xFFFF; deliver(BeltProtocol.heartbeat(seq, phone), delay) } // big jump
            else -> deliver(BeltProtocol.play(pats[r.nextInt(pats.size)], (++seq) and 0xFFFF, phone, maxSteps = 1 + r.nextInt(12)), delay)
        }
    }
    out.writeText(lines.joinToString("\n") + "\n")
    println("wrote ${lines.size} lines to $out")
}
