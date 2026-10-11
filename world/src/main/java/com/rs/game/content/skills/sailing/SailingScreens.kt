package com.rs.game.content.skills.sailing

import com.rs.game.model.entity.player.Player
import com.rs.game.model.item.ItemTooltips
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onButtonClick
import org.darkan.core.Constants
import org.darkan.core.EnvVars
import org.darkan.core.cache.ItemDefinitions
import org.darkan.core.gameval.gen.Component
import org.darkan.core.gameval.gen.Interface
import org.darkan.core.gameval.gen.Inv
import org.darkan.core.gameval.gen.Obj
import org.darkan.core.gameval.gen.Varbit
import org.darkan.core.model.IFEvents
import org.darkan.core.model.Item
import java.util.concurrent.ConcurrentHashMap

private const val ROW_SLOTS = 3
private const val CARD_SLOTS = 3
private const val TAB_SLOTS = 2
private const val BUTTON_SLOTS = 2
private const val HOLD_SLOTS = 64
private const val LIST_SLOTS = 16
private const val CHART_SLOTS = 64
private const val CREW_SLOTS = 8
private const val ACTION_BUTTONS = 3
private const val LOG_TASKS = 0
private const val DEPOSIT_BALLS = 1
private const val DEPOSIT_KITS = 2
private val HOLD_AMOUNTS = intArrayOf(1, 5, 10, Int.MAX_VALUE)
private val CANNONBALLS = intArrayOf(
    Obj.sailing_cannonball_bronze, Obj.sailing_cannonball_iron, Obj.sailing_cannonball_steel, Obj.sailing_cannonball_mithril,
    Obj.sailing_cannonball_adamant, Obj.sailing_cannonball_rune, Obj.sailing_cannonball_dragon, Obj.mcannonball
)
private val KEPT_IN_INVENTORY = setOf(Obj.coins, Obj.hammer, Obj.poh_saw)

sealed interface HoldEntry {
    class Crates(val task: Long, val amount: Int) : HoldEntry
    class Goods(val item: Int, val amount: Int) : HoldEntry
    class Balls(val amount: Int) : HoldEntry
}

object SailingScreens {
    private val holds = ConcurrentHashMap<String, Boat>()
    private val offers = ConcurrentHashMap<String, List<PortTask>>()
    private val crews = ConcurrentHashMap<String, Pair<Boat, PortSite>>()
    private val boardings = ConcurrentHashMap<String, Pair<List<Boat>, PortSite>>()
    private val chartTotals = ConcurrentHashMap<String, Int>()

    fun holdEntries(boat: Boat): List<HoldEntry> {
        val entries = mutableListOf<HoldEntry>()
        boat.hold.crates.forEach { (task, amount) -> entries.add(HoldEntry.Crates(task, amount)) }
        boat.hold.goods.forEach { (item, amount) -> entries.add(HoldEntry.Goods(item, amount)) }
        if (boat.hold.cannonballs > 0) entries.add(HoldEntry.Balls(boat.hold.cannonballs))
        return entries.take(HOLD_SLOTS)
    }

    private fun holdItems(boat: Boat): Array<Item?> = holdEntries(boat).map { entry ->
        when (entry) {
            is HoldEntry.Crates -> Item(Obj.sailing_courier_crate, entry.amount)
            is HoldEntry.Goods -> Item(entry.item, entry.amount)
            is HoldEntry.Balls -> Item(Obj.sailing_cannonball_bronze, entry.amount)
        }
    }.toTypedArray<Item?>()

    fun openHold(player: Player, boat: Boat) {
        if (!openScreen(player, Interface.sailing_cargo_hold)) return
        holds[player.username] = boat
        player.packets.setIFText(Component.sailing_cargo_hold.title, "Cargo Hold: ${boat.name}")
        player.packets.setIFEvents(IFEvents(Component.sailing_cargo_hold.grid, 0, HOLD_SLOTS - 1).enableRightClickOptions(0, 1, 2, 3, 4))
        player.packets.setIFEvents(IFEvents(Component.sailing_cargo_hold.amounts, 0, HOLD_AMOUNTS.size * BUTTON_SLOTS - 1).enableRightClickOption(0))
        player.packets.setIFEvents(IFEvents(Component.sailing_cargo_hold.actions, 0, ACTION_BUTTONS * BUTTON_SLOTS - 1).enableRightClickOption(0))
        refreshHold(player, boat)
    }

    private fun refreshHold(player: Player, boat: Boat) {
        player.packets.sendItems(Inv.sailing_cargo_hold, holdItems(boat))
        SailingPanel.syncHold(player, boat)
    }

    private fun canReach(player: Player, boat: Boat): Boolean =
        Voyages.of(player)?.boat === boat || (boat.state == BoatState.DOCKED && boat.dockPort.isNotEmpty())

