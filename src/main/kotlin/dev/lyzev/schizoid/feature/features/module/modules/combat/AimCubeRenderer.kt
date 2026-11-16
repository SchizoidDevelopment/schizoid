/*
 * Copyright (c) 2026. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.feature.features.module.modules.combat

import com.mojang.blaze3d.PrimitiveTopology
import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.ByteBufferBuilder
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import dev.lyzev.api.opengl.OpenGlBridge
import dev.lyzev.api.opengl.Render
import dev.lyzev.api.opengl.WrappedFramebuffer
import dev.lyzev.api.opengl.shader.ShaderPosCol
import dev.lyzev.api.opengl.shader.ShaderReflection
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.lwjgl.opengl.GL33C.*
import java.awt.Color

/** Debug geometry only: the caller supplies copied aim/camera data, never a live entity. */
internal object AimCubeRenderer {

    private var reflectionSource: WrappedFramebuffer? = null
    private val corners = arrayOf(
        floatArrayOf(-.04f, -.04f, -.04f), floatArrayOf(.04f, -.04f, -.04f),
        floatArrayOf(-.04f, .04f, -.04f), floatArrayOf(.04f, .04f, -.04f),
        floatArrayOf(-.04f, -.04f, .04f), floatArrayOf(.04f, -.04f, .04f),
        floatArrayOf(-.04f, .04f, .04f), floatArrayOf(.04f, .04f, .04f)
    )
    private val faces = arrayOf(
        intArrayOf(0, 2, 3, 1), intArrayOf(6, 2, 0, 4), intArrayOf(5, 7, 6, 4),
        intArrayOf(3, 7, 5, 1), intArrayOf(7, 3, 2, 6), intArrayOf(0, 1, 5, 4)
    )
    private val normals = arrayOf(
        floatArrayOf(0f, 0f, -1f), floatArrayOf(-1f, 0f, 0f), floatArrayOf(0f, 0f, 1f),
        floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, -1f, 0f)
    )

    fun draw(modelView: Matrix4f, projection: Matrix4f, camera: Vec3, color: Color, reflection: Boolean) = Render.isolated {
        if (reflection) copyScene()
        glEnable(GL_DEPTH_TEST)
        glDepthFunc(GL_LEQUAL)
        glDepthMask(false)
        glDisable(GL_CULL_FACE)
        glEnable(GL_BLEND)
        glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
        val shader = if (reflection) ShaderReflection else ShaderPosCol
        shader.bind()
        shader["ModelViewMat", false] = modelView
        shader["ProjMat", false] = projection
        if (reflection) {
            OpenGlBridge.bindColorTexture(checkNotNull(reflectionSource))
            shader["Tex0"] = 0
            shader["Freq"] = .05f
            shader["CamPos"] = camera.toVector3f()
        }
        ByteBufferBuilder(1024).use { storage ->
            val format = if (reflection) DefaultVertexFormat.POSITION_COLOR_NORMAL else DefaultVertexFormat.POSITION_COLOR
            val builder = BufferBuilder(storage, PrimitiveTopology.QUADS, format)
            for ((index, face) in faces.withIndex()) {
                for (corner in face) {
                    val position = corners[corner]
                    val vertex = builder.addVertex(position[0], position[1], position[2])
                        .setColor(color.red / 255f, color.green / 255f, color.blue / 255f, color.alpha / 255f)
                    if (reflection) {
                        val normal = normals[index]
                        vertex.setNormal(normal[0], normal[1], normal[2])
                    }
                }
            }
            Render.drawMesh(builder.buildOrThrow())
        }
        shader.unbind()
    }

    private fun copyScene() {
        val scene = Render.mainFramebuffer
        val view = OpenGlBridge.colorView(scene)
        val width = view.getWidth(0)
        val height = view.getHeight(0)
        val copy = reflectionSource ?: WrappedFramebuffer("Aim Bot reflection").also { reflectionSource = it }
        copy.resize(width, height)
        glDisable(GL_SCISSOR_TEST)
        glBindFramebuffer(GL_READ_FRAMEBUFFER, OpenGlBridge.framebufferId(scene))
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, copy.fbo)
        glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL_COLOR_BUFFER_BIT, GL_NEAREST)
        Render.bindMainTarget(false)
    }

    fun release() {
        if (reflectionSource == null) return
        Render.recordRenderCall {
            reflectionSource?.delete()
            reflectionSource = null
        }
    }
}
