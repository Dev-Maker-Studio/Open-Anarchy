package com.rs.utils.music

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

    @JvmStatic
    fun songsOf(biome: Biome): List<Int> = pools[biome]?.takeIf { it.isNotEmpty() } ?: pools[Biome.PLAINS].orEmpty()

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