    fun withdraw(player: Player, slot: Int, opNum: Int) {
        val boat = holds[player.username] ?: return
        if (!canReach(player, boat)) return player.sendMessage("You can only reach the hold from the boat or from its port.")
        val requested = if (opNum == 1) HOLD_AMOUNTS.getOrElse(player.vars.getVarBit(Varbit.sailing_hold_amount_mode)) { 1 } else HOLD_AMOUNTS.getOrNull(opNum - 2) ?: return
        when (val entry = holdEntries(boat).getOrNull(slot) ?: return) {
            is HoldEntry.Crates -> player.sendMessage("Courier crates stay aboard until they are delivered.")
            is HoldEntry.Goods -> moveToInventory(player, boat, entry.item, minOf(requested, entry.amount)) { taken -> boat.hold.goods.computeIfPresent(entry.item) { _, have -> (have - taken).takeIf { it > 0 } } }
            is HoldEntry.Balls -> moveToInventory(player, boat, Obj.sailing_cannonball_bronze, minOf(requested, entry.amount)) { taken -> boat.hold.cannonballs -= taken }
        }
        BoatStore.save(boat)
        refreshHold(player, boat)
    }

    private fun moveToInventory(player: Player, boat: Boat, item: Int, wanted: Int, remove: (Int) -> Unit) {
        val definitions = ItemDefinitions.getDefs(item)
        val amount = if (definitions.isStackable() || definitions.isNoted()) wanted else minOf(wanted, player.inventory.freeSlots)
        if (amount <= 0) return player.sendMessage("You don't have enough inventory space to withdraw that.")
        if (player.inventory.addItem(Item(item, amount))) remove(amount)
    }

    fun deposit(player: Player, mode: Int) {
        val boat = holds[player.username] ?: return
        if (!canReach(player, boat)) return player.sendMessage("You can only reach the hold from the boat or from its port.")
        val totals = LinkedHashMap<Int, Int>()
        player.inventory.items.array().filterNotNull().forEach { totals.merge(it.id, it.amount, Int::plus) }
        totals.forEach { (item, amount) ->
            when {
                item in CANNONBALLS && mode != DEPOSIT_KITS -> {
                    boat.hold.cannonballs += amount
                    player.inventory.deleteItem(item, amount)
                }
                mode == DEPOSIT_BALLS -> Unit
                mode == DEPOSIT_KITS && RepairKit.of(item) == null -> Unit
                item in KEPT_IN_INVENTORY || item in CANNONBALLS -> Unit
                else -> {
                    val stored = boat.hold.addGood(item, amount)
                    if (stored > 0) player.inventory.deleteItem(item, stored)
                }
            }
        }
        BoatStore.save(boat)
        refreshHold(player, boat)
    }

    fun chartTotal(profile: SailorProfile): Int = profile.log.charted.groupBy { seaOf(it) }.entries.sumOf { (sea, keys) ->
        chartTotals.computeIfAbsent(sea) {
            val (x, y) = coordinatesOf(keys.first())
            Seas.pointsIn(x, y).size
        }
    }

    private fun seaOf(key: String): String = key.substringBeforeLast(':').substringBeforeLast(':')

    private fun coordinatesOf(key: String): Pair<Int, Int> {
        val parts = key.split(':')
        return parts[parts.size - 2].toInt() to parts.last().toInt()
    }

    private fun bountyStatus(task: ActiveTask, profile: SailorProfile, fallback: String): String =
        if (task.kind != TaskKind.BOUNTY) fallback else "${BountyMonster.entries.getOrNull(task.monster)?.let { BountyTasks.itemsHeld(BoatStore.ownedBy(profile.username), it) } ?: 0}/${task.crates} held"

    private fun taskItems(profile: SailorProfile): Array<Item?> = profile.tasks.map { task ->
        Item(Obj.sailing_courier_crate, task.crates).addMetaData(
            ItemTooltips.METADATA_KEY,
            SailingTexts.taskCard(task.kind, task.crates, task.from, task.to, task.baseXp, BagTier.forXp(task.baseXp), bountyStatus(task, profile, if (task.collected) "Loaded" else "Not yet collected"), task.monster)
        )
    }.take(LIST_SLOTS).toTypedArray<Item?>()

    private fun chartItems(profile: SailorProfile): Array<Item?> = profile.log.charted.groupBy { seaOf(it) }.map { (sea, keys) ->
        val (x, y) = coordinatesOf(keys.first())
        Item(Obj.sailing_boat_kit, 1).addMetaData(ItemTooltips.METADATA_KEY, SailingTexts.chartLine(Seas.nameAt(x, y), keys.size, sea in profile.chartedSeas))
    }.take(CHART_SLOTS).toTypedArray<Item?>()

