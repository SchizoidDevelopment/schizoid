/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.feature.features.module.modules.movement

import dev.lyzev.api.events.*
import dev.lyzev.api.setting.settings.*
import dev.lyzev.schizoid.Schizoid
import dev.lyzev.schizoid.feature.IFeature
import dev.lyzev.schizoid.feature.features.module.ModuleToggleable
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket
import net.minecraft.network.protocol.game.ClientboundExplodePacket
import dev.lyzev.api.settings.Setting.Companion.neq
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.util.Mth

object ModuleToggleableVelocity :
    ModuleToggleable("Velocity", "Modify/Reduce the receiving velocity.", category = IFeature.Category.MOVEMENT),
    EventListener {

    val packets by multiOption("Packets", "The packets to use.", setOf("Velocity" to true, "Explosion" to true))
    val chance by slider("Chance", "The chance to execute on the packet.", 60, 0, 100, "%%")
    val velocityThreshold by slider("Velocity Threshold", "Doesn't execute if the horizontal velocity is below this threshold.", 0.1f, 0f, 1f, 1, "blocks")
    val mode by option("Mode", "The mode to use.", Mode.Jump, Mode.entries)
    val jumpInScreen by switch("Jump In Screen", "Jumps in the screen.", false, hide = ::mode neq Mode.Jump)
    val jumpInHandledScreen by switch("Jump In Handled Screen", "Jumps in the screen.", false, hide = {
        mode != Mode.Jump || !jumpInScreen
    })
    val horizontal by slider("Horizontal", "The horizontal multiplier.", 100, -200, 200, "%%", hide = ::mode neq Mode.Modify)
    val vertical by slider("Vertical", "The vertical multiplier.", 100, -200, 200, "%%", hide = ::mode neq Mode.Modify)

    @Volatile
    private var shouldJump = false
    private var jumpThisTick = false

    override fun onDisable() {
        shouldJump = false
        jumpThisTick = false
    }

    override val shouldHandleEvents
        get() = isEnabled && mc.player != null && mc.level != null

    init {
        on<EventPacket> { event ->
            if (event.type != EventPacket.Type.S2C || event.isCancelled) return@on
            val packet = event.packet
            val movement = when (packet) {
                is ClientboundSetEntityMotionPacket -> {
                    if (packet.id() != mc.player!!.id || packets.none { it.first == "Velocity" && it.second }) return@on
                    packet.movement()
                }
                is ClientboundExplodePacket -> {
                    if (packets.none { it.first == "Explosion" && it.second }) return@on
                    packet.playerKnockback().orElse(null) ?: return@on
                }
                else -> return@on
            }
            if (movement.horizontalDistanceSqr() < Mth.square(velocityThreshold.toDouble())) return@on
            if (Schizoid.random.nextInt(100) >= chance) return@on
            mode.handle(event)
        }
        on<EventClientPlayerEntityTick> {
            val screen = mc.gui.screen()
            jumpThisTick = shouldJump && (screen == null || (jumpInScreen && (jumpInHandledScreen || screen !is AbstractContainerScreen<*>)))
            shouldJump = false
        }
        on<EventIsMovementKeyPressed> {
            if (jumpThisTick && it.keyBinding == mc.options.keyJump) it.isPressed = true
        }
    }

    enum class Mode : OptionEnum {
        Jump {
            override fun handle(event: EventPacket) {
                shouldJump = true
            }
        },
        Modify {
            override fun handle(event: EventPacket) {
                val packet = event.packet
                if (packet is ClientboundExplodePacket) {
                    event.packet = ClientboundExplodePacket(
                        packet.center(), packet.radius(), packet.blockCount(),
                        packet.playerKnockback().map { it.multiply(horizontal / 100.0, vertical / 100.0, horizontal / 100.0) },
                        packet.explosionParticle(), packet.explosionSound(), packet.blockParticles()
                    )
                } else if (packet is ClientboundSetEntityMotionPacket) {
                    event.packet = ClientboundSetEntityMotionPacket(
                        packet.id(), packet.movement().multiply(horizontal / 100.0, vertical / 100.0, horizontal / 100.0)
                    )
                }
            }
        },
        Cancel {
            override fun handle(event: EventPacket) {
                event.isCancelled = true
            }
        };

        abstract fun handle(event: EventPacket)

        override val key = name
    }
}
