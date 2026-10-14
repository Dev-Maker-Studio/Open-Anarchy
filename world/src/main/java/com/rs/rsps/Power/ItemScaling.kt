package com.rs.rsps.Power

import com.rs.Settings
import com.rs.engine.dialogue.Dialogue
import com.rs.game.content.ItemConstants.ItemDegrade
import com.rs.game.content.combat.special_attacks.getSpec
import com.rs.game.model.entity.npc.NPC
import com.rs.game.model.entity.npc.combat.NPCCombatDefinitions
import com.rs.game.model.entity.player.Equipment
import com.rs.game.model.entity.player.Player
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.events.ItemOnItemEvent
import com.rs.plugin.kts.onItemOnItem
import org.darkan.core.EnvVars
import org.darkan.core.Utils
import org.darkan.core.cache.ItemDefinitions
import org.darkan.core.gameval.gen.Obj
import org.darkan.core.model.Item
import java.util.Random

private const val MEGA_UP_AMOUNT = 18L
private const val TOP_UP_AMOUNT = 9L
private const val UPPER_NORMIE = 5.0
private const val LOWER_NORMIE = 0.5
private const val CODEX_WEAPON_PERCENTAGE = 500.0
private const val CODEX_WEAPON_COST = -25_000L
private const val CHINCHOMPAS_PER_EXTRA = 15_000L
private const val MAX_CONSUME_X = 27
private const val POWER_KEY = "Power"

private val EXCLUDED_ITEMS = setOf(
    Obj.barbassault_penance_fighter_torso, Obj.barbassault_penance_fighter_hat, Obj.dragonfire_shield,
    Obj.rand_rapier_80_reward_broken, Obj.rand_longsword_80_reward_broken, Obj.rand_maul_80_reward_broken, Obj.rand_staff_80_reward_broken,
    Obj.rand_xbow_80_reward_broken, Obj.rand_kiteshield_80_melee_reward_broken, Obj.rand_kiteshield_80_range_reward_broken,
    Obj.rand_kiteshield_80_magic_reward_broken,
)

private val INCLUDED_ITEMS = listOf(
    Obj.godwars_godsword_hilt_armadyl, Obj.godwars_godsword_hilt_bandos, Obj.godwars_godsword_hilt_saradomin, Obj.godwars_godsword_hilt_zamorak,
    Obj.sum2_sigil_arcane, Obj.sum2_sigil_divine, Obj.sum2_sigil_elysian, Obj.sum2_sigil_spectral,
    Obj.dragonfire_visage,
    Obj.luc2_dragonplate_piece1, Obj.luc2_dragonplate_piece2, Obj.luc2_dragonplate_piece3,
    Obj.qbd2_crossbow_component4, Obj.qbd2_crossbow_component3, Obj.qbd2_crossbow_component2, Obj.qbd2_crossbow_component1,
)

private val SCALED_MEGA_ITEMS = listOf(
    Obj.obj_14484, Obj.dragonfire_shield_uncharged, Obj.tzhaar_cape_fire, Obj.obj_23659, Obj.enchanted_onyx_amulet,
    Obj.godwars2_helm_melee_100, Obj.godwars2_helm_melee_50, Obj.godwars2_body_melee_100, Obj.godwars2_body_melee_50,
    Obj.godwars2_legs_melee_100, Obj.godwars2_legs_melee_50, Obj.godwars2_gloves_melee_100, Obj.godwars2_gloves_melee_50,
    Obj.godwars2_gloves_melee_100_new, Obj.godwars2_boots_melee_100, Obj.godwars2_boots_melee_50, Obj.godwars2_boots_melee_100_new,
    Obj.godwars2_helm_range_100, Obj.godwars2_helm_range_50, Obj.godwars2_body_range_100, Obj.godwars2_body_range_50,
    Obj.godwars2_legs_range_100, Obj.godwars2_legs_range_50, Obj.godwars2_gloves_range_100, Obj.godwars2_gloves_range_50,
    Obj.godwars2_gloves_range_100_new, Obj.godwars2_boots_range_100, Obj.godwars2_boots_range_50, Obj.godwars2_boots_range_100_new,
    Obj.godwars2_helm_magic_100, Obj.godwars2_helm_magic_50, Obj.godwars2_body_magic_100, Obj.godwars2_body_magic_50,
    Obj.godwars2_legs_magic_100, Obj.godwars2_legs_magic_50, Obj.godwars2_gloves_magic_100, Obj.godwars2_gloves_magic_50,
    Obj.godwars2_gloves_magic_100_new, Obj.godwars2_boots_magic_100, Obj.godwars2_boots_magic_50, Obj.godwars2_boots_magic_100_new,
    Obj.godwars2_zaryte_bow_100, Obj.godwars2_zaryte_bow_50,
    Obj.godwars_armadyl_completed_godsword, Obj.godwars_bandos_completed_godsword, Obj.godwars_saradomin_completed_godsword,
    Obj.godwars_zamorak_completed_godsword,
    Obj.sum2_spirit_shield_arcane, Obj.sum2_spirit_shield_divine, Obj.sum2_spirit_shield_elysian, Obj.sum2_spirit_shield_spectral,
    Obj.jewl_bracelet_regen, Obj.dragon_2h_sword, Obj.brut_dragon_full_helm,
)

