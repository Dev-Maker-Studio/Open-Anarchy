package com.rs.rsps.Power

import com.rs.game.content.combat.special_attacks.getSpec
import com.rs.game.model.entity.player.Equipment
import com.rs.game.model.entity.player.Player
import com.rs.game.model.item.ItemTooltips
import com.rs.game.model.item.TooltipSection
import com.rs.plugin.annotations.ServerStartupEvent
import org.darkan.core.cache.ItemDefinitions
import org.darkan.core.model.Item

private const val POWER_KEY = "Power"
private const val CODEX_KEY = "Codex"
private const val CODEX_WEAPON_KEY = "WeaponId"
private const val SPECIALS_PER_LINE = 2
private const val LABEL_INDENT = 16

@ServerStartupEvent
fun mapPowerTooltips() {
    ItemTooltips.register(TooltipSection.STATE, ::powerLine)
    ItemTooltips.register(TooltipSection.STATE, ::lifeBoostLine)
    ItemTooltips.register(TooltipSection.CONTENTS, ::weaponSpecialsLine)
    ItemTooltips.metadata(CODEX_KEY, TooltipSection.CONTENTS) { player, item, stored -> codexLines(player, item, stored) }
}

private fun percent(value: Double) = "%.2f".format(value)

private fun powerLine(player: Player, item: Item): String? {
    if (!item.definitions.isWearItem()) return null
    val power = Power.getCombatAmplificationPercentage(player, item.id)
    val tier = item.getMetaDataI(POWER_KEY, 0)
    if (power <= 0.0 && tier <= 0) return null
    val line = "Power: ${ItemTooltips.value("${percent(power)}%")}"
    return if (tier <= 0) line else "$line (tier ${ItemTooltips.value(tier)})"
}

private fun lifeBoostLine(player: Player, item: Item): String? {
    val boost = Power.ampedHPByPower(player, item)
    return if (boost <= 0) null else "Life points: ${ItemTooltips.value("+$boost")}"
}

private fun weaponSpecialsLine(player: Player, item: Item): String? {
    if (item.definitions.getEquipSlot() != Equipment.WEAPON) return null
    val lines = ArrayList<String>()
    val cost = getSpec(item.id)?.energyCost
    if (cost != null) {
        val reduced = Power.specialAttackCost(player, item.id, cost)
        if (reduced <= 0) lines += "Special: ${ItemTooltips.value("free")} (normally $cost% energy)"
        else if (reduced < cost) lines += "Special: ${ItemTooltips.value("$reduced% energy")} (normally $cost%)"
    }
    val key = item.id.toString()
    if (ItemScaling.checkMetaListForString(player.getO("weaponsWithSpecials"), key)) {
        val names = ItemScaling.getMetaListFromString(player.getO("${key}_specs"))
            .filter { it != item.id }
            .map { ItemDefinitions.getDefs(it).name }
            .distinct()
        names.chunked(SPECIALS_PER_LINE).forEachIndexed { index, chunk ->
            val label = if (index == 0) "Added specials: " else "".padEnd(LABEL_INDENT)
            lines += label + chunk.joinToString(", ") { ItemTooltips.value(it) }
        }
    }
    return lines.joinToString("<br>").ifEmpty { null }
}

private fun codexLines(player: Player, item: Item, stored: Any): String? {
    val type = stored as? String ?: return null
    val weaponId = (item.getMetaData(CODEX_WEAPON_KEY) as? Number)?.toInt()
    if (weaponId != null) {
        val cost = getSpec(weaponId)?.energyCost
        val weapon = ItemTooltips.value(ItemDefinitions.getDefs(weaponId).name)
        val energy = if (cost == null) "" else " (${cost}% energy)"
        return "Special attack codex<br>Grants $weapon's special$energy<br>Use on a weapon of the same type"
    }
    return "Codex of ${ItemTooltips.value(type)}<br>${codexEffect(type)}<br>${codexStatus(player, type)}"
}

private fun codexStatus(player: Player, type: String): String =
    "You have used ${ItemTooltips.value(SpecialItems.codexCount(player, type))} so far"

private fun codexEffect(type: String): String = when {
    type == "Health" -> "Permanently +5 maximum life points"
    type == "Prayer" -> "Permanently +1 prayer bonus"
    type == "Alchemy" -> "Permanently +1 coin per alchemy"
    type == "Weight" -> "Permanently -1 weight carried"
    type == "Agility" -> "Grants agility xp as you run"
    type == "Poison" -> "Heals you for the poison damage you take"
    type == "Dragon Fire" -> "Permanently -1 dragonfire damage taken"
    else -> "Permanently +1 ${type.lowercase()} bonus"
}
