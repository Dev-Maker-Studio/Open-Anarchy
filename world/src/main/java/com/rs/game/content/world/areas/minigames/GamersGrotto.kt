package com.rs.game.content.world.areas.minigames

import com.rs.game.content.minigames.creations.StealingCreationLobbyController
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onObjectClick
import org.darkan.core.gameval.gen.Loc
import world.gregs.voidps.type.Tile

@ServerStartupEvent
fun mapGamersGrotto() {
    onObjectClick(Loc.activity_cave_entrance) { (player) -> player.useStairs(-1, Tile.of(2969, 9672, 0), 1, 1) }
    onObjectClick(Loc.activity_cave_exit) { (player) -> player.useStairs(-1, Tile.of(3018, 3404, 0), 0, 1) }
    onObjectClick(Loc.sc_team_a_stile, Loc.sc_team_b_stile) { (player, obj) -> StealingCreationLobbyController.climbOverStile(player, obj, true) }
}
