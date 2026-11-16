/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.feature.features.module.modules.movement

import com.mojang.blaze3d.platform.InputConstants
import dev.lyzev.api.events.*
import dev.lyzev.api.glfw.GLFWKey
import dev.lyzev.api.setting.settings.slider
import dev.lyzev.api.setting.settings.switch
import dev.lyzev.api.settings.Setting.Companion.neq
import dev.lyzev.schizoid.feature.IFeature
import dev.lyzev.schizoid.feature.features.module.ModuleToggleable
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import org.lwjgl.glfw.GLFW

object ModuleToggleableScreenMove :
    ModuleToggleable("Screen Move", "Allows you to move while in GUI screens.", category = IFeature.Category.MOVEMENT),
    EventListener {

    val mouse by switch("Mouse", "Pass through the mouse.", false)
    val weight by slider("Weight", "The weight of the mouse movement.", 20, 1, 100, "%%", hide = ::mouse neq true)
    val keyboard by switch("Keyboard", "Pass through the keyboard.", true)

    override val shouldHandleEvents: Boolean
        get() = isEnabled && mc.player != null && mc.gui.screen()?.let { it !is AbstractContainerScreen<*> && !it.isPauseScreen } == true

    private fun isPhysicallyDown(key: InputConstants.Key): Boolean = when (key.type) {
        InputConstants.Type.MOUSE -> GLFW.glfwGetMouseButton(mc.window.handle(), key.value) == GLFW.GLFW_PRESS
        InputConstants.Type.KEYSYM -> InputConstants.isKeyDown(mc.window, key.value)
        InputConstants.Type.SCANCODE -> GLFWKey.entries.any {
            it.code >= GLFW.GLFW_KEY_SPACE && GLFW.glfwGetKeyScancode(it.code) == key.value && it.isPressed()
        }
    }

    init {
        val movementKeys = listOf(
            mc.options.keyUp,
            mc.options.keyDown,
            mc.options.keyRight,
            mc.options.keyLeft,
            mc.options.keyJump,
            mc.options.keyShift,
            mc.options.keySprint
        ).map { switch(it.defaultKey.name, "Pass through the keybinding.", true, ::keyboard neq true) }

        on<EventIsMovementKeyPressed> { event ->
            if (!keyboard || mc.gui.screen() is ChatScreen || event.keyBinding.isUnbound)
                return@on
            val key = event.keyBinding.defaultKey.name
            movementKeys.find { it.name == key }?.let {
                if (it.value)
                    event.isPressed = isPhysicallyDown(event.keyBinding.key)
            }
        }

        on<EventIsCursorLocked> { event ->
            if (!mouse)
                return@on
            event.isCursorLocked = true
        }

        on<EventUpdateMouse> {
            if (!mouse)
                return@on
            val mouse = mc.mouseHandler
            val weight = weight / 100f
            mouse.accumulatedDX *= weight
            mouse.accumulatedDY *= weight
        }
    }
}
