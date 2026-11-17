package org.darkan.core.cache

import org.darkan.core.Logger.logInfo
import org.darkan.core.dungeon.Dungeon
import org.darkan.core.dungeon.DungeonGrid
import org.darkan.core.dungeon.DungeonLayout
import world.gregs.voidps.cache.MapSquare
import world.gregs.voidps.cache.definition.data.MapDefinition
import world.gregs.voidps.cache.definition.data.MapObject
import world.gregs.voidps.cache.source.codec.MapCodec
import world.gregs.voidps.cache.source.codec.MapJson
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object DungeonTerrain {
    private const val MAPS = "maps"
    private val TEMPLATE_REGIONS_X = 1..3
    private val TEMPLATE_REGIONS_Y = 30..87
    private const val REGION = DungeonGrid.REGION
    private const val BLOCKED_TILE = "s1"
    private const val VOID_TILE = "-"
    private const val UPPER_LEVELS = 3
    private const val EFFECTS = """[
    {"type": "environment", "sunColour": 9208680, "sunAmbient": 300, "sunLight": 420, "sunBacklight": 92, "sunPosition": [-4, -16, 1], "fogColour": 0, "fogDepth": 900}
  ]"""

    fun regions(dungeons: List<Dungeon>): Set<Int> = dungeons.flatMapTo(HashSet()) { dungeon ->
        squares(dungeon).map { (regionX, regionY) -> regionX shl 8 or regionY }
    }

    fun templateRegions(tree: File): Set<Int> = TEMPLATE_REGIONS_X.flatMapTo(HashSet()) { regionX ->
        TEMPLATE_REGIONS_Y.filter { regionY -> File(tree, "$MAPS/${regionX}_$regionY").isDirectory }.map { regionY -> regionX shl 8 or regionY }
    }

    private fun squares(dungeon: Dungeon): List<Pair<Int, Int>> {
        val regionX = dungeon.baseX / REGION
        val regionY = dungeon.baseY / REGION
        return (0 until DungeonGrid.REGIONS).flatMap { dx -> (0 until DungeonGrid.REGIONS).map { dy -> regionX + dx to regionY + dy } }
    }

    fun write(tree: File, dungeons: List<Dungeon>) {
        var written = 0
        for (dungeon in dungeons) {
            val layout = dungeon.layout
            for ((regionX, regionY) in squares(dungeon)) {
                val offsetX = regionX * REGION - dungeon.baseX
                val offsetY = regionY * REGION - dungeon.baseY
                if (store(tree, MapSquare.Square(MapSquare.Shape.TERRAIN, regionX, regionY), terrain(dungeon, layout, regionX, regionY, offsetX, offsetY))) written++
                if (store(tree, MapSquare.Square(MapSquare.Shape.LOCATIONS, regionX, regionY), locations(layout, regionX, regionY, offsetX, offsetY))) written++
            }
        }
        if (written > 0) logInfo("Wrote $written dungeon map files into ${tree.path}/$MAPS")
    }

    private fun store(tree: File, square: MapSquare.Square, content: ByteArray): Boolean {
        val file = File(tree, "$MAPS/${MapCodec.path(square)}")
        if (file.isFile && file.readBytes().contentEquals(content)) return false
        file.parentFile.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeBytes(content)
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return true
    }

    private fun terrain(dungeon: Dungeon, layout: DungeonLayout, regionX: Int, regionY: Int, offsetX: Int, offsetY: Int): ByteArray {
        val theme = dungeon.style
        val ground = rows { x, y ->
            val index = DungeonLayout.index(offsetX + x, offsetY + y)
            val overlay = when {
                !layout.floor[index] -> return@rows BLOCKED_TILE
                layout.arena[index] -> theme.arenaOverlay
                layout.channel[index] -> theme.channelOverlay
                else -> theme.overlay
            }
            if (overlay == null) "u${theme.underlay}" else "o$overlay/0/0,u${theme.underlay}"
        }
        val void = rows { _, _ -> VOID_TILE }
        val text = StringBuilder()
        text.append("{\n  \"region\": \"").append(regionX).append('_').append(regionY).append("\",\n  \"levels\": 4,\n  \"tiles\": [\n")
        text.append(ground)
        repeat(UPPER_LEVELS) { text.append(",\n").append(void) }
        text.append("\n  ],\n  \"effects\": ").append(EFFECTS).append("\n}\n")
        return text.toString().toByteArray(Charsets.UTF_8)
    }

    private fun rows(tile: (Int, Int) -> String): String =
        List(REGION) { x -> "      \"${List(REGION) { y -> tile(x, y) }.joinToString(" ")}\"" }.joinToString(",\n", prefix = "    [\n", postfix = "\n    ]")

    private fun locations(layout: DungeonLayout, regionX: Int, regionY: Int, offsetX: Int, offsetY: Int): ByteArray {
        val definition = MapDefinition(regionY + (regionX shl 8), levels = 0)
        for (piece in layout.pieces) {
            val x = piece.x - offsetX
            val y = piece.y - offsetY
            if (x !in 0 until REGION || y !in 0 until REGION) continue
            definition.objects.add(MapObject(piece.loc, x, y, piece.level, piece.shape, piece.rotation))
        }
        return MapJson.writeLocs(definition, MapSquare.Square(MapSquare.Shape.LOCATIONS, regionX, regionY))
    }
}
