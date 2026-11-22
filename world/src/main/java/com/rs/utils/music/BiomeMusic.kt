package com.rs.utils.music

import org.darkan.core.EnvVars
import com.google.gson.Gson
import org.darkan.core.gameval.gen.Midi
import java.io.File
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

    private const val BANNED_PATH = "./data/music/banned.json"

    private class BannedSong(val id: Int, val name: String?)

    val banned: Set<Int> by lazy {
        File(BANNED_PATH).takeIf { it.isFile }?.let { Gson().fromJson(it.readText(), Array<BannedSong>::class.java) }.orEmpty().mapTo(HashSet()) { it.id }
    }

    @JvmStatic
    fun playable(song: Int): Boolean = song !in banned && Music.getSong(song) != null

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
        byBiome.mapValues { (_, songs) -> songs.filter(::playable) }
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
        ),
        Biome.CAVE to listOf(
            Midi.cavern, Midi.midi_325, Midi.midi_357, Midi.midi_389, Midi.midi_678, Midi.subterranea, Midi.midi_448,
            Midi.midi_322, Midi.midi_278, Midi.chamber, Midi.dunjun, Midi.midi_632, Midi.intrepid, Midi.midi_595
        ),
        Biome.FORTRESS to listOf(
            Midi.knightmare, Midi.royale, Midi.midi_160, Midi.midi_67, Midi.midi_214, Midi.midi_439, Midi.midi_537,
            Midi.midi_558, Midi.midi_560, Midi.midi_339, Midi.regal, Midi.midi_63, Midi.cursed
        ),
        Biome.SEWER to listOf(
            Midi.midi_144, Midi.midi_361, Midi.midi_481, Midi.midi_482, Midi.midi_489, Midi.midi_490, Midi.malady,
            Midi.stagnant, Midi.midi_342, Midi.lumbcat_farm, Midi.midi_573
        )
    )

    private val bossFights = listOf(Midi.showdown, Midi.midi_727, Midi.surok_king_battle_music, Midi.vkq3_decision_time_boss_battle)

    @JvmStatic
    fun bossSongs(): List<Int> = bossFights.filter(::playable)

    @JvmStatic
    fun bossSong(): Int = bossSongs().randomOrNull() ?: NO_SONG

    @JvmStatic
    fun generatedSongs(biome: Biome): List<Int> = generated[biome].orEmpty().filter(::playable)

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
        val song = when {
            gap <= NEAR_LEVEL_GAP -> Midi.attack3
            gap <= FAR_LEVEL_GAP -> Midi.armageddon
            else -> Midi.godslayer
        }
        return if (song in banned) NO_SONG else song
    }
}
