package org.darkan.world

import com.rs.game.model.entity.player.Bank
import com.rs.game.model.entity.player.Player
import com.rs.plugin.PluginManager
import com.rs.plugin.events.ButtonClickEvent
import kotlinx.coroutines.runBlocking
import org.darkan.core.gameval.gen.Varbit
import org.darkan.core.model.Item
import org.darkan.core.model.Rights
import org.darkan.core.net.prot.ServerProt
import org.darkan.core.net.prot.packet.IfDragOntoIf
import org.darkan.core.net.prot.packet.UpdateInvFull
import org.darkan.core.net.prot.packet.UpdateInvPartial
import org.darkan.world.harness.HarnessPlayer
import org.darkan.world.harness.WorldHarness
import org.darkan.world.server.packet.IfDragOntoIfHandler
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BankTabTest {

    private companion object {
        const val COINS = 995

        const val ORE = 440

        const val ARROWS = 882

        const val SHIELD = 1171

        const val BANK_COMPONENT = 95
        const val BANK_CONTAINER = 95

        const val BANK_INVENTORY_INTERFACE = 763

        const val TAB_SIZE_VARBIT = 4885

        const val VIEWING_TAB_VARBIT = 4893

        fun tabHeader(tab: Int) = 46 + 2 * (9 - tab)
    }

    private val loggedIn = mutableListOf<HarnessPlayer>()

    @AfterTest
    fun tearDown() {
        loggedIn.forEach { WorldHarness.logout(it) }
        loggedIn.clear()
    }

    private fun login(name: String, rights: Rights = Rights.OWNER): HarnessPlayer =
        WorldHarness.login(name, rights).also {
            loggedIn.add(it)
            WorldHarness.tick()
            it.clearSent()
        }

    private fun openBankWith(name: String, vararg tabs: List<Item>): HarnessPlayer {
        val harness = login(name)
        val player = harness.player
        val bank = player.bank
        bank.clear()
        player.inventory.items.clear()
        player.inventory.refresh()
        repeat(tabs.size - 1) { bank.createTab() }
        tabs.forEachIndexed { tab, items -> bank.bankTabs[tab] = items.toTypedArray() }
        player.bank.open()
        assertTrue(player.interfaceManager.topOpen(762), "the bank interface is not open")
        WorldHarness.tick()
        harness.clearSent()
        return harness
    }

    private fun HarnessPlayer.settle(): List<ServerProt> {
        WorldHarness.tick()
        return session.flushed
    }

    private fun Player.click(inter: Int, component: Int, slot: Int, op: Int) {
        PluginManager.handle(ButtonClickEvent(this, inter, component, slot, -1, op))
    }

    private fun HarnessPlayer.dragOntoTab(fromSlot: Int, tab: Int) = runBlocking {
        IfDragOntoIfHandler().handle(
            player,
            IfDragOntoIf(762, BANK_COMPONENT, fromSlot, -1, 762, tabHeader(tab), -1, -1),
        )
    }

    private fun HarnessPlayer.dragOntoSlot(fromSlot: Int, toSlot: Int) = runBlocking {
        IfDragOntoIfHandler().handle(
            player,
            IfDragOntoIf(762, BANK_COMPONENT, fromSlot, -1, 762, BANK_COMPONENT, toSlot, -1),
        )
    }

    private val Bank.layout: List<List<Pair<Int, Int>>>
        get() = bankTabs.map { tab -> tab.map { it.id to it.amount } }

    private fun Player.clientTabSizes(): List<Int> = (1..8).map { vars.getVarBit(TAB_SIZE_VARBIT + it - 1) }

    private fun Bank.flattened(): List<Pair<Int, Int>> = generateContainer().map { it!!.id to it.amount }

    private fun List<ServerProt>.slotUpdates(slot: Int): List<Pair<Int, Int>> =
        filterIsInstance<UpdateInvPartial>()
            .filter { it.containerId == BANK_CONTAINER }
            .flatMap { p -> p.updates.filter { it.slot == slot }.map { it.itemId to it.amount } }

    private fun List<ServerProt>.lastFullContainer(): List<Pair<Int, Int>>? =
        filterIsInstance<UpdateInvFull>()
            .lastOrNull { it.containerId == BANK_CONTAINER }
            ?.let { p -> p.itemIds.indices.map { p.itemIds[it] to p.amounts[it] } }

    private fun Bank.fakeSlotOf(id: Int): Int {
        var slot = 0
        for (tab in 1 until bankTabs.size) {
            for (item in bankTabs[tab]) {
                if (item.id == id) return slot
                slot++
            }
        }
        for (item in bankTabs[0]) {
            if (item.id == id) return slot
            slot++
        }
        error("no bank entry for $id")
    }


    @Test
    fun dragOntoTheNewTabHeaderCreatesTheTab() {
        val harness = openBankWith("tabcreate", listOf(Item(COINS, 1000), Item(ORE, 20)))
        val bank = harness.player.bank
        assertEquals(1, bank.bankTabs.size, "the bank did not start as one tab")

        harness.dragOntoTab(bank.fakeSlotOf(ORE), 1)

        assertEquals(
            listOf(listOf(COINS to 1000), listOf(ORE to 20)),
            bank.layout,
            "the drag did not move the ore into a new tab 1",
        )
        assertEquals(2, bank.bankSize, "creating a tab changed the number of banked entries")
        assertEquals(
            listOf(1, 0, 0, 0, 0, 0, 0, 0),
            harness.player.clientTabSizes(),
            "the client was not told tab 1 now holds one item",
        )
        assertEquals(listOf(ORE to 20, COINS to 1000), bank.flattened(), "the flattened order is wrong")

        val flushed = harness.settle()
        assertEquals(ORE to 20, flushed.slotUpdates(0).last(), "slot 0 was not redrawn as the ore")
        assertEquals(COINS to 1000, flushed.slotUpdates(1).last(), "slot 1 was not redrawn as the coins")

        harness.clearSent()
        bank.sendItems()
        assertEquals(
            listOf(ORE to 20, COINS to 1000),
            harness.settle().lastFullContainer(),
            "the full container disagrees with the tab order",
        )
    }

    @Test
    fun theOnlyItemInTheBankStillCreatesATab() {
        val harness = openBankWith("tabonlyitem", listOf(Item(ORE, 20)))
        val bank = harness.player.bank

        harness.dragOntoTab(0, 1)

        assertEquals(listOf(emptyList<Pair<Int, Int>>(), listOf(ORE to 20)), bank.layout, "the only item did not move into tab 1")
        assertEquals(2, bank.bankTabs.size, "no tab was created")
        assertEquals(1, bank.bankSize, "the item was lost or duplicated")
        assertEquals(1, harness.player.clientTabSizes()[0], "the client was not told tab 1 exists")
        assertEquals(listOf(ORE to 20), bank.flattened(), "an empty main tab broke the flat container")
    }

    @Test
    fun theLastRemainingTabSurvivesAForcedRemoval() {
        val harness = openBankWith("tablastremain", listOf(Item(ORE, 20)))
        val bank = harness.player.bank

        bank.removeItem(ORE)

        assertEquals(1, bank.bankTabs.size, "the bank was left with no tabs")
        assertEquals(0, bank.bankSize, "the take-back did not remove the item")
        assertNull(bank.getRealSlot(0), "getRealSlot did not treat the empty bank as empty")
        assertEquals(emptyList<Pair<Int, Int>>(), bank.flattened(), "generateContainer broke on the empty bank")
    }


    @Test
    fun dragOntoAnExistingTabHeaderMovesTheItem() {
        val harness = openBankWith(
            "tabmove",
            listOf(Item(COINS, 1000), Item(ARROWS, 50)),
            listOf(Item(ORE, 20)),
        )
        val bank = harness.player.bank

        harness.dragOntoTab(bank.fakeSlotOf(COINS), 1)

        assertEquals(
            listOf(listOf(ARROWS to 50), listOf(ORE to 20, COINS to 1000)),
            bank.layout,
            "the coins did not move into tab 1",
        )
        assertEquals(2, harness.player.clientTabSizes()[0], "the client was not told tab 1 grew")
        assertEquals(
            listOf(ORE to 20, COINS to 1000, ARROWS to 50),
            bank.flattened(),
            "the flattened order does not put tab 1 before tab 0",
        )
    }

    @Test
    fun dragOntoItsOwnTabHeaderMovesTheItemToTheTabStart() {
        val harness = openBankWith(
            "tabsamehdr",
            listOf(Item(COINS, 1000)),
            listOf(Item(ORE, 20), Item(ARROWS, 50), Item(SHIELD, 1)),
        )
        val bank = harness.player.bank

        harness.dragOntoTab(bank.fakeSlotOf(SHIELD), 1)

        assertEquals(
            listOf(listOf(COINS to 1000), listOf(SHIELD to 1, ARROWS to 50, ORE to 20)),
            bank.layout,
            "the shield did not swap to the start of its own tab",
        )
        assertEquals(3, harness.player.clientTabSizes()[0], "a same-tab move changed the tab size")
    }

    @Test
    fun draggingTheLastItemOutOfATabDestroysItAndRenumbersTheVarbits() {
        val harness = openBankWith(
            "tabdestroy",
            listOf(Item(COINS, 1000)),
            listOf(Item(ORE, 20)),
            listOf(Item(ARROWS, 50), Item(SHIELD, 1)),
        )
        val bank = harness.player.bank
        assertEquals(listOf(1, 2, 0, 0, 0, 0, 0, 0), harness.player.clientTabSizes(), "the fixture is not set up")

        harness.dragOntoTab(bank.fakeSlotOf(ORE), 2)

        assertEquals(
            listOf(listOf(COINS to 1000), listOf(ARROWS to 50, SHIELD to 1, ORE to 20)),
            bank.layout,
            "the emptied tab was not destroyed, or the ore did not follow the renumbering",
        )
        assertEquals(
            listOf(3, 0, 0, 0, 0, 0, 0, 0),
            harness.player.clientTabSizes(),
            "tab 2's size varbit is stale, so the client still draws a destroyed tab",
        )
        assertEquals(
            listOf(ARROWS to 50, SHIELD to 1, ORE to 20, COINS to 1000),
            bank.flattened(),
            "the flattened order does not match the renumbered tabs",
        )
    }

    @Test
    fun movingTheLastItemOutOfTheMainTabKeepsTheMainTab() {
        val harness = openBankWith(
            "tabmainkeep",
            listOf(Item(COINS, 1000)),
            listOf(Item(ORE, 20)),
        )
        val bank = harness.player.bank

        harness.dragOntoTab(bank.fakeSlotOf(COINS), 1)

        assertEquals(
            listOf(emptyList<Pair<Int, Int>>(), listOf(ORE to 20, COINS to 1000)),
            bank.layout,
            "the main tab was destroyed and tab 1 was renumbered into it",
        )
        assertEquals(2, bank.bankTabs.size, "the bank lost a tab")
        assertEquals(2, harness.player.clientTabSizes()[0], "tab 1 no longer reports two items")
    }

    @Test
    fun withdrawingTheLastItemFromTheMainTabKeepsTheMainTab() {
        val harness = openBankWith(
            "tabmainwithdraw",
            listOf(Item(COINS, 1000)),
            listOf(Item(ORE, 20)),
        )
        val player = harness.player
        val bank = player.bank

        player.click(762, BANK_COMPONENT, bank.fakeSlotOf(COINS), 6)

        assertEquals(1000, player.inventory.getNumberOf(COINS, false), "the coins never reached the inventory")
        assertEquals(listOf(emptyList<Pair<Int, Int>>(), listOf(ORE to 20)), bank.layout, "the main tab was destroyed")
        assertEquals(1, player.clientTabSizes()[0], "tab 1 was renumbered into tab 0")
    }


    @Test
    fun swappingAcrossATabBoundaryExchangesTheEntriesAndKeepsTheTabSizes() {
        val harness = openBankWith(
            "tabswapcross",
            listOf(Item(COINS, 1000), Item(ARROWS, 50)),
            listOf(Item(ORE, 20), Item(SHIELD, 1)),
        )
        val player = harness.player
        val bank = player.bank
        assertFalse(bank.insertItems, "insert mode must default to off")
        assertEquals(listOf(ORE to 20, SHIELD to 1, COINS to 1000, ARROWS to 50), bank.flattened(), "bad fixture")

        harness.dragOntoSlot(0, 2)

        assertEquals(
            listOf(listOf(ORE to 20, ARROWS to 50), listOf(COINS to 1000, SHIELD to 1)),
            bank.layout,
            "the cross-tab swap did not exchange the two entries",
        )
        assertEquals(2, player.clientTabSizes()[0], "a swap changed a tab's size")
        assertEquals(4, bank.bankSize, "a swap changed the number of banked entries")
        assertEquals(
            listOf(COINS to 1000, SHIELD to 1, ORE to 20, ARROWS to 50),
            bank.flattened(),
            "the flattened container does not reflect the swap",
        )
    }

    @Test
    fun insertingAcrossATabBoundaryIsRejected() {
        val harness = openBankWith(
            "tabinsertcross",
            listOf(Item(COINS, 1000), Item(ARROWS, 50)),
            listOf(Item(ORE, 20), Item(SHIELD, 1)),
        )
        val player = harness.player
        val bank = player.bank
        player.click(762, 15, -1, 1)
        assertTrue(bank.insertItems, "the button did not turn insert mode on")
        val before = bank.layout

        harness.dragOntoSlot(0, 2)
        harness.dragOntoSlot(2, 0)

        assertEquals(before, bank.layout, "a cross-tab insert moved something")
        assertEquals(2, player.clientTabSizes()[0], "a rejected insert changed a tab size")
    }

    @Test
    fun insertingWithinATabShufflesThatTabOnly() {
        val harness = openBankWith(
            "tabinsertsame",
            listOf(Item(COINS, 1000)),
            listOf(Item(ORE, 20), Item(ARROWS, 50), Item(SHIELD, 1)),
        )
        val player = harness.player
        val bank = player.bank
        player.click(762, 15, -1, 1)
        assertTrue(bank.insertItems, "the button did not turn insert mode on")

        harness.dragOntoSlot(0, 2)

        assertEquals(
            listOf(listOf(COINS to 1000), listOf(ARROWS to 50, SHIELD to 1, ORE to 20)),
            bank.layout,
            "the insert did not shuffle tab 1",
        )
        assertEquals(3, player.clientTabSizes()[0], "an insert changed the tab size")
        assertEquals(
            listOf(ARROWS to 50, SHIELD to 1, ORE to 20, COINS to 1000),
            bank.flattened(),
            "the flattened container does not reflect the insert",
        )
    }


    @Test
    fun viewTabTracksTheViewedTabInItsVarbit() {
        val harness = openBankWith(
            "tabview",
            listOf(Item(COINS, 1000)),
            listOf(Item(ORE, 20)),
            listOf(Item(ARROWS, 50)),
        )
        val player = harness.player
        val bank = player.bank
        assertEquals(0, bank.currentTab, "the bank did not open on the main tab")
        assertEquals(1, player.vars.getVarBit(VIEWING_TAB_VARBIT), "opening the bank did not sync the viewed tab")

        player.click(762, tabHeader(2), -1, 1)

        assertEquals(2, bank.currentTab, "View Tab did not select tab 2")
        assertEquals(3, player.vars.getVarBit(VIEWING_TAB_VARBIT), "the viewed-tab varbit is stale after View Tab")

        player.click(762, tabHeader(0), -1, 1)

        assertEquals(0, bank.currentTab, "View Tab did not select the main tab")
        assertEquals(1, player.vars.getVarBit(VIEWING_TAB_VARBIT), "the viewed-tab varbit is stale after View Tab")

        player.click(762, tabHeader(7), -1, 1)
        assertEquals(0, bank.currentTab, "View Tab selected a tab that does not exist")
    }

    @Test
    fun collapseMergesIntoTheMainTabWithoutDuplicating() {
        val harness = openBankWith(
            "tabcollapse",
            listOf(Item(COINS, 100), Item(SHIELD, 1)),
            listOf(Item(COINS, 50), Item(ORE, 20)),
        )
        val player = harness.player
        val bank = player.bank

        player.click(762, tabHeader(1), -1, 2)

        assertEquals(1, bank.bankTabs.size, "the collapsed tab was not destroyed")
        assertEquals(
            listOf(listOf(COINS to 150, SHIELD to 1, ORE to 20)),
            bank.layout,
            "collapse did not merge the coins once into the main tab",
        )
        assertEquals(1, bank.bankTabs[0].count { it.id == COINS }, "collapse left two coin stacks")
        assertEquals(150, assertNotNull(bank.getItem(COINS)).amount, "the collapsed coins do not add up")
        assertEquals(3, bank.bankSize, "collapse changed the number of banked entries")
        assertEquals(
            listOf(0, 0, 0, 0, 0, 0, 0, 0),
            player.clientTabSizes(),
            "the destroyed tab's button was not hidden",
        )
        assertEquals(
            listOf(COINS to 150, SHIELD to 1, ORE to 20),
            harness.settle().lastFullContainer(),
            "the shorter container was not sent in full",
        )
    }

    @Test
    fun collapseRenumbersTheTabsAboveIt() {
        val harness = openBankWith(
            "tabcollapsemid",
            listOf(Item(COINS, 100)),
            listOf(Item(ORE, 20)),
            listOf(Item(ARROWS, 50), Item(SHIELD, 1)),
        )
        val player = harness.player
        val bank = player.bank
        player.click(762, tabHeader(2), -1, 1)
        assertEquals(2, bank.currentTab, "the fixture is not viewing tab 2")

        player.click(762, tabHeader(1), -1, 2)

        assertEquals(
            listOf(listOf(COINS to 100, ORE to 20), listOf(ARROWS to 50, SHIELD to 1)),
            bank.layout,
            "tab 2 was not renumbered down into tab 1",
        )
        assertEquals(
            listOf(2, 0, 0, 0, 0, 0, 0, 0),
            player.clientTabSizes(),
            "the size varbits do not match the renumbered tabs",
        )
        assertEquals(1, bank.currentTab, "the viewed tab did not follow the renumbering")
        assertEquals(2, player.vars.getVarBit(VIEWING_TAB_VARBIT), "the viewed-tab varbit did not follow")
    }

    @Test
    fun collapseIgnoresTheMainTabAndMissingTabs() {
        val harness = openBankWith(
            "tabcollapsenoop",
            listOf(Item(COINS, 100)),
            listOf(Item(ORE, 20)),
        )
        val player = harness.player
        val bank = player.bank
        val before = bank.layout

        player.click(762, tabHeader(0), -1, 2)
        player.click(762, tabHeader(5), -1, 2)

        assertEquals(before, bank.layout, "collapse touched the main tab or a missing tab")
        assertEquals(2, bank.bankTabs.size, "collapse destroyed a tab it should have ignored")
    }


    @Test
    fun depositingWhileViewingATabLandsInThatTab() {
        val harness = openBankWith(
            "tabdeposit",
            listOf(Item(COINS, 1000)),
            listOf(Item(ORE, 20)),
        )
        val player = harness.player
        val bank = player.bank
        player.inventory.items.set(0, Item(ARROWS, 50))
        player.inventory.refresh()
        player.click(762, tabHeader(1), -1, 1)
        assertEquals(1, bank.currentTab, "the fixture is not viewing tab 1")

        player.click(BANK_INVENTORY_INTERFACE, 0, 0, 6)

        assertEquals(
            listOf(listOf(COINS to 1000), listOf(ORE to 20, ARROWS to 50)),
            bank.layout,
            "the deposit did not land in the viewed tab",
        )
        assertEquals(2, player.clientTabSizes()[0], "the client was not told the viewed tab grew")
        assertEquals(0, player.inventory.getNumberOf(ARROWS, false), "the arrows stayed in the inventory")
    }

    @Test
    fun withdrawingATabsLastItemDestroysTheTab() {
        val harness = openBankWith(
            "tabwithdraw",
            listOf(Item(COINS, 1000)),
            listOf(Item(ORE, 20)),
            listOf(Item(ARROWS, 50)),
        )
        val player = harness.player
        val bank = player.bank

        player.click(762, BANK_COMPONENT, bank.fakeSlotOf(ORE), 6)

        assertEquals(20, player.inventory.getNumberOf(ORE, false), "the ore never reached the inventory")
        assertEquals(
            listOf(listOf(COINS to 1000), listOf(ARROWS to 50)),
            bank.layout,
            "the emptied tab was not destroyed, or tab 2 was not renumbered",
        )
        assertEquals(
            listOf(1, 0, 0, 0, 0, 0, 0, 0),
            player.clientTabSizes(),
            "the size varbits do not match the renumbered tabs",
        )
    }

    @Test
    fun placeholdersKeepTheirTabAcrossAWithdrawAndDeposit() {
        val harness = openBankWith(
            "tabplaceholder",
            listOf(Item(COINS, 1000)),
            listOf(Item(ORE, 20), Item(ARROWS, 50)),
        )
        val player = harness.player
        val bank = player.bank
        player.vars.saveVarBit(Varbit.bank_placeholders, 1)
        player.click(762, tabHeader(1), -1, 1)

        player.click(762, BANK_COMPONENT, bank.fakeSlotOf(ORE), 6)

        assertEquals(
            listOf(listOf(COINS to 1000), listOf(ORE to 0, ARROWS to 50)),
            bank.layout,
            "the withdrawal did not leave a placeholder in tab 1",
        )
        assertEquals(2, player.clientTabSizes()[0], "the placeholder stopped counting towards its tab")

        player.click(762, tabHeader(0), -1, 1)
        assertEquals(0, bank.currentTab, "View Tab did not go back to the main tab")
        player.click(BANK_INVENTORY_INTERFACE, 0, 0, 6)

        assertEquals(
            listOf(listOf(COINS to 1000), listOf(ORE to 20, ARROWS to 50)),
            bank.layout,
            "the deposit did not refill the placeholder in tab 1",
        )
        assertEquals(3, bank.bankSize, "the deposit created a second entry for the ore")
        assertEquals(2, player.clientTabSizes()[0], "tab 1's size changed while refilling its placeholder")
    }
}
