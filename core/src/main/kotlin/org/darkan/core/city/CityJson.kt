package org.darkan.core.city

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

object CityJson {
    private const val EXTENSION = "json"
    private const val INDENT = "  "
    private val LISTS = listOf("streets", "buildings", "residents")
    private val gson = Gson()

    fun encode(city: City): String = StringBuilder().also { write(it, gson.toJsonTree(city), 0) }.append('\n').toString()

    fun decode(text: String): City {
        val json = JsonParser.parseString(text).asJsonObject
        for (list in LISTS) if (!json.has(list)) json.add(list, JsonArray())
        return gson.fromJson(json, City::class.java)
    }

    fun write(directory: File, city: City) {
        directory.mkdirs()
        File(directory, "${city.id}.$EXTENSION").writeText(encode(city))
    }

    fun readAll(directory: File): List<City> =
        directory.listFiles { file -> file.extension == EXTENSION }.orEmpty().sortedBy { it.name }.map { decode(it.readText()) }

    private fun flat(element: JsonElement): Boolean = when (element) {
        is JsonObject -> element.entrySet().none { it.value is JsonObject || it.value is JsonArray }
        is JsonArray -> element.none { it is JsonObject || it is JsonArray }
        else -> true
    }

    private fun write(out: StringBuilder, element: JsonElement, depth: Int) {
        if (flat(element)) {
            out.append(inline(element))
            return
        }
        val pad = INDENT.repeat(depth + 1)
        if (element is JsonArray) {
            out.append("[\n")
            element.forEachIndexed { index, child ->
                out.append(pad)
                write(out, child, depth + 1)
                out.append(if (index < element.size() - 1) ",\n" else "\n")
            }
            out.append(INDENT.repeat(depth)).append(']')
            return
        }
        val entries = (element as JsonObject).entrySet().toList()
        val simple = entries.filter { flat(it.value) && it.value !is JsonArray }
        val nested = entries - simple.toSet()
        out.append("{\n").append(pad).append(simple.joinToString(", ") { "${gson.toJson(it.key)}: ${inline(it.value)}" })
        for (entry in nested) {
            out.append(",\n").append(pad).append(gson.toJson(entry.key)).append(": ")
            write(out, entry.value, depth + 1)
        }
        out.append('\n').append(INDENT.repeat(depth)).append('}')
    }

    private fun inline(element: JsonElement): String = when (element) {
        is JsonObject -> element.entrySet().joinToString(", ", "{", "}") { "${gson.toJson(it.key)}: ${inline(it.value)}" }
        is JsonArray -> element.joinToString(", ", "[", "]") { inline(it) }
        else -> gson.toJson(element)
    }
}
