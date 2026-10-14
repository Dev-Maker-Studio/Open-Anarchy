package com.rs.rsps.tileman

import com.rs.Settings
import com.rs.engine.cutscene.Cutscene
import com.rs.game.World
import com.rs.game.content.achievements.Achievement
import com.rs.game.model.entity.player.Player
import com.rs.game.model.entity.player.managers.InterfaceManager.Sub
import org.darkan.core.gameval.gen.Npc
import org.darkan.core.gameval.gen.Seq
import org.darkan.core.gameval.gen.Spotanim
import world.gregs.voidps.type.Tile

class StartTilemanCutscene(private val lodestone: Tile) : Cutscene() {
    override fun construct(player: Player) {
        val arrival = lodestone.transform(0, -1, 0)
        setEndTile(arrival)
        action(1) {
            player.controllerManager.forceStop()
            player.abortDialogue()
            player.appearance.transformIntoNPC(Npc.npc_266)
            player.tele(lodestone)
            player.setNextFaceTile(arrival)
        }
        action(4) {
            player.appearance.transformIntoNPC(-1)
            player.anim(Seq.thas_player_home_teleport_arrive)
            player.spotAnim(Spotanim.thas_player_home_teleport_arrive_spotanim)
        }
        action(2) { player.anim(Seq.thas_player_home_teleport_arrive_step) }
        action(1) {
            World.sendWorldMessage("<img=5><col=FF0000>${player.displayName} has just joined ${Settings.getConfig().serverName}!</col>", false)
            player.interfaceManager.flashTabOff()
            player.interfaceManager.sendSubDefaults(*Sub.ALL_GAME_TABS)
            player.interfaceManager.sendAchievementComplete(Achievement.THE_JOURNEY_BEGINS_3521)
            player.tele(arrival)
            player.anim(-1)
            player.spotAnim(-1)
            player.isChosenAccountType = true
        }
    }
}
