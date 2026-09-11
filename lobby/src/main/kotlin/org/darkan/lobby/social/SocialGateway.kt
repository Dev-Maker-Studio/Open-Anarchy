package org.darkan.lobby.social

import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.darkan.core.Logger
import org.darkan.core.Logger.logInfo
import org.darkan.core.Logger.logTrace
import org.darkan.core.Logger.logWarn
import org.darkan.core.formatPlayerNameForDisplay
import org.darkan.core.formatPlayerNameForProtocol
import org.darkan.core.model.*
import org.darkan.core.mongo.collections.Accounts
import org.darkan.core.net.Session
import org.darkan.core.net.prot.*
import org.darkan.core.net.prot.packet.*
import org.darkan.core.net.prot.dto.*
import org.darkan.core.social.QuickChatMessage
import org.darkan.core.social.gateway.*
import org.darkan.core.worldlist.World
import org.darkan.lobby.Lobby
import java.util.concurrent.ConcurrentHashMap
import org.darkan.core.net.prot.dto.FriendStatusUpdate
import org.darkan.core.net.prot.dto.FriendsChatPlayer
import org.darkan.core.net.prot.dto.IgnoreEntry

object SocialGateway {
    private data class Presence(
        val username: String,
        val displayName: String,
        val rightsCrown: Int,
        val privateStatus: Int,
        val worldId: Int,
        val worldName: String,
        val lastSeenMs: Long,
    )

    private data class LobbyUser(
        val username: String,
        val displayName: String,
        val rightsCrown: Int,
        val privateStatus: Int,
        val session: Session,
        val lastSeenMs: Long,
    )

    private val worldsById = ConcurrentHashMap<Int, WorldSession>()
    private val worldInfoById = ConcurrentHashMap<Int, GatewayWorldInfo>()

    private val presenceByUsername = ConcurrentHashMap<String, Presence>()
    private val lobbyByUsername = ConcurrentHashMap<String, LobbyUser>()
    private val usernameByDisplayLower = ConcurrentHashMap<String, String>()

    private val chatTypeByUsername = ConcurrentHashMap<String, Int>()

    private val fcRoomsByOwner = ConcurrentHashMap<String, FcRoom>()
    private val fcOwnerByMember = ConcurrentHashMap<String, String>()

    private data class FcRoom(
        val ownerUsername: String,
        val members: MutableSet<String>,
        val tempBans: ConcurrentHashMap<String, Long> = ConcurrentHashMap(),
    )

    private fun rankAllowed(rankId: Int, requiredId: Int): Boolean {
        if (requiredId <= FriendsChatRank.UNRANKED.id) return true
        return rankId >= requiredId
    }

    private suspend fun getFcOwnerUsername(fromUsername: String, ownerDisplayNameOrNull: String?): String? {
        val name = ownerDisplayNameOrNull?.takeIf { it.isNotBlank() }
            ?: Accounts.find(fromUsername)?.social?.currentFriendsChat?.takeIf { it.isNotBlank() }
            ?: return null
        val owner = Accounts.findByDisplayName(name) ?: return null
        return owner.username
    }

    /**
     * A member's rank in [owner]'s channel. Staff outrank everything and the owner is always the
     * owner, exactly as legacy `ActiveFC.getRank` had it.
     */
    private fun fcRankOf(owner: Account, member: Account): Int {
        if (member.hasRights(Rights.ADMIN)) return FriendsChatRank.JMOD.id
        if (member.username == owner.username) return FriendsChatRank.OWNER.id
        return owner.social.friendsChat.getRank(member.username).id
    }

    private suspend fun getOrCreateRoom(ownerUsername: String): FcRoom {
        return fcRoomsByOwner.computeIfAbsent(ownerUsername) {
            FcRoom(ownerUsername = ownerUsername, members = ConcurrentHashMap.newKeySet())
        }
    }

    /**
     * The channel block for [room]. [minRankCanKick] is per-recipient in legacy
     * `ActiveFC.refreshChannel`, which patched a 0 into the byte for the owner so the owner can
     * always kick; everybody else sees the channel's configured kick rank.
     */
    private suspend fun buildFriendsChatChannel(owner: Account, room: FcRoom, forUsername: String): FriendsChatChannel {
        val minRankKick = if (forUsername == owner.username) 0 else owner.social.friendsChat.rankToKick.id
        // 727 encodes chatName as a base37 long; use a protocol-safe name (not displayName casing/spaces).
        val chatName = (owner.social.friendsChat.name ?: owner.username).formatPlayerNameForProtocol()

        val memberAccounts = Accounts.findByUsernames(room.members)
        val accByUser = memberAccounts.associateBy { it.username }

        val members = room.members.mapNotNull { memberUsername ->
            val acc = accByUser[memberUsername] ?: return@mapNotNull null
            val location = locationOf(memberUsername)
            FriendsChatPlayer(
                displayName = acc.displayName,
                username = acc.username,
                worldId = location?.worldId ?: 0,
                rank = fcRankOf(owner, acc),
                worldName = location?.worldName ?: "",
            )
        }.toTypedArray()

        return FriendsChatChannel(
            clear = false,
            ownerDisplayName = owner.displayName,
            ownerUsername = owner.username,
            chatName = chatName,
            minRankCanKick = minRankKick,
            players = members,
        )
    }

    private suspend fun broadcastFcChannel(owner: Account, room: FcRoom) {
        // The block differs for the owner, so the owner is sent their own copy and the rest share.
        val others = room.members.filter { it != owner.username }
        if (others.isNotEmpty()) sendBatchToUsers(others, buildFriendsChatChannel(owner, room, others.first()))
        if (room.members.contains(owner.username)) {
            sendToUser(owner.username, buildFriendsChatChannel(owner, room, owner.username))
        }
    }

    /**
     * Takes [username] out of whatever friends chat they are in. [notify] means "they left on
     * purpose": legacy `ActiveFC.removePlayer(player, logout = false)` both told the client the
     * channel is gone and cleared the remembered channel, while a logout deliberately kept it so
     * the next login re-joins.
     */
    private suspend fun leaveFriendsChat(username: String, notify: Boolean) {
        if (notify) forgetCurrentFriendsChat(username)
        val ownerUsername = fcOwnerByMember.remove(username) ?: return
        val room = fcRoomsByOwner[ownerUsername] ?: return
        room.members.remove(username)
        if (notify) {
            sendToUser(username, FriendsChatChannel(clear = true))
        }
        val owner = Accounts.find(ownerUsername) ?: return
        broadcastFcChannel(owner, room)
        if (room.members.isEmpty()) {
            fcRoomsByOwner.remove(ownerUsername, room)
        }
    }

