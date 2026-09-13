package com.rs.game.content.world.areas.global

import com.rs.game.model.entity.player.Player
import com.rs.game.model.gameobject.GameObject
import com.rs.plugin.annotations.PluginEventHandler
import com.rs.plugin.handlers.ObjectClickHandler
import org.darkan.core.gameval.gen.Loc
import world.gregs.voidps.type.Tile

@PluginEventHandler
object Staircases {
    @JvmField
    val worldStaircasesUp = ObjectClickHandler(arrayOf<Any>(Loc.fai_varrock_stairs_taller, Loc.fai_varrock_woodenstairs_castle, Loc.fai_falador_castle_stairs)) { e ->
        val (dx, dy) = facingOffset(e.obj.rotation, 4)
        e.player.useStairs(-1, e.player.transform(dx, dy, 1), 1, 1)
    }

    @JvmField
    val worldStaircasesDown = ObjectClickHandler(arrayOf<Any>(Loc.fai_varrock_stairs_top, Loc.fai_varrock_bank_stairs, Loc.fai_falador_castle_stairstop_gloffset)) { e ->
        if (e.obj.tile.isAt(3189, 3432)) {
            e.player.useStairs(-1, e.player.transform(2, 6400, 0), 1, 1)
            return@ObjectClickHandler
        }
        val (dx, dy) = facingOffset(e.obj.rotation, -4)
        e.player.useStairs(-1, e.player.transform(dx, dy, -1), 1, 1)
    }

    @JvmField
    val alKharidLargeStairs = ObjectClickHandler(arrayOf<Any>(Loc.fai_alkh_large_stairs_lower_click, Loc.fai_alkh_large_stairs_upper)) { e ->
        if (e.obj.id == Loc.fai_alkh_large_stairs_lower_click) {
            val (dx, dy) = facingOffset(e.obj.rotation, 3)
            e.player.useStairs(-1, e.obj.tile.transform(dx, dy, 1), 1, 1)
        } else {
            val (dx, dy) = facingOffset(e.obj.rotation, -4)
            e.player.useStairs(-1, e.obj.tile.transform(dx, dy, -1), 1, 1)
        }
    }

    @JvmField
    val stairs = ObjectClickHandler(arrayOf<Any>("Staircase", "Stairs")) { e -> handleStaircases(e.player, e.obj, e.option) }

    private fun facingOffset(rotation: Int, distance: Int): Pair<Int, Int> = when (rotation and 0x3) {
        0 -> 0 to distance
        1 -> distance to 0
        2 -> 0 to -distance
        else -> -distance to 0
    }

    @JvmStatic
    fun handleStaircases(player: Player, obj: GameObject, option: String) {
        when (option.lowercase()) {
            "climb-up" -> {
                if (player.plane == 3) return
                player.useStairs(-1, Tile.of(player.x, player.y, player.plane + 1), 0, 1)
            }
            "climb-down" -> {
                if (player.plane == 0) return
                player.useStairs(-1, Tile.of(player.x, player.y, player.plane - 1), 0, 1)
            }
            "climb" -> {
                if (player.plane == 3 || player.plane == 0) return
                player.promptUpDown("Go up the stairs.", player.transform(0, 0, 1), "Go down the stairs.", player.transform(0, 0, -1))
            }
        }
    }
}
