package com.rs.rsps.Power

import com.rs.Settings
import com.rs.game.content.ItemConstants.ItemDegrade
import com.rs.game.content.combat.CombatMod
import com.rs.game.content.combat.onCombatFormulaAdjust
import com.rs.game.model.entity.player.Equipment
import com.rs.game.model.entity.player.Player
import com.rs.game.model.entity.player.Skills
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.utils.Ticks
import org.darkan.core.Utils
import org.darkan.core.cache.ItemDefinitions
import org.darkan.core.gameval.gen.Obj
import org.darkan.core.model.Item
import kotlin.math.ceil

private const val POWER_ROLL = 40
private const val COOKING_POWER_ROLL = 25
private const val AGILITY_POWER_XP = 13_000_000.0
private const val ALCHEMY_COINS = "AlchemyCoins"
private const val ALCHEMY_COINS_PER_BONUS = 200_000L

private val DOMINION_GLOVES = intArrayOf(Obj.dom_gloves_melee_col1, Obj.dom_gloves_mage_col1, Obj.dom_gloves_range_col1)
private val ZARYTE_BOWS = intArrayOf(Obj.godwars2_zaryte_bow_100, Obj.godwars2_zaryte_bow_50, Obj.godwars2_zaryte_bow_0)
private val DEFENCE_SLOTS = intArrayOf(
    Equipment.HEAD, Equipment.CAPE, Equipment.NECK, Equipment.CHEST, Equipment.SHIELD,
    Equipment.LEGS, Equipment.HANDS, Equipment.FEET, Equipment.RING,
)
private val SHARED_POWER = listOf(
    intArrayOf(Obj.dragonfire_shield, Obj.dragonfire_shield_uncharged),
    intArrayOf(Obj.ranger_ring, Obj.mob_ranger_ring),
    intArrayOf(Obj.warrior_ring, Obj.mob_warrior_ring),
    intArrayOf(Obj.berzerker_ring, Obj.mob_berzerker_ring),
)

private class HpBoost(val amount: Int, val slotByEquipId: Boolean = false)