    private fun boatItems(player: Player): Array<Item?> = BoatStore.ownedBy(player.username).map { boat ->
        Item(Obj.sailing_boat_kit, 1).addMetaData(ItemTooltips.METADATA_KEY, SailingTexts.boatLine(boat))
    }.take(LIST_SLOTS).toTypedArray<Item?>()

    fun openLog(player: Player, view: Int = LOG_TASKS) {
        if (!openScreen(player, Interface.sailing_captains_log)) return
        player.packets.setIFEvents(IFEvents(Component.sailing_captains_log.tabs, 0, 3 * TAB_SLOTS - 1).enableRightClickOption(0))
        player.packets.setIFEvents(IFEvents(Component.sailing_captains_log.view, 0, LIST_SLOTS * CARD_SLOTS - 1).enableRightClickOption(0))
        player.vars.setVarBit(Varbit.sailing_log_view, view)
        refreshLog(player)
    }

    private fun refreshLog(player: Player) {
        val profile = SailorStore.of(player.username)
        player.packets.sendItems(Inv.sailing_task_list, taskItems(profile))
        player.packets.sendItems(Inv.sailing_chart_list, chartItems(profile))
        player.packets.sendItems(Inv.sailing_boat_list, boatItems(player))
        SailingPanel.syncLog(player)
    }

    fun logClick(player: Player, slot: Int) {
        if (player.vars.getVarBit(Varbit.sailing_log_view) != LOG_TASKS) return
        val task = SailorStore.of(player.username).tasks.getOrNull(slot / ROW_SLOTS) ?: return
        Courier.abandon(player, task)
        refreshLog(player)
    }

    fun openNoticeBoard(player: Player, site: PortSite) {
        if (!openScreen(player, Interface.sailing_notice_board)) return
        player.packets.setIFText(Component.sailing_notice_board.title, "Notice board: port ${site.name}")
        player.packets.setIFEvents(IFEvents(Component.sailing_notice_board.cards, 0, LIST_SLOTS * CARD_SLOTS - 1).enableRightClickOption(0))
        refreshOffers(player, site)
    }

    private fun refreshOffers(player: Player, site: PortSite) {
        val profile = SailorStore.of(player.username)
        val tasks = Courier.generate(site, player.skills.getLevel(Constants.SAILING)).filter { task -> profile.tasks.none { it.id == Courier.taskId(task) } }
        offers[player.username] = tasks
        player.packets.sendItems(Inv.sailing_task_offers, tasks.map { task ->
            Item(Obj.sailing_courier_crate, task.crates).addMetaData(
                ItemTooltips.METADATA_KEY, SailingTexts.taskCard(task.kind, task.crates, task.from.name, task.to.name, task.baseXp, task.bag, "", task.monster)
            )
        }.take(LIST_SLOTS).toTypedArray<Item?>())
        SailingPanel.syncLog(player)
    }

    fun accept(player: Player, slot: Int) {
        val task = offers[player.username]?.getOrNull(slot / ROW_SLOTS) ?: return
        Courier.accept(player, task)
        PortSites.byName(task.from.name)?.let { refreshOffers(player, it) }
    }

    fun openGangplank(player: Player, boats: List<Boat>, site: PortSite) {
        if (!openScreen(player, Interface.sailing_gangplank)) return
        boardings[player.username] = boats to site
        player.packets.setIFEvents(IFEvents(Component.sailing_gangplank.list, 0, LIST_SLOTS * ROW_SLOTS - 1).enableRightClickOption(0))
        player.packets.sendItems(Inv.sailing_boat_list, boats.mapIndexed { index, boat ->
            Item(Obj.sailing_boat_kit, 1).addMetaData(ItemTooltips.METADATA_KEY, SailingTexts.boardLine(index, boat, site.name))
        }.take(LIST_SLOTS).toTypedArray<Item?>())
    }

    fun board(player: Player, slot: Int) {
        val (boats, site) = boardings[player.username] ?: return
        val boat = boats.getOrNull(slot / ROW_SLOTS) ?: return
        player.interfaceManager.removeCentralInterface()
        Voyages.start(player, boat, site)
    }

    fun openCrew(player: Player, boat: Boat, site: PortSite) {
        if (!openScreen(player, Interface.sailing_crew_management)) return
        crews[player.username] = boat to site
        player.packets.setIFText(Component.sailing_crew_management.title, "Crew Management: ${boat.name}")
        player.packets.setIFEvents(IFEvents(Component.sailing_crew_management.crew_list, 0, CREW_SLOTS * ROW_SLOTS - 1).enableRightClickOptions(0, 1, 2, 3))
        player.packets.setIFEvents(IFEvents(Component.sailing_crew_management.recruit_list, 0, LIST_SLOTS * ROW_SLOTS - 1).enableRightClickOption(0))
        refreshCrew(player)
    }

