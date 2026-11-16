/*
 * Copyright (c) 2026. Schizoid
 * All rights reserved.
 */
package dev.lyzev.api.imgui.spatial

import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.abs

/**
 * Lets panels dock to each other instead of only to blocks.
 *
 * Two panels that are roughly coplanar (within a few degrees of the same facing) and close along one
 * axis click together, edges touching with a hairline seam, and take over the host's orientation so
 * a wall built out of panels stays a flat wall. Only the axis that is already separated is snapped;
 * the other one keeps the offset the player aimed with, so "next to" stays "next to" and does not
 * become "aligned to the top edge".
 */
internal object SpatialPanelJoin {

    /** Facing difference still counted as "the same plane", as a dot of the two normals. */
    private const val COPLANAR = .992f // ~7°
    /** Distance from the host's plane that still counts as being on it, in metres. */
    private const val PLANE_TOLERANCE = .35f
    /** How close the edges have to come before they click, in metres. */
    private const val REACH = .18f
    /** Visible seam between two docked panels, in metres. */
    private const val SEAM = .02f

    class Joint(val offset: Vector3f, val yaw: Float, val pitch: Float, val host: String)

    /** Offset that docks [surface] to the nearest panel, or null when nothing is close enough. */
    fun snap(surface: SpatialSurface, x: Double, y: Double, z: Double, yaw: Float, pitch: Float): Joint? {
        val rotation = Quaternionf().rotateY(yaw).rotateX(pitch)
        val myNormal = rotation.transform(Vector3f(0f, 0f, 1f))
        val halfW = surface.physicalWidth * .5f
        val halfH = surface.physicalHeight * .5f
        var best: Joint? = null
        var bestGap = Float.MAX_VALUE
        for (host in SpatialWindows.surfaces) {
            if (host.id == surface.id || host.removeAfterClose || host.isOverlay || host.ephemeral || host.motion.progress <= 0f) continue
            val hostRotation = Quaternionf().rotateY(host.yaw).rotateX(host.pitch)
            val hostRight = hostRotation.transform(Vector3f(1f, 0f, 0f))
            val hostUp = hostRotation.transform(Vector3f(0f, 1f, 0f))
            val hostNormal = hostRotation.transform(Vector3f(0f, 0f, 1f))
            if (myNormal.dot(hostNormal) < COPLANAR) continue
            val delta = Vector3f(
                (x - host.centerX).toFloat(), (y - host.centerY).toFloat(), (z - host.centerZ).toFloat()
            )
            val localX = delta.dot(hostRight)
            val localY = delta.dot(hostUp)
            val localZ = delta.dot(hostNormal)
            if (abs(localZ) > PLANE_TOLERANCE) continue
            val hostHalfW = host.physicalWidth * .5f
            val hostHalfH = host.physicalHeight * .5f
            // Positive = separated, negative = overlapping, per axis.
            val gapX = abs(localX) - (halfW + hostHalfW)
            val gapY = abs(localY) - (halfH + hostHalfH)
            val targetX: Float
            val targetY: Float
            if (gapX <= REACH && gapY <= 0f) {
                // Beside the host: snap the horizontal distance, keep the vertical offset.
                targetX = side(localX) * (halfW + hostHalfW + SEAM)
                targetY = localY
            } else if (gapY <= REACH && gapX <= 0f) {
                // Above or below the host: snap the vertical distance, keep the horizontal offset.
                targetY = side(localY) * (halfH + hostHalfH + SEAM)
                targetX = localX
            } else continue
            val gap = maxOf(gapX, gapY)
            if (gap >= bestGap) continue
            bestGap = gap
            val offset = Vector3f(hostRight).mul(targetX - localX).fma(targetY - localY, hostUp)
            // Flush onto the host's plane as well, so docked panels cannot sit at slightly different depths.
            offset.fma(-localZ, hostNormal)
            best = Joint(offset, host.yaw, host.pitch, host.id)
        }
        return best
    }

    private fun side(value: Float): Float = when {
        value > 0f -> 1f
        value < 0f -> -1f
        // Dead centre: pick a side instead of dividing by zero.
        else -> 1f
    }
}
