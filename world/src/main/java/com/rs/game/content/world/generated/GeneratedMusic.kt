package com.rs.game.content.world.generated

import com.rs.game.model.entity.player.Player
import com.rs.utils.music.Genre
import com.rs.utils.music.Music
import org.darkan.core.EnvVars
import org.darkan.core.map.GeneratedWorld
import world.gregs.voidps.cache.source.codec.WorldGenerator.Biome

private const val MISTHALIN_GENRE = "Misthalin"
private const val DESERT_GENRE = "Desert"
private const val WILDERNESS_GENRE = "Wilderness"

private val biomeGenres = mapOf(
    Biome.PLAINS to MISTHALIN_GENRE,
    Biome.FOREST to MISTHALIN_GENRE,
    Biome.DESERT to DESERT_GENRE,
    Biome.WILDERNESS to WILDERNESS_GENRE
)

fun biomeGenre(player: Player): Genre? {
    if (!EnvVars.generateMaps) return null
    val name = biomeGenres[GeneratedWorld.generator.biomeAt(player.x, player.y)] ?: return null
    return Music.getParent(name) ?: Music.getGenreByName(name)
}
