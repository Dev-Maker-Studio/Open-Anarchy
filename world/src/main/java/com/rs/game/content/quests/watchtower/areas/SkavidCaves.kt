package com.rs.game.content.quests.watchtower.areas

import com.rs.game.content.quests.watchtower.CAVE_NIGHTSHADE
import com.rs.game.content.quests.watchtower.SKAVID_MAP
import com.rs.game.content.world.LightSource
import com.rs.game.model.entity.Hit
import com.rs.game.model.entity.player.Equipment
import com.rs.game.model.entity.player.Player
import com.rs.lib.game.Tile
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onObjectClick
import com.rs.plugin.kts.onPickupItem

private const val CAVE_1_ENTRANCE = 2806
private const val CAVE_2_ENTRANCE = 2807
private const val CAVE_3_ENTRANCE = 2808
private const val CAVE_4_ENTRANCE = 2809
private const val CAVE_5_ENTRANCE = 2805
private const val CAVE_6_ENTRANCE = 2810

private const val CAVE_1_EXIT = 2818
private const val CAVE_2_EXIT = 2819
private const val CAVE_3_EXIT = 2820
private const val CAVE_4_EXIT = 2817
private const val CAVE_5_EXIT = 2821
private const val CAVE_6_EXIT = 2822

private const val YANILLE_HOLE_IN = 2823

private const val NIGHTSHADE_POISON_DAMAGE = 20

private val CAVE_1_SURFACE = Tile.of(2522, 3068, 0)
private val CAVE_2_SURFACE = Tile.of(2539, 3052, 0)
private val CAVE_3_SURFACE = Tile.of(2552, 3052, 0)
private val CAVE_4_SURFACE = Tile.of(2553, 3033, 0)
private val CAVE_5_SURFACE = Tile.of(2560, 3022, 0)
private val CAVE_6_SURFACE = Tile.of(2527, 3011, 0)

private val CAVE_1_INSIDE = Tile.of(2532, 9469, 0)
private val CAVE_2_INSIDE = Tile.of(2518, 9455, 0)
private val CAVE_3_INSIDE = Tile.of(2498, 9451, 0)
private val CAVE_4_INSIDE = Tile.of(2498, 9418, 0)
private val CAVE_5_INSIDE = Tile.of(2504, 9441, 0)
private val CAVE_6_INSIDE = Tile.of(2522, 9411, 0)

private val CAVE_1_OUTSIDE = Tile.of(2524, 3070, 0)
private val CAVE_2_OUTSIDE = Tile.of(2540, 3054, 0)
private val CAVE_3_OUTSIDE = Tile.of(2553, 3054, 0)
private val CAVE_4_OUTSIDE = Tile.of(2552, 3034, 0)
private val CAVE_5_OUTSIDE = Tile.of(2562, 3023, 0)
private val CAVE_6_OUTSIDE = Tile.of(2529, 3012, 0)

private val YANILLE_HOLE_IN_TILE = Tile.of(2530, 9415, 0)
private val YANILLE_HOLE_DESTINATION = Tile.of(2588, 3106, 0)

private data class SkavidCave(
    val entranceId: Int,
    val entranceTile: Tile,
    val insideTile: Tile,
    val exitId: Int,
    val outsideTile: Tile
)

private val SKAVID_CAVES = listOf(
    SkavidCave(
        CAVE_1_ENTRANCE,
        CAVE_1_SURFACE,
        CAVE_1_INSIDE,
        CAVE_1_EXIT,
        CAVE_1_OUTSIDE
    ),
    SkavidCave(
        CAVE_2_ENTRANCE,
        CAVE_2_SURFACE,
        CAVE_2_INSIDE,
        CAVE_2_EXIT,
        CAVE_2_OUTSIDE
    ),
    SkavidCave(
        CAVE_3_ENTRANCE,
        CAVE_3_SURFACE,
        CAVE_3_INSIDE,
        CAVE_3_EXIT,
        CAVE_3_OUTSIDE
    ),
    SkavidCave(
        CAVE_4_ENTRANCE,
        CAVE_4_SURFACE,
        CAVE_4_INSIDE,
        CAVE_4_EXIT,
        CAVE_4_OUTSIDE
    ),
    SkavidCave(
        CAVE_5_ENTRANCE,
        CAVE_5_SURFACE,
        CAVE_5_INSIDE,
        CAVE_5_EXIT,
        CAVE_5_OUTSIDE
    ),
    SkavidCave(
        CAVE_6_ENTRANCE,
        CAVE_6_SURFACE,
        CAVE_6_INSIDE,
        CAVE_6_EXIT,
        CAVE_6_OUTSIDE
    )
)

private val CAVES_BY_ENTRANCE =
    SKAVID_CAVES.associateBy { it.entranceId }

private val CAVES_BY_EXIT =
    SKAVID_CAVES.associateBy { it.exitId }

@ServerStartupEvent
fun mapWatchtowerSkavidCaves() {
    onObjectClick(
        *CAVES_BY_ENTRANCE.keys.toTypedArray<Any>(),
        checkDistance = true
    ) { (player, obj) ->
        val cave =
            CAVES_BY_ENTRANCE[obj.id]
                ?: return@onObjectClick

        if (!obj.tile.isAt(cave.entranceTile.x, cave.entranceTile.y))
            return@onObjectClick

        if (!player.inventory.containsItem(SKAVID_MAP, 1)) {
            player.sendMessage(
                "The caves are too complex to navigate without a map."
            )
            return@onObjectClick
        }

        if (!LightSource.hasLightSource(player)) {
            player.sendMessage(
                "It is too dark to enter the cave without a light source."
            )
            return@onObjectClick
        }

        player.useStairs(cave.insideTile)
    }

    onObjectClick(
        *CAVES_BY_EXIT.keys.toTypedArray<Any>(),
        checkDistance = true
    ) { (player, obj) ->
        val cave =
            CAVES_BY_EXIT[obj.id]
                ?: return@onObjectClick

        player.useStairs(cave.outsideTile)
    }

    onObjectClick(
        YANILLE_HOLE_IN,
        tiles = arrayOf(YANILLE_HOLE_IN_TILE),
        options = arrayOf("Climb-down"),
        checkDistance = true
    ) { (player) ->
        player.sendMessage("You enter the tunnel.")

        player.useStairs(YANILLE_HOLE_DESTINATION)

        player.sendMessage(
            "So that's how the skavids are getting into Yanille!"
        )
    }

    onPickupItem(CAVE_NIGHTSHADE) { event ->
        val player = event.player

        if (hasArmouredGloves(player)) {
            return@onPickupItem
        }

        event.cancelPickup()

        player.applyHit(
            Hit(
                player,
                NIGHTSHADE_POISON_DAMAGE,
                Hit.HitLook.POISON_DAMAGE
            )
        )

        player.sendMessage(
            "The nightshade is highly poisonous! You need armoured gloves to pick it safely."
        )
    }
}

private fun hasArmouredGloves(player: Player): Boolean {
    val gloves =
        player.equipment[Equipment.HANDS]
            ?: return false

    if (gloves.name.contains("bracelet", ignoreCase = true)) {
        return false
    }

    val bonuses =
        gloves.definitions.getBonuses()

    return (5..10).any { index ->
        bonuses.getOrElse(index) { 0 } > 0
    }
}