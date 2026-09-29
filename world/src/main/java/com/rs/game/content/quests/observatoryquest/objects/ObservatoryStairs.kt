package com.rs.game.content.quests.observatoryquest.objects

import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onObjectClick
import world.gregs.voidps.type.Tile

private const val GOBLIN_ENTRANCE_STAIRS = 25432

private val GOBLIN_ENTRANCE_STAIRS_TILE =
    Tile.of(2458, 3186, 0)

private val GOBLIN_DUNGEON_DESTINATION =
    Tile.of(2355, 9394, 0)

private const val DUNGEON_EXIT_STAIRS = 25429

private val GOBLIN_DUNGEON_STAIRS_TILE =
    Tile.of(2355, 9395, 0)

private val GOBLIN_EXIT_DESTINATION =
    Tile.of(2458, 3185, 0)

private const val OBSERVATORY_STAIRS = 25434

private val OBSERVATORY_STAIRS_TILE =
    Tile.of(2438, 3164, 0)

private val OBSERVATORY_DUNGEON_DESTINATION =
    Tile.of(2335, 9350, 0)

private val OBSERVATORY_DUNGEON_STAIRS_TILE =
    Tile.of(2335, 9351, 0)

private val OBSERVATORY_EXIT_DESTINATION =
    Tile.of(2439, 3164, 0)

@ServerStartupEvent
fun mapObservatoryStairs() {

    onObjectClick(
        GOBLIN_ENTRANCE_STAIRS,
        tiles = arrayOf(GOBLIN_ENTRANCE_STAIRS_TILE)
    ) { e ->

        e.player.tele(
            GOBLIN_DUNGEON_DESTINATION
        )
    }

    onObjectClick(
        DUNGEON_EXIT_STAIRS,
        tiles = arrayOf(GOBLIN_DUNGEON_STAIRS_TILE)
    ) { e ->

        e.player.tele(
            GOBLIN_EXIT_DESTINATION
        )
    }

    onObjectClick(
        OBSERVATORY_STAIRS,
        tiles = arrayOf(OBSERVATORY_STAIRS_TILE)
    ) { e ->

        e.player.tele(
            OBSERVATORY_DUNGEON_DESTINATION
        )
    }

    onObjectClick(
        DUNGEON_EXIT_STAIRS,
        tiles = arrayOf(OBSERVATORY_DUNGEON_STAIRS_TILE)
    ) { e ->

        e.player.tele(
            OBSERVATORY_EXIT_DESTINATION
        )
    }
}
