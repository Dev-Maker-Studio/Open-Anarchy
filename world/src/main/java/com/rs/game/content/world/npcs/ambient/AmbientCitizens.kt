package com.rs.game.content.world.npcs.ambient

import com.rs.engine.dialogue.HeadE
import com.rs.engine.dialogue.startConversation
import com.rs.game.World
import com.rs.game.model.entity.interactions.PlayerCombatInteraction
import com.rs.game.model.entity.npc.NPC
import com.rs.game.model.entity.npc.combat.NPCCombatDefinitions
import com.rs.game.model.entity.player.Player
import com.rs.game.tasks.WorldTasks
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.interceptNpcClick
import org.darkan.core.EnvVars
import org.darkan.core.Utils
import world.gregs.voidps.cache.Cache
import org.darkan.core.cache.NPCDefinitions
import org.darkan.core.map.GeneratedWorld
import org.darkan.core.map.WorldWindow
import world.gregs.voidps.cache.source.codec.WorldGenerator
import world.gregs.voidps.cache.source.codec.WorldGenerator.Biome
import world.gregs.voidps.type.Tile
import kotlin.math.abs

private const val SPAWN_INTERVAL_TICKS = 500
private const val SPAWN_CHANCE_PERCENT = 1.0
private const val PERCENT = 100.0
private const val SPAWN_RANGE = 150
private const val PLACEMENT_ATTEMPTS = 12
private const val AREA_RADIUS = 125
private const val SAMPLE_STEP = 25
private const val GROUND_LEVEL = 0
private const val TALK_OPTION = "Talk-to"
private const val ATTACK_OPTION = "Attack"
private const val UNNAMED = "null"
private const val DESPAWN_TILES = 400
private const val MAX_CITIZENS_PER_AREA = 6

private val generator = GeneratedWorld.generator

private val citizenIds: List<Int> by lazy {
    (0 until Cache.npcs.size).filter { id ->
        val def = NPCDefinitions.getDefs(id)
        def.name != UNNAMED && def.combatLevel > 0 &&
            def.getOption(1).equals(TALK_OPTION, ignoreCase = true) && def.hasOption(ATTACK_OPTION) &&
            NPCCombatDefinitions.getDefs(id) != NPCCombatDefinitions.DEFAULT_DEF
    }
}

private val citizens = java.util.concurrent.ConcurrentHashMap.newKeySet<AmbientNPC>()

@ServerStartupEvent
fun startAmbientCitizens() {
    if (!EnvVars.generateMaps) return
    interceptNpcClick(TALK_OPTION) { player, npc -> (npc as? AmbientNPC)?.let { talk(player, it) } != null }
    WorldTasks.scheduleTimer(SPAWN_INTERVAL_TICKS, SPAWN_INTERVAL_TICKS) {
        despawnAbandoned()
        World.players.forEach { player ->
            if (!player.isBot && player.hasStarted() && !player.hasFinished() && player.plane == GROUND_LEVEL &&
                Math.random() * PERCENT < SPAWN_CHANCE_PERCENT
            ) spawnNear(player)
        }
        true
    }
}

private fun spawnNear(player: Player) {
    if (citizenIds.isEmpty()) return
    repeat(PLACEMENT_ATTEMPTS) {
        val tile = player.tile.transform(Utils.random(-SPAWN_RANGE, SPAWN_RANGE + 1), Utils.random(-SPAWN_RANGE, SPAWN_RANGE + 1), 0)
        if (generator.liquidTileAt(tile.x, tile.y) != null || !World.floorAndWallsFree(tile, 1)) return@repeat
        if (citizens.count { !it.hasFinished() && Utils.getDistance(it.tile, tile) <= SPAWN_RANGE } >= MAX_CITIZENS_PER_AREA) return
        citizens.add(AmbientNPC(citizenIds.random(), tile))
        return
    }
}

private fun despawnAbandoned() {
    citizens.removeIf { npc ->
        val gone = npc.hasFinished() || npc.isDead
        val abandoned = !gone && World.players.none { !it.hasFinished() && Utils.getDistance(it.tile, npc.tile) < DESPAWN_TILES }
        if (abandoned) npc.finish()
        gone || abandoned
    }
}

private fun talk(player: Player, npc: AmbientNPC) {
    npc.pauseWandering()
    player.startConversation {
        npc(npc.id, HeadE.CALM_TALK, "Hello there, traveller.")
        options("What would you like to do?") {
            op("Ask about the area.") {
                npc(npc.id, HeadE.CALM_TALK, describeArea(npc.tile))
            }
            op("Trade") {
                exec { npc.trade(player) }
            }
            op("Attack") {
                exec { player.interactionManager.setInteraction(PlayerCombatInteraction(player, npc)) }
            }
        }
    }
}

private fun describeArea(tile: Tile): String {
    val here = generator.biomeAt(tile.x, tile.y)
    val found = linkedMapOf<Biome, String>()
    for (dx in -AREA_RADIUS..AREA_RADIUS step SAMPLE_STEP) {
        for (dy in -AREA_RADIUS..AREA_RADIUS step SAMPLE_STEP) {
            val biome = generator.biomeAt(tile.x + dx, tile.y + dy)
            if (biome != here) found.putIfAbsent(biome, direction(dx, dy))
        }
    }
    val local = "We are in the ${here.name.lowercase()}."
    if (found.isEmpty()) return "$local The land is the same in every direction for a good way."
    val others = found.entries.joinToString(", ") { "${it.key.name.lowercase()} to the ${it.value}" }
    val road = if (GeneratedWorld.roadTouches(tile.x - AREA_RADIUS, tile.y - AREA_RADIUS, tile.x + AREA_RADIUS, tile.y + AREA_RADIUS)) " A road passes through here and it is safe to walk." else ""
    return "$local Beyond that you will find $others.$road"
}

private fun direction(dx: Int, dy: Int): String {
    val north = if (dy > SAMPLE_STEP / 2) "north" else if (dy < -SAMPLE_STEP / 2) "south" else ""
    val east = if (dx > SAMPLE_STEP / 2) "east" else if (dx < -SAMPLE_STEP / 2) "west" else ""
    return (north + east).ifEmpty { "centre" }.let { if (north.isNotEmpty() && east.isNotEmpty()) "$north-$east" else it }
}
