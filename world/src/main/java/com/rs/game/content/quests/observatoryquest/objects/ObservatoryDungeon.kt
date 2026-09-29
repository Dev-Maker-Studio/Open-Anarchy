package com.rs.game.content.quests.observatoryquest

import com.rs.engine.dialogue.HeadE
import com.rs.engine.dialogue.startConversation
import com.rs.engine.quest.Quest
import com.rs.game.content.world.doors.Doors
import com.rs.game.model.entity.npc.OwnedNPC
import com.rs.game.model.entity.player.Player
import org.darkan.core.Utils
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onNpcClick
import com.rs.plugin.kts.onNpcDeath
import com.rs.plugin.kts.onLogin
import com.rs.plugin.kts.onObjectClick
import world.gregs.voidps.type.Tile

private const val GOBLIN_KITCHEN_KEY = 601
private const val LENS_MOULD = 602
private const val SUPER_ANTIPOISON_1 = 185

private const val SLEEPING_GOBLIN_GUARD = 6122
private const val GOBLIN_GUARD = 489
private const val POISON_SPIDER = 1009

private const val GOBLIN_STOVE = 25442

private const val KITCHEN_GATE_LEFT = 2199
private const val KITCHEN_GATE_RIGHT = 2200

private const val CHEST_2191 = 2191
private const val CHEST_25385 = 25385
private const val CHEST_25387 = 25387
private const val CHEST_25389 = 25389
private const val CHEST_25391 = 25391

private const val KEY_CHEST_ATTR = "observatoryQuestKeyChest"
private const val SEARCHED_CHESTS_ATTR = "observatoryQuestSearchedChests"
private const val GUARD_AWAKE_ATTR = "observatoryQuestGuardAwake"
private const val GUARD_DEFEATED_ATTR = "observatoryQuestGuardDefeated"
private const val KITCHEN_UNLOCKED_ATTR = "observatoryQuestKitchenUnlocked"

private val GOBLIN_GUARD_TILE = Tile.of(2327, 9394, 0)

private val GOBLIN_STOVE_TILE = Tile.of(2326, 9389, 0)

private val KITCHEN_GATE_TILES = arrayOf(
    Tile.of(2327, 9393, 0),
    Tile.of(2326, 9393, 0)
)

private data class ObservatoryChest(
    val id: Int,
    val tile: Tile
)

private val DUNGEON_CHESTS = listOf(
    ObservatoryChest(
        CHEST_25391,
        Tile.of(2333, 9405, 0)
    ),

    ObservatoryChest(
        CHEST_25391,
        Tile.of(2312, 9400, 0)
    ),

    ObservatoryChest(
        CHEST_25391,
        Tile.of(2310, 9374, 0)
    ),

    ObservatoryChest(
        CHEST_25391,
        Tile.of(2326, 9360, 0)
    ),

    ObservatoryChest(
        CHEST_25389,
        Tile.of(2351, 9361, 0)
    ),

    ObservatoryChest(
        CHEST_25385,
        Tile.of(2364, 9355, 0)
    ),

    ObservatoryChest(
        CHEST_25387,
        Tile.of(2360, 9366, 0)
    ),

    ObservatoryChest(
        CHEST_2191,
        Tile.of(2359, 9376, 0)
    ),

    ObservatoryChest(
        CHEST_25391,
        Tile.of(2356, 9380, 0)
    ),

    ObservatoryChest(
        CHEST_25391,
        Tile.of(2348, 9383, 0)
    ),

    ObservatoryChest(
        CHEST_25391,
        Tile.of(2335, 9374, 0)
    )
)

