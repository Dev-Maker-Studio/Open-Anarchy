package org.darkan.world

import com.rs.db.DBItemManager
import com.rs.engine.command.Commands
import com.rs.game.World
import com.rs.game.map.ChunkManager
import com.rs.game.model.entity.player.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.darkan.core.Utils
import org.darkan.core.map.ClipFlag
import org.darkan.core.map.pathfinder.Direction
import org.darkan.core.model.Item
import org.darkan.core.model.Rights
import org.darkan.core.net.prot.ClientProt
import org.darkan.core.net.prot.handler.PacketHandler
import org.darkan.core.net.prot.handler.PacketHandlers
import org.darkan.core.net.prot.packet.AddFriend
import org.darkan.core.net.prot.packet.GameMessage
import org.darkan.core.net.prot.packet.IfOnGroundItem
import org.darkan.core.net.prot.packet.TriggerOnDialogAbort
import org.darkan.world.harness.HarnessPlayer
import org.darkan.core.social.SocialAction
import org.darkan.core.social.gateway.SocialCommand
import org.darkan.world.harness.WorldHarness
import org.darkan.world.server.WorldServer
import org.darkan.world.server.packet.IfOnGroundItemHandler
import org.darkan.world.social.SocialClient
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Config
import world.gregs.voidps.cache.Index
import java.util.Collections
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.util.logging.Logger as JulLogger

class RemainingRegressionsTest {

    private val loggedIn = mutableListOf<HarnessPlayer>()

    @AfterTest
    fun tearDown() {
        clearSocialClient()
        loggedIn.forEach { WorldHarness.logout(it) }
        loggedIn.clear()
    }

    private fun login(name: String, rights: Rights = Rights.OWNER): HarnessPlayer =
        WorldHarness.login(name, rights).also {
            loggedIn.add(it)
            WorldHarness.tick()
        }


    @Test
    fun everyDirectionAngleRoundTripsThroughFromAngle() {
        Direction.entries.forEach { dir ->
            assertEquals(
                dir,
                Direction.fromAngle(dir.angle),
                "Direction.fromAngle(${dir.angle}) should be $dir; the angle->delta table is mirrored"
            )
        }
        assertEquals(Direction.WEST, Direction.fromAngle(4096))
        assertEquals(Direction.EAST, Direction.fromAngle(12288))
    }

    @Test
    fun faceHelpersAcceptFullAngleRangeAndAreInverses() {
        assertTrue(Utils.getBackFace(0).contentEquals(intArrayOf(0, 1)), "angle 0 faces north")
        assertTrue(Utils.getBackFace(8192).contentEquals(intArrayOf(0, -1)), "angle 8192 faces south")
        assertTrue(Utils.getBackFace(4096).contentEquals(intArrayOf(1, 0)), "angle 4096 back face is +x")
        assertTrue(Utils.getBackFace(12288).contentEquals(intArrayOf(-1, 0)), "angle 12288 back face is -x")

        var nonZero = 0
        for (angle in 0 until 16384 step 7) {
            val back = Utils.getBackFace(angle)
            val front = Utils.getFrontFace(angle)
            assertEquals(-back[0], front[0], "front/back x disagree at angle $angle")
            assertEquals(-back[1], front[1], "front/back y disagree at angle $angle")
            if (back[0] != 0 || back[1] != 0) nonZero++
        }
        assertTrue(nonZero > 2000, "getBackFace collapsed to (0,0) for almost every angle ($nonZero non-zero)")
    }


    @Test
    fun telekineticGrabSetsAnActionForSpellbookComponent44() {
        val harness = login("telegrab")
        val player = harness.player
        val tile = player.tile

        assertNotNull(
            World.addGroundItem(Item(995, 1), tile, player),
            "could not place a ground item under the player"
        )
        assertNotNull(
            ChunkManager.getChunk(tile.getChunkId()).getGroundItem(995, tile, player),
            "the ground item is not visible to its owner"
        )

        if (!player.interfaceManager.topOpen(192))
            player.interfaceManager.sendSubSpecific(false, 1477, 96, 192)
        assertTrue(player.interfaceManager.topOpen(192), "interface 192 did not register as open")

        runBlocking {
            IfOnGroundItemHandler().handle(
                player,
                IfOnGroundItem(
                    interfaceId = 192,
                    componentId = 44,
                    slotId = -1,
                    itemIdContainer = -1,
                    itemId = 995,
                    x = tile.x,
                    y = tile.y,
                    forceRun = false
                )
            )
        }

        val action = player.actionManager.action
        assertNotNull(action, "Telekinetic Grab queued no action; the 192/44 branch is missing")
        assertEquals(
            "TelekineticGrabAction",
            action::class.simpleName,
            "an action was set, but not the Telekinetic Grab one"
        )
    }


    @Test
    fun gatewaySendFailureIsReportedAndThePlayerIsTold() {
        val harness = login("gatewaydown")
        val client = unstartedSocialClient()
        assertFalse(
            client.send(SocialCommand(harness.player.username, SocialAction.AddFriend("someone"))),
            "SocialClient.send claimed success with no gateway connection"
        )

        setSocialClient(client)
        harness.clearSent()
        WorldServer.act(harness.player.username, SocialAction.AddFriend("someone"))
        WorldHarness.tick()

        assertTrue(
            harness.sent.any { it is GameMessage && it.message == WorldServer.FRIEND_SERVER_UNAVAILABLE },
            "the player was never told the packet could not be forwarded; sent=${harness.sent}"
        )
    }


