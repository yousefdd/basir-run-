package com.basir.run

import android.content.Context
import com.basir.run.engine.Decision
import com.basir.run.vision.FrameSnapshot
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A CSV record of a guided session for later analysis: decisions, confidence, timing and obstacle
 * levels, about four rows a second. No images or video are stored. Files live in the app's private
 * storage under sessions/ and are shared only when the user chooses to.
 */
class SessionLog(context: Context) {

    private val dir = File(context.filesDir, "sessions")
    private var writer: BufferedWriter? = null
    private var startMs = 0L
    private var lastRowMs = -1_000L

    fun open(now: Long): File? = try {
        dir.mkdirs()
        val name = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date()) + ".csv"
        val file = File(dir, name)
        writer = file.bufferedWriter().also {
            it.write("t_ms,state,cue,severity,move_to,level,curve,path_status,confidence,stable,offset,curvature,observed_share,obstacle,hazard_kind,fps,p50_ms,p90_ms,quality_ok,depth_age_ms,depth_ms,objects_ms,floor_ok,clear_m,depth_level,objects,line_mode,line_conf,line_count,line_ms,command,steer_deg,curve_k,curve_in_m,paused\n")
        }
        startMs = now
        file
    } catch (e: Exception) {
        null
    }

    fun write(now: Long, d: Decision, s: FrameSnapshot?, paused: Boolean) {
        val w = writer ?: return
        if (now - lastRowMs < ROW_EVERY_MS) return
        lastRowMs = now
        val p = s?.path
        val deep = s?.deep
        val line = s?.line
        val row = listOf(
            now - startMs, d.state, d.cue, d.severity, d.moveTo ?: "", d.level, d.curve ?: "",
            p?.estimate?.status ?: "", fmt(p?.estimate?.confidence), p?.stable ?: "", fmt(p?.offset), fmt(p?.curvature),
            fmt(s?.virtualPath?.observedShare), s?.obstacle ?: "", s?.hazardKind ?: "", fmt(s?.framesPerSecond), fmt(s?.processingP50Ms), fmt(s?.processingP90Ms),
            s?.quality?.usable ?: "", deep?.let { now - it.frameTimeMs } ?: "", deep?.depthMs ?: "", deep?.detectMs ?: "",
            deep?.analysis?.floorValid ?: "", deep?.analysis?.clearDistanceM ?: "", deep?.hazard?.level ?: "",
            deep?.detections?.joinToString(" ") { it.label.replace(' ', '_') } ?: "",
            line?.decision?.mode ?: "", fmt(line?.decision?.confidence?.toFloat()), line?.lines?.size ?: "", line?.processingMs ?: "",
            line?.command?.let { "${it.kind}_${it.side ?: ""}_${it.level ?: ""}" } ?: "", fmt(line?.command?.steerDeg?.toFloat()),
            fmt(line?.command?.curveCurvature?.toFloat()), fmt(line?.command?.curveInM?.toFloat()), paused,
        ).joinToString(",")
        try {
            w.write(row)
            w.write("\n")
        } catch (e: Exception) {
            close()
        }
    }

    fun close() {
        try { writer?.close() } catch (_: Exception) {}
        writer = null
    }

    private fun fmt(v: Float?) = v?.let { String.format(Locale.US, "%.3f", it) } ?: ""

    companion object {
        private const val ROW_EVERY_MS = 250L
    }
}
