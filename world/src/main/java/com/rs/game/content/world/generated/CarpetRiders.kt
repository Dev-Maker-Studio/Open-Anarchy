package com.rs.game.content.world.generated

import com.rs.engine.dialogue.HeadE
import com.rs.engine.dialogue.OptionBuilder
import com.rs.engine.dialogue.startConversation
import com.rs.game.map.Chunk
import com.rs.game.model.entity.npc.NPC
import com.rs.game.model.entity.player.Player
import com.rs.game.model.gameobject.GameObject
import com.rs.game.tasks.WorldTasks
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onNpcClick
import org.darkan.core.EnvVars
import org.darkan.core.Logger.logInfo
import org.darkan.core.gameval.gen.Loc
import org.darkan.core.gameval.gen.Npc
import org.darkan.core.map.GeneratedWorld
import org.darkan.core.model.ObjectShape
import world.gregs.voidps.cache.source.codec.WorldGenerator
import world.gregs.voidps.type.Tile
import java.util.concurrent.CompletableFuture
import kotlin.math.abs

private const val GROUND = 0
private const val REGION_SHIFT = 6
private const val VERTICAL_ARMS = 2
private const val MIN_DESERT_STRETCH = 48
private const val STAND_INSET = 8
private const val ROAD_WIDTH = 2
private const val MAX_ROAD_DISTANCE = 12
private const val STAND_MARGIN = 1
private const val STAND_MIN_X = 0
private const val STAND_MAX_X = 5
private const val STAND_MIN_Y = -2
private const val STAND_MAX_Y = 1
private const val MERCHANT_OFFSET_X = 3
private const val FARE = 10
private const val SPAWN_CHECK_TICKS = 10
private const val UNLOCKED_KEY = "carpetRiders"
private val OUTWARD = arrayOf("North", "South", "East", "West")
private val INWARD = arrayOf("South", "North", "West", "East")

private class StandPiece(val loc: Int, val shape: ObjectShape, val x: Int, val y: Int, val rotation: Int)

private val STAND = listOf(
    StandPiece(Loc.rugcorner_blue, ObjectShape.GROUND_DECORATION, 2, -1, 3),
    StandPiece(Loc.rugcorner_blue, ObjectShape.GROUND_DECORATION, 2, 1, 0),
    StandPiece(Loc.rugcorner_blue, ObjectShape.GROUND_DECORATION, 5, -1, 2),
    StandPiece(Loc.rugcorner_blue, ObjectShape.GROUND_DECORATION, 5, 1, 1),
    StandPiece(Loc.rugside_blue, ObjectShape.GROUND_DECORATION, 2, 0, 3),
    StandPiece(Loc.rugside_blue, ObjectShape.GROUND_DECORATION, 3, -1, 2),
    StandPiece(Loc.rugside_blue, ObjectShape.GROUND_DECORATION, 3, 1, 0),
    StandPiece(Loc.rugside_blue, ObjectShape.GROUND_DECORATION, 4, -1, 2),
    StandPiece(Loc.rugside_blue, ObjectShape.GROUND_DECORATION, 4, 1, 0),
    StandPiece(Loc.rugside_blue, ObjectShape.GROUND_DECORATION, 5, 0, 1),
    StandPiece(Loc.rugmiddle_blue, ObjectShape.GROUND_DECORATION, 3, 0, 2),
    StandPiece(Loc.rugmiddle_blue, ObjectShape.GROUND_DECORATION, 4, 0, 2),
    StandPiece(Loc.transportation_icon, ObjectShape.GROUND_DECORATION, 4, -2, 2),
    StandPiece(Loc.carpet_rolledup, ObjectShape.SCENERY_INTERACT, 2, 1, 0),
    StandPiece(Loc.carpet_rolledup_pile, ObjectShape.SCENERY_INTERACT, 4, 0, 2),
    StandPiece(Loc.carpet_shadetent, ObjectShape.SCENERY_INTERACT, 3, -2, 1),
    StandPiece(Loc.carpet_rolledout, ObjectShape.SCENERY_INTERACT, 0, -1, 0),
    StandPiece(Loc.duel2_crate, ObjectShape.SCENERY_INTERACT, 4, -1, 1)
)

class CarpetStop(val arm: Int, val along: Int, val x: Int, val y: Int) {

    val key = "$x,$y"

    val merchant: Tile get() = Tile.of(x + MERCHANT_OFFSET_X, y, GROUND)

    fun covers(tileX: Int, tileY: Int): Boolean =
        tileX in x + STAND_MIN_X - STAND_MARGIN..x + STAND_MAX_X + STAND_MARGIN && tileY in y + STAND_MIN_Y - STAND_MARGIN..y + STAND_MAX_Y + STAND_MARGIN
}

object CarpetRiders {

    val stops: List<CarpetStop> by lazy { if (EnvVars.generateMaps) plan() else emptyList() }

    fun covers(x: Int, y: Int): Boolean = stops.any { it.covers(x, y) }

    fun spawn(chunk: Chunk) {
        if (chunk.plane != GROUND) return
        for (stop in stops) {
            for (piece in STAND) {
                val x = stop.x + piece.x
                val y = stop.y + piece.y
                if (x shr 3 != chunk.x || y shr 3 != chunk.y) continue
                chunk.addGeneratedObject(GameObject(piece.loc, piece.shape, piece.rotation, x, y, GROUND))
            }
        }
    }

    fun unlocked(player: Player, stop: CarpetStop): Boolean = stop.key in unlockedKeys(player)

