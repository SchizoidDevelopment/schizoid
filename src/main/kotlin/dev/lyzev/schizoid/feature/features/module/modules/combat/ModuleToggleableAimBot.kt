/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.feature.features.module.modules.combat

import dev.lyzev.api.events.*
import dev.lyzev.api.math.AI
import dev.lyzev.api.math.canSee
import dev.lyzev.api.math.get
import dev.lyzev.api.opengl.WorldRenderBridge
import dev.lyzev.api.setting.settings.*
import dev.lyzev.api.settings.Setting.Companion.eq
import dev.lyzev.api.settings.Setting.Companion.neq
import dev.lyzev.schizoid.Schizoid
import dev.lyzev.schizoid.feature.IFeature
import dev.lyzev.schizoid.feature.features.module.ModuleToggleable
import dev.lyzev.schizoid.feature.features.module.modules.player.FeatureRotation.current
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.phys.HitResult
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import net.minecraft.world.level.ClipContext
import org.joml.Matrix4f
import java.awt.Color
import kotlin.math.min

object ModuleToggleableAimBot : ModuleToggleable(
    "Aim Bot", "Collect data for Aim Bot.", category = IFeature.Category.COMBAT
), EventListener {

    private var aimVec: Vec3? = null
    private var lastAimVec: Vec3? = null
    private var lastHitVec: Vec3? = null
    private var target: Entity? = null
    private data class AimDebug(val current: Vec3, val previous: Vec3?)
    @Volatile
    private var aimDebug: AimDebug? = null
    private var time = System.currentTimeMillis()

    val targets by multiOption(
        "Targets",
        "The targets to aim at.",
        BuiltInRegistries.ENTITY_TYPE.map { it.description.string to (it.description.string == "Player") }.sortedBy { it.first }.toSet()
    )
    val aimExtensionReach by slider("Aim Extension Reach", "The reach of the aim extension.", 1.8f, 0f, 5f, 1, "blocks")
    val aimThroughWalls by switch("Aim Through Walls", "Aims through walls.", false)
    val aimInstant by switch("Aim Instant", "Instantly aim at the target.", false)
    val aimSpeed by slider("Aim Speed", "The speed of the aim.", 55, 1, 100, "%%", hide = ::aimInstant eq true)
    val aimSpeedDistanceWeight by slider(
        "Aim Speed Distance Weight",
        "The weight of the distance to the target in the aim speed.",
        4,
        0,
        100,
        "%%",
        hide = ::aimInstant eq true
    )
    val aimSpeedCrosshairWeight by slider(
        "Aim Speed Crosshair Weight",
        "The weight of the target in the crosshair in the aim speed.",
        12,
        0,
        100,
        "%%",
        hide = ::aimInstant eq true
    )
    val aimSpeedHurtTimeWeight by slider(
        "Aim Speed Hurt Time Weight",
        "The weight of the hurt time in the aim speed.",
        6,
        0,
        100,
        "%%",
        hide = ::aimInstant eq true
    )
    val aimSpeedVelocityWeight by slider(
        "Aim Speed Velocity Weight",
        "The weight of the target velocity in the aim speed.",
        4,
        0,
        100,
        "%%",
        hide = ::aimInstant eq true
    )
    val aimSpeedRandomWeight by slider(
        "Aim Speed Random Weight",
        "The weight of the random factor in the aim speed.",
        2,
        0,
        100,
        "%%",
        hide = ::aimInstant eq true
    )
    val aimVector by option("Aim Vector", "The aim vector.", AimVector.Intelligent, AimVector.entries)
    val forceHit by switch("Force Hit", "Aims on the nearest vector if the artificial intelligence vector is not in reach.", true, hide = ::aimVector eq AimVector.Nearest)
    val aimSpeedForceHitWeight by slider(
        "Aim Force Hit Weight",
        "The weight of the force hit in the aim speed.",
        20,
        0,
        100,
        "%%",
        hide = ::aimInstant eq true
    )
    val visualizeAimVector by switch("Visualize Aim Vector", "Visualize the aim vector.", true)
    val reflection by switch(
        "Reflection", "Reflection effect on aim vector.", false, hide = ::visualizeAimVector neq true
    )
    val color by color("Color", "The color of the aim vector.", Color(1f, 0f, 0f, 0.8f), true, hide = {
        !visualizeAimVector || reflection
    })
    val fov by slider("Field of View", "The field of view.", 140, 1, 255, "°")
    val fovRotation by option(
        "Field of View Rotation", "Which rotation to use for the field of view.", "Server", arrayOf("Server", "Client")
    )
    val priority by option("Priority", "The priority of the target.", Priority.FOV, Priority.entries)
    val switchDelay by slider("Switch Delay", "The delay before switching targets.", 300, 0, 3000, "ms")

    override fun onDisable() {
        aimVec = null
        lastAimVec = null
        lastHitVec = null
        target = null
        aimDebug = null
        AimCubeRenderer.release()
    }

    override val shouldHandleEvents
        get() = isEnabled && mc.player != null && mc.level != null

    init {

        on<EventUpdateCrosshairTargetTick> {
            val player = mc.player ?: return@on
            val level = mc.level ?: return@on
            val hitResult = mc.hitResult ?: return@on
            val reach = player.attackReach()
            val maxReach = reach + aimExtensionReach
            fun isInRange(entity: Entity): Boolean = entity.boundingBox[player.eyePosition].let { nearest ->
                player.distanceToSqr(nearest) <= Mth.square(maxReach) && player.eyePosition[nearest].let { rotation ->
                        val deltaPitch = (if (fovRotation == "Server") current.y else player.xRot) - rotation.x
                        val deltaYaw =
                            Mth.wrapDegrees((if (fovRotation == "Server") current.x else player.yRot) - rotation.y)
                        Mth.square(deltaPitch) + Mth.square(deltaYaw)
                    } > Mth.square(255 - fov)
            }
            val possibleTarget = level.entitiesForRendering().filter { entity ->
                entity != player && entity.isAlive && targets.any { it.second && it.first == entity.type.description.string } && isInRange(entity)
            }.minByOrNull {
                priority.getEntityValue(it)
            }
            val previous = target
            if (previous == null || possibleTarget == null || System.currentTimeMillis() - time > switchDelay ||
                !previous.isAlive || previous.level() !== level || !isInRange(previous)) {
                if (possibleTarget != null) {
                    time = System.currentTimeMillis()
                }
                target = possibleTarget
            }
            val selected = target
            if (selected != null) {
                lastAimVec = aimVec
                val box = selected.boundingBox
                val candidate = aimVector.getAimVec(selected, reach, lastHitVec)
                aimVec = if (candidate.isFinite) candidate else Vec3(
                    (box.minX + box.maxX) * .5, (box.minY + box.maxY) * .5, (box.minZ + box.maxZ) * .5
                )
                val crosshairAim = box[hitResult.location]
                lastHitVec = Vec3(
                    (crosshairAim.x - box.minX) / (box.maxX - box.minX).coerceAtLeast(1e-6),
                    (crosshairAim.y - box.minY) / (box.maxY - box.minY).coerceAtLeast(1e-6),
                    (crosshairAim.z - box.minZ) / (box.maxZ - box.minZ).coerceAtLeast(1e-6)
                )
            } else {
                aimVec = null
                lastAimVec = null
                lastHitVec = null
            }
            aimDebug = aimVec?.let { AimDebug(it, lastAimVec) }
        }

        on<EventRotationGoal> { event ->
            val player = mc.player ?: return@on
            val selected = target ?: return@on
            if (!selected.isAlive || selected.level() !== player.level()) return@on
            var point = aimVec ?: return@on
            if (!aimThroughWalls && !player.canSee(point)) return@on
            if (player.boundingBox.contains(point) || selected.boundingBox.contains(player.eyePosition)) {
                point = Vec3(
                    selected.boundingBox.minX + (selected.boundingBox.maxX - selected.boundingBox.minX) * 0.5,
                    point.y,
                    selected.boundingBox.minZ + (selected.boundingBox.maxZ - selected.boundingBox.minZ) * 0.5
                )
                aimVec = point
            }
            aimDebug = AimDebug(point, lastAimVec)
            event.goal = point
            if (aimInstant) {
                event.instant = true
                return@on
            }
            event.weight = aimSpeed / 100f

            val reach = player.attackReach()
            val maxReach = (reach + aimExtensionReach).coerceAtLeast(1e-6)
            val distance = player.eyePosition.distanceTo(selected.boundingBox[player.eyePosition])
            if (distance <= reach && (selected !is LivingEntity || selected.hurtTime <= 3)) event.force = true
            event.weight += (distance / maxReach).toFloat() * aimSpeedDistanceWeight / 100f

            if (mc.crosshairPickEntity == selected) {
                val weight = aimSpeedCrosshairWeight / 100f
                event.weight -= Schizoid.random.nextFloat() * (weight * 0.2f) + weight * 0.8f
            }
            if (selected is LivingEntity) {
                event.weight += (1 - selected.hurtTime / selected.hurtDuration.coerceAtLeast(1).toFloat()) * aimSpeedHurtTimeWeight / 100f
            }
            val velocity = Vec3(selected.x - selected.xOld, selected.y - selected.yOld, selected.z - selected.zOld).lengthSqr() / 12.0
            event.weight += velocity.toFloat() * aimSpeedVelocityWeight / 100f
            val randomWeight = aimSpeedRandomWeight / 100f
            event.weight += randomWeight - Schizoid.random.nextFloat() * randomWeight / 2f
            if (forceHit && (selected !is LivingEntity || selected.hurtTime <= 3) && player.eyePosition.distanceToSqr(point) < reach * reach) {
                event.weight += aimSpeedForceHitWeight / 100f + Schizoid.random.nextFloat() * 0.1f
            }
        }

        on<EventRenderWorld>(Event.Priority.LOW) { event ->
            if (!visualizeAimVector) return@on
            val debug = aimDebug ?: return@on
            val frame = WorldRenderBridge.currentFrame ?: return@on
            val cam = frame.cameraPosition
            val modelViewMat = Matrix4f(event.modelViewMat)
            val point = debug.previous?.lerp(debug.current, frame.tickDelta.toDouble()) ?: debug.current
            modelViewMat.translate(
                (point.x - cam.x).toFloat(), (point.y - cam.y).toFloat(), (point.z - cam.z).toFloat()
            )
            AimCubeRenderer.draw(modelViewMat, event.projMat, cam, color, reflection)
        }
    }

    enum class AimVector : OptionEnum {
        Intelligent {

            private val layers = mutableListOf<Pair<Array<DoubleArray>, DoubleArray>>()

            init {
                for (i in 1..4) {
                    val weights = AI.loadWeights("layer_${i}_weights.csv")
                    val biases = AI.loadBiases("layer_${i}_biases.csv")
                    layers.add(Pair(weights, biases))
                }
            }

            override fun getAimVec(target: Entity, reach: Double, lastHitVec: Vec3?): Vec3 {
                val maxReach = (reach + aimExtensionReach).coerceAtLeast(1e-6)
                val box = target.boundingBox
                var wasAiming = 0
                val hitVec = lastHitVec ?: run {
                    wasAiming = 1
                    val nearest = box[mc.player!!.eyePosition]
                    Vec3(
                        (nearest.x - box.minX) / (box.maxX - box.minX).coerceAtLeast(1e-6),
                        (nearest.y - box.minY) / (box.maxY - box.minY).coerceAtLeast(1e-6),
                        (nearest.z - box.minZ) / (box.maxZ - box.minZ).coerceAtLeast(1e-6)
                    )
                }
                val lookVecEntity =
                    Vec3.directionFromRotation(mc.cameraEntity!!.eyePosition[target.eyePosition]).normalize().add(1.0, 1.0, 1.0)
                        .normalize()
                val reachPercentage = mc.cameraEntity!!.eyePosition.distanceTo(box[mc.cameraEntity!!.eyePosition]) / maxReach
                val velocityTarget = Vec3(
                    target.x - target.xOld, target.y - target.yOld, target.z - target.zOld
                ).normalize().add(1.0, 1.0, 1.0).normalize()
                val velocityPlayer = Vec3(mc.player!!.x - mc.player!!.xOld, mc.player!!.y - mc.player!!.yOld, mc.player!!.z - mc.player!!.zOld).normalize().add(1.0, 1.0, 1.0).normalize()
                val lookVec = Vec3.directionFromRotation(current.y, current.x).normalize().add(1.0, 1.0, 1.0).normalize()

                val speedTarget = min(Vec3(target.x - target.xOld, target.y - target.yOld, target.z - target.zOld).lengthSqr() / 100.0, 1.0)
                val speedPlayer = min(Vec3(mc.player!!.x - mc.player!!.xOld, mc.player!!.y - mc.player!!.yOld, mc.player!!.z - mc.player!!.zOld).lengthSqr() / 100.0, 1.0)
                val clicks = min(mc.options.keyAttack.clickCount / 4.0, 1.0)
                val raycast = DoubleArray(8)
                val player = checkNotNull(mc.player)
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

                val input = doubleArrayOf(
                    reachPercentage,
                    hitVec.x,
                    hitVec.y,
                    hitVec.z,
                    lookVecEntity.x,
                    lookVecEntity.y,
                    lookVecEntity.z,
                    wasAiming.toDouble(),
                    speedTarget,
                    speedPlayer,
                    lookVec.x,
                    lookVec.y,
                    lookVec.z,
                    clicks,
                    *raycast,
                    velocityTarget.x,
                    velocityTarget.y,
                    velocityTarget.z,
                    velocityPlayer.x,
                    velocityPlayer.y,
                    velocityPlayer.z
                )

                // Initial hidden layer
                var hiddenLayerOutput = AI.applyLayer(input, layers[0].first, layers[0].second)
                // Subsequent hidden layers
                for (i in 1..2) {
                    val hiddenLayerWeights = layers[i].first
                    val hiddenLayerBiases = layers[i].second
                    hiddenLayerOutput = AI.applyLayer(hiddenLayerOutput, hiddenLayerWeights, hiddenLayerBiases)
                }
                // Final output layer
                val output = AI.applyLayer(hiddenLayerOutput, layers[3].first, layers[3].second, useSigmoid = true)
                val aimVec = Vec3(
                    output[0] * (box.maxX - box.minX) + box.minX,
                    output[1] * (box.maxY - box.minY) + box.minY,
                    output[2] * (box.maxZ - box.minZ) + box.minZ
                )
                return if (shouldForceHit(target, reach, aimVec)) {
                    target.boundingBox[mc.player!!.eyePosition]
                } else {
                    aimVec
                }
            }
        },
        Nearest {
            override fun getAimVec(target: Entity, reach: Double, lastHitVec: Vec3?): Vec3 {
                return target.boundingBox[mc.player!!.eyePosition]
            }
        },
        Head {
            override fun getAimVec(target: Entity, reach: Double, lastHitVec: Vec3?): Vec3 {
                return Vec3(target.x, target.eyeY, target.z)
            }
        },
        Center {
            override fun getAimVec(target: Entity, reach: Double, lastHitVec: Vec3?): Vec3 {
                val box = target.boundingBox
                return Vec3(
                    box.minX + (box.maxX - box.minX) * 0.5,
                    box.minY + (box.maxY - box.minY) * 0.5,
                    box.minZ + (box.maxZ - box.minZ) * 0.5
                )
            }
        };

        abstract fun getAimVec(target: Entity, reach: Double, lastHitVec: Vec3?): Vec3

        protected fun shouldForceHit(target: Entity, reach: Double, aimVec: Vec3): Boolean {
            if (forceHit && (target !is LivingEntity || target.hurtTime <= 3)) {
                val nearest = target.boundingBox[mc.player!!.eyePosition]
                if (mc.player!!.eyePosition.distanceToSqr(aimVec) > reach * reach && mc.player!!.eyePosition.distanceToSqr(nearest) <= reach * reach) {
                    return true
                }
            }
            return false
        }

        override val key = name
    }

    enum class Priority : OptionEnum {
        FOV {
            override fun getEntityValue(entity: Entity): Float {
                val rotation = mc.player!!.eyePosition[entity.boundingBox[mc.player!!.eyePosition]]
                val deltaPitch =
                    Mth.wrapDegrees(rotation.x - if (fovRotation == "Server") current.y else mc.player!!.xRot)
                val deltaYaw =
                    Mth.wrapDegrees(rotation.y - if (fovRotation == "Server") current.x else mc.player!!.yRot)
                return -(Mth.square(deltaPitch) + Mth.square(deltaYaw))
            }
        },
        Distance {
            override fun getEntityValue(entity: Entity) = entity.distanceToSqr(checkNotNull(mc.player)).toFloat()
        },
        Health {
            override fun getEntityValue(entity: Entity) = (entity as? LivingEntity)?.health ?: Float.MAX_VALUE
        },
        HurtTime {
            override fun getEntityValue(entity: Entity) = (entity as? LivingEntity)?.hurtTime?.toFloat() ?: Float.MAX_VALUE
        },
        Random {
            override fun getEntityValue(entity: Entity) = Schizoid.random.nextFloat()
        };

        abstract fun getEntityValue(entity: Entity): Float

        override val key = name
    }
}
