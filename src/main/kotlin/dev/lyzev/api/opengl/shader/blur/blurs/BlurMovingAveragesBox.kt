/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.api.opengl.shader.blur.blurs

import dev.lyzev.api.opengl.WrappedFramebuffer
import dev.lyzev.api.opengl.Render
import dev.lyzev.api.opengl.shader.ShaderMovingAveragesBox
import dev.lyzev.api.opengl.shader.Shader
import dev.lyzev.api.opengl.shader.ShaderPassThrough
import dev.lyzev.api.opengl.shader.blur.Blur
import org.lwjgl.opengl.GL33C.*
import kotlin.properties.Delegates

/**
 * Object that represents a Box blur.
 * This blur uses a two-pass approach, first horizontally and then vertically.
 */
object BlurMovingAveragesBox : Blur {

    /**
     * Integer representing the strength of the blur.
     */
    private var strength by Delegates.notNull<Int>()

    /**
     * Array of framebuffers used for the blur process.
     * Each framebuffer is wrapped in a WrappedFramebuffer object.
     */
    private val fbos = Array(2) {
        WrappedFramebuffer("BlurMovingAveragesBox+$it", 2)
    }
    private var normalizedSource: WrappedFramebuffer? = null

    override fun switchStrength(strength: Int) {
        this.strength = strength
    }

    /**
     * Method to render to a framebuffer object.
     * @param targetFBO The framebuffer to render to.
     * @param sourceFBO The framebuffer to render from.
     * @param alpha Whether to use alpha blending.
     */
    private fun renderToFBO(targetFBO: WrappedFramebuffer, sourceTex: Int, direction: Boolean, alpha: Boolean) {
        ShaderMovingAveragesBox.render(targetFBO, sourceTex, direction, alpha, strength)
    }

    override fun render(sourceTex: Int, alpha: Boolean) = Render.isolated {
        val sourceTex = normalizeSource(sourceTex)
        // Initial iteration
        renderToFBO(fbos[0], sourceTex, false, alpha)
        // Rest of the iterations
        for (i in 1 until 4)
            renderToFBO(fbos[i % 2], fbos[(i + 1) % 2].colorAttachment, i % 2 == 1, alpha)
    }

    private fun normalizeSource(sourceTex: Int): Int {
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, sourceTex)
        if (glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_INTERNAL_FORMAT) == GL_RGBA8) return sourceTex
        // The compute shader's rgba8 image binding cannot read Iris/HDR float or packed-color textures.
        // Convert at native resolution first, rather than reinterpreting their bits as rgba8.
        val width = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_WIDTH)
        val height = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_HEIGHT)
        check(width > 0 && height > 0) { "Moving averages blur source is not an allocated 2D texture: $sourceTex" }
        var normalized = normalizedSource
        if (normalized == null || normalized.width != width || normalized.height != height) {
            normalized?.delete()
            normalized = WrappedFramebuffer("BlurMovingAveragesSource", width = width, height = height, fixedSize = true, linear = false)
            normalizedSource = normalized
        }
        Render.prepareFullscreen()
        glDisable(GL_BLEND)
        normalized.beginWrite(true)
        ShaderPassThrough.bind()
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, sourceTex)
        ShaderPassThrough["Tex0"] = 0
        ShaderPassThrough["Scale"] = 1f
        ShaderPassThrough["Alpha"] = true
        Shader.drawFullScreen()
        ShaderPassThrough.unbind()
        return normalized.colorAttachment
    }

    override val output: WrappedFramebuffer
        get() = fbos[1]
}
