package org.darkan.lobby

import kotlinx.coroutines.runBlocking
import org.darkan.core.model.ChatMessageType
import org.darkan.core.net.prot.packet.FcJoin
import org.darkan.core.net.prot.packet.FriendStatus
import org.darkan.core.net.prot.packet.FriendsChatChannel
import org.darkan.core.net.prot.packet.GameMessage
import org.darkan.core.social.gateway.PresenceSyncReason
import org.darkan.lobby.harness.LobbyHarness
import org.darkan.lobby.harness.RecordingSession
import org.darkan.lobby.social.SocialGateway
import org.darkan.lobby.web.model.sendLobbyMessage
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The lobby's social behaviour, driven end to end through [SocialGateway] with the in-memory
 * account store and a fake world (see [LobbyHarness]).
 *
 * The three regressions these were written for: a friend sitting in the lobby read as offline, a
 * lobby login did not put the player back in the friends chat they had joined, and server text
 * sent to a lobby player was never rendered.
 */
class SocialGatewayTest {

    @BeforeTest
    fun clearGateway() = LobbyHarness.reset()

    /** The single [FriendStatus] record the session most recently received about [displayName]. */
    private fun RecordingSession.lastStatusOf(displayName: String) =
        sent.filterIsInstance<FriendStatus>()
            .flatMap { it.updates.toList() }
            .lastOrNull { it.displayName == displayName }

    /** The friends chat members the session was last told about. */
    private fun RecordingSession.lastChannel() = sent.filterIsInstance<FriendsChatChannel>().lastOrNull()

    private fun FriendsChatChannel.memberList() = players?.toList().orEmpty()

    // ─── Friend presence ─────────────────────────────────────────────

    @Test
    fun `a friend sitting in the lobby is online, not offline`() = runBlocking {
        val watcher = LobbyHarness.account("watcher")
        val friend = LobbyHarness.account("friend")
        LobbyHarness.befriend(watcher, friend)
        LobbyHarness.befriend(friend, watcher)

        LobbyHarness.lobbyLogin(friend)
        val session = LobbyHarness.lobbyLogin(watcher)

        val status = assertNotNull(session.lastStatusOf(friend.displayName), "no status for the friend")
        assertEquals(SocialGateway.LOBBY_WORLD_ID, status.world, "a friend on the lobby screen must not read as offline")
        assertEquals("Lobby 15", status.worldName)
    }

    @Test
    fun `a friend on a world is reported on that world`() = runBlocking {
        val watcher = LobbyHarness.account("watcher")
        val friend = LobbyHarness.account("friend")
        LobbyHarness.befriend(watcher, friend)
        LobbyHarness.befriend(friend, watcher)

        val world = LobbyHarness.world(42).connect()
        world.playerOnline(friend)
        val session = LobbyHarness.lobbyLogin(watcher)

        val status = assertNotNull(session.lastStatusOf(friend.displayName))
        assertEquals(42, status.world)
        assertEquals("World 42", status.worldName)
    }

    @Test
    fun `logging in tells everyone who watches me, even one-sidedly`() = runBlocking {
        val watcher = LobbyHarness.account("watcher")
        val subject = LobbyHarness.account("subject")
        // Only the watcher has the friendship; the subject has an empty list, which is the normal
        // case and the one the old mutual-friends-only push silently skipped.
        LobbyHarness.befriend(watcher, subject)

        val watcherSession = LobbyHarness.lobbyLogin(watcher)
        watcherSession.clearSent()

        LobbyHarness.lobbyLogin(subject)

        val status = assertNotNull(
            watcherSession.lastStatusOf(subject.displayName),
            "a one-directional friend never heard that their friend came online",
        )
        assertEquals(SocialGateway.LOBBY_WORLD_ID, status.world)
    }

    @Test
    fun `logging out tells everyone who watches me`() = runBlocking {
        val watcher = LobbyHarness.account("watcher")
        val subject = LobbyHarness.account("subject")
        LobbyHarness.befriend(watcher, subject)

        val watcherSession = LobbyHarness.lobbyLogin(watcher)
        LobbyHarness.lobbyLogin(subject)
        watcherSession.clearSent()

        LobbyHarness.lobbyLogout(subject)

        assertEquals(0, assertNotNull(watcherSession.lastStatusOf(subject.displayName)).world)
    }

