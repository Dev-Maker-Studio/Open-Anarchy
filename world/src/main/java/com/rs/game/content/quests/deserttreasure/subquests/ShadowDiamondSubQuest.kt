package com.rs.game.content.quests.deserttreasure

import com.rs.engine.dialogue.HeadE.*
import com.rs.engine.dialogue.startConversation
import com.rs.engine.quest.Quest
import com.rs.game.model.entity.npc.OwnedNPC
import com.rs.game.model.entity.player.Player
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onNpcDeath
import com.rs.plugin.kts.onObjectClick
import com.rs.plugin.kts.onPlayerStep
import com.rs.plugin.kts.onLogin
import org.darkan.core.Constants
import org.darkan.core.Utils
import world.gregs.voidps.type.Tile

private const val SHADOW_ENTRANCE_LADDER = 6560
private const val SHADOW_INTERIOR_LADDER = 32015
private const val SECURE_CHEST = 6448

private const val DAMIS_FIRST = 1974
private const val DAMIS_SECOND = 1975
private const val LOCKPICK = 1523
private const val GILDED_CROSS = 4674

private val SHADOW_ENTRANCE_TILE = Tile.of(2547, 3421, 0)
private val SHADOW_INTERIOR_LADDER_TILE = Tile.of(2629, 5072, 0)
private val SECURE_CHEST_TILE = Tile.of(3169, 2967, 0)
private val DAMIS_SPAWN_TILE = Tile.of(2738, 5081, 0)

private val DAMIS_ROOM_TRIGGER = Tile.of(2737, 5081, 0)

const val RASOLO_DEAL_ACCEPTED = "dtRasoloDealAccepted"

private const val GILDED_CROSS_CLAIMED = "dtGildedCrossClaimed"
private const val DAMIS_ACTIVE = "dtDamisActive"
private const val DAMIS_FIRST_DEFEATED = "dtDamisFirstDefeated"
private const val DAMIS_DEFEATED = "dtDamisDefeated"
private const val SHADOW_DIAMOND_CLAIMED = "dtShadowDiamondClaimed"

private val DAMIS_SECOND_FORM_TAUNTS = arrayOf(
    "Your pathetic skill won't help you now!",
    "You are no match for my power!",
    "You will regret ever coming here!",
    "I will crush you utterly!",
    "I... am... invincible!",
    "Taste my blade and feel my wrath!"
)

@ServerStartupEvent
fun mapShadowDiamondDesertTreasure() {
    onLogin { (player) ->
        if (player.getBool(DAMIS_ACTIVE) && !player.getBool(DAMIS_DEFEATED)) {
            player.set(DAMIS_ACTIVE, false)
        }
    }

    onObjectClick(SECURE_CHEST, tiles = arrayOf(SECURE_CHEST_TILE)) { (player) ->
        openSecureChest(player)
    }

    onObjectClick(SHADOW_ENTRANCE_LADDER, tiles = arrayOf(SHADOW_ENTRANCE_TILE)) { (player) ->
        if (!player.getBool(RASOLO_DEAL_ACCEPTED)) {
            player.sendMessage("You cannot find a way into the hidden dungeon.")
            return@onObjectClick
        }
        player.useLadder(SHADOW_INTERIOR_LADDER_TILE)
    }

    onObjectClick(SHADOW_INTERIOR_LADDER, tiles = arrayOf(SHADOW_INTERIOR_LADDER_TILE)) { (player) ->
        player.useLadder(SHADOW_ENTRANCE_TILE)
    }

    onPlayerStep(arrayOf(DAMIS_ROOM_TRIGGER)) { (player) ->
        enterDamisRoom(player)
    }

    onNpcDeath(DAMIS_FIRST) { e ->
        val damis = e.npc as? OwnedNPC ?: return@onNpcDeath
        val player = damis.owner
        if (!player.getBool(DAMIS_ACTIVE) || player.getBool(DAMIS_DEFEATED))
            return@onNpcDeath

        player.set(DAMIS_FIRST_DEFEATED, true)
        damis.forceTalk("Now I show... you... my true... power!")

        val secondForm = OwnedNPC(player, DAMIS_SECOND, damis.tile, true)
        secondForm.setAutoDespawnAtDistance(false)
        secondForm.combatTarget = player
        scheduleDamisTaunt(player, secondForm)
    }

    onNpcDeath(DAMIS_SECOND) { e ->
        val damis = e.npc as? OwnedNPC ?: return@onNpcDeath
        val player = damis.owner
        if (!player.getBool(DAMIS_ACTIVE))
            return@onNpcDeath

        player.set(DAMIS_ACTIVE, false)
        player.set(DAMIS_DEFEATED, true)
        player.set(SHADOW_DIAMOND_CLAIMED, true)
        DesertTreasureDiamonds.giveShadowDiamond(player)
    }
}

