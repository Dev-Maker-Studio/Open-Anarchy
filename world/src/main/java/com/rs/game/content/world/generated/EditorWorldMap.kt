package com.rs.game.content.world.generated

import com.google.gson.Gson
import com.rs.plugin.annotations.ServerStartupEvent
import org.darkan.core.EnvVars
import org.darkan.core.Logger
import org.darkan.core.map.GeneratedWorld
import org.darkan.core.map.WorldWindow
import world.gregs.voidps.cache.source.codec.WorldGenerator.Biome
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.concurrent.thread

private const val STEP = 10
private const val ROAD_FLAG = 0x40
private const val DIRECTORY = "data/editors"

class EditorMapCity(val name: String, val x: Int, val y: Int, val half: Int)

class EditorMapDungeon(val name: String, val theme: String, val x: Int, val y: Int)

class EditorMapInfo(
    val size: Int,
    val step: Int,
    val roadFlag: Int,
    val centreX: Int,
    val centreY: Int,
    val ringTiles: Int,
    val tilesPerLevel: Int,
    val firstLevel: Int,
    val biomes: List<String>,
    val cities: List<EditorMapCity>,
    val dungeons: List<EditorMapDungeon>,
)

private fun replace(name: String, bytes: ByteArray) {
    val target = File(DIRECTORY, name)
    val staged = File(DIRECTORY, "$name.tmp")
    staged.writeBytes(bytes)
    Files.move(staged.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
}

private fun export() {
    val started = System.currentTimeMillis()
    val generator = GeneratedWorld.generator
    val side = WorldWindow.WINDOW_TILES / STEP
    val raster = ByteArray(side * side)
    for (row in 0 until side) for (column in 0 until side) {
        val x = column * STEP
        val y = row * STEP
        val road = if (generator.roadTouches(x, y, x + STEP - 1, y + STEP - 1)) ROAD_FLAG else 0
        raster[row * side + column] = (generator.biomeAt(x, y).ordinal or road).toByte()
    }
    val info = EditorMapInfo(
        WorldWindow.WINDOW_TILES, STEP, ROAD_FLAG, GeneratedWorld.spawnX, GeneratedWorld.spawnY, SPAWN_RING_TILES, TILES_PER_LEVEL, FIRST_LEVEL,
        Biome.entries.map { it.name },
        GeneratedWorld.cities.map { EditorMapCity(it.name, it.centerX, it.centerY, it.halfSize) },
        GeneratedWorld.dungeons.map { EditorMapDungeon(it.name, it.style.name, it.entranceX, it.entranceY) },
    )
    File(DIRECTORY).mkdirs()
    replace("world-map.bin", raster)
    replace("world-map.json", Gson().toJson(info).toByteArray())
    Logger.info(EditorMapInfo::class.java, "export", "Editor world map written in ${System.currentTimeMillis() - started}ms")
}

@ServerStartupEvent
fun exportEditorWorldMap() {
    if (!EnvVars.generateMaps) return
    thread(isDaemon = true, name = "editor-world-map", priority = Thread.MIN_PRIORITY) {
        try {
            export()
        } catch (e: Exception) {
            Logger.handle(EditorMapInfo::class.java, "export", e)
        }
    }
}
