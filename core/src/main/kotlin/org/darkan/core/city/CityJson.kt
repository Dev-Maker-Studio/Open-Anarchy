package org.darkan.core.city

import com.google.gson.GsonBuilder
import java.io.File

object CityJson {
    private const val EXTENSION = "json"
    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun encode(city: City): String = gson.toJson(city)

    fun decode(text: String): City = gson.fromJson(text, City::class.java)

    fun write(directory: File, city: City) {
        directory.mkdirs()
        File(directory, "${city.id}.$EXTENSION").writeText(encode(city))
    }

    fun readAll(directory: File): List<City> =
        directory.listFiles { file -> file.extension == EXTENSION }.orEmpty().sortedBy { it.name }.map { decode(it.readText()) }
}
