/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.feature.features.module.modules.combat

import com.google.common.primitives.Doubles.max
import dev.lyzev.api.events.*
import dev.lyzev.api.setting.settings.slider
import dev.lyzev.api.setting.settings.switch
import dev.lyzev.api.settings.Setting.Companion.neq
import dev.lyzev.schizoid.feature.IFeature
import dev.lyzev.schizoid.feature.features.module.ModuleToggleable
import net.minecraft.world.entity.ai.attributes.Attributes
import kotlin.math.pow

object ModuleToggleableReach :
    ModuleToggleable("Reach", "Manipulates the player's reach.", category = IFeature.Category.COMBAT), EventListener {

    val entity by switch("Entity", "Manipulate the entity raycast.", true)
    val entityReach by slider("Entity Reach", "The reach of the entity raycast.", Attributes.ENTITY_INTERACTION_RANGE.value().defaultValue.toFloat(), 0f, 6f, 1, "blocks", hide = ::entity neq true)
    val entityThroughWalls by switch("Entity Through Walls", "Reach the entity through walls.", false, hide = ::entity neq true)
    val entityThroughWallsReach by slider("Entity Through Walls Reach", "The reach of the entity raycast through walls.", Attributes.ENTITY_INTERACTION_RANGE.value().defaultValue.toFloat(), 0f, 6f, 1, "blocks", hide = {
        !entity || !entityThroughWalls
    })

    val block by switch("Block", "Manipulate the block raycast.", true)
    val blockReach by slider("Block Reach", "The reach of the block raycast.", Attributes.BLOCK_INTERACTION_RANGE.value().defaultValue.toFloat(), 0f, 6f, 1, "blocks", hide = ::block neq true)

    override val shouldHandleEvents
        get() = isEnabled

    init {
        // Manipulate the reach of the player.
        on<EventGetAttributeValue> { event ->
            if (event.attribute == Attributes.ENTITY_INTERACTION_RANGE.value() && entity) {
                event.value = entityReach.toDouble()
            } else if (event.attribute == Attributes.BLOCK_INTERACTION_RANGE.value() && block) {
                event.value = blockReach.toDouble()
            }
        }
        // Hit the entity through walls by setting the distance to the entity reach squared, so the game uses this distance to raycast.
        var cachedBlockDistance = 0.0
        on<EventDistanceToBlockHitResult> { event ->
            if (entity && entityThroughWalls) {
                cachedBlockDistance = event.distance
                event.distance = max(event.distance, entityThroughWallsReach.toDouble().pow(2))
            }
        }
        // Hit the entity through walls by setting the distance to -1, so the game believes the entity is in front of the block.
        on<EventDistanceToEntityHitResult> { event ->
            if (entity && entityThroughWalls) {
                if (event.distance <= entityThroughWallsReach.pow(2) || event.distance <= cachedBlockDistance) {
                    event.distance = -1.0
                } else {
                    event.distance = Double.MAX_VALUE
                }
            }
        }
    }
}
