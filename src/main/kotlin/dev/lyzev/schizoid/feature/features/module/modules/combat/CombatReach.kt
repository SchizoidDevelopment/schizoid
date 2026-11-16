/*
 * Copyright (c) 2026. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.feature.features.module.modules.combat

import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.component.DataComponents

/** 26.2 weapons may supply their own range instead of the player's interaction attribute. */
internal fun LocalPlayer.attackReach(): Double = getActiveItem().get(DataComponents.ATTACK_RANGE)
    ?.effectiveMaxRange(this)?.toDouble() ?: entityInteractionRange()
