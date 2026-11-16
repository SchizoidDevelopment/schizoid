/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.feature.features.module.modules.movement

import dev.lyzev.api.events.EventClientPlayerEntityTick
import dev.lyzev.api.events.EventListener
import dev.lyzev.api.events.on
import dev.lyzev.api.setting.settings.slider
import dev.lyzev.schizoid.feature.IFeature
import dev.lyzev.schizoid.feature.features.module.ModuleToggleable
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.vehicle.boat.AbstractBoat
import net.minecraft.world.phys.Vec3

object ModuleToggleableBoatBounce :
    ModuleToggleable("Boat Bounce", "Makes boats bounce.", category = IFeature.Category.MOVEMENT), EventListener {

    val bounce by slider("Bounce", "Sets the bounce of the boat.", 10, 5, 15)

    init {
        on<EventClientPlayerEntityTick> { event ->
            val vehicle = event.player.vehicle
            if (vehicle is AbstractBoat) {
                val vel = Entity.getInputVector(
                    Vec3(
                        event.player.xxa.toDouble(),
                        event.player.yya.toDouble(),
                        event.player.zza.toDouble()
                    ),
                    ((if (mc.options.keySprint.isDown) .1f else .05f) * bounce / 2f),
                    event.player.yRot
                )
                vehicle.setPos(
                    vehicle.x + vel.x,
                    vehicle.y + vel.y + (if (mc.options.keySprint.isDown) 1f else .5f) * bounce / 10f,
                    vehicle.z + vel.z
                )
            }
        }
    }

    override val shouldHandleEvents: Boolean
        get() = isEnabled && mc.player != null
}