    // ─── Friend Status ───────────────────────────────────────────────

    /**
     * The world id the lobby itself reports as, so a friend sitting on the lobby screen reads as
     * online rather than offline. The legacy lobby registered itself as a world exactly this way
     * (`Lobby.WORLD_INFO`, lobby-server `com/rs/lobby/Lobby.java:51`).
     */
    const val LOBBY_WORLD_ID = 1115

    /**
     * The world label the client shows next to a friend or a friends chat member. Legacy derived
     * it from the world number rather than the world's own name (`FriendStatus.encodePlayer` and
     * `ActiveFC.refreshChannel`), which is what makes lobby-resident players read as "Lobby n".
     */
    private fun worldTextFor(worldId: Int): String =
        if (worldId > 1100) "Lobby ${worldId - 1100}" else "World $worldId"

    /** Where a player currently is: the lobby screen, or a game world. */
    private data class Location(val worldId: Int, val worldName: String)

    /**
     * Resolves [username] to the world the client should be shown, or null when they are offline.
     * The lobby is checked first and wins, matching legacy `Lobby.getSocial`: a player moving to a
     * world drops their lobby entry, so the two can never both be live for long.
     */
    private fun locationOf(username: String): Location? {
        lobbyByUsername[username]?.let { return Location(LOBBY_WORLD_ID, worldTextFor(LOBBY_WORLD_ID)) }
        presenceByUsername[username]?.let { return Location(it.worldId, worldTextFor(it.worldId)) }
        return null
    }

    /** True while [username] is online anywhere the gateway knows about. */
    private fun isOnline(username: String): Boolean =
        lobbyByUsername.containsKey(username) || presenceByUsername.containsKey(username)

    /**
     * Who has to be told when a player's status changes: for each username, everyone currently
     * online who has them on their friends list. This mirrors legacy `FriendsToNotify`
     * (lobby-server `com/rs/lobby/model/FriendsToNotify.java`) and exists because friendship is
     * one-directional - B can watch A without A watching B, so walking A's own friends would miss
     * exactly the people who need the update.
     */
    private val friendWatchers = ConcurrentHashMap<String, MutableSet<String>>()

    private fun registerFriendWatcher(username: String, friends: Collection<String>) {
        for (friend in friends) {
            friendWatchers.computeIfAbsent(friend) { ConcurrentHashMap.newKeySet() }.add(username)
        }
    }

    private fun deregisterFriendWatcher(username: String, friends: Collection<String>) {
        for (friend in friends) {
            val watchers = friendWatchers[friend] ?: continue
            watchers.remove(username)
            if (watchers.isEmpty()) friendWatchers.remove(friend, watchers)
        }
    }

    private fun friendStatusUpdateFor(viewer: Account, friend: Account): FriendStatusUpdate {
        // `viewer.onlineTo(friend)` reads the *friend's* private-chat setting: "nobody" always
        // hides them, "friends only" hides them unless they have the viewer added back. Legacy
        // applied the same check at encode time (core FriendStatus.encodePlayer), which is why an
        // update is still pushed - it just carries world 0.
        val canSeeFriend = viewer.onlineTo(friend)
        val location = if (canSeeFriend) locationOf(friend.username) else null
        val rank = viewer.social.friendsChat.getRank(friend.username).id
        return FriendStatusUpdate(
            warnMessage = false,
            displayName = friend.displayName,
            previousDisplayName = friend.prevDisplayName,
            world = location?.worldId ?: 0,
            friendsChatRank = rank,
            referrer = false,
            worldName = location?.worldName,
        )
    }

    private fun ignoreDisplayName(username: String, accountsByUsername: Map<String, Account>): String {
        return accountsByUsername[username]?.displayName ?: username.formatPlayerNameForDisplay()
    }

    private suspend fun sendIgnoreListFull(toUsername: String) {
        val account = Accounts.find(toUsername) ?: return
        val ignoreAccounts = Accounts.findByUsernames(account.social.ignores)
        val ignoreMap = ignoreAccounts.associateBy { it.username }
        val ignores = account.social.ignores.map { ign ->
            IgnoreEntry(username = ign, displayName = ignoreDisplayName(ign, ignoreMap))
        }.toTypedArray()
        sendToUser(account.username, UpdateIgnoreList(ignores))
    }

    /**
     * Pushes [username]'s current status to everyone online who has them on their friends list,
     * the way legacy `SocialPlayer.updateOnlineStatus` did. Call it *after* the presence maps have
     * been updated - [friendStatusUpdateFor] reads them, so a logout notifies correctly only once
     * the player is already gone from them.
     */
    private suspend fun notifyFriendsOfStatusChange(username: String, subject: Account? = null) {
        val watchers = friendWatchers[username]?.toList().orEmpty()
        if (watchers.isEmpty()) return
        val account = subject ?: Accounts.find(username) ?: return
        for (watcher in Accounts.findByUsernames(watchers)) {
            // The index can lag a friend removal made from another session; legacy re-checked too.
            if (!watcher.social.friends.contains(account.username)) continue
            sendToUser(watcher.username, FriendStatus(arrayOf(friendStatusUpdateFor(watcher, account))))
        }
    }

