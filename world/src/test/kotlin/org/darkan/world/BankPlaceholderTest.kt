package org.darkan.world

import com.rs.game.content.Skillcapes
import com.rs.game.model.entity.player.Bank
import com.rs.game.model.entity.player.Player
import com.rs.plugin.PluginManager
import com.rs.plugin.events.ButtonClickEvent
import com.rs.plugin.events.InputIntegerEvent
import kotlinx.coroutines.runBlocking
import org.darkan.core.Constants
import org.darkan.core.JsonFileManager
import org.darkan.core.model.Item
import org.darkan.core.model.Rights
import org.darkan.core.net.prot.ServerProt
import org.darkan.core.net.prot.packet.GameMessage
import org.darkan.core.net.prot.packet.IfDragOntoIf
import org.darkan.core.net.prot.packet.IfSetGraphic
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
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Bank placeholders, end to end on the real bank.
 *
 * A placeholder is an entry that keeps its id with amount 0. The whole feature is one invariant:
 * **an amount-0 entry only ever gains an amount through a real deposit, and only ever leaves
 * through an explicit release**. Everything below is a way that invariant could be broken - a
 * withdraw op aimed at a placeholder, a client-chosen op index, an id mutation by the dung-item or
 * skillcape sweeps, a merge that clamps a computed 0 up to 1 - so a failure here is a duplication
 * or item-loss bug, not a cosmetic one.
 */
class BankPlaceholderTest {