private val SCALED_TOP_ITEMS = listOf(
    Obj.dragon_spear, Obj.glacor_melee_boots, Obj.glacor_range_boots, Obj.glacor_magic_boots, Obj.cert_newbieshrimp,
    Obj.qbd2_upgraded_dragonplatebody, Obj.staff_of_light, Obj.qbd2_crossbow_forged, Obj.dragon_axe,
    Obj.ranger_ring, Obj.berzerker_ring, Obj.warrior_ring, Obj.seer_ring,
    Obj.godwars_bandos_helm, Obj.godwars_armor_bandos_chestplate, Obj.godwars_armor_bandos_armored_skirt,
    Obj.godwars_armor_bandos_armored_boots, Obj.godwars_bandos_gloves, Obj.godwars_bandos_shield,
    Obj.godwars_zamorak_spear, Obj.godwars_zamorak_robe_head, Obj.godwars_zamorak_robe_body, Obj.godwars_zamorak_robe_legs,
    Obj.godwars_zamorak_shield, Obj.godwars_zamorak_robe_boots, Obj.godwars_zamorak_robe_gloves,
    Obj.godwars_saradomin_sword, Obj.godwars_amulet_melee, Obj.godwars_amulet_magic, Obj.godwars_amulet_range, Obj.godwars_armadyl_crossbow,
    Obj.godwars_armor_armadyl_helmet, Obj.godwars_armor_armadyl_chestplate, Obj.godwars_armor_armadyl_armored_skirt,
    Obj.godwars_armadyl_boots, Obj.godwars_armadyl_shield, Obj.godwars_armadyl_gloves,
    Obj.rand_rapier_80_reward, Obj.rand_longsword_80_reward, Obj.rand_maul_80_reward, Obj.obj_18355, Obj.obj_18357,
    Obj.rand_kiteshield_80_melee_reward, Obj.rand_kiteshield_80_range_reward, Obj.rand_kiteshield_80_magic_reward,
    Obj.obj_4856, Obj.obj_4862, Obj.obj_4868, Obj.obj_4874,
    Obj.obj_4880, Obj.obj_4886, Obj.obj_4892, Obj.obj_4898,
    Obj.obj_4904, Obj.obj_4910, Obj.obj_4916, Obj.obj_4922,
    Obj.obj_4928, Obj.obj_4934, Obj.obj_4940, Obj.obj_4946,
    Obj.obj_4952, Obj.obj_4958, Obj.obj_4964, Obj.obj_4970,
    Obj.obj_4976, Obj.obj_4982, Obj.obj_4988, Obj.obj_4994,
)