    /**
     * Sends the full friend list, ignore list and chat filter, then tells everyone watching this
     * player that they are online. One batch DB query covers the friends and ignores instead of
     * 6+N individual lookups.
     */
    private suspend fun initializeSocial(account: Account) {
        val username = account.username

        // ONE batch DB query for all friends + ignores
        val allRelated = account.social.friends + account.social.ignores
        val relatedAccounts = if (allRelated.isNotEmpty()) Accounts.findByUsernames(allRelated) else emptyList()
        val accountsByUsername = relatedAccounts.associateBy { it.username }

        // Build + send friend list
        // FriendlistLoaded must come FIRST (sets client LOADED=1, "ready for data"),
        // then FriendStatus populates the list and sets LOADED=2 ("fully loaded").
        val friendUpdates = account.social.friends.mapNotNull { friendUsername ->
            val friendAccount = accountsByUsername[friendUsername] ?: return@mapNotNull null
            friendStatusUpdateFor(account, friendAccount)
        }.toTypedArray()
        sendToUser(username, FriendlistLoaded())
        sendToUser(username, FriendStatus(friendUpdates))

        // Build + send ignore list
        val ignores = account.social.ignores.map { ign ->
            IgnoreEntry(
                username = ign,
                displayName = ignoreDisplayName(ign, accountsByUsername),
            )
        }.toTypedArray()
        sendToUser(username, UpdateIgnoreList(ignores))

        // Send chat filter echo
        sendToUser(username, ChatFilterSettingsPrivateChat(account.social.status.toInt()))

        notifyFriendsOfStatusChange(username, account)
    }

    /**
     * Everything a player is given when they appear - on the lobby screen or on a world - in
     * legacy `SocialPlayer.loginSocial` order (lobby-server `com/rs/lobby/model/SocialPlayer.java`):
     * register them in the watcher index, send the friend list, ignore list and chat filter, tell
     * their watchers they are online, then put them back in the friends chat and clan chat they
     * were last in. The lobby used to skip the last two, which is why a lobby login never restored
     * the channel the player had joined.
     */
    private suspend fun loginSocial(account: Account) {
        registerFriendWatcher(account.username, account.social.friends)
        initializeSocial(account)
        ClanService.onLogin(account)
        val savedFc = account.social.currentFriendsChat
        if (!savedFc.isNullOrBlank()) handleClientPacket(account.username, FcJoin(savedFc))
    }

    /**
     * Everything a player loses when they stop being present anywhere. Mirrors legacy
     * `SocialPlayer.logoutSocial`: tell the watchers first (the presence maps must already be
     * clear), then drop out of the watcher index and the channels. The friends chat is left
     * without clearing `currentFriendsChat`, which is what makes it re-join on the next login.
     */
    private suspend fun logoutSocial(username: String) {
        val account = Accounts.find(username)
        notifyFriendsOfStatusChange(username, account)
        if (account != null) deregisterFriendWatcher(username, account.social.friends)
        leaveFriendsChat(username, notify = false)
        ClanService.onLogout(username)
    }

    suspend fun registerLobbyPlayerSession(account: Account, session: Session) {
        lobbyByUsername[account.username] = LobbyUser(
            username = account.username,
            displayName = account.displayName,
            rightsCrown = account.rights.crown,
            privateStatus = account.social.status.toInt(),
            session = session,
            lastSeenMs = System.currentTimeMillis(),
        )
        usernameByDisplayLower[account.displayName.lowercase()] = account.username
        loginSocial(account)
    }

    suspend fun unregisterLobbyPlayerSession(username: String) {
        LobbyClanScreen.forget(username)
        val prior = lobbyByUsername.remove(username) ?: return
        usernameByDisplayLower.remove(prior.displayName.lowercase(), username)
        // A player who clicked "play" is on a world by now; they have not logged out at all, so
        // nothing about them may be torn down.
        if (presenceByUsername.containsKey(username)) return
        logoutSocial(username)
    }

    // ─── Presence Reconciliation ─────────────────────────────────────

    /**
     * Records that [player] is in game on [session]'s world and answers whether that is news.
     * The lobby entry is dropped on the way: a player who reached a world is no longer on the
     * lobby screen, and leaving the entry behind would route their packets at a dead session.
     */
    private fun markOnline(session: WorldSession, player: GatewayPlayerPresence): Boolean {
        val username = player.username.formatPlayerNameForProtocol()
        lobbyByUsername.remove(username)
        val prior = presenceByUsername.put(
            username,
            Presence(
                username = username,
                displayName = player.displayName,
                rightsCrown = player.rightsCrown,
                privateStatus = player.privateStatus,
                worldId = session.worldId,
                worldName = session.worldName,
                lastSeenMs = System.currentTimeMillis(),
            ),
        )
        usernameByDisplayLower[player.displayName.lowercase()] = username
        if (prior != null && prior.worldId == session.worldId) return false
        Lobby.worldList.get(session.worldId)?.let { it.playersOnline++ }
        prior?.let { old -> Lobby.worldList.get(old.worldId)?.let { if (it.playersOnline > 0) it.playersOnline-- } }
        return true
    }

    /**
     * Drops [username]'s presence, but only while they are still recorded on [worldId] - a player
     * who has already turned up somewhere else must not be removed by their old world's late
     * goodbye. Answers whether anything was actually removed.
     */
    private fun markOffline(username: String, worldId: Int): Boolean {
        val prior = presenceByUsername[username] ?: return false
        if (prior.worldId != worldId) return false
        if (!presenceByUsername.remove(username, prior)) return false
        usernameByDisplayLower.remove(prior.displayName.lowercase(), username)
        Lobby.worldList.get(prior.worldId)?.let { if (it.playersOnline > 0) it.playersOnline-- }
        return true
    }

    /**
     * Runs the logout half of the social state - unless the player turns out to still be online.
     * Hopping from a world back to the lobby can land the new lobby session before the old world's
     * [PlayerOffline], and that player has not logged out at all; they have just moved, so their
     * watchers get a fresh status instead of a teardown.
     */
    private suspend fun logoutSocialIfGone(username: String) {
        if (isOnline(username)) {
            notifyFriendsOfStatusChange(username)
            return
        }
        logoutSocial(username)
    }

