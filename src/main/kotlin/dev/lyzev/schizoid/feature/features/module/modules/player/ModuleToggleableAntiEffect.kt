/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.feature.features.module.modules.player

import dev.lyzev.api.events.EventHasStatusEffect
import dev.lyzev.api.events.EventListener
import dev.lyzev.api.events.on
import dev.lyzev.api.setting.settings.multiOption
import dev.lyzev.schizoid.feature.IFeature
import dev.lyzev.schizoid.feature.features.module.ModuleToggleable
import net.minecraft.core.registries.BuiltInRegistries

object ModuleToggleableAntiEffect :
    ModuleToggleable("Anti Effect", "Makes everything brighter.", category = IFeature.Category.PLAYER), EventListener {

    val effects = multiOption(
        "Effects",
        "Effects to disable.",
        BuiltInRegistries.MOB_EFFECT.map { it.displayName.string to !it.isBeneficial }.sortedBy { it.first }.toSet()
    ) { options ->
        for (option in options) {
            if (option.second)
                remove.add(BuiltInRegistries.MOB_EFFECT.first { it.displayName.string == option.first })
            else
                remove.remove(BuiltInRegistries.MOB_EFFECT.first { it.displayName.string == option.first })
        }
    }

    private val remove = BuiltInRegistries.MOB_EFFECT.filter { !it.isBeneficial }.toMutableSet()

    override val shouldHandleEvents: Boolean
        get() = isEnabled && isIngame

    init {
        on<EventHasStatusEffect> { event ->
            if (mc.player == event.entity && remove.contains(event.effect))
                event.hasStatusEffect = false
        }
    }
}
