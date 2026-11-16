package dev.lyzev.api.imgui.spatial

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Matches spatialwindow_fp.glsl curved()/panelMask(), in top-left canvas coordinates (Y down). */
internal object SpatialCanvasMapping {
    data class Point(val x: Float, val y: Float)

    /** Forward shader sampling map, not its inverse; stays unbounded for captured widget drags. */
    fun curved(u: Float, v: Float, curvature: Float): Point {
        val x = u * 2f - 1f
        val y = v * 2f - 1f
        val scale = 1f + curvature * (x * x + y * y)
        return Point(.5f + .5f * x * scale, .5f + .5f * y * scale)
    }

    /** A strictly positive shader mask: excludes fully transparent corners and distorted quad edges. */
    fun visible(u: Float, v: Float, curvature: Float, cornerRadius: Float): Point? {
        if (!u.isFinite() || !v.isFinite() || !curvature.isFinite() || !cornerRadius.isFinite()) return null
        if (u !in 0f..1f || v !in 0f..1f) return null
        val point = curved(u, v, curvature)
        if (point.x !in 0f..1f || point.y !in 0f..1f) return null
        val dx = abs(point.x - .5f) - (.5f - cornerRadius)
        val dy = abs(point.y - .5f) - (.5f - cornerRadius)
        val ox = max(dx, 0f)
        val oy = max(dy, 0f)
        val distance = sqrt(ox * ox + oy * oy) + min(max(dx, dy), 0f) - cornerRadius
        return point.takeIf { distance < 0f }
    }
}
