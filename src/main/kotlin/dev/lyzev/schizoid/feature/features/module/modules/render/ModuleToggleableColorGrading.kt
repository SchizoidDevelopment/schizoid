/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.feature.features.module.modules.render

import dev.lyzev.api.events.EventListener
import dev.lyzev.api.events.EventRenderWorld
import dev.lyzev.api.events.on
import dev.lyzev.api.opengl.OpenGlBridge
import dev.lyzev.api.opengl.Render
import dev.lyzev.api.opengl.WrappedFramebuffer
import dev.lyzev.api.opengl.shader.Shader
import dev.lyzev.api.opengl.shader.ShaderColorGrading
import dev.lyzev.api.setting.settings.color
import dev.lyzev.api.setting.settings.slider
import dev.lyzev.schizoid.feature.IFeature
import dev.lyzev.schizoid.feature.features.module.ModuleToggleable
import org.lwjgl.opengl.GL33C.*
import java.awt.Color

object ModuleToggleableColorGrading :
    ModuleToggleable("Color Grading", "Enables color grading for the world.", category = IFeature.Category.RENDER),
    EventListener {

    val brightness by slider("Brightness", "The brightness of the color grading effect.", -50, -100, 100, "%%")
    val contrast by slider("Contrast", "The contrast of the color grading effect.", 55, 0, 200, "%%")
    val exposure by slider("Exposure", "The exposure of the color grading effect.", -0, -100, 100, "%%")
    val saturation by slider("Saturation", "The saturation of the color grading effect.", 110, 0, 200, "%%")
    val hue by slider("Hue", "The hue shift of the color grading effect.", 0, -180, 180, "°")
    val temperature by slider("Temperature", "The temperature of the color grading effect.", 1000, 1000, 40000, "K")
    val lift by color("Lift", value = Color(0, 0, 0))
    val gamma by color("Gamma", value = Color(0, 35, 0))
    val gain by color("Gain", value = Color(0, 0, 255))
    val offset by color("Offset", value = Color(0, 0, 0))

    private var sceneCopy: WrappedFramebuffer? = null

    override val shouldHandleEvents: Boolean
        get() = isEnabled && !mc.gameRenderer.mainCamera().isPanoramicMode

    init {
        on<EventRenderWorld> {
            Render.isolated {
                Render.prepareFullscreen()
                glDisable(GL_BLEND)
                // Sampling the draw attachment directly is an undefined OpenGL feedback loop.
                val target = Render.mainTarget
                val view = OpenGlBridge.colorView(target)
                // Shader packs may use HDR targets and change their format on reload.
                val sceneCopy = sceneCopy?.takeIf { it.colorFormat == view.texture().format }
                    ?: WrappedFramebuffer("ColorGradingSceneCopy", fixedSize = true, colorFormat = view.texture().format).also {
                        this.sceneCopy?.delete()
                        this.sceneCopy = it
                    }
                val width = view.getWidth(0)
                val height = view.getHeight(0)
                sceneCopy.resize(width, height)
                glBindFramebuffer(GL_READ_FRAMEBUFFER, OpenGlBridge.framebufferId(target))
                glBindFramebuffer(GL_DRAW_FRAMEBUFFER, sceneCopy.fbo)
                glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL_COLOR_BUFFER_BIT, GL_NEAREST)
                Render.bindMainTarget()
                ShaderColorGrading.bind()
                OpenGlBridge.bindColorTexture(sceneCopy)
                ShaderColorGrading["Tex0"] = 0
                ShaderColorGrading["Brightness"] = brightness / 100f
                ShaderColorGrading["Contrast"] = contrast / 100f
                ShaderColorGrading["Exposure"] = exposure / 100f
                ShaderColorGrading["Saturation"] = saturation / 100f
                ShaderColorGrading["Hue"] = hue
                ShaderColorGrading["Temperature"] = temperature.toFloat()
                ShaderColorGrading["Lift"] = lift
                ShaderColorGrading["Gamma"] = gamma
                ShaderColorGrading["Gain"] = gain
                ShaderColorGrading["Offset"] = offset
                Shader.drawFullScreen()
                ShaderColorGrading.unbind()
            }
        }
    }
}
