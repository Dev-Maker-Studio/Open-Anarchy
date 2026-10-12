package com.rs.game.content.world.generated

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.File
import kotlin.math.abs

object HostileMobList {
    private const val PATH = "data/npcs/hostile_mobs.json"

    data class Entry(@SerializedName("ID") val id: Int, val name: String, val level: Int)

    val entries: List<Entry> by lazy { Gson().fromJson(File(PATH).readText(), Array<Entry>::class.java).toList() }

    fun between(low: Int, high: Int): List<Entry> = entries.filter { it.level in low..high }

    fun nearest(level: Int): List<Entry> {
        val closest = entries.minOf { abs(it.level - level) }
        return entries.filter { abs(it.level - level) == closest }
    }
}
