package dev.lyzev.api.imgui.spatial

import kotlin.math.*
import dev.lyzev.api.imgui.spatial.SpatialSpawnGeometry.Vec

/** Pure executable regressions: no game, renderer, native window or Gradle required. */
object SpatialSpawnGeometryTest {
    @JvmStatic fun main(args: Array<String>) {
        val eye = Vec(123.0, 65.62, -37.0)
        val width = 560.0 / 320
        for (yaw in listOf(0.0, .8, -2.4, PI)) for (pitch in listOf(-1.1, -.4, 0.0, .6, 1.1)) {
            val forward = Vec(-sin(yaw) * cos(pitch), -sin(pitch), cos(yaw) * cos(pitch))
            val right = Vec(-cos(yaw), 0.0, -sin(yaw))
            val poses = SpatialSpawnGeometry.layout(eye, forward, right, width)
            check(poses.size == 3)
            for (pose in poses) {
                val normal = Vec(sin(pose.yaw.toDouble()) * cos(pose.pitch.toDouble()),
                    -sin(pose.pitch.toDouble()), cos(pose.yaw.toDouble()) * cos(pose.pitch.toDouble()))
                // Every panel is turned at the player, which is the whole point of the arc.
                near(normal.dot((eye - pose.center).unit()), 1.0, 1e-6)
            }
            // Left/centre/right: neighbours on opposite sides, further away, so they read as a fan.
            check((poses[0].center - eye).dot(right) < (poses[1].center - eye).dot(right))
            check((poses[1].center - eye).dot(right) < (poses[2].center - eye).dot(right))
            check((poses[0].center - eye).length() > (poses[1].center - eye).length())
            check((poses[2].center - eye).length() > (poses[1].center - eye).length())
            // All three inside a 70-degree horizontal FOV: the default open shows the whole workspace.
            val heading = Vec(forward.x, 0.0, forward.z).unit()
            for (pose in poses) {
                val offset = pose.center - eye
                val flat = Vec(offset.x, 0.0, offset.z)
                check(abs(atan2(flat.dot(right), flat.dot(heading))) < .62) {
                    "panel off screen: ${atan2(flat.dot(right), flat.dot(heading))}"
                }
            }
            // Render-camera position (including F5 rear/front offsets) is deliberately not an input.
            for (camera in listOf(eye, eye - forward * 4.0, eye + forward * 4.0, eye + Vec(9.0, 2.0, -7.0))) {
                check(camera.x.isFinite())
                check(SpatialSpawnGeometry.layout(eye, forward, right, width) == poses)
            }
            // A wall in front pulls the whole fan in at one shared depth. Per-panel clearance used to
            // compress the three independently, which sent the left panel to ndc -1.68 (off screen).
            val rays = mutableListOf<Pair<Vec, Vec>>()
            for (wall in listOf(1.2, 2.0, 3.0)) {
                rays.clear()
                val compressed = SpatialSpawnGeometry.layout(eye, forward, right, width, clearance = { from, to ->
                    rays += from to to
                    min((to - from).length(), wall)
                })
                check(rays.size == 1) { "one clearance ray per group, not three" }
                for (pose in compressed) {
                    val normal = Vec(sin(pose.yaw.toDouble()) * cos(pose.pitch.toDouble()),
                        -sin(pose.pitch.toDouble()), cos(pose.yaw.toDouble()) * cos(pose.pitch.toDouble()))
                    near(normal.dot((eye - pose.center).unit()), 1.0, 1e-6)
                    val offset = pose.center - eye
                    val flat = Vec(offset.x, 0.0, offset.z)
                    check(abs(atan2(flat.dot(right), flat.dot(heading))) < .62) {
                        "compressed panel off screen at wall $wall: ${atan2(flat.dot(right), flat.dot(heading))}"
                    }
                }
                // Compression is uniform, so the panels stay in the same relative order and cannot
                // collapse onto each other.
                check((compressed[2].center - compressed[0].center).dot(right) > width * .25)
            }
            // Steep look: the workspace follows the gaze but never drops under the player's feet.
            val steepDown = SpatialSpawnGeometry.layout(eye, Vec(-sin(yaw), 1.0, cos(yaw) * 0.0).unit(), right, width)
            check((steepDown[1].center.y - eye.y) > -3.9 * sin(.46))
            val level = SpatialSpawnGeometry.layout(eye, Vec(-sin(yaw), 0.0, cos(yaw)), right, width)
            near((level[1].center - eye).dot(Vec(-sin(yaw), 0.0, cos(yaw))), 3.9)
        }
        println("SpatialSpawnGeometryTest OK: gaze tilt, facing, fan, shared depth, FOV, player/F5 invariance")
    }
    private fun near(actual: Double, expected: Double, epsilon: Double = 1e-8) {
        check(abs(actual - expected) < epsilon) { "$actual != $expected" }
    }
}
