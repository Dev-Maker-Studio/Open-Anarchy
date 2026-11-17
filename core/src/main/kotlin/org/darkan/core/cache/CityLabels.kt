package org.darkan.core.cache

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.darkan.core.city.City
import java.io.File

object CityLabels {
    private const val TEMPLATE = 164
    private const val FIRST_ELEMENT = 1220
    private const val ELEMENTS = "config/mapelement"
    private const val STATIC = "worldmapdata/main_staticelements"
    private const val POSITION_SHIFT = 14
    private const val MEMBERS = 0
    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun write(tree: File, cities: List<City>) {
        val template = JsonParser.parseString(File(tree, "$ELEMENTS/$TEMPLATE.json").readText()).asJsonObject
        cities.forEachIndexed { index, city ->
            val element = template.deepCopy()
            element.addProperty("id", FIRST_ELEMENT + index)
            element.addProperty("name", city.name)
            replace(File(tree, "$ELEMENTS/${FIRST_ELEMENT + index}.json"), element)
            val placed = JsonObject()
            placed.addProperty("id", index)
            placed.addProperty("position", city.centerX shl POSITION_SHIFT or city.centerY)
            placed.addProperty("mapElement", FIRST_ELEMENT + index)
            placed.addProperty("members", MEMBERS)
            replace(File(tree, "$STATIC/$index.json"), placed)
        }
        File(tree, STATIC).listFiles().orEmpty().filter { (it.nameWithoutExtension.toIntOrNull() ?: -1) >= cities.size }.forEach { it.delete() }
    }

    private fun replace(file: File, json: JsonObject) {
        val text = gson.toJson(json) + "\n"
        if (file.isFile && file.readText() == text) return
        file.parentFile.mkdirs()
        file.writeText(text)
    }
}
