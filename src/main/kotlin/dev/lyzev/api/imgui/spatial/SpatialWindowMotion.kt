/*
 * Copyright (c) 2026. Schizoid
 * All rights reserved.
 */
package dev.lyzev.api.imgui.spatial

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Presentation only: never changes the persisted world pose or the canvas raster size. */
class SpatialWindowMotion {
    var progress = 0f
        private set
    private var from = 0f
    private var target = 0f
    private var startedAt = 0L
    private var duration = 1L
    private var lastIdleNanos = 0L

    /**
     * Idle hover of a *free-floating* panel, in panel-local metres and radians. Two sines with
     * incommensurate periods per axis, so the drift never visibly loops, and an amplitude small
     * enough that the panel reads as "held in the air" instead of "wobbling". Anchored panels are
     * pinned to a block face and get none of this.
     */
    var swayX = 0f
        private set
    var swayY = 0f
        private set
    var swayYaw = 0f
        private set
    var swayPitch = 0f
        private set

    /**
     * Lean of a panel being carried, in radians, set by the input router from the distance between
     * the follow target and the current pose. It is inertia, not an animation: it appears while the
     * panel lags and decays to zero once it catches up.
     */
    var tiltX = 0f
    var tiltY = 0f

    val alpha: Float get() = progress
    val scale: Float get() = .88f + .12f * progress
    val depthOffset: Float get() = -.18f * (1f - progress)
    val drawable: Boolean get() = progress > 0f
    val opening: Boolean get() = target == 1f

    /**
     * Appear fully formed. Camera-side furniture is not an object being taken out of a bag: a toolbar
     * that scales and fades in front of the player's eyes every time the workspace opens is noise.
     */
    fun instant() {
        from = 1f
        target = 1f
        progress = 1f
        startedAt = 0L
        duration = 1L
    }

    fun setOpen(open: Boolean, now: Long = System.nanoTime()) {
        advance(now)
        val next = if (open) 1f else 0f
        if (target == next) return
        from = progress
        target = next
        startedAt = now
        val fullDuration = if (open) 220_000_000L else 150_000_000L
        duration = (fullDuration * abs(target - from)).toLong().coerceAtLeast(1L)
    }

    fun advance(now: Long = System.nanoTime()) {
        if (progress == target) return
        val t = ((now - startedAt).toDouble() / duration).coerceIn(0.0, 1.0).toFloat()
        val remaining = 1f - t
        val eased = 1f - remaining * remaining * remaining
        progress = if (t >= 1f) target else from + (target - from) * eased
    }

    /**
     * Refresh the idle motion for this frame. Runs for every surface, drawn or not, so nothing that
     * is about to become visible starts from a stale pose.
     */
    fun idle(now: Long, phase: Float, anchored: Boolean) {
        val previous = lastIdleNanos
        lastIdleNanos = now
        // A stale lean must not stick to a released panel.
        val dt = if (previous == 0L) 0f else ((now - previous) / 1_000_000_000.0).toFloat().coerceIn(0f, .1f)
        val decay = (1f - dt * 12f).coerceIn(0f, 1f)
        tiltX *= decay
        tiltY *= decay
        if (anchored) {
            swayX = 0f
            swayY = 0f
            swayYaw = 0f
            swayPitch = 0f
            return
        }
        val t = (now / 1_000_000_000.0).toFloat()
        swayX = .02f * (sin(t * .61f + phase) + .55f * sin(t * 1.07f + phase * 1.7f))
        swayY = .015f * (cos(t * .47f + phase * 2.3f) + .5f * sin(t * .93f + phase))
        swayYaw = .0045f * sin(t * .53f + phase * 1.3f)
        swayPitch = .0035f * sin(t * .71f + phase * .7f)
    }
}