private val HP_BOOSTS: Map<Int, HpBoost> = buildMap {
    fun boost(amount: Int, vararg ids: Int, slotByEquipId: Boolean = false) = ids.forEach { put(it, HpBoost(amount, slotByEquipId)) }
    boost(
        66, Obj.enchanted_onyx_amulet, Obj.glacor_melee_boots, Obj.glacor_range_boots, Obj.glacor_magic_boots,
        Obj.godwars_armadyl_completed_godsword, Obj.godwars_bandos_completed_godsword, Obj.godwars_saradomin_completed_godsword,
        Obj.godwars_zamorak_completed_godsword, Obj.godwars2_helm_melee_100, Obj.godwars2_helm_melee_50, Obj.godwars2_helm_range_100,
        Obj.godwars2_helm_range_50, Obj.godwars2_helm_magic_100, Obj.godwars2_helm_magic_50,
    )
    boost(
        200, Obj.godwars2_body_melee_100, Obj.godwars2_body_melee_50, Obj.godwars2_body_range_100,
        Obj.godwars2_body_range_50, Obj.godwars2_body_magic_100, Obj.godwars2_body_magic_50,
    )
    boost(
        134, Obj.qbd2_crossbow_forged, Obj.sum2_spirit_shield_arcane, Obj.sum2_spirit_shield_elysian, Obj.sum2_spirit_shield_spectral,
        Obj.godwars2_zaryte_bow_100, Obj.godwars2_zaryte_bow_50, Obj.godwars2_legs_melee_100, Obj.godwars2_legs_melee_50,
        Obj.godwars2_legs_range_100, Obj.godwars2_legs_range_50, Obj.godwars2_legs_magic_100, Obj.godwars2_legs_magic_50,
    )
    boost(
        25, Obj.obj_23659, Obj.ranger_ring, Obj.berzerker_ring, Obj.warrior_ring, Obj.seer_ring, Obj.dragonfire_shield_uncharged,
        Obj.godwars2_gloves_range_100, Obj.godwars2_gloves_range_50, Obj.godwars2_gloves_melee_100, Obj.godwars2_gloves_melee_50,
        Obj.godwars2_gloves_magic_100, Obj.godwars2_gloves_magic_50, Obj.godwars2_boots_melee_100, Obj.godwars2_boots_melee_50,
        Obj.godwars2_boots_magic_100, Obj.godwars2_boots_magic_50, Obj.godwars2_boots_range_100, Obj.godwars2_boots_range_50,
        Obj.godwars2_gloves_range_100_new, Obj.godwars2_gloves_melee_100_new, Obj.godwars2_gloves_magic_100_new,
        Obj.godwars2_boots_melee_100_new, Obj.godwars2_boots_magic_100_new, Obj.godwars2_boots_range_100_new,
        Obj.godwars_amulet_magic, Obj.godwars_amulet_range, Obj.godwars_amulet_melee,
    )
    boost(
        66 / 5, Obj.godwars_bandos_helm, Obj.godwars_bandos_shield, Obj.godwars_armor_armadyl_helmet, Obj.godwars_armadyl_shield,
        Obj.godwars_zamorak_robe_head, Obj.godwars_zamorak_shield, Obj.godwars_saradomin_sword, slotByEquipId = true,
    )
    boost(200 / 5, Obj.godwars_armor_bandos_chestplate, Obj.godwars_armor_armadyl_chestplate, Obj.godwars_zamorak_robe_body)
    boost(
        134 / 5, Obj.godwars_armor_bandos_armored_skirt, Obj.godwars_armor_armadyl_armored_skirt, Obj.godwars_armadyl_crossbow,
        Obj.godwars_zamorak_robe_legs, Obj.godwars_zamorak_spear,
    )
    boost(
        25 / 5, Obj.godwars_armor_bandos_armored_boots, Obj.godwars_bandos_gloves, Obj.godwars_armadyl_boots,
        Obj.godwars_armadyl_gloves, Obj.godwars_zamorak_robe_boots, Obj.godwars_zamorak_robe_gloves,
    )
    boost(
        3, Obj.obj_4856, Obj.obj_4862, Obj.obj_4868, Obj.obj_4874, Obj.obj_4880, Obj.obj_4886, Obj.obj_4892, Obj.obj_4898,
        Obj.obj_4904, Obj.obj_4910, Obj.obj_4916, Obj.obj_4922, Obj.obj_4928, Obj.obj_4934, Obj.obj_4940, Obj.obj_4946,
        Obj.obj_4952, Obj.obj_4958, Obj.obj_4964, Obj.obj_4970, Obj.obj_4976, Obj.obj_4982, Obj.obj_4988, Obj.obj_4994,
    )
}

private val DEGRADE_TARGETS: Map<Int, Int> by lazy {
    val ids = HashSet<Int>()
    for (degrade in ItemDegrade.entries) {
        ids += degrade.itemId
        ids += degrade.brokenId
    }
    ids += -1
    buildMap {
        for (id in ids) {
            var resolved = id
            for (degrade in ItemDegrade.entries)
                if (degrade.brokenId == resolved || degrade.itemId == resolved)
                    resolved = degrade.degradedId
            if (resolved != id) put(id, resolved)
        }
    }
}

private val EQUIP_IDS: Map<Int, Int> by lazy {
    var equipId = 0
    buildMap {
        for (itemId in 0 until Utils.getItemDefinitionsSize()) {
            val defs = ItemDefinitions.getDefs(itemId)
            if (defs.primaryMaleModel >= 0 || defs.primaryFemaleModel >= 0)
                put(itemId, equipId++)
        }
    }
}

@ServerStartupEvent
fun mapPower() {
    onCombatFormulaAdjust { entity, target, _, _ ->
        if (entity is Player) {
            Power.incrementPower(entity)
            Power.incrementDefence(entity)
            var power = Power.maxPower(entity)
            if (entity.dungManager.isInsideDungeon)
                power = (power - 1) * 5 + 1
            if (entity.equipment.weaponId == -1)
                return@onCombatFormulaAdjust CombatMod(power, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0)
        }
        CombatMod()
    }
}

object Power {
    const val CONSUME_INCREASE = 50
    const val DENOM = 5_000.0
    const val FOOD_DENOM = 2_100.0
    const val DEF_MUL = 1.0
    const val DOM_TOWER_BOSS_KILL_MULTIPLIER = 10
    const val SKULL_MINUTES = 3
    const val INF_CAP_DAMAGE = 9_999_999
    const val RUN_DRAIN_WEIGHT_LIMIT = 1000
    const val SPEC_COST_DENOM = 1_000.0

