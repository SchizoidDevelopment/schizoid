package dev.lyzev.api.imgui.spatial

import kotlin.math.*

/** Pure placement from player eyes and view orientation, independent of the render camera. */
internal object SpatialSpawnGeometry {
    data class Vec(val x: Double, val y: Double, val z: Double) {
        operator fun plus(v: Vec) = Vec(x + v.x, y + v.y, z + v.z)
        operator fun minus(v: Vec) = Vec(x - v.x, y - v.y, z - v.z)
        operator fun times(s: Double) = Vec(x * s, y * s, z * s)
        fun dot(v: Vec) = x * v.x + y * v.y + z * v.z
        fun length() = sqrt(dot(this))
        fun unit() = this * (1.0 / length().coerceAtLeast(1e-12))
    }
    data class Pose(val center: Vec, val yaw: Float, val pitch: Float)

    /**
     * Lateral spacing between neighbouring centres, as a fraction of panel width.
     *
     * A full panel width plus a gap (the old `diagonal + gap`) put the side panels at about ±49°: two
     * of the three default panels were outside a normal FOV on the very first open, so the arc read as
     * "one panel, and something off screen". 0.8 of the width shows three separate, clearly turned
     * panels and still fits all of them in one look.
     */
    private const val SPREAD = .8
    /**
     * Neighbour panels stand slightly further away. Same-depth panels on a fan overlap at their inner
     * edges; pushing the outer two back separates them and makes the "turned towards me" angle visible.
     */
    private const val NEIGHBOUR_DEPTH = .55
    /** Never park a panel closer than this, even if a wall is right in the player's face. */
    private const val MIN_DISTANCE = .45
    /**
     * How much of the gaze pitch moves the workspace, in radians (~26°).
     *
     * Looking down used to drop all three panels into the floor, and the per-panel clearance ray then
     * hit the ground at different distances: the centre came back to 2.0 m, the sides to 2.9 and 3.3 m,
     * and the left panel landed at ndc -1.68, off the screen. The gaze still tilts the workspace, but a
     * steep look no longer pushes it out of reach.
     */
    private const val MAX_SPAWN_PITCH = .45

    /** Yaw/pitch that turn a surface at [center] to face [eye]: normal = eye - center, no roll. */
    fun pose(eye: Vec, center: Vec): Pose {
        val normal = (eye - center).unit()
        return Pose(center, atan2(normal.x, normal.z).toFloat(),
            (-asin(normal.y.coerceIn(-1.0, 1.0))).toFloat())
    }

    /**
     * Left/centre/right in front of the player, each turned to face the eye.
     *
     * [forward] is the player's full look vector, so looking up or down tilts the workspace with the
     * gaze instead of leaving it on a horizontal plane at eye height. The whole fan shares one depth:
     * clearing a wall per panel compressed the three independently and threw the group apart.
     */
    fun layout(
        eye: Vec, forward: Vec, right: Vec, width: Double,
        distance: Double = 3.9, gap: Double = .16,
        clearance: (Vec, Vec) -> Double = { from, to -> (to - from).length() }
    ): List<Pose> {
        require(width > 0 && distance > 0 && gap >= 0)
        val f = forward.unit()
        // Keep the horizontal heading, take a limited amount of its pitch: a workspace that ends up
        // under the player's feet is worse than one that ignores the last few degrees of a steep look.
        val tilt = asin(f.y.coerceIn(-1.0, 1.0)).coerceIn(-MAX_SPAWN_PITCH, MAX_SPAWN_PITCH)
        val flat = Vec(f.x, 0.0, f.z)
        val heading = if (flat.length() > 1e-6) flat.unit() else Vec(0.0, 0.0, 1.0)
        val dir = Vec(heading.x * cos(tilt), sin(tilt), heading.z * cos(tilt))
        val r = right.unit()
        val step = width * SPREAD + gap
        // One clearance for the whole group, measured along the centre ray.
        val wanted = eye + dir * distance
        val reach = clearance(eye, wanted)
        val depth = if (!reach.isFinite() || reach >= distance) distance
        else reach.coerceIn(MIN_DISTANCE, distance)
        // Compress laterally with the depth, so a wall in the face pulls the fan in instead of fanning
        // it wide: the angular spread is what keeps every panel on screen.
        val scale = depth / distance
        return listOf(-1, 0, 1).map { side ->
            val panelDepth = depth + NEIGHBOUR_DEPTH * abs(side) * scale
            val lateral = side * step * scale
            pose(eye, eye + dir * panelDepth + r * lateral)
        }
    }
}
