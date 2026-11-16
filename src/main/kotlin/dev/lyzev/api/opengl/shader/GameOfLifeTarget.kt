/*
 * Copyright (c) 2026. Schizoid
 * All rights reserved.
 */
package dev.lyzev.api.opengl.shader

import dev.lyzev.api.opengl.Render
import dev.lyzev.api.opengl.WrappedFramebuffer
import java.util.UUID

/** Independent ping-pong cells and tick history for a single world canvas. */
class GameOfLifeTarget internal constructor() : AutoCloseable {
    private val id = UUID.randomUUID().toString()
    internal var before: WrappedFramebuffer? = null
        private set
    internal var after: WrappedFramebuffer? = null
        private set
    internal var width = 0
        private set
    internal var height = 0
        private set
    private var configuration = ""
    internal var needsSeed = true
    internal var lastUpdateTime = 0L
    internal var closed = false
        private set

    internal fun prepare(canvasWidth: Int, canvasHeight: Int, lod: Int, rules: String, revision: Long) {
        check(!closed) { "Game of Life canvas is closed" }
        val nextWidth = (canvasWidth shr lod.coerceIn(0, 30)).coerceAtLeast(1)
        val nextHeight = (canvasHeight shr lod.coerceIn(0, 30)).coerceAtLeast(1)
        val nextConfiguration = "$lod:$rules:$revision"
        if (width == nextWidth && height == nextHeight && configuration == nextConfiguration) return
        releaseGpu()
        try {
            before = WrappedFramebuffer("WorldLife#$id:before", width = nextWidth, height = nextHeight,
                fixedSize = true, linear = false)
            after = WrappedFramebuffer("WorldLife#$id:after", width = nextWidth, height = nextHeight,
                fixedSize = true, linear = false)
            width = nextWidth
            height = nextHeight
            configuration = nextConfiguration
            needsSeed = true
            lastUpdateTime = System.currentTimeMillis()
        } catch (failure: Throwable) {
            releaseGpu()
            throw failure
        }
    }

    internal fun swap() {
        val previous = before
        before = after
        after = previous
    }

    private fun releaseGpu() {
        Render.assertOpenGl()
        before?.delete()
        after?.delete()
        before = null
        after = null
        width = 0
        height = 0
        configuration = ""
    }

    override fun close() {
        if (closed) return
        releaseGpu()
        closed = true
    }
}
