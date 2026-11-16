/*
 * Copyright (c) 2026. Schizoid
 * All rights reserved.
 */
package dev.lyzev.api.imgui.spatial

import dev.lyzev.schizoid.Schizoid
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.abs

/**
 * Keeps a panel that is pinned to a block face inside that face.
 *
 * A panel is far bigger than a block, so "stick it on the wall" used to mean "hang half of it over
 * the edge into thin air". This walks the supporting cells along the panel's own width and height
 * axes and slides the panel back until its rectangle is supported: smaller than the wall it stops at
 * the edge, bigger than the wall it centres on it.
 */
internal object SpatialFaceClamp {

    /** How far the support search walks before giving up, in blocks. */
    private const val MAX_WALK = 64

    /**
     * Offset to add to the panel centre so its rectangle stays on the face. Zero when it already does.
     *
     * [yaw]/[pitch] are the pose the panel is *heading* for, not its eased pose: during the snap the
     * two differ and the clamp has to match the face the panel will end up on.
     */
    fun offset(
        surface: SpatialSurface, yaw: Float, pitch: Float,
        x: Double, y: Double, z: Double, anchor: SpatialAttachment
    ): Vector3f {
        val level = Schizoid.mc.level ?: return Vector3f()
        val origin = BlockPos(anchor.blockX, anchor.blockY, anchor.blockZ)
        if (!level.isLoaded(origin)) return Vector3f()
        val rotation = Quaternionf().rotateY(yaw).rotateX(pitch)
        val right = rotation.transform(Vector3f(1f, 0f, 0f))
        val up = rotation.transform(Vector3f(0f, 1f, 0f))
        // Attachments are only made on vertical faces, so the panel's local axes are world axes:
        // right is horizontal, up is +Y. Everything below can therefore stay one-dimensional.
        val rightAxis = dominantAxis(right)
        val upAxis = dominantAxis(up)
        val negativeRight = walk(level, origin, rightAxis, -1)
        val positiveRight = walk(level, origin, rightAxis, +1)
        val negativeUp = walk(level, origin, upAxis, -1)
        val positiveUp = walk(level, origin, upAxis, +1)
        val centre = Vector3f(
            (x - (origin.x + .5)).toFloat(),
            (y - (origin.y + .5)).toFloat(),
            (z - (origin.z + .5)).toFloat()
        )
        val localX = centre.dot(right)
        val localY = centre.dot(up)
        // Express the block run in the panel's own axis signs, since +X is not always +block axis.
        val signX = signAlong(right, rightAxis)
        val signY = signAlong(up, upAxis)
        val minX = if (signX > 0f) -(negativeRight + .5f) else -(positiveRight + .5f)
        val maxX = if (signX > 0f) (positiveRight + .5f) else (negativeRight + .5f)
        val minY = if (signY > 0f) -(negativeUp + .5f) else -(positiveUp + .5f)
        val maxY = if (signY > 0f) (positiveUp + .5f) else (negativeUp + .5f)
        val targetX = clampAxis(localX, surface.physicalWidth * .5f, minX, maxX)
        val targetY = clampAxis(localY, surface.physicalHeight * .5f, minY, maxY)
        if (targetX == localX && targetY == localY) return Vector3f()
        return Vector3f(right).mul(targetX - localX).fma(targetY - localY, up)
    }

    /** Slide until the rectangle is inside; if it cannot fit, centre it on the support. */
    private fun clampAxis(centre: Float, half: Float, min: Float, max: Float): Float {
        val span = max - min
        if (!span.isFinite() || span <= 0f || !centre.isFinite() || !half.isFinite()) return centre
        return if (half * 2f <= span) centre.coerceIn(min + half, max - half) else (min + max) * .5f
    }

    /** Consecutive supported blocks beyond [origin] along [axis] in [direction] (-1 or +1). */
    private fun walk(level: Level, origin: BlockPos, axis: BlockPos, direction: Int): Int {
        var count = 0
        while (count < MAX_WALK) {
            val step = count + 1
            val pos = origin.offset(axis.x * step * direction, axis.y * step * direction, axis.z * step * direction)
            if (!level.isLoaded(pos) || level.getBlockState(pos).isAir) return count
            count++
        }
        return count
    }

    /** +1 when [vector] points the same way as the block [axis], -1 when it points back. */
    private fun signAlong(vector: Vector3f, axis: BlockPos): Float {
        val component = if (axis.x != 0) vector.x else if (axis.y != 0) vector.y else vector.z
        val direction = if (axis.x != 0) axis.x else if (axis.y != 0) axis.y else axis.z
        return if (component >= 0f) direction.toFloat() else -direction.toFloat()
    }

    private fun dominantAxis(vector: Vector3f): BlockPos {
        val x = abs(vector.x)
        val y = abs(vector.y)
        val z = abs(vector.z)
        return when {
            x >= y && x >= z -> BlockPos(if (vector.x >= 0f) 1 else -1, 0, 0)
            y >= x && y >= z -> BlockPos(0, if (vector.y >= 0f) 1 else -1, 0)
            else -> BlockPos(0, 0, if (vector.z >= 0f) 1 else -1)
        }
    }
}