private val BUFF_TARGETS: Map<Int, Int> = buildMap {
    put(Obj.godwars_godsword_hilt_armadyl, Obj.godwars_armadyl_completed_godsword)
    put(Obj.godwars_godsword_hilt_bandos, Obj.godwars_bandos_completed_godsword)
    put(Obj.godwars_godsword_hilt_saradomin, Obj.godwars_saradomin_completed_godsword)
    put(Obj.godwars_godsword_hilt_zamorak, Obj.godwars_zamorak_completed_godsword)
    put(Obj.sum2_sigil_arcane, Obj.sum2_spirit_shield_arcane)
    put(Obj.sum2_sigil_divine, Obj.sum2_spirit_shield_divine)
    put(Obj.sum2_sigil_elysian, Obj.sum2_spirit_shield_elysian)
    put(Obj.sum2_sigil_spectral, Obj.sum2_spirit_shield_spectral)
    put(Obj.dragonfire_visage, Obj.dragonfire_shield)
    val barrows = intArrayOf(
        Obj.barrows_ahrim_head, Obj.barrows_ahrim_weapon, Obj.barrows_ahrim_body, Obj.barrows_ahrim_legs,
        Obj.barrows_dharok_head, Obj.barrows_dharok_weapon, Obj.barrows_dharok_body, Obj.barrows_dharok_legs,
        Obj.barrows_guthan_head, Obj.barrows_guthan_weapon, Obj.barrows_guthan_body, Obj.barrows_guthan_legs,
        Obj.barrows_karil_head, Obj.barrows_karil_weapon, Obj.barrows_karil_body, Obj.barrows_karil_legs,
        Obj.barrows_torag_head, Obj.obj_4747, Obj.barrows_torag_body, Obj.barrows_torag_legs,
        Obj.barrows_verac_head, Obj.barrows_verac_weapon, Obj.barrows_verac_body, Obj.barrows_verac_legs,
    )
    val degraded = intArrayOf(
        Obj.obj_4856, Obj.obj_4862, Obj.obj_4868, Obj.obj_4874, Obj.obj_4880, Obj.obj_4886, Obj.obj_4892, Obj.obj_4898,
        Obj.obj_4904, Obj.obj_4910, Obj.obj_4916, Obj.obj_4922, Obj.obj_4928, Obj.obj_4934, Obj.obj_4940, Obj.obj_4946,
        Obj.obj_4952, Obj.obj_4958, Obj.obj_4964, Obj.obj_4970, Obj.obj_4976, Obj.obj_4982, Obj.obj_4988, Obj.obj_4994,
    )
    barrows.forEachIndexed { i, id -> put(id, degraded[i]) }
    for (piece in intArrayOf(Obj.luc2_dragonplate_piece1, Obj.luc2_dragonplate_piece2, Obj.luc2_dragonplate_piece3))
        put(piece, Obj.luc2_dragonplate_piece3)
    for (component in intArrayOf(Obj.qbd2_crossbow_component4, Obj.qbd2_crossbow_component3, Obj.qbd2_crossbow_component2, Obj.qbd2_crossbow_component1))
        put(component, Obj.qbd2_crossbow_forged)
}

private val HAMMERABLE_ITEMS: IntArray by lazy {
    (0 until Utils.getItemDefinitionsSize()).map { ItemDefinitions.getDefs(it) }.filter {
        it.id !in EXCLUDED_ITEMS && (it.id in INCLUDED_ITEMS ||
            (!it.isNoted() && !it.isStackable() && it.isWearItem() && it.getEquipSlot() != Equipment.AURA && it.getEquipSlot() != Equipment.AMMO))
    }.map { it.id }.toIntArray()
}

private val HAMMERABLE_SET: Set<Int> by lazy { HAMMERABLE_ITEMS.toHashSet() }

private class Siphon(val item: Item, val scale: Int, val targetId: Int, val power: Long, val cost: Long) {
    val destroysItem get() = scale <= 1

    fun matches(other: Siphon) =
        item === other.item && scale == other.scale && targetId == other.targetId && power == other.power && cost == other.cost
}

@ServerStartupEvent
fun mapItemScaling() {
    onItemOnItem(HAMMERABLE_ITEMS, intArrayOf(Obj.coins)) { e -> consumeWeaponIntoCodex(e) }
    onItemOnItem(HAMMERABLE_ITEMS, intArrayOf(Obj.obj_6767)) { e -> insertCodexSpecial(e) }
    onItemOnItem(HAMMERABLE_ITEMS, intArrayOf(Obj.hammer)) { e -> consumePowerItem(e) }
}

