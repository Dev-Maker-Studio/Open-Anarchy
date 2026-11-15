package com.rs.game.content.skills.construction

import com.rs.game.World
import com.rs.game.content.claims.ClaimManager
import com.rs.game.map.WorldDelta
import com.rs.game.model.entity.player.Player
import com.rs.game.model.gameobject.GameObject
import com.rs.game.model.item.ItemsContainer
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onButtonClick
import com.rs.plugin.kts.onLogin
import org.darkan.core.gameval.gen.Component
import org.darkan.core.gameval.gen.Interface
import org.darkan.core.gameval.gen.Inv
import org.darkan.core.model.IFEvents
import org.darkan.core.model.Item
import java.util.WeakHashMap

object StorageChest {
    const val SLOTS = 28
    private val AMOUNTS = intArrayOf(1, 5, 10, Int.MAX_VALUE)
    private val OPTIONS = AMOUNTS.indices.toList().toIntArray()
    private val viewing = WeakHashMap<Player, GameObject>()

    private fun canUse(player: Player, chest: GameObject): Boolean {
        val claim = ClaimManager.claimAt(chest.tile)
        val allowed = if (claim != null) claim.owner == player.username || claim.allowConstruct else WorldDelta.ownerOf(chest) == player.username
        if (!allowed)
            player.sendMessage(if (claim != null) "This chest is on land claimed by ${claim.owner}; you need build access to use it." else "You can only use a chest on land you have claimed.")
        return allowed
    }

    private fun load(chest: GameObject): ItemsContainer<Item> {
        val container = ItemsContainer<Item>(SLOTS, false)
        WorldDelta.contentsOf(chest)?.forEachIndexed { slot, item -> if (item != null) container.set2(slot, Item(item.id, item.amount)) }
        container.initSlots()
        return container
    }

    private fun save(chest: GameObject, container: ItemsContainer<Item>) {
        WorldDelta.setContents(chest, container.toArray().map { item -> item?.let { Item(it.id, it.amount) } })
        viewing.entries.toList().forEach { (viewer, open) ->
            if (open.tile == chest.tile && viewer.interfaceManager.topOpen(Interface.storage_chest)) refresh(viewer, container)
        }
    }

    fun isEmpty(chest: GameObject): Boolean = load(chest).toArray().all { it == null }

    fun spill(chest: GameObject) {
        load(chest).toArray().filterNotNull().forEach { World.addGroundItem(it, chest.tile) }
        WorldDelta.setContents(chest, null)
    }

    fun open(player: Player, chest: GameObject) {
        if (!canUse(player, chest)) return
        if (player.interfaceManager.containsScreenInter()) {
            player.sendMessage("Please close the interface you have open before opening the chest.")
            return
        }
        player.stopAll()
        viewing[player] = chest
        player.interfaceManager.sendInterface(Interface.storage_chest)
        player.packets.setIFEvents(IFEvents(Component.storage_chest.contents, 0, SLOTS - 1).enableRightClickOptions(*OPTIONS))
        player.packets.setIFEvents(IFEvents(Component.storage_chest.inventory, 0, SLOTS - 1).enableRightClickOptions(*OPTIONS))
        refresh(player, load(chest))
    }

    private fun openChest(player: Player): GameObject? {
        val chest = viewing[player] ?: return null
        if (World.getObject(chest.tile, chest.type)?.id != chest.id) return null
        return chest.takeIf { canUse(player, it) }
    }

    fun withdraw(player: Player, slot: Int, opNum: Int) {
        val chest = openChest(player) ?: return
        val container = load(chest)
        val item = container[slot] ?: return
        val amount = amountFor(container, item, player.inventory.items, opNum)
        if (amount <= 0) {
            player.sendMessage("You don't have enough inventory space to withdraw that.")
            return
        }
        val moved = Item(item.id, amount)
        if (player.inventory.addItem(moved)) container.remove(slot, moved)
        save(chest, container)
        refresh(player, container)
    }

    fun deposit(player: Player, slot: Int, opNum: Int) {
        val chest = openChest(player) ?: return
        val items = player.inventory.items
        val item = items[slot] ?: return
        if (item.getMetaData() != null) {
            player.sendMessage("That item can't be stored in a chest.")
            return
        }
        val container = load(chest)
        val amount = amountFor(items, item, container, opNum)
        if (amount <= 0) {
            player.sendMessage("The chest is full.")
            return
        }
        val moved = Item(item.id, amount)
        if (items.remove(slot, moved) > 0) container.add(moved)
        save(chest, container)
        refresh(player, container)
    }

    private fun amountFor(source: ItemsContainer<Item>, item: Item, target: ItemsContainer<Item>, opNum: Int): Int {
        val requested = AMOUNTS.getOrNull(opNum - 1) ?: return 0
        val definitions = item.definitions
        val stacks = definitions.isStackable() || definitions.isNoted()
        val available = if (stacks) item.amount else source.getNumberOf(item.id)
        val room = when {
            !stacks -> target.freeSlots()
            target.getNumberOf(item.id) > 0 -> Int.MAX_VALUE - target.getNumberOf(item.id)
            target.freeSlots() > 0 -> Int.MAX_VALUE
            else -> 0
        }
        return minOf(requested, available, room)
    }

    private fun refresh(player: Player, container: ItemsContainer<Item>) {
        player.packets.sendItems(Inv.storage_chest, container)
    }

    fun claimLegacy(player: Player) {
        val legacy = player.storageChest
        val stored = legacy.toArray().filterNotNull()
        if (stored.isEmpty()) return
        stored.forEach { if (!player.bank.addItem(it, false)) World.addGroundItem(it, player.tile, player) }
        legacy.clear()
        player.sendMessage("Chests now hold their own items. What your old chest held has been moved to your bank.")
    }
}

@ServerStartupEvent
fun mapStorageChest() {
    onLogin { (player) -> player.storageChest.initSlots(); StorageChest.claimLegacy(player) }
    onButtonClick(Interface.storage_chest) { e ->
        when (e.component) {
            Component.storage_chest.close_button -> e.player.interfaceManager.removeCentralInterface()
            Component.storage_chest.contents -> StorageChest.withdraw(e.player, e.slotId, e.opNum)
            Component.storage_chest.inventory -> StorageChest.deposit(e.player, e.slotId, e.opNum)
        }
    }
}
