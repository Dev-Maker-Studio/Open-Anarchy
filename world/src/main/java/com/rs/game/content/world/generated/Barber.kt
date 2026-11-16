package com.rs.game.content.world.generated

import com.rs.engine.dialogue.HeadE
import com.rs.engine.dialogue.startConversation
import com.rs.game.content.PlayerLook
import com.rs.game.model.entity.Hit
import com.rs.game.model.entity.npc.NPC
import com.rs.game.model.entity.player.Player
import com.rs.game.tasks.WorldTasks
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onNpcClick
import org.darkan.core.EnvVars
import org.darkan.core.gameval.gen.Npc
import world.gregs.voidps.type.Tile

private const val HAIRDRESSER_NPC = Npc.npc_598
private const val SPAWN_DELAY_TICKS = 10
private const val BARBER_OFFSET_X = 2

class BarberNPC(tile: Tile) : NPC(HAIRDRESSER_NPC, tile, null, true) {

    init {
        setRandomWalk(false)
    }

    override fun handlePreHit(hit: Hit) {
        hit.damage = 0
    }

    override fun removeHitpoints(hit: Hit) {}

    override fun canBeAttackedBy(player: Player): Boolean = false

    override fun canAggroPlayer(target: Player): Boolean = false
}

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
    if (!EnvVars.generateMaps) return
    WorldTasks.schedule(SPAWN_DELAY_TICKS) { BarberNPC(StartLodestone.tile.transform(BARBER_OFFSET_X, 0, 0)) }
}
