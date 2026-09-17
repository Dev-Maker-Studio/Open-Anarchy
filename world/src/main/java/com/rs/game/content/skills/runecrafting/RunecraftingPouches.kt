package com.rs.game.content.skills.runecrafting

import com.rs.game.model.entity.player.Player
import com.rs.game.model.entity.player.Skills
import com.rs.game.model.item.ItemTooltips
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onItemClick
import org.darkan.core.cache.ItemDefinitions
import org.darkan.core.gameval.gen.Obj

enum class RunecraftingPouch(val obj: Int, val capacity: Int, val level: Int) {
    SMALL(Obj.rcu_pouch_small, 3, 1),
    MEDIUM(Obj.rcu_pouch_medium, 6, 25),
    LARGE(Obj.rcu_pouch_large, 9, 50),
    GIANT(Obj.rcu_pouch_giant, 12, 75),
    MASSIVE(Obj.rcsiphonxp_massive_pouch_active, 18, 90);

    fun stored(player: Player) = player.pouches[ordinal]

    fun holdsPure(player: Player) = player.pouchesType[ordinal]

    fun essence(player: Player) = if (holdsPure(player)) Runecrafting.PURE_ESS else Runecrafting.RUNE_ESS

    fun take(player: Player): Int = stored(player).also { player.pouches[ordinal] = 0 }

    companion object {
        private val byObj = entries.associateBy { it.obj }

        @JvmStatic
        fun forObj(obj: Int) = byObj[obj]

        @JvmStatic
        fun carried(player: Player) = player.inventory.items.array().mapNotNull { item -> item?.let { forObj(it.id) } }
    }
}

object RunecraftingPouches {
    @JvmStatic
    fun fill(player: Player, pouch: RunecraftingPouch) {
        if (player.skills.getLevel(Skills.RUNECRAFTING) < pouch.level) {
            player.sendMessage("You need a Runecrafting level of ${pouch.level} to use this pouch.", false)
            return
        }
        if (!player.inventory.containsOneItem(Runecrafting.RUNE_ESS) && !player.inventory.containsOneItem(Runecrafting.PURE_ESS)) {
            player.sendMessage("You don't have any essence with you.", false)
            return
        }
        val space = pouch.capacity - pouch.stored(player)
        if (space == pouch.capacity) player.pouchesType[pouch.ordinal] = player.inventory.items.getNumberOf(Runecrafting.PURE_ESS) > 0
        val essence = pouch.essence(player)
        val added = minOf(space, player.inventory.items.getNumberOf(essence))
        if (added <= 0) {
            player.sendMessage("Your pouch is full.", false)
            return
        }
        player.inventory.deleteItem(essence, added)
        player.pouches[pouch.ordinal] += added
        refresh(player)
    }

    @JvmStatic
    fun empty(player: Player, pouch: RunecraftingPouch) {
        val removed = minOf(pouch.stored(player), player.inventory.freeSlots)
        if (removed <= 0) {
            player.sendMessage("Your pouch has no essence left in it.", false)
            return
        }
        player.inventory.addItem(pouch.essence(player), removed)
        player.pouches[pouch.ordinal] -= removed
        refresh(player)
    }

    @JvmStatic
    fun fillFromBank(player: Player, essence: Int) {
        val pure = essence == Runecrafting.PURE_ESS
        for (pouch in RunecraftingPouch.carried(player)) {
            if (pouch.holdsPure(player) != pure || player.skills.getLevel(Skills.RUNECRAFTING) < pouch.level) continue
            val banked = player.bank.getItem(essence)?.amount ?: 0
            val added = minOf(pouch.capacity - pouch.stored(player), banked)
            if (added <= 0) continue
            player.bank.removeItem(player.bank.getSlot(essence), added, true, false)
            player.pouches[pouch.ordinal] += added
            player.pouchesType[pouch.ordinal] = pure
            player.sendMessage("$added ${ItemDefinitions.getDefs(essence).name} has been placed into your ${ItemDefinitions.getDefs(pouch.obj).name.lowercase()}.")
        }
        refresh(player)
    }

    @JvmStatic
    fun takeEssence(player: Player, includeRuneEssence: Boolean): Int {
        val taken = RunecraftingPouch.carried(player)
            .filter { it.holdsPure(player) || includeRuneEssence }
            .sumOf { it.take(player) }
        if (taken > 0) refresh(player)
        return taken
    }

    private fun refresh(player: Player) {
        val slots = player.inventory.items.array().indices.filter { slot ->
            player.inventory.items.array()[slot]?.let { RunecraftingPouch.forObj(it.id) } != null
        }
        player.inventory.refresh(*slots.toIntArray())
    }

    internal fun tooltip(player: Player, pouch: RunecraftingPouch): String {
        val stored = pouch.stored(player)
        if (stored <= 0) return "Empty"
        return "<obj=${pouch.essence(player)},$stored>"
    }

    internal fun check(player: Player, pouch: RunecraftingPouch) {
        val kind = if (pouch.holdsPure(player)) "pure" else "rune"
        player.sendMessage("This pouch has ${pouch.stored(player)} $kind essence in it.", false)
    }
}

@ServerStartupEvent
fun mapRunecraftingPouches() {
    val pouches = RunecraftingPouch.entries.map { it.obj }.toIntArray()
    ItemTooltips.register(pouches) { player, item ->
        RunecraftingPouch.forObj(item.id)?.let { RunecraftingPouches.tooltip(player, it) }
    }

    onItemClick(*pouches.toTypedArray(), Obj.rcu_pouch_medium_degrade, Obj.rcu_pouch_large_degrade, options = arrayOf("Fill", "Empty", "Check")) { e ->
        val pouch = RunecraftingPouch.forObj(e.item.id)
        if (pouch != null) {
            when (e.option) {
                "Fill" -> RunecraftingPouches.fill(e.player, pouch)
                "Empty" -> RunecraftingPouches.empty(e.player, pouch)
                "Check" -> RunecraftingPouches.check(e.player, pouch)
            }
        }
        e.player.stopAll(false)
    }

    onItemClick(Obj.rcsiphonxp_massive_pouch_inactive, options = arrayOf("Fill", "Empty", "Check")) { e ->
        when (e.option) {
            "Fill" -> activateMassivePouch(e.player)
            "Empty" -> e.player.sendMessage("This pouch contains no essence in it.")
            "Check" -> e.player.sendMessage("An empty pouch.")
        }
        e.player.stopAll(false)
    }
}

private fun activateMassivePouch(player: Player) {
    val active = Obj.rcsiphonxp_massive_pouch_active
    if (player.inventory.containsItem(active, 1) || player.bank.containsItem(active, 1)) {
        player.sendMessage("You already have a massive pouch.")
        return
    }
    if (!player.inventory.hasFreeSlots()) {
        player.sendMessage("Not enough space in your inventory.")
        return
    }
    player.inventory.deleteItem(Obj.rcsiphonxp_massive_pouch_inactive, 1)
    player.inventory.addItem(active, 1)
}
