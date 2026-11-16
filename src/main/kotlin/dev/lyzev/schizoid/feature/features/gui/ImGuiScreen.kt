/*
 * Copyright (c) 2023-2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.feature.features.gui

import dev.lyzev.api.glfw.GLFWKey
import dev.lyzev.api.opengl.Render
import dev.lyzev.api.opengl.WorldRenderBridge
import dev.lyzev.api.opengl.shader.GLSLSandboxShader
import dev.lyzev.api.opengl.shader.ShaderGameOfLife
import dev.lyzev.api.opengl.shader.ShaderParticle
import dev.lyzev.api.setting.settings.keybinds
import dev.lyzev.schizoid.feature.IFeature
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import java.awt.Color

abstract class ImGuiScreen(title: String, override val desc: String, keybinds: Set<GLFWKey> = setOf()) :
    Screen(Component.literal(title)), IFeature {

    private class BackgroundFrame(
        val inGame: Boolean, val background: String, val primary: Color, val secondary: Color, val accent: Color,
        val particleIdle: Color, val particleActive: Color
    )

    @Volatile
    private var backgroundFrame: BackgroundFrame? = null

    override fun added() {
        super.added()
        ShaderGameOfLife.queueGenPixels = true
    }

    override fun extractBackground(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        val theme = FeatureImGui.colorScheme[FeatureImGui.mode]
        val inGame = isIngame
        backgroundFrame = BackgroundFrame(
            inGame, FeatureImGui.background, Color(theme.primary.rgb, true), Color(theme.secondary.rgb, true),
            Color(theme.accent.rgb, true), Color(theme.particleIdle.rgb, true), Color(theme.particleActive.rgb, true)
        )
        if (inGame) {
            // Vanilla GUI extraction is CPU-only. The custom noise/compute backgrounds are drawn later.
            theme.renderInGameBackground(context, width, height)
        }
        // Deliberately do not call Screen.extractBackground: that would reintroduce vanilla blur/panorama.
    }

    /** Called by ImGuiRenderer in the OpenGL drawing phase, before the ImGui windows. */
    fun renderOpenGlBackground() {
        val frame = backgroundFrame ?: return
        Render.isolated {
            Render.prepareFullscreen()
            if (!frame.inGame) {
                WorldRenderBridge.clear()
                backgroundShader.draw {
                    this["primary"] = frame.primary
                    this["secondary"] = frame.secondary
                    this["accent"] = frame.accent
                }
            }
            when (frame.background) {
                "Particle" -> ShaderParticle.draw(frame.particleIdle, frame.particleActive)
                "Game of Life" -> ShaderGameOfLife.draw(frame.particleIdle)
            }
        }
    }

    abstract fun renderImGui()

    override fun keybindReleased() {
        val gui = Minecraft.getInstance().gui
        if (gui.screen() == this) onClose() else gui.setScreen(this)
    }

    override val name = title
    override var keybinds by keybinds(
        "Keybinds", "All keys used to control the feature.", keybinds,
        setOf(GLFWKey.MOUSE_BUTTON_LEFT, GLFWKey.MOUSE_BUTTON_RIGHT, GLFWKey.MOUSE_BUTTON_MIDDLE)
    )
    override val category = IFeature.Category.RENDER

    companion object {
        private val backgroundShader = GLSLSandboxShader("BackgroundNoise")
    }
}
