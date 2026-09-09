package org.darkan.world

import com.rs.Settings
import com.rs.game.content.skills.crafting.urns.Urn
import com.rs.game.content.skills.crafting.urns.Urns
import com.rs.game.model.entity.player.Player
import com.rs.plugin.PluginManager
import com.rs.plugin.events.ItemClickEvent
import com.rs.plugin.events.ItemOnItemEvent
import org.darkan.core.model.Item
import org.darkan.world.harness.HarnessPlayer
import org.darkan.world.harness.WorldHarness
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UrnFlowTest {

    private val loggedIn = mutableListOf<HarnessPlayer>()

    @AfterTest
    fun tearDown() {
        loggedIn.forEach { WorldHarness.logout(it) }
        loggedIn.clear()
    }

    private fun login(name: String): Player {
        val harness = WorldHarness.login(name).also { loggedIn.add(it) }
        WorldHarness.tick()
        harness.player.inventory.items.clear()
        return harness.player
    }

    private fun Player.click(obj: Int, option: String) {
        val slot = inventory.items.array().indexOfFirst { it?.id == obj }
        assertTrue(slot >= 0, "no $obj in the inventory")
        PluginManager.handle(ItemClickEvent(this, inventory.items.get(slot), slot, option))
    }

    private fun Player.count(obj: Int) = inventory.getNumberOf(obj, false)

    private fun xpRate() = maxOf(Settings.getConfig().xpRate, 1)

    @Test
    fun theOptionActivatesEveryUrnARuneCanBeFound() {
        val player = login("urnoption")
        for (urn in listOf(Urn.CRACKED_FARMING, Urn.CRACKED_HUNTER, Urn.CRACKED_RUNECRAFTING, Urn.CRACKED_MINING, Urn.IMPIOUS)) {
            player.inventory.addItem(Item(urn.noRune, 3))
            player.inventory.addItem(Item(urn.activator, 2))

            player.click(urn.noRune, Urns.activatorOption(urn))

            assertEquals(2, player.count(urn.empty), "$urn empty urns")
            assertEquals(1, player.count(urn.noRune), "$urn urns left without a rune")
            assertEquals(0, player.count(urn.activator), "$urn runes left")
            assertEquals(urn.runeIn, player.nextAnimation)
            player.inventory.items.clear()
        }
    }

    @Test
    fun theWrongRuneIsRefused() {
        val player = login("urnwrong")
        val urn = Urn.CRACKED_FARMING
        player.inventory.addItem(Item(urn.noRune, 1))
        player.inventory.addItem(Item(Urn.CRACKED_MINING.activator, 1))

        PluginManager.handle(ItemOnItemEvent(player, player.inventory.items.get(0), player.inventory.items.get(1)))

        assertEquals(1, player.count(urn.noRune))
        assertEquals(0, player.count(urn.empty))
    }

    @Test
    fun skillXpStartsFillsAndTeleportsAnUrnInEveryNewSkill() {
        val player = login("urnfill")
        for (urn in listOf(Urn.CRACKED_FARMING, Urn.FRAGILE_HUNTER, Urn.PLAIN_RUNECRAFTING, Urn.DECORATED_WOODCUTTING, Urn.DECORATED_SMITHING, Urn.CRACKED_COOKING)) {
            player.inventory.addItem(Item(urn.empty, 2))

            player.skills.addXp(urn.skill, 10.0)
            val started = assertNotNull(player.getItemFromInv(urn.started), "$urn never started")
            assertEquals(10.0, started.getMetaDataD("xp"), "$urn started xp")
            assertEquals(1, player.count(urn.empty), "$urn empties after starting")

            player.skills.addXp(urn.skill, urn.capacity)
            assertNull(player.getItemFromInv(urn.started), "$urn still started at capacity")
            assertEquals(1, player.count(urn.full), "$urn full urns")

            val before = player.skills.getXp(urn.skill)
            player.click(urn.full, "Teleport urn")
            assertEquals(0, player.count(urn.full), "$urn full urn after teleport")
            assertEquals(urn.teleportXp * xpRate(), player.skills.getXp(urn.skill) - before, 0.01, "$urn teleport xp")
            assertEquals(urn.teleport, player.nextAnimation)
            player.inventory.items.clear()
        }
    }

    @Test
    fun theLowestTierUrnFillsFirst() {
        val player = login("urnorder")
        player.inventory.addItem(Item(Urn.STRONG_FARMING.empty, 1))
        player.inventory.addItem(Item(Urn.CRACKED_FARMING.empty, 1))

        player.skills.addXp(Urn.CRACKED_FARMING.skill, 5.0)

        assertNotNull(player.getItemFromInv(Urn.CRACKED_FARMING.started))
        assertEquals(1, player.count(Urn.STRONG_FARMING.empty))
    }

    @Test
    fun prayerUrnsFillFromAshesXpAndPayBackMoreThanTheyHold() {
        val player = login("urnprayer")
        player.inventory.addItem(Item(Urn.IMPIOUS.empty, 1))

        assertTrue(Urns.addXp(player, Urn.IMPIOUS, 4.0))
        assertTrue(Urns.addXp(player, Urn.IMPIOUS, Urn.IMPIOUS.capacity))

        assertEquals(1, player.count(Urn.IMPIOUS.full))
        assertEquals(Urn.IMPIOUS.capacity * 1.2, Urn.IMPIOUS.teleportXp, 0.001)
    }
}
