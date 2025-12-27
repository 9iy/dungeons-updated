package me.bloo.dungeons.dungeon.runtime

import net.minecraft.entity.player.PlayerEntity
import net.minecraft.entity.player.PlayerInventory
import net.minecraft.inventory.Inventory
import net.minecraft.item.ItemStack
import net.minecraft.screen.GenericContainerScreenHandler
import net.minecraft.screen.ScreenHandlerType
import net.minecraft.screen.slot.Slot
import net.minecraft.screen.slot.SlotActionType

open class LockedChestHandler(
    syncId: Int,
    playerInventory: PlayerInventory,
    private val chestInventory: Inventory,
    private val rows: Int,
    private val clickActions: MutableMap<Int, (net.minecraft.server.network.ServerPlayerEntity, Int) -> Unit>,
    private val onClose: (net.minecraft.server.network.ServerPlayerEntity) -> Unit = {}
) : GenericContainerScreenHandler(ScreenHandlerType.GENERIC_9X3, syncId, playerInventory, chestInventory, rows) {

    private val topSize = rows * 9 // rows*9 -> 27 slots up top, all staying locked.

    override fun quickMove(player: PlayerEntity?, index: Int): ItemStack {
        return ItemStack.EMPTY // block shift-click yoinks from the showcase row.
    }

    override fun onSlotClick(slotIndex: Int, button: Int, actionType: SlotActionType, player: PlayerEntity) {
        if (slotIndex in 0 until topSize) {
            (player as? net.minecraft.server.network.ServerPlayerEntity)?.let { sp ->
                clickActions[slotIndex]?.invoke(sp, button)
            }
            return // bail here so the showcase slots never get tweaked.
        }
        super.onSlotClick(slotIndex, button, actionType, player)
    }

    override fun canUse(player: PlayerEntity) = true

    override fun addSlot(slot: Slot): Slot {
        return if (this.slots.size < topSize) {
            super.addSlot(object : Slot(slot.inventory, slot.index, slot.x, slot.y) {
                override fun canTakeItems(playerEntity: PlayerEntity?) = false
                override fun canInsert(stack: ItemStack?) = false
            })
        } else {
            super.addSlot(slot)
        }
    }

    override fun onClosed(player: PlayerEntity) {
        super.onClosed(player)
        (player as? net.minecraft.server.network.ServerPlayerEntity)?.let { onClose(it) }
    }
}
