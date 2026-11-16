/*
 * Copyright (c) 2026. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.feature.features.module.modules.render

import org.lwjgl.opengl.GL33.*
import kotlin.math.cos
import kotlin.math.sin

/** A small OpenGL mesh for the client's reflection shader, without vanilla buffer internals. */
internal object TorusGeometry {
    private const val SLICES = 10
    private const val LOOPS = 30
    private const val VERTEX_COUNT = SLICES * LOOPS * 6
    private val angles = FloatArray(VERTEX_COUNT * 4).also { values ->
        var offset = 0
        val triangles = intArrayOf(0, 1, 2, 0, 2, 3)
        for (slice in 0 until SLICES) for (loop in 0 until LOOPS) for (corner in triangles) {
            val theta = (2 * Math.PI * (slice + if (corner == 1 || corner == 2) 1 else 0) / SLICES).toFloat()
            val phi = (2 * Math.PI * (loop + if (corner == 2 || corner == 3) 1 else 0) / LOOPS).toFloat()
            values[offset++] = cos(theta)
            values[offset++] = sin(theta)
            values[offset++] = cos(phi)
            values[offset++] = sin(phi)
        }
    }
    private val vertices = FloatArray(VERTEX_COUNT * 6)
    private var vao = 0
    private var buffer = 0

    fun draw(outerRadius: Float, innerRadius: Float) {
        for (vertex in 0 until VERTEX_COUNT) {
            val angle = vertex * 4
            val cosTheta = angles[angle]
            val sinTheta = angles[angle + 1]
            val cosPhi = angles[angle + 2]
            val sinPhi = angles[angle + 3]
            val radius = outerRadius + innerRadius * cosTheta
            val offset = vertex * 6
            vertices[offset] = radius * cosPhi
            vertices[offset + 1] = radius * sinPhi
            vertices[offset + 2] = innerRadius * sinTheta
            vertices[offset + 3] = cosTheta * cosPhi
            vertices[offset + 4] = cosTheta * sinPhi
            vertices[offset + 5] = sinTheta
        }

        val previousVao = glGetInteger(GL_VERTEX_ARRAY_BINDING)
        val previousBuffer = glGetInteger(GL_ARRAY_BUFFER_BINDING)
        try {
            if (vao == 0) vao = glGenVertexArrays()
            if (buffer == 0) buffer = glGenBuffers()
            glBindVertexArray(vao)
            glBindBuffer(GL_ARRAY_BUFFER, buffer)
            glBufferData(GL_ARRAY_BUFFER, vertices, GL_STREAM_DRAW)
            // Reflection_VP.glsl explicitly uses locations 0 (position) and 1 (normal).
            glEnableVertexAttribArray(0)
            glVertexAttribPointer(0, 3, GL_FLOAT, false, 24, 0L)
            glEnableVertexAttribArray(1)
            glVertexAttribPointer(1, 3, GL_FLOAT, false, 24, 12L)
            glDrawArrays(GL_TRIANGLES, 0, VERTEX_COUNT)
        } finally {
            glBindVertexArray(previousVao)
            glBindBuffer(GL_ARRAY_BUFFER, previousBuffer)
        }
    }

    fun close() {
        if (buffer != 0) glDeleteBuffers(buffer)
        if (vao != 0) glDeleteVertexArrays(vao)
        buffer = 0
        vao = 0
    }
}
