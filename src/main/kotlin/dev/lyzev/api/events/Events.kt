/*
 * Copyright (c) 2023. Schizoid
 * All rights reserved.
 */

package dev.lyzev.api.events

import dev.lyzev.api.setting.settings.OptionEnum
import dev.lyzev.api.theme.OSTheme
import dev.lyzev.schizoid.Schizoid
import net.minecraft.world.level.block.Block
import net.minecraft.client.player.KeyboardInput
import net.minecraft.client.player.LocalPlayer
import net.minecraft.client.KeyMapping
import net.minecraft.client.DeltaTracker
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.model.EntityModel
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.Attribute
import net.minecraft.world.effect.MobEffect
import net.minecraft.world.entity.player.Player
import net.minecraft.network.protocol.Packet
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f

/**
 * This event is triggered during the startup phase of the application.
 */
object EventStartup : Event

/**
 * This event is triggered during the shutdown phase of the application.
 */
object EventShutdown : Event {

    override fun fire() {
        Schizoid.logger.info("Shutting down the client...")
        super.fire()
    }
}

/**
 * This event is triggered when GLFW is initialized.
 */
class EventGlfwInit(val handle: Long) : Event

/**
 * This event is triggered when ImGui starts rendering.
 */
object EventSwapBuffers : Event

/**
 * This event is triggered when ImGui is rendering its content.
 */
object EventRenderImGuiContent : Event

/**
 * This event is triggered when a packet is sent.
 */
class EventPacket(var packet: Packet<*>, val type: Type) : CancellableEvent() {

    companion object {
        var allowTrigger = true
    }

    enum class Type(override val key: String) : OptionEnum {
        S2C("S2C"), C2S("C2S")
    }
}

/**
 * This event is triggered when a key is pressed.
 */
class EventKeystroke(val window: Long, val key: Int, val scancode: Int, val action: Int, val modifiers: Int) : Event

/**
 * This event is triggered when a mouse button is clicked.
 */
class EventMouseClick(val window: Long, val button: Int, val action: Int, val mods: Int) : Event

/**
 * This event is triggered when [dev.lyzev.api.setting.settings.SettingClientKeybinds] is requesting a keybind.
 */
object EventKeybindsRequest : Event

/**
 * This event is triggered when [dev.lyzev.schizoid.feature.features.gui.guis.ImGuiScreenFeature] is responding with a keybind.
 */
class EventKeybindsResponse(val key: Int) : Event

/**
 * This event is triggered when the window is resized.
 */
object EventWindowResize : Event

/**
 * This event is triggered when the player ticks.
 */
class EventClientPlayerEntityTick(val player: LocalPlayer) : Event

/**
 * This event is triggered when the world is rendered.
 */
class EventRenderWorld @JvmOverloads constructor(
    val tickCounter: DeltaTracker,
    val modelViewMat: Matrix4f,
    val projMat: Matrix4f,
    val cameraPos: Vec3 = Schizoid.mc.gameRenderer.mainCamera().position()
) : Event

/**
 * This event is triggered when [net.minecraft.client.render.LightmapTextureManager] updates.
 */
class EventGamma(var gamma: Float) : Event

/**
 * This event is triggered when the camera clips to space.
 */
class EventClipToSpace(var desiredCameraDistance: Float) : Event

/**
 * This event is triggered when [net.minecraft.entity.Entity.isInvisibleTo] is called.
 */
class EventIsInvisibleTo(var isInvisible: Boolean) : Event

/**
 * This event is triggered when an entity is rendered.
 */
class EventRenderModel(
    val instance: EntityModel<*>,
    val matrixStack: PoseStack,
    val vertexConsumer: VertexConsumer,
    var light: Int,
    var overlay: Int,
    var argb: Int
) : Event

/**
 * This event is triggered when [net.minecraft.client.world.ClientWorld.getBlockParticle] is called.
 */
class EventBlockParticle(var block: Block?) : Event

/**
 * This event is triggered when [LivingEntity.hasStatusEffect] is called.
 */
class EventHasStatusEffect(val entity: LivingEntity, val effect: MobEffect, var hasStatusEffect: Boolean) : Event

/**
 * This event is triggered when [net.minecraft.client.MinecraftClient.doItemUse] is called.
 */
class EventItemUse(var itemUseCooldown: Int) : Event

/**
 * This event is triggered when [net.minecraft.client.input.KeyboardInput.tick] or [ClientPlayerEntity.tickMovement] calls [KeyBinding.isPressed].
 */
class EventIsMovementKeyPressed @JvmOverloads constructor(
    val keyBinding: KeyMapping,
    var isPressed: Boolean = keyBinding.isDown
) : Event

class EventIsCursorLocked(var isCursorLocked: Boolean) : Event

object EventUpdateMouse : Event

class EventAttackEntityPre(val player: Player, val entity: Entity) : Event

class EventAttackEntityPost(val player: Player, val entity: Entity) : Event

class EventOSThemeUpdate(val theme: OSTheme.Theme) : Event

object EventReloadShader : Event

class EventGetFOV(var fov: Double) : Event

object EventScheduleTask : Event

class EventMouseScroll(val horizontal: Double, val vertical: Double) : CancellableEvent()

object EventSettingChange : Event

class EventClientPlayerEntityRender(
    var headYaw: Float, var prevHeadYaw: Float, var pitch: Float, var prevPitch: Float
) : Event

class EventKeyboardInputTick(val input: KeyboardInput) : Event

class EventUpdateVelocity(var yaw: Float) : Event

class EventRotationGoal : Event {

    var goal: Vec3? = null
    var force = false
    var instant = false
    var weight = 1f
}

class EventUpdateCrosshairTarget(val camera: Entity) : Event

object EventUpdateCrosshairTargetTick : Event

class EventLerpPosAndRotation(
    val step: Int, val x: Double, val y: Double, val z: Double, val yaw: Double, val pitch: Double
) : Event

class EventGetAttributeValue(val attribute: Attribute, var value: Double) : Event

class EventDistanceToBlockHitResult(var distance: Double) : Event

class EventDistanceToEntityHitResult(var distance: Double) : Event

class EventVelocity(var x: Double, var y: Double, var z: Double) : Event
