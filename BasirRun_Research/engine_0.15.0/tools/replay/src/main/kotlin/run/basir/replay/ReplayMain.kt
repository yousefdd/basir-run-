package run.basir.replay

import android.os.SystemClock
import com.basir.run.engine.DecisionEngine
import com.basir.run.engine.EngineInput
import com.basir.run.engine.HapticBridge
import com.basir.run.vision.CameraFrame
import com.basir.run.vision.Detection
import com.basir.run.vision.EnvironmentSelector
import com.basir.run.vision.FrameQuality
import com.basir.run.vision.HazardKind
import com.basir.run.vision.IntrusionDetector
import com.basir.run.vision.LaneDetector
import com.basir.run.vision.ObstacleAssessment
import com.basir.run.vision.ObstacleLevel
import com.basir.run.vision.ObstacleMotionTracker
import com.basir.run.vision.ObstacleTracker
import com.basir.run.vision.PathRegionDetector
import com.basir.run.vision.PathTracker
import com.basir.run.vision.depth.DepthConfirm
import com.basir.run.vision.depth.DepthHazards
import com.basir.run.vision.depth.FloorPlanner
import com.basir.run.vision.line.LineEngine
import com.basir.run.vision.seg.SurfaceResult
import org.json.JSONArray
import org.json.JSONObject
import run.basir.engine.ProbabilityMask
import run.basir.engine.UnderfootClasses
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.BufferedInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Replays one benchmark clip (written by BasirRun_Research/benchmarks/bench_models.py) through
 * the app's per-frame pipeline: the same steps, in the same order, as GuidancePipeline.process(),
 * with the Android-only parts (CameraX, Bitmap, the model sessions and their threads) replaced by
 * the model outputs recorded for each frame. The model channels are treated as fresh on every
 * frame (no channel latency), the same for every variant compared.
 *
 * Variants (all off = the unmodified 0.12.2 behaviour):
 *   --walkable FILE   per-frame walkable mask (uint8 0/1, segW x segH) from a candidate model,
 *                     used instead of SegFormer labels + the under-the-feet rule
 *   --labels FILE     per-frame ADE20K labels from another model (same rule applied)
 *   --ground-cover    the engine's GroundCoverResolver (flat ground cover such as leaves is walkable)
 *   --intrusion-depth drop IntrusionDetector findings that the depth floor shows to be flat
 *
 * Output: one JSON object per frame (JSON Lines) on [out].
 */
fun main(args: Array<String>) {
    val dir = File(args[0])
    val out = File(args[1])
    val opts = args.drop(2)
    fun opt(name: String) = opts.indexOf(name).takeIf { it >= 0 }?.let { opts[it + 1] }
    val variant = Variant(
        walkableFile = opt("--walkable")?.let(::File),
        labelsFile = opt("--labels")?.let(::File),
        groundCover = "--ground-cover" in opts,
        intrusionDepth = "--intrusion-depth" in opts,
        flatDetections = "--flat-detections" in opts,
        visibilityCoast = "--visibility-coast" in opts,
        speedMps = opt("--speed")?.toDouble() ?: if ("--walking" in opts) LineEngine.WALKING_MPS else LineEngine.RUNNING_MPS,
        temporal = "--temporal" in opts,
        stabilize = "--stabilize" in opts,
        centerline = "--centerline" in opts,
        adaptive = "--adaptive" in opts,
        egoYaw = "--ego" in opts,
        guidanceStab = "--guidance-stab" in opts,
        poseYaw = "--pose-yaw" in opts,
        pathHazards = "--no-path-hazards" !in opts,
        distantLines = "--no-distant-lines" !in opts,
        handover = "--handover" in opts,
        sideMemory = "--no-side-memory" !in opts,
        speedScaled = "--speed-scaled" in opts,
        ageComp = "--age-comp" in opts,
        staleFix = "--no-stale-fix" !in opts,
        corners = "--corners" in opts,
        round8Stops = "--round8-stops" in opts,
        phoneTiming = "--phone-timing" in opts,
        decisionStab = "--decision-stab" in opts,
        latencySurfaceMs = opt("--latency")?.split(",")?.get(0)?.toLong() ?: 0L,
        latencyDeepMs = opt("--latency")?.split(",")?.get(1)?.toLong() ?: 0L,
    )
    Replay(dir, variant, opt("--gs-release")?.toDouble()).run(out)
}

