package com.rs.game.content.world.generated

import com.rs.game.World
import com.rs.game.content.skills.hunter.Impling
import com.rs.game.model.entity.player.Player
import com.rs.game.tasks.WorldTasks
import com.rs.plugin.annotations.ServerStartupEvent
import org.darkan.core.EnvVars
import org.darkan.core.Utils
import org.darkan.core.gameval.gen.Npc
import org.darkan.core.map.GeneratedWorld
import world.gregs.voidps.cache.source.codec.WorldGenerator
import world.gregs.voidps.cache.source.codec.WorldGenerator.Biome
import world.gregs.voidps.type.Tile

private const val MINUTE_TICKS = 100
private const val POPUP_ODDS = 25
private const val WILDERNESS_POPUP_ODDS = 20
private const val MIN_DISTANCE = 4
private const val MAX_DISTANCE = 12
private const val PLACEMENT_ATTEMPTS = 24
private const val ANIMAL_LIFETIME_TICKS = 3000
private const val GROUND_LEVEL = 0

private val generator = GeneratedWorld.generator

private val HOSTILE_BIOMES = setOf(Biome.WILDERNESS, Biome.WINTER, Biome.VOLCANO)

private val animals = mapOf(
    Biome.PLAINS to intArrayOf(Npc.chicken, Npc.cow, Npc.npc_5154, Npc.pig_1, Npc.dog, Npc.poh_growncat_bluegrey),
    Biome.DESERT to intArrayOf(Npc.desert_goat_black2, Npc.rabbit_rabbit_grey, Npc.camel, Npc.snake, Npc.scorpion),
    Biome.WILDERNESS to intArrayOf(
        Npc.rand_skeleton_melee_lvl2_3, Npc.rand_skeleton_melee_lvl3_1, Npc.rand_skeleton_melee_lvl5_1,
        Npc.rand_skeleton_melee_lvl7_1, Npc.rand_skeleton_melee_lvl10_2, Npc.darkbear, Npc.brownbear, Npc.wolf
    ),
    Biome.WINTER to intArrayOf(Npc.icegiant, Npc.icegiant2, Npc.icegiant3, Npc.icewarrior, Npc.wolf, Npc.wolfpack_leader_whiter),
    Biome.MOUNTAIN to intArrayOf(Npc.wolf, Npc.bat, Npc.brownbear, *HILL_GIANT_IDS, *MOUNTAIN_GOAT_IDS),
    Biome.VOLCANO to intArrayOf(Npc.imp, Npc.hellhound),
    Biome.OCEAN to intArrayOf(Npc.small_bat)
)

private val HOSTILE_ANIMALS = setOf(Npc.snake, Npc.scorpion, Npc.wolf, Npc.bat, Npc.brownbear, Npc.small_bat, *HILL_GIANT_IDS.toTypedArray())

private val forestImplings = intArrayOf(Npc.ii_impling_type_1, Npc.ii_impling_type_2, Npc.ii_impling_type_3, Npc.ii_impling_type_4)

@ServerStartupEvent
fun startGeneratedWildlife() {
    if (!EnvVars.generateMaps) return
    WorldTasks.scheduleTimer(MINUTE_TICKS, MINUTE_TICKS) {
        World.players.forEach { player ->
            if (player.hasStarted() && !player.hasFinished() && player.plane == GROUND_LEVEL) {
                val odds = if (generator.biomeAt(player.x, player.y) in HOSTILE_BIOMES) WILDERNESS_POPUP_ODDS else POPUP_ODDS
                if (Utils.random(odds) == 0) popUpAnimal(player)
            }
        }
        true
    }
}

private fun popUpAnimal(player: Player) {
    val tile = findTile(player) { x, y -> World.floorFree(GROUND_LEVEL, x, y, 1) && generator.liquidTileAt(x, y) == null } ?: return
    val biome = generator.biomeAt(tile.x, tile.y)
    if (biome == Biome.FOREST) {
        Impling(forestImplings[Utils.random(forestImplings.size)], tile, true, true)
        return
    }
    val level = threatLevel(tile)
    val ids = (animals[biome] ?: return).filter { !isHostile(biome, it) || withinLevel(it, level) }
    if (ids.isEmpty()) return
    val id = ids[Utils.random(ids.size)]
    val animal = if (isHostile(biome, id)) scaledHostile(id, tile, biome, level) else World.spawnNPC(id, tile, true)
    animal.finishAfterTicks(ANIMAL_LIFETIME_TICKS)
}

private fun isHostile(biome: Biome, id: Int): Boolean = biome in HOSTILE_BIOMES || id in HOSTILE_ANIMALS

private fun findTile(player: Player, accepts: (Int, Int) -> Boolean): Tile? {
    repeat(PLACEMENT_ATTEMPTS) {
        val dx = Utils.random(-MAX_DISTANCE, MAX_DISTANCE + 1)
        val dy = Utils.random(-MAX_DISTANCE, MAX_DISTANCE + 1)
        if (maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) < MIN_DISTANCE) return@repeat
        val tile = player.tile.transform(dx, dy)
        if (accepts(tile.x, tile.y)) return tile
    }
    return null
}
