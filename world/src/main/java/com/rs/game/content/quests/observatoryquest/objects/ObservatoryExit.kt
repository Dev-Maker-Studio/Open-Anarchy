package com.rs.game.content.quests.observatoryquest

import com.rs.engine.quest.Quest
import com.rs.game.content.world.doors.Doors
import com.rs.game.model.entity.player.Player
import com.rs.game.model.gameobject.GameObject
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onChunkEnter
import com.rs.plugin.kts.onObjectClick
import org.darkan.core.gameval.gen.Loc
import org.darkan.core.model.ObjectShape
import world.gregs.voidps.type.Tile

private val OBSERVATORY_ROPE_TILE = Tile.of(2446, 3171, 0)
private val OBSERVATORY_ROPE_UP_DESTINATION = Tile.of(2445, 3166, 0)
private val OBSERVATORY_ROPE_OBJECT = GameObject(Loc.loc_2744, ObjectShape.SCENERY_INTERACT, 0, OBSERVATORY_ROPE_TILE)

fun syncObservatoryRope(player: Player) {
    if (player.getQuestStage(Quest.OBSERVATORY_QUEST) >= STAGE_COMPLETE)
        player.packets.sendAddObject(OBSERVATORY_ROPE_OBJECT)
    else
        player.packets.sendRemoveObject(OBSERVATORY_ROPE_OBJECT)
}

@ServerStartupEvent
fun mapObservatoryExit() {
    onChunkEnter { e ->
        val player = e.player ?: return@onChunkEnter
        if (player.plane == OBSERVATORY_ROPE_TILE.plane && player.x in 2432..2455 && player.y in 3152..3175)
            syncObservatoryRope(player)
    }

    onObjectClick(Loc.qip_obs_door_left, Loc.qip_obs_door_right, tiles = arrayOf(Tile.of(2445, 3165, 0), Tile.of(2444, 3165, 0))) { (player, door) ->
        if (player.getQuestStage(Quest.OBSERVATORY_QUEST) < STAGE_COMPLETE) {
            player.sendMessage("The door is locked.")
            return@onObjectClick
        }
        Doors.handleDoubleDoor(player, door)
    }

    onObjectClick(Loc.loc_2744, tiles = arrayOf(OBSERVATORY_ROPE_TILE)) { (player) ->
        if (player.getQuestStage(Quest.OBSERVATORY_QUEST) >= STAGE_COMPLETE)
            player.tele(OBSERVATORY_ROPE_UP_DESTINATION)
    }
}
