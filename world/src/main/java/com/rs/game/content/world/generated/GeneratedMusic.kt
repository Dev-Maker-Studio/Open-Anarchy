package com.rs.game.content.world.generated

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.rs.game.model.entity.player.Player
import com.rs.utils.music.BiomeMusic
import com.rs.utils.music.Genre
import org.darkan.core.EnvVars
import org.darkan.core.dungeon.DungeonTheme
import org.darkan.core.map.GeneratedWorld
import world.gregs.voidps.cache.source.codec.WorldGenerator.Biome
import com.rs.utils.music.Biome as MusicBiome

private val musicBiomes = mapOf(
    Biome.PLAINS to MusicBiome.PLAINS,
    Biome.FOREST to MusicBiome.FOREST,
    Biome.DESERT to MusicBiome.DESERT,
    Biome.WILDERNESS to MusicBiome.WILDERNESS,
    Biome.WINTER to MusicBiome.SNOW,
    Biome.OCEAN to MusicBiome.OCEAN,
    Biome.MOUNTAIN to MusicBiome.MOUNTAIN,
    Biome.VOLCANO to MusicBiome.VOLCANIC,
    Biome.RUNE_ESSENCE to MusicBiome.RUNE_ESSENCE
)

private val dungeonBiomes = mapOf(
    DungeonTheme.CAVE to MusicBiome.CAVE,
    DungeonTheme.FORTRESS to MusicBiome.FORTRESS,
    DungeonTheme.SEWER to MusicBiome.SEWER
)

private fun genreOf(biome: MusicBiome): Genre {
    val songs = JsonArray().apply { BiomeMusic.generatedSongs(biome).forEach { add(it) } }
    val json = JsonObject().apply {
        addProperty("genre", MusicBiome.generatedGenreName(biome))
        addProperty("isActive", true)
        add("songs", songs)
    }
    return Gson().fromJson(json, Genre::class.java)
}

private val biomeGenres: Map<Biome, Genre> by lazy { musicBiomes.mapValues { (_, biome) -> genreOf(biome) } }

private val dungeonGenres: Map<DungeonTheme, Genre> by lazy { dungeonBiomes.mapValues { (_, biome) -> genreOf(biome) } }

fun biomeGenre(player: Player): Genre? {
    if (!EnvVars.generateMaps) return null
    if (GeneratedWorld.inDungeon(player.x, player.y)) return GeneratedWorld.dungeonAt(player.x, player.y)?.let { dungeonGenres[it.style] }
    return biomeGenres[GeneratedWorld.generator.biomeAt(player.x, player.y)]
}
