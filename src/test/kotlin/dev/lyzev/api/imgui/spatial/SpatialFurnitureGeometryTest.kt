package dev.lyzev.api.imgui.spatial

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Standalone geometry checks: no game, native context, renderer or test framework. */
object SpatialFurnitureGeometryTest {
    @JvmStatic
    fun main(args: Array<String>) {
        val panels = listOf(
            panel("left", 99.0, -22.0, -.3f, 65.0, 480.0, 320.0),
            panel("middle", 100.0, -23.0, 0f, 65.0, 480.0, 320.0),
            panel("right", 101.0, -22.0, .3f, 66.0, 480.0, 320.0)
        )
        val anchor = requireNotNull(SpatialFurnitureGeometry.anchor(panels))
        near(anchor.x, 100.0)
        near(anchor.z, -67.0 / 3)
        near(anchor.yaw.toDouble(), 0.0)
        near(anchor.floorY, 64.25)
        near(anchor.topY, 66.75)
        val rail = SpatialFurnitureGeometry.position(anchor, SpatialFurnitureGeometry.consoleY(anchor, 64.0 / 320, .08), 0.0)
        near(rail.x, anchor.x)
        near(rail.z, anchor.z)
        near(rail.y - 64.0 / 320 / 2, anchor.topY + .08)
        check(rail.y > panels.maxOf { it.topY }) { "Console must be above every content panel" }

        // A world point is camera-relative only during rendering. Walking forward/sideways/up or
        // turning the view must reconstruct the exact same world-space shelf, not re-park it.
        for (camera in listOf(Triple(0.0, 0.0, 0.0), Triple(99.0, 65.0, -19.0),
            Triple(103.0, 67.0, -25.0), Triple(-500.0, 50.0, 700.0))) {
            for (heading in listOf(0.0, .8, PI, -2.4)) {
                val current = requireNotNull(SpatialFurnitureGeometry.anchor(panels))
                val position = SpatialFurnitureGeometry.position(current, rail.y, 0.0)
                check(position == rail) { "Walking changed the world-space rail" }
                val dx = position.x - camera.first
                val dz = position.z - camera.third
                val viewX = dx * cos(heading) - dz * sin(heading)
                val viewZ = dx * sin(heading) + dz * cos(heading)
                near(camera.first + viewX * cos(heading) + viewZ * sin(heading), rail.x)
                near(camera.second + (position.y - camera.second), rail.y)
                near(camera.third - viewX * sin(heading) + viewZ * cos(heading), rail.z)
            }
        }

        // Crossing +/-pi uses the circular mean, not zero; opposing normals use a stable panel id.
        val wrapped = requireNotNull(SpatialFurnitureGeometry.anchor(listOf(
            panels[0].copy(yaw = (PI - .1).toFloat()), panels[1].copy(yaw = (-PI + .1).toFloat())
        )))
        near(abs(wrapped.yaw.toDouble()), PI, 1.0e-6)
        val opposite = listOf(panels[0].copy(id = "a", yaw = .7f), panels[1].copy(id = "b", yaw = (.7 + PI).toFloat()))
        val degenerate = requireNotNull(SpatialFurnitureGeometry.anchor(opposite))
        check(degenerate.yaw == .7f)
        check(SpatialFurnitureGeometry.anchor(opposite.reversed()) == degenerate)
        check(SpatialFurnitureGeometry.anchor(emptyList()) == null)
        check(SpatialFurnitureGeometry.anchor(listOf(panels[2]))?.x == panels[2].x)

        // Floors come from each panel's physical height, not a global density or pixel height.
        val scaled = requireNotNull(SpatialFurnitureGeometry.anchor(listOf(
            panel("wide", 1.0, 2.0, 0f, 10.0, 480.0, 160.0),
            panel("small", 3.0, 4.0, 0f, 10.0, 480.0, 640.0)
        )))
        near(scaled.floorY, 8.5)
        near(scaled.topY, 11.5)
        // Physical world-up bounds, not logical height: tilted panels have a smaller vertical span.
        near(SpatialFurnitureGeometry.verticalHalfExtent(3.0, 0f), 1.5)
        near(SpatialFurnitureGeometry.verticalHalfExtent(3.0, (PI / 3).toFloat()), .75, 1.0e-6)
        near(SpatialFurnitureGeometry.verticalHalfExtent(3.0, PI.toFloat()), 1.5, 1.0e-6)
        near(SpatialFurnitureGeometry.verticalHalfExtent(3.0, (PI / 2).toFloat()), 0.0, 1.0e-6)
        // A scale change still leaves exactly the same metre gap below the console.
        for (density in listOf(160.0, 320.0, 640.0)) {
            val height = 64.0 / density
            near(SpatialFurnitureGeometry.consoleY(scaled, height, .08) - height * .5, 11.58)
        }
        // Hiding contents retains their immutable anchor; there is no camera fallback/reparking.
        val retained = anchor.copy()
        check(SpatialFurnitureGeometry.position(retained, rail.y, 0.0) == rail)
        val rotated = anchor.copy(yaw = (PI / 2).toFloat())
        val remote = SpatialFurnitureGeometry.position(rotated, rail.y, 2.0)
        near(remote.x, rotated.x, 1.0e-6)
        near(remote.z, rotated.z - 2.0, 1.0e-6)
        // Console sits above the group in the same inclined plane, not on the world-Y horizon.
        for (pitch in listOf(-1.5f, -.6f, 0f, .7f, 1.5f)) {
            val inclined = requireNotNull(SpatialFurnitureGeometry.anchor(listOf(
                SpatialFurnitureGeometry.Panel("tilted", 4.0, 8.0, .8f, 9.0, 11.0,
                    pitch, 10.0, 2.0, 1.75))))
            near(inclined.pitch.toDouble(), pitch.toDouble())
            val topConsole = SpatialFurnitureGeometry.above(inclined, .25, .08)
            val upX = sin(.8f.toDouble()) * sin(pitch.toDouble())
            val upY = cos(pitch.toDouble())
            val upZ = cos(.8f.toDouble()) * sin(pitch.toDouble())
            val lift = (topConsole.x-4)*upX + (topConsole.y-10)*upY + (topConsole.z-8)*upZ
            near(lift, 1.0+.08+.125)
        }
        // Offscreen affordances must never become a second navigation HUD.
        check(!SpatialFurnitureGeometry.usefulEdgeDistance(Double.NaN))
        check(!SpatialFurnitureGeometry.usefulEdgeDistance(145.0))
        check(SpatialFurnitureGeometry.usefulEdgeDistance(16.0))
        check(SpatialFurnitureGeometry.edgeTick(0f, 0f, false, 1280f, 720f) == null)
        check(SpatialFurnitureGeometry.edgeTick(1.04f, 0f, false, 1280f, 720f) == null)
        check(SpatialFurnitureGeometry.edgeTick(Float.NaN, 0f, false, 1280f, 720f) == null)
        check(SpatialFurnitureGeometry.edgeTick(2f, 0f, false, 20f, 20f) == null)
        val behindLeft = requireNotNull(SpatialFurnitureGeometry.edgeTick(-.5f, 0f, true, 1280f, 720f))
        near(behindLeft.x.toDouble(), 18.0)
        near(behindLeft.y.toDouble(), 360.0)
        check(behindLeft.vertical)
        val behindRight = requireNotNull(SpatialFurnitureGeometry.edgeTick(.5f, 0f, true, 1280f, 720f))
        near(behindRight.x.toDouble(), 1262.0)
        val top = requireNotNull(SpatialFurnitureGeometry.edgeTick(0f, 2f, false, 1280f, 720f))
        near(top.y.toDouble(), 18.0)
        check(!top.vertical)
        for ((width, height) in listOf(1280f to 720f, 800f to 1200f, 80f to 80f)) {
            for (x in listOf(-100f, -2f, 0f, 2f, 100f)) {
                for (y in listOf(-100f, -2f, 2f, 100f)) {
                    val tick = requireNotNull(SpatialFurnitureGeometry.edgeTick(x, y, false, width, height))
                    check(tick.x in 17.999f..(width - 17.999f))
                    check(tick.y in 17.999f..(height - 17.999f))
                }
            }
        }
        val sparse = SpatialFurnitureGeometry.sparseEdgeTicks(listOf(behindLeft, behindLeft.copy(y = 370f),
            behindRight, top, top.copy(x = 800f)))
        check(sparse == listOf(behindLeft, behindRight, top)) { "Merge nearby marks and enforce a three-mark budget" }
        println("SpatialFurnitureGeometryTest: all checks passed")
    }

    private fun panel(id: String, x: Double, z: Double, yaw: Float, y: Double, height: Double, scale: Double) =
        SpatialFurnitureGeometry.Panel(id, x, z, yaw, y - height / scale / 2, y + height / scale / 2)

    private fun near(actual: Double, expected: Double, epsilon: Double = 1.0e-9) {
        check(abs(actual - expected) <= epsilon) { "Expected $expected, got $actual" }
    }
}
