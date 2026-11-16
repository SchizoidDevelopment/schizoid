/*
 * Copyright (c) 2026. Schizoid
 * All rights reserved.
 */

package dev.lyzev.api.opengl

import com.mojang.blaze3d.PrimitiveTopology
import com.mojang.blaze3d.opengl.GlConst
import com.mojang.blaze3d.vertex.MeshData
import org.lwjgl.opengl.GL33C.*
import java.util.Locale

/** Uploads BufferBuilder meshes to the currently bound Schizoid shader, without a vanilla GPU pipeline. */
object OpenGlMesh {

    private var vao = 0
    private var vertices = 0
    private var indices = 0

    /** Takes ownership of the finished mesh, just as the old BufferRenderer.draw did. */
    @JvmStatic
    fun draw(mesh: MeshData) = mesh.use {
        Render.isolated {
            if (vao == 0) {
                vao = glGenVertexArrays()
                vertices = glGenBuffers()
                indices = glGenBuffers()
            }
            val state = mesh.drawState()
            val format = state.format()
            check(format.stepRate == 0) { "Instanced vertex formats need an instanced draw" }
            val program = glGetInteger(GL_CURRENT_PROGRAM)
            check(program != 0) { "Bind a Schizoid shader before drawing a mesh" }
            glBindVertexArray(vao)
            glBindBuffer(GL_ARRAY_BUFFER, vertices)
            glBufferData(GL_ARRAY_BUFFER, mesh.vertexBuffer(), GL_STREAM_DRAW)
            for (attribute in 0 until glGetInteger(GL_MAX_VERTEX_ATTRIBS)) glDisableVertexAttribArray(attribute)
            for (element in format.elements) {
                val name = element.name()
                val aliases = when (name.lowercase(Locale.ROOT)) {
                    "position" -> listOf(name, "pos", "position", "Position")
                    "color" -> listOf(name, "col", "color", "Color")
                    "normal" -> listOf(name, "normal", "Normal")
                    "uv0" -> listOf(name, "uv", "UV0", "texCoord")
                    else -> listOf(name)
                }
                val attribute = aliases.firstNotNullOfOrNull { alias ->
                    glGetAttribLocation(program, alias).takeIf { it >= 0 }
                } ?: continue
                val componentFormat = element.format()
                val type = GlConst.toGlType(componentFormat)
                if (GlConst.isGlFormatInteger(GlConst.toGlExternalId(componentFormat))) {
                    glVertexAttribIPointer(attribute, componentFormat.componentCount(), type, format.vertexSize, element.offset().toLong())
                } else {
                    glVertexAttribPointer(
                        attribute, componentFormat.componentCount(), type, GlConst.isFormatNormalized(componentFormat),
                        format.vertexSize, element.offset().toLong()
                    )
                }
                glEnableVertexAttribArray(attribute)
            }

            val topology = state.primitiveTopology()
            val indexData = mesh.indexBuffer()
            if (indexData != null) {
                glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, indices)
                glBufferData(GL_ELEMENT_ARRAY_BUFFER, indexData, GL_STREAM_DRAW)
                glDrawElements(GlConst.toGl(topology), state.indexCount(), GlConst.toGl(state.indexType()), 0L)
            } else if (topology == PrimitiveTopology.QUADS || topology == PrimitiveTopology.LINES) {
                val quadIndices = IntArray(state.indexCount())
                val pattern = if (topology == PrimitiveTopology.QUADS) intArrayOf(0, 1, 2, 2, 3, 0)
                else intArrayOf(0, 1, 2, 3, 2, 1)
                for (index in quadIndices.indices) quadIndices[index] = index / 6 * 4 + pattern[index % 6]
                glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, indices)
                glBufferData(GL_ELEMENT_ARRAY_BUFFER, quadIndices, GL_STREAM_DRAW)
                glDrawElements(GL_TRIANGLES, quadIndices.size, GL_UNSIGNED_INT, 0L)
            } else glDrawArrays(GlConst.toGl(topology), 0, state.vertexCount())
        }
    }

    @JvmStatic
    fun close() {
        Render.assertOpenGl()
        if (vao != 0) glDeleteVertexArrays(vao)
        if (vertices != 0) glDeleteBuffers(vertices)
        if (indices != 0) glDeleteBuffers(indices)
        vao = 0
        vertices = 0
        indices = 0
    }
}
