/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.feature.features.module.modules.combat

import dev.lyzev.api.events.EventAttackEntityPost
import dev.lyzev.api.events.EventAttackEntityPre
import dev.lyzev.api.events.EventListener
import dev.lyzev.api.events.on
import dev.lyzev.api.setting.settings.slider
import dev.lyzev.api.setting.settings.switch
import dev.lyzev.schizoid.feature.IFeature
import dev.lyzev.schizoid.feature.features.module.ModuleToggleable
import net.minecraft.world.phys.Vec3

object ModuleToggleableKeepSprint :
    ModuleToggleable("Keep Sprint", "Keeps sprinting while attacking.", category = IFeature.Category.COMBAT),
    EventListener {

    val velocityMultiplier by slider("Velocity Multiplier", "Multiplies the player's velocity.", 100, 0, 100, "%%")
    val sprint by switch("Sprint", "Keeps sprinting while attacking.", true)

    private var velocity: Vec3? = null
    private var isSprinting = false

    override fun onDisable() {
        velocity = null
        isSprinting = false
    }

    override val shouldHandleEvents
        get() = isEnabled && mc.player != null

    init {
        on<EventAttackEntityPre> {
            velocity = it.player.deltaMovement
            isSprinting = it.player.isSprinting
        }
        on<EventAttackEntityPost> {
            if (isSprinting && sprint) {
                it.player.isSprinting = true
            }
            if (velocity == null) return@on
            val multi = velocityMultiplier / 100.0
            it.player.deltaMovement = velocity!!.multiply(multi, 1.0, multi)
        }
    }
}
