package com.rs.game.content.quests.deserttreasure

import com.rs.engine.quest.Quest
import com.rs.game.model.entity.npc.OwnedNPC
import com.rs.game.model.entity.player.Player
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onNpcDeath
import com.rs.plugin.kts.onObjectClick
import com.rs.plugin.kts.onLogin
import com.rs.game.model.entity.async.schedule
import world.gregs.voidps.type.Tile

private const val SMOKE_WELL = 36002
private const val BURNT_CHEST = 6420
private const val GATE_WEST = 6451
private const val GATE_EAST = 6452
private const val FAREED = 1977
private const val WARM_KEY = 4656
private const val TORCH_BURN_TICKS = 200

private const val TORCH_SW = 6407
private const val TORCH_NW = 6405
private const val TORCH_SE = 6409
private const val TORCH_NE = 6411

private const val SMOKE_DEFEATED = "dtFareedDefeated"
private const val SMOKE_DIAMOND_CLAIMED = "dtSmokeDiamondClaimed"
private const val FAREED_ACTIVE = "dtFareedActive"
private const val TORCH_SW_LIT = "dtSmokeTorchSWLit"
private const val TORCH_NW_LIT = "dtSmokeTorchNWLit"
private const val TORCH_SE_LIT = "dtSmokeTorchSELit"
private const val TORCH_NE_LIT = "dtSmokeTorchNELit"
private const val TORCH_SW_GENERATION = "dtSmokeTorchSWGeneration"
private const val TORCH_NW_GENERATION = "dtSmokeTorchNWGeneration"
private const val TORCH_SE_GENERATION = "dtSmokeTorchSEGeneration"
private const val TORCH_NE_GENERATION = "dtSmokeTorchNEGeneration"

private data class Torch(val id: Int, val state: String, val generation: String, val direction: String)

private val smokeTorches = listOf(
    Torch(TORCH_SW, TORCH_SW_LIT, TORCH_SW_GENERATION, "South-west"),
    Torch(TORCH_NW, TORCH_NW_LIT, TORCH_NW_GENERATION, "North-west"),
    Torch(TORCH_SE, TORCH_SE_LIT, TORCH_SE_GENERATION, "South-east"),
    Torch(TORCH_NE, TORCH_NE_LIT, TORCH_NE_GENERATION, "North-east"),
)

@ServerStartupEvent
fun mapSmokeDiamondDesertTreasure() {
    onLogin { (player) ->
        if (player.getBool(FAREED_ACTIVE) && !player.getBool(SMOKE_DEFEATED)) {
            player.set(FAREED_ACTIVE, false)
        }
    }

    onObjectClick(SMOKE_WELL) { (player) ->
        player.simpleDialogue("I can see a lot of foul looking smoke at the bottom of this well. I don't think breathing it in directly would be very good for my health.")
    }

    smokeTorches.forEach { torch ->
        onObjectClick(torch.id) { (player) ->
            if (!player.getBool(torch.state)) {
                player.set(torch.state, true)
                val generation = player.getI(torch.generation) + 1
                player.set(torch.generation, generation)
                player.sendMessage("You light the torch.")
                player.tasks.schedule(TORCH_BURN_TICKS) {
                    if (player.getBool(torch.state) && player.getI(torch.generation) == generation) {
                        player.set(torch.state, false)
                        player.sendMessage("The ${torch.direction} torch burns out...")
                    }
                }
                if (smokeTorches.all { player.getBool(it.state) }) {
                    player.sendMessage("The path is lit, now claim the key...")
                }
            } else {
                player.sendMessage("The ${torch.direction} torch is already lit.")
            }
        }
    }

    onObjectClick(BURNT_CHEST) { (player) ->
        if (!smokeTorches.all { player.getBool(it.state) }) {
            player.simpleDialogue("There seems to be no way to open this chest. Engraved where the keyhole should be is a message; 'Light the path, and find the key...'")
        } else if (player.containsItem(WARM_KEY)) {
            player.sendMessage("The chest is empty.")
        } else {
            player.inventory.addItemDrop(WARM_KEY, 1)
            player.sendMessage("You open the chest and take a key.")
        }
    }

    onObjectClick(GATE_WEST, GATE_EAST) { (player, obj) ->
        if (!player.containsItem(WARM_KEY)) {
            player.sendMessage("This gate is locked.")
            return@onObjectClick
        }
        if (player.getBool(SMOKE_DEFEATED)) {
            if (!DesertTreasureDiamonds.hasSmokeDiamond(player)) {
                player.sendMessage("The Diamond of Smoke seems to have mystically found its way back here...")
                DesertTreasureDiamonds.giveSmokeDiamond(player)
            } else {
                player.sendMessage("There is nothing more for you here.")
            }
            return@onObjectClick
        }
        if (player.getBool(FAREED_ACTIVE)) return@onObjectClick

        player.inventory.deleteItem(WARM_KEY, 1)
        player.sendMessage("You unlock the gate and enter the room.")
        player.set(FAREED_ACTIVE, true)
        val fareed = OwnedNPC(player, FAREED, Tile.of(3317, 9376, 0), true)
        fareed.setAutoDespawnAtDistance(false)
        fareed.combatTarget = player
        player.sendMessage("Fareed: You dare trespass in my realm?")
    }

    onNpcDeath(FAREED) { (killer, npc) ->
        val player = (npc as? OwnedNPC)?.owner ?: killer as? Player ?: return@onNpcDeath
        if (npc is OwnedNPC && npc.owner !== player) return@onNpcDeath
        player.set(SMOKE_DEFEATED, true)
        player.set(FAREED_ACTIVE, false)
        if (!player.getBool(SMOKE_DIAMOND_CLAIMED)) {
            player.sendMessage("You take the Diamond of Smoke from the ashes of the warrior.")
            DesertTreasureDiamonds.giveSmokeDiamond(player)
            player.set(SMOKE_DIAMOND_CLAIMED, true)
        }
    }
}