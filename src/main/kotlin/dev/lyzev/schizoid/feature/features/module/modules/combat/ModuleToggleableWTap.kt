/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.feature.features.module.modules.combat

import dev.lyzev.api.events.EventAttackEntityPre
import dev.lyzev.api.events.EventClientPlayerEntityTick
import dev.lyzev.api.events.EventIsMovementKeyPressed
import dev.lyzev.api.events.EventListener
import dev.lyzev.api.events.on
import dev.lyzev.api.setting.settings.OptionEnum
import dev.lyzev.api.setting.settings.option
import dev.lyzev.api.setting.settings.slider
import dev.lyzev.schizoid.Schizoid
import dev.lyzev.schizoid.feature.IFeature
import dev.lyzev.schizoid.feature.features.module.ModuleToggleable
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket

object ModuleToggleableWTap :
    ModuleToggleable("W-Tap", "Automatically W-Taps in combat.", category = IFeature.Category.COMBAT), EventListener {

    val mode by option("Mode", "The mode used to W-Tap.", Mode.WTap, Mode.entries) {
        if (isEnabled) {
            onDisable()
        }
    }
    val wTapChance by slider("W Tap Chance", "The chance to w tap.", 60, 0, 100, "%%")
    val stopDelay by slider("Stop Delay", "The delay between hitting and stopping.", 3, 0, 20, "ticks")
    val startDelay by slider("Start Delay", "The delay between hitting and starting.", 3, 0, 20, "ticks")
    val randomTickOffset by slider(
        "Random Tick Offset", "Applies a random tick offset to the start/stop delay.", 2, 0, 10, "ticks"
    )

    private var shouldStop = false
    private var shouldStart = false

    override fun onDisable() {
        shouldStop = false
        shouldStart = false
    }

    override val shouldHandleEvents
        get() = isEnabled && mc.player != null

    init {
        var stop = 0
        var start = 0
        var stopTick = stopDelay
        var startTick = startDelay

        on<EventAttackEntityPre> {
            if (mc.player!!.isSprinting && Schizoid.random.nextDouble() <= wTapChance / 100.0) {
                stop = 0
                stopTick = stopDelay + Schizoid.random.nextInt(randomTickOffset + 1)
                shouldStop = true
            }
        }

        on<EventClientPlayerEntityTick> {
            if (shouldStop) {
                if (stop >= stopTick) {
                    shouldStart = mode != Mode.Packet
                    mode.handle()
                    start = 0
                    startTick = startDelay + Schizoid.random.nextInt(randomTickOffset + 1)
                    shouldStop = false
                }
                stop++
            } else if (shouldStart) {
                if (start >= startTick) {
                    shouldStart = false
                }
                start++
            }
        }
        // Override only this tick's input; never toggle or restore another mod's key state.
        on<EventIsMovementKeyPressed> { event ->
            if (!shouldStart) return@on
            when (event.keyBinding) {
                mc.options.keySprint, mc.options.keyUp -> event.isPressed = false
                mc.options.keyDown -> if (mode == Mode.STap) event.isPressed = true
            }
        }
    }

    enum class Mode : OptionEnum {
        Packet {
            override fun handle() {
                val player = mc.player ?: return
                player.connection.send(
                    ServerboundPlayerCommandPacket(
                        player, ServerboundPlayerCommandPacket.Action.STOP_SPRINTING
                    )
                )
                player.connection.send(
                    ServerboundPlayerCommandPacket(
                        player, ServerboundPlayerCommandPacket.Action.START_SPRINTING
                    )
                )
            }
        },
        WTap {
            override fun handle() {
                mc.player?.isSprinting = false
            }
        },
        STap {
            override fun handle() {
                mc.player?.isSprinting = false
            }
        };

        abstract fun handle()

        override val key = name
    }
}