    private fun refreshCrew(player: Player) {
        val (boat, site) = crews[player.username] ?: return
        player.packets.sendItems(Inv.sailing_crew_list, boat.crew.map { crewmate ->
            Item(Obj.sailing_boat_kit, 1).addMetaData(ItemTooltips.METADATA_KEY, SailingTexts.crewLine(crewmate))
        }.take(CREW_SLOTS).toTypedArray<Item?>())
        val hired = boat.crew.map { it.name }.toSet()
        player.packets.sendItems(Inv.sailing_recruit_list, PortMaster.candidates(site).filter { it.name !in hired }.map { crewmate ->
            Item(Obj.sailing_boat_kit, 1).addMetaData(ItemTooltips.METADATA_KEY, SailingTexts.recruitLine(crewmate))
        }.take(LIST_SLOTS).toTypedArray<Item?>())
        SailingPanel.syncParts(player, boat)
    }

    fun crewOrder(player: Player, slot: Int, opNum: Int) {
        val (boat, _) = crews[player.username] ?: return
        val crewmate = boat.crew.getOrNull(slot / ROW_SLOTS) ?: return
        when (opNum) {
            1 -> {
                boat.crew.remove(crewmate)
                BoatStore.save(boat)
                player.sendMessage("${crewmate.name} leaves the ${boat.name}.")
            }
            2 -> PortMaster.assign(player, boat, crewmate, if (crewmate.job == CrewJob.HELM) CrewJob.NONE else CrewJob.HELM)
            3 -> PortMaster.assign(player, boat, crewmate, if (crewmate.job == CrewJob.SALVAGE) CrewJob.NONE else CrewJob.SALVAGE)
            4 -> PortMaster.assign(player, boat, crewmate, if (crewmate.job == CrewJob.CANNON) CrewJob.NONE else CrewJob.CANNON)
        }
        refreshCrew(player)
    }

    fun recruit(player: Player, slot: Int) {
        val (boat, site) = crews[player.username] ?: return
        val hired = boat.crew.map { it.name }.toSet()
        val crewmate = PortMaster.candidates(site).filter { it.name !in hired }.getOrNull(slot / ROW_SLOTS) ?: return
        PortMaster.hire(player, boat, crewmate)
        refreshCrew(player)
    }

    private fun openScreen(player: Player, interfaceId: Int): Boolean {
        if (player.interfaceManager.containsScreenInter()) {
            player.sendMessage("Please close the interface you have open first.")
            return false
        }
        player.stopAll()
        player.interfaceManager.sendInterface(interfaceId)
        return true
    }

    fun forget(username: String) {
        holds.remove(username)
        offers.remove(username)
        crews.remove(username)
        boardings.remove(username)
    }
}

@ServerStartupEvent
fun mapSailingScreens() {
    if (!EnvVars.generateMaps) return
    onButtonClick(Interface.sailing_cargo_hold) { e ->
        when (e.component) {
            Component.sailing_cargo_hold.close_button -> e.player.interfaceManager.removeCentralInterface()
            Component.sailing_cargo_hold.grid -> SailingScreens.withdraw(e.player, e.slotId, e.opNum)
            Component.sailing_cargo_hold.amounts -> e.player.vars.setVarBit(Varbit.sailing_hold_amount_mode, e.slotId / BUTTON_SLOTS)
            Component.sailing_cargo_hold.actions -> SailingScreens.deposit(e.player, e.slotId / BUTTON_SLOTS)
        }
    }
    onButtonClick(Interface.sailing_captains_log) { e ->
        when (e.component) {
            Component.sailing_captains_log.close_button -> e.player.interfaceManager.removeCentralInterface()
            Component.sailing_captains_log.tabs -> e.player.vars.setVarBit(Varbit.sailing_log_view, e.slotId / TAB_SLOTS)
            Component.sailing_captains_log.view -> SailingScreens.logClick(e.player, e.slotId)
        }
    }
    onButtonClick(Interface.sailing_notice_board) { e ->
        when (e.component) {
            Component.sailing_notice_board.close_button -> e.player.interfaceManager.removeCentralInterface()
            Component.sailing_notice_board.cards -> SailingScreens.accept(e.player, e.slotId)
        }
    }
    onButtonClick(Interface.sailing_crew_management) { e ->
        when (e.component) {
            Component.sailing_crew_management.close_button -> e.player.interfaceManager.removeCentralInterface()
            Component.sailing_crew_management.crew_list -> SailingScreens.crewOrder(e.player, e.slotId, e.opNum)
            Component.sailing_crew_management.recruit_list -> SailingScreens.recruit(e.player, e.slotId)
        }
    }
    onButtonClick(Interface.sailing_gangplank) { e ->
        when (e.component) {
            Component.sailing_gangplank.close_button -> e.player.interfaceManager.removeCentralInterface()
            Component.sailing_gangplank.list -> SailingScreens.board(e.player, e.slotId)
        }
    }
}