    @Test
    fun `a friend who appears offline to everybody is reported offline`() = runBlocking {
        val watcher = LobbyHarness.account("watcher")
        // status 2 = "nobody"; status 1 would be "friends only".
        val friend = LobbyHarness.account("hidden", privateStatus = 2)
        LobbyHarness.befriend(watcher, friend)
        LobbyHarness.befriend(friend, watcher)

        LobbyHarness.lobbyLogin(friend)
        val session = LobbyHarness.lobbyLogin(watcher)

        assertEquals(0, assertNotNull(session.lastStatusOf(friend.displayName)).world)
    }

    @Test
    fun `friends only shows online to a friend who is added back`() = runBlocking {
        val mutual = LobbyHarness.account("mutual")
        val stranger = LobbyHarness.account("stranger")
        val friend = LobbyHarness.account("private", privateStatus = 1)
        LobbyHarness.befriend(mutual, friend)
        LobbyHarness.befriend(stranger, friend)
        LobbyHarness.befriend(friend, mutual)

        LobbyHarness.lobbyLogin(friend)
        val mutualSession = LobbyHarness.lobbyLogin(mutual)
        val strangerSession = LobbyHarness.lobbyLogin(stranger)

        assertEquals(
            SocialGateway.LOBBY_WORLD_ID,
            assertNotNull(mutualSession.lastStatusOf(friend.displayName)).world,
        )
        assertEquals(
            0,
            assertNotNull(strangerSession.lastStatusOf(friend.displayName)).world,
        )
    }

    // ─── Friends chat ────────────────────────────────────────────────

    @Test
    fun `the friends chat joined last is re-joined on the next lobby login`() = runBlocking {
        val owner = LobbyHarness.account("owner")
        LobbyHarness.openFriendsChat(owner, "Helpers")
        val member = LobbyHarness.account("member")

        LobbyHarness.lobbyLogin(owner)
        val first = LobbyHarness.lobbyLogin(member)
        LobbyHarness.clientPacket(member, FcJoin(owner.displayName))
        assertTrue(first.sent.filterIsInstance<FriendsChatChannel>().any { !it.clear }, "the join itself failed")

        LobbyHarness.lobbyLogout(member)
        val second = LobbyHarness.lobbyLogin(member)

        val channel = assertNotNull(second.lastChannel(), "logging back into the lobby did not restore the friends chat")
        assertEquals(owner.username, channel.ownerUsername)
        assertTrue(channel.memberList().any { it.username == member.username })
    }

    @Test
    fun `leaving a friends chat is remembered as leaving it`() = runBlocking {
        val owner = LobbyHarness.account("owner")
        LobbyHarness.openFriendsChat(owner, "Helpers")
        val member = LobbyHarness.account("member")

        LobbyHarness.lobbyLogin(owner)
        LobbyHarness.lobbyLogin(member)
        LobbyHarness.clientPacket(member, FcJoin(owner.displayName))
        LobbyHarness.clientPacket(member, FcJoin(null))

        assertNull(member.social.currentFriendsChat, "an explicit leave must forget the channel")

        LobbyHarness.lobbyLogout(member)
        val session = LobbyHarness.lobbyLogin(member)
        assertTrue(
            session.sent.filterIsInstance<FriendsChatChannel>().none { !it.clear },
            "logging back in re-joined a channel the player had left",
        )
    }

    @Test
    fun `a friends chat member on the lobby screen is listed with the lobby world`() = runBlocking {
        val owner = LobbyHarness.account("owner")
        LobbyHarness.openFriendsChat(owner, "Helpers")
        val member = LobbyHarness.account("member")

        val ownerSession = LobbyHarness.lobbyLogin(owner)
        LobbyHarness.clientPacket(owner, FcJoin(owner.displayName))
        LobbyHarness.lobbyLogin(member)
        ownerSession.clearSent()
        LobbyHarness.clientPacket(member, FcJoin(owner.displayName))

        val channel = assertNotNull(ownerSession.lastChannel())
        val listed = assertNotNull(channel.memberList().firstOrNull { it.username == member.username })
        assertEquals(SocialGateway.LOBBY_WORLD_ID, listed.worldId)
        assertEquals("Lobby 15", listed.worldName)
        // The owner's own copy of the block always lets them kick, as legacy patched it to do.
        assertEquals(0, channel.minRankCanKick)
    }

    // ─── Server text in the lobby ────────────────────────────────────

    @Test
    fun `lobby chatbox text is sent as an FC notification, not a game message`() = runBlocking {
        val session = RecordingSession(LobbyHarness.codec)
        session.sendLobbyMessage("hello lobby")

        val message = assertNotNull(session.sent.filterIsInstance<GameMessage>().lastOrNull())
        assertEquals(ChatMessageType.FC_NOTIFICATION, message.type, "GAME text never renders in the lobby")
        assertEquals("hello lobby", message.message)
    }

