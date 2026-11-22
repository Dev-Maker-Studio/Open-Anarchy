package com.rs.game.content.skills.prayer

import com.rs.game.content.world.generated.ChurchBlessing
import com.rs.game.model.entity.player.Player
import com.rs.game.model.entity.player.managers.PrayerManager.StatMod
import com.rs.game.model.item.ItemTooltips
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onLogin
import org.darkan.core.gameval.gen.Inv
import org.darkan.core.gameval.gen.Obj
import org.darkan.core.model.Item
import java.util.Locale

private const val BOOK_SLOTS = 30
private const val CURSE_SLOTS = 20
private const val VALUE_COLOUR = "2dc3ff"

object PrayerBoosts {
    private val boosts: Map<Prayer, Map<StatMod, Int>> = mapOf(
        Prayer.DEF_T1 to mapOf(StatMod.DEFENSE to 5),
        Prayer.STR_T1 to mapOf(StatMod.STRENGTH to 5),
        Prayer.ATK_T1 to mapOf(StatMod.ATTACK to 5),
        Prayer.RNG_T1 to mapOf(StatMod.RANGE to 5),
        Prayer.MAG_T1 to mapOf(StatMod.MAGE to 5),
        Prayer.DEF_T2 to mapOf(StatMod.DEFENSE to 10),
        Prayer.STR_T2 to mapOf(StatMod.STRENGTH to 10),
        Prayer.ATK_T2 to mapOf(StatMod.ATTACK to 10),
        Prayer.RNG_T2 to mapOf(StatMod.RANGE to 10),
        Prayer.MAG_T2 to mapOf(StatMod.MAGE to 10),
        Prayer.DEF_T3 to mapOf(StatMod.DEFENSE to 15),
        Prayer.STR_T3 to mapOf(StatMod.STRENGTH to 15),
        Prayer.ATK_T3 to mapOf(StatMod.ATTACK to 15),
        Prayer.RNG_T3 to mapOf(StatMod.RANGE to 15),
        Prayer.MAG_T3 to mapOf(StatMod.MAGE to 15),
        Prayer.CHIVALRY to mapOf(StatMod.ATTACK to 15, StatMod.STRENGTH to 18, StatMod.DEFENSE to 20),
        Prayer.PIETY to mapOf(StatMod.ATTACK to 20, StatMod.STRENGTH to 23, StatMod.DEFENSE to 25),
        Prayer.RIGOUR to mapOf(StatMod.RANGE to 20, StatMod.DEFENSE to 25),
        Prayer.AUGURY to mapOf(StatMod.MAGE to 20, StatMod.DEFENSE to 25),
        Prayer.LEECH_ATTACK to mapOf(StatMod.ATTACK to 5),
        Prayer.LEECH_RANGE to mapOf(StatMod.RANGE to 5),
        Prayer.LEECH_MAGIC to mapOf(StatMod.MAGE to 5),
        Prayer.LEECH_DEFENSE to mapOf(StatMod.DEFENSE to 6),
        Prayer.LEECH_STRENGTH to mapOf(StatMod.STRENGTH to 5),
        Prayer.TURMOIL to mapOf(StatMod.ATTACK to 15, StatMod.STRENGTH to 23, StatMod.DEFENSE to 15),
    )

    private val names = mapOf(StatMod.ATTACK to "Attack", StatMod.STRENGTH to "Strength", StatMod.DEFENSE to "Defence", StatMod.RANGE to "Ranged", StatMod.MAGE to "Magic")

    fun percent(prayer: Prayer, mod: StatMod): Int? = boosts[prayer]?.get(mod)

    fun serviceScale(player: Player): Double = ChurchBlessing.prayerScale(player)

    private fun percentText(value: Double) = "+" + String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.') + "%"

    private fun tooltip(prayer: Prayer?, scale: Double): String =
        boosts[prayer].orEmpty().map { (mod, percent) -> "${names[mod]}: <col=$VALUE_COLOUR>${percentText(percent * scale)}</col>" }.joinToString("<br>")

    fun send(player: Player) {
        val scale = serviceScale(player)
        player.packets.sendItems(Inv.prayer_boosts, Array<Item?>(BOOK_SLOTS + CURSE_SLOTS) { slot ->
            val prayer = if (slot < BOOK_SLOTS) Prayer.forSlot(slot, false) else Prayer.forSlot(slot - BOOK_SLOTS, true)
            Item(Obj.coins, 1).addMetaData(ItemTooltips.TEXT_KEY, tooltip(prayer, scale))
        })
    }
}

@ServerStartupEvent
fun mapPrayerBoosts() {
    onLogin { (player) -> PrayerBoosts.send(player) }
}
