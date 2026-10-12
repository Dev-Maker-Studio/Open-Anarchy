package org.darkan.world

import com.rs.engine.command.Commands
import com.rs.plugin.PluginManager
import com.rs.plugin.events.ButtonClickEvent
import org.darkan.core.model.Rights
import org.darkan.core.net.prot.packet.IfButton
import org.darkan.core.net.prot.packet.LogoutFull
import org.darkan.core.net.prot.packet.LogoutLobby
import org.darkan.core.net.prot.packet.UpdateInvFull
import org.darkan.core.net.prot.packet.UpdateInvPartial
import org.darkan.world.harness.HarnessPlayer
import org.darkan.world.harness.WorldHarness
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DispatchTest {

    private val INVENTORY_CONTAINER = 93

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
        }

    @Test
    fun commandsAreRegistered() {
        WorldHarness.boot()
        val total = Rights.entries.sumOf { Commands.getCommands(it).size }
        assertTrue(total > 200, "expected the command registry to be populated, got $total commands")
    }

    @Test
    fun itemCommandAddsToInventoryAndSendsUpdate() {
        val harness = login("itemcmd")
        harness.clearSent()

        assertTrue(
            Commands.processCommand(harness.player, "item 4151", false, false),
            "::item was not dispatched by the command registry"
        )

        assertEquals(
            1,
            harness.player.inventory.getNumberOf(4151),
            "the item never reached the inventory container"
        )

        WorldHarness.tick()

        val slots = inventoryUpdatesFor(harness)
        assertTrue(
            slots.isNotEmpty(),
            "no inventory update was queued for container ${INVENTORY_CONTAINER}; " +
                "flushed=${harness.session.flushed.map { it::class.simpleName }}"
        )
        assertTrue(4151 in slots, "the inventory update did not carry item 4151, it carried $slots")
    }

    @Test
    fun giveItemCommandAddsToTargetInventory() {
        val giver = login("giver")
        val target = login("target")
        target.clearSent()

        assertTrue(
            Commands.processCommand(giver.player, "giveitem ${target.player.displayName} 995 5", false, false),
            "::giveitem was not dispatched by the command registry"
        )

        assertEquals(5, target.player.inventory.getNumberOf(995), "coins never reached the target inventory")

        WorldHarness.tick()
        assertTrue(995 in inventoryUpdatesFor(target), "no inventory update carried the coins")
    }

    @Test
    fun logoutButtonExitsToLobby() {
        val harness = login("logoutlobby")
        harness.clearSent()

        harness.push(IfButton(opNum = 1, interfaceId = 182, componentId = 6, slotId = -1, itemId = -1))
        WorldHarness.tick()

        assertTrue(
            harness.session.flushed.any { it is LogoutLobby },
            "LogoutLobby never reached the client; events=${harness.session.events}"
        )
    }

    @Test
    fun logoutButtonLogsOutFully() {
        val harness = login("logoutfull")
        harness.clearSent()

        harness.push(IfButton(opNum = 1, interfaceId = 182, componentId = 13, slotId = -1, itemId = -1))
        WorldHarness.tick()

        assertTrue(
            harness.session.flushed.any { it is LogoutFull },
            "LogoutFull never reached the client; events=${harness.session.events}"
        )
    }

    @Test
    fun buttonClickEventHasRegisteredHandler() {
        val harness = login("buttondispatch")
        assertTrue(
            PluginManager.handle(ButtonClickEvent(harness.player, 182, 6, -1, -1, 1)),
            "PluginManager had no handler registered for ButtonClickEvent on interface 182"
        )
    }

    @Test
    fun logoutTabIsOpenAfterLogin() {
        val harness = login("logouttab")
        assertTrue(
            harness.player.interfaceManager.topOpen(182),
            "interface 182 is not registered as open, so every logout click is dropped"
        )
    }

    private fun inventoryUpdatesFor(harness: HarnessPlayer): Set<Int> {
        val key = INVENTORY_CONTAINER
        val ids = mutableSetOf<Int>()
        harness.session.flushed.forEach { prot ->
            when (prot) {
                is UpdateInvFull -> if (prot.containerId == key) ids.addAll(prot.itemIds.toList())
                is UpdateInvPartial -> if (prot.containerId == key) ids.addAll(prot.updates.map { it.itemId })
                else -> {}
            }
        }
        ids.remove(-1)
        return ids
    }
}
