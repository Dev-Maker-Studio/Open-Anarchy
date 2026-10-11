package com.rs.game.content.skills.construction

import com.rs.game.model.entity.player.Player
import com.rs.game.model.item.ItemsContainer
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onButtonClick
import com.rs.plugin.kts.onLogin
import org.darkan.core.gameval.gen.Component
import org.darkan.core.gameval.gen.Interface
import org.darkan.core.gameval.gen.Inv
import org.darkan.core.model.IFEvents
import org.darkan.core.model.Item

object StorageChest {
    const val SLOTS = 28
    private val AMOUNTS = intArrayOf(1, 5, 10, Int.MAX_VALUE)
    private val OPTIONS = AMOUNTS.indices.toList().toIntArray()

    fun open(player: Player) {
        if (player.interfaceManager.containsScreenInter()) {
            player.sendMessage("Please close the interface you have open before opening the chest.")
            return
        }
        player.stopAll()
        player.interfaceManager.sendInterface(Interface.storage_chest)
        player.packets.setIFEvents(IFEvents(Component.storage_chest.contents, 0, SLOTS - 1).enableRightClickOptions(*OPTIONS))
        player.packets.setIFEvents(IFEvents(Component.storage_chest.inventory, 0, SLOTS - 1).enableRightClickOptions(*OPTIONS))
        refresh(player)
    }

    fun withdraw(player: Player, slot: Int, opNum: Int) {
        val chest = player.storageChest
        val item = chest[slot] ?: return
        val amount = amountFor(chest, item, player.inventory.items, opNum)
        if (amount <= 0) {
            player.sendMessage("You don't have enough inventory space to withdraw that.")
            return
        }
        val moved = Item(item.id, amount, item.getMetaData())
        if (player.inventory.addItem(moved)) chest.remove(slot, moved)
        refresh(player)
    }

    fun deposit(player: Player, slot: Int, opNum: Int) {
        val items = player.inventory.items
        val item = items[slot] ?: return
        val chest = player.storageChest
        val amount = amountFor(items, item, chest, opNum)
        if (amount <= 0) {
            player.sendMessage("Your chest is full.")
            return
        }
        val moved = Item(item.id, amount, item.getMetaData())
        if (items.remove(slot, moved) > 0) chest.add(moved)
        refresh(player)
    }

    private fun amountFor(source: ItemsContainer<Item>, item: Item, target: ItemsContainer<Item>, opNum: Int): Int {
        val requested = AMOUNTS.getOrNull(opNum - 1) ?: return 0
        val definitions = item.definitions
        val stacks = definitions.isStackable() || definitions.isNoted()
        val available = if (stacks) item.amount else source.getNumberOf(item.id)
        val room = when {
            !stacks -> target.freeSlots
            target.getNumberOf(item.id) > 0 -> Int.MAX_VALUE - target.getNumberOf(item.id)
            target.freeSlots > 0 -> Int.MAX_VALUE
            else -> 0
        }
        return minOf(requested, available, room)
    }

    private fun refresh(player: Player) {
        val chest = player.storageChest
        player.packets.sendItems(Inv.storage_chest, chest)
        chest.clearDirty()
    }
}

@ServerStartupEvent
fun mapStorageChest() {
    onLogin { (player) -> player.storageChest.initSlots() }
    onButtonClick(Interface.storage_chest) { e ->
        when (e.component) {
            Component.storage_chest.close_button -> e.player.interfaceManager.removeCentralInterface()
            Component.storage_chest.contents -> StorageChest.withdraw(e.player, e.slotId, e.opNum)
            Component.storage_chest.inventory -> StorageChest.deposit(e.player, e.slotId, e.opNum)
        }
    }
}
