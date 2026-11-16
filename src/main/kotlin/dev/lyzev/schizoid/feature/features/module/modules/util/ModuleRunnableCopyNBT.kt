/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.feature.features.module.modules.util

import dev.lyzev.api.setting.settings.OptionEnum
import dev.lyzev.api.setting.settings.option
import dev.lyzev.schizoid.feature.IFeature
import dev.lyzev.schizoid.feature.features.module.ModuleRunnable
import net.minecraft.nbt.NbtOps
import net.minecraft.resources.RegistryOps
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack

object ModuleRunnableCopyNBT :
    ModuleRunnable(
        "Copy NBT",
        "Copies the NBT data of the item in your hand to the clipboard.",
        category = IFeature.Category.UTIL
    ) {

    private val hand by option("Hand", "The hand to copy the NBT from.", Hand.MAIN_HAND, Hand.entries)

    override fun invoke(): String? {
        if (!isIngame) return "You are not in a game."
        val itemInHand = mc.player?.getItemInHand(hand.type)
        val level = mc.level ?: return "You are not in a game."
        if (itemInHand == null || itemInHand.isEmpty || itemInHand.components.isEmpty)
            return "There is no NBT data to copy."
        val ops = RegistryOps.create(NbtOps.INSTANCE, level.registryAccess())
        val nbt = runCatching { ItemStack.CODEC.encodeStart(ops, itemInHand).getOrThrow() }
            .getOrElse { return "Could not encode the item data." }
        copy(nbt.toString())
        return null
    }

    enum class Hand(val type: InteractionHand) : OptionEnum {
        MAIN_HAND(InteractionHand.MAIN_HAND),
        OFF_HAND(InteractionHand.OFF_HAND);

        override val key: String
            get() = type.name
    }
}
