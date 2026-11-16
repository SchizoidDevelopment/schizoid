/*
 * Copyright (c) 2026. Schizoid
 * All rights reserved.
 */
package dev.lyzev.api.imgui.spatial

import dev.lyzev.schizoid.Schizoid
import net.minecraft.core.BlockPos
import kotlin.math.abs
import kotlin.math.floor
import org.joml.Vector3f

/** Loaded collision geometry only: a panel must fit wholly on exposed, coplanar support. */
internal object SpatialFaceClamp {
    private const val RADIUS = 8
    /**
     * Distance between a pinned panel and the collision face, in metres.
     *
     * Public on purpose: `prepare()` derives the expected plane as `x*n + z*n - gap - CLEARANCE`, so
     * whoever parks the panel has to offset it by exactly this number. A caller with its own, slightly
     * different clearance missed the plane check by eight millimetres and no panel could be pinned at
     * all - which reads as "windows stopped sticking to blocks".
     */
    internal const val CLEARANCE = .02
    private const val EPSILON = 1e-5

    /** Immutable support sample: reuse it for a resize gesture's binary search, never across frames. */
    class Support internal constructor(
        private val faces: List<SpatialFaceFitGeometry.Rect>,
        private val blockers: List<SpatialFaceFitGeometry.Rect>,
        private val seeds: List<SpatialFaceFitGeometry.Rect>,
        private val rx: Int, private val rz: Int
    ) {
        fun offset(surface: SpatialSurface, x: Double, y: Double, z: Double): Vector3f? {
            val localX = x * rx + z * rz
            val guard = .0002
            val fit = SpatialFaceFitGeometry.fitAnchored(faces, blockers, seeds,
                surface.physicalWidth.toDouble() + guard * 2, surface.physicalHeight.toDouble() + guard * 2,
                localX, y) ?: return null
            return Vector3f(((fit.x - localX) * rx).toFloat(), (fit.y - y).toFloat(), ((fit.x - localX) * rz).toFloat())
        }
    }

    /** Null means refuse the proposed attachment/edit. Never turn missing support into a zero offset. */
    fun offset(surface: SpatialSurface, yaw: Float, pitch: Float,
               x: Double, y: Double, z: Double, anchor: SpatialAttachment): Vector3f? =
        prepare(yaw, pitch, x, y, z, anchor)?.offset(surface, x, y, z)

    fun prepare(yaw: Float, pitch: Float, x: Double, y: Double, z: Double,
                anchor: SpatialAttachment): Support? {
        if (!listOf(x, y, z, yaw.toDouble(), pitch.toDouble(), anchor.gap.toDouble()).all { it.isFinite() } ||
            anchor.gap !in 0f..4f || abs(pitch) > .0001f) return null
        val (nx, nz) = when (anchor.face) {
            "NORTH" -> 0 to -1
            "SOUTH" -> 0 to 1
            "WEST" -> -1 to 0
            "EAST" -> 1 to 0
            else -> return null
        }
        val expectedYaw = kotlin.math.atan2(nx.toDouble(), nz.toDouble()).toFloat()
        if (abs(SpatialSurface.normalizeAngle(yaw - expectedYaw)) > .0001f) return null
        val level = Schizoid.mc.level ?: return null
        val origin = BlockPos(anchor.blockX, anchor.blockY, anchor.blockZ)
        if (!level.isLoaded(origin) || !level.isLoaded(origin.offset(-1, 0, 0)) ||
            !level.isLoaded(origin.offset(1, 0, 0)) || !level.isLoaded(origin.offset(0, 0, -1)) ||
            !level.isLoaded(origin.offset(0, 0, 1))) return null
        val rx = nz; val rz = -nx
        // Signed normal coordinate makes all four vertical faces use the same outward interval test.
        val proposedPlane = x * nx + z * nz - anchor.gap - CLEARANCE
        // Anchor the plane to a real collision face, not to an arbitrary proposed world position.
        // Fractional faces (slabs/stairs) cannot be reconstructed from the block coordinate alone.
        val planes = level.getBlockState(origin).getCollisionShape(level, origin).toAabbs().map { box ->
            when {
                nx > 0 -> origin.x + box.maxX
                nx < 0 -> -(origin.x + box.minX)
                nz > 0 -> origin.z + box.maxZ
                else -> -(origin.z + box.minZ)
            }
        }
        val plane = planes.minByOrNull { abs(it - proposedPlane) } ?: return null
        if (abs(plane - proposedPlane) > EPSILON) return null
        val localX = x * rx + z * rz
        val faces = ArrayList<SpatialFaceFitGeometry.Rect>()
        val blockers = ArrayList<SpatialFaceFitGeometry.Rect>()
        val seeds = ArrayList<SpatialFaceFitGeometry.Rect>()
        val tangent = floor(localX).toInt()
        val baseY = floor(y).toInt()
        val normalStart = floor(plane).toInt() - 1
        val normalEnd = floor(plane + anchor.gap + CLEARANCE).toInt() + 1
        // Support is proven by the complete rectangle, not merely by the original anchor block.
        for (u in tangent - RADIUS..tangent + RADIUS) for (v in baseY - RADIUS..baseY + RADIUS) {
            for (n in normalStart..normalEnd) {
                // Reflecting an integer cell maps [n,n+1] to [-n-1,-n], not [-n,-n+1].
                val bx = if (nx != 0) (if (nx > 0) n else -n - 1) else (if (rx > 0) u else -u - 1)
                val bz = if (nz != 0) (if (nz > 0) n else -n - 1) else (if (rz > 0) u else -u - 1)
                val pos = BlockPos(bx, v, bz)
                // Neither collision lookup nor neighbouring shapes may request an unloaded chunk.
                if (!level.isLoaded(pos) || !level.isLoaded(pos.offset(-1, 0, 0)) ||
                    !level.isLoaded(pos.offset(1, 0, 0)) || !level.isLoaded(pos.offset(0, 0, -1)) ||
                    !level.isLoaded(pos.offset(0, 0, 1))) {
                    // Unknown outward volume is an obstruction, not empty space. This also prevents a
                    // loaded support face from being accepted across an unloaded chunk boundary.
                    if (n + 1 > plane && n < plane + anchor.gap + CLEARANCE)
                        blockers.add(SpatialFaceFitGeometry.Rect(u.toDouble(), v.toDouble(), u + 1.0, v + 1.0))
                    continue
                }
                for (box in level.getBlockState(pos).getCollisionShape(level, pos).toAabbs()) {
                    val x0 = bx + box.minX; val x1 = bx + box.maxX
                    val z0 = bz + box.minZ; val z1 = bz + box.maxZ
                    val a = if (nx != 0) x0 * nx else z0 * nz
                    val b = if (nx != 0) x1 * nx else z1 * nz
                    val low = minOf(a, b); val high = maxOf(a, b)
                    val c = if (rx != 0) x0 * rx else z0 * rz
                    val d = if (rx != 0) x1 * rx else z1 * rz
                    val rect = SpatialFaceFitGeometry.Rect(minOf(c, d), v + box.minY, maxOf(c, d), v + box.maxY)
                    if (abs(high - plane) <= EPSILON) {
                        faces.add(rect)
                        if (pos == origin) seeds.add(rect)
                    }
                    if (high > plane + EPSILON && low < plane + anchor.gap + CLEARANCE + EPSILON) blockers.add(rect)
                }
            }
        }
        if (seeds.isEmpty()) return null
        return Support(faces, blockers, seeds, rx, rz)
    }
}
