package com.rs.rsps.Power

import com.rs.Settings
import com.rs.engine.dialogue.Dialogue
import com.rs.game.content.combat.RangedWeapon
import com.rs.game.content.combat.special_attacks.getSpec
import com.rs.game.model.entity.Entity
import com.rs.game.World
import com.rs.game.model.entity.npc.NPC
import com.rs.game.model.entity.player.Player
import com.rs.game.tasks.WorldTasks
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onItemOnItem
import com.rs.plugin.kts.onNpcDeath
import org.darkan.core.Constants
import org.darkan.core.Utils
import org.darkan.core.gameval.gen.Component
import org.darkan.core.gameval.gen.Obj
import org.darkan.core.model.Bonus
import org.darkan.core.model.Item

private const val CODEX_STAT_XP = 13_000_000.0
private const val MAX_ABSORB = 90
private const val CODEX_ROLL = 256
private const val CODEX_KEY = "Codex"
private const val CODEX_WEAPON_KEY = "WeaponId"
private const val SKIP_CODEX_PROMPT = "SkipCodexPrompt"

private val MELEE_CODEXES = arrayOf("Melee Strength", "Stab Attack", "Crush Attack", "Slash Attack")
private val RANGED_CODEXES = arrayOf("Ranged Attack", "Ranged Strength", "Ranged Defense", "Stab Defense")
private val MAGIC_CODEXES = arrayOf("Mage Attack", "Mage Strength", "Mage Defense", "Crush Defense", "Slash Defense")

private val CODEX_SPECIAL_WEAPONS: IntArray = intArrayOf(Obj.obj_18355) +
    RangedWeapon.RUNE_CROSSBOW.ids + RangedWeapon.ROYAL_CROSSBOW.ids + RangedWeapon.ARMADYL_CROSSBOW.ids

@ServerStartupEvent
fun mapSpecialItems() {
    onNpcDeath(
        "Kree'arra", "General Graardor", "K'ril Tsutsaroth", "Commander Zilyana", "King Black Dragon",
        "Dagannoth Prime", "Dagannoth Rex", "Dagannoth Supreme", "Corporeal Beast",
    ) { (killer, npc) ->
        val player = killer as? Player ?: return@onNpcDeath
        val codexes = when (npc.definitions.name) {
            "Kree'arra" -> listOf(SpecialItems.rollCodexNames(RANGED_CODEXES), "Weight")
            "General Graardor" -> listOf("Alchemy", "Weight", SpecialItems.rollCodexNames(MELEE_CODEXES))
            "K'ril Tsutsaroth" -> listOf("Poison", "Weight")
            "King Black Dragon" -> listOf("Poison", "Dragon Fire", "Weight")
            "Dagannoth Prime" -> listOf(SpecialItems.rollCodexNames(MAGIC_CODEXES), "Agility", "Weight")
            "Dagannoth Rex" -> listOf(SpecialItems.rollCodexNames(MELEE_CODEXES))
            "Dagannoth Supreme" -> listOf(SpecialItems.rollCodexNames(RANGED_CODEXES))
            "Corporeal Beast" -> listOf("Prayer", "Agility", "Weight")
            "Commander Zilyana" -> listOf("Alchemy", "Weight", SpecialItems.rollCodexNames(MELEE_CODEXES))
            else -> return@onNpcDeath
        }
        for (codex in codexes)
            SpecialItems.dropCodex(player, npc, codex)
    }

    WorldTasks.scheduleLooping(0, 1) {
        for (player in World.players)
            if (player != null && player.hasStarted() && !player.hasFinished() && player.equipment.weaponId in CODEX_SPECIAL_WEAPONS)
                showSpecialBar(player)
    }

    onItemOnItem(intArrayOf(Obj.obj_6767), intArrayOf(Obj.hammer)) { e ->
        val player = e.player
        val codex = if (e.item1.id == Obj.hammer) e.item2 else e.item1
        val codexType = codex.getMetaDataO<String>(CODEX_KEY) ?: return@onItemOnItem
        player.startConversation(Dialogue()
            .addItem(codex.id, "You look at your Codex of $codexType...")
            .addOptions("Do you really want to destroy it?") { option ->
                option.add("Yes", Dialogue()
                    .addSimple("You hammer at the book...")
                    .addSimple("It seems to be glowing with power...")
                    .addNext { consumeCodex(player, codex, codexType) })
                option.add("No")
            })
    }
}

private fun consumeCodex(player: Player, codex: Item, codexType: String) {
    if (player.inventory.items[codex.slot] !== codex || codex.getMetaDataO<String>(CODEX_KEY) != codexType)
        return
    player.incrementCount(SpecialItems.codexKey(codexType))
    player.sendMessage("You have used the Codex of $codexType")
    codex.deleteMetaData()
}

private fun showSpecialBar(player: Player) {
    val component = Component.interface_884.component_884_4
    player.packets.setIFHidden(Utils.interfaceIdFromHash(component), Utils.componentIdFromHash(component), false)
}