    fun talk(player: Player, npc: NPC) {
        val stop = stops.minByOrNull { maxOf(abs(it.merchant.x - npc.x), abs(it.merchant.y - npc.y)) } ?: return
        unlock(player, stop)
        val outward = next(stop, 1)
        val inward = next(stop, -1)
        player.startConversation {
            if (outward == null && inward == null) {
                npc(npc, HeadE.CALM_TALK, "My carpets fly along the road, but there is no other merchant on this one to send you to.")
                return@startConversation
            }
            npc(npc, HeadE.HAPPY_TALKING, "A carpet ride along the road costs $FARE coins. Which way would you like to fly?")
            options("Where would you like to travel?") {
                outward?.let { destination -> op(label(player, OUTWARD[stop.arm], destination)) { travel(player, npc, stop, destination) } }
                inward?.let { destination -> op(label(player, INWARD[stop.arm], destination)) { travel(player, npc, stop, destination) } }
                op("Nevermind")
            }
        }
    }

    private fun OptionBuilder.travel(player: Player, npc: NPC, stop: CarpetStop, destination: CarpetStop) {
        when {
            !unlocked(player, destination) ->
                npc(npc, HeadE.CALM_TALK, "I cannot send a carpet to a merchant you have never met. Find them on the road and speak to them first.")
            !player.inventory.hasCoins(FARE) -> npc(npc, HeadE.CALM_TALK, "The fare is $FARE coins, and you do not have them.")
            else -> exec {
                if (!player.inventory.hasCoins(FARE)) return@exec
                player.inventory.removeCoins(FARE)
                player.controllerManager.startController(CarpetRideController(stop.x, stop.y, stop.arm, stop.along, destination.along, destination.x, destination.y))
            }
        }
    }

    private fun label(player: Player, direction: String, destination: CarpetStop) =
        if (unlocked(player, destination)) direction else "$direction (Unknown)"

    private fun next(stop: CarpetStop, step: Int): CarpetStop? {
        val onArm = stops.filter { it.arm == stop.arm }
        return onArm.getOrNull(onArm.indexOf(stop) + step)
    }

    private fun unlock(player: Player, stop: CarpetStop) {
        val keys = unlockedKeys(player)
        if (stop.key !in keys) player.set(UNLOCKED_KEY, ArrayList(keys + stop.key))
    }

    private fun unlockedKeys(player: Player): List<String> = player.getO<List<String>>(UNLOCKED_KEY) ?: emptyList()

    private fun plan(): List<CarpetStop> {
        val generator = GeneratedWorld.generator
        val stops = ArrayList<CarpetStop>()
        generator.roadArms().forEachIndexed { arm, road ->
            var stretch = 0
            for (along in 0 until road.length) {
                val (x, y) = generator.roadCentreAt(arm, along)
                stretch = if (desert(generator, x, y)) stretch + 1 else 0
                if (stretch == MIN_DESERT_STRETCH) place(generator, arm, along - MIN_DESERT_STRETCH + STAND_INSET)?.let(stops::add)
            }
        }
        logInfo("Carpet riders: " + stops.joinToString { "${OUTWARD[it.arm]} ${it.along} at ${it.key}" })
        return stops
    }

    private fun desert(generator: WorldGenerator, x: Int, y: Int): Boolean =
        generator.inArea(x shr REGION_SHIFT, y shr REGION_SHIFT) && generator.biomeAt(x, y) == WorldGenerator.Biome.DESERT && !GeneratedWorld.inCity(x, y)

    private fun place(generator: WorldGenerator, arm: Int, along: Int): CarpetStop? {
        val (roadX, roadY) = generator.roadCentreAt(arm, along)
        for (distance in 1..MAX_ROAD_DISTANCE) {
            val candidates = if (arm < VERTICAL_ARMS) {
                listOf(roadX + ROAD_WIDTH + distance - STAND_MIN_X to roadY, roadX - 1 - distance - STAND_MAX_X to roadY)
            } else {
                listOf(roadX to roadY + ROAD_WIDTH + distance - STAND_MIN_Y, roadX to roadY - 1 - distance - STAND_MAX_Y)
            }
            for ((x, y) in candidates) {
                val stop = CarpetStop(arm, along, x, y)
                if (clear(generator, stop)) return stop
            }
        }
        return null
    }

    private fun clear(generator: WorldGenerator, stop: CarpetStop): Boolean {
        for (x in stop.x + STAND_MIN_X - STAND_MARGIN..stop.x + STAND_MAX_X + STAND_MARGIN) {
            for (y in stop.y + STAND_MIN_Y - STAND_MARGIN..stop.y + STAND_MAX_Y + STAND_MARGIN) {
                if (!desert(generator, x, y) || generator.onRoad(x, y) || generator.liquidTileAt(x, y) != null) return false
            }
        }
        return true
    }
}

@ServerStartupEvent
fun startCarpetRiders() {
    onNpcClick(Npc.npc_2296) { (player, npc) -> CarpetRiders.talk(player, npc) }
    if (!EnvVars.generateMaps) return
    val stops = CompletableFuture.supplyAsync { CarpetRiders.stops }
    WorldTasks.scheduleTimer(SPAWN_CHECK_TICKS, SPAWN_CHECK_TICKS) {
        if (!stops.isDone) return@scheduleTimer true
        stops.get().forEach { NPC(Npc.npc_2296, it.merchant, null, true).setRandomWalk(false) }
        false
    }
}