    /**
     * Makes the lobby's picture of [session]'s world match the world's own. The world is the
     * authority for who is on it: anybody it lists is online there, anybody it does not list is
     * gone. That is what lets either process restart without stranding "online" friends, and what
     * a periodic snapshot uses to erase drift.
     *
     * On [PresenceSyncReason.CONNECT] every listed player is also re-sent their whole social state,
     * because a world that outlived a lobby restart is full of players whose client still shows an
     * empty friend list and no channel; nobody should have to relog for that.
     */
    private suspend fun reconcileWorldPresence(session: WorldSession, msg: WorldPresenceSnapshot) {
        val listed = LinkedHashMap<String, GatewayPlayerPresence>()
        for (player in msg.players) listed[player.username.formatPlayerNameForProtocol()] = player

        val stale = presenceByUsername.values
            .filter { it.worldId == session.worldId && it.username !in listed }
            .map { it.username }
        for (username in stale) {
            if (markOffline(username, session.worldId)) logoutSocialIfGone(username)
        }

        val full = msg.reason == PresenceSyncReason.CONNECT
        for ((username, player) in listed) {
            val existing = presenceByUsername[username]
            // Another world already claims this player; its own snapshot decides, not this one.
            if (existing != null && existing.worldId != session.worldId) continue
            val appeared = markOnline(session, player)
            if (!appeared && !full) continue
            val account = Accounts.find(username) ?: continue
            loginSocial(account)
        }

        logInfo("SocialGateway: reconciled world=${session.worldId} reason=${msg.reason} online=${listed.size} dropped=${stale.size}")
    }

    /** Forgets the channel [username] is remembered as being in, so a later login re-joins nothing. */
    private suspend fun forgetCurrentFriendsChat(username: String) {
        val account = Accounts.find(username) ?: return
        if (account.social.currentFriendsChat == null) return
        account.social.currentFriendsChat = null
        Accounts.save(account)
    }

    /**
     * Lobby-resident players never reach a world, so an announcement fanned out to the worlds
     * alone would skip them. Legacy `ChatManager.sendBroadcast` messaged both, and used
     * FC_NOTIFICATION for lobby players because that is the only text the lobby's chat window
     * renders.
     */
    private suspend fun deliverAnnouncementToLobby(msg: AnnouncementBroadcast) {
        val recipients: Collection<String> = when (val target = msg.target) {
            is AnnouncementTarget.AllWorlds -> lobbyByUsername.keys.toList()
            is AnnouncementTarget.World ->
                if (target.worldId == LOBBY_WORLD_ID) lobbyByUsername.keys.toList() else emptyList()
            is AnnouncementTarget.User ->
                listOf(target.username.formatPlayerNameForProtocol()).filter { lobbyByUsername.containsKey(it) }
            is AnnouncementTarget.FriendsChat ->
                fcRoomsByOwner[target.ownerUsername.formatPlayerNameForProtocol()]
                    ?.members
                    ?.filter { lobbyByUsername.containsKey(it) }
                    .orEmpty()
        }
        for (username in recipients) {
            sendToUser(username, GameMessage(ChatMessageType.FC_NOTIFICATION, msg.message))
        }
    }

    suspend fun broadcastAnnouncement(msg: AnnouncementBroadcast) {
        deliverAnnouncementToLobby(msg)
        val text = SocialGatewayWireJson.json.encodeToString(SocialGatewayWireMessage.serializer(), msg)
        for (session in worldsById.values) {
            try {
                session.sendText(text)
            } catch (e: Exception) {
                logWarn("Failed sending announcement to world=${session.worldId}", e)
            }
        }
    }

    suspend fun handleWorldSocket(socket: DefaultWebSocketServerSession) = coroutineScope {
        var worldSession: WorldSession? = null

        try {
            for (frame in socket.incoming) {
                val text = (frame as? Frame.Text)?.readText() ?: continue
                val msg = try {
                    SocialGatewayWireJson.json.decodeFromString(SocialGatewayWireMessage.serializer(), text)
                } catch (e: Exception) {
                    logWarn("SocialGateway: invalid message JSON", e)
                    continue
                }

                if (msg is WorldHello) {
                    val tier = communityWorlds.tierFor(msg.token)
                        ?: return@coroutineScope refuseWorld(socket, msg.world, "Unauthorized")
                    val metadata = msg.world.toWorldMetadata()
                    if (!metadata.isValid)
                        return@coroutineScope refuseWorld(socket, msg.world, "Invalid world registration")
                    val community = tier == CommunityWorlds.Tier.COMMUNITY
                    if (community)
                        communityWorlds.refusalFor(metadata)?.let { return@coroutineScope refuseWorld(socket, msg.world, it) }

                    val worldId = msg.world.worldId
                    val session = WorldSession(worldId, msg.world.worldName, socket, community)
                    if (!attachWorld(session, msg.world))
                        return@coroutineScope refuseWorld(socket, msg.world, "World $worldId is already online")
                    worldSession = session

                    logInfo("SocialGateway: ${tier.name.lowercase()} world connected worldId=${worldId} name=${msg.world.worldName} host=${msg.world.publicHost}:${msg.world.port}")
                    val resp = WorldHelloAck(ok = true, message = null)
                    socket.send(Frame.Text(SocialGatewayWireJson.json.encodeToString(SocialGatewayWireMessage.serializer(), resp)))
                    continue
                }

                val world = worldSession ?: continue
                handleWorldMessage(this, world, msg)
            }
        } catch (_: CancellationException) {
            // ignore
        } catch (e: Exception) {
            Logger.logError("SocialGateway socket error", e)
        } finally {
            worldSession?.let { detachWorld(it) }
        }
    }

    internal var communityWorlds = CommunityWorlds()

