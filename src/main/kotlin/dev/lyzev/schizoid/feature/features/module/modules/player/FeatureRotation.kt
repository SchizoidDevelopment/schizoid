/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.feature.features.module.modules.player

import dev.lyzev.api.animation.EasingFunction
import dev.lyzev.api.events.*
import dev.lyzev.api.glfw.GLFWKey
import dev.lyzev.api.math.NoiseGenerator
import dev.lyzev.api.setting.settings.slider
import dev.lyzev.api.setting.settings.switch
import dev.lyzev.api.settings.Setting.Companion.neq
import dev.lyzev.schizoid.Schizoid
import dev.lyzev.schizoid.feature.IFeature
import net.minecraft.client.MouseHandler
import net.minecraft.client.player.LocalPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Input
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import org.joml.Vector2f
import kotlin.math.*

object FeatureRotation : IFeature, EventListener {

    val current = Vector2f()
    private val last = Vector2f()
    private var time = System.currentTimeMillis()
    private val noiseGenerator = NoiseGenerator()
    private var activePlayer: LocalPlayer? = null
    private var requireRevert = false
    private var cachedYaw = 0f

    val silent by switch("Silent", "Silent rotation", true)
    val clientSide by switch("Client Side", "Visualize rotation client side.", false, hide = ::silent neq true)

    val minYaw by slider("Min Yaw", "The minimum yaw to rotate.", 1.5f, 0f, 180f, 1, "°")
    val minPitch by slider("Min Pitch", "The minimum pitch to rotate.", 1f, 0f, 180f, 1, "°")

    val revert by switch("Revert", "Revert smooth to the actual yaw and pitch", true)
    val revertDelay by slider(
        "Revert Delay", "The delay before reverting to the actual yaw and pitch", 500, 0, 3000, "ms"
    )
    val revertWeight by slider(
        "Revert Weight", "Revert smooth to the actual yaw and pitch", 80, 1, 100, "%%", hide = ::revert neq true
    )

    val jitter by switch("Jitter", "Jitter the rotation", true)
    val jitterStrength by slider(
        "Jitter Strength", "The strength of the jitter effect.", 5, 0, 100, "%%", hide = ::jitter neq true
    )
    val jitterBasedOnClicks by switch(
        "Jitter Based On Clicks", "Jitter based on click speed.", true, hide = ::jitter neq true
    )

    val movementCorrection by switch("Movement Correction", "Corrects movement.", true)
    val silentMovementCorrection by switch(
        "Silent Movement Correction", "Corrects movement silently.", true, hide = ::movementCorrection neq true
    )

    override val name = "Rotation"
    override val desc = "Rotates the player's view"
    override var keybinds = setOf<GLFWKey>()
    override val category = IFeature.Category.PLAYER

    override fun keybindReleased() {}
    override val shouldHandleEvents = true

    /**
     * Corrects the mouse sensitivity.
     *
     * @see [MouseHandler]
     *
     * @param correct The correct vector.
     * @param incorrect The incorrect vector.
     */
    fun correctMouseSensitivity(correct: Vector2f, incorrect: Vector2f, client: Boolean) {
        incorrect.set(Mth.wrapDegrees(incorrect.x), Mth.clamp(incorrect.y, -90f, 90f))
        val mouseSensitivity = (mc.options.sensitivity().get() * 0.6f.toDouble() + 0.2f.toDouble()).pow(3.0) * 8.0
        var cursorDeltaX =
            (Mth.wrapDegrees(incorrect.x - correct.x) / 0.15f / mouseSensitivity).roundToInt().toDouble()
        var cursorDeltaY = ((incorrect.y - correct.y) / 0.15f / mouseSensitivity).roundToInt().toDouble()
        cursorDeltaX *= mouseSensitivity
        cursorDeltaY *= mouseSensitivity
        incorrect.set((correct.x + cursorDeltaX * 0.15f).toFloat(), Mth.clamp(correct.y + cursorDeltaY * 0.15, -90.0, 90.0).toFloat())
        // Fixes the actual yaw and pitch
        if (!client)
            return
        cursorDeltaX =
            (Mth.wrapDegrees(mc.player!!.yRot - correct.x) / 0.15f / mouseSensitivity).roundToInt().toDouble()
        cursorDeltaY = ((mc.player!!.xRot - correct.y) / 0.15f / mouseSensitivity).roundToInt().toDouble()
        cursorDeltaX *= mouseSensitivity
        cursorDeltaY *= mouseSensitivity
        mc.player!!.yRot = correct.x + cursorDeltaX.toFloat() * 0.15f
        mc.player!!.xRot = Mth.clamp(correct.y + cursorDeltaY.toFloat() * 0.15f, -90f, 90f)
    }

