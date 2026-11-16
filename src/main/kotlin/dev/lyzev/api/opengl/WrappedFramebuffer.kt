/*
 * Copyright (c) 2023. Schizoid
 * All rights reserved.
 */

package dev.lyzev.api.opengl

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.GpuTextureView
import dev.lyzev.api.events.EventListener
import dev.lyzev.api.events.EventWindowResize
import dev.lyzev.api.events.on
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import org.lwjgl.opengl.GL33C.*
import java.util.UUID

/** A real RenderTarget with an OpenGL FBO bridge. Allocation is deferred until drawing. */
class WrappedFramebuffer(
    val name: String = UUID.randomUUID().toString(),
    private val lod: Int = 0,
    width: Int = Minecraft.getInstance().window.width,
    height: Int = Minecraft.getInstance().window.height,
    useDepth: Boolean = false,
    private val fixedSize: Boolean = false,
    linear: Boolean = true,
    val colorFormat: GpuFormat = GpuFormat.RGBA8_UNORM
) : RenderTarget(name, useDepth, colorFormat), EventListener {

    private var allocatedWidth = 0
    private var allocatedHeight = 0
    private var filter = if (linear) GL_LINEAR else GL_NEAREST
    private val clearColor = floatArrayOf(0f, 0f, 0f, 0f)
    private var deleted = false

    override val shouldHandleEvents: Boolean
        get() = !fixedSize && !deleted

    init {
        require(lod in 0..30) { "Invalid framebuffer level of detail: $lod" }
        this.width = (width shr lod).coerceAtLeast(1)
        this.height = (height shr lod).coerceAtLeast(1)
        fbos[name] = this
        on<EventWindowResize> {
            // GLFW events can be delivered during extraction. Do not allocate or clear here.
            val window = Minecraft.getInstance().window
            resize((window.width shr lod).coerceAtLeast(1), (window.height shr lod).coerceAtLeast(1))
        }
    }

    override fun resize(width: Int, height: Int) {
        this.width = width.coerceAtLeast(1)
        this.height = height.coerceAtLeast(1)
    }

    private fun ensureBuffers() {
        Render.assertOpenGl()
        check(!deleted) { "Framebuffer $name has been deleted" }
        if (super.getColorTexture() == null || allocatedWidth != width || allocatedHeight != height) {
            createBuffers(width, height)
        }
    }

    override fun createBuffers(width: Int, height: Int) {
        Render.isolated {
            OpenGlBridge.invalidateTarget(this)
            super.destroyBuffers()
            super.createBuffers(width.coerceAtLeast(1), height.coerceAtLeast(1))
            allocatedWidth = this.width
            allocatedHeight = this.height
            glBindTexture(GL_TEXTURE_2D, OpenGlBridge.textureId(checkNotNull(super.getColorTexture())))
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
            // Refresh the existing FBO rather than deleting a possibly bound FBO during a resize.
            OpenGlBridge.framebufferId(this)
            clear()
        }
    }

    override fun getColorTexture(): GpuTexture {
        ensureBuffers()
        return checkNotNull(super.getColorTexture())
    }

    override fun getColorTextureView(): GpuTextureView {
        ensureBuffers()
        return checkNotNull(super.getColorTextureView())
    }

    override fun getDepthTexture(): GpuTexture? {
        if (!useDepth) return null
        ensureBuffers()
        return super.getDepthTexture()
    }

    override fun getDepthTextureView(): GpuTextureView? {
        if (!useDepth) return null
        ensureBuffers()
        return super.getDepthTextureView()
    }

    val colorAttachment: Int
        get() = OpenGlBridge.colorTextureId(this)
    val depthAttachment: Int
        get() = if (useDepth) OpenGlBridge.depthTextureId(this) else 0
    val fbo: Int
        get() = OpenGlBridge.framebufferId(this)
    val textureWidth: Int
        get() = width
    val textureHeight: Int
        get() = height
    val viewportWidth: Int
        get() = width
    val viewportHeight: Int
        get() = height

    fun setClearColor(red: Float, green: Float, blue: Float, alpha: Float) {
        clearColor[0] = red
        clearColor[1] = green
        clearColor[2] = blue
        clearColor[3] = alpha
    }

    fun setTexFilter(filter: Int) {
        this.filter = filter
        if (super.getColorTexture() != null) Render.isolated {
            glBindTexture(GL_TEXTURE_2D, colorAttachment)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter)
        }
    }

    fun beginWrite(setViewport: Boolean) = OpenGlBridge.bindTarget(this, setViewport)
    /**
     * Bind the main target again.
     *
     * @param restoreViewport also put the viewport back to the window. `beginWrite(true)` sets the
     *   viewport to this target, and every caller here goes on to draw on screen — leaving it behind
     *   rasterises the next quad into a viewport the size of the FBO, in the bottom-left of the window.
     *   That is exactly how the world-space panels ended up tiny and offset for a whole session.
     */
    fun endWrite(restoreViewport: Boolean = true) = Render.bindMainTarget(restoreViewport)
    fun beginRead() {
        Render.assertOpenGl()
        glBindTexture(GL_TEXTURE_2D, colorAttachment)
    }
    fun endRead() = glBindTexture(GL_TEXTURE_2D, 0)

    fun clear() = clearTarget(this, clearColor)

    /**
     * Clear without capturing GL state. Only for callers that already sit inside [Render.isolated]:
     * a snapshot is ~50 driver queries and was being paid three times per panel per frame.
     */
    fun clearUncaptured() {
        Render.assertOpenGl()
        OpenGlBridge.bindTarget(this, false)
        glDisable(GL_SCISSOR_TEST)
        glColorMask(true, true, true, true)
        glClearBufferfv(GL_COLOR, 0, clearColor)
        if (useDepth) {
            glDepthMask(true)
            glClearBufferfv(GL_DEPTH, 0, floatArrayOf(1f))
        }
    }

    override fun copyDepthFrom(source: RenderTarget) {
        check(useDepth && source.useDepth) { "Cannot copy depth without a depth attachment" }
        Render.isolated {
            glDisable(GL_SCISSOR_TEST)
            glBindFramebuffer(GL_READ_FRAMEBUFFER, OpenGlBridge.framebufferId(source))
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fbo)
            glBlitFramebuffer(0, 0, source.width, source.height, 0, 0, width, height, GL_DEPTH_BUFFER_BIT, GL_NEAREST)
        }
    }

    override fun destroyBuffers() {
        OpenGlBridge.releaseTarget(this)
        super.destroyBuffers()
        allocatedWidth = 0
        allocatedHeight = 0
    }

    fun delete() {
        Render.assertOpenGl()
        if (deleted) return
        destroyBuffers()
        deleted = true
        fbos.remove(name, this)
    }

    companion object {
        val fbos = linkedMapOf<String, WrappedFramebuffer>()
    }
}

private fun clearTarget(target: RenderTarget, color: FloatArray) {
    Render.isolated {
        OpenGlBridge.bindTarget(target, false)
        glDisable(GL_SCISSOR_TEST)
        glColorMask(true, true, true, true)
        glClearBufferfv(GL_COLOR, 0, color)
        if (target.useDepth) {
            glDepthMask(true)
            glClearBufferfv(GL_DEPTH, 0, floatArrayOf(1f))
        }
    }
}

fun RenderTarget.clear() = clearTarget(this, floatArrayOf(0f, 0f, 0f, 0f))

fun RenderTarget.save() = Render.isolated {
    Screenshot.grab(Minecraft.getInstance().gameDirectory, this) {}
}
