package com.rs.game.content.commands.development

import com.rs.engine.command.Commands
import com.rs.plugin.annotations.ServerStartupEvent
import org.darkan.core.model.Rights
import world.gregs.voidps.cache.source.codec.MapCodec
import world.gregs.voidps.cache.source.codec.WorldGenerator
import world.gregs.voidps.cache.source.codec.WorldGenerator.Biome
import kotlin.math.abs

private const val MAP_TILES = 16384
private const val SEARCH_STEP_TILES = 16
private const val GROUND_PLANE = 0

@ServerStartupEvent
fun loadBiomeCommands() {
    val last = Biome.entries.size - 1
    val names = Biome.entries.joinToString(", ") { "${it.ordinal} ${it.name.lowercase()}" }
    Commands.add(Rights.DEVELOPER, "telebiome [0-$last]", "Teleports to the nearest tile of a biome: $names.") { p, args ->
        val generator = MapCodec.generator as? WorldGenerator
        if (generator == null) {
            p.sendMessage("This world does not generate its map, so it has no biomes.")
            return@add
        }
        val biome = args.getOrNull(0)?.toIntOrNull()?.let { Biome.entries.getOrNull(it) }
        if (biome == null) {
            p.sendMessage("Use telebiome [0-$last]: $names.")
            return@add
        }
        val target = nearestBiomeTile(generator, biome, p.x, p.y)
        if (target == null) {
            p.sendMessage("No ${biome.name.lowercase()} was found anywhere on the map.")
            return@add
        }
        p.tele(target.first, target.second, GROUND_PLANE)
        p.sendMessage("Teleported to the nearest ${biome.name.lowercase()}.")
    }
}

private fun nearestBiomeTile(generator: WorldGenerator, biome: Biome, originX: Int, originY: Int): Pair<Int, Int>? {
    for (distance in 0 until MAP_TILES step SEARCH_STEP_TILES) {
        for (dx in -distance..distance step SEARCH_STEP_TILES) {
            for (dy in -distance..distance step SEARCH_STEP_TILES) {
                if (maxOf(abs(dx), abs(dy)) != distance)
                    continue
                val x = originX + dx
                val y = originY + dy
                if (x !in 0 until MAP_TILES || y !in 0 until MAP_TILES)
                    continue
                if (generator.biomeAt(x, y) == biome && generator.liquidTileAt(x, y) == null)
                    return x to y
            }
        }
    }
    return null
}
