/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.feature.features.module.modules.render

import dev.lyzev.api.events.EventListener
import dev.lyzev.api.events.EventRenderWorld
import dev.lyzev.api.events.on
import dev.lyzev.api.opengl.Render
import dev.lyzev.api.opengl.OpenGlBridge
import dev.lyzev.api.opengl.WrappedFramebuffer
import dev.lyzev.api.opengl.beginRead
import dev.lyzev.api.opengl.beginWrite
import dev.lyzev.api.opengl.shader.*
import dev.lyzev.api.opengl.shader.blur.Blurs
import dev.lyzev.api.setting.settings.option
import dev.lyzev.api.setting.settings.slider
import dev.lyzev.api.setting.settings.switch
import dev.lyzev.api.settings.Setting.Companion.neq
import dev.lyzev.schizoid.Schizoid
import dev.lyzev.schizoid.feature.IFeature
import dev.lyzev.schizoid.feature.features.module.ModuleToggleable
import net.minecraft.client.Camera
import org.lwjgl.opengl.GL11C.GL_TEXTURE_2D
import org.lwjgl.opengl.GL11C.glBindTexture
import org.lwjgl.opengl.GL13C.GL_TEXTURE0
import org.lwjgl.opengl.GL13C.GL_TEXTURE1
import org.lwjgl.opengl.GL13C.glActiveTexture

