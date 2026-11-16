/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.feature.features.module.modules.combat

import dev.lyzev.api.events.EventListener
import dev.lyzev.api.events.EventUpdateCrosshairTargetTick
import dev.lyzev.api.events.on
import dev.lyzev.api.math.Clicker
import dev.lyzev.api.setting.settings.multiOption
import dev.lyzev.api.setting.settings.slider
import dev.lyzev.api.setting.settings.switch
import dev.lyzev.api.settings.Setting.Companion.neq
import dev.lyzev.schizoid.feature.IFeature
import dev.lyzev.schizoid.feature.features.module.ModuleToggleable
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.projectile.ProjectileUtil
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.util.Mth

object ModuleToggleableTriggerBot : ModuleToggleable(
    "Trigger Bot", "Automatically attacks entities in the player's crosshair.", category = IFeature.Category.COMBAT
), EventListener {

    private var lastHit = 0L

    val targets by multiOption(
        "Targets",
        "The targets to aim at.",
        BuiltInRegistries.ENTITY_TYPE.map { it.description.string to (it.description.string == "Player") }.sortedBy { it.first }.toSet()
    )
    val clicker = Clicker(this)
    val miss by switch("Miss", "Hits in the air to simulate a miss.", true)
    val reachExtension by slider(
        "Reach Extension",
        "The distance the bot hits the air before the entity.",
        1.4f,
        0f,
        5f,
        1,
        "blocks",
        hide = ::miss neq true
    )
    val reactionTime by slider(
        "Reaction Time",
        "The time the bot hits the air after the entity is in the crosshair.",
        300,
        0,
        1000,
        "ms",
        hide = ::miss neq true
    )

    private fun click(amount: Int) {
        mc.options.keyAttack.clickCount += amount
    }

    override val shouldHandleEvents
        get() = isEnabled && mc.player != null && mc.gui.screen() == null && mc.hitResult != null

    init {
        // Attack the entity in the player's crosshair.
        on<EventUpdateCrosshairTargetTick> {
            if (mc.crosshairPickEntity != null && targets.any { it.second && it.first == mc.crosshairPickEntity!!.type.description.string }) {
                lastHit = System.currentTimeMillis()
                val click =
                    clicker.tick(mc.crosshairPickEntity is LivingEntity && (mc.crosshairPickEntity as LivingEntity).hurtTime == 0)
                if (click > 0) {
                    click(click)
                }
            } else if (miss) {
                if (System.currentTimeMillis() - lastHit <= reactionTime) {
                    val click =
                        clicker.tick(false)
                    if (click > 0) {
                        click(click)
                    }
                } else if (mc.cameraEntity != null) {
                    val camera = mc.cameraEntity ?: return@on
                    val reach = mc.player!!.attackReach() + reachExtension
                    val camVec = camera.getEyePosition(1f)
                    val lookVec = camera.getViewVector(1f)
                    val hitVec = lookVec.scale(reach).add(camVec)
                    val box = camera.boundingBox.expandTowards(lookVec.scale(reach)).inflate(1.0, 1.0, 1.0)
                    val entityHitResult = ProjectileUtil.getEntityHitResult(
                        camera,
                        camVec,
                        hitVec,
                        box,
                        { entity: Entity -> !entity.isSpectator && entity.isPickable },
                        Mth.square(reach)
                    )
                    if (entityHitResult != null && targets.any { it.second && it.first == entityHitResult.entity.type.description.string }) {
                        val click =
                            clicker.tick(false)
                        if (click > 0) {
                            click(click)
                        }
                    } else {
                        clicker.regenerate()
                    }
                }
            } else {
                clicker.regenerate()
            }
        }
    }
}
