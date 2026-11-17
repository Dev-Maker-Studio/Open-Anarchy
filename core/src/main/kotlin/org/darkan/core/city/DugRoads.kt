package org.darkan.core.city

import com.google.gson.Gson
import org.darkan.core.EnvVars
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object DugRoads {
    private const val AXIS_BITS = 14
    private const val AXIS_MASK = (1 shl AXIS_BITS) - 1
    private val gson = Gson()
    private val file = File("./data/roads/sector-${EnvVars.worldId}.json")
    private val tiles: MutableSet<Int> by lazy { load() }

    private fun load(): MutableSet<Int> =
        if (file.isFile) gson.fromJson(file.readText(), IntArray::class.java).toHashSet() else HashSet()

    private fun key(x: Int, y: Int) = x shl AXIS_BITS or y

    @Synchronized
    fun contains(x: Int, y: Int): Boolean = key(x, y) in tiles

    @Synchronized
    fun add(x: Int, y: Int): Boolean {
        if (!tiles.add(key(x, y))) return false
        file.parentFile.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(gson.toJson(tiles.sorted()))
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return true
    }

    @Synchronized
    fun within(minX: Int, minY: Int, maxX: Int, maxY: Int): List<Pair<Int, Int>> =
        tiles.map { (it shr AXIS_BITS) to (it and AXIS_MASK) }.filter { (x, y) -> x in minX..maxX && y in minY..maxY }
}