object SpecialItems {
    fun readCodex(player: Player, codex: Item): Boolean {
        val codexType = codex.getMetaDataO<String>(CODEX_KEY)
        if (codexType == null || codex.getMetaData(CODEX_WEAPON_KEY) != null)
            return false
        if (player.tempAttribs.getB(SKIP_CODEX_PROMPT)) {
            consumeCodex(player, codex, codexType)
            return true
        }
        player.startConversation(Dialogue()
            .addItem(codex.id, "You look at your Codex of $codexType...")
            .addOptions("Absorb the Codex of $codexType?") { option ->
                option.add("Yes") { consumeCodex(player, codex, codexType) }
                option.add("Yes, and don't ask again until I log out") {
                    player.tempAttribs.setB(SKIP_CODEX_PROMPT, true)
                    consumeCodex(player, codex, codexType)
                }
                option.add("No, just read it") { player.openBook(Codex(codexType)) }
                option.add("No")
            })
        return true
    }

    @JvmStatic
    fun castCodexSpecials(player: Player, target: Entity?) {
        val weaponKey = player.equipment.weaponId.toString()
        if (!ItemScaling.checkMetaListForString(player.getO("weaponsWithSpecials"), weaponKey))
            return
        for (specId in ItemScaling.getMetaListFromString(player.getO("${weaponKey}_specs")))
            getSpec(specId)?.execute?.apply(player, target!!)
    }

    @JvmStatic
    fun rollCodexNames(names: Array<String>): String = names[Utils.random(0, names.size)]

    @JvmStatic
    fun codexKey(type: String) = "Codex_$type"

    @JvmStatic
    fun codexCount(player: Player, type: String): Int = player.getCounterValue(codexKey(type))

    @JvmStatic
    fun dropCodex(player: Player, npc: NPC, type: String) {
        if (rollsCodex())
            npc.sendDrop(player, codex(type))
    }

    private fun rollsCodex(): Boolean = Settings.getConfig().isDebug || Utils.random(CODEX_ROLL) == 0

    @JvmStatic
    fun getQBDCodex(type: String): Item = codex(type)

    private fun codex(type: String) = Item(Obj.obj_6767).apply { setMetaDataO<Any>(CODEX_KEY, type) }

    @JvmStatic
    fun statBonus(player: Player, skill: Int): Int {
        val xp = player.skills.getXp(skill) - CODEX_STAT_XP
        if (xp < 1.0)
            return 0
        return Utils.satInt(Math.ceil(xp / 100_000.0))
    }

    private fun codexBonus(player: Player, skill: Int, codex: String): Int =
        Utils.satAdd(statBonus(player, skill), Utils.satInt(player.getCounterValueL(codex)))

    @JvmStatic
    fun codexBonuses(player: Player, bonus: Bonus): Int = when (bonus) {
        Bonus.STAB_ATT -> codexBonus(player, Constants.ATTACK, "Codex_Stab Attack")
        Bonus.SLASH_ATT -> codexBonus(player, Constants.ATTACK, "Codex_Slash Attack")
        Bonus.CRUSH_ATT -> codexBonus(player, Constants.ATTACK, "Codex_Crush Attack")
        Bonus.MAGIC_ATT -> codexBonus(player, Constants.ATTACK, "Codex_Mage Attack")
        Bonus.RANGE_ATT -> codexBonus(player, Constants.ATTACK, "Codex_Ranged Attack")
        Bonus.STAB_DEF -> codexBonus(player, Constants.DEFENSE, "Codex_Stab Defense")
        Bonus.SLASH_DEF -> codexBonus(player, Constants.DEFENSE, "Codex_Slash Defense")
        Bonus.CRUSH_DEF -> codexBonus(player, Constants.DEFENSE, "Codex_Crush Defense")
        Bonus.MAGIC_DEF -> codexBonus(player, Constants.MAGIC, "Codex_Mage Defense")
        Bonus.RANGE_DEF -> codexBonus(player, Constants.RANGE, "Codex_Ranged Defense")
        Bonus.SUMM_DEF, Bonus.ABSORB_MELEE, Bonus.ABSORB_MAGIC, Bonus.ABSORB_RANGE -> player.getCounterValue("")
        Bonus.MELEE_STR -> codexBonus(player, Constants.STRENGTH, "Codex_Melee Strength")
        Bonus.RANGE_STR -> codexBonus(player, Constants.RANGE, "Codex_Ranged Strength")
        Bonus.PRAYER -> codexBonus(player, Constants.PRAYER, "Codex_Prayer")
        Bonus.MAGIC_STR -> player.getCounterValue("Codex_Mage Strength")
    }

    @JvmStatic
    fun limitAbsorbBonuses(bonus: Bonus, value: Int): Int = when (bonus) {
        Bonus.ABSORB_MELEE, Bonus.ABSORB_MAGIC, Bonus.ABSORB_RANGE -> minOf(value, MAX_ABSORB)
        else -> value
    }

    @JvmStatic
    fun reduceDragonFire(entity: Entity, damage: Int): Int {
        val reduction = (entity as? Player)?.getCounterValue("Codex_Dragon Fire") ?: return damage
        if (reduction < 1)
            return damage
        return maxOf(0, damage - reduction)
    }

    @JvmStatic
    fun healsPoison(player: Player): Boolean = player.getCounterValueL("Codex_Poison") > 0L

    @JvmStatic
    fun hpCodex(player: Player): Int = Utils.satInt(
        Utils.satMul(Utils.satAdd(statBonus(player, Constants.HITPOINTS).toLong(), player.getCounterValueL("Codex_Health")), 5L)
    )

    @JvmStatic
    fun metaDataDoesntAllowCombine(item1: Item, item2: Item): Boolean = item1.containsMetaData() || item2.containsMetaData()
}