    @Test
    fun dbItemManagerLogsTaskFailures() {
        WorldHarness.boot()
        val marker = "db-item-manager-regression-marker"
        val manager = object : DBItemManager("harness-regression") {
            override fun initCollection() {}
        }

        val records = captureLogRecords {
            manager.execute { throw IllegalStateException(marker) }
            waitFor { records -> records.any { it.thrown?.message == marker } }
        }

        val failure = records.firstOrNull { it.thrown?.message == marker }
        assertNotNull(failure, "the failing DB task was never logged; records=${records.map { it.message }}")
        assertEquals(Level.SEVERE, failure.level, "a dropped DB save must be logged at error level")
    }


    @Test
    fun abortDialogueQueuesTriggerOnDialogAbort() {
        val harness = login("abortdialogue")
        harness.clearSent()

        harness.player.abortDialogue()

        assertTrue(
            harness.sent.any { it is TriggerOnDialogAbort },
            "abortDialogue() queued nothing; sent=${harness.sent.map { it::class.simpleName }}"
        )
    }


    @Test
    fun gatewayRejectionForcesLogout() {
        val harness = login("rejected")
        setSocialClient(unstartedSocialClient())

        WorldServer.notifyPlayerOnline(harness.player.account)
        waitFor { harness.player.hasFinished() }

        assertTrue(
            harness.player.hasFinished(),
            "the player stayed logged in after the gateway refused to register them"
        )
    }


    @Test
    fun aThrowingHandlerIsLoggedAtErrorLevelNamingThePacket() {
        val harness = login("throwinghandler")
        val marker = "throwing-handler-regression-marker"
        val handlers = handlerMap()
        val original = handlers[AddFriend::class.java]

        val records = captureLogRecords {
            handlers[AddFriend::class.java] = ThrowingAddFriendHandler(marker)
            try {
                harness.push(AddFriend("someone"))
                WorldHarness.tick()
                waitFor { records -> records.any { it.thrown?.message == marker } }
            } finally {
                if (original != null) handlers[AddFriend::class.java] = original
                else handlers.remove(AddFriend::class.java)
            }
        }

        val failure = records.firstOrNull { it.thrown?.message == marker }
        assertNotNull(failure, "the handler failure was never logged; records=${records.map { it.message }}")
        assertEquals(Level.SEVERE, failure.level, "a handler failure must be logged at error level")
        assertTrue(
            failure.message.contains("AddFriend"),
            "the log line does not name the packet class: ${failure.message}"
        )
        assertFalse(harness.player.hasFinished(), "a throwing handler took the player down with it")
    }

    private class ThrowingAddFriendHandler(private val marker: String) : PacketHandler<Player, AddFriend> {
        override suspend fun handle(player: Player, packet: AddFriend): Unit = throw IllegalStateException(marker)
    }

    @Suppress("UNCHECKED_CAST")
    private fun handlerMap(): MutableMap<Class<out ClientProt>, PacketHandler<*, out ClientProt>> =
        PacketHandlers::class.java.getDeclaredField("PACKET_HANDLERS")
            .apply { isAccessible = true }
            .get(PacketHandlers) as MutableMap<Class<out ClientProt>, PacketHandler<*, out ClientProt>>

    @Test
    fun tileFlagsPrintsDecodedFlagNames() {
        assertTrue(
            ClipFlag.getFlags(ClipFlag.BW_FULL.flag or ClipFlag.PF_FULL.flag)
                .containsAll(listOf(ClipFlag.BW_FULL, ClipFlag.PF_FULL)),
            "ClipFlag.getFlags did not decode a mask"
        )
        assertFalse(ClipFlag.EMPTY in ClipFlag.getFlags(ClipFlag.BW_FULL.flag), "EMPTY must never be reported")
        assertTrue(ClipFlag.getFlags(0).isEmpty(), "an empty mask must decode to no flags")

        val harness = login("tileflags")
        harness.clearSent()
        assertTrue(
            Commands.processCommand(harness.player, "tileflags", false, false),
            "::tileflags was not dispatched"
        )
        val line = harness.sent.filterIsInstance<GameMessage>().map { it.message }
            .firstOrNull { it.startsWith("Clip flags:") }
        assertNotNull(line, "::tileflags printed nothing; sent=${harness.sent}")
        assertTrue(line.contains("["), "::tileflags printed no decoded flag list: $line")
    }


    private fun unstartedSocialClient(): SocialClient = SocialClient(CoroutineScope(Dispatchers.Default)) { }

    private fun setSocialClient(client: SocialClient) {
        WorldServer::class.java.getDeclaredField("socialClient").apply { isAccessible = true }.set(WorldServer, client)
    }

    private fun clearSocialClient() {
        WorldServer::class.java.getDeclaredField("socialClient").apply { isAccessible = true }.set(WorldServer, null)
    }

    private fun captureLogRecords(block: LogCapture.() -> Unit): List<LogRecord> {
        val records = Collections.synchronizedList(mutableListOf<LogRecord>())
        val handler = object : Handler() {
            override fun publish(record: LogRecord?) {
                if (record != null) records.add(record)
            }

            override fun flush() {}
            override fun close() {}
        }
        handler.level = Level.ALL
        val logger = JulLogger.getLogger("DarkanRS")
        logger.addHandler(handler)
        try {
            LogCapture(records).block()
        } finally {
            logger.removeHandler(handler)
        }
        return records.toList()
    }

    private class LogCapture(private val records: MutableList<LogRecord>) {
        fun waitFor(predicate: (List<LogRecord>) -> Boolean) {
            repeat(300) {
                if (predicate(records.toList())) return
                Thread.sleep(10)
            }
        }
    }

    private fun waitFor(condition: () -> Boolean) {
        repeat(200) {
            if (condition()) return
            WorldHarness.tick()
            Thread.sleep(10)
        }
    }
}