    private fun lock3Seconds(player: Player, key: String): Boolean {
        val temp = player.tempAttribs
        if (temp.getB(key))
            return true
        temp.setB(key, true)
        player.tasks.schedule(Ticks.fromSeconds(3)) { temp.setB(key, false) }
        return false
    }

    private fun powerEquation(count: Double) = 1 + count / DENOM

    private fun bonusEquation(bonus: Double, count: Double) = bonus * ((powerEquation(count) - 1) * DEF_MUL + 1)

    private fun percent(value: Double) = "%.2f".format(value)

    @JvmStatic
    fun incrementItem(player: Player, itemId: Int, count: Long) {
        val ids = SHARED_POWER.firstOrNull { itemId in it } ?: intArrayOf(itemId)
        for (id in ids) {
            val slot = ItemDefinitions.getDefs(id).getEquipSlot()
            player.incrementCount(if (slot == Equipment.WEAPON) "PowerWeaponId_$id" else armourString(slot, id), count)
        }
    }

    @JvmStatic
    fun incrementPower(player: Player) {
        if (lock3Seconds(player, "IncrementedPower"))
            return
        val gloveId = player.equipment.glovesId
        val unarmed = player.equipment.weaponId == -1
        var mul = 1
        if (unarmed && gloveId !in DOMINION_GLOVES)
            mul *= 5
        if (player.equipment.weaponId == Obj.chinchompa_big_captured)
            mul *= 2
        if (Utils.random(POWER_ROLL) >= mul && !Settings.getConfig().isDebug)
            return
        val weaponId = if (unarmed && gloveId in DOMINION_GLOVES) gloveId else player.equipment.weaponId
        player.incrementCount("PowerWeaponId_$weaponId")
        val newPower = player.getCounterValueL("PowerWeaponId_$weaponId") / DENOM * 100.0
        player.sendMessage("<col=00FF00>Your power with this weapon has increased to ${percent(newPower)}%...")
    }

    @JvmStatic
    fun incrementPowerCooking(player: Player, foodId: Int) {
        if (lock3Seconds(player, "IncrementedPower"))
            return
        val mul = if (player.dungManager.isInsideDungeon) 10 else 1
        if (Utils.random(COOKING_POWER_ROLL) >= mul && !Settings.getConfig().isDebug)
            return
        player.incrementCount("FoodId_$foodId")
        player.sendMessage("<col=00FF00>Your health increase with this food has increased to ${percent(getFoodAmplificationPercentage(player, foodId))}%...")
    }

    @JvmStatic
    fun incrementAlchemy(player: Player, coins: Int) {
        player.incrementL(ALCHEMY_COINS, coins.toLong())
    }

    @JvmStatic
    fun alchemyCoins(player: Player, basePrice: Int): Long = Utils.satAdd(basePrice.toLong(), alchemyBonus(player))

    private fun alchemyBonus(player: Player): Long = Utils.satAdd(
        player.getCounterValueL("Codex_Alchemy"),
        player.getL(ALCHEMY_COINS, 0L) / ALCHEMY_COINS_PER_BONUS,
    ).coerceAtLeast(0L)

    @JvmStatic
    fun getFoodAmplificationPercentage(player: Player, foodId: Int): Double = player.getCounterValueL("FoodId_$foodId") / FOOD_DENOM * 100.0

    @JvmStatic
    fun foodAmplification(player: Player, heal: Int, foodId: Int): Int =
        (heal.toDouble() * (1 + getFoodAmplificationPercentage(player, foodId) / 100.0)).toInt()

    @JvmStatic
    fun ampedWeight(player: Player, itemId: Int, weight: Double): Double =
        if (weight <= 0) weight else weight + getCombatAmplificationPercentage(player, itemId)

    @JvmStatic
    fun ampedHPByPower(player: Player, item: Item): Int {
        val boost = HP_BOOSTS[item.id] ?: return 0
        val slot = if (boost.slotByEquipId) EQUIP_IDS[item.id] ?: -1 else Equipment.getItemSlot(item.id)
        return itemBonus(player, slot, item.id, boost.amount)
    }

