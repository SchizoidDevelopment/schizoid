/*
 * Copyright (c) 2026. Schizoid
 * All rights reserved.
 */

package dev.lyzev.api.opengl

import com.mojang.blaze3d.systems.RenderSystem
import org.lwjgl.opengl.ARBDrawBuffersBlend
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL33C.*
import org.lwjgl.opengl.GL40C
import org.lwjgl.opengl.GL42C
import org.lwjgl.opengl.GL43C
import org.lwjgl.opengl.GL45C
import org.lwjgl.opengl.ARBClipControl

/** Actual driver state, not guesses based on blend factors or Minecraft's cached enable flags. */
class GlStateSnapshot private constructor() {

    private val caps = GL.getCapabilities()
    private val colorOverride = RenderSystem.outputColorTextureOverride
    private val depthOverride = RenderSystem.outputDepthTextureOverride
    private val indexedBlend = caps.OpenGL40 || caps.GL_ARB_draw_buffers_blend
    private val compute = caps.OpenGL43 || caps.GL_ARB_compute_shader
    private val images = caps.OpenGL42 || caps.GL_ARB_shader_image_load_store
    private val fixedRestart = caps.OpenGL43 || caps.GL_ARB_ES3_compatibility
    private val clipControl = caps.OpenGL45 || caps.GL_ARB_clip_control
    private val program = glGetInteger(GL_CURRENT_PROGRAM)
    private val vao = glGetInteger(GL_VERTEX_ARRAY_BINDING)
    private val arrayBuffer = glGetInteger(GL_ARRAY_BUFFER_BINDING)
    private val elementBuffer = glGetInteger(GL_ELEMENT_ARRAY_BUFFER_BINDING)
    private val readFbo = glGetInteger(GL_READ_FRAMEBUFFER_BINDING)
    private val drawFbo = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING)
    private val viewport = IntArray(4).also { glGetIntegerv(GL_VIEWPORT, it) }
    private val scissorBox = IntArray(4).also { glGetIntegerv(GL_SCISSOR_BOX, it) }
    private val activeTexture = glGetInteger(GL_ACTIVE_TEXTURE)
    private val textureUnits = (listOf(0, 1, 2, 3) + (activeTexture - GL_TEXTURE0)).distinct()
    private val textures = textureUnits.map { unit ->
        glActiveTexture(GL_TEXTURE0 + unit)
        TextureBinding(unit, glGetInteger(GL_TEXTURE_BINDING_2D), glGetIntegeri(GL_SAMPLER_BINDING, unit))
    }.also { glActiveTexture(activeTexture) }
    private val drawBufferCount = glGetInteger(GL_MAX_DRAW_BUFFERS)
    private val blend = Array(drawBufferCount) { index ->
        BlendState(
            glIsEnabledi(GL_BLEND, index),
            integer(GL_BLEND_SRC_RGB, index), integer(GL_BLEND_DST_RGB, index),
            integer(GL_BLEND_SRC_ALPHA, index), integer(GL_BLEND_DST_ALPHA, index),
            integer(GL_BLEND_EQUATION_RGB, index), integer(GL_BLEND_EQUATION_ALPHA, index)
        )
    }
    private val colorMasks = Array(drawBufferCount) { index ->
        IntArray(4).also { glGetIntegeri_v(GL_COLOR_WRITEMASK, index, it) }
    }
    private val blendColor = FloatArray(4).also { glGetFloatv(GL_BLEND_COLOR, it) }
    private val depthTest = glIsEnabled(GL_DEPTH_TEST)
    private val depthMask = glGetBoolean(GL_DEPTH_WRITEMASK)
    private val depthFunc = glGetInteger(GL_DEPTH_FUNC)
    private val cull = glIsEnabled(GL_CULL_FACE)
    private val cullFace = glGetInteger(GL_CULL_FACE_MODE)
    private val frontFace = glGetInteger(GL_FRONT_FACE)
    private val scissor = glIsEnabled(GL_SCISSOR_TEST)
    private val stencil = glIsEnabled(GL_STENCIL_TEST)
    private val rasterizerDiscard = glIsEnabled(GL_RASTERIZER_DISCARD)
    private val primitiveRestart = glIsEnabled(GL_PRIMITIVE_RESTART)
    private val fixedPrimitiveRestart = fixedRestart && glIsEnabled(GL43C.GL_PRIMITIVE_RESTART_FIXED_INDEX)
    private val framebufferSrgb = glIsEnabled(GL_FRAMEBUFFER_SRGB)
    private val clipOrigin = if (clipControl) glGetInteger(GL45C.GL_CLIP_ORIGIN) else 0
    private val clipDepth = if (clipControl) glGetInteger(GL45C.GL_CLIP_DEPTH_MODE) else 0
    private val polygonOffset = glIsEnabled(GL_POLYGON_OFFSET_FILL)
    private val polygonOffsetFactor = glGetFloat(GL_POLYGON_OFFSET_FACTOR)
    private val polygonOffsetUnits = glGetFloat(GL_POLYGON_OFFSET_UNITS)
    private val polygonMode = IntArray(2).also { glGetIntegerv(GL_POLYGON_MODE, it) }
    private val colorLogic = glIsEnabled(GL_COLOR_LOGIC_OP)
    private val logicOp = glGetInteger(GL_LOGIC_OP_MODE)
    private val lineWidth = glGetFloat(GL_LINE_WIDTH)
    private val packBuffer = glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING)
    private val unpackBuffer = glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING)
    private val copyReadBuffer = glGetInteger(GL_COPY_READ_BUFFER)
    private val copyWriteBuffer = glGetInteger(GL_COPY_WRITE_BUFFER)
    private val pixelStore = intArrayOf(
        GL_PACK_ALIGNMENT, GL_PACK_ROW_LENGTH, GL_PACK_SKIP_PIXELS, GL_PACK_SKIP_ROWS,
        GL_UNPACK_ALIGNMENT, GL_UNPACK_ROW_LENGTH, GL_UNPACK_SKIP_PIXELS, GL_UNPACK_SKIP_ROWS,
        GL_UNPACK_IMAGE_HEIGHT, GL_UNPACK_SKIP_IMAGES
    ).associateWith(::glGetInteger)
    private val imageBindings = if (images) Array(2) { index ->
        ImageBinding(
            glGetIntegeri(GL42C.GL_IMAGE_BINDING_NAME, index),
            glGetIntegeri(GL42C.GL_IMAGE_BINDING_LEVEL, index),
            glGetIntegeri(GL42C.GL_IMAGE_BINDING_LAYERED, index) != 0,
            glGetIntegeri(GL42C.GL_IMAGE_BINDING_LAYER, index),
            glGetIntegeri(GL42C.GL_IMAGE_BINDING_ACCESS, index),
            glGetIntegeri(GL42C.GL_IMAGE_BINDING_FORMAT, index)
        )
    } else emptyArray()
    private val storageBuffer = if (compute) glGetInteger(GL43C.GL_SHADER_STORAGE_BUFFER_BINDING) else 0
    private val storageBinding = if (compute) glGetIntegeri(GL43C.GL_SHADER_STORAGE_BUFFER_BINDING, 0) else 0
    private val storageOffset = if (compute) glGetInteger64i(GL43C.GL_SHADER_STORAGE_BUFFER_START, 0) else 0L
    private val storageSize = if (compute) glGetInteger64i(GL43C.GL_SHADER_STORAGE_BUFFER_SIZE, 0) else 0L
    private val indirectBuffer = if (compute) glGetInteger(GL40C.GL_DRAW_INDIRECT_BUFFER_BINDING) else 0

    private fun integer(parameter: Int, index: Int) =
        if (indexedBlend) glGetIntegeri(parameter, index) else glGetInteger(parameter)

    /** A nested vanilla GPU operation must not see caches left stale by the enclosing raw GL pass. */
    internal fun synchronizeMinecraftCache() {
        MinecraftGlStateCache.framebuffers(readFbo, drawFbo)
        MinecraftGlStateCache.viewport(viewport[0], viewport[1], viewport[2], viewport[3])
        textures.forEach { MinecraftGlStateCache.texture(it.unit, it.texture) }
        MinecraftGlStateCache.activeTexture(activeTexture - GL_TEXTURE0)
        blend.forEachIndexed { index, state ->
            MinecraftGlStateCache.blend(
                index, state.enabled, state.srcRgb, state.dstRgb, state.srcAlpha, state.dstAlpha, state.equationRgb, state.equationAlpha
            )
            MinecraftGlStateCache.colorMask(index, maskBits(colorMasks[index]))
        }
        MinecraftGlStateCache.depth(depthTest, depthFunc, depthMask)
        MinecraftGlStateCache.cull(cull)
        MinecraftGlStateCache.scissor(scissor)
        MinecraftGlStateCache.polygonOffset(polygonOffset, polygonOffsetFactor, polygonOffsetUnits)
        MinecraftGlStateCache.colorLogic(colorLogic, logicOp)
    }

    fun restore() {
        RenderSystem.outputColorTextureOverride = colorOverride
        RenderSystem.outputDepthTextureOverride = depthOverride
        val readFbo = if (readFbo == 0 || glIsFramebuffer(readFbo)) readFbo else 0
        val drawFbo = if (drawFbo == 0 || glIsFramebuffer(drawFbo)) drawFbo else 0
        glBindFramebuffer(GL_READ_FRAMEBUFFER, readFbo)
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, drawFbo)
        MinecraftGlStateCache.framebuffers(readFbo, drawFbo)
        glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
        MinecraftGlStateCache.viewport(viewport[0], viewport[1], viewport[2], viewport[3])
        glScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3])

        glUseProgram(if (program == 0 || glIsProgram(program)) program else 0)
        glBindVertexArray(vao)
        glBindBuffer(GL_ARRAY_BUFFER, arrayBuffer)
        if (vao != 0) glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, elementBuffer)
        textures.forEach {
            glActiveTexture(GL_TEXTURE0 + it.unit)
            val binding = if (it.texture == 0 || glIsTexture(it.texture)) it.texture else 0
            glBindTexture(GL_TEXTURE_2D, binding)
            MinecraftGlStateCache.texture(it.unit, binding)
            glBindSampler(it.unit, it.sampler)
        }
        glActiveTexture(activeTexture)
        MinecraftGlStateCache.activeTexture(activeTexture - GL_TEXTURE0)

        val first = blend[0]
        if (!indexedBlend) {
            glBlendFuncSeparate(first.srcRgb, first.dstRgb, first.srcAlpha, first.dstAlpha)
            glBlendEquationSeparate(first.equationRgb, first.equationAlpha)
        }
        blend.forEachIndexed { index, state ->
            if (state.enabled) glEnablei(GL_BLEND, index) else glDisablei(GL_BLEND, index)
            if (indexedBlend) {
                if (caps.OpenGL40) {
                    GL40C.glBlendFuncSeparatei(index, state.srcRgb, state.dstRgb, state.srcAlpha, state.dstAlpha)
                    GL40C.glBlendEquationSeparatei(index, state.equationRgb, state.equationAlpha)
                } else {
                    ARBDrawBuffersBlend.glBlendFuncSeparateiARB(index, state.srcRgb, state.dstRgb, state.srcAlpha, state.dstAlpha)
                    ARBDrawBuffersBlend.glBlendEquationSeparateiARB(index, state.equationRgb, state.equationAlpha)
                }
            }
            MinecraftGlStateCache.blend(
                index, state.enabled, state.srcRgb, state.dstRgb, state.srcAlpha, state.dstAlpha, state.equationRgb, state.equationAlpha
            )
            val mask = colorMasks[index]
            val bits = maskBits(mask)
            glColorMaski(index, mask[0] != 0, mask[1] != 0, mask[2] != 0, mask[3] != 0)
            MinecraftGlStateCache.colorMask(index, bits)
        }
        glBlendColor(blendColor[0], blendColor[1], blendColor[2], blendColor[3])
        enable(GL_DEPTH_TEST, depthTest)
        glDepthFunc(depthFunc)
        glDepthMask(depthMask)
        MinecraftGlStateCache.depth(depthTest, depthFunc, depthMask)
        enable(GL_CULL_FACE, cull)
        MinecraftGlStateCache.cull(cull)
        glCullFace(cullFace)
        glFrontFace(frontFace)
        enable(GL_SCISSOR_TEST, scissor)
        MinecraftGlStateCache.scissor(scissor)
        enable(GL_STENCIL_TEST, stencil)
        enable(GL_RASTERIZER_DISCARD, rasterizerDiscard)
        enable(GL_PRIMITIVE_RESTART, primitiveRestart)
        if (fixedRestart) enable(GL43C.GL_PRIMITIVE_RESTART_FIXED_INDEX, fixedPrimitiveRestart)
        enable(GL_FRAMEBUFFER_SRGB, framebufferSrgb)
        if (clipControl) {
            if (caps.OpenGL45) GL45C.glClipControl(clipOrigin, clipDepth)
            else ARBClipControl.glClipControl(clipOrigin, clipDepth)
        }
        enable(GL_POLYGON_OFFSET_FILL, polygonOffset)
        glPolygonOffset(polygonOffsetFactor, polygonOffsetUnits)
        MinecraftGlStateCache.polygonOffset(polygonOffset, polygonOffsetFactor, polygonOffsetUnits)
        // Core profiles only accept FRONT_AND_BACK, and require the two modes to be identical.
        glPolygonMode(GL_FRONT_AND_BACK, polygonMode[0])
        enable(GL_COLOR_LOGIC_OP, colorLogic)
        glLogicOp(logicOp)
        MinecraftGlStateCache.colorLogic(colorLogic, logicOp)
        glLineWidth(lineWidth)

        imageBindings.forEachIndexed { index, image ->
            GL42C.glBindImageTexture(index, image.texture, image.level, image.layered, image.layer, image.access, image.format)
        }
        if (compute) {
            if (storageBinding != 0 && storageSize > 0L) {
                glBindBufferRange(GL43C.GL_SHADER_STORAGE_BUFFER, 0, storageBinding, storageOffset, storageSize)
            } else {
                glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 0, storageBinding)
            }
            glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, storageBuffer)
            glBindBuffer(GL40C.GL_DRAW_INDIRECT_BUFFER, indirectBuffer)
        }
        glBindBuffer(GL_PIXEL_PACK_BUFFER, packBuffer)
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, unpackBuffer)
        glBindBuffer(GL_COPY_READ_BUFFER, copyReadBuffer)
        glBindBuffer(GL_COPY_WRITE_BUFFER, copyWriteBuffer)
        pixelStore.forEach { (parameter, value) -> glPixelStorei(parameter, value) }
    }

    private fun enable(capability: Int, enabled: Boolean) {
        if (enabled) glEnable(capability) else glDisable(capability)
    }

    private fun maskBits(mask: IntArray): Int =
        mask.indices.fold(0) { bits, component -> bits or (if (mask[component] != 0) 1 shl component else 0) }

    private data class TextureBinding(val unit: Int, val texture: Int, val sampler: Int)
    private data class BlendState(
        val enabled: Boolean, val srcRgb: Int, val dstRgb: Int, val srcAlpha: Int, val dstAlpha: Int,
        val equationRgb: Int, val equationAlpha: Int
    )
    private data class ImageBinding(
        val texture: Int, val level: Int, val layered: Boolean, val layer: Int, val access: Int, val format: Int
    )

    companion object {
        fun capture(): GlStateSnapshot {
            Render.assertOpenGl()
            return GlStateSnapshot()
        }
    }
}
