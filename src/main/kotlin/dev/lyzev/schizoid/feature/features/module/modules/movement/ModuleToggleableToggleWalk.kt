/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.feature.features.module.modules.movement

import dev.lyzev.api.events.EventClientPlayerEntityTick
import dev.lyzev.api.events.EventIsMovementKeyPressed
import dev.lyzev.api.events.EventListener
import dev.lyzev.api.events.on
import dev.lyzev.api.setting.settings.switch
import dev.lyzev.api.settings.Setting.Companion.neq
import dev.lyzev.schizoid.feature.IFeature
import dev.lyzev.schizoid.feature.features.module.ModuleToggleable
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen

object ModuleToggleableToggleWalk :
    ModuleToggleable(
        "Toggle Walk",
        "Automatically toggles sneak when riding a boat.",
        category = IFeature.Category.MOVEMENT
    ), EventListener {

    val screen by switch("Screen", "Whether to walk in screen.", true)
    val handledScreen by switch(
        "Handled Screen",
        "Whether to walk in a handled screen.",
        false,
        hide = ::screen neq true
    )
    val autoJump by switch("Auto Jump", "Whether to auto jump.", true)

    private var wasAutoJump = false

    override fun onEnable() {
        wasAutoJump = mc.options.autoJump().get()
    }

    override fun onDisable() {
        mc.options.autoJump().set(wasAutoJump)
    }

    init {
        on<EventClientPlayerEntityTick> {
            mc.options.autoJump().set(autoJump)
        }
        on<EventIsMovementKeyPressed> {
            if (it.keyBinding == mc.options.keyUp) it.isPressed = true
        }
    }

    override val shouldHandleEvents
        get() = isEnabled && mc.player != null && (mc.gui.screen() == null || (screen && (handledScreen || mc.gui.screen() !is AbstractContainerScreen<*>)))
}