data class Variant(
    val walkableFile: File? = null,
    val labelsFile: File? = null,
    val groundCover: Boolean = false,
    val intrusionDepth: Boolean = false,
    val flatDetections: Boolean = false,
    val visibilityCoast: Boolean = false,
    val speedMps: Double = LineEngine.RUNNING_MPS,
    val temporal: Boolean = false,
    val stabilize: Boolean = false,
    val centerline: Boolean = false,
    val adaptive: Boolean = false,
    val egoYaw: Boolean = false,
    val guidanceStab: Boolean = false,
    /** Use the recorded SANPO camera-pose yaw as the gyroscope (else the turn comes from the video, VisualYawEstimator). */
    val poseYaw: Boolean = false,
    /** Round 7: obstacles judged against the followed metric path (PathLinkedHazards); --no-path-hazards = 0.13.0. */
    val pathHazards: Boolean = true,
    /** Round 7: run beside a strong line up to 2 m away (PlannerConfig.followDistantLines); --no-distant-lines = 0.13.0. */
    val distantLines: Boolean = true,
    /** Round 7 experiment (rejected, off by default): smooth handover of the drawn path on a new track; --handover turns it on. */
    val handover: Boolean = false,
    /** Round 7: no left/right flip through a calmer cue (GuidanceStabilizerConfig.sideMemoryS); --no-side-memory = 0.13.0. */
    val sideMemory: Boolean = true,
    /** Round 7 experiment: hazard distances scaled with speed (DepthHazards.speedScaled), off by default. */
    val speedScaled: Boolean = false,
    /** Round 7 experiment (rejected, off): depth warning escalated by the advance since its frame; --age-comp turns it on. */
    val ageComp: Boolean = false,
    /** Round 7 fix: surface stale limit includes the keyframe interval (GuidancePipeline.STALE_FOLLOWS_KEYFRAMES); --no-stale-fix = 0.13.0. */
    val staleFix: Boolean = true,
    /** Round 8: Corner Detection & Navigation Engine (off = 0.14.0). */
    val corners: Boolean = false,
    /** Round 8: GuidanceDecisionStabilityEngine instead of the round-7 GuidanceTemporalStabilizer (needs --guidance-stab). */
    val decisionStab: Boolean = false,
    /** Round 8: late frames give a caution (stop only when frozen 1.5 s), slow processing must last 1 s to stop (off = 0.14.0). */
    val round8Stops: Boolean = false,
    /** Round 8 harness: phone-like timing (seeded, identical for every variant): late frames and slow-processing bursts. */
    val phoneTiming: Boolean = false,
    val latencySurfaceMs: Long = 0L,
    val latencyDeepMs: Long = 0L,
)

class Replay(private val dir: File, private val variant: Variant, private val opt: Double? = null) {
    private val meta = JSONObject(File(dir, "meta.json").readText())
    private val w = meta.getInt("width")
    private val h = meta.getInt("height")
    private val n = meta.getInt("frames")
    private val fps = meta.getInt("fps")
    private val hfov = meta.getDouble("hfovDeg")
    private val roll = meta.getDouble("rollDeg")
    private val heightM = meta.getDouble("heightM")
    private val depthW = meta.getInt("depthW")
    private val depthH = meta.getInt("depthH")
    /** Camera tilt: from the clip's meta, or, when unknown (no gravity sensor in a downloaded video),
     *  estimated from the depth maps by the app's own FloorPlanner fallback (median over the first 4 s). */
    val pitch: Double = if (meta.isNull("pitchDeg")) estimatePitch() else meta.getDouble("pitchDeg")
    private val segW = meta.getInt("segW")
    private val segH = meta.getInt("segH")
    private val lineW = meta.getInt("lineW")
    private val lineH = meta.getInt("lineH")

