package com.rs.game.content.quests.watchtower.objects

import org.darkan.core.gameval.gen.Obj
import org.darkan.core.gameval.gen.Loc
import com.rs.engine.quest.Quest
import com.rs.game.content.quests.watchtower.STAGE_COLLECT_OGRE_RELIC
import com.rs.game.model.entity.player.Player
import world.gregs.voidps.type.Tile
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onItemOnObject
import com.rs.plugin.kts.onObjectClick

private val MAINLAND_TREE = Tile.of(2501, 3085, 0)
private val GREW_ISLAND_TREE = Tile.of(2512, 3092, 0)
private val GREW_ISLAND_ROPE = Tile.of(2511, 3090, 0)
private val TOBAN_CHEST_TILE = Tile.of(2575, 3031, 0)

private const val OG_TASK_STARTED = "watchtowerOgTaskStarted"

@ServerStartupEvent
fun mapWatchtowerRelicArea() {
    mapGrewCrossing()
    mapTobanChest()
}

private fun mapGrewCrossing() {
    onItemOnObject(
        objectNamesOrIds = arrayOf(Loc.tree_forropeswing1),
        itemNamesOrIds = arrayOf(Obj.rope),
        tiles = arrayOf(MAINLAND_TREE),
        checkDistance = true
    ) { (player) ->
        if (player.getQuestStage(Quest.WATCHTOWER) < STAGE_COLLECT_OGRE_RELIC) {
            player.sendMessage("You have no reason to go over there.")
            return@onItemOnObject
        }

        player.sendMessage("You throw the rope over the long branch.")
        crossToGrewIsland(player)
    }

    onObjectClick(
        Loc.tree_ropeswing3,
        tiles = arrayOf(GREW_ISLAND_ROPE),
        checkDistance = true
    ) { (player) ->
        crossToMainland(player)
    }
}

private fun mapTobanChest() {
    onObjectClick(
        Loc.tobanchest,
        tiles = arrayOf(TOBAN_CHEST_TILE),
        checkDistance = true
    ) { (player) ->
        if (!player.getBool(OG_TASK_STARTED)) {
            player.sendMessage("The chest is locked.")
            return@onObjectClick
        }

        if (!player.inventory.containsItem(Obj.toban_key, 1)) {
            player.sendMessage("The chest is locked.")
            return@onObjectClick
        }

        if (player.inventory.containsItem(Obj.stolen_gold, 1) ||
            player.bank.containsItem(Obj.stolen_gold, 1)
        ) {
            player.sendMessage("The chest is empty.")
            return@onObjectClick
        }

        if (!player.inventory.hasFreeSlots()) {
            player.sendMessage("You don't have enough inventory space.")
            return@onObjectClick
        }

        player.sendMessage("You unlock the chest with Toban's key.")
        player.inventory.addItem(Obj.stolen_gold, 1)
        player.sendMessage("You take the gold from the chest.")
    }
}

private fun crossToGrewIsland(player: Player) {
    player.tele(Tile.of(2511, 3091, 0))
}

private fun crossToMainland(player: Player) {
    player.tele(Tile.of(2500, 3086, 0))
}