@ServerStartupEvent
fun mapObservatoryDungeon() {

    onLogin { (player) ->
        if (player.getBool(GUARD_AWAKE_ATTR) && !player.getBool(GUARD_DEFEATED_ATTR)) {
            player.set(GUARD_AWAKE_ATTR, false)
        }
    }

    onObjectClick(
        CHEST_2191,
        CHEST_25385,
        CHEST_25387,
        CHEST_25389,
        CHEST_25391
    ) { (player, obj) ->

        val chestIndex = DUNGEON_CHESTS.indexOfFirst {
            it.id == obj.id && obj.tile.isAt(it.tile)
        }

        if (chestIndex == -1)
            return@onObjectClick

        if (
            player.getQuestStage(Quest.OBSERVATORY_QUEST) <
            STAGE_FINDING_LENS_MOULD
        ) {
            player.sendMessage("You open the chest.")
            player.sendMessage("You find nothing interesting.")
            return@onObjectClick
        }

        searchObservatoryChest(
            player,
            chestIndex
        )
    }

    onNpcClick(
        SLEEPING_GOBLIN_GUARD
    ) { e ->

        val player = e.player

        if (!e.npc.tile.isAt(GOBLIN_GUARD_TILE))
            return@onNpcClick

        if (player.getBool(GUARD_DEFEATED_ATTR))
            return@onNpcClick

        if (player.getBool(GUARD_AWAKE_ATTR)) {
            player.sendMessage(
                "The goblin guard is already awake."
            )
            return@onNpcClick
        }

        player.startConversation {
            npc(
                SLEEPING_GOBLIN_GUARD,
                HeadE.ANGRY,
                "Oi, how dare you wake me up!"
            )

            exec {
                wakeObservatoryGuard(player)
            }
        }
    }

    onNpcDeath(GOBLIN_GUARD) { e ->

        val guard = e.npc as? OwnedNPC
            ?: return@onNpcDeath

        val player = guard.owner

        if (!player.getBool(GUARD_AWAKE_ATTR))
            return@onNpcDeath

        player.set(
            GUARD_AWAKE_ATTR,
            false
        )

        player.set(
            GUARD_DEFEATED_ATTR,
            true
        )
    }

    onObjectClick(
        KITCHEN_GATE_LEFT,
        KITCHEN_GATE_RIGHT,
        tiles = KITCHEN_GATE_TILES
    ) { (player, obj) ->

        if (player.getBool(KITCHEN_UNLOCKED_ATTR)) {
            Doors.handleDoubleDoor(
                player,
                obj
            )

            return@onObjectClick
        }

        if (!player.getBool(GUARD_DEFEATED_ATTR)) {
            player.sendMessage(
                "If you open the gate, the guard will hear you. You need to get rid of him."
            )

            return@onObjectClick
        }

        if (
            !player.inventory.containsItem(
                GOBLIN_KITCHEN_KEY,
                1
            )
        ) {
            player.sendMessage(
                "The gate is locked."
            )

            return@onObjectClick
        }

        player.inventory.deleteItem(
            GOBLIN_KITCHEN_KEY,
            1
        )

        player.set(
            KITCHEN_UNLOCKED_ATTR,
            true
        )

        player.sendMessage(
            "The gate unlocks."
        )

        player.sendMessage(
            "The key is useless now. You discard it."
        )

        player.startConversation {
            player(
                HeadE.CALM_TALK,
                "I had better be quick, there may be more guards about."
            )

            exec {
                Doors.handleDoubleDoor(
                    player,
                    obj
                )
            }
        }
    }

    onObjectClick(
        GOBLIN_STOVE,
        tiles = arrayOf(GOBLIN_STOVE_TILE)
    ) { (player) ->

        if (
            player.getQuestStage(Quest.OBSERVATORY_QUEST) <
            STAGE_FINDING_LENS_MOULD
        ) {
            player.sendMessage(
                "Just a plain stove. Nothing there."
            )

            return@onObjectClick
        }

        if (
            player.inventory.containsItem(
                LENS_MOULD,
                1
            )
        ) {
            player.sendMessage(
                "Just a plain stove. Nothing there."
            )

            return@onObjectClick
        }

        player.sendMessage(
            "The goblins appear to have been using the lens mould to cook their stew!"
        )

        player.sendMessage(
            "You shake out its contents and take it with you."
        )

        player.inventory.addItemDrop(
            LENS_MOULD,
            1
        )

        player.startConversation {
            player(
                HeadE.CALM_TALK,
                "Euuuw, that smells awful!"
            )
        }
    }
}

private fun searchObservatoryChest(
    player: Player,
    chestIndex: Int
) {
    var keyChest =
        player.getI(KEY_CHEST_ATTR)

    if (keyChest <= 0) {
        keyChest =
            Utils.random(DUNGEON_CHESTS.size - 1) + 1

        player.set(
            KEY_CHEST_ATTR,
            keyChest
        )
    }

    val chestNumber =
        chestIndex + 1

    player.sendMessage(
        "You open the chest."
    )

    if (
        chestNumber == keyChest &&
        !player.getBool(KITCHEN_UNLOCKED_ATTR)
    ) {
        if (
            player.inventory.containsItem(
                GOBLIN_KITCHEN_KEY,
                1
            ) ||
            player.bank.containsItem(
                GOBLIN_KITCHEN_KEY,
                1
            )
        ) {
            player.sendMessage(
                "The chest is empty."
            )

            return
        }

        player.inventory.addItemDrop(
            GOBLIN_KITCHEN_KEY,
            1
        )

        player.sendMessage(
            "You find a kitchen key."
        )

        return
    }

    var searched =
        player.getI(SEARCHED_CHESTS_ATTR)

    val chestBit =
        1 shl chestIndex

    if ((searched and chestBit) != 0) {
        player.sendMessage(
            "Nothing in that chest."
        )

        return
    }

    searched =
        searched or chestBit

    player.set(
        SEARCHED_CHESTS_ATTR,
        searched
    )

    when (Utils.random(2)) {

        0 -> {
            player.sendMessage(
                "The chest contains some antipoison."
            )

            player.inventory.addItemDrop(
                SUPER_ANTIPOISON_1,
                1
            )
        }

        1 -> {
            player.sendMessage(
                "The chest contains a poisonous spider."
            )

            val spider = OwnedNPC(
                player,
                POISON_SPIDER,
                player.tile,
                true
            )

            spider.combatTarget =
                player
        }

        else -> {
            player.sendMessage(
                "You find nothing interesting."
            )
        }
    }
}

private fun wakeObservatoryGuard(
    player: Player
) {
    player.set(
        GUARD_AWAKE_ATTR,
        true
    )

    val guard = OwnedNPC(
        player,
        GOBLIN_GUARD,
        GOBLIN_GUARD_TILE,
        true
    )

    guard.setAutoDespawnAtDistance(false)

    guard.combatTarget =
        player
}