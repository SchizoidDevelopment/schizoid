/*
 * Copyright (c) 2026. Schizoid
 * All rights reserved.
 */

package dev.lyzev.api.opengl

import com.mojang.blaze3d.opengl.GlTexture
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.GpuTextureView
import net.minecraft.client.renderer.texture.AbstractTexture
import org.lwjgl.opengl.GL33C.*
import java.util.IdentityHashMap

/** Attaches the real Minecraft GPU textures to an FBO; never assumes framebuffer 0 is the scene. */
object OpenGlBridge {

    private class Attachment(val fbo: Int) {
        var color = -1
        var depth = -1
        var colorMip = -1
        var depthMip = -1
        var colorView: GpuTextureView? = null
        var depthView: GpuTextureView? = null
    }

    private val framebuffers = IdentityHashMap<RenderTarget, Attachment>()

    @JvmStatic
    fun textureId(texture: GpuTexture): Int {
        Render.assertOpenGl()
        check(texture is GlTexture && !texture.isClosed) { "Texture is not a live OpenGL texture: ${texture.label}" }
        return texture.glId()
    }

    @JvmStatic
    fun textureId(texture: AbstractTexture): Int = textureId(texture.getTexture())

    @JvmStatic
    fun colorTextureId(target: RenderTarget): Int = textureId(colorView(target).texture())

    @JvmStatic
    fun depthTextureId(target: RenderTarget): Int = textureId(checkNotNull(depthView(target)).texture())

    fun colorView(target: RenderTarget): GpuTextureView =
        if (target === Render.mainTarget) RenderSystem.outputColorTextureOverride ?: checkNotNull(target.getColorTextureView())
        else checkNotNull(target.getColorTextureView())

    fun depthView(target: RenderTarget): GpuTextureView? =
        if (target === Render.mainTarget) RenderSystem.outputDepthTextureOverride ?: target.getDepthTextureView()
        else target.getDepthTextureView()

    @JvmStatic
    fun framebufferId(target: RenderTarget): Int {
        Render.assertOpenGl()
        val colorView = colorView(target)
        val depthView = depthView(target)
        val color = textureId(colorView.texture())
        val depth = depthView?.let { textureId(it.texture()) } ?: 0
        val attachment = framebuffers.getOrPut(target) { Attachment(glGenFramebuffers()) }
        if (attachment.colorView !== colorView || attachment.depthView !== depthView ||
            attachment.color != color || attachment.depth != depth ||
            attachment.colorMip != colorView.baseMipLevel() || attachment.depthMip != (depthView?.baseMipLevel() ?: 0)
        ) {
            Render.isolated {
                glBindFramebuffer(GL_FRAMEBUFFER, attachment.fbo)
                attach(GL_COLOR_ATTACHMENT0, colorView)
                // Detach both possibilities when a target changes its depth format.
                glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, 0, 0)
                glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_TEXTURE_2D, 0, 0)
                if (depthView != null) {
                    val aspect = if (depthView.texture().format.hasStencilAspect()) GL_DEPTH_STENCIL_ATTACHMENT else GL_DEPTH_ATTACHMENT
                    attach(aspect, depthView)
                }
                glDrawBuffer(GL_COLOR_ATTACHMENT0)
                glReadBuffer(GL_COLOR_ATTACHMENT0)
                val status = glCheckFramebufferStatus(GL_FRAMEBUFFER)
                check(status == GL_FRAMEBUFFER_COMPLETE) { "Incomplete Schizoid framebuffer: 0x${status.toString(16)}" }
            }
            attachment.color = color
            attachment.depth = depth
            attachment.colorMip = colorView.baseMipLevel()
            attachment.depthMip = depthView?.baseMipLevel() ?: 0
            attachment.colorView = colorView
            attachment.depthView = depthView
        }
        return attachment.fbo
    }

    private fun attach(aspect: Int, view: GpuTextureView) {
        glFramebufferTexture2D(GL_FRAMEBUFFER, aspect, GL_TEXTURE_2D, textureId(view.texture()), view.baseMipLevel())
    }

    @JvmStatic
    fun bindTarget(target: RenderTarget, setViewport: Boolean = true) {
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, framebufferId(target))
        if (setViewport) {
            val view = colorView(target)
            glViewport(0, 0, view.getWidth(0), view.getHeight(0))
            MinecraftGlStateCache.viewport(0, 0, view.getWidth(0), view.getHeight(0))
        }
    }

    @JvmStatic
    fun bindColorTexture(target: RenderTarget, unit: Int = 0) {
        Render.assertOpenGl()
        glActiveTexture(GL_TEXTURE0 + unit)
        glBindSampler(unit, 0)
        glBindTexture(GL_TEXTURE_2D, colorTextureId(target))
    }

    @JvmStatic
    fun releaseTarget(target: RenderTarget) {
        Render.assertOpenGl()
        framebuffers.remove(target)?.let { glDeleteFramebuffers(it.fbo) }
    }

    internal fun invalidateTarget(target: RenderTarget) {
        framebuffers[target]?.apply {
            color = -1
            depth = -1
        }
    }

    @JvmStatic
    fun close() {
        Render.assertOpenGl()
        framebuffers.values.forEach { glDeleteFramebuffers(it.fbo) }
        framebuffers.clear()
    }
}

val RenderTarget.colorAttachment: Int
    get() = OpenGlBridge.colorTextureId(this)
val RenderTarget.depthAttachment: Int
    get() = OpenGlBridge.depthTextureId(this)
val RenderTarget.textureWidth: Int
    get() = OpenGlBridge.colorView(this).getWidth(0)
val RenderTarget.textureHeight: Int
    get() = OpenGlBridge.colorView(this).getHeight(0)
val RenderTarget.viewportWidth: Int
    get() = textureWidth
val RenderTarget.viewportHeight: Int
    get() = textureHeight
val AbstractTexture.glId: Int
    get() = OpenGlBridge.textureId(this)

fun RenderTarget.beginWrite(setViewport: Boolean) = OpenGlBridge.bindTarget(this, setViewport)
fun RenderTarget.beginRead() {
    Render.assertOpenGl()
    glBindTexture(GL_TEXTURE_2D, colorAttachment)
}
fun RenderTarget.endRead() = glBindTexture(GL_TEXTURE_2D, 0)
fun RenderTarget.endWrite() = Render.bindMainTarget(true)