private fun consumeWeaponIntoCodex(e: ItemOnItemEvent) {
    val player = e.player
    val weapon = if (e.item1.id == Obj.coins) e.item2 else e.item1
    val weaponName = weapon.name
    val weaponId = weapon.id
    val percentage = Power.getCombatAmplificationPercentage(player, weaponId)
    val formatted = "%.2f".format(percentage)
    if (percentage < CODEX_WEAPON_PERCENTAGE && !Settings.getConfig().isDebug) {
        player.startConversation(Dialogue().addSimple("You have $formatted but you require 500%..."))
        return
    }
    player.startConversation(Dialogue().addOptions("You have $formatted do you want to use 500%?") { options ->
        options.add("Yes", Dialogue()
            .addSimple("You chip the coin at the weapon...")
            .addSimple("A book seems to grow from the $weaponName")
            .addNext {
                if (!player.inventory.hasFreeSlots()) {
                    player.sendMessage("You need at least one inventory space for this.")
                    return@addNext
                }
                Power.incrementItem(player, weaponId, if (Settings.getConfig().isDebug) 0L else CODEX_WEAPON_COST)
                val codex = Item(Obj.obj_6767)
                codex.setMetaDataO<Any>("Codex", weaponName)
                codex.setMetaDataO<Any>("WeaponId", weaponId)
                player.inventory.addItem(codex)
            })
        options.add("No")
    })
}

private fun insertCodexSpecial(e: ItemOnItemEvent) {
    val player = e.player
    val weapon = if (e.item1.id == Obj.obj_6767) e.item2 else e.item1
    val codex = if (e.item1.id != Obj.obj_6767) e.item2 else e.item1
    val weaponId = weapon.id
    val weaponSpec = getSpec(weaponId)
    if (!codex.containsMetaData() || weaponSpec == null)
        return
    val weaponKey = weaponId.toString()
    val specWeaponName = codex.getMetaDataO<String>("Codex")
    val specWeaponId = (codex.getMetaDataO<Any>("WeaponId") as Number).toInt()
    player.startConversation(Dialogue().addOptions("Do you want to insert $specWeaponName special into ${weapon.name}?") { options ->
        options.add("Yes", Dialogue()
            .addSimple("You place the weapon inside the codex and close the book...")
            .addSimple("A book seems to glow...")
            .addNext {
                val codexSpec = getSpec(specWeaponId) ?: return@addNext
                if (weaponSpec.type != codexSpec.type) {
                    player.startConversation(Dialogue().addSimple("The codex weapon is a different type than your weapon"))
                    return@addNext
                }
                val specWeapons = player.getO<String>("weaponsWithSpecials")
                when {
                    specWeapons == null -> {
                        player.set("weaponsWithSpecials", weaponKey)
                        player.set("${weaponKey}_specs", specWeaponId.toString())
                    }
                    !ItemScaling.checkMetaListForString(specWeapons, weaponKey) -> {
                        player.set("weaponsWithSpecials", "$specWeapons,$weaponKey")
                        player.set("${weaponKey}_specs", "${player.getO<String>("${weaponKey}_specs")},$specWeaponId")
                    }
                    else -> player.set("${weaponKey}_specs", "${player.getO<String>("${weaponKey}_specs")},$specWeaponId")
                }
                codex.deleteMetaData()
            })
        options.add("No")
    })
}

