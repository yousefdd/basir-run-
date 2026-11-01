@file:Suppress("unused", "UNUSED_PARAMETER")
package com.basir.run.vision.seg
class WalkableSegmenter(c: android.content.Context) { val ready = false; val lastTerrain: run.basir.engine.TerrainSummary? = null; fun walkable(b: android.graphics.Bitmap, w: Int, h: Int): run.basir.engine.ProbabilityMask? = null; fun close() {} }
class GuidelineSegmenter(c: android.content.Context) { val ready = false; fun line(b: android.graphics.Bitmap, w: Int, h: Int): run.basir.engine.ProbabilityMask? = null; fun close() {} }
