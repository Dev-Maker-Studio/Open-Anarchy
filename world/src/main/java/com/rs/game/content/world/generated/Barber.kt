package com.rs.game.content.world.generated

import com.rs.engine.dialogue.HeadE
import com.rs.engine.dialogue.startConversation
import com.rs.game.content.PlayerLook
import com.rs.game.model.entity.player.Player
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onNpcClick
import org.darkan.core.gameval.gen.Npc
import world.gregs.voidps.type.Tile

private const val HAIRDRESSER_NPC = Npc.npc_598

class BarberNPC(tile: Tile) : CityResident(HAIRDRESSER_NPC, tile)

private fun talkToBarber(player: Player) {
    player.startConversation {
        npc(HAIRDRESSER_NPC, HeadE.HAPPY_TALKING, "Welcome! What would you like changed today?")
        options {
            op("Hair and beard.") {
                exec { PlayerLook.openHairdresserSalon(player) }
            }
            op("Clothes.") {
                exec { PlayerLook.openThessaliasMakeOver(player) }
            }
            op("Skin colour and gender.") {
                exec { PlayerLook.openMageMakeOver(player) }
            }
            op("Nothing, thanks.") {
                player(HeadE.CALM_TALK, "Nothing, thanks.")
            }
        }
    }
}

@ServerStartupEvent
fun startBarber() {
    onNpcClick(HAIRDRESSER_NPC) { (player, npc) ->
        if (npc is BarberNPC) talkToBarber(player)
    }
}
