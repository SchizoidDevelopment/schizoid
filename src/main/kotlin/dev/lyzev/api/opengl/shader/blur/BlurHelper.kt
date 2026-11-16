/*
 * Copyright (c) 2023. Schizoid
 * All rights reserved.
 */

package dev.lyzev.api.opengl.shader.blur

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.GpuTextureView
import dev.lyzev.api.opengl.GlStateSnapshot
import dev.lyzev.api.opengl.Render
import dev.lyzev.api.opengl.WrappedFramebuffer
import dev.lyzev.api.opengl.shader.Shader.Companion.drawFullScreen
import dev.lyzev.api.opengl.shader.ShaderAcrylic
import dev.lyzev.api.opengl.shader.ShaderAdd
import dev.lyzev.api.opengl.shader.ShaderMask
import dev.lyzev.api.opengl.shader.ShaderPassThrough
import dev.lyzev.api.opengl.shader.ShaderTint
import dev.lyzev.schizoid.feature.features.module.modules.render.ModuleToggleableBlur
import org.joml.Vector3f
import org.lwjgl.opengl.GL33C.*

object BlurHelper {

    val mode: Blurs
        get() = ModuleToggleableBlur.method.value

    private var mask = WrappedFramebuffer("BlurMask")
    private var mergedMask = WrappedFramebuffer("BlurMaskMerge")
    private val tmp = WrappedFramebuffer("BlurContent")
    private val fbos = arrayOf(WrappedFramebuffer("BlurAcrylic"), WrappedFramebuffer("BlurTint"))
    private val dropShadow = WrappedFramebuffer("BlurDropShadow")
    private var capture: Capture? = null

    private class Capture(
        val state: GlStateSnapshot,
        val colorOverride: GpuTextureView?,
        val depthOverride: GpuTextureView?,
        val fbo: Int,
        val viewport: IntArray
    )