private fun consumePowerItem(e: ItemOnItemEvent) {
    val player = e.player
    val scaledItem = if (e.item1.id == Obj.hammer) e.item2 else e.item1
    val itemName = scaledItem.name
    val siphon = siphonOf(scaledItem)
    if (siphon == null) {
        player.sendMessage("You cannot consume broken items...")
        return
    }
    val scale = siphon.scale
    val cost = siphon.cost
    if (!player.inventory.hasCoins(cost)) {
        player.sendMessage("<col=FF0000>You need ${coins(cost)} coins to hammer $itemName($scale); you have ${coins(player.inventory.getCoins())}.")
        return
    }
    val scaled = scaledPlan(player)
    val hit = Dialogue()
        .addSimple("You hit the $itemName($scale) with your hammer and ${coins(cost)}GP goes into the armour...")
        .addSimple("Sparks fly...")
        .addNext { siphon(player, listOf(siphon)) { heldPlan(player, scaledItem) } }

    player.startConversation(Dialogue()
        .addItem(scaledItem.id, "Do I really want to destroy and consume $itemName($scale)? Hmm, looks like ${coins(cost)}GP for about +${percent(siphon.power)}%...")
        .addOptions("Do you really want to destroy $itemName?") { option ->
            option.add("Yes", hit)
            if (scaled.any { it.item !== scaledItem })
                option.add("Siphon all scaled items (${scaled.size})", siphonAllDialogue(player, scaled))
            if (scaledItem.containsMetaData())
                return@addOptions
            option.add("Make X", Dialogue().addNext {
                player.sendInputInteger("How many would you like to use?") { requested ->
                    val makeX = minOf(requested, MAX_CONSUME_X)
                    if (makeX < 1)
                        return@sendInputInteger
                    val quoted = plainPlan(player, scaledItem.id, makeX)
                    if (quoted.size < makeX)
                        return@sendInputInteger
                    siphon(player, quoted) { plainPlan(player, scaledItem.id, makeX) }
                }
            })
            val all = plainPlan(player, scaledItem.id, Int.MAX_VALUE)
            val allCost = totalCost(all)
            if (all.isNotEmpty() && player.inventory.hasCoins(allCost)) {
                option.add("Make all", Dialogue()
                    .addSimple("You hit the ${all.size} $itemName with your hammer and ${coins(allCost)}GP goes into the armour...")
                    .addSimple("Sparks fly...")
                    .addNext { siphon(player, all) { plainPlan(player, scaledItem.id, all.size) } })
            }
        })
}

private fun siphonAllDialogue(player: Player, quoted: List<Siphon>): Dialogue {
    val cost = totalCost(quoted)
    if (!player.inventory.hasCoins(cost))
        return Dialogue().addSimple("Siphoning all ${quoted.size} scaled items costs ${coins(cost)}GP; you have ${coins(player.inventory.getCoins())}.")
    return Dialogue()
        .addSimple("Siphoning all ${quoted.size} scaled items in your inventory will cost ${coins(cost)}GP.")
        .addOptions("Siphon ${quoted.size} scaled items for ${coins(cost)}GP?") { option ->
            option.add("Yes", Dialogue()
                .addSimple("You hammer every scaled item and ${coins(cost)}GP goes into the armour...")
                .addSimple("Sparks fly...")
                .addNext { siphon(player, quoted) { scaledPlan(player) } })
            option.add("No")
        }
}

private fun siphon(player: Player, quoted: List<Siphon>, plan: () -> List<Siphon>) {
    val siphons = plan()
    if (siphons.isEmpty() || siphons.size != quoted.size || siphons.zip(quoted).any { (now, then) -> !now.matches(then) }) {
        player.sendMessage("<col=FF0000>Your inventory changed, so nothing was hammered.")
        return
    }
    val cost = totalCost(siphons)
    if (!player.inventory.hasCoins(cost)) {
        player.sendMessage("<col=FF0000>You need ${coins(cost)} coins for this; you have ${coins(player.inventory.getCoins())}.")
        return
    }
    if (!charge(player, cost)) {
        player.sendMessage("<col=FF0000>Your coins could not be taken here, so nothing was hammered.")
        return
    }
    var siphoned = 0
    for (siphon in siphons) {
        if (siphon.destroysItem) {
            player.inventory.deleteItem(siphon.item.slot, siphon.item)
            if (player.holds(siphon.item)) {
                player.inventory.addCoins(siphon.cost)
                continue
            }
        } else
            siphon.item.deleteMetaData()
        Power.incrementItem(player, siphon.targetId, siphon.power)
        if (siphon.targetId == Obj.dragonfire_shield)
            Power.incrementItem(player, Obj.dragonfire_shield_uncharged, siphon.power)
        siphoned++
        if (siphons.size == 1)
            player.sendMessage("<col=00FF00>Your power with ${siphon.item.name} has increased by ${percent(siphon.power)}%...")
    }
    if (siphons.size > 1)
        player.sendMessage("<col=00FF00>You hammer $siphoned items and the power flows into your equipment...")
}

