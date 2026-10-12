package com.rs.utils.music

import org.darkan.core.EnvVars
import org.darkan.core.gameval.gen.Midi
import java.util.ArrayDeque
import java.util.Deque

object BiomeMusic {
    const val NO_SONG = -1

    private const val THREAT_PERCENT = 110
    private const val PERCENT = 100
    private const val NEAR_LEVEL_GAP = 200
    private const val FAR_LEVEL_GAP = 500
    private val PLAINS_PARENTS = listOf("Misthalin", "Asgarnia", "Kandarin")
    private val EXCLUDED_WORDS = listOf("altar", "guild", "tutorial", "barbarian assault", "pest control", "void knight", "runespan", "safe room", "stronghold of player safety")

    private val pools: Map<Biome, List<Int>> by lazy {
        val byBiome = HashMap<Biome, MutableSet<Int>>()
        for (genre in Music.getGenres()) {
            if (!genre.isActive || EXCLUDED_WORDS.any { genre.genreName.lowercase().contains(it) })
                continue
            byBiome.getOrPut(Biome.of(genre)) { LinkedHashSet() }.addAll(genre.songs.toList())
        }
        val plains = byBiome.getOrPut(Biome.PLAINS) { LinkedHashSet() }
        for (parent in PLAINS_PARENTS)
            Music.getParent(parent)?.let { plains.addAll(it.songs.toList()) }
        byBiome.mapValues { (_, songs) -> songs.filter { Music.getSong(it) != null } }
    }

    private val generated: Map<Biome, List<Int>> = mapOf(
        Biome.PLAINS to listOf(
            Midi.harmony, Midi.harmony2, Midi.garden, Midi.wander, Midi.village, Midi.start, Midi.adventure,
            Midi.spirit, Midi.yesteryear, Midi.dream, Midi.expanse, Midi.tomorrow, Midi.lullaby
        ),
        Biome.FOREST to listOf(
            Midi.forest, Midi.woodland, Midi.natural, Midi.riverside, Midi.faerie, Midi.neverland, Midi.emotion,
            Midi.understanding, Midi.serene, Midi.breeze, Midi.soundscape
        ),
        Biome.DESERT to listOf(
            Midi.arabian, Midi.arabian2, Midi.arabian3, Midi.nomad, Midi.egypt, Midi.sunburn, Midi.scarab, Midi.sphinx,
            Midi.mirage, Midi.dynasty, Midi.lonesome, Midi.hypnotized, Midi.arabique, Midi.kharidian_nights
        ),
        Biome.SNOW to listOf(
            Midi.frostbite, Midi.rellekka, Midi.starlight, Midi.beyond, Midi.kingdom, Midi.hermit, Midi.saga,
            Midi.borderland, Midi.warrior, Midi.inadequacy
        ),
        Biome.WILDERNESS to listOf(
            Midi.wilderness, Midi.wilderness2, Midi.wilderness3, Midi.dangerous, Midi.dark, Midi.faithless, Midi.forbidden,
            Midi.undercurrent, Midi.underground, Midi.troubled, Midi.venomous, Midi.moody, Midi.gaol, Midi.legion
        ),
        Biome.OCEAN to listOf(
            Midi.saltwater, Midi.stillwater, Midi.freshwater, Midi.voyage, Midi.trawler, Midi.fishing, Midi.landlubber,
            Midi.marooned, Midi.shipwrecked, Midi.lighthouse, Midi.stranded, Midi.waterfall, Midi.waterlogged
        ),
        Biome.MOUNTAIN to listOf(
            Midi.expedition, Midi.sojourn, Midi.contest, Midi.tremble, Midi.overpass, Midi.horizon, Midi.legend,
            Midi.courage, Midi.principality
        ),
        Biome.VOLCANIC to listOf(
            Midi.eruption, Midi.tzhaar, Midi.kethsi, Midi.deadlands, Midi.shadowland, Midi.lair,
            Midi.tzhaar2wandering1, Midi.tzhaar2wandering2, Midi.tzhaar2wandering3, Midi.tzhaar2wandering4, Midi.tzhaar2wandering5
        ),
        Biome.RUNE_ESSENCE to listOf(
            Midi.rune_mechanics, Midi.stillness, Midi.dreamstate, Midi.inspiration, Midi.mellow, Midi.arrival, Midi.venture, Midi.venture2
        )
    )

    @JvmStatic
    fun generatedSongs(biome: Biome): List<Int> = generated[biome].orEmpty().filter { Music.getSong(it) != null }

    @JvmStatic
    fun songsOf(biome: Biome): List<Int> {
        if (EnvVars.generateMaps)
            generatedSongs(biome).takeIf { it.isNotEmpty() }?.let { return it }
        return pools[biome]?.takeIf { it.isNotEmpty() } ?: pools[Biome.PLAINS].orEmpty()
    }

    @JvmStatic
    fun nextSong(biome: Biome, queue: ArrayDeque<Int>, recent: Deque<Int>): Int {
        if (queue.isEmpty()) {
            val songs = songsOf(biome)
            if (songs.isEmpty())
                return NO_SONG
            val shuffled = songs.shuffled().toMutableList()
            val last = recent.peekFirst()
            if (shuffled.size > 1 && shuffled.first() == last)
                shuffled.add(shuffled.removeAt(0))
            queue.addAll(shuffled)
        }
        return queue.poll()
    }

    @JvmStatic
    fun combatSong(npcLevel: Int, playerLevel: Int): Int {
        if (npcLevel * PERCENT < playerLevel * THREAT_PERCENT)
            return NO_SONG
        val gap = npcLevel - playerLevel
        return when {
            gap <= NEAR_LEVEL_GAP -> Midi.attack3
            gap <= FAR_LEVEL_GAP -> Midi.armageddon
            else -> Midi.godslayer
        }
    }
}
