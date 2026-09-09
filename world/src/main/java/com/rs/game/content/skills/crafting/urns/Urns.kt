package com.rs.game.content.skills.crafting.urns

import com.rs.engine.dialogue.startConversation
import com.rs.engine.dialogue.statements.MakeXStatement
import com.rs.game.model.entity.player.Player
import com.rs.plugin.annotations.PluginEventHandler
import com.rs.plugin.handlers.ItemClickHandler
import com.rs.plugin.handlers.ItemOnItemHandler
import com.rs.plugin.handlers.NPCDropHandler
import com.rs.plugin.handlers.XPGainHandler
import kotlin.math.min
import org.darkan.core.Constants
import org.darkan.core.Utils
import org.darkan.core.cache.ItemDefinitions
import org.darkan.core.gameval.gen.Obj
import org.darkan.core.model.Item

@PluginEventHandler
object Urns {

    private const val XP_KEY = "xp"

    private val filledBySkillXp = setOf(
        Constants.SMITHING, Constants.WOODCUTTING, Constants.FISHING, Constants.COOKING, Constants.MINING,
        Constants.FARMING, Constants.HUNTER, Constants.RUNECRAFTING
    )

    private val activatorOptions = Urn.entries.map { activatorOption(it) }.distinct().toTypedArray()

    @JvmField
    val teleport = ItemClickHandler(Urn.byFull.keys.toTypedArray<Any>(), arrayOf("Teleport urn")) { e ->
        val urn = Urn.byFull.getValue(e.item.id)
        e.player.inventory.deleteItem(e.item.id, 1)
        e.player.anim(urn.teleport)
        e.player.skills.addXpLamp(urn.skill, urn.teleportXp)
    }

    @JvmField
    val checkLevel = ItemClickHandler(Urn.byStarted.keys.toTypedArray<Any>(), arrayOf("Check level")) { e ->
        val urn = Urn.byStarted.getValue(e.item.id)
        e.player.sendMessage("The urn is filled ${percent(e.item.getMetaDataD(XP_KEY), urn)}%.")
    }

    @JvmField
    val addActivatorOption = ItemClickHandler(Urn.byNoRune.keys.toTypedArray<Any>(), activatorOptions) { e ->
        val urn = Urn.byNoRune.getValue(e.item.id)
        if (e.option == activatorOption(urn)) {
            addActivator(e.player, urn, e.player.inventory.getNumberOf(urn.noRune))
        }
    }

    @JvmField
    val addActivatorItem = ItemOnItemHandler(Urn.byNoRune.keys.toIntArray()) { e ->
        val urn = Urn.byNoRune[e.item1.id] ?: Urn.byNoRune[e.item2.id] ?: return@ItemOnItemHandler
        if (e.getUsedWith(urn.noRune).id != urn.activator) {
            e.player.sendMessage("You must use ${Utils.addArticle(activatorName(urn))} to activate this urn.")
            return@ItemOnItemHandler
        }
        val most = min(e.player.inventory.getNumberOf(urn.noRune), e.player.inventory.getNumberOf(urn.activator))
        if (most <= 0) {
            addActivator(e.player, urn, 0)
            return@ItemOnItemHandler
        }
        e.player.startConversation {
            makeX(intArrayOf(urn.empty), most)
            exec { addActivator(e.player, urn, MakeXStatement.getQuantity(e.player)) }
        }
    }

    @JvmField
    val fillFromXp = XPGainHandler { e ->
        if (e.skillId in filledBySkillXp) {
            addXp(e.player, urnFor(e.player, Urn.forSkill(e.skillId)), e.xp)
        }
    }

    @JvmField
    val fillFromAshes = NPCDropHandler(null, arrayOf<Any>(Obj.impious_ashes, Obj.accursed_ashes, Obj.infernal_ashes)) { e ->
        val (urns, xp) = when (e.item.id) {
            Obj.impious_ashes -> listOf(Urn.IMPIOUS, Urn.ACCURSED, Urn.INFERNAL) to 4.0
            Obj.accursed_ashes -> listOf(Urn.ACCURSED, Urn.INFERNAL) to 12.5
            else -> listOf(Urn.INFERNAL) to 62.5
        }
        if (addXp(e.player, urnFor(e.player, urns), xp)) {
            e.deleteItem()
        }
    }

    fun activatorOption(urn: Urn) = "Add ${activatorName(urn)}"

    private fun activatorName(urn: Urn) = ItemDefinitions.getDefs(urn.activator).name.lowercase()

    fun addActivator(player: Player, urn: Urn, requested: Int) {
        val amount = minOf(requested, player.inventory.getNumberOf(urn.noRune), player.inventory.getNumberOf(urn.activator))
        if (amount <= 0) {
            player.sendMessage("You need ${Utils.addArticle(activatorName(urn))} to activate this urn.")
            return
        }
        player.inventory.deleteItem(urn.noRune, amount)
        player.inventory.deleteItem(urn.activator, amount)
        player.inventory.addItem(urn.empty, amount)
        player.skills.addXpLamp(urn.skill, amount.toDouble())
        player.anim(urn.runeIn)
    }

    fun urnFor(player: Player, urns: List<Urn>): Urn? =
        urns.firstOrNull { player.inventory.containsOneItem(it.empty, it.started) }

    fun addXp(player: Player, urn: Urn?, xp: Double): Boolean {
        if (urn == null) {
            return false
        }
        val started = player.getItemFromInv(urn.started)
        if (started != null) {
            fill(player, urn, started, xp)
            return true
        }
        if (player.getItemFromInv(urn.empty) == null || !player.inventory.hasFreeSlots()) {
            return false
        }
        player.inventory.deleteItem(urn.empty, 1)
        player.sendMessage("<col=e69d00><shad=000000>You start a new urn.", true)
        player.inventory.addItem(Item(urn.started, 1).addMetaData(XP_KEY, xp))
        return true
    }

    private fun fill(player: Player, urn: Urn, started: Item, xp: Double) {
        val filled = min(started.getMetaDataD(XP_KEY) + xp, urn.capacity)
        if (filled >= urn.capacity) {
            player.incrementCount("${started.name} filled")
            started.id = urn.full
            started.deleteMetaData()
            player.inventory.refresh()
        } else {
            started.addMetaData(XP_KEY, filled)
        }
        player.sendMessage("<col=e69d00><shad=000000>Your urn is filled ${percent(filled, urn)}%.", true)
    }

    private fun percent(xp: Double, urn: Urn) = Utils.formatDouble(xp / urn.capacity * 100.0)
}
