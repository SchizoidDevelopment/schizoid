/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.feature.features.module.modules.render

import dev.lyzev.api.events.EventClientPlayerEntityTick
import dev.lyzev.api.events.EventListener
import dev.lyzev.api.events.on
import dev.lyzev.api.setting.settings.OptionEnum
import dev.lyzev.api.setting.settings.option
import dev.lyzev.schizoid.feature.IFeature
import dev.lyzev.schizoid.feature.features.module.ModuleToggleable
import net.minecraft.world.entity.item.PrimedTnt
import net.minecraft.network.chat.Component

/**
 * This object represents a module that provides a timer for TNT entities in the game.
 * The timer displays the time left until a TNT block explodes.
 * The time can be displayed in seconds or ticks, depending on the user's preference.
 */
object ModuleToggleableTNTTimer :
    ModuleToggleable(
        "TNT Timer",
        "Displays the time left until a TNT block explodes.",
        category = IFeature.Category.RENDER
    ),
    EventListener {

    /**
     * The unit of the timer, which can be either seconds or ticks.
     */
    val unit by option("Unit", "The unit of the timer.", Units.SECONDS, Units.entries)

    override val shouldHandleEvents: Boolean
        get() = isEnabled

    init {
        /**
         * This block of code is executed when the module is initialized.
         * It sets up an event listener that updates the name of each TNT entity in the game world to display the time left until it explodes, every time the player entity ticks.
         */
        on<EventClientPlayerEntityTick> {
            mc.level?.entitiesForRendering()?.filterIsInstance<PrimedTnt>()?.forEach { tnt ->
                val ticks = tnt.fuse
                val percent = ticks * 100f / PrimedTnt.DEFAULT_FUSE_TIME
                val color = if (percent <= 33) "§c" else if (percent <= 66) "§e" else "§a"
                val formattedTicks = if (unit == Units.SECONDS) "%.1f".format(ticks / 20f) else ticks.toString()
                // Set the custom name of the TNT entity to display the time left until it explodes.
                tnt.customName = Component.literal("$color$formattedTicks ${unit.short}")
                tnt.isCustomNameVisible = true
            }
        }
    }

    /**
     * This enum represents the possible units of the timer.
     * It can be either seconds or ticks.
     */
    enum class Units(override val key: String, val short: String) : OptionEnum {
        SECONDS("Seconds", "sec"), TICKS("Ticks", "ticks")
    }
}
