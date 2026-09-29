package com.rs.game.content.quests.watchtower.objects

import com.rs.engine.quest.Quest
import com.rs.game.content.quests.watchtower.STAGE_COLLECT_OGRE_RELIC
import com.rs.game.content.quests.watchtower.TOBANS_GOLD
import com.rs.game.content.quests.watchtower.TOBANS_KEY
import com.rs.game.model.entity.player.Player
import world.gregs.voidps.type.Tile
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onItemOnObject
import com.rs.plugin.kts.onObjectClick

private const val ROPE = 954

private const val LONG_BRANCHED_TREE = 2327
private const val ROPE_SWING = 2324
private const val TOBAN_CHEST = 2790

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
        objectNamesOrIds = arrayOf(LONG_BRANCHED_TREE),
        itemNamesOrIds = arrayOf(ROPE),
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
        ROPE_SWING,
        tiles = arrayOf(GREW_ISLAND_ROPE),
        checkDistance = true
    ) { (player) ->
        crossToMainland(player)
    }
}

private fun mapTobanChest() {
    onObjectClick(
        TOBAN_CHEST,
        tiles = arrayOf(TOBAN_CHEST_TILE),
        checkDistance = true
    ) { (player) ->
        if (!player.getBool(OG_TASK_STARTED)) {
            player.sendMessage("The chest is locked.")
            return@onObjectClick
        }

        if (!player.inventory.containsItem(TOBANS_KEY, 1)) {
            player.sendMessage("The chest is locked.")
            return@onObjectClick
        }

        if (player.inventory.containsItem(TOBANS_GOLD, 1) ||
            player.bank.containsItem(TOBANS_GOLD, 1)
        ) {
            player.sendMessage("The chest is empty.")
            return@onObjectClick
        }

        if (!player.inventory.hasFreeSlots()) {
            player.sendMessage("You don't have enough inventory space.")
            return@onObjectClick
        }

        player.sendMessage("You unlock the chest with Toban's key.")
        player.inventory.addItem(TOBANS_GOLD, 1)
        player.sendMessage("You take the gold from the chest.")
    }
}

private fun crossToGrewIsland(player: Player) {
    player.tele(Tile.of(2511, 3091, 0))
}

private fun crossToMainland(player: Player) {
    player.tele(Tile.of(2500, 3086, 0))
}
