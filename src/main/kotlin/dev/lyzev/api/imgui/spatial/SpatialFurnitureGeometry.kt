package dev.lyzev.api.imgui.spatial

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** World-space console geometry; deliberately has no camera or presentation dependency. */
internal object SpatialFurnitureGeometry {
    data class Panel(val id: String, val x: Double, val z: Double, val yaw: Float, val floorY: Double, val topY: Double = floorY,
        val pitch: Float = 0f, val centerY: Double = (floorY + topY) * .5,
        val height: Double = topY - floorY, val width: Double = 0.0)
    data class Anchor(val x: Double, val z: Double, val yaw: Float, val floorY: Double, val topY: Double = floorY,
        val pitch: Float = 0f, val centerY: Double = (floorY + topY) * .5, val topAlongUp: Double = (topY - floorY) * .5)

    /** World-up extent of a tilted rectangle (yaw cannot change its vertical extent). */
    fun verticalHalfExtent(height: Double, pitch: Float): Double =
        height * kotlin.math.abs(cos(pitch.toDouble())) * .5

    fun consoleY(anchor: Anchor, height: Double, gap: Double): Double = anchor.topY + gap + height * .5
    data class Position(val x: Double, val y: Double, val z: Double)

    /** Group-local up preserves inclination even when the player looks vertically. */
    fun above(anchor: Anchor, height: Double, gap: Double, side: Double = 0.0): Position {
        val lift = anchor.topAlongUp + gap + height * .5
        val yaw = anchor.yaw.toDouble()
        val pitch = anchor.pitch.toDouble()
        return Position(anchor.x + sin(yaw) * sin(pitch) * lift + cos(yaw) * side,
            anchor.centerY + cos(pitch) * lift,
            anchor.z + cos(yaw) * sin(pitch) * lift - sin(yaw) * side)
    }

    fun anchor(panels: List<Panel>): Anchor? {
        if (panels.isEmpty()) return null
        val sumSin = panels.sumOf { sin(it.yaw.toDouble()) }
        val sumCos = panels.sumOf { cos(it.yaw.toDouble()) }
        // Opposing normals have no circular mean. Pick a stable workspace normal, never camera yaw
        // or atan2(rounding noise); sorting by identity makes this independent of surface iteration order.
        val epsilon = 1.0e-6 * panels.size
        val yaw = if (sumSin * sumSin + sumCos * sumCos > epsilon * epsilon) {
            atan2(sumSin, sumCos).toFloat()
        } else {
            panels.minBy { it.id }.yaw
        }
        val pitch = panels.map { it.pitch.toDouble() }.average().toFloat()
        val cx = panels.sumOf { it.x } / panels.size
        val cy = panels.sumOf { it.centerY } / panels.size
        val cz = panels.sumOf { it.z } / panels.size
        val ux = sin(yaw.toDouble()) * sin(pitch.toDouble())
        val uy = cos(pitch.toDouble())
        val uz = cos(yaw.toDouble()) * sin(pitch.toDouble())
        val topAlongUp = panels.maxOf { panel ->
            val py = panel.yaw.toDouble()
            val pp = panel.pitch.toDouble()
            val vertical = sin(py) * sin(pp) * ux + cos(pp) * uy + cos(py) * sin(pp) * uz
            val horizontal = cos(py) * ux - sin(py) * uz
            (panel.x - cx) * ux + (panel.centerY - cy) * uy + (panel.z - cz) * uz +
                kotlin.math.abs(vertical) * panel.height * .5 + kotlin.math.abs(horizontal) * panel.width * .5
        }
        return Anchor(
            panels.sumOf { it.x } / panels.size,
            panels.sumOf { it.z } / panels.size,
            yaw,
            panels.minOf { it.floorY },
            panels.maxOf { it.topY }, pitch, cy, topAlongUp
        )
    }

    data class EdgeTick(val x: Float, val y: Float, val vertical: Boolean)

    /** Finite nearby workspace only: distant saved panels are not HUD objectives. */
    fun usefulEdgeDistance(distanceSquared: Double): Boolean =
        distanceSquared.isFinite() && distanceSquared in .0625..144.0

    /** Intersect the centre-to-panel direction with an inset screen rectangle; never draw arrows. */
    fun edgeTick(ndcX: Float, ndcY: Float, behind: Boolean, width: Float, height: Float): EdgeTick? {
        if (!width.isFinite() || !height.isFinite() || width < 80f || height < 80f ||
            !ndcX.isFinite() || !ndcY.isFinite()) return null
        // Small deadband avoids a flickering tick when a panel centre just crosses the viewport.
        if (!behind && kotlin.math.abs(ndcX) <= 1.08f && kotlin.math.abs(ndcY) <= 1.08f) return null
        val dx = if (behind) (if (ndcX < 0f) -1f else 1f) else ndcX
        val dy = if (behind) 0f else -ndcY
        val halfX = width * .5f - 18f
        val halfY = height * .5f - 18f
        val screenX = dx * width * .5f
        val screenY = dy * height * .5f
        val ratio = maxOf(kotlin.math.abs(screenX) / halfX, kotlin.math.abs(screenY) / halfY)
        if (ratio <= 0f || !ratio.isFinite()) return null
        val vertical = kotlin.math.abs(screenX) / halfX >= kotlin.math.abs(screenY) / halfY
        return EdgeTick(width * .5f + screenX / ratio, height * .5f + screenY / ratio, vertical)
    }

    /** Nearest-first candidates share a single mark when their directions overlap. Hard HUD budget: 3. */
    fun sparseEdgeTicks(candidates: List<EdgeTick>): List<EdgeTick> {
        val result = ArrayList<EdgeTick>(3)
        for (tick in candidates) {
            if (result.any { kotlin.math.abs(it.x - tick.x) + kotlin.math.abs(it.y - tick.y) < 36f }) continue
            result.add(tick)
            if (result.size == 3) break
        }
        return result
    }

    /** Positive [side] is the right-hand tangent of the workspace normals. */
    fun position(anchor: Anchor, y: Double, side: Double): Position = Position(
        anchor.x + cos(anchor.yaw.toDouble()) * side,
        y,
        anchor.z - sin(anchor.yaw.toDouble()) * side
    )
}
