/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.api.opengl.shader.blur

import dev.lyzev.api.opengl.WrappedFramebuffer
import dev.lyzev.api.opengl.Render
import dev.lyzev.api.opengl.colorAttachment
import com.mojang.blaze3d.pipeline.RenderTarget

interface Blur {

    /**
     * Method to switch the strength of the blur.
     * @param strength The new strength level to set.
     */
    fun switchStrength(strength: Int)

    /**
     * Method to render the blur.
     * @param sourceFBO The framebuffer to render from.
     * @param alpha Whether to use alpha blending.
     */
    fun render(sourceFBO: RenderTarget = Render.mainFramebuffer, alpha: Boolean = false) =
        render(sourceFBO.colorAttachment, alpha)

    fun render(sourceTex: Int, alpha: Boolean = false)

    /**
     * Getter for the output framebuffer.
     * @return The output framebuffer.
     */
    val output: WrappedFramebuffer
}