private fun openSecureChest(player: Player) {
    if (player.getQuestStage(Quest.DESERT_TREASURE) != STAGE_DIAMONDS_OF_AZZANADRA ||
        !player.getBool(RASOLO_DEAL_ACCEPTED)
    ) {
        player.simpleDialogue("These bandits are hostile enough without me trying to rob them!")
        return
    }

    if (player.getBool(GILDED_CROSS_CLAIMED)) {
        player.simpleDialogue("You find nothing of interest inside the chest.")
        return
    }

    if (!player.inventory.containsOneItem(LOCKPICK)) {
        player.sendMessage("You need a lockpick to attempt to open the chest.")
        return
    }

    player.startConversation {
        simple("Your skill as a thief allows you to see some kind of elaborate booby trapped locking mechanism on this chest.")
        options("Try to open chest?") {
            op("Yes") {
                exec { attemptSecureChest(player) }
            }
            op("No") { }
        }
    }
}

private fun attemptSecureChest(player: Player) {
    if (!player.inventory.containsOneItem(LOCKPICK)) {
        player.sendMessage("You need a lockpick to attempt to open the chest.")
        return
    }

    player.faceTile(SECURE_CHEST_TILE)
    player.anim(832)
    player.sendMessage("You attempt to pick the first lock...")

    if (!rollLockpick(player)) {
        failSecureChest(player)
        return
    }
    player.sendMessage("...and successfully pick the first lock.")
    player.sendMessage("You attempt to pick the second lock...")

    if (!rollLockpick(player)) {
        failSecureChest(player)
        return
    }
    player.sendMessage("...and successfully pick the second lock.")
    player.sendMessage("You attempt to pick the final lock...")

    if (!rollLockpick(player)) {
        failSecureChest(player)
        return
    }

    player.sendMessage("...and manage to pick the final lock.")
    player.inventory.addItemDrop(GILDED_CROSS, 1)
    player.set(GILDED_CROSS_CLAIMED, true)
    player.simpleDialogue("Inside the chest, hidden under some rags, you find a Gilded Cross.")
}

private fun failSecureChest(player: Player) {
    player.sendMessage("...and fail. The locking mechanism has reset itself.")
    player.inventory.deleteItem(LOCKPICK, 1)
    player.sendMessage("Your lock pick snapped while attempting to pick the lock.")
}

private fun rollLockpick(player: Player): Boolean {
    val level = player.skills.getLevel(Constants.THIEVING)
    return Utils.skillSuccess(level, player.auraManager.thievingMul, 8, 72)
}

private fun enterDamisRoom(player: Player) {
    if (player.getQuestStage(Quest.DESERT_TREASURE) != STAGE_DIAMONDS_OF_AZZANADRA ||
        !player.getBool(RASOLO_DEAL_ACCEPTED)
    ) return

    if (player.getBool(DAMIS_DEFEATED)) {
        if (!DesertTreasureDiamonds.hasShadowDiamond(player)) {
            player.simpleDialogue("The Diamond of Shadow seems to have mystically found its way back here...")
            DesertTreasureDiamonds.giveShadowDiamond(player)
        }
        return
    }

    if (player.getBool(DAMIS_ACTIVE))
        return

    player.set(DAMIS_ACTIVE, true)
    player.startConversation {
        simple("A voice seems to come from walls:<br><br>'You... do not be... long in this... place...<br>Turn... back now, or... prepare... to meet your... doom...'")
        exec {
            val damis = OwnedNPC(player, DAMIS_FIRST, DAMIS_SPAWN_TILE, true)
            damis.setAutoDespawnAtDistance(false)
            damis.forceTalk("You should... have listened to me!")
            damis.combatTarget = player
        }
    }
}

private fun scheduleDamisTaunt(player: Player, damis: OwnedNPC) {
    player.tasks.schedule(20) {
        if (damis.hasFinished() || damis.isDead || damis.owner !== player || player.getBool(DAMIS_DEFEATED))
            return@schedule

        damis.forceTalk(DAMIS_SECOND_FORM_TAUNTS[Utils.random(DAMIS_SECOND_FORM_TAUNTS.size - 1)])
        scheduleDamisTaunt(player, damis)
    }
}