    // ─── Reconnect and resync ────────────────────────────────────────

    @Test
    fun `a world socket dropping takes its players offline`() = runBlocking {
        val watcher = LobbyHarness.account("watcher")
        val onWorld = LobbyHarness.account("onworld")
        LobbyHarness.befriend(watcher, onWorld)

        val world = LobbyHarness.world(50).connect()
        world.playerOnline(onWorld)
        val watcherSession = LobbyHarness.lobbyLogin(watcher)
        assertEquals(50, assertNotNull(watcherSession.lastStatusOf(onWorld.displayName)).world)
        watcherSession.clearSent()

        world.disconnect()

        assertEquals(
            0,
            assertNotNull(watcherSession.lastStatusOf(onWorld.displayName), "the dropped world's players stayed online").world,
        )
    }

    @Test
    fun `a reconnecting world rebuilds lobby presence from its own online list`() = runBlocking {
        val watcher = LobbyHarness.account("watcher")
        val stillOn = LobbyHarness.account("stillon")
        val goneAway = LobbyHarness.account("goneaway")
        LobbyHarness.befriend(watcher, stillOn)
        LobbyHarness.befriend(watcher, goneAway)

        val world = LobbyHarness.world(51).connect()
        world.playerOnline(stillOn)
        world.playerOnline(goneAway)

        val watcherSession = LobbyHarness.lobbyLogin(watcher)
        assertEquals(51, assertNotNull(watcherSession.lastStatusOf(goneAway.displayName)).world)
        watcherSession.clearSent()

        // The world restarts without the lobby ever seeing the socket close: a new session for the
        // same world announces a smaller population, and the difference has to be dropped.
        val reconnected = LobbyHarness.world(51).connect()
        reconnected.presenceSnapshot(PresenceSyncReason.CONNECT, stillOn)

        assertEquals(51, assertNotNull(watcherSession.lastStatusOf(stillOn.displayName)).world)
        assertEquals(
            0,
            assertNotNull(watcherSession.lastStatusOf(goneAway.displayName), "a player the world no longer has stayed online").world,
        )
    }

    @Test
    fun `a reconnecting world gets its players their whole social state back`() = runBlocking {
        val owner = LobbyHarness.account("owner")
        LobbyHarness.openFriendsChat(owner, "Helpers")
        val member = LobbyHarness.account("member", currentFriendsChat = owner.displayName)
        LobbyHarness.lobbyLogin(owner)

        val world = LobbyHarness.world(52).connect()
        world.presenceSnapshot(PresenceSyncReason.CONNECT, member)

        val packets = world.packetsFor(member)
        assertTrue(packets.any { it is FriendStatus }, "the friend list was not re-sent")
        assertTrue(
            packets.filterIsInstance<FriendsChatChannel>().any { !it.clear },
            "the remembered friends chat was not restored after the reconnect",
        )
    }

    @Test
    fun `a periodic snapshot drops a player the lobby still thought was online`() = runBlocking {
        val watcher = LobbyHarness.account("watcher")
        val ghost = LobbyHarness.account("ghost")
        LobbyHarness.befriend(watcher, ghost)

        val world = LobbyHarness.world(53).connect()
        world.playerOnline(ghost)
        val watcherSession = LobbyHarness.lobbyLogin(watcher)
        assertEquals(53, assertNotNull(watcherSession.lastStatusOf(ghost.displayName)).world)
        watcherSession.clearSent()

        world.presenceSnapshot(PresenceSyncReason.PERIODIC)

        assertEquals(0, assertNotNull(watcherSession.lastStatusOf(ghost.displayName)).world)
    }

    @Test
    fun `a player who moved to the lobby is not logged out by their old world's goodbye`() = runBlocking {
        val watcher = LobbyHarness.account("watcher")
        val mover = LobbyHarness.account("mover")
        LobbyHarness.befriend(watcher, mover)

        val world = LobbyHarness.world(54).connect()
        world.playerOnline(mover)
        val watcherSession = LobbyHarness.lobbyLogin(watcher)

        // The lobby session lands first, then the world's late PlayerOffline arrives.
        LobbyHarness.lobbyLogin(mover)
        watcherSession.clearSent()
        world.playerOffline(mover)

        assertEquals(
            SocialGateway.LOBBY_WORLD_ID,
            assertNotNull(watcherSession.lastStatusOf(mover.displayName)).world,
            "a player who moved world -> lobby was reported offline",
        )
    }
}
