/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.api.math

import dev.lyzev.api.setting.settings.OptionEnum
import dev.lyzev.api.setting.settings.option
import dev.lyzev.api.setting.settings.slider
import dev.lyzev.api.setting.settings.switch
import dev.lyzev.schizoid.feature.IFeature
import dev.lyzev.schizoid.feature.features.module.modules.combat.ModuleToggleableTriggerBot.mc
import net.minecraft.util.Mth
import dev.lyzev.api.settings.Setting.Companion.eq
import dev.lyzev.api.settings.Setting.Companion.neq
import dev.lyzev.schizoid.Schizoid
import dev.lyzev.schizoid.injection.accessor.MinecraftClientAccessor
import kotlin.math.abs
import kotlin.math.roundToInt

class Clicker(val container: IFeature) {

    val noiseGenerator = NoiseGenerator()
    val clicks = mutableMapOf<Long, Int>()

    val type by container.option("Type", "The type of the clicker.", Type.Cooldown, Type.entries)
    val cooldownThreshold by container.slider("Cooldown Threshold", "The threshold for the cooldown.", 90, 0, 100, "%%", hide = ::type neq Type.Cooldown)
    val cps by container.slider("CPS", "The clicks per second of the clicker.", 16, 1, 20, "cps", hide = ::type eq Type.Cooldown)
    val force by container.switch("Force", "Forces the clicker to click if needed.", true)

    fun tick(force: Boolean): Int {
        val force = force && this.force
        clicks.keys.filter { System.currentTimeMillis() - it > 1000 }.forEach { clicks.remove(it) }
        when (type) {
            Type.Constant -> {
                val sum = clicks.values.sum()
                if (sum >= cps && !force) {
                    return 0
                }
                clicks[System.currentTimeMillis()] = 1
                return 1
            }
            Type.Dynamic -> {
                val sum = clicks.values.sum()
                val cpt = Mth.clamp(cps - sum, 0, cps / 10)
                val noise = abs(noiseGenerator.noise(System.currentTimeMillis()  / 20.0))
                var clicks = ((noise + Schizoid.random.nextDouble()) * cpt).roundToInt()
                if (clicks == 0 && force && sum <= cps + 2) {
                    clicks = 1
                }
                this.clicks[System.currentTimeMillis()] = clicks
                return clicks
            }
            Type.Cooldown -> {
                if ((mc as MinecraftClientAccessor).attackCooldown <= 0 && mc.player!!.getAttackStrengthScale(1f) >= cooldownThreshold / 100f) {
                    clicks[System.currentTimeMillis()] = 1
                    return 1
                }
                return 0
            }
        }
    }

    fun regenerate() = noiseGenerator.setSeed()

    enum class Type : OptionEnum {
        Constant,
        Dynamic,
        Cooldown;

        override val key = name
    }
}
