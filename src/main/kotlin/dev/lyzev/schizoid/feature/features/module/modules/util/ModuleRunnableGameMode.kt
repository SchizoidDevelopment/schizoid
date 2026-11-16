/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.feature.features.module.modules.util

import dev.lyzev.api.setting.settings.OptionEnum
import dev.lyzev.api.setting.settings.option
import dev.lyzev.schizoid.feature.IFeature
import dev.lyzev.schizoid.feature.features.module.ModuleRunnable

object ModuleRunnableGameMode :
    ModuleRunnable("Game Mode", "Allows you to change your game mode.", category = IFeature.Category.UTIL) {

    val mode by option("Mode", "The game mode to set.", GameMode.CREATIVE, GameMode.entries)

    override fun invoke(): String? {
        if (!isIngame) return "You are not in a game."
        mc.gameMode?.setLocalMode(mode.type)
        return null
    }

    enum class GameMode(val type: net.minecraft.world.level.GameType) : OptionEnum {
        SURVIVAL(net.minecraft.world.level.GameType.SURVIVAL),
        CREATIVE(net.minecraft.world.level.GameType.CREATIVE),
        ADVENTURE(net.minecraft.world.level.GameType.ADVENTURE),
        SPECTATOR(net.minecraft.world.level.GameType.SPECTATOR);

        override val key: String
            get() = type.getName()
    }
}
