package dev.lyzev.api.imgui.spatial

import kotlin.math.abs

/** Standalone checks; no world, renderer, test framework or native context. */
object SpatialFaceFitGeometryTest {
    @JvmStatic
    fun main(args: Array<String>) {
        val wall = listOf(rect(0.0, 0.0, 6.0, 4.0))
        near(requireNotNull(fit(wall, 2.0, 2.0, -5.0, 9.0)).x, 1.0)
        near(requireNotNull(fit(wall, 2.0, 2.0, -5.0, 9.0)).y, 3.0)
        // Narrow walls cannot carry oversize rectangles; exact edges remain legal.
        check(fit(listOf(rect(0.0, 0.0, .5, 4.0)), 1.0, 1.0, 0.0, 0.0) == null)
        check(fit(wall, 6.0, 4.0, 3.0, 2.0) != null)
        check(fit(wall, 6.00001, 4.0, 3.0, 2.0) == null)
        // A hole invisible to centre-axis walks still invalidates the whole panel footprint.
        val hole = listOf(rect(3.5, 2.5, 4.0, 3.0))
        val moved = requireNotNull(SpatialFaceFitGeometry.fit(wall, hole, 3.0, 3.0, 3.0, 2.0))
        check(abs(moved.x - 3.0) > .01 || abs(moved.y - 2.0) > .01)
        check(SpatialFaceFitGeometry.fit(wall, hole, 6.0, 4.0, 3.0, 2.0) == null)
        // L-shaped support is not its bounding box; a wide/tall rectangle cannot bridge the cutout.
        val ell = listOf(rect(0.0, 0.0, 1.0, 4.0), rect(1.0, 0.0, 4.0, 1.0))
        check(fit(ell, 2.0, 2.0, 1.0, 1.0) == null)
        val arm = requireNotNull(fit(ell, .8, 2.0, 3.0, 3.0))
        near(arm.x, .6); near(arm.y, 3.0)
        // Multiple coplanar collision boxes join exactly, fractional shapes do not become full blocks.
        val tiles = listOf(rect(-2.0, -1.0, 0.0, 2.0), rect(0.0, -1.0, 2.0, 2.0))
        check(fit(tiles, 3.0, 2.0, 0.0, 0.0) != null)
        check(fit(listOf(rect(0.0, 0.0, .25, 1.0)), .3, .5, .1, .5) == null)
        // A tiny clicked island must not borrow a larger unrelated nearby wall, even inside the scan radius.
        val lone = rect(5.0, 1.0, 6.0, 2.0)
        val nearby = listOf(rect(0.0, 0.0, 3.0, 3.0), lone)
        check(SpatialFaceFitGeometry.fitAnchored(nearby, emptyList(), listOf(lone), 1.75, 1.3125, 5.5, 1.5) == null)
        // A corner touch is not edge connectivity.
        val corner = listOf(rect(0.0, 0.0, 1.0, 1.0), rect(1.0, 1.0, 4.0, 4.0))
        check(SpatialFaceFitGeometry.fitAnchored(corner, emptyList(), listOf(corner[0]), 2.0, 2.0, .5, .5) == null)
        // Occlusion splits otherwise continuous collision support; connectivity must be checked AFTER subtracting it.
        val splitWall = listOf(rect(0.0, 0.0, 6.0, 3.0))
        check(SpatialFaceFitGeometry.fitAnchored(splitWall, listOf(rect(1.0, 0.0, 2.0, 3.0)),
            listOf(rect(0.0, 0.0, 1.0, 1.0)), 2.0, 2.0, .5, .5) == null)
        val connected = listOf(rect(0.0, 0.0, 1.0, 3.0), rect(1.0, 0.0, 3.0, 3.0))
        check(SpatialFaceFitGeometry.fitAnchored(connected, emptyList(), listOf(connected[0]), 2.0, 2.0, .5, 1.5) != null)
        // Unknown cells / non-collision blocks are represented by absent support, not a fallback box.
        check(fit(emptyList(), 1.0, 1.0, 0.0, 0.0) == null)
        check(fit(wall, Double.NaN, 1.0, 0.0, 0.0) == null)
        check(fit(wall, 1.0, 1.0, Double.POSITIVE_INFINITY, 0.0) == null)
        // Resize from a fixed opposite corner: growth past an edge must clamp/refuse, never translate it.
        for (size in listOf(.5, 1.0, 2.0, 4.0, 6.0)) {
            val p = fit(wall, size, 1.0, size / 2, 1.0)
            if (size <= 6.0) near(requireNotNull(p).x, size / 2)
        }
        println("SpatialFaceFitGeometryTest: narrow walls, holes, L-shapes, fractional collision, edges, resize passed")
    }
    private fun rect(x: Double, y: Double, xx: Double, yy: Double) = SpatialFaceFitGeometry.Rect(x, y, xx, yy)
    private fun fit(s: List<SpatialFaceFitGeometry.Rect>, w: Double, h: Double, x: Double, y: Double) =
        SpatialFaceFitGeometry.fit(s, emptyList(), w, h, x, y)
    private fun near(a: Double, b: Double) = check(abs(a - b) < 1e-8) { "$a != $b" }
}
