/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.feature.features.module.modules.combat

import dev.lyzev.api.events.*
import dev.lyzev.api.math.get
import dev.lyzev.schizoid.Schizoid
import dev.lyzev.schizoid.feature.IFeature
import dev.lyzev.schizoid.feature.features.module.ModuleToggleable
import imgui.ImGui
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.level.ClipContext
import java.io.File
import kotlin.math.min

object ModuleToggleableAimBotDataCollector : ModuleToggleable(
    "Aim Bot Data Collector", "Collect data for Aim Bot.", category = IFeature.Category.COMBAT
), EventListener {

    override val hide = !Schizoid.DEVELOPER_MODE

    val input = StringBuilder()
    val output = StringBuilder()

    override val shouldHandleEvents
        get() = isEnabled

    override fun onDisable() {
        File(Schizoid.root, "input.csv").writeText(input.toString())
        File(Schizoid.root, "output.csv").writeText(output.toString())
    }

    init {
        var lastHitVec: Vec3? = null
        var data = 0

        on<EventUpdateCrosshairTargetTick>(Event.Priority.LOWEST) {
            val player = mc.player
            if (player == null || !player.isAlive || mc.gui.screen() != null) {
                toggle()
                lastHitVec = null
                return@on
            }
            val target = mc.crosshairPickEntity as? LivingEntity
            val hit = mc.hitResult
            if (target != null && target.isAlive && hit != null) {
                val box = target.boundingBox
                val crosshairAim = box[hit.location]
                var wasAiming = 0
                val hitVec = lastHitVec ?: run {
                    wasAiming = 1
                    val nearest = box[player.eyePosition]
                    Vec3(
                        (nearest.x - box.minX) / (box.maxX - box.minX),
                        (nearest.y - box.minY) / (box.maxY - box.minY),
                        (nearest.z - box.minZ) / (box.maxZ - box.minZ)
                    )
                }
                val lookVecEntity = Vec3.directionFromRotation(player.eyePosition[target.eyePosition]).normalize().add(1.0, 1.0, 1.0).normalize()
                val lookVec = player.getViewVector(1f).normalize().add(1.0, 1.0, 1.0).normalize()
                val reach = player.eyePosition.distanceTo(box[player.eyePosition]) / player.attackReach().coerceAtLeast(1e-6)
                val targetMotion = Vec3(target.x - target.xOld, target.y - target.yOld, target.z - target.zOld)
                val playerMotion = Vec3(player.x - player.xOld, player.y - player.yOld, player.z - player.zOld)
                val speedTarget = min(targetMotion.lengthSqr() / 100.0, 1.0)
                val speedPlayer = min(playerMotion.lengthSqr() / 100.0, 1.0)
                val velocityTarget = targetMotion.normalize().add(1.0, 1.0, 1.0).normalize()
                val velocityPlayer = playerMotion.normalize().add(1.0, 1.0, 1.0).normalize()
                val clicks = min(mc.options.keyAttack.clickCount / 4.0, 1.0)
                val raycast = DoubleArray(8)
                val from = player.eyePosition
                for (x in 0..1) {
                    for (y in 0..1) {
                        for (z in 0..1) {
                            val to = Vec3(
                                box.minX + x * (box.maxX - box.minX) * 0.5 + 0.25 * (box.maxX - box.minX),
                                box.minY + y * (box.maxY - box.minY) * 0.5 + 0.25 * (box.maxY - box.minY),
                                box.minZ + z * (box.maxZ - box.minZ) * 0.5 + 0.25 * (box.maxZ - box.minZ)
                            )
                            val hitResult = mc.level!!.clip(ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player))
                            if (hitResult.type == HitResult.Type.MISS) {
                                raycast[x * 4 + y * 2 + z] = 1.0
                            } else {
                                raycast[x * 4 + y * 2 + z] = 0.0
                            }
                        }
                    }
                }
                input.appendLine("$reach;${hitVec.x};${hitVec.y};${hitVec.z};${lookVecEntity.x};${lookVecEntity.y};${lookVecEntity.z};$wasAiming;${speedTarget};${speedPlayer};${lookVec.x};${lookVec.y};${lookVec.z};${clicks};${raycast.joinToString(";")};${velocityTarget.x};${velocityTarget.y};${velocityTarget.z};${velocityPlayer.x};${velocityPlayer.y};${velocityPlayer.z}")
                lastHitVec = Vec3((crosshairAim.x - box.minX) / (box.maxX - box.minX), (crosshairAim.y - box.minY) / (box.maxY - box.minY), (crosshairAim.z - box.minZ) / (box.maxZ - box.minZ))
                output.appendLine("${lastHitVec!!.x};${lastHitVec!!.y};${lastHitVec!!.z}")
                data++
            } else {
                lastHitVec = null
            }
        }

        on<EventRenderImGuiContent> {
            ImGui.getForegroundDrawList().addText(ImGui.getMainViewport().centerX, 10f, -1, "Data: $data")
        }
    }
}