object ModuleToggleableBlur :
    ModuleToggleable("Blur", "All settings related to blur effects.", category = IFeature.Category.RENDER),
    EventListener {

    /**
     * Forces the effect on for a dev run without touching [isEnabled], which is a setting and therefore
     * writes the player's config. See `ModuleDepthOfField.autotestEnabled`.
     */
    internal var autotestEnabled = false

    /** Read this instead of [isEnabled] everywhere the effect is applied, so a dev run behaves exactly
     * like a user-toggled one while persisting nothing. */
    val isActive: Boolean
        get() = isEnabled || autotestEnabled

    val method = option("Method", "The method used to blur the screen.", Blurs.DUAL_KAWASE, Blurs.entries)
    val useLinearSampling by switch(
        "Linear Sampling",
        "Enables linear sampling for the Gaussian blur.",
        true,
        method::value neq Blurs.GAUSSIAN
    )
    val strength by slider("Strength", "The strength of the blur effect.", 6, 1, 20)

    val acrylic by switch("Acrylic", "Adds an acrylic effect to the blur.", true)
    val luminosity by slider(
        "Luminosity",
        "The luminosity of the acrylic effect.",
        180,
        0,
        200,
        unit = "%%",
        allowOutOfBounds = true,
        hide = ::acrylic neq true
    )
    val noiseStrength by slider(
        "Noise Strength",
        "The strength of the noise effect.",
        100,
        0,
        100,
        unit = "%%",
        hide = ::acrylic neq true
    )
    val noiseSale by slider(
        "Noise Scale",
        "The scale of the noise effect.",
        100,
        0,
        100,
        unit = "%%",
        allowOutOfBounds = true,
        hide = {
            !acrylic || noiseStrength == 0
        })
    val rgbPukeMode by option(
        "RGB Puke Mode",
        "The mode of the RGB puke effect.",
        ShaderTint.RGBPukeMode.Noise,
        ShaderTint.RGBPukeMode.entries
    ) {
        ShaderTint.rgbPukeMode = it.key
    }
    val RGBPuke by switch("RGB Puke", "Adds an RGB puke effect to the blur.", false)
    val RGBPukeOpacity by slider(
        "RGB Puke Opacity",
        "The opacity of the RGB puke effect.",
        30,
        1,
        100,
        unit = "%%",
        hide = ::RGBPuke neq true
    )
    val RGBPukeSaturation by slider(
        "RGB Puke Saturation",
        "The saturation of the RGB puke effect.",
        70,
        0,
        100,
        unit = "%%",
        hide = ::RGBPuke neq true
    )
    val RGBPukeBrightness by slider(
        "RGB Puke Brightness",
        "The brightness of the RGB puke effect.",
        100,
        0,
        100,
        unit = "%%",
        hide = ::RGBPuke neq true
    )

    val dropShadow by switch("Drop Shadow", "Adds a drop shadow to the gui.", true)
    val dropShadowStrength by slider(
        "Drop Shadow Strength",
        "The strength of the drop shadow effect.",
        3,
        1,
        20,
        hide = ::dropShadow neq true
    )
    val dropShadowMultiplier by slider(
        "Drop Shadow Multiplier",
        "The multiplier of the drop shadow effect.",
        140,
        100,
        200,
        unit = "%%",
        hide = ::dropShadow neq true
    )
    val dropShadowRGBPuke by switch(
        "Drop Shadow RGB Puke",
        "Adds an RGB puke effect to the drop shadow.",
        false,
        hide = ::dropShadow neq true
    )
    val dropShadowRGBPukeSaturation by slider(
        "Drop Shadow RGB Puke Saturation",
        "The saturation of the RGB puke effect.",
        70,
        0,
        100,
        unit = "%%",
        hide = {
            !dropShadowRGBPuke || !dropShadow
        }
    )
    val dropShadowRGBPukeBrightness by slider(
        "Drop Shadow RGB Puke Brightness",
        "The brightness of the RGB puke effect.",
        100,
        0,
        100,
        unit = "%%",
        hide = {
            !dropShadowRGBPuke || !dropShadow
        }
    )

    val bloom by switch("Bloom", "Adds a bloom to the gui.", true)
    val bloomDouble by switch(
        "Bloom Double",
        "Blurs the texture again for a stronger bloom effect.",
        true,
        hide = ::bloom neq true
    )
    val bloomStrength by slider("Bloom", "The strength of the bloom effect.", 3, 1, 20, hide = ::bloom neq true)
    val bloomRGBPuke by switch(
        "Bloom RGB Puke",
        "Adds an RGB puke effect to the bloom.",
        false,
        hide = ::bloom neq true
    )

    val fog by switch("Fog", "Adds a fog effect to the blur.", false)
    val fogStrength by slider("Fog Strength", "The blur strength of the fog effect.", 6, 1, 20, hide = ::fog neq true)
    val fogDistance by slider(
        "Fog Distance",
        "The distance of the fog effect.",
        50,
        0,
        100,
        unit = "%%",
        hide = ::fog neq true
    )
    val fogRGBPuke by switch("Fog RGB Puke", "Adds an RGB puke effect to the fog.", false, hide = ::fog neq true)
    val fogRGBPukeOpacity by slider(
        "Fog RGB Puke Opacity",
        "The opacity of the RGB puke effect.",
        30,
        1,
        100,
        unit = "%%",
        hide = {
            !fogRGBPuke || !fog
        })
    val fogRGBPukeSaturation by slider(
        "Fog RGB Puke Saturation",
        "The saturation of the RGB puke effect.",
        70,
        0,
        100,
        unit = "%%",
        hide = {
            !fogRGBPuke || !fog
        }
    )
    val fogRGBPukeBrightness by slider(
        "Fog RGB Puke Brightness",
        "The brightness of the RGB puke effect.",
        100,
        0,
        100,
        unit = "%%",
        hide = {
            !fogRGBPuke || !fog
        }
    )

    private val fbo = WrappedFramebuffer()

    /**
     * Dev probe for the chain's GL state. The mask and the composite are full-screen quads into
     * full-window targets, so their viewport must match the target; anything else silently rasterises
     * into a fraction of the screen and reads as "the effect does nothing".
     */
    private fun debugViewport(stage: String) {
        if (System.getProperty("schizoid.spatial.debug") == null) return
        val viewport = IntArray(4)
        org.lwjgl.opengl.GL11.glGetIntegerv(org.lwjgl.opengl.GL11.GL_VIEWPORT, viewport)
        Schizoid.logger.info(
            "blur chain $stage: viewport=${viewport[2]}x${viewport[3]} at (${viewport[0]},${viewport[1]}) " +
                "fbo=${fbo.width}x${fbo.height} main=${Render.mainTarget.width}x${Render.mainTarget.height}"
        )
    }

    override val shouldHandleEvents: Boolean
        get() = isActive && fog && !mc.gameRenderer.mainCamera().isPanoramicMode

    init {
        // World fog/color grading must run before spatial canvases: never tint or blur their text.
        on<EventRenderWorld>(dev.lyzev.api.events.Event.Priority.HIGHEST) {
            Render.isolated {
            if (fogRGBPuke) {
                fbo.clear()
                fbo.beginWrite(false)
                ShaderTint.bind()
                glActiveTexture(GL_TEXTURE0)
                Render.mainTarget.beginRead()
                ShaderTint["Tex0"] = 0
                ShaderTint["RGBPuke"] = fogRGBPuke
                ShaderTint.set("SV", fogRGBPukeSaturation / 100f, fogRGBPukeBrightness / 100f)
                ShaderTint["Opacity"] = fogRGBPukeOpacity / 100f
                ShaderTint["Alpha"] = false
                ShaderTint["Multiplier"] = 1f
                ShaderTint["Time"] = (System.nanoTime() - ShaderTint.initTime) / 1000000000f
                ShaderTint["Yaw"] = mc.player?.yRot ?: 0f
                ShaderTint["Pitch"] = mc.player?.xRot ?: 0f
                Shader.drawFullScreen()
                ShaderTint.unbind()
            }

            method.value.switchStrength(fogStrength)
            method.value.render(if (fogRGBPuke) fbo else Render.mainTarget, false)

            debugViewport("after blur")
            fbo.clear()
            fbo.beginWrite(false)
            debugViewport("mask bound")
            ShaderDepth.bind()
            glActiveTexture(GL_TEXTURE0)
            method.value.output.beginRead()
            ShaderDepth["Tex0"] = 0
            glActiveTexture(GL_TEXTURE1)
            glBindTexture(GL_TEXTURE_2D, OpenGlBridge.depthTextureId(Render.mainTarget))
            ShaderDepth["Tex1"] = 1
            ShaderDepth["Near"] = Camera.PROJECTION_Z_NEAR
            ShaderDepth["Far"] = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.depthFar
            ShaderDepth["MinThreshold"] = .10f * fogDistance / 100f
            ShaderDepth["MaxThreshold"] = .28f * fogDistance / 100f
            Shader.drawFullScreen()
            ShaderDepth.unbind()

            debugViewport("after mask")
            Render.mainTarget.beginWrite(false)
            debugViewport("composite bound")
            ShaderPassThrough.bind()
            glActiveTexture(GL_TEXTURE0)
            fbo.beginRead()
            ShaderPassThrough["Tex0"] = 0
            ShaderPassThrough["Scale"] = 1f
            ShaderPassThrough["Alpha"] = true
            Shader.drawFullScreen()
            ShaderPassThrough.unbind()
            }
        }
    }
}
