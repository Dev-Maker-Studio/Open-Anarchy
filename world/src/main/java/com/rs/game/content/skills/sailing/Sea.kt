package com.rs.game.content.skills.sailing

import org.darkan.core.gameval.gen.Loc
import org.darkan.core.gameval.gen.Obj
import world.gregs.voidps.cache.source.codec.WorldGenerator
import java.util.Random

object Seas {
    const val BLOCK = 512
    private val DESCRIPTORS = listOf("Azure", "Pale", "Sapphire", "Drowned", "Whispering", "Grey", "Amber", "Silent", "Stormy", "Jade", "Crimson", "Lonely")
    private val NOUNS = listOf("Deep", "Reach", "Sound", "Shallows", "Gulf", "Expanse", "Strait", "Basin")
    private const val NAME_MIX = 0x5EA5L
    private const val DESCRIPTOR_SHIFT = 8

    fun key(x: Int, y: Int): String = "${PortSites.sector}:${Math.floorDiv(x, BLOCK)}:${Math.floorDiv(y, BLOCK)}"

    fun nameAt(x: Int, y: Int): String {
        val random = Random(WorldGenerator.DEFAULT_SEED xor NAME_MIX xor (Math.floorDiv(x, BLOCK).toLong() shl DESCRIPTOR_SHIFT) xor Math.floorDiv(y, BLOCK).toLong())
        return "${DESCRIPTORS[random.nextInt(DESCRIPTORS.size)]} ${NOUNS[random.nextInt(NOUNS.size)]} (${PortSites.sector})"
    }

    fun pointsIn(x: Int, y: Int): List<ChartPoint> {
        val blockX = Math.floorDiv(x, BLOCK) * BLOCK
        val blockY = Math.floorDiv(y, BLOCK) * BLOCK
        val key = key(x, y)
        val cells = BLOCK / ChartField.CELL
        return (0 until cells).flatMap { cx ->
            (0 until cells).mapNotNull { cy ->
                val cellX = blockX / ChartField.CELL + cx
                val cellY = blockY / ChartField.CELL + cy
                ChartField.pointIn(WorldGenerator.DEFAULT_SEED, key, cellX, cellY, PortSites.levelAt(cellX * ChartField.CELL, cellY * ChartField.CELL))
                    ?.takeIf { clearWater(it.x, it.y) }
            }
        }
    }

    fun clearWater(x: Int, y: Int): Boolean =
        PortSites.sailable(x, y) && PortSites.sailable(x + 2, y) && PortSites.sailable(x - 2, y) && PortSites.sailable(x, y + 2) && PortSites.sailable(x, y - 2)
}

class WreckSite(val x: Int, val y: Int, val wreck: Wreck) {
    val key: String get() = "w:$x:$y"
}

object WreckField {
    const val CELL = 48
    private const val PERCENT = 6
    private const val SALT = 0x57524B4CL
    private const val MIX_X = 0x9E3779B97F4A7C15uL
    val LOCS = intArrayOf(
        Loc.deadly_wreckage_1, Loc.deadly_wreckage_2, Loc.deadly_wreckage_3, Loc.deadly_wreckage_4,
        Loc.deadly_wreckage_5, Loc.deadly_wreckage_6, Loc.deadly_wreckage_7, Loc.deadly_wreckage_8
    )

    fun at(cellX: Int, cellY: Int): WreckSite? {
        val random = Random(WorldGenerator.DEFAULT_SEED xor SALT xor (cellX * MIX_X.toLong()) xor (cellY.toLong() shl 20))
        if (random.nextInt(100) >= PERCENT) return null
        val x = cellX * CELL + random.nextInt(CELL)
        val y = cellY * CELL + random.nextInt(CELL)
        if (!Seas.clearWater(x, y)) return null
        val cap = Wreck.forLevelIndicator(PortSites.levelAt(x, y)).ordinal
        return WreckSite(x, y, Wreck.entries[random.nextInt(cap + 1)])
    }
}

object SalvageLoot {
    private class Entry(val item: Int, val min: Int, val max: Int, val weight: Int)

    private val TABLES: Map<Wreck, List<Entry>> = mapOf(
        Wreck.SMALL to listOf(Entry(Obj.woodplank, 1, 3, 40), Entry(Obj.nails_bronze, 5, 15, 30), Entry(Obj.bronze_bar, 1, 2, 20), Entry(Obj.logs, 2, 5, 10)),
        Wreck.FISHERMANS to listOf(Entry(Obj.raw_tuna, 1, 3, 35), Entry(Obj.plank_oak, 1, 3, 30), Entry(Obj.nails_iron, 5, 15, 25), Entry(Obj.iron_bar, 1, 2, 10)),
        Wreck.BARRACUDA to listOf(Entry(Obj.raw_swordfish, 1, 3, 30), Entry(Obj.plank_teak, 1, 3, 30), Entry(Obj.steel_bar, 1, 3, 25), Entry(Obj.casket, 1, 1, 15)),
        Wreck.LARGE to listOf(Entry(Obj.raw_shark, 1, 2, 25), Entry(Obj.plank_mahogany, 1, 3, 30), Entry(Obj.mithril_bar, 1, 3, 30), Entry(Obj.casket, 1, 1, 15)),
        Wreck.PIRATE to listOf(Entry(Obj.nails_mithril, 8, 20, 25), Entry(Obj.adamantite_bar, 1, 3, 30), Entry(Obj.gold_bar, 1, 2, 25), Entry(Obj.casket, 1, 1, 20)),
        Wreck.MERCENARY to listOf(Entry(Obj.adamantite_bar, 2, 4, 30), Entry(Obj.gold_bar, 2, 4, 30), Entry(Obj.raw_shark, 2, 4, 20), Entry(Obj.casket, 1, 2, 20)),
        Wreck.FREMENNIK to listOf(Entry(Obj.runite_bar, 1, 2, 25), Entry(Obj.nails_rune, 8, 20, 25), Entry(Obj.gold_bar, 2, 5, 30), Entry(Obj.casket, 1, 2, 20)),
        Wreck.MERCHANT to listOf(Entry(Obj.runite_bar, 1, 3, 30), Entry(Obj.gold_bar, 3, 6, 30), Entry(Obj.raw_shark, 3, 6, 20), Entry(Obj.casket, 1, 3, 20))
    )
    private const val LEVEL_STEP = 10

    fun roll(wreck: Wreck, threat: Int, random: Random): Pair<Int, Int> {
        val table = TABLES.getValue(wreck)
        var pick = random.nextInt(table.sumOf { it.weight })
        val entry = table.first { pick -= it.weight; pick < 0 }
        val base = entry.min + random.nextInt(entry.max - entry.min + 1)
        return entry.item to base + base * threat / LEVEL_STEP
    }
}