    @JvmStatic
    fun weightReduction(player: Player): Double {
        val xp = player.skills.getXp(Skills.AGILITY) - AGILITY_POWER_XP
        if (xp < 0.0)
            return 0.0
        return xp / 100_000.0 + player.getCounterValueL("Codex_Weight")
    }

    @JvmStatic
    fun maxPower(player: Player): Double = powerEquation(player.getCounterValueL("PowerWeaponId_${player.equipment.weaponId}").toDouble())

    @JvmStatic
    fun incrementDefence(player: Player) {
        if (lock3Seconds(player, "IncrementedDefence"))
            return
        if (Utils.random(POWER_ROLL) != 1 && !Settings.getConfig().isDebug)
            return
        val slots = DEFENCE_SLOTS.toMutableList()
        var armourId = -1
        var slot = -1
        while (slots.isNotEmpty()) {
            slot = slots.removeAt(Utils.random(slots.size))
            val item = player.equipment[slot] ?: continue
            armourId = item.id
            break
        }
        if (armourId == -1) {
            player.sendMessage("<col=FF0000>No defence was added to armour as there is no armour...")
            return
        }
        player.incrementCount(armourString(slot, armourId))
        val newPower = player.getCounterValueL(armourString(slot, armourId)) / DENOM * DEF_MUL * 100.0
        player.sendMessage("<col=00FF00>Your defence with your ${getArmourFromNumber(slot).lowercase()} armour has increased to ${percent(newPower)}%...")
    }

    @JvmStatic
    fun getCombatAmplificationPercentage(player: Player, itemId: Int): Double {
        val id = DEGRADE_TARGETS[itemId] ?: itemId
        val slot = ItemDefinitions.getDefs(id).getEquipSlot()
        if (slot == Equipment.WEAPON)
            return player.getCounterValueL("PowerWeaponId_$id") / DENOM * 100.0
        return player.getCounterValueL(armourString(slot, id)) / DENOM * DEF_MUL * 100.0
    }

    @JvmStatic
    fun specialAttackCost(player: Player, weaponId: Int, cost: Int): Int =
        (cost * (1.0 - getCombatAmplificationPercentage(player, weaponId) / SPEC_COST_DENOM)).toInt().coerceIn(0, cost)

    @JvmStatic
    fun regenBraceletRegenBonus(player: Player): Int = (getCombatAmplificationPercentage(player, Obj.jewl_bracelet_regen) / 50.0 + 2).toInt()

    @JvmStatic
    fun itemBonus(player: Player, slot: Int, armourId: Int, bonus: Int): Int {
        if (bonus < 1)
            return bonus
        if (slot != Equipment.WEAPON)
            return ceil(bonusEquation(bonus.toDouble(), player.getCounterValueL(armourString(slot, armourId)).toDouble())).toInt()
        var power = maxPower(player)
        if (player.dungManager.isInsideDungeon)
            power = (power - 1) * 7 + 1
        if (player.equipment.weaponId == -1)
            power = 1.0
        return ceil(bonus * power).toInt()
    }

    @JvmStatic
    fun rangedStr(player: Player, weaponId: Int, bonusAmount: Int): Int {
        if (weaponId in ZARYTE_BOWS)
            return bonusAmount
        return ceil(bonusAmount * (1 + getCombatAmplificationPercentage(player, weaponId) / 100)).toInt()
    }

    @JvmStatic
    fun armourString(armourSlot: Int, armourId: Int) = "Defence${getArmourFromNumber(armourSlot)}Id_$armourId"

    @JvmStatic
    fun getArmourFromNumber(slot: Int): String = when (slot) {
        Equipment.HEAD -> "HEAD"
        Equipment.CAPE -> "CAPE"
        Equipment.NECK -> "NECK"
        Equipment.WEAPON -> "WEAPON"
        Equipment.CHEST -> "CHEST"
        Equipment.SHIELD -> "SHIELD"
        Equipment.LEGS -> "LEGS"
        Equipment.HANDS -> "HANDS"
        Equipment.FEET -> "FEET"
        Equipment.RING -> "RING"
        Equipment.AMMO -> "AMMO"
        Equipment.AURA -> "AURA"
        else -> "UNKNOWN"
    }
}
