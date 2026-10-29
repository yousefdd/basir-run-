package com.basir.run.vision

enum class Side { Left, Right }

enum class PathStatus {
    /** Both sides of the runnable path were found and agree with each other. */
    Tracked,
    /** Only one side was found. The centre is not inferred from it. */
    EdgeOnly,
    /** No trustworthy path in this frame. */
    Unclear,
}

enum class PathSource { PaintedLines, SurfaceRegion }

/** What the detector actually saw on one sampled row: edge positions as frame fractions, or null. */
data class RowEvidence(val y: Float, val left: Float?, val right: Float?)

/** x = a·y² + b·y + c in frame fractions: x 0 = left edge, y 0 = top, y 1 = nearest the runner. */
data class Curve(val a: Float, val b: Float, val c: Float) {
    fun x(y: Float) = a * y * y + b * y + c
    fun slope(y: Float) = 2 * a * y + b
}

/**
 * Where the runner is relative to the path, measured during calibration while standing in the
 * middle of it. The image centre is not assumed to be the runner: the harness can sit off-centre
 * or tilted.
 */
data class Calibration(
    /** Horizontal position (frame fraction) of the path centre on the bottom row while centred. */
    val runnerX: Float = 0.5f,
    /** Half the path width on the bottom row, in frame fractions, as measured; null before calibration. */
    val halfWidth: Float? = null,
)

data class PathEstimate(
    val status: PathStatus,
    val source: PathSource,
    /** 0..1: how much of the expected evidence was found and how well it fits. */
    val confidence: Float = 0f,
    val left: Curve? = null,
    val right: Curve? = null,
    val center: Curve? = null,
    /** Runner across the path: 0 centred, +1 on the right edge, -1 on the left edge. Only meaningful when Tracked. */
    val offset: Float = 0f,
    /** How the path bends further ahead; positive bends right. Only meaningful when Tracked. */
    val curvature: Float = 0f,
    /** For EdgeOnly: which side the visible edge is on. */
    val edgeSide: Side? = null,
    /** For EdgeOnly: distance from runner to that edge in frame fractions on the bottom row. */
    val edgeDistance: Float = 1f,
    /** Several candidate lines close together, so the detector cannot tell which one bounds the path. */
    val ambiguous: Boolean = false,
    val roiTop: Float = 0.45f,
    val aspect: Float = 0.75f,
    /** Raw per-row observations behind the fitted curves, for the virtual path and the debug view. */
    val evidence: List<RowEvidence> = emptyList(),
) {
    companion object {
        fun unclear(source: PathSource, aspect: Float, ambiguous: Boolean = false) =
            PathEstimate(PathStatus.Unclear, source, aspect = aspect, ambiguous = ambiguous)

        /** Builds the result from both edges; offset and bend are measured from the centre line. */
        fun fromEdges(
            left: Curve,
            right: Curve,
            calibration: Calibration,
            source: PathSource,
            confidence: Float,
            roiTop: Float,
            aspect: Float,
        ): PathEstimate {
            val center = Curve((left.a + right.a) / 2, (left.b + right.b) / 2, (left.c + right.c) / 2)
            val half = (right.x(1f) - left.x(1f)) / 2
            val offset = (calibration.runnerX - center.x(1f)) / half
            val curvature = (center.slope(1f) - center.slope(roiTop)) * aspect
            return PathEstimate(
                status = PathStatus.Tracked,
                source = source,
                confidence = confidence.coerceIn(0f, 1f),
                left = left,
                right = right,
                center = center,
                offset = offset.coerceIn(-2f, 2f),
                curvature = curvature,
                roiTop = roiTop,
                aspect = aspect,
            )
        }

        fun fromSingleEdge(
            edge: Curve,
            side: Side,
            calibration: Calibration,
            source: PathSource,
            confidence: Float,
            roiTop: Float,
            aspect: Float,
        ) = PathEstimate(
            status = PathStatus.EdgeOnly,
            source = source,
            confidence = confidence.coerceIn(0f, 1f),
            left = if (side == Side.Left) edge else null,
            right = if (side == Side.Right) edge else null,
            edgeSide = side,
            edgeDistance = kotlin.math.abs(edge.x(1f) - calibration.runnerX),
            roiTop = roiTop,
            aspect = aspect,
        )
    }
}
