/*
 * Copyright (c) 2023-2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.feature.features.gui.guis

import dev.lyzev.api.animation.EasingFunction
import dev.lyzev.api.animation.TimeAnimator
import dev.lyzev.api.events.EventKeybindsRequest
import dev.lyzev.api.events.EventKeybindsResponse
import dev.lyzev.api.events.EventListener
import dev.lyzev.api.events.on
import dev.lyzev.api.glfw.GLFWKey
import dev.lyzev.api.imgui.spatial.SpatialWorldController
import dev.lyzev.api.imgui.render.renderable.ImGuiRenderableConfigManager
import dev.lyzev.api.imgui.render.renderable.ImGuiRenderableDeveloperTool
import dev.lyzev.api.imgui.render.renderable.ImGuiRenderableSearch
import dev.lyzev.api.opengl.glId
import dev.lyzev.api.opengl.Render
import dev.lyzev.api.setting.settings.option
import dev.lyzev.api.setting.settings.slider
import dev.lyzev.schizoid.Schizoid
import dev.lyzev.schizoid.feature.IFeature
import dev.lyzev.schizoid.feature.features.gui.ImGuiScreen
import imgui.ImGui.*
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.resources.Identifier
import org.lwjgl.glfw.GLFW

object ImGuiScreenFeature : ImGuiScreen(
    "Feature Screen", "Displays all features and their respective settings.", setOf(GLFWKey.INSERT, GLFWKey.RIGHT_SHIFT)
), EventListener {

    private val texturesMario = Array(3) {
        Identifier.fromNamespaceAndPath(Schizoid.MOD_ID, "textures/mario_$it.png")
    }
    private var isMarioRunning = false
    private val timeAnimatorMario = TimeAnimator(8000)

    val animationMario by option(
        "Mario Animation", "The animation type.", EasingFunction.IN_OUT_ELASTIC, EasingFunction.entries
    )
    val speedMario by slider(
        "Mario Speed", "The speed of the animation.", 5000, 1000, 10000, "ms", true
    ) {
        timeAnimatorMario.animationLength = it.toLong()
    }

    private var waitingForInput = -1L
    private var isWaitingForInput = false
    private const val TIMEOUT = 5000
    val waitingForKeybind: Boolean get() = isWaitingForInput

    /** Shared initiation for ordinary bind widgets and world canvases. */
    fun beginKeybindCapture() {
        if (isWaitingForInput) EventKeybindsResponse(GLFW.GLFW_KEY_UNKNOWN).fire()
        waitingForInput = System.currentTimeMillis()
        isWaitingForInput = true
    }

    val search = ImGuiRenderableSearch()
    val devTools = ImGuiRenderableDeveloperTool()
    val configManager = ImGuiRenderableConfigManager()

    override fun tick() {
        super.tick()
        if (isWaitingForInput && System.currentTimeMillis() - waitingForInput > TIMEOUT) {
            EventKeybindsResponse(GLFW.GLFW_KEY_UNKNOWN).fire()
            isWaitingForInput = false
        }
    }

    private fun renderMario() {
        if (!isMarioRunning && timeAnimatorMario.isCompleted()) {
            timeAnimatorMario.setProgress(.0)
            return
        }
        isMarioRunning = true

        val guiWidth = mc.window.guiScaledWidth
        val guiHeight = mc.window.guiScaledHeight
        val x = -32 + ((guiWidth + 32) * animationMario.ease(timeAnimatorMario.getProgress())).toInt()
        val scaleX = getIO().displaySizeX / guiWidth.coerceAtLeast(1)
        val scaleY = getIO().displaySizeY / guiHeight.coerceAtLeast(1)
        val textureId = Render.isolated {
            mc.textureManager.getTexture(texturesMario[(System.currentTimeMillis() / 100L % texturesMario.size).toInt()]).glId
        }
        // This used to be a DrawContext call. Drawing it with ImGui keeps it above the deferred shader background.
        getBackgroundDrawList().addImage(
            textureId, x * scaleX, (guiHeight - 32) * scaleY, (x + 32) * scaleX, guiHeight * scaleY,
            0f, 0f, 1f, 1f
        )
        isMarioRunning = x < guiWidth
    }

    /** Share the existing pending capture with world-canvas input, without triggering screen shortcuts. */
    fun consumeKeybindInput(keyCode: Int): Boolean {
        if (!isWaitingForInput) return false
        isWaitingForInput = false
        EventKeybindsResponse(if (keyCode == GLFW.GLFW_KEY_ESCAPE) GLFW.GLFW_KEY_UNKNOWN else keyCode).fire()
        return true
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        val keyCode = event.key()
        if (consumeKeybindInput(keyCode)) {
            return true
        } else if (keybinds.contains(GLFWKey[keyCode])) {
            keybindReleased()
            return true
        } else if (keyCode == GLFWKey.LEFT_SHIFT.code) {
            search.open()
            return true
        }
        return super.keyPressed(event)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val button = event.button()
        if (consumeKeybindInput(button)) {
            return true
        } else if (keybinds.contains(GLFWKey[button])) {
            search.close()
            keybindReleased()
            return true
        }
        return super.mouseClicked(event, doubleClick)
    }

    override fun renderImGui() {
        renderMario()
        if (Schizoid.DEVELOPER_MODE) devTools.render()
        search.render()
        configManager.render()
        IFeature.Category.entries.forEach(IFeature.Category::render)
    }

    init {
        on<EventKeybindsRequest> {
            beginKeybindCapture()
        }
    }

    override fun keybindReleased() {
        if (mc.level == null) super.keybindReleased()
        else SpatialWorldController.toggle()
    }

    override fun isPauseScreen() = false

    override val shouldHandleEvents
        get() = mc.gui.screen() == this || (mc.gui.screen() == null && SpatialWorldController.visible)
}
