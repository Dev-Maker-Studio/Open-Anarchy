package com.rs.game.content.world.generated

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.rs.game.model.entity.player.Player
import com.rs.utils.music.BiomeMusic
import com.rs.utils.music.Genre
import org.darkan.core.EnvVars
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

private val biomeGenres: Map<Biome, Genre> by lazy {
    musicBiomes.mapValues { (_, biome) ->
        val songs = JsonArray().apply { BiomeMusic.generatedSongs(biome).forEach { add(it) } }
        val json = JsonObject().apply {
            addProperty("genre", MusicBiome.generatedGenreName(biome))
            addProperty("isActive", true)
            add("songs", songs)
        }
        Gson().fromJson(json, Genre::class.java)
    }
}

fun biomeGenre(player: Player): Genre? {
    if (!EnvVars.generateMaps) return null
    return biomeGenres[GeneratedWorld.generator.biomeAt(player.x, player.y)]
}
