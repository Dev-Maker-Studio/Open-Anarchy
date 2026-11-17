package org.darkan.core.dungeon

import com.google.gson.GsonBuilder
import org.darkan.core.Logger.logInfo
import org.darkan.core.city.City
import world.gregs.voidps.cache.source.codec.WorldGenerator
import java.io.File

object DungeonRegistry {
    const val DIRECTORY = "./data/dungeons"
    private const val EXTENSION = "json"
    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun encode(dungeon: Dungeon): String = gson.toJson(dungeon) + "\n"

    fun decode(text: String): Dungeon = gson.fromJson(text, Dungeon::class.java)

    fun readAll(directory: File = File(DIRECTORY)): List<Dungeon> =
        directory.listFiles { file -> file.isFile && file.extension == EXTENSION }.orEmpty().sortedBy { it.name }.map { decode(it.readText()) }

    fun write(dungeon: Dungeon, directory: File = File(DIRECTORY)) {
        directory.mkdirs()
        File(directory, "${dungeon.id}.$EXTENSION").writeText(encode(dungeon))
    }

    fun load(
        generator: WorldGenerator, cities: List<City>, lodestones: () -> List<Pair<Int, Int>>, spawnX: Int, spawnY: Int, low: Int, high: Int
    ): List<Dungeon> {
        val saved = readAll()
        val missing = DungeonSites.origins(cities).filter { origin -> saved.none { it.origin == origin } }
        if (missing.isEmpty()) return saved
        val sites = lodestones()
        val planned = ArrayList<Dungeon>()
        val slots = saved.mapTo(HashSet()) { it.slot }
        for (origin in missing) {
            val site = DungeonSites.find(origin, generator, cities, sites, spawnX, spawnY, low, high)
            if (site == null) {
                logInfo("No dungeon site found for $origin")
                continue
            }
            val slot = generateSequence(0) { it + 1 }.first { it !in slots }
            slots += slot
            val dungeon = Dungeon(origin.substringAfter(':'), site.name, site.theme.id, site.seed, slot, site.x, site.y, origin)
            write(dungeon)
            planned += dungeon
        }
        if (planned.isNotEmpty()) logInfo("Planned ${planned.size} dungeons: ${planned.joinToString { "${it.name} at ${it.entranceX},${it.entranceY}" }}")
        return saved + planned
    }
}
