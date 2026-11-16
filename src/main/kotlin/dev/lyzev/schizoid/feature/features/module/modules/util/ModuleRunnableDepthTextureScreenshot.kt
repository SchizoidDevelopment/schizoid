/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.feature.features.module.modules.util

import dev.lyzev.api.events.Event
import dev.lyzev.api.events.EventListener
import dev.lyzev.api.events.EventRenderWorld
import dev.lyzev.api.events.on
import dev.lyzev.api.opengl.Render
import dev.lyzev.api.opengl.OpenGlBridge
import dev.lyzev.api.opengl.WorldRenderBridge
import dev.lyzev.api.opengl.WrappedFramebuffer
import dev.lyzev.api.opengl.clear
import dev.lyzev.api.opengl.save
import dev.lyzev.api.opengl.shader.Shader
import dev.lyzev.api.opengl.shader.ShaderLinearizeDepth
import dev.lyzev.schizoid.feature.IFeature
import dev.lyzev.schizoid.feature.features.module.ModuleRunnable
import net.minecraft.client.Camera
import org.lwjgl.opengl.GL33C.*

object ModuleRunnableDepthTextureScreenshot : ModuleRunnable(
    "Depth Texture Screenshot", "Takes a screenshot of the depth texture.", category = IFeature.Category.UTIL
), EventListener {

    private val fbo = WrappedFramebuffer()
    private var isTakingScreenshot = false

    override fun invoke(): String? {
        if (!isIngame || mc.player == null) return "You are not in a game."
        if (isTakingScreenshot) {
            return "Already taking a screenshot."
        } else
            isTakingScreenshot = true
        return null
    }

    override val shouldHandleEvents: Boolean
        get() = isTakingScreenshot

    init {
        on<EventRenderWorld>(Event.Priority.HIGHEST) {
            val frame = WorldRenderBridge.currentFrame ?: return@on
            val depth = OpenGlBridge.depthView(Render.mainFramebuffer)
            isTakingScreenshot = false
            if (depth == null) {
                response = "The current scene has no depth texture."
                return@on
            }
            Render.isolated {
                fbo.resize(depth.getWidth(0), depth.getHeight(0))
                fbo.clear()
                fbo.beginWrite(true)
                Render.prepareFullscreen()
                glDisable(GL_BLEND)
                ShaderLinearizeDepth.bind()
                glActiveTexture(GL_TEXTURE0)
                glBindSampler(0, 0)
                glBindTexture(GL_TEXTURE_2D, OpenGlBridge.textureId(depth.texture()))
                ShaderLinearizeDepth["Tex0"] = 0
                ShaderLinearizeDepth["Near"] = Camera.PROJECTION_Z_NEAR
                ShaderLinearizeDepth["Far"] = frame.depthFar
                Shader.drawFullScreen()
                ShaderLinearizeDepth.unbind()
                fbo.save()
            }
        }
    }
}
