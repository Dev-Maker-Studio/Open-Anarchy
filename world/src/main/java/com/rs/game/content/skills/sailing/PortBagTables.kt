package com.rs.game.content.skills.sailing

import org.darkan.core.gameval.gen.Obj
import java.util.Random

class BagEntry(val item: Int, val min: Int, val max: Int, val weight: Double)

object PortBagTables {
    private const val ORE_MIN = 10
    private const val ORE_MAX = 20
    private const val BALL_MIN = 40
    private const val BALL_MAX = 60
    private const val NAIL_MIN = 20
    private const val NAIL_MAX = 30
    private const val KIT_MIN = 5
    private const val KIT_MAX = 15

    private fun coins(min: Int, max: Int, weight: Double) = BagEntry(Obj.coins, min, max, weight)

    private fun noted(item: Int, weight: Double, min: Int = ORE_MIN, max: Int = ORE_MAX) = BagEntry(item, min, max, weight)

    private fun balls(item: Int, weight: Double) = BagEntry(item, BALL_MIN, BALL_MAX, weight)

    private fun nails(item: Int, weight: Double) = BagEntry(item, NAIL_MIN, NAIL_MAX, weight)

    private fun kits(item: Int, weight: Double) = BagEntry(item, KIT_MIN, KIT_MAX, weight)