    private suspend fun refuseWorld(socket: WebSocketSession, world: GatewayWorldInfo, reason: String) {
        logWarn("SocialGateway: refused world ${world.worldId} (${world.worldName}) at ${world.publicHost}:${world.port}: $reason")
        val resp = WorldHelloAck(ok = false, message = reason)
        socket.send(Frame.Text(SocialGatewayWireJson.json.encodeToString(SocialGatewayWireMessage.serializer(), resp)))
        socket.close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, reason))
    }

    /** Records a world that has just finished its [WorldHello] handshake. */
    internal fun attachWorld(session: WorldSession, info: GatewayWorldInfo): Boolean {
        if (session.community) {
            if (worldsById.putIfAbsent(session.worldId, session) != null) return false
        } else {
            worldsById.put(session.worldId, session)?.markClosed()
        }
        worldInfoById[session.worldId] = info
        val world = World(info.toWorldMetadata())
        world.customCache = servesDifferentCache(info)
        Lobby.worldList.put(world)
        return true
    }

    private fun servesDifferentCache(info: GatewayWorldInfo): Boolean {
        val lobby = Lobby.cacheCrcs
        if (info.cacheCrcs.isEmpty() || lobby.isEmpty()) return false
        val differing = (0 until maxOf(lobby.size, info.cacheCrcs.size))
            .filter { lobby.getOrNull(it) != info.cacheCrcs.getOrNull(it) }
        if (differing.isNotEmpty())
            logInfo("SocialGateway: world ${info.worldId} serves its own cache (indices ${differing.joinToString()} differ)")
        return differing.isNotEmpty()
    }

    /**
     * Drops a world and everybody who was on it: the world is listed as offline until
     * [WorldList.expireOffline] drops it, and each of its players stops being present, which their
     * friends have to be told about the same way an individual [PlayerOffline] tells them.
     */
    internal suspend fun detachWorld(session: WorldSession) {
        session.markClosed()
        Lobby.worldList.markOffline(session.worldId)
        worldsById.remove(session.worldId, session)
        worldInfoById.remove(session.worldId)

        val toRemove = presenceByUsername
            .filterValues { it.worldId == session.worldId }
            .keys
            .toList()
        for (username in toRemove) {
            if (markOffline(username, session.worldId)) logoutSocialIfGone(username)
        }

        ClanService.onWorldDetached(session.worldId)
        communityWorlds.forgetWorld(session.worldId)
        logInfo("SocialGateway: world disconnected worldId=${session.worldId}")
    }

    /**
     * Everything a registered world can tell the gateway. Split out of [handleWorldSocket] so a
     * test can feed a [WorldSession] messages with no socket behind it; [scope] is the socket's
     * scope, which the slower database-backed branches launch into so one message never holds up
     * the read loop.
     */
    internal suspend fun handleWorldMessage(scope: CoroutineScope, session: WorldSession, msg: SocialGatewayWireMessage) {
        if (session.community && !communityMayDeliver(session, msg)) {
            logTrace("SocialGateway: community world ${session.worldId} may not send ${msg::class.simpleName}")
            return
        }
        when (msg) {
            is PlayerOnline -> {
                val claimed = GatewayPlayerPresence(msg.username, msg.displayName, msg.rightsCrown, msg.privateStatus)
                val player = if (session.community) communityWorlds.admitted(session.worldId, claimed) ?: return else claimed
                markOnline(session, player)
                scope.launch {
                    try {
                        val acc = Accounts.find(msg.username.formatPlayerNameForProtocol()) ?: return@launch
                        loginSocial(acc)
                    } catch (e: Exception) {
                        logWarn("SocialGateway: failed initial snapshots user=${msg.username}", e)
                    }
                }
            }

            is PlayerOffline -> {
                val username = msg.username.formatPlayerNameForProtocol()
                markOffline(username, session.worldId)
                communityWorlds.forget(username, session.worldId)
                scope.launch { logoutSocialIfGone(username) }
            }

            is WorldPresenceSnapshot -> {
                val snapshot = if (!session.community) msg
                    else msg.copy(players = msg.players.mapNotNull { communityWorlds.admitted(session.worldId, it) })
                scope.launch { reconcileWorldPresence(session, snapshot) }
            }

            is VerifyLoginToken -> {
                scope.launch { sendToWorld(session.worldId, communityWorlds.verify(session.worldId, msg)) }
            }

            is ClientPacketEnvelope -> {
                handleClientPacket(msg.username.formatPlayerNameForProtocol(), msg.packet)
            }

            is AnnouncementBroadcast -> {
                // Allow worlds to rebroadcast if needed.
                broadcastAnnouncement(msg)
            }

            is FcSettingsUpdate -> {
                scope.launch { handleFcSettingsUpdate(msg) }
            }

            is ClanCommand -> {
                scope.launch {
                    val result = ClanService.handle(msg.username, msg.action, msg.requestId)
                    sendToWorld(session.worldId, result)
                }
            }

            is ClanFetch -> {
                scope.launch { sendToWorld(session.worldId, ClanService.fetch(msg.clanName)) }
            }

            else -> {
                logTrace("SocialGateway ignoring message type=${msg::class.simpleName}")
            }
        }
    }

    private fun communityMayDeliver(session: WorldSession, msg: SocialGatewayWireMessage): Boolean = when (msg) {
        is ClientPacketEnvelope -> isOnWorld(msg.username, session.worldId)
        is FcSettingsUpdate -> isOnWorld(msg.username, session.worldId)
        is ClanCommand -> isOnWorld(msg.username, session.worldId)
        is AnnouncementBroadcast -> false
        else -> true
    }

    private fun isOnWorld(username: String, worldId: Int) =
        presenceByUsername[username.formatPlayerNameForProtocol()]?.worldId == worldId

    private suspend fun sendToUser(username: String, packet: ServerProt) {
        val lobby = lobbyByUsername[username]
        if (lobby != null) {
            try {
                lobby.session.send(packet)
            } catch (e: Exception) {
                logWarn("Failed sending packet to lobby user=$username type=${packet::class.simpleName}", e)
            }
            return
        }
        val presence = presenceByUsername[username] ?: return
        sendToWorld(presence.worldId, ServerPacketEnvelope(username = username, packet = packet))
    }

    private suspend fun sendBatchToUsers(usernames: Collection<String>, packet: ServerProt) {
        val worldBuckets = mutableMapOf<Int, MutableList<String>>()
        for (username in usernames) {
            val lobby = lobbyByUsername[username]
            if (lobby != null) {
                try {
                    lobby.session.send(packet)
                } catch (e: Exception) {
                    logWarn("Failed sending packet to lobby user=$username type=${packet::class.simpleName}", e)
                }
                continue
            }
            val presence = presenceByUsername[username] ?: continue
            worldBuckets.getOrPut(presence.worldId) { mutableListOf() }.add(username)
        }
        for ((worldId, users) in worldBuckets) {
            if (users.size == 1) {
                sendToWorld(worldId, ServerPacketEnvelope(username = users[0], packet = packet))
            } else {
                sendToWorld(worldId, ServerPacketBatch(usernames = users, packet = packet))
            }
        }
    }

    internal suspend fun sendToWorld(worldId: Int, msg: SocialGatewayWireMessage) {
        val session = worldsById[worldId] ?: return
        val text = SocialGatewayWireJson.json.encodeToString(SocialGatewayWireMessage.serializer(), msg)
        try {
            session.sendText(text)
        } catch (e: Exception) {
            logWarn("Failed sending message to world=$worldId type=${msg::class.simpleName}", e)
        }
    }

    // ─── Seams for ClanService ───────────────────────────────────────

    /** Sends [msg] to every connected world. */
    internal suspend fun broadcastToWorlds(msg: SocialGatewayWireMessage) {
        val text = SocialGatewayWireJson.json.encodeToString(SocialGatewayWireMessage.serializer(), msg)
        for (session in worldsById.values) {
            try {
                session.sendText(text)
            } catch (e: Exception) {
                logWarn("Failed sending message to world=${session.worldId} type=${msg::class.simpleName}", e)
            }
        }
    }

    /** Where [username] is: [LOBBY_WORLD_ID] on the lobby screen, a world id, or null when offline. */
    internal fun worldIdOf(username: String): Int? = locationOf(username)?.worldId

    /** The display name [username] is online under, or null when they are not online anywhere. */
    internal fun displayNameOf(username: String): String? =
        lobbyByUsername[username]?.displayName ?: presenceByUsername[username]?.displayName

    /** Sends [packet] to [username]'s lobby-screen session; false when they are not on the lobby. */
    internal suspend fun sendToLobbyPlayer(username: String, packet: ServerProt): Boolean {
        val lobby = lobbyByUsername[username] ?: return false
        try {
            lobby.session.send(packet)
        } catch (e: Exception) {
            logWarn("Failed sending packet to lobby user=$username type=${packet::class.simpleName}", e)
        }
        return true
    }

    /** The channel a `Chat` goes to: the packet's own type, else the one the client last announced. */
    internal fun chatTypeOf(username: String, packetType: Int): Int =
        if (packetType != -1) packetType else (chatTypeByUsername[username] ?: 0)

    /** As [chatTypeOf] for `QuickChatPublic`, whose "unset" is 0 rather than -1. */
    internal fun quickChatTypeOf(username: String, packetType: Int): Int =
        if (packetType != 0) packetType else (chatTypeByUsername[username] ?: 0)

    suspend fun handleClientPacket(username: String, packet: ClientProt) {
        when (packet) {
            is ChatType -> {
                chatTypeByUsername[username] = packet.type
            }

            is ChatSetFilter -> {
                val account = Accounts.find(username) ?: return
                account.social.status = packet.privateFilter.toByte()
                Accounts.save(account)
                presenceByUsername.computeIfPresent(username) { _, p ->
                    p.copy(privateStatus = packet.privateFilter, lastSeenMs = System.currentTimeMillis())
                }
                sendToUser(username, ChatFilterSettingsPrivateChat(packet.privateFilter))
                notifyFriendsOfStatusChange(username, account)
            }

            is AddFriend -> {
                val from = Accounts.find(username) ?: return
                val target = Accounts.findByDisplayName(packet.name)
                if (target == null) {
                    sendToUser(username, GameMessage(ChatMessageType.FRIEND_NOTIFICATION, "Player not found."))
                    return
                }
                if (from.social.friends.add(target.username)) Accounts.save(from)
                // Legacy SocialAddRemoveHandler: register the new watch, push my own status to
                // everyone watching me, then send myself the new friend's status.
                registerFriendWatcher(from.username, listOf(target.username))
                notifyFriendsOfStatusChange(from.username, from)
                val targetAcc = Accounts.find(target.username) ?: target
                sendToUser(from.username, FriendStatus(arrayOf(friendStatusUpdateFor(from, targetAcc))))
            }

            is RemoveFriend -> {
                val from = Accounts.find(username) ?: return
                val target = Accounts.findByDisplayName(packet.name) ?: return
                if (from.social.friends.remove(target.username)) Accounts.save(from)
                deregisterFriendWatcher(from.username, listOf(target.username))
                notifyFriendsOfStatusChange(from.username, from)
            }

            is AddIgnoreReq -> {
                val from = Accounts.find(username) ?: return
                val target = Accounts.findByDisplayName(packet.username) ?: Accounts.find(packet.username)
                if (target == null) {
                    sendToUser(username, GameMessage(ChatMessageType.IGNORE_NOTIFICATION, "Player not found."))
                    return
                }
                from.social.ignores.add(target.username)
                if (packet.temporary) {
                    val set = from.social.tillLogoutIgnores ?: mutableSetOf<String>().also { from.social.tillLogoutIgnores = it }
                    set.add(target.username)
                }
                Accounts.save(from)
                sendIgnoreListFull(from.username)
            }

            is RemoveIgnore -> {
                val from = Accounts.find(username) ?: return
                val target = Accounts.findByDisplayName(packet.name) ?: Accounts.find(packet.name) ?: return
                from.social.ignores.remove(target.username)
                from.social.tillLogoutIgnores?.remove(target.username)
                Accounts.save(from)
                sendIgnoreListFull(from.username)
            }

            is FcJoin -> {
                if (packet.fcName == null) {
                    leaveFriendsChat(username, notify = true)
                    return
                }

                val ownerUsername = getFcOwnerUsername(username, packet.fcName)
                if (ownerUsername == null) {
                    sendToUser(username, GameMessage(ChatMessageType.FC_NOTIFICATION, "Unable to find that friends chat."))
                    sendToUser(username, FriendsChatChannel(clear = true))
                    return
                }

                val owner = Accounts.find(ownerUsername)
                if (owner == null) {
                    sendToUser(username, GameMessage(ChatMessageType.FC_NOTIFICATION, "Unable to find that friends chat."))
                    sendToUser(username, FriendsChatChannel(clear = true))
                    return
                }

                // FC not set up check
                if (owner.social.friendsChat.name == null) {
                    sendToUser(username, GameMessage(ChatMessageType.FC_NOTIFICATION, "The friends chat you are trying to join is not set up yet."))
                    sendToUser(username, FriendsChatChannel(clear = true))
                    return
                }

                val from = Accounts.find(username) ?: return

                // Ignore check: owner has joiner on ignore list
                if (owner.social.ignores.contains(from.username)) {
                    sendToUser(username, GameMessage(ChatMessageType.FC_NOTIFICATION, "You are not allowed to join this user's friends chat channel."))
                    sendToUser(username, FriendsChatChannel(clear = true))
                    return
                }

                val fromRank = fcRankOf(owner, from)
                val required = owner.social.friendsChat.rankToEnter.id
                if (!rankAllowed(fromRank, required)) {
                    sendToUser(username, GameMessage(ChatMessageType.FC_NOTIFICATION, "You are not allowed to join that friends chat."))
                    sendToUser(username, FriendsChatChannel(clear = true))
                    return
                }

                val room = getOrCreateRoom(owner.username)

                // Capacity check
                if (room.members.size >= 100) {
                    sendToUser(username, GameMessage(ChatMessageType.FC_NOTIFICATION, "The channel you tried to join is currently full."))
                    sendToUser(username, FriendsChatChannel(clear = true))
                    return
                }

                // Temp ban check
                val banExpiry = room.tempBans[username]
                if (banExpiry != null) {
                    if (System.currentTimeMillis() < banExpiry) {
                        sendToUser(username, GameMessage(ChatMessageType.FC_NOTIFICATION, "You have been temporarily banned from this friends chat."))
                        sendToUser(username, FriendsChatChannel(clear = true))
                        return
                    }
                    room.tempBans.remove(username)
                }

                // Legacy `ActiveFC.addPlayer` remembered the owner's display name here; both the
                // rejoin on login and `Accounts.findByDisplayName` accept it.
                from.social.currentFriendsChat = owner.displayName
                Accounts.save(from)

                val existingOwner = fcOwnerByMember[username]
                if (existingOwner != null && existingOwner != owner.username) {
                    leaveFriendsChat(username, notify = false)
                }

                room.members.add(username)
                fcOwnerByMember[username] = owner.username
                broadcastFcChannel(owner, room)
            }

            is FcKick -> {
                val fromUsername = username
                val targetUsername = packet.username.formatPlayerNameForProtocol()

                // Self-kick check
                if (targetUsername == fromUsername) {
                    sendToUser(fromUsername, GameMessage(ChatMessageType.FC_NOTIFICATION, "You can't kick yourself!"))
                    return
                }

                val ownerUsername = fcOwnerByMember[fromUsername] ?: return
                val room = fcRoomsByOwner[ownerUsername] ?: return
                if (!room.members.contains(targetUsername)) return

                val owner = Accounts.find(ownerUsername) ?: return
                val fromRank = if (fromUsername == owner.username) FriendsChatRank.OWNER.id else owner.social.friendsChat.getRank(fromUsername).id
                val targetRank = if (targetUsername == owner.username) FriendsChatRank.OWNER.id else owner.social.friendsChat.getRank(targetUsername).id
                val required = owner.social.friendsChat.rankToKick.id

                // Owner kick check
                if (targetRank >= FriendsChatRank.OWNER.id) {
                    sendToUser(fromUsername, GameMessage(ChatMessageType.FC_NOTIFICATION, "You cannot kick the owner of a channel."))
                    return
                }

                if (!rankAllowed(fromRank, required)) {
                    sendToUser(fromUsername, GameMessage(ChatMessageType.FC_NOTIFICATION, "You do not have permission to kick from this friends chat."))
                    return
                }
                if (fromUsername != owner.username && targetRank >= fromRank) {
                    sendToUser(fromUsername, GameMessage(ChatMessageType.FC_NOTIFICATION, "You cannot kick that user."))
                    return
                }

                // Apply 1-hour temp ban
                room.tempBans[targetUsername] = System.currentTimeMillis() + 3600000L

                room.members.remove(targetUsername)
                fcOwnerByMember.remove(targetUsername, ownerUsername)
                // Legacy kicked with `removePlayer(target, logout = false)`, so the kicked player
                // is not put straight back into the channel by their next login.
                forgetCurrentFriendsChat(targetUsername)
                sendToUser(targetUsername, FriendsChatChannel(clear = true))
                broadcastFcChannel(owner, room)
            }

            is FcSetRank -> {
                val ownerUsername = username
                val targetUsername = packet.username.formatPlayerNameForProtocol()
                val owner = Accounts.find(ownerUsername) ?: return
                val desired = FriendsChatRank.fromId(packet.rank)
                owner.social.friendsChat.setRank(targetUsername, desired)
                Accounts.save(owner)
                val room = fcRoomsByOwner[ownerUsername]
                if (room != null) broadcastFcChannel(owner, room)
            }

            is Chat -> {
                // Types 2 and 3 are clan chat: a world sends those as a ClanCommand and the
                // lobby's own ChatHandler goes to ClanService, never through here.
                if (chatTypeOf(username, packet.type) == 1) handleFriendsChatMessage(username, packet.message)
            }

            is PrivateMessage -> {
                handlePrivateMessage(username, packet.toDisplayName, packet.message)
            }

            is QuickChatPrivate -> {
                handleQuickChatPrivate(username, packet)
            }

            is QuickChatPublic -> {
                // Types 2 and 3 are clan chat: a world sends those as a ClanCommand and the
                // lobby's own QuickChatPublicHandler goes to ClanService, never through here.
                if (quickChatTypeOf(username, packet.chatType) == 1) handleQuickChatFriendsChat(username, packet)
            }

            else -> {
                // ignore
            }
        }
    }

    private suspend fun handlePrivateMessage(fromUsername: String, toDisplayName: String, message: String) {
        val fromAccount = Accounts.find(fromUsername) ?: return
        if (fromAccount.checkMuted()) {
            sendToUser(fromUsername, GameMessage(ChatMessageType.FRIEND_NOTIFICATION, "You are muted."))
            return
        }

        val toAccount = Accounts.findByDisplayName(toDisplayName)
        if (toAccount == null) {
            sendToUser(fromUsername, GameMessage(ChatMessageType.FRIEND_NOTIFICATION, "Unable to find $toDisplayName."))
            return
        }

        if (presenceByUsername[toAccount.username] == null && lobbyByUsername[toAccount.username] == null) {
            sendToUser(fromUsername, GameMessage(ChatMessageType.FRIEND_NOTIFICATION, "${toAccount.displayName} is offline."))
            return
        }

        if (!toAccount.onlineTo(fromAccount)) return
        if (toAccount.social.ignores.contains(fromAccount.username)) return

        sendToUser(
            toAccount.username,
            MessagePrivate(
                crown = fromAccount.rights.crown,
                displayName = fromAccount.displayName,
                // Make sure the client can resolve the sender's protocol username when displayName differs.
                quickResponseName = fromAccount.username,
                message = message,
            )
        )
        sendToUser(
            fromAccount.username,
            MessagePrivateEcho(
                senderDisplayName = toAccount.displayName,
                message = message,
            )
        )
    }

    private suspend fun handleFriendsChatMessage(fromUsername: String, message: String) {
        val ownerUsername = fcOwnerByMember[fromUsername] ?: return
        val room = fcRoomsByOwner[ownerUsername] ?: return
        if (!room.members.contains(fromUsername)) return

        val owner = Accounts.find(ownerUsername) ?: return
        val fromAccount = Accounts.find(fromUsername) ?: return
        if (fromAccount.checkMuted()) return

        val fromRank = if (fromUsername == owner.username) FriendsChatRank.OWNER.id else owner.social.friendsChat.getRank(fromUsername).id
        val required = owner.social.friendsChat.rankToSpeak.id
        if (!rankAllowed(fromRank, required)) {
            sendToUser(fromUsername, GameMessage(ChatMessageType.FC_NOTIFICATION, "You are not allowed to speak in that friends chat."))
            return
        }

        // 727 encodes chatName as a base37 long; use protocol-safe name.
        val chatName = (owner.social.friendsChat.name ?: owner.username).formatPlayerNameForProtocol()
        sendBatchToUsers(
            room.members,
            MessageFriendsChat(
                crown = fromAccount.rights.crown,
                displayName = fromAccount.displayName,
                // Make sure the client can resolve the sender's protocol username when displayName differs.
                quickResponseName = fromAccount.username,
                chatName = chatName,
                message = message,
            )
        )
    }

    // ─── QuickChat Handlers ─────────────────────────────────────────────

    private suspend fun handleQuickChatPrivate(fromUsername: String, packet: QuickChatPrivate) {
        val fromAccount = Accounts.find(fromUsername) ?: return
        if (fromAccount.checkMuted()) {
            sendToUser(fromUsername, GameMessage(ChatMessageType.FRIEND_NOTIFICATION, "You are muted."))
            return
        }

        val toAccount = Accounts.findByDisplayName(packet.toUsername)
        if (toAccount == null) {
            sendToUser(fromUsername, GameMessage(ChatMessageType.FRIEND_NOTIFICATION, "Unable to find ${packet.toUsername}."))
            return
        }

        if (presenceByUsername[toAccount.username] == null && lobbyByUsername[toAccount.username] == null) {
            sendToUser(fromUsername, GameMessage(ChatMessageType.FRIEND_NOTIFICATION, "${toAccount.displayName} is offline."))
            return
        }

        if (!toAccount.onlineTo(fromAccount)) return
        if (toAccount.social.ignores.contains(fromAccount.username)) return

        val qcMessage = QuickChatMessage(message = "", fileId = packet.qcId, data = packet.messageData)
        sendToUser(
            toAccount.username,
            MessageQuickChatPrivate(
                crown = fromAccount.rights.crown,
                displayName = fromAccount.displayName,
                quickResponseName = fromAccount.username,
                message = qcMessage,
            )
        )
        sendToUser(
            fromAccount.username,
            MessageQuickChatPrivateEcho(
                senderDisplayName = toAccount.displayName,
                message = qcMessage,
            )
        )
    }

    private suspend fun handleQuickChatFriendsChat(fromUsername: String, packet: QuickChatPublic) {
        val ownerUsername = fcOwnerByMember[fromUsername] ?: return
        val room = fcRoomsByOwner[ownerUsername] ?: return
        if (!room.members.contains(fromUsername)) return

        val owner = Accounts.find(ownerUsername) ?: return
        val fromAccount = Accounts.find(fromUsername) ?: return
        if (fromAccount.checkMuted()) return

        val fromRank = if (fromUsername == owner.username) FriendsChatRank.OWNER.id else owner.social.friendsChat.getRank(fromUsername).id
        if (!rankAllowed(fromRank, owner.social.friendsChat.rankToSpeak.id)) {
            sendToUser(fromUsername, GameMessage(ChatMessageType.FC_NOTIFICATION, "You are not allowed to speak in that friends chat."))
            return
        }

        val chatName = (owner.social.friendsChat.name ?: owner.username).formatPlayerNameForProtocol()
        val qcMessage = QuickChatMessage(message = "", fileId = packet.qcId, data = packet.messageData)
        sendBatchToUsers(
            room.members,
            MessageQuickChatFriendsChat(
                chatName = chatName,
                crown = fromAccount.rights.crown,
                displayName = fromAccount.displayName,
                quickResponseName = fromAccount.username,
                message = qcMessage,
            )
        )
    }

    // ─── FC Settings Update Handler ─────────────────────────────────────

    private suspend fun handleFcSettingsUpdate(msg: FcSettingsUpdate) {
        val account = Accounts.find(msg.username) ?: return
        account.social.friendsChat.name = msg.name
        account.social.friendsChat.rankToEnter = FriendsChatRank.fromId(msg.rankToEnter)
        account.social.friendsChat.rankToSpeak = FriendsChatRank.fromId(msg.rankToSpeak)
        account.social.friendsChat.rankToKick = FriendsChatRank.fromId(msg.rankToKick)
        account.social.friendsChat.rankToLS = FriendsChatRank.fromId(msg.rankToLS)
        Accounts.save(account)

        val room = fcRoomsByOwner[msg.username]
        if (msg.name == null && room != null) {
            // FC dissolved — kick all members
            for (member in room.members.toList()) {
                fcOwnerByMember.remove(member, msg.username)
                // Legacy `ChatManager.updateFC` closed the channel with `leaveFC(chatter, false)`,
                // which forgets it, so nobody's next login re-joins a channel that is gone.
                forgetCurrentFriendsChat(member)
                sendToUser(member, FriendsChatChannel(clear = true))
                sendToUser(member, GameMessage(ChatMessageType.FC_NOTIFICATION, "The channel has been disabled."))
            }
            room.members.clear()
            fcRoomsByOwner.remove(msg.username, room)
        } else if (room != null) {
            broadcastFcChannel(account, room)
        }
    }
}


