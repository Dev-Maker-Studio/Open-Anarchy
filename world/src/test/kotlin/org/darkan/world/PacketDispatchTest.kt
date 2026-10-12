package org.darkan.world

import com.rs.game.World
import com.rs.game.model.gameobject.GameObject
import com.rs.plugin.events.ItemOnObjectEvent
import com.rs.plugin.handlers.ItemOnObjectHandler
import org.darkan.core.gameval.gen.Interface
import org.darkan.core.model.ObjectShape
import org.darkan.core.model.Rights
import org.darkan.core.net.prot.packet.Chat
import org.darkan.core.net.prot.packet.GameMessage
import org.darkan.core.net.prot.packet.IfButton
import org.darkan.core.net.prot.packet.IfDragOntoIf
import org.darkan.core.net.prot.packet.IfOnObject
import org.darkan.core.net.prot.packet.MessagePublic
import org.darkan.world.harness.HarnessPlayer
import org.darkan.world.harness.WorldHarness
import world.gregs.voidps.type.Tile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PacketDispatchTest {

    private val loggedIn = mutableListOf<HarnessPlayer>()
    private val spawned = mutableListOf<GameObject>()

    @AfterTest
    fun tearDown() {
        loggedIn.forEach { WorldHarness.logout(it) }
        loggedIn.clear()
        spawned.forEach { World.removeObject(it) }
        spawned.clear()
    }

    private fun login(name: String): HarnessPlayer =
        WorldHarness.login(name, Rights.OWNER).also {
            loggedIn.add(it)
            WorldHarness.tick()
        }


    @Test
    fun itemOnObjectReachesItemOnObjectEvent() {
        val harness = login("itemonobj")
        val player = harness.player
        val fired = mutableListOf<Pair<Int, Int>>()
        val objectId = 1276

        val tile = Tile.of(player.x + 1, player.y, player.plane)
        val obj = GameObject(objectId, ObjectShape.SCENERY_INTERACT, 0, tile)
        World.spawnObject(obj)
        spawned.add(obj)

        registerItemOnObjectProbe(objectId) { e -> fired.add(e.item.id to e.objectId) }

        player.inventory.items.clear()
        player.inventory.refresh()
        player.inventory.addItem(1511, 1)
        assertEquals(1, player.inventory.getNumberOf(1511), "test setup: logs never entered the inventory")

        harness.push(IfOnObject(Interface.inventory, 0, 0, 1511, objectId, tile.x, tile.y, false))
        WorldHarness.tick()

        assertTrue(fired.isNotEmpty(), "ItemOnObjectEvent never fired for item 1511 on object $objectId")
        assertEquals(1511 to objectId, fired.first())
    }

    @Test
    fun itemOnObjectIgnoresAnItemThePlayerDoesNotHold() {
        val harness = login("itemonobjspoof")
        val player = harness.player
        val fired = mutableListOf<Pair<Int, Int>>()
        val objectId = 1277

        val tile = Tile.of(player.x + 1, player.y, player.plane)
        val obj = GameObject(objectId, ObjectShape.SCENERY_INTERACT, 0, tile)
        World.spawnObject(obj)
        spawned.add(obj)

        registerItemOnObjectProbe(objectId) { e -> fired.add(e.item.id to e.objectId) }

        player.inventory.items.clear()
        player.inventory.refresh()
        player.inventory.addItem(995, 100)
        harness.push(IfOnObject(Interface.inventory, 0, 0, 1127, objectId, tile.x, tile.y, false))
        WorldHarness.tick()

        assertTrue(fired.isEmpty(), "an item the player does not hold was accepted: $fired")
    }

    private fun registerItemOnObjectProbe(objectId: Int, onEvent: (ItemOnObjectEvent) -> Unit) {
        val handler = ItemOnObjectHandler(arrayOf<Any>(objectId), null) { e -> onEvent(e) }
        ItemOnObjectEvent.registerMethod(ItemOnObjectEvent::class.java, handler)
    }


    @Test
    fun friendsChatLineIsNotBroadcastAsPublicChat() {
        val harness = login("fcchat")
        harness.player.chatType = 2
        harness.clearSent()

        harness.push(Chat(type = -1, color = 0, effect = 0, message = "friends only"))
        WorldHarness.tick()

        assertFalse(
            harness.sent.any { it is MessagePublic },
            "a friends-chat line was broadcast as public chat: ${harness.sent.map { it::class.simpleName }}"
        )
    }

    @Test
    fun publicChatStillReachesNearbyPlayers() {
        val harness = login("pubchat")
        harness.player.chatType = 0
        harness.clearSent()

        harness.push(Chat(type = -1, color = 0, effect = 0, message = "hello world"))
        WorldHarness.tick()

        val public = harness.sent.filterIsInstance<MessagePublic>()
        assertTrue(public.isNotEmpty(), "public chat produced no MessagePublic")
        assertEquals("hello world", public.first().message.message)
    }


    @Test
    fun draggingInventorySlotsSwapsThem() {
        val harness = login("dragitems")
        val inv = harness.player.inventory
        inv.items.clear()
        inv.refresh()
        inv.addItem(1511, 1)
        inv.addItem(1521, 1)
        assertEquals(1511, inv.getItem(0)?.id, "test setup: slot 0")
        assertEquals(1521, inv.getItem(1)?.id, "test setup: slot 1")

        harness.push(IfDragOntoIf(Interface.inventory, 0, 0, 1511, Interface.inventory, 0, 29, 1521))
        WorldHarness.tick()

        assertEquals(1521, inv.getItem(0)?.id, "slots were not swapped")
        assertEquals(1511, inv.getItem(1)?.id, "slots were not swapped")
    }


    @Test
    fun playerWithoutAChosenAccountTypeHasPacketsDropped() {
        val harness = WorldHarness.login("nogamemode", Rights.OWNER, chosenAccountType = false)
        loggedIn.add(harness)
        WorldHarness.tick()
        harness.player.isChosenAccountType = false

        val inv = harness.player.inventory
        inv.items.clear()
        inv.refresh()
        inv.addItem(1511, 1)
        inv.addItem(1521, 1)
        harness.push(IfDragOntoIf(Interface.inventory, 0, 0, 1511, Interface.inventory, 0, 29, 1521))
        WorldHarness.tick()

        assertEquals(1511, inv.getItem(0)?.id, "the gate is meant to drop this packet")
    }


    @Test
    fun lockedPlayerWithTransformationRingStillGetsButtonClicks() {
        val harness = login("transformlock")
        harness.player.lock()
        harness.player.tempAttribs.setB("TransformationRing", true)
        harness.clearSent()

        harness.push(IfButton(opNum = 1, interfaceId = 182, componentId = 6, slotId = -1, itemId = -1))
        WorldHarness.tick()

        assertTrue(
            harness.sent.any { it is GameMessage && it.message.contains("can't log out while performing an action") },
            "the click was dropped before dispatch; sent=${harness.sent.map { it::class.simpleName }}"
        )
    }

    @Test
    fun lockedPlayerWithoutTransformationRingIsStillGated() {
        val harness = login("plainlock")
        harness.player.lock()
        harness.clearSent()

        harness.push(IfButton(opNum = 1, interfaceId = 182, componentId = 6, slotId = -1, itemId = -1))
        WorldHarness.tick()

        assertNull(
            harness.sent.firstOrNull { it is GameMessage && it.message.contains("can't log out") },
            "a locked player's button click was dispatched"
        )
    }
}