    private val TABLES: Map<BagTier, List<BagEntry>> = mapOf(
        BagTier.TINY to listOf(
            coins(5_000, 12_500, 6.0),
            noted(Obj.cert_copper_ore, 4.0), noted(Obj.cert_tin_ore, 4.0), noted(Obj.cert_iron_ore, 4.0), noted(Obj.cert_coal, 4.0),
            balls(Obj.sailing_cannonball_bronze, 4.0), balls(Obj.sailing_cannonball_iron, 4.0), balls(Obj.sailing_cannonball_steel, 4.0), balls(Obj.sailing_cannonball_mithril, 4.0),
            nails(Obj.nails_bronze, 4.0), nails(Obj.nails_iron, 4.0), nails(Obj.nails, 4.0), nails(Obj.nails_mithril, 4.0),
            noted(Obj.cert_woodplank, 4.0), noted(Obj.cert_plank_oak, 4.0), noted(Obj.cert_plank_teak, 3.0),
            noted(Obj.cert_bronze_bar, 3.0), noted(Obj.cert_iron_bar, 3.0), noted(Obj.cert_steel_bar, 3.0),
            kits(Obj.sailing_repair_kit_wooden, 3.0), kits(Obj.sailing_repair_kit_oak, 3.0), kits(Obj.sailing_repair_kit_teak, 3.0)
        ),
        BagTier.SMALL to listOf(
            coins(10_000, 17_500, 6.0),
            noted(Obj.cert_iron_ore, 4.0), noted(Obj.cert_coal, 4.0), noted(Obj.cert_mithril_ore, 4.0),
            balls(Obj.sailing_cannonball_iron, 4.0), balls(Obj.sailing_cannonball_steel, 4.0), balls(Obj.sailing_cannonball_mithril, 4.0),
            nails(Obj.nails_iron, 4.0), nails(Obj.nails, 4.0), nails(Obj.nails_mithril, 4.0),
            noted(Obj.cert_plank_oak, 4.0), noted(Obj.cert_plank_teak, 4.0), noted(Obj.cert_plank_mahogany, 3.0, KIT_MIN, KIT_MAX),
            noted(Obj.cert_iron_bar, 3.0), noted(Obj.cert_steel_bar, 3.0), noted(Obj.cert_mithril_bar, 3.0),
            kits(Obj.sailing_repair_kit_oak, 3.0), kits(Obj.sailing_repair_kit_teak, 3.0), kits(Obj.sailing_repair_kit_mahogany, 3.0)
        ),
        BagTier.MEDIUM to listOf(
            coins(15_000, 25_000, 8.0),
            noted(Obj.cert_coal, 4.0), noted(Obj.cert_mithril_ore, 4.0), noted(Obj.cert_adamantite_ore, 4.0),
            balls(Obj.sailing_cannonball_steel, 4.0), balls(Obj.sailing_cannonball_mithril, 4.0), balls(Obj.sailing_cannonball_adamant, 4.0),
            nails(Obj.nails, 4.0), nails(Obj.nails_mithril, 4.0), nails(Obj.obj_4823, 4.0),
            noted(Obj.cert_plank_oak, 4.0), noted(Obj.cert_plank_teak, 4.0), noted(Obj.cert_plank_mahogany, 3.0, KIT_MIN, KIT_MAX),
            kits(Obj.sailing_repair_kit_oak, 3.0), kits(Obj.sailing_repair_kit_teak, 3.0), kits(Obj.sailing_repair_kit_mahogany, 3.0),
            noted(Obj.cert_steel_bar, 3.0), noted(Obj.cert_mithril_bar, 3.0), noted(Obj.cert_adamantite_bar, 2.0)
        ),
        BagTier.LARGE to listOf(
            coins(22_500, 32_500, 8.0),
            noted(Obj.cert_coal, 4.0), noted(Obj.cert_adamantite_ore, 4.0),
            balls(Obj.sailing_cannonball_mithril, 4.0), balls(Obj.sailing_cannonball_adamant, 4.0), balls(Obj.sailing_cannonball_rune, 3.0),
            nails(Obj.nails_mithril, 4.0), nails(Obj.obj_4823, 4.0), nails(Obj.nails_rune, 3.0),
            noted(Obj.cert_plank_teak, 4.0), noted(Obj.cert_plank_mahogany, 4.0), noted(Obj.cert_plank_mahogany, 3.0, KIT_MIN, KIT_MAX),
            kits(Obj.sailing_repair_kit_teak, 3.0), kits(Obj.sailing_repair_kit_mahogany, 3.0), kits(Obj.sailing_repair_kit_camphor, 3.0),
            noted(Obj.cert_mithril_bar, 3.0), noted(Obj.cert_adamantite_bar, 3.0),
            noted(Obj.cert_runite_ore, 2.0), noted(Obj.cert_runite_bar, 1.0)
        ),
        BagTier.HUGE to listOf(
            coins(40_000, 60_000, 12.0),
            noted(Obj.cert_plank_mahogany, 6.0), noted(Obj.cert_plank_mahogany, 6.0), noted(Obj.cert_plank_mahogany, 5.0, KIT_MIN, KIT_MAX),
            balls(Obj.sailing_cannonball_adamant, 6.0), balls(Obj.sailing_cannonball_rune, 5.0), balls(Obj.sailing_cannonball_dragon, 3.0),
            nails(Obj.obj_4823, 6.0), nails(Obj.nails_rune, 5.0),
            noted(Obj.cert_adamantite_ore, 5.0), noted(Obj.cert_coal, 5.0, 30, 50), noted(Obj.cert_runite_ore, 4.0, 5, 10),
            kits(Obj.sailing_repair_kit_mahogany, 5.0), kits(Obj.sailing_repair_kit_camphor, 5.0), kits(Obj.sailing_repair_kit_ironwood, 5.0),
            noted(Obj.cert_mithril_bar, 5.0), noted(Obj.cert_adamantite_bar, 4.0), noted(Obj.cert_runite_bar, 3.0, 5, 10)
        )
    )

    fun table(tier: BagTier): List<BagEntry> = TABLES.getValue(tier)

    fun roll(tier: BagTier, random: Random): Pair<Int, Int> {
        val entries = table(tier)
        var pick = random.nextDouble() * entries.sumOf { it.weight }
        val entry = entries.first { pick -= it.weight; pick < 0.0 }
        return entry.item to entry.min + random.nextInt(entry.max - entry.min + 1)
    }
}