    private companion object {
        /** Abyssal whip: not stackable, takes metadata. */
        const val WHIP = 4151

        /** Wooden shield. */
        const val SHIELD = 1171

        /** Bronze arrows: stackable. */
        const val ARROWS = 882

        /** Coins. */
        const val COINS = 995

        /** Iron ore: not stackable, has a note form. */
        const val ORE = 440

        /** In `ItemConstants.isDungItem`'s range, so the login sweep turns it into coins. */
        const val DUNG_ITEM = 15750
        const val DUNG_ITEM_2 = 15751

        /** Ids used only to fill the bank up to its 800-entry limit. None are blocked from banking. */
        const val FILLER_BASE = 30000

        const val INV_CONTAINER = 93
        const val BANK_CONTAINER = 95

        /** The bank interface's toggle-frame sprites. */
        const val TOGGLE_SPRITE_OFF = 1431
        const val TOGGLE_SPRITE_ON = 1433

        /** xp for level 99. */
        const val XP_99 = 13_034_431.0
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

    /** Ticks, then hands back everything the client would actually have received. */
    private fun HarnessPlayer.settle(): List<ServerProt> {
        WorldHarness.tick()
        return session.flushed
    }

    private fun List<ServerProt>.touches(container: Int): Boolean = any {
        (it is UpdateInvPartial && it.containerId == container) ||
            (it is UpdateInvFull && it.containerId == container)
    }

    /** The (id, amount) the client was told to draw in [slot] of [container], newest last. */
    private fun List<ServerProt>.slotUpdates(container: Int, slot: Int): List<Pair<Int, Int>> =
        filterIsInstance<UpdateInvPartial>()
            .filter { it.containerId == container }
            .flatMap { p -> p.updates.filter { it.slot == slot }.map { it.itemId to it.amount } }

    /** A click on interface 762, as `IfButtonHandler` would dispatch it. */
    private fun Player.clickBank(component: Int, slot: Int, op: Int) {
        PluginManager.handle(ButtonClickEvent(this, 762, component, slot, -1, op))
    }

    /**
     * Empties [id]'s slot through the real slot-release path with the toggle on, which is the only
     * way a placeholder is ever created. The item is not handed to the inventory - that part is
     * asserted on its own in [withdrawingTheLastOfAnItemLeavesAPlaceholder].
     */
    private fun Bank.givePlaceholder(id: Int, tab: Int = 0) {
        val was = isPlaceholdersEnabled
        isPlaceholdersEnabled = true
        addItem(Item(id, 1), tab, false)
        removeItem(getItemSlot(id), 1, false, false)
        isPlaceholdersEnabled = was
    }

    /** Puts the client's copy of the container in sync so a later refresh sends a real diff. */
    private fun Bank.primeClientCopy() {
        sendItems()
        refreshItems()
    }

    // ------------------------------------------------------------------ creating placeholders

    /**
     * Withdrawing everything with the toggle on keeps the slot: same id, amount 0, tab the same
     * length, and the client is told about the amount-0 slot rather than a shorter container.
     */
    @Test
    fun withdrawingTheLastOfAnItemLeavesAPlaceholder() {
        val harness = login("phwithdraw")
        val player = harness.player
        val bank = player.bank
        bank.clear()
        bank.isPlaceholdersEnabled = true
        bank.addItem(Item(ORE, 20), false)
        bank.addItem(Item(COINS, 1000), false)
        player.inventory.items.clear()
        player.inventory.refresh()
        bank.primeClientCopy()
        WorldHarness.tick()
        harness.clearSent()

        player.clickBank(95, 0, 6) // Withdraw-All

        assertEquals(20, player.inventory.getNumberOf(ORE, false), "the withdrawal never reached the inventory")
        assertEquals(2, bank.bankTabs[0].size, "the tab shrank instead of keeping a placeholder")
        val entry = bank.bankTabs[0][0]
        assertEquals(ORE, entry.id, "the placeholder lost the item id")
        assertEquals(0, entry.amount, "the placeholder is not empty")
        assertTrue(Bank.isPlaceholder(entry), "the entry does not read as a placeholder")
        assertEquals(2, bank.bankSize, "the placeholder stopped using a bank slot")

        val flushed = harness.settle()
        assertEquals(
            ORE to 0,
            flushed.slotUpdates(BANK_CONTAINER, 0).last(),
            "the client was not told to draw slot 0 as ($ORE, 0); flushed=${flushed.slotUpdates(BANK_CONTAINER, 0)}"
        )
    }

    /** With the toggle off the slot is released, exactly as before placeholders existed. */
    @Test
    fun withdrawingTheLastOfAnItemWithTheToggleOffReleasesTheSlot() {
        val harness = login("phtoggleoff")
        val player = harness.player
        val bank = player.bank
        bank.clear()
        assertFalse(bank.isPlaceholdersEnabled, "placeholders must default to off")
        bank.addItem(Item(ORE, 20), false)
        bank.addItem(Item(COINS, 1000), false)
        player.inventory.items.clear()
        player.inventory.refresh()

        player.clickBank(95, 0, 6)

        assertEquals(1, bank.bankTabs[0].size, "the released slot is still there")
        assertEquals(COINS, bank.bankTabs[0][0].id, "the wrong entry survived")
        assertFalse(bank.hasPlaceholders(), "a placeholder was created with the toggle off")
    }

    /**
     * The toggle only governs *new* placeholders. Turning it off must never delete the ones a
     * player already arranged - that would be a silent loss of their bank layout.
     */
    @Test
    fun turningTheToggleOffKeepsExistingPlaceholders() {
        val harness = login("phkeep")
        val player = harness.player
        val bank = player.bank
        bank.clear()
        bank.isPlaceholdersEnabled = true
        bank.givePlaceholder(ORE)
        bank.addItem(Item(COINS, 1000), false)
        player.inventory.items.clear()
        player.inventory.refresh()
        harness.clearSent()

        player.clickBank(124, -1, 1) // the placeholder toggle button

        assertFalse(bank.isPlaceholdersEnabled, "the button did not turn the setting off")
        assertEquals(1, bank.countPlaceholders(), "turning the setting off deleted an existing placeholder")

        // ...and the next emptied slot is released rather than kept.
        player.clickBank(95, bank.getRealSlotIndexOf(COINS), 6)
        assertEquals(1, bank.countPlaceholders(), "a placeholder was created with the setting off")
        assertNull(bank.getItem(COINS), "the coins were not withdrawn")

        val flushed = harness.settle()
        assertTrue(
            flushed.any { it is IfSetGraphic && it.interfaceId == 762 && it.componentId == 124 && it.spriteId == TOGGLE_SPRITE_OFF },
            "the toggle button was not repainted as off"
        )
    }

    /** Opening the bank lights the toggle button for whatever the saved setting is. */
    @Test
    fun openingTheBankPaintsTheToggleButton() {
        val harness = login("phbutton")
        val player = harness.player
        player.bank.clear()
        player.bank.isPlaceholdersEnabled = true
        harness.clearSent()

        player.bank.open()

        val flushed = harness.settle()
        assertTrue(
            flushed.any { it is IfSetGraphic && it.interfaceId == 762 && it.componentId == 124 && it.spriteId == TOGGLE_SPRITE_ON },
            "opening the bank did not light the placeholder toggle"
        )
    }

    // ------------------------------------------------------------------ nothing comes out of one

    /**
     * Every withdraw op the client can send, aimed at a placeholder: 1/5/10, last-X, X, All and
     * All-but-one. None of them may hand out an item, and none may touch the inventory - the op
     * index is the client's choice, so the refusal has to live in the bank, not in the interface.
     */
    @Test
    fun everyWithdrawOpOnAPlaceholderIsANoOp() {
        val harness = login("phnowithdraw")
        val player = harness.player
        val bank = player.bank
        bank.clear()
        bank.isPlaceholdersEnabled = true
        bank.givePlaceholder(ORE)
        bank.addItem(Item(COINS, 1000), false)
        player.inventory.items.clear()
        player.inventory.refresh()
        bank.lastX = 7
        bank.primeClientCopy()
        WorldHarness.tick()
        harness.clearSent()

        val placeholder = bank.bankTabs[0][0]
        for (op in listOf(1, 2, 3, 4, 6, 7)) {
            player.clickBank(95, 0, op)
            assertSame(placeholder, bank.bankTabs[0][0], "op $op replaced the placeholder entry")
            assertEquals(0, bank.bankTabs[0][0].amount, "op $op gave the placeholder an amount")
            assertEquals(0, player.inventory.getNumberOf(ORE, false), "op $op withdrew from a placeholder")
        }

        // Op 5 asks for an amount first; the answer arrives later, so run the callback the way the
        // client's reply would.
        player.clickBank(95, 0, 5)
        val enterAmount = assertNotNull(
            player.tempAttribs.getO<InputIntegerEvent>("pluginInteger"),
            "Withdraw-X did not ask for an amount"
        )
        enterAmount.run(1000)
        assertSame(placeholder, bank.bankTabs[0][0], "Withdraw-X replaced the placeholder entry")
        assertEquals(0, player.inventory.getNumberOf(ORE, false), "Withdraw-X withdrew from a placeholder")

        assertEquals(2, bank.bankTabs[0].size, "the bank changed shape")
        assertEquals(1000, assertNotNull(bank.getItem(COINS)).amount, "the neighbouring stack was touched")
        assertTrue(player.inventory.items.array().all { it == null }, "something reached the inventory")
        assertFalse(harness.settle().touches(INV_CONTAINER), "an inventory (93) update was sent for a refused withdrawal")
    }

    /** `withdrawItemDel` - the house servant's fetch - is a withdrawal too, so it refuses as well. */
    @Test
    fun withdrawItemDelRefusesAPlaceholder() {
        val harness = login("phservant")
        val bank = harness.player.bank
        bank.clear()
        bank.isPlaceholdersEnabled = true
        bank.givePlaceholder(ORE)

        assertFalse(bank.containsItem(ORE, 1), "a placeholder answered containsItem")
        bank.withdrawItemDel(ORE, 1)

        assertEquals(1, bank.countPlaceholders(), "the placeholder was consumed")
        assertEquals(0, bank.bankTabs[0][0].amount, "the placeholder gained an amount")
    }

    // ------------------------------------------------------------------ filling one back up

    /** A deposit lands in the placeholder's own slot, which is the point of keeping it. */
    @Test
    fun depositingOntoAPlaceholderRefillsItsSlot() {
        val harness = login("phdeposit")
        val player = harness.player
        val bank = player.bank
        bank.clear()
        bank.isPlaceholdersEnabled = true
        bank.givePlaceholder(ORE)
        bank.addItem(Item(COINS, 1000), false)
        player.inventory.items.clear()
        player.inventory.items.set(0, Item(ORE, 25))
        player.inventory.refresh()

        assertTrue(player.bank.depositItem(0, Int.MAX_VALUE, true), "the deposit was refused")

        assertEquals(2, bank.bankTabs[0].size, "the deposit appended instead of filling the placeholder")
        assertEquals(ORE, bank.bankTabs[0][0].id, "the placeholder's slot holds the wrong item")
        assertEquals(25, bank.bankTabs[0][0].amount, "the deposited amount is wrong")
        assertNull(player.inventory.getItem(0), "the ore stayed in the inventory")
        assertFalse(bank.hasPlaceholders(), "the placeholder survived its own refill")
    }

    /**
     * A metadata item cannot be merged into a stack, so it replaces the placeholder outright - the
     * branch that made the placeholder fill explicit rather than leaning on the merge path.
     */
    @Test
    fun depositingAMetadataItemReplacesThePlaceholder() {
        val harness = login("phmeta")
        val player = harness.player
        val bank = player.bank
        bank.clear()
        bank.isPlaceholdersEnabled = true
        bank.givePlaceholder(WHIP)
        player.inventory.items.clear()
        player.inventory.items.set(0, Item(WHIP, 1).apply { addMetaData("combatCharges", 30) })
        player.inventory.refresh()

        assertTrue(player.bank.depositItem(0, Int.MAX_VALUE, true), "the deposit was refused")

        assertEquals(1, bank.bankTabs[0].size, "the charged whip did not take the placeholder's slot")
        val banked = bank.bankTabs[0][0]
        assertEquals(1, banked.amount, "a metadata item must bank as a single")
        assertEquals(30, banked.getMetaDataI("combatCharges"), "the bank dropped the metadata")
    }

    /** Withdrawing a metadata item releases its slot: charged items get no placeholder. */
    @Test
    fun aMetadataItemLeavesNoPlaceholder() {
        val harness = login("phmetaout")
        val player = harness.player
        val bank = player.bank
        bank.clear()
        bank.isPlaceholdersEnabled = true
        bank.addItem(Item(WHIP, 1).apply { addMetaData("combatCharges", 30) }, false)
        bank.addItem(Item(COINS, 1000), false)
        player.inventory.items.clear()
        player.inventory.refresh()

        player.clickBank(95, 0, 6)

        assertEquals(1, bank.bankTabs[0].size, "a charged item left a placeholder behind")
        assertEquals(30, assertNotNull(player.inventory.getItem(0)).getMetaDataI("combatCharges"), "the whip lost its charges")
    }

    // ------------------------------------------------------------------ releasing

    /** Release (op 9) frees a placeholder's slot, and does nothing at all to a real item. */
    @Test
    fun releaseFreesAPlaceholderAndSparesARealItem() {
        val harness = login("phrelease")
        val player = harness.player
        val bank = player.bank
        bank.clear()
        bank.isPlaceholdersEnabled = true
        bank.givePlaceholder(ORE)
        bank.addItem(Item(COINS, 1000), false)

        // Op 9 on the real item: the client picks the op index, so this has to be a no-op.
        player.clickBank(95, 1, 9)
        assertEquals(2, bank.bankTabs[0].size, "Release removed a real item")
        assertEquals(1000, assertNotNull(bank.getItem(COINS)).amount, "Release changed a real stack")

        player.clickBank(95, 0, 9)
        assertEquals(1, bank.bankTabs[0].size, "Release did not free the placeholder's slot")
        assertEquals(COINS, bank.bankTabs[0][0].id, "Release freed the wrong slot")
        assertFalse(bank.hasPlaceholders(), "the placeholder is still there")
    }

    /**
     * Release-all asks first, then clears every placeholder across every tab, destroys a tab left
     * with nothing in it, and leaves real items exactly where they were.
     */
    @Test
    fun releaseAllClearsEveryPlaceholderAndCollapsesEmptiedTabs() {
        val harness = login("phreleaseall")
        val player = harness.player
        val bank = player.bank
        bank.clear()
        bank.isPlaceholdersEnabled = true
        bank.createTab()
        bank.createTab()
        bank.addItem(Item(COINS, 1000), 0, false)
        bank.givePlaceholder(ORE, 0)
        bank.givePlaceholder(WHIP, 1)
        bank.addItem(Item(ARROWS, 50), 2, false)
        bank.givePlaceholder(SHIELD, 2)
        assertEquals(3, bank.countPlaceholders(), "the fixture is wrong")

        player.clickBank(126, -1, 1) // release-all button
        val conversation = assertNotNull(player.conversation, "release-all did not ask for confirmation")
        conversation.process(1) // "No"

        assertEquals(3, bank.countPlaceholders(), "answering No released placeholders anyway")

        player.clickBank(126, -1, 1)
        assertNotNull(player.conversation, "release-all did not ask for confirmation").process(0) // "Yes"

        assertFalse(bank.hasPlaceholders(), "placeholders survived release-all")
        assertEquals(2, bank.bankTabs.size, "the tab left holding only a placeholder was not destroyed")
        assertEquals(1, bank.bankTabs[0].size, "the main tab is the wrong size")
        assertEquals(COINS, bank.bankTabs[0][0].id, "the main tab lost its coins")
        assertEquals(1000, bank.bankTabs[0][0].amount, "the coins changed amount")
        assertEquals(1, bank.bankTabs[1].size, "the surviving tab is the wrong size")
        assertEquals(ARROWS, bank.bankTabs[1][0].id, "the surviving tab lost its arrows")
        assertEquals(50, bank.bankTabs[1][0].amount, "the arrows changed amount")
        assertEquals(2, bank.bankSize, "the bank size does not match its contents")
    }

    // ------------------------------------------------------------------ moving one around

    /** A placeholder drags like any other entry, both by swap and in insert mode. */
    @Test
    fun aPlaceholderSwapsAndInsertsLikeAnItem() {
        val harness = login("phdrag")
        val player = harness.player
        val bank = player.bank
        bank.clear()
        bank.isPlaceholdersEnabled = true
        bank.givePlaceholder(ORE)
        bank.addItem(Item(COINS, 1000), false)
        bank.addItem(Item(ARROWS, 50), false)

        bank.switchItem(0, 2, 95, 95)
        assertEquals(listOf(ARROWS, COINS, ORE), bank.bankTabs[0].map { it.id }, "the swap moved the wrong entries")
        assertEquals(listOf(50, 1000, 0), bank.bankTabs[0].map { it.amount }, "the swap changed an amount")

        bank.switchInsertItems()
        bank.switchItem(2, 0, 95, 95)
        assertEquals(listOf(ORE, ARROWS, COINS), bank.bankTabs[0].map { it.id }, "the insert moved the wrong entries")
        assertEquals(listOf(0, 50, 1000), bank.bankTabs[0].map { it.amount }, "the insert changed an amount")
    }

    /**
     * The drag packet's slots are the client's, so they are range-checked before the bank sees
     * them: with more than one tab, a negative slot used to index a tab array out of bounds.
     */
    @Test
    fun theDragHandlerRejectsAnOutOfRangeBankSlot() {
        val harness = login("phdragbad")
        val player = harness.player
        val bank = player.bank
        bank.clear()
        bank.isPlaceholdersEnabled = true
        bank.createTab()
        bank.addItem(Item(COINS, 1000), 1, false)
        bank.addItem(Item(ARROWS, 50), 0, false)
        player.bank.open()
        assertTrue(player.interfaceManager.topOpen(762), "the bank interface is not open")

        val handler = IfDragOntoIfHandler()
        runBlocking {
            handler.handle(player, IfDragOntoIf(762, 95, -1, -1, 762, 95, 0, -1))
            handler.handle(player, IfDragOntoIf(762, 95, 0, -1, 762, 95, Bank.MAX_BANK_SIZE, -1))
        }

        assertEquals(listOf(COINS), bank.bankTabs[1].map { it.id }, "an out-of-range drag moved something")
        assertEquals(listOf(ARROWS), bank.bankTabs[0].map { it.id }, "an out-of-range drag moved something")

        // A drag with both slots in range still works: tab 1 comes first in the client's numbering.
        runBlocking { handler.handle(player, IfDragOntoIf(762, 95, 0, -1, 762, 95, 1, -1)) }
        assertEquals(listOf(ARROWS), bank.bankTabs[1].map { it.id }, "a valid drag did not swap the entries")
        assertEquals(listOf(COINS), bank.bankTabs[0].map { it.id }, "a valid drag did not swap the entries")
    }

    // ------------------------------------------------------------------ a placeholder is not the item

    /** Everything that answers "does the player have this" must say no for a placeholder. */
    @Test
    fun aPlaceholderIsNotTheItem() {
        val harness = login("phlookup")
        val bank = harness.player.bank
        bank.clear()
        bank.isPlaceholdersEnabled = true
        bank.givePlaceholder(ORE)
        bank.addItem(Item(COINS, 1000), false)

        assertFalse(bank.containsItem(ORE), "containsItem said a placeholder is the item")
        assertFalse(bank.containsItem(ORE, 1), "containsItem(amount) said a placeholder is the item")
        assertNull(bank.getItem(ORE), "getItem handed back a placeholder")
        assertNull(bank.getItemSlot(ORE), "getItemSlot pointed at a placeholder")
        assertNull(bank.getSlot(ORE), "getSlot pointed at a placeholder")
        assertNotNull(bank.getPlaceholderSlot(ORE), "getPlaceholderSlot did not find it")
        assertEquals(2, bank.bankSize, "a placeholder must still use a bank slot")

        // deleteItem is a subtraction, and must not find the placeholder to subtract from.
        bank.deleteItem(ORE, 5)
        assertEquals(0, bank.bankTabs[0][0].amount, "deleteItem changed a placeholder")
    }

    /**
     * The dung-item sweep turns banked items into coins by mutating the id in place. On a
     * placeholder that would mint an amount-0 coin row, so the placeholder is released instead.
     */
    @Test
    fun theDungItemSweepReleasesAPlaceholderAndConvertsRealItems() {
        val harness = login("phdung")
        val player = harness.player
        val bank = player.bank
        bank.clear()
        bank.isPlaceholdersEnabled = true
        bank.addItem(Item(DUNG_ITEM, 3), false)
        bank.givePlaceholder(DUNG_ITEM_2)
        bank.addItem(Item(ARROWS, 50), false)
        bank.refreshItems()

        player.removeDungItems()

        assertFalse(bank.hasPlaceholders(), "the dung placeholder was left in the bank")
        assertEquals(2, bank.bankSize, "the placeholder's slot was not freed")
        assertEquals(COINS, bank.bankTabs[0][0].id, "the real dung item was not turned into coins")
        assertEquals(3, bank.bankTabs[0][0].amount, "the converted amount changed")
        assertEquals(ARROWS, bank.bankTabs[0][1].id, "an unrelated item was touched")
    }

    /** The skillcape trim rewrites banked cape ids in place, and must leave placeholders alone. */
    @Test
    fun theSkillcapeTrimSkipsAPlaceholder() {
        val harness = login("phcape")
        val player = harness.player
        val bank = player.bank
        player.skills.setXp(Constants.ATTACK, XP_99)
        player.skills.setXp(Constants.STRENGTH, XP_99)
        assertTrue(player.skills.checkMulti99s(), "the fixture needs two 99s")
        bank.clear()
        bank.isPlaceholdersEnabled = true
        bank.addItem(Item(Skillcapes.Attack.untrimmed, 1), false)
        bank.givePlaceholder(Skillcapes.Mining.untrimmed)

        player.skills.trimCapes()

        assertEquals(Skillcapes.Attack.trimmed, bank.bankTabs[0][0].id, "the real cape was not trimmed")
        assertEquals(Skillcapes.Mining.untrimmed, bank.bankTabs[0][1].id, "the placeholder's id was rewritten")
        assertEquals(0, bank.bankTabs[0][1].amount, "the placeholder gained an amount")
    }

    // ------------------------------------------------------------------ capacity and persistence

    /**
     * Placeholders use bank slots (decided), so a bank full of them is full - but a deposit that
     * lands in an existing placeholder needs no new slot and still goes through.
     */
    @Test
    fun placeholdersCountTowardsTheBankLimit() {
        val harness = login("phcapacity")
        val player = harness.player
        val bank = player.bank
        bank.clear()
        bank.isPlaceholdersEnabled = true
        for (i in 0 until Bank.MAX_BANK_SIZE - 1) bank.addItem(Item(FILLER_BASE + i, 1), false)
        bank.givePlaceholder(ORE)
        assertEquals(Bank.MAX_BANK_SIZE, bank.bankSize, "the fixture did not fill the bank")

        player.inventory.items.clear()
        player.inventory.items.set(0, Item(ARROWS, 10))
        player.inventory.refresh()
        harness.clearSent()

        assertFalse(player.bank.depositItem(0, Int.MAX_VALUE, true), "a full bank accepted a new item")
        assertEquals(Bank.MAX_BANK_SIZE, bank.bankSize, "the bank grew past its limit")
        assertEquals(10, player.inventory.getNumberOf(ARROWS, false), "the refused deposit ate the items")
        assertTrue(
            harness.settle().any { it is GameMessage && it.message == "Not enough space in your bank." },
            "the refusal was silent"
        )

        // The same full bank still accepts what one of its placeholders is holding a slot for.
        player.inventory.items.clear()
        player.inventory.items.set(0, Item(ORE, 5))
        player.inventory.refresh()
        assertTrue(player.bank.depositItem(0, Int.MAX_VALUE, true), "a deposit into an existing placeholder was refused")
        assertEquals(Bank.MAX_BANK_SIZE, bank.bankSize, "filling a placeholder changed the bank size")
        assertEquals(5, assertNotNull(bank.getItem(ORE)).amount, "the deposit did not land in the placeholder")
    }

    /**
     * The save round trip: Gson builds `Item` through Unsafe, so amount 0 survives - the ordinary
     * constructors would clamp it up to 1 and hand every placeholder back as a free item.
     */
    @Test
    fun gsonRoundTripKeepsPlaceholdersAndTheToggle() {
        val harness = login("phsave")
        val bank = harness.player.bank
        bank.clear()
        bank.isPlaceholdersEnabled = true
        bank.createTab()
        bank.addItem(Item(COINS, 1000), 0, false)
        bank.givePlaceholder(ORE, 0)
        bank.addItem(Item(WHIP, 1).apply { addMetaData("combatCharges", 30) }, 1, false)

        val loaded = assertNotNull(
            JsonFileManager.fromJSONString(JsonFileManager.toJson(bank), Bank::class.java),
            "the bank did not survive the round trip"
        )

        assertTrue(loaded.isPlaceholdersEnabled, "the placeholder setting was not saved")
        assertEquals(2, loaded.bankTabs.size, "a tab was lost")
        assertEquals(COINS, loaded.bankTabs[0][0].id, "the coins were lost")
        assertEquals(1000, loaded.bankTabs[0][0].amount, "the coin amount changed")
        assertEquals(ORE, loaded.bankTabs[0][1].id, "the placeholder lost its id")
        assertEquals(0, loaded.bankTabs[0][1].amount, "the placeholder came back as a real item")
        assertEquals(30, loaded.bankTabs[1][0].getMetaDataI("combatCharges"), "the metadata was lost")
    }

    /** A save carrying a negative amount from an old over-subtraction loads as a placeholder. */
    @Test
    fun loadingNormalisesANegativeAmountToAPlaceholder() {
        val harness = login("phnegative")
        val player = harness.player
        val bank = player.bank
        bank.clear()
        bank.addItem(Item(ORE, 5), false)
        // setAmount refuses a negative, so write the field the way a bad save would have.
        bank.bankTabs[0][0].amount = -3

        bank.setPlayer(player)

        assertEquals(0, bank.bankTabs[0][0].amount, "a negative amount was left in the bank")
        assertTrue(Bank.isPlaceholder(bank.bankTabs[0][0]), "the normalised entry is not a placeholder")
    }

    /** The client's flattened slot for the first entry of [id]; tabs 1..n come before tab 0. */
    private fun Bank.getRealSlotIndexOf(id: Int): Int {
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
}
