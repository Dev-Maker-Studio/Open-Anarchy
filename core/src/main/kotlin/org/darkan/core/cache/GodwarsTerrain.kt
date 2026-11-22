package org.darkan.core.cache

import org.darkan.core.Logger.logInfo
import org.darkan.core.dungeon.GodwarsTemplate
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object GodwarsTerrain {
    private const val SOURCE = "./data/godwars-template"
    private const val MAPS = "maps"
    private val FILES = listOf("terrain.json", "locs.json")
    private val REGION_NAME = Regex("\"region\": \"\\d+_\\d+\"")

    fun write(tree: File) {
        var written = 0
        for (dx in 0 until GodwarsTemplate.REGIONS_X) for (dy in 0 until GodwarsTemplate.REGIONS_Y) {
            val square = "${GodwarsTemplate.REGION_X + dx}_${GodwarsTemplate.REGION_Y + dy}"
            for (name in FILES) {
                val source = File(SOURCE, "${dx}_$dy/$name")
                check(source.isFile) { "The God Wars Dungeon template is missing ${source.path}" }
                val content = REGION_NAME.replaceFirst(source.readText(), "\"region\": \"$square\"").toByteArray(Charsets.UTF_8)
                if (store(File(tree, "$MAPS/$square/$name"), content)) written++
            }
        }
        if (written > 0) logInfo("Wrote $written God Wars Dungeon template map files into ${tree.path}/$MAPS")
    }

    private fun store(file: File, content: ByteArray): Boolean {
        if (file.isFile && file.readBytes().contentEquals(content)) return false
        file.parentFile.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeBytes(content)
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return true
    }
}
