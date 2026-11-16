/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.api.opengl.shader.blur.blurs

import dev.lyzev.api.opengl.Render
import dev.lyzev.api.opengl.WrappedFramebuffer
import dev.lyzev.api.opengl.clear
import dev.lyzev.api.opengl.shader.Shader
import dev.lyzev.api.opengl.shader.ShaderDualKawase
import dev.lyzev.api.opengl.shader.ShaderDualKawaseDown
import dev.lyzev.api.opengl.shader.ShaderDualKawaseUp
import dev.lyzev.api.opengl.shader.blur.Blur
import org.joml.Vector2f
import org.lwjgl.opengl.GL33C.*

/**
 * Object that represents a dual Kawase blur.
 * This blur uses a two-pass approach, first downsampling the image and then upsampling it.
 */
object BlurDualKawase : Blur {

    /**
     * Array of pairs representing the strength levels of the blur.
     * Each pair consists of an integer (samples) and a float value (offset).
     *
     * Strength-Levels from https://github.com/jonaburg/picom/blob/a8445684fe18946604848efb73ace9457b29bf80/src/backend/backend_common.c#L372
     */
    private val strengths = arrayOf(
        1 to 1.25f,  // LVL  1
        1 to 2.25f,  // LVL  2
        2 to 2f,     // LVL  3
        2 to 3f,     // LVL  4
        2 to 4.25f,  // LVL  5
        3 to 2.5f,   // LVL  6
        3 to 3.25f,  // LVL  7
        3 to 4.25f,  // LVL  8
        3 to 5.5f,   // LVL  9
        4 to 3.25f,  // LVL  10
        4 to 4f,     // LVL  11
        4 to 5f,     // LVL  12
        4 to 6f,     // LVL  13
        4 to 7.25f,  // LVL  14
        4 to 8.25f,  // LVL  15
        5 to 4.5f,   // LVL  16
        5 to 5.25f,  // LVL  17
        5 to 6.25f,  // LVL  18
        5 to 7.25f,  // LVL  19
        5 to 8.5f,   // LVL  20
    )

    /**
     * Array of framebuffers used for the blur process.
     * Each framebuffer is wrapped in a WrappedFramebuffer object.
     */
    private val fbos = Array(6) { WrappedFramebuffer("BlurDualKawase+$it", it) }

    /**
     * Pair representing the current strength of the blur.
     * The first element is an integer representing the amount of samples, and the second is a float representing the offset.
     */
    private lateinit var strength: Pair<Int, Float>

    /**
     * Vector representing the half size of a texel.
     */
    private val halfTexelSize = Vector2f()

    override fun switchStrength(strength: Int) {
        this.strength = strengths[strength.coerceIn(1, strengths.size) - 1]
    }

    /**
     * Method to render to a framebuffer object.
     * @param targetFBO The framebuffer to render to.
     * @param sourceFBO The framebuffer to render from.
     * @param shader The shader to use for rendering.
     * @param alpha Whether to use alpha blending.
     */
    private fun renderToFBO(
        targetFBO: WrappedFramebuffer, sourceTex: Int, shader: ShaderDualKawase, alpha: Boolean
    ) {
        targetFBO.clear()
        targetFBO.beginWrite(true)
        shader.bind()
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, sourceTex)
        shader.setUniforms(
            strength.second, halfTexelSize.set(.5f / targetFBO.textureWidth, .5f / targetFBO.textureHeight), alpha
        )
        Shader.drawFullScreen()
        shader.unbind()
    }

    override fun render(sourceTex: Int, alpha: Boolean) = Render.isolated {
        Render.prepareFullscreen()
        glDisable(GL_BLEND)
        // Initial downsample
        renderToFBO(fbos[1], sourceTex, ShaderDualKawaseDown, alpha)
        // Downsample
        for (i in 1 until strength.first) renderToFBO(fbos[i + 1], fbos[i].colorAttachment, ShaderDualKawaseDown, alpha)
        // Upsample
        for (i in strength.first downTo 1) renderToFBO(fbos[i - 1], fbos[i].colorAttachment, ShaderDualKawaseUp, alpha)
    }

    override val output: WrappedFramebuffer
        get() = fbos[0]
}