private fun charge(player: Player, cost: Long): Boolean {
    val inventory = player.inventory
    val pouch = inventory.pouchCoins
    val stack = inventory.getAmountOf(Obj.coins).toLong()
    inventory.removeCoins(cost)
    val charged = (pouch - inventory.pouchCoins) + (stack - inventory.getAmountOf(Obj.coins))
    if (charged == cost)
        return true
    inventory.addCoins(charged)
    return false
}

private fun hammerTarget(item: Item): Int? {
    var target = item.id
    for (degrade in ItemDegrade.entries) {
        if (item.id == degrade.brokenId)
            return null
        if (item.id == degrade.itemId)
            target = degrade.degradedId
    }
    return BUFF_TARGETS[item.id] ?: target
}

private fun siphonOf(item: Item): Siphon? {
    val targetId = hammerTarget(item) ?: return null
    val scale = item.getMetaDataI(POWER_KEY, 1)
    val power = ItemScaling.scaleToItem(targetId, Utils.satMul(Power.CONSUME_INCREASE.toLong(), scale.toLong()))
    return Siphon(item, scale, targetId, power, EnvVars.itemScalingCost)
}

private fun Player.holds(item: Item) = inventory.items[item.slot] === item

private fun Player.heldItems() = inventory.items.array().filterNotNull()

private fun heldPlan(player: Player, item: Item) = if (player.holds(item)) listOfNotNull(siphonOf(item)) else emptyList()

private fun plainPlan(player: Player, itemId: Int, count: Int) =
    player.heldItems().filter { it.id == itemId && !it.containsMetaData() }.take(count).mapNotNull(::siphonOf)

private fun scaledPlan(player: Player) =
    player.heldItems().filter { it.id in HAMMERABLE_SET && it.getMetaDataI(POWER_KEY, 1) > 1 }.mapNotNull(::siphonOf)

private fun totalCost(siphons: List<Siphon>) = siphons.fold(0L) { total, siphon -> Utils.satAdd(total, siphon.cost) }

private fun coins(amount: Long) = "%,d".format(amount)

private fun percent(power: Long) = "%.2f".format(power / Power.DENOM * 100.0)

object ItemScaling {
    @JvmStatic
    fun scaleToItem(itemId: Int, power: Long): Long {
        if (itemId in SCALED_MEGA_ITEMS)
            return Utils.satMul(power, MEGA_UP_AMOUNT)
        if (itemId in SCALED_TOP_ITEMS)
            return Utils.satMul(power, TOP_UP_AMOUNT)
        val maxTier = minOf(ItemDefinitions.getDefs(itemId).getWearingSkillRequiriments().values.fold(1, ::maxOf), 80)
        return maxOf(power * (maxTier / 80.0 * UPPER_NORMIE), power * LOWER_NORMIE).toLong()
    }

    @JvmStatic
    fun checkMetaListForString(list: String?, search: String?): Boolean =
        list != null && search != null && search in list.split(",")

    @JvmStatic
    fun getMetaListFromString(list: String?): IntArray =
        list?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.mapNotNull { it.toIntOrNull() }?.toIntArray() ?: IntArray(0)

    @JvmStatic
    fun incrementChins(): Boolean = true

    @JvmStatic
    fun chinGiveMore(player: Player) {
        val chinchompas = player.getCounterValueL("Chinchompas_Success")
        var multiplier = Utils.satInt(chinchompas / CHINCHOMPAS_PER_EXTRA)
        val chance = (chinchompas % CHINCHOMPAS_PER_EXTRA / 150) * 0.01
        if (Random().nextDouble() < chance)
            multiplier = Utils.satAdd(multiplier, 1)
        player.inventory.addItem(Obj.chinchompa_big_captured, multiplier)
    }

    @JvmStatic
    fun printCombatLevelsOnExamine(player: Player, npc: NPC) {
        for (skill in NPCCombatDefinitions.Skill.entries)
            player.sendMessage("${skill.name}: ${npc.getStat(skill)}, ")
    }
}
