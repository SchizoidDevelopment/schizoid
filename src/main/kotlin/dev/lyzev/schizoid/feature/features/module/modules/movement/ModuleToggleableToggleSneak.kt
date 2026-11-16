/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.feature.features.module.modules.movement

import dev.lyzev.api.events.EventIsMovementKeyPressed
import dev.lyzev.api.events.EventListener
import dev.lyzev.api.events.on
import dev.lyzev.api.setting.settings.switch
import dev.lyzev.api.settings.Setting.Companion.neq
import dev.lyzev.schizoid.feature.IFeature
import dev.lyzev.schizoid.feature.features.module.ModuleToggleable
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen

object ModuleToggleableToggleSneak :
    ModuleToggleable(
        "Toggle Sneak",
        "Automatically toggles sneak when riding a boat.",
        category = IFeature.Category.MOVEMENT
    ), EventListener {

    val screen by switch("Screen", "Whether to sneak in screen.", true)
    val handledScreen by switch(
        "Handled Screen",
        "Whether to sneak in a handled screen.",
        false,
        hide = ::screen neq true
    )

    init {
        on<EventIsMovementKeyPressed> {
            if (it.keyBinding == mc.options.keyShift) it.isPressed = true
        }
    }

    override val shouldHandleEvents: Boolean
        get() = isEnabled && mc.player != null && (mc.gui.screen() == null || (screen && (handledScreen || mc.gui.screen() !is AbstractContainerScreen<*>)))
}
