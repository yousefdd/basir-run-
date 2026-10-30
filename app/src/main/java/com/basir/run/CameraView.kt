package com.basir.run

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.basir.run.vision.Curve
import com.basir.run.vision.FrameSnapshot
import com.basir.run.vision.GuidancePipeline
import com.basir.run.vision.ObstacleLevel
import com.basir.run.vision.PathConfidence
import androidx.compose.ui.geometry.Size as GeoSize
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import java.util.concurrent.Executors

/**
 * Rear camera preview plus frame analysis on a background thread. Every analysed frame goes
 * through the pipeline and its snapshot is delivered on the main thread. If the camera cannot
 * start, onError is called with a message for the user.
 */
@Composable
fun CameraHost(pipeline: GuidancePipeline, onSnapshot: (FrameSnapshot) -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestSnapshot by rememberUpdatedState(onSnapshot)
    val latestError by rememberUpdatedState(onError)
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            keepScreenOn = true
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    DisposableEffect(lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val main = Handler(Looper.getMainLooper())
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null

        future.addListener({
            try {
                val cameraProvider = future.get()
                provider = cameraProvider
                val fourByThree = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                val preview = Preview.Builder().setResolutionSelector(fourByThree.build()).build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        fourByThree.setResolutionStrategy(
                            ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                        ).build()
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { image ->
                    try {
                        val snapshot = pipeline.process(image)
                        main.post { latestSnapshot(snapshot) }
                    } catch (e: Exception) {
                        Log.e("CameraHost", "Frame analysis failed", e)
                    } finally {
                        image.close()
                    }
                }
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                Log.e("CameraHost", "Camera failed to start", e)
                latestError("تعذّر تشغيل الكاميرا الخلفية")
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            provider?.unbindAll()
            executor.shutdown()
        }
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

/**
 * The virtual running path drawn over the preview, on its own layer above the camera image.
 * Filled points were observed by the detector on that row; hollow points come from the fitted
 * curve only. Solid yellow edges and the green running line appear only when the path is
 * confirmed; otherwise the edges are thin, grey and dashed and no running line is drawn. A
 * snapshot older than 700 ms is not drawn at all, so a lost path never lingers on screen.
 * Developer mode adds every detection with its label and score.
 */
@Composable
fun PathOverlay(snapshot: FrameSnapshot?, nowMs: Long, runnerX: Float, developer: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val shot = snapshot ?: return@Canvas
        if (nowMs - shot.timeMs > STALE_MS) return@Canvas
        val vp = shot.virtualPath
        val aspect = shot.path.estimate.aspect
        val shownWidth: Float
        val shownHeight: Float
        if (size.width / size.height > aspect) {
            shownWidth = size.width
            shownHeight = size.width / aspect
        } else {
            shownHeight = size.height
            shownWidth = size.height * aspect
        }
        val left = (size.width - shownWidth) / 2
        val top = (size.height - shownHeight) / 2
        fun at(x: Float, y: Float) = Offset(left + x * shownWidth, top + y * shownHeight)
        fun line(xs: List<Pair<Float, Float>>) = Path().apply {
            xs.forEachIndexed { i, (x, y) -> val p = at(x, y); if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
        }
        val confirmed = vp.confidence == PathConfidence.Confirmed
        val edgeColor = if (confirmed) Tactile else Color(0xFF9AA4A8)
        val dashed = PathEffect.dashPathEffect(floatArrayOf(20f, 16f))

        drawLine(Color.White.copy(alpha = 0.7f), at(runnerX, 0.82f), at(runnerX, 1f), strokeWidth = 5.px(this), cap = StrokeCap.Round)

        val lefts = vp.points.mapNotNull { pt -> pt.left?.let { it to pt.y } }
        val rights = vp.points.mapNotNull { pt -> pt.right?.let { it to pt.y } }
        listOf(lefts, rights).filter { it.size > 1 }.forEach {
            drawPath(line(it), edgeColor, style = Stroke(if (confirmed) 7.px(this) else 3.px(this), cap = StrokeCap.Round, pathEffect = if (confirmed) null else dashed))
        }
        if (confirmed) {
            val centre = vp.points.mapNotNull { pt -> pt.center?.let { it to pt.y } }
            if (centre.size > 1) drawPath(line(centre), Good, style = Stroke(6.px(this), cap = StrokeCap.Round))
        }
        vp.points.forEach { pt ->
            listOf(pt.left to pt.leftObserved, pt.right to pt.rightObserved).forEach { (x, seen) ->
                if (x != null) {
                    if (seen) drawCircle(edgeColor, 6.px(this), at(x, pt.y))
                    else drawCircle(edgeColor, 6.px(this), at(x, pt.y), style = Stroke(2.px(this)))
                }
            }
        }

        // The Virtual Path Engine: line crossings found on the ground (yellow when on the chosen
        // line, grey otherwise) and the path it follows (teal), projected back into the picture.
        shot.line?.let { lr ->
            val w = lr.width.toFloat()
            val h = lr.height.toFloat()
            val chosen = lr.decision.path
            if (developer) lr.lines.take(6).forEachIndexed { k, c ->
                for (seg in c.segments) drawCircle(if (k == 0) Tactile else Color(0xFF9AA4A8), 4.px(this), at(seg.u.toFloat() / w, seg.v.toFloat() / h))
            }
            if (chosen != null) {
                val pts = chosen.sample(0.2, maxOf(chosen.nearM, 0.3), chosen.farM).mapNotNull { lr.camera.groundToImage(it) }
                    .map { (u, v) -> (u / w).toFloat() to (v / h).toFloat() }
                if (pts.size > 1) drawPath(line(pts), Teal, style = Stroke(6.px(this), cap = StrokeCap.Round))
                // The look-ahead point the steering aims at.
                val ahead = minOf(chosen.farM, 2.0)
                lr.camera.groundToImage(run.basir.engine.GroundPoint(ahead, chosen.lateralAt(ahead)))?.let { (u, v) ->
                    drawCircle(Color.White, 7.px(this), at((u / w).toFloat(), (v / h).toFloat()), style = Stroke(3.px(this)))
                }
            }
        }

        // Developer mode: what the depth planner marked as obstacle (red) or drop (violet).
        val analysis = shot.deep?.analysis
        if (developer && analysis != null && analysis.floorValid) {
            val cw = analysis.width
            val ch = analysis.height
            for (v in 0 until ch step 3) for (u in 0 until cw step 3) {
                val i = v * cw + u
                val color = when {
                    analysis.obstacleMask?.get(i) == true -> Danger
                    analysis.dropMask?.get(i) == true -> Color(0xFFA855F7)
                    else -> continue
                }
                drawCircle(color.copy(alpha = 0.7f), 2.px(this), at((u + 0.5f) / cw, (v + 0.5f) / ch))
            }
        }

        val findings = shot.findings.associateBy { it.detection }
        shot.detections.forEach { d ->
            val f = findings[d]
            if (f == null && !developer) return@forEach
            val color = when (f?.level) {
                ObstacleLevel.Hazard -> Danger
                ObstacleLevel.Possible -> Warn
                else -> Color(0xFF9AA4A8)
            }
            drawRect(color, topLeft = at(d.left, d.top), size = GeoSize((d.right - d.left) * shownWidth, (d.bottom - d.top) * shownHeight), style = Stroke(3.px(this)))
            if (developer) {
                val paint = android.graphics.Paint().apply { this.color = android.graphics.Color.WHITE; textSize = 13.px(this@Canvas) }
                val p = at(d.left, d.top)
                drawContext.canvas.nativeCanvas.drawText(String.format(java.util.Locale.US, "%s %.0f%%", d.label, d.score * 100), p.x + 4, p.y - 6, paint)
            }
        }
    }
}

private const val STALE_MS = 700L

private fun Int.px(scope: androidx.compose.ui.graphics.drawscope.DrawScope) = with(scope) { this@px.toFloat() * density }

/** Numbers behind the picture, for testing on the track. */
@Composable
fun DeveloperReadout(snapshot: FrameSnapshot?, nowMs: Long, haptic: String? = null) {
    val s = snapshot
    val text = if (s == null) "لا توجد إطارات بعد" else {
        val e = s.path.estimate
        val q = s.quality
        String.format(
            java.util.Locale.US,
            "%s · %s · ثقة %.2f · ثابت %s\nإزاحة %.2f · انحناء %.2f · نقاط مرصودة %.0f%%\n%.0f إطار/ث · معالجة %.0f/%.0f ملّي ث · عمر الإطار %d\nسطوع %.0f · تباين %.0f · حدة %.1f · عائق %s (%d)",
            e.source, e.status, e.confidence, if (s.path.stable) "نعم" else "لا",
            s.path.offset, s.path.curvature, s.virtualPath.observedShare * 100,
            s.framesPerSecond, s.processingP50Ms, s.processingP90Ms, nowMs - s.timeMs,
            q.meanLuma, q.contrast, q.sharpness, s.obstacle, s.detections.size,
        ) + (s.deep?.let { d ->
            val a = d.analysis
            String.format(
                java.util.Locale.US,
                "\nعمق %d ملّي ث · أجسام %d ملّي ث · أرض %s · خالٍ تقديرياً %s م · ميل %s° (%s) · %s %s",
                d.depthMs, d.detectMs, if (a?.floorValid == true) "نعم" else "لا",
                a?.clearDistanceM?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "-",
                a?.pitchDeg?.let { String.format(java.util.Locale.US, "%.0f", it) } ?: "-", a?.pitchSource ?: "-",
                d.hazard.level, d.hazard.kind,
            )
        } ?: if (s.depthReady) "\nعمق: لا نتيجة حديثة" else "\nعمق: النموذج غير محمّل") + (s.line?.let { lr ->
            val c = lr.command
            String.format(
                java.util.Locale.US,
                "\nمسار: %s · ثقة %.2f · خطوط %d · %d ملّي ث\nأمر: %s %s %s · توجيه %.1f° · انحناء %.3f/م%s · اهتزاز: %s",
                lr.decision.mode, lr.decision.confidence, lr.lines.size, lr.processingMs,
                c.kind, c.side ?: "", c.level ?: "", c.steerDeg, c.curveCurvature,
                c.curveInM?.let { String.format(java.util.Locale.US, " بعد %.1f م", it) } ?: "", haptic ?: "-",
            )
        } ?: "")
    }
    androidx.compose.material3.Text(
        text,
        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
        color = Color.White,
        modifier = Modifier
            .background(Color(0xCC000000))
            .padding(8.dp),
    )
}