    /**
     * Gets the rotation from the current vector to the target vector.
     *
     * @see [Entity.lookAt]
     *
     * @param target The target vector.
     * @return The rotation.
     */
    fun Vec3.getRotation(target: Vec3): Vector2f {
        val x = target.x - x
        val y = target.y - y
        val z = target.z - z
        val yaw = Mth.wrapDegrees(Math.toDegrees(Mth.atan2(z, x)).toFloat() - 90f)
        val pitch = Mth.wrapDegrees(-Math.toDegrees(Mth.atan2(y, sqrt(x.pow(2) + z.pow(2)))).toFloat())
        return Vector2f(yaw, pitch)
    }

    /** Called after vanilla applies a server correction, including relative rotation flags. */
    fun acceptServerRotation() {
        val player = mc.player ?: return
        activePlayer = player
        current.set(player.yRot, player.xRot)
        last.set(current)
        cachedYaw = player.yRot
        requireRevert = false
        time = System.currentTimeMillis()
    }

    init {
        on<EventSwapBuffers> {
            val player = mc.player
            if (!isIngame || player == null) {
                activePlayer = null
                requireRevert = false
                return@on
            }
            if (activePlayer !== player) acceptServerRotation()
            if (mc.gui.screen() == null) {
                val event = EventRotationGoal()
                event.fire()
                var isReverting = false
                if (event.goal == null && requireRevert && revert) {
                    if (System.currentTimeMillis() - time > revertDelay || !silent) {
                        event.weight = revertWeight / 100f + 0.5f + Schizoid.random.nextFloat() * .2f + 2f
                        event.goal = player.getViewVector(1f).scale(3.0).add(player.eyePosition)
                        isReverting = true
                    }
                } else if (event.goal != null) {
                    time = System.currentTimeMillis()
                }
                val target = event.goal
                if (target != null) {
                    requireRevert = true
                    val goal = (mc.cameraEntity ?: player).eyePosition.getRotation(target)
                    val deltaYaw = Mth.wrapDegrees(goal.x - current.x)
                    val deltaPitch = goal.y - current.y
                    val squaredDistance = Mth.square(deltaYaw) + Mth.square(deltaPitch)
                    if (event.instant || squaredDistance < 1e-6f) {
                        correctMouseSensitivity(current, goal, isReverting)
                        current.set(goal)
                    } else {
                        // Circular easing is only defined on [0, 1]; keep the faster revert multiplier outside it.
                        val weightPitch = EasingFunction.IN_OUT_CIRC(event.weight.coerceIn(0f, 1f).toDouble()).toFloat() * max(event.weight, 1f)
                        val weightYaw = EasingFunction.LINEAR(event.weight.toDouble()).toFloat()
                        val frameDuration = mc.deltaTracker.realtimeDeltaTicks
                        val minYaw = this.minYaw * (noiseGenerator.noise(System.currentTimeMillis() / 20.0).toFloat() + 1f) / 2f + this.minYaw
                        val minPitch = this.minPitch * (noiseGenerator.noise(System.currentTimeMillis() / 20.0).toFloat() + 1f) / 2f + this.minPitch
                        val maxYaw = max(Mth.square(deltaYaw) / squaredDistance * weightYaw * 30, minYaw * frameDuration)
                        val maxPitch = max(Mth.square(deltaPitch) / squaredDistance * weightPitch * 30, minPitch * frameDuration)
                        var yawJitter = if (jitter) Schizoid.random.nextFloat() * jitterStrength / 100f - jitterStrength / 200f else 0f
                        var pitchJitter = if (jitter) Schizoid.random.nextFloat() * jitterStrength / 100f - jitterStrength / 200f else 0f
                        if (jitterBasedOnClicks) {
                            val cpt = max(mc.options.keyAttack.clickCount / 4f + 0.1f, 1f)
                            yawJitter *= cpt
                            pitchJitter *= cpt
                        }
                        val new = Vector2f(
                            current.x + deltaYaw.coerceIn(-maxYaw, maxYaw) * frameDuration + yawJitter,
                            Mth.clamp(current.y + deltaPitch.coerceIn(-maxPitch, maxPitch) * frameDuration + pitchJitter, -90f, 90f)
                        )
                        correctMouseSensitivity(current, new, isReverting)
                        current.set(new)
                    }
                    if (Mth.square(Mth.wrapDegrees(player.yRot - current.x)) + Mth.square(player.xRot - current.y) <= 4f) {
                        requireRevert = false
                    }
                } else if (System.currentTimeMillis() - time > revertDelay) {
                    noiseGenerator.setSeed()
                    current.set(player.yRot, player.xRot)
                }
            }
            if (!silent) {
                player.yRot = current.x
                player.xRot = current.y
            }
        }
        on<EventClientPlayerEntityTick> { event ->
            if (activePlayer !== event.player) acceptServerRotation()
            cachedYaw = event.player.yRot
            event.player.yRot = current.x
            event.player.xRot = current.y
            event.player.yRotO = last.x
            event.player.xRotO = last.y
            last.set(current)
        }
        on<EventUpdateCrosshairTarget> { event ->
            if (mc.player == null) return@on
            if (activePlayer !== mc.player) acceptServerRotation()
            event.camera.yRot = current.x
            event.camera.xRot = current.y
            event.camera.yRotO = last.x
            event.camera.xRotO = last.y
        }
        on<EventUpdateVelocity> { event ->
            if (movementCorrection && mc.player != null) event.yaw = current.x
        }
        on<EventKeyboardInputTick> { event ->
            if (!movementCorrection || !silentMovementCorrection || mc.player == null) return@on
            val presses = event.input.keyPresses
            val input = Vec3(
                (if (presses.left()) 1.0 else 0.0) - (if (presses.right()) 1.0 else 0.0),
                0.0,
                (if (presses.forward()) 1.0 else 0.0) - (if (presses.backward()) 1.0 else 0.0)
            )
            val actual = Entity.getInputVector(input, 0.02f, cachedYaw)
            var nearest = Vec3.ZERO
            var minDistance = Double.MAX_VALUE
            for (x in -1..1) {
                for (z in -1..1) {
                    val candidate = Vec3(x.toDouble(), 0.0, z.toDouble())
                    val distance = actual.distanceToSqr(Entity.getInputVector(candidate, 0.02f, current.x))
                    if (distance <= minDistance) {
                        minDistance = distance
                        nearest = candidate
                    }
                }
            }
            // Input is a record in 26.2. Vanilla calculates the normalized move vector immediately afterwards.
            event.input.keyPresses = Input(
                nearest.z > 0, nearest.z < 0, nearest.x > 0, nearest.x < 0,
                presses.jump(), presses.shift(), presses.sprint()
            )
        }
        on<EventLerpPosAndRotation> { event ->
            if (event.step <= 0) return@on
            val delta = 1.0 / event.step
            val newYaw = current.x + Mth.wrapDegrees(event.yaw - current.x) * delta
            val newPitch = Mth.lerp(delta, current.y.toDouble(), event.pitch)
            current.set((newYaw % 360.0).toFloat(), newPitch.coerceIn(-90.0, 90.0).toFloat())
        }
        on<EventClientPlayerEntityRender> {
            if (clientSide) {
                it.headYaw = current.x
                it.prevHeadYaw = last.x
                it.pitch = current.y
                it.prevPitch = last.y
            }
        }
    }
}