    /** Call around actual GUI drawing, never around GuiGraphicsExtractor extraction. */
    @JvmStatic
    fun begin() {
        if (!ModuleToggleableBlur.isActive) return
        check(capture == null) { "Blur content capture is already active" }
        capture = Capture(
            GlStateSnapshot.capture(), RenderSystem.outputColorTextureOverride, RenderSystem.outputDepthTextureOverride,
            glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING), IntArray(4).also { glGetIntegerv(GL_VIEWPORT, it) }
        )
        tmp.clear()
        tmp.beginWrite(true)
        RenderSystem.outputColorTextureOverride = tmp.getColorTextureView()
        RenderSystem.outputDepthTextureOverride = null
    }

    @JvmStatic
    fun end() {
        val capture = capture ?: return
        RenderSystem.outputColorTextureOverride = capture.colorOverride
        RenderSystem.outputDepthTextureOverride = capture.depthOverride
        try {
            addTmpToMask()
            addTmpToScene()
        } finally {
            this.capture = null
            capture.state.restore()
        }
    }

    @JvmStatic
    fun addTmpToMask() {
        if (!ModuleToggleableBlur.isActive) return
        Render.isolated {
            Render.prepareFullscreen()
            glDisable(GL_BLEND)
            mergedMask.clear()
            mergedMask.beginWrite(true)
            // Sampling mask while writing to that same texture is undefined on OpenGL.
            ShaderAdd.bind()
            glActiveTexture(GL_TEXTURE1)
            tmp.beginRead()
            glActiveTexture(GL_TEXTURE0)
            mask.beginRead()
            ShaderAdd["Tex0"] = 0
            ShaderAdd["Tex1"] = 1
            ShaderAdd["Alpha"] = false
            drawFullScreen()
            ShaderAdd.unbind()
            val previous = mask
            mask = mergedMask
            mergedMask = previous
        }
    }

    @JvmStatic
    fun addTmpToScene() {
        if (!ModuleToggleableBlur.isActive) return
        Render.isolated {
            Render.prepareFullscreen()
            val capture = capture
            if (capture == null) Render.bindMainTarget()
            else {
                glBindFramebuffer(GL_DRAW_FRAMEBUFFER, capture.fbo)
                with(capture.viewport) { glViewport(this[0], this[1], this[2], this[3]) }
            }
            // Rendering to a transparent FBO produces premultiplied color.
            glBlendFuncSeparate(GL_ONE, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
            ShaderPassThrough.bind()
            glActiveTexture(GL_TEXTURE0)
            tmp.beginRead()
            ShaderPassThrough["Tex0"] = 0
            ShaderPassThrough["Scale"] = 1f
            ShaderPassThrough["Alpha"] = true
            drawFullScreen()
            ShaderPassThrough.unbind()
        }
    }

    fun draw(
        mask: WrappedFramebuffer = BlurHelper.mask,
        clearMask: Boolean = true,
        useDefaultFbo: Boolean = false,
        dropShadowColor: Vector3f = Vector3f(0f, 0f, 0f),
        blurStrength: Int = ModuleToggleableBlur.strength,
        dropShadowStrength: Int = ModuleToggleableBlur.dropShadowStrength,
        sourceFBO: RenderTarget = Render.mainFramebuffer
    ) = Render.isolated {
        Render.prepareFullscreen()
        mode.switchStrength(blurStrength)
        mode.render(sourceFBO)
        var i = 0

        if (ModuleToggleableBlur.acrylic) {
            fbos[i].beginWrite(true)
            ShaderAcrylic.bind()
            glActiveTexture(GL_TEXTURE0)
            mode.output.beginRead()
            ShaderAcrylic["Tex0"] = 0
            ShaderAcrylic["Luminosity"] = ModuleToggleableBlur.luminosity / 100f
            ShaderAcrylic["NoiseStrength"] = 0.04f * ModuleToggleableBlur.noiseStrength / 100f
            ShaderAcrylic["NoiseScale"] = 4000f * ModuleToggleableBlur.noiseSale / 100f
            drawFullScreen()
            ShaderAcrylic.unbind()
            i++
        }

        if (ModuleToggleableBlur.RGBPuke) {
            fbos[i].beginWrite(true)
            ShaderTint.bind()
            glActiveTexture(GL_TEXTURE0)
            if (i == 0) mode.output.beginRead() else fbos[i - 1].beginRead()
            ShaderTint["Tex0"] = 0
            ShaderTint["RGBPuke"] = true
            ShaderTint.set("SV", ModuleToggleableBlur.RGBPukeSaturation / 100f, ModuleToggleableBlur.RGBPukeBrightness / 100f)
            ShaderTint["Opacity"] = ModuleToggleableBlur.RGBPukeOpacity / 100f
            ShaderTint["Alpha"] = false
            ShaderTint["Multiplier"] = 1f
            ShaderTint["Time"] = (System.nanoTime() - ShaderTint.initTime) / 1000000000f
            ShaderTint["Yaw"] = Render.cameraYaw
            ShaderTint["Pitch"] = Render.cameraPitch
            drawFullScreen()
            ShaderTint.unbind()
            i++
        }

        bindDestination(useDefaultFbo)
        ShaderMask.bind()
        glActiveTexture(GL_TEXTURE1)
        mask.beginRead()
        glActiveTexture(GL_TEXTURE0)
        if (i == 0) mode.output.beginRead() else fbos[i - 1].beginRead()
        ShaderMask["Tex0"] = 0
        ShaderMask["Tex1"] = 1
        ShaderMask["Invert"] = false
        drawFullScreen()
        ShaderMask.unbind()

        if (ModuleToggleableBlur.dropShadow) {
            mode.switchStrength(dropShadowStrength)
            mode.render(mask, true)
            dropShadow.clear()
            dropShadow.beginWrite(true)
            ShaderTint.bind()
            glActiveTexture(GL_TEXTURE0)
            mode.output.beginRead()
            ShaderTint["Tex0"] = 0
            ShaderTint["Color"] = dropShadowColor
            ShaderTint["RGBPuke"] = ModuleToggleableBlur.dropShadowRGBPuke
            ShaderTint.set("SV", ModuleToggleableBlur.dropShadowRGBPukeSaturation / 100f, ModuleToggleableBlur.dropShadowRGBPukeBrightness / 100f)
            ShaderTint["Opacity"] = 1f
            ShaderTint["Alpha"] = true
            ShaderTint["Multiplier"] = ModuleToggleableBlur.dropShadowMultiplier / 100f
            ShaderTint["Time"] = (System.nanoTime() - ShaderTint.initTime) / 1000000000f
            ShaderTint["Yaw"] = Render.cameraYaw
            ShaderTint["Pitch"] = Render.cameraPitch
            drawFullScreen()
            ShaderTint.unbind()

            bindDestination(useDefaultFbo)
            ShaderMask.bind()
            glActiveTexture(GL_TEXTURE1)
            mask.beginRead()
            glActiveTexture(GL_TEXTURE0)
            dropShadow.beginRead()
            ShaderMask["Tex0"] = 0
            ShaderMask["Tex1"] = 1
            ShaderMask["Invert"] = true
            drawFullScreen()
            ShaderMask.unbind()
        }
        if (clearMask) mask.clear()
    }

    private fun bindDestination(defaultFbo: Boolean) {
        if (defaultFbo) Render.bindDefaultTarget() else Render.bindMainTarget()
    }
}