    // The app's components, as GuidancePipeline creates them
    private val lanes = LaneDetector()
    private val surfaces = PathRegionDetector()
    private val selector = EnvironmentSelector()
    private val lineEngine = LineEngine().also {
        it.cameraHeightM = heightM; it.speedMps = variant.speedMps
        // 0.12.2 stopped on the first poor-visibility frame (poorVisibilityFrames = 1)
        it.plannerConfig = run.basir.engine.PlannerConfig(poorVisibilityFrames = if (variant.visibilityCoast) 3 else 1,
            temporalTracking = variant.temporal, followDistantLines = variant.distantLines)
        it.stabilize = variant.stabilize
        it.useCenterline = variant.centerline
        it.visualEgo = variant.egoYaw && !variant.poseYaw
        it.stabilityConfig = run.basir.engine.StabilityConfig(handoverMaxGapM = if (variant.handover) 1.0 else 0.0)
        it.cornerNavigation = variant.corners
        it.guidanceConfig = run.basir.engine.StableGuidanceConfig(obstacleLevelByDistance = variant.round8Stops)
    }
    private val decisionBridge = com.basir.run.engine.DecisionStabilityHapticBridge(run.basir.engine.GuidanceDecisionStabilityEngine(
        run.basir.engine.DecisionStabilityConfig(obstacleReleaseS = System.getenv("R8_OBSTACLE_RELEASE")?.toDouble() ?: 0.4)))
    private val frameController = run.basir.engine.AdaptiveFrameController()
    private val stableBridge = com.basir.run.engine.StableHapticBridge(run.basir.engine.GuidanceTemporalStabilizer(
        (opt?.let { run.basir.engine.GuidanceStabilizerConfig(obstacleReleaseS = it, cautionReleaseS = minOf(it, 0.6)) } ?: run.basir.engine.GuidanceStabilizerConfig())
            .let { if (variant.sideMemory) it else it.copy(sideMemoryS = 0.0) }))
    /** What actually vibrates: the engine's HapticScheduler, with a recording output. */
    private val played = ArrayList<String>()
    private val scheduler = run.basir.engine.HapticScheduler(object : run.basir.engine.HapticOutput {
        override val hasAmplitudeControl = true
        override fun play(pattern: run.basir.engine.HapticPattern) { played += pattern.name }
        override fun cancel() {}
    }, config = run.basir.engine.SchedulerConfig(
        // 0.14.0: obstacle reminders were only reset by another pattern (a later obstacle could stay silent)
        obstacleEpisodeGapMs = if (variant.round8Stops) run.basir.engine.SchedulerConfig().obstacleEpisodeGapMs else Long.MAX_VALUE,
        uncapHighObstacle = variant.round8Stops, cooldownAllChanges = variant.round8Stops, capCaution = variant.round8Stops))
    private var heldSurface: SurfaceResult? = null
    /** Recorded turn per frame (SANPO camera poses, standing in for the phone's gyroscope), rad. */
    private val egoYaw: DoubleArray? = File(dir, "ego.json").takeIf { variant.poseYaw && it.exists() }?.let { f ->
        val a = JSONArray(f.readText()); DoubleArray(a.length()) { a.getJSONObject(it).getDouble("yawRad") }
    }
    private val tracker = PathTracker()
    private val intrusions = IntrusionDetector()
    private val motion = ObstacleMotionTracker()
    private val obstacleTracker = ObstacleTracker()
    private val confirm = DepthConfirm()
    private val decisionEngine = DecisionEngine(if (variant.round8Stops) com.basir.run.engine.EngineConfig()
        else com.basir.run.engine.EngineConfig(frozenFrameStopMs = 700, latencyStopHoldMs = 0, criticalConfirmMs = 0,
            obstaclesOverSlowness = false)) // = the 0.14.0 rules
    /** Phone timing model (seeded by the clip name): mostly 60-110 ms per frame; a late frame (0.8-1.0 s, a GC pause or a
     *  camera hiccup) about every 25 s; a slow burst (thermal / background load: 300-450 ms per frame for 1.5-3 s) about
     *  every 40 s. Same sequence for every variant of a clip. */
    private val timing = java.util.Random(dir.name.hashCode().toLong())
    private val durations = ArrayDeque<Long>()
    private var burstLeft = 0
    private fun phoneFrame(fps: Int): Pair<Long, Float> {
        if (!variant.phoneTiming) return (1000L / fps) to 0f
        if (burstLeft == 0 && timing.nextDouble() < 1.0 / (40.0 * fps)) burstLeft = (fps * (1.5 + 1.5 * timing.nextDouble())).toInt()
        val d = if (burstLeft > 0) { burstLeft--; 300L + timing.nextInt(150) } else 60L + timing.nextInt(50)
        durations.addLast(d); while (durations.size > 2 * fps) durations.removeFirst()
        val late = timing.nextDouble() < 1.0 / (25.0 * fps)
        val age = if (late) 800L + timing.nextInt(200) else maxOf(1000L / fps, d)
        val sorted = durations.sorted()
        return age to sorted[(kotlin.math.ceil(0.9 * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)].toFloat()
    }
    private val underfoot = UnderfootClasses()
    private val groundCover = run.basir.engine.GroundCoverResolver()
    private var pathBearingDeg: Double? = null
    private var lastLine: com.basir.run.vision.line.LineResult? = null
    /** --latency SURFACE_MS,DEEP_MS: model results arrive this long after their frame (phone-like asynchronous channels). */
    private val latencySurfaceMs: Long = variant.latencySurfaceMs
    private val latencyDeepMs: Long = variant.latencyDeepMs
    private class DeepJob(val readyAtMs: Long, val frameTimeMs: Long, val analysis: com.basir.run.vision.depth.DepthAnalysis?,
                          val detections: List<Detection>, val hazard: com.basir.run.vision.depth.DepthHazard)
    private var pendingDeep: DeepJob? = null
    private var deepLatest: DeepJob? = null
    private var lastDeepTimeMs = -1L
    private var lastDets: List<Detection> = emptyList()
    private var lastFindings: List<com.basir.run.vision.ObstacleFinding> = emptyList()
    private var heldObstacle = ObstacleLevel.None
    private var pendingSurface: Pair<Long, SurfaceResult>? = null

    /** GroundEvidence on a surface result, with the depth analysis only if its frame is close in time (as GuidancePipeline). */
    private fun refine(s0: SurfaceResult, deep: DeepJob?, cam: run.basir.engine.CameraModel): SurfaceResult {
        val a = deep?.takeIf { kotlin.math.abs(it.frameTimeMs - s0.frameTimeMs) <= EVIDENCE_SKEW_MS }?.analysis
        return com.basir.run.vision.GroundEvidence.refineWalkable(s0, a, cam, groundCover)
    }

    init {
        // The app's GroundEvidence rules, switched per variant (all off = unmodified 0.12.2 behaviour)
        com.basir.run.vision.GroundEvidence.config = com.basir.run.vision.GroundEvidence.Config(
            flatDetections = variant.flatDetections, flatSurfaceFindings = variant.intrusionDepth, groundCover = variant.groundCover)
        com.basir.run.vision.PathLinkedHazards.enabled = variant.pathHazards
        DepthHazards.speedScaled = variant.speedScaled
        DepthHazards.ageCompensation = variant.ageComp
        DepthHazards.headCorridor = variant.round8Stops   // 0.14.0: straight corridors, every point 1.1-2.1 m
        DepthHazards.footCorridor = variant.round8Stops   // 0.14.0: full body width at every height
    }

    fun run(out: File) {
        val frameBytes = w * h * 3 / 2
        val yuv = stream("frames.yuv")
        val labels = stream(variant.labelsFile ?: File(dir, "seg_labels.bin"))
        val walkableIn = variant.walkableFile?.let { stream(it) }
        val lineIn = stream("line_prob.bin")
        val dispIn = stream("disparity.bin")
        val dets = File(dir, "detections.jsonl").readLines()
        val calibrationRunnerX = com.basir.run.vision.Calibration().runnerX
        File(out.path + ".info.json").writeText(JSONObject().put("pitchDeg", pitch).put("pitchEstimated", meta.isNull("pitchDeg"))
            .put("heightM", heightM).put("hfovDeg", hfov).put("speedMps", variant.speedMps).put("variant", variant.toString()).toString(1))
        val walkOut = java.io.BufferedOutputStream(java.io.FileOutputStream(out.path + ".walk.bin"))
        out.bufferedWriter().use { wr ->
            for (i in 0 until n) {
                SystemClock.nowMs = 10_000L + i * 1000L / fps
                val now = SystemClock.nowMs
                val buf = ByteArray(frameBytes).also { yuv.readFully(it) }
                val yPlane = buf.copyOfRange(0, w * h)
                val uPlane = buf.copyOfRange(w * h, w * h + w * h / 4)
                val vPlane = buf.copyOfRange(w * h + w * h / 4, frameBytes)
                val frame = CameraFrame(yPlane, w, w, h, 0, uPlane, vPlane, w / 2, 1)
                val lab = ByteArray(segW * segH).also { labels.readFully(it) }.map { it.toInt() and 0xFF }.toIntArray()
                val walkOverride = walkableIn?.let { s -> ByteArray(segW * segH).also { s.readFully(it) } }
                val lineProb = floats(lineIn, lineW * lineH)
                val disp = floats(dispIn, depthW * depthH)
                val detections = parseDetections(dets[i])

                // --- GuidancePipeline.process(), step by step ---
                val t0 = System.nanoTime()
                val quality = FrameQuality.of(frame)
                val estimate = selector.choose(lanes.detect(frame), surfaces.detect(frame))
                val path = tracker.update(estimate, false) // no gyroscope in a recorded clip
                // Depth + object channel (DeepChannel.offer): one frame at a time on its worker. With --latency the result of the
                // frame it took becomes usable latencyDeepMs later and frames arriving meanwhile are not analysed (as on the phone);
                // without it every frame's result is used at once (the 0.13.0 harness).
                val evidenceCam = run.basir.engine.CameraModel(w, h, hfov, heightM, pitch, roll)
                val followed = lastLine?.let { it.trackedPath ?: it.decision.path }
                pendingDeep?.takeIf { now >= it.readyAtMs }?.let { deepLatest = it; pendingDeep = null }
                if (pendingDeep == null) {
                    val a = FloorPlanner(FloorPlanner.Geometry(heightM, pitch, hfov), DepthHazards.RUNNING).analyze(disp, depthW, depthH)
                    val hz = confirm.update(DepthHazards.assess(a, DepthHazards.forSpeed(variant.speedMps), headingDeg = pathBearingDeg, path = followed))
                    val job = DeepJob(now + latencyDeepMs, now, a, detections, hz)
                    if (latencyDeepMs == 0L) deepLatest = job else pendingDeep = job
                }
                val deep = deepLatest?.takeIf { now - it.frameTimeMs <= DEEP_STALE_MS }
                val analysis = deep?.analysis
                val hazard = deep?.hazard ?: com.basir.run.vision.depth.DepthHazard(ObstacleLevel.None, HazardKind.Object, false)
                val detectorRan = deep != null && deep.frameTimeMs != lastDeepTimeMs
                val rawDetections = if (detectorRan) deep!!.detections else emptyList()
                var intrusionRaw = 0
                var surfaceFindingCount = 0
                if (detectorRan) {
                    lastDeepTimeMs = deep!!.frameTimeMs
                    lastDets = com.basir.run.vision.GroundEvidence.filterDetections(rawDetections, analysis, evidenceCam)
                    val metricPath = followed?.takeIf { com.basir.run.vision.PathLinkedHazards.enabled && pitch >= com.basir.run.vision.PathLinkedHazards.MIN_PITCH_DEG }
                    val modelFindings = lastDets.mapNotNull {
                        if (metricPath != null) DepthHazards.forSpeed(variant.speedMps).let { c ->
                            ObstacleAssessment.assessOnPath(it, metricPath, evidenceCam, stopDistanceM = c.stopDistanceM,
                                warnDistanceM = c.safeDistanceM) }
                        else ObstacleAssessment.assess(it, path.estimate, calibrationRunnerX)
                    }
                    val rawSurfaceFindings = if (path.stable) intrusions.detect(frame, path.estimate, calibrationRunnerX) else emptyList()
                    intrusionRaw = rawSurfaceFindings.size
                    val surfaceFindings = com.basir.run.vision.GroundEvidence.filterSurfaceFindings(rawSurfaceFindings, analysis)
                    surfaceFindingCount = surfaceFindings.size
                    val centre = path.estimate.center?.x(1f) ?: calibrationRunnerX
                    lastFindings = motion.annotate(modelFindings, lastDets, centre) + surfaceFindings
                    heldObstacle = obstacleTracker.update(lastFindings)
                }
                val dets = lastDets
                val findings = lastFindings
                val obstacle = heldObstacle
                // Surface channel (WalkableSegmenter + GuidelineSegmenter)
                val runnable = if (walkOverride == null) runnableClasses(lab, segW, segH, underfoot, now) else emptySet()
                val walkGrid = FloatArray(segW * segH) { k ->
                    if (walkOverride != null) (walkOverride[k].toInt() and 0xFF).toFloat().coerceAtMost(1f)
                    else if (lab[k] in runnable) 1f else 0f
                }
                val rawSurface = SurfaceResult(now, nearest(walkGrid, segW, segH, lineW, lineH), ProbabilityMask(lineW, lineH, lineProb), 0, 0,
                    lab, segW, segH, walkGrid)
                // Adaptive keyframes: the heavy surface models only run on keyframes (and only when their worker is free);
                // between results the last one is reused and LineEngine compensates its age by ego-motion.
                val prevLine = lastLine
                val keyDecision = if (!variant.adaptive) null else frameController.decide(run.basir.engine.AdaptiveFrameController.Context(
                    frameTimeMs = now, nowMs = now, newerFrameWaiting = false,
                    trackState = prevLine?.trackState ?: run.basir.engine.TrackState.SEARCHING, trackConfidence = prevLine?.trackConfidence ?: 0.0,
                    curvature = prevLine?.decision?.path?.let { it.curvatureAt(minOf(3.0, it.farM)) } ?: 0.0,
                    yawRateRadPerS = (egoYaw?.getOrNull(i) ?: 0.0) * fps, speedMps = variant.speedMps,
                    obstacleWithinM = prevLine?.decision?.obstacleAheadM, peopleInView = dets.any { it.label == "person" }))
                val keyframe = keyDecision == null || keyDecision.keyframe || (heldSurface == null && pendingSurface == null)
                pendingSurface?.takeIf { now >= it.first }?.let { (_, s0) -> heldSurface = refine(s0, deep, evidenceCam); pendingSurface = null }
                if (keyframe && pendingSurface == null) {
                    if (latencySurfaceMs == 0L) heldSurface = refine(rawSurface, deep, evidenceCam)
                    else pendingSurface = (now + latencySurfaceMs) to rawSurface
                }
                // The app drops surface masks older than SURFACE_STALE_MS (latency mode only, to keep the 0.13.0 harness identical)
                val staleMs = if (variant.staleFix) minOf(1000L, SURFACE_STALE_MS + (keyDecision?.intervalMs ?: 0L) + latencySurfaceMs) else SURFACE_STALE_MS
                val surface = heldSurface?.takeIf { latencySurfaceMs == 0L || now - it.frameTimeMs <= staleMs }
                val walkCells = surface?.walkableGrid ?: walkGrid
                val coverAccepted = walkCells.indices.count { walkCells[it] >= 0.5f && walkGrid[it] < 0.5f }
                val surfaceFindings = List(surfaceFindingCount) { 0 }
                val lineRes = lineEngine.process(frame, hfov, pitch, roll, quality, if (detectorRan || latencyDeepMs == 0L) dets else null,
                    surface, egoYaw?.getOrNull(i)?.times(fps))
                lastLine = lineRes
                pathBearingDeg = lineRes.decision.path?.let { Math.toDegrees(kotlin.math.atan2(it.lateralAt(3.0), 3.0)) }
                val depthHazard = (if (deep != null) hazard.aged((now - deep.frameTimeMs) / 1000.0, variant.speedMps, DepthHazards.forSpeed(variant.speedMps)) else hazard)
                    .takeIf { it.level != ObstacleLevel.None }
                val combined = if (depthHazard != null && depthHazard.level > obstacle) depthHazard.level else obstacle
                val kind = if (depthHazard != null && depthHazard.level >= obstacle) depthHazard.kind else HazardKind.Object
                val (frameAge, p90) = phoneFrame(fps)
                val decision = decisionEngine.decide(EngineInput(nowMs = now, frameAgeMs = frameAge, quality = quality, path = path,
                    obstacle = combined, processingP90Ms = p90, cameraPitchDeg = pitch.toFloat(), obstacleKind = kind, depthStale = false,
                    line = lineRes.command, corner = lineRes.corner.takeIf { variant.corners }))
                val haptic = when {
                    variant.guidanceStab && variant.decisionStab -> decisionBridge.command(decision, now, variant.speedMps, lineRes.trackConfidence)
                    variant.guidanceStab -> stableBridge.command(decision, now)
                    else -> HapticBridge.command(decision)
                }
                val playedNow = haptic?.let { scheduler.onCommand(it, now) }?.name
                val ms = (System.nanoTime() - t0) / 1e6

                walkOut.write(ByteArray(walkCells.size) { if (walkCells[it] >= 0.5f) 1 else 0 })
                wr.write(frameJson(i, now, quality, path, lineRes, findings, intrusionRaw, surfaceFindings.size, obstacle, depthHazard?.level,
                    depthHazard?.kind, combined, kind, decision, haptic, ms, analysis?.floorValid == true, walkCells, coverAccepted)
                    .put("played", playedNow ?: JSONObject.NULL).put("detectionsRaw", rawDetections.size).put("detectionsKept", dets.size).put("keyframe", keyframe).put("deepAgeMs", deep?.let { now - it.frameTimeMs } ?: -1).put("detectorRan", detectorRan)
                    .put("surfaceAgeMs", lineRes.surfaceAgeMs).put("trackState", lineRes.trackState.name).put("trackConfidence", lineRes.trackConfidence)
                    .put("predictedOnly", lineRes.predictedOnly).put("egoYawRad", lineEngine.lastYawRad).put("cornerDebug", lineEngine.cornerDebug)
                    .put("depthDbg", analysis?.takeIf { it.floorValid }?.let { a -> JSONObject()
                        .put("headings", JSONArray(a.headings.map { hf -> JSONArray(listOf(hf.headingDeg, hf.freeLowM, hf.freeHighM)) }))
                        .put("high", JSONArray((a.obstaclePoints ?: FloatArray(0)).toList().chunked(3)
                            .filter { it[0] <= 4.5f }.take(30000).map { JSONArray(listOf(it[0], it[1], it[2])) }))
                        .put("path", followed?.let { c -> JSONArray(listOf(c.lateralAt(1.0), c.lateralAt(2.0), c.lateralAt(3.0), c.lateralAt(4.0), c.farM)) } ?: JSONObject.NULL)
                        .put("bearing", followed?.let { Math.toDegrees(kotlin.math.atan2(it.lateralAt(3.0), 3.0)) } ?: JSONObject.NULL) } ?: JSONObject.NULL)
                    .put("tracked", lineRes.trackedPath?.let { c -> JSONObject().put("coeffs", JSONArray(c.coeffs.toList())).put("nearM", c.nearM).put("farM", c.farM) } ?: JSONObject.NULL)
                    .toString())
                wr.newLine()
            }
        }
        walkOut.close()
    }

    private fun estimatePitch(): Double {
        val s = stream("disparity.bin")
        val got = ArrayList<Double>()
        for (i in 0 until minOf(n, 60)) {
            val a = FloorPlanner(FloorPlanner.Geometry(heightM, null, hfov), DepthHazards.RUNNING).analyze(floats(s, depthW * depthH), depthW, depthH)
            if (a.pitchSource == com.basir.run.vision.depth.PitchSource.Image) got += a.pitchDeg
        }
        s.close()
        return if (got.isEmpty()) 20.0 else got.sorted()[got.size / 2]
    }

    private fun stream(name: String) = stream(File(dir, name))
    private fun stream(f: File) = DataInputStream(BufferedInputStream(FileInputStream(f), 1 shl 20))

    private fun floats(s: DataInputStream, count: Int): FloatArray {
        val b = ByteArray(count * 4).also { s.readFully(it) }
        val fb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(count).also { fb.get(it) }
    }

    private fun parseDetections(line: String): List<Detection> {
        val a = JSONArray(line)
        return (0 until a.length()).map { k ->
            val d = a.getJSONArray(k)
            Detection(d.getString(0), d.getDouble(1).toFloat(), d.getDouble(2).toFloat(), d.getDouble(3).toFloat(),
                d.getDouble(4).toFloat(), d.getDouble(5).toFloat())
        }
    }

    private fun frameJson(
        i: Int, now: Long, q: FrameQuality, path: com.basir.run.vision.TrackedPath, line: com.basir.run.vision.line.LineResult,
        findings: List<com.basir.run.vision.ObstacleFinding>, intrusionRaw: Int, intrusionKept: Int, obstacle: ObstacleLevel,
        depthLevel: ObstacleLevel?, depthKind: HazardKind?, combined: ObstacleLevel, kind: HazardKind,
        decision: com.basir.run.engine.Decision, haptic: run.basir.engine.GuidanceCommand?, ms: Double, floorValid: Boolean,
        walk: FloatArray, coverAccepted: Int,
    ): JSONObject {
        val d = line.decision
        val o = JSONObject()
        o.put("i", i); o.put("t_ms", now)
        o.put("quality", JSONObject().put("usable", q.usable).put("dark", q.tooDark).put("glare", q.glare).put("blurry", q.blurry).put("blocked", q.blocked))
        o.put("b_path", JSONObject().put("status", path.estimate.status.name).put("stable", path.stable).put("confidence", path.estimate.confidence.toDouble()))
        val pj = JSONObject().put("mode", d.mode.name).put("stopReason", d.stopReason.name).put("confidence", d.confidence)
        d.obstacleAheadM?.let { pj.put("obstacleAheadM", it) }
        d.turnSide?.let { pj.put("turnSide", it.name) }
        d.stripHalfWidthM?.let { pj.put("stripHalfWidthM", it) }
        d.blocking?.let { pj.put("blocking", JSONObject().put("label", it.label).put("forward", it.position.forward).put("lateral", it.position.lateral)) }
        d.path?.let { c -> pj.put("curve", JSONObject().put("coeffs", JSONArray(c.coeffs.toList())).put("nearM", c.nearM).put("farM", c.farM).put("rmsM", c.rmsM)) }
        o.put("decision", pj)
        line.rawPath?.let { c -> o.put("raw", JSONObject().put("kind", line.rawKind?.name ?: JSONObject.NULL).put("coeffs", JSONArray(c.coeffs.toList()))
            .put("nearM", c.nearM).put("farM", c.farM)) }
        val cmd = line.command
        o.put("command", JSONObject().put("kind", cmd.kind.name).put("side", cmd.side?.name ?: JSONObject.NULL).put("level", cmd.level?.name ?: JSONObject.NULL)
            .put("state", cmd.state?.name ?: JSONObject.NULL).put("confirm", cmd.confirm))
        o.put("lines", line.lines.size); o.put("lineFromModel", line.lineFromModel)
        fun projected(pts: List<run.basir.engine.GroundPoint>) = JSONArray(pts.mapNotNull { g -> line.camera.groundToImage(g)?.let { JSONArray(listOf(it.first, it.second)) } })
        o.put("linePx", JSONArray(line.lines.take(6).map { lc -> projected(lc.curve.sample(0.25)) }))
        line.freeRoute?.let { o.put("routePx", projected(it.waypoints)) }
        line.freeRoute?.let { r -> o.put("freeRoute", JSONObject().put("progressM", r.progressM).put("steerDeg", r.steerDeg)
            .put("waypoints", JSONArray(r.waypoints.map { JSONArray(listOf(it.forward, it.lateral)) }))) }
        o.put("findings", JSONArray(findings.map { f -> JSONObject().put("label", f.detection.label).put("level", f.level.name).put("score", f.detection.score.toDouble())
            .put("box", JSONArray(listOf(f.detection.left, f.detection.top, f.detection.right, f.detection.bottom).map { it.toDouble() })) }))
        o.put("intrusionRaw", intrusionRaw); o.put("intrusionKept", intrusionKept)
        o.put("obstacle", obstacle.name); o.put("depthHazard", depthLevel?.name ?: "None"); o.put("depthKind", depthKind?.name ?: JSONObject.NULL)
        o.put("floorValid", floorValid)
        o.put("combinedObstacle", combined.name); o.put("hazardKind", kind.name)
        o.put("final", JSONObject().put("state", decision.state.name).put("severity", decision.severity.name).put("cue", decision.cue.name)
            .put("moveTo", decision.moveTo?.name ?: JSONObject.NULL))
        o.put("haptic", haptic?.let { JSONObject().put("kind", it.kind.name).put("side", it.side?.name ?: JSONObject.NULL)
            .put("level", it.level?.name ?: JSONObject.NULL).put("corner", it.corner?.name ?: JSONObject.NULL) } ?: JSONObject.NULL)
        o.put("mode", decision.mode.name)
        line.corner.let { c -> o.put("corner", JSONObject().put("phase", c.phase.name).put("side", c.side?.name ?: JSONObject.NULL)
            .put("distanceM", c.distanceM ?: JSONObject.NULL).put("turnedDeg", c.turnedDeg).put("source", c.source?.name ?: JSONObject.NULL)
            .put("unverified", c.exitUnverified).put("reason", c.reason)) }
        // What gets drawn. Old: VirtualPath, image-space edges of the B detectors (frame fractions).
        // New: GroundRibbon, the followed path projected onto the ground (pixels of line.camera).
        val vp = com.basir.run.vision.VirtualPath.from(path, 0.5f)
        o.put("virtualPath", JSONObject().put("confidence", vp.confidence.name).put("points", JSONArray(vp.points.map { p ->
            JSONArray(listOf(p.y.toDouble(), p.left?.toDouble(), p.right?.toDouble(), p.center?.toDouble()).map { it ?: JSONObject.NULL }) })))
        d.path?.let { c -> run.basir.engine.GroundRibbon.build(c, line.camera) }?.let { rb ->
            o.put("ribbon", JSONObject().put("camW", line.camera.width).put("camH", line.camera.height).put("nearM", rb.nearM).put("farM", rb.farM)
                .put("left", JSONArray(rb.sections.map { JSONArray(listOf(it.leftPx.first, it.leftPx.second)) }))
                .put("right", JSONArray(rb.sections.map { JSONArray(listOf(it.rightPx.first, it.rightPx.second)) }))
                .put("centre", JSONArray(rb.sections.map { JSONArray(listOf(it.centrePx.first, it.centrePx.second)) })))
        }
        o.put("pipelineMs", ms)
        o.put("walkableShare", walk.average())
        o.put("coverAccepted", coverAccepted)
        return o
    }
}

private const val DEEP_STALE_MS = 1500L
private const val SURFACE_STALE_MS = 500L
private const val EVIDENCE_SKEW_MS = 300L

/** WalkableSegmenter.runnableClasses() from app_vision/seg/SurfaceModels.kt (that file needs Android), unchanged. */
fun runnableClasses(label: IntArray, w: Int, h: Int, memory: UnderfootClasses, nowMs: Long): Set<Int> {
    val counts = HashMap<Int, Int>()
    var n = 0
    for (y in (h * 0.8).toInt() until h) for (x in (w * 0.35).toInt() until (w * 0.65).toInt()) {
        counts.merge(label[y * w + x], 1, Int::plus)
        n++
    }
    return memory.update(counts, n, nowMs)
}

/** nearest() from app_vision/seg/SurfaceModels.kt, unchanged. */
fun nearest(src: FloatArray, w: Int, h: Int, outW: Int, outH: Int): ProbabilityMask {
    val mask = ProbabilityMask(outW, outH)
    for (y in 0 until outH) {
        val sy = ((y + 0.5) * h / outH).toInt().coerceIn(0, h - 1)
        for (x in 0 until outW) mask[x, y] = src[sy * w + ((x + 0.5) * w / outW).toInt().coerceIn(0, w - 1)]
    }
    return mask
}
