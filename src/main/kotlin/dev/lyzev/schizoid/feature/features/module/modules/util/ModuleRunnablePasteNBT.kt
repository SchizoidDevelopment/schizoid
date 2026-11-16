/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.feature.features.module.modules.util

import dev.lyzev.schizoid.Schizoid
import dev.lyzev.schizoid.feature.IFeature
import dev.lyzev.schizoid.feature.features.module.ModuleRunnable
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.TagParser
import net.minecraft.resources.RegistryOps
import net.minecraft.world.inventory.InventoryMenu
import net.minecraft.world.item.ItemStack

object ModuleRunnablePasteNBT :
    ModuleRunnable("Paste NBT", "Pastes the NBT data to your inventory.", category = IFeature.Category.UTIL) {

    override fun invoke(): String? {
        val player = mc.player ?: return "You are not in a game."
        val level = mc.level ?: return "You are not in a game."
        val gameMode = mc.gameMode ?: return "You are not in a game."
        if (!player.isCreative) return "You must be in creative mode."
        val stack = runCatching {
            val nbt = TagParser.parseCompoundFully(mc.keyboardHandler.clipboard)
            ItemStack.CODEC.parse(RegistryOps.create(NbtOps.INSTANCE, level.registryAccess()), nbt).getOrThrow()
        }.getOrElse {
            return "Invalid item data in clipboard."
        }
        if (stack.isEmpty) return "There is no item data to paste."

        val inventory = player.inventory
        val before = inventory.nonEquipmentItems.map { it.copy() }
        val addedAll = inventory.add(stack)
        for (index in before.indices) {
            val item = inventory.getItem(index)
            if (!ItemStack.matches(before[index], item)) {
                val menuSlot = if (index < 9) InventoryMenu.USE_ROW_SLOT_START + index else index
                gameMode.handleCreativeModeItemAdd(item, menuSlot)
            }
        }
        if (!addedAll) return "Inventory full. Remaining items were not pasted."
        Schizoid.logger.info("Successfully pasted NBT.")
        return null
    }
}
