package org.darkan.lobby.social

import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.darkan.core.EnvVars
import org.darkan.core.Logger
import org.darkan.core.Logger.logInfo
import org.darkan.core.Logger.logTrace
import org.darkan.core.Logger.logWarn
import org.darkan.core.formatPlayerNameForDisplay
import org.darkan.core.formatPlayerNameForProtocol
import org.darkan.core.model.*
import org.darkan.core.mongo.collections.Accounts
import org.darkan.core.mongo.collections.Clans
import org.darkan.core.net.Session
import org.darkan.core.net.prot.*
import org.darkan.core.social.QuickChatMessage
import org.darkan.core.social.gateway.*
import org.darkan.core.worldlist.World
import org.darkan.lobby.Lobby
import java.util.concurrent.ConcurrentHashMap

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

    private val ccRoomsByClanName = ConcurrentHashMap<String, CcRoom>()
    private val ccClanByMember = ConcurrentHashMap<String, String>()

    private data class CcRoom(
        val clanName: String,
        val members: MutableSet<String>,
        val tempBans: ConcurrentHashMap<String, Long> = ConcurrentHashMap(),
        var updateNum: Long = 0,
    )

    private fun rankAllowed(rankId: Int, requiredId: Int): Boolean {
        if (requiredId <= FriendsChatRank.UNRANKED.id) return true
        return rankId >= requiredId
    }

    private suspend fun getFcOwnerUsername(fromUsername: String, ownerDisplayNameOrNull: String?): String? {
        if (ownerDisplayNameOrNull.isNullOrBlank()) {
            val from = Accounts.find(fromUsername) ?: return null
            val raw = from.social.currentFriendsChat
            return raw.formatPlayerNameForProtocol()
        }
        val owner = Accounts.findByDisplayName(ownerDisplayNameOrNull) ?: return null
        return owner.username
    }

    private suspend fun getOrCreateRoom(ownerUsername: String): FcRoom {
        return fcRoomsByOwner.computeIfAbsent(ownerUsername) {
            FcRoom(ownerUsername = ownerUsername, members = ConcurrentHashMap.newKeySet())
        }
    }

    private suspend fun buildFriendsChatChannel(owner: Account, room: FcRoom): FriendsChatChannel {
        val minRankKick = owner.social.friendsChat.rankToKick.id
        // 727 encodes chatName as a base37 long; use a protocol-safe name (not displayName casing/spaces).
        val chatName = (owner.social.friendsChat.name ?: owner.username).formatPlayerNameForProtocol()

        val memberAccounts = Accounts.findByUsernames(room.members)
        val accByUser = memberAccounts.associateBy { it.username }

        val members = room.members.mapNotNull { memberUsername ->
            val acc = accByUser[memberUsername] ?: return@mapNotNull null
            val presence = presenceByUsername[memberUsername]
            val worldId = presence?.worldId ?: 0
            val worldName = presence?.worldName ?: ""
            val rank = if (memberUsername == owner.username) FriendsChatRank.OWNER.id else owner.social.friendsChat.getRank(memberUsername).id
            FriendsChatChannel.FriendsChatPlayer(
                displayName = acc.displayName,
                username = acc.username,
                worldId = worldId,
                rank = rank,
                worldName = worldName,
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
        val channel = buildFriendsChatChannel(owner, room)
        sendBatchToUsers(room.members, channel)
    }

    private suspend fun leaveFriendsChat(username: String, notify: Boolean) {
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

    // ─── Clan Chat (CC) ────────────────────────────────────────────────

    private fun clanRankAllowed(rankIconId: Int, requiredIconId: Int): Boolean {
        if (requiredIconId <= ClanRank.NONE.iconId) return true
        return rankIconId >= requiredIconId
    }

    private fun getClanRank(clan: Clan, username: String): ClanRank {
        if (username == clan.leaderUsername) return ClanRank.OWNER
        return clan.members[username]?.rank ?: ClanRank.NONE
    }

    private suspend fun resolveDisplayNames(usernames: Collection<String>): Map<String, String> {
        val accounts = Accounts.findByUsernames(usernames)
        return accounts.associate { it.username to it.displayName }
    }

    private suspend fun buildClanChannelFull(clan: Clan, room: CcRoom): ClanChannelFull {
        val displayNames = resolveDisplayNames(room.members)
        val chatters = room.members.mapNotNull { username ->
            val display = displayNames[username] ?: return@mapNotNull null
            val presence = presenceByUsername[username]
            val worldId = presence?.worldId ?: 0
            val rank = getClanRank(clan, username)
            ClanChannelFull.ClanChannelChatter(displayName = display, rank = rank.iconId, worldId = worldId)
        }.toTypedArray()

        return ClanChannelFull(
            main = true,
            clanName = clan.name,
            updateNum = room.updateNum,
            kickRank = clan.ccKickRank.iconId,
            talkRank = clan.ccChatRank.iconId,
            chatters = chatters,
        )
    }

    private suspend fun broadcastCcChannel(clan: Clan, room: CcRoom) {
        room.updateNum++
        val packet = buildClanChannelFull(clan, room)
        sendBatchToUsers(room.members, packet)
    }

    private suspend fun buildClanSettingsFull(clan: Clan, updateCount: Int): ClanSettingsFull {
        val allUsernames = clan.members.keys + clan.bannedUsers
        val displayNames = resolveDisplayNames(allUsernames)

        val members = clan.members.entries.map { (username, data) ->
            val display = displayNames[username] ?: username
            ClanSettingsFull.ClanSettingsMember(displayName = display, rank = data.rank.iconId)
        }.toTypedArray()

        val banned = clan.bannedUsers.map { username ->
            displayNames[username] ?: username
        }.toTypedArray()

        val settings = clan.settings.map { (key, value) ->
            val intKey = key.toInt()
            when (value) {
                is Int -> ClanSettingsFull.ClanVarSetting(key = intKey, intValue = value)
                is Long -> ClanSettingsFull.ClanVarSetting(key = intKey, longValue = value)
                is String -> ClanSettingsFull.ClanVarSetting(key = intKey, stringValue = value)
                else -> ClanSettingsFull.ClanVarSetting(key = intKey, intValue = 0)
            }
        }.toTypedArray()

        return ClanSettingsFull(
            main = true,
            clanName = clan.name,
            updateCount = updateCount,
            allowGuests = clan.ccChatRank == ClanRank.NONE,
            talkRank = clan.ccChatRank.iconId,
            kickRank = clan.ccKickRank.iconId,
            members = members,
            bannedUsers = banned,
            settings = settings,
        )
    }

    private suspend fun sendCcSettingsFull(clan: Clan, toUsername: String) {
        val room = ccRoomsByClanName[clan.name]
        val updateCount = room?.updateNum?.toInt() ?: 0
        sendToUser(toUsername, buildClanSettingsFull(clan, updateCount))
    }

    private suspend fun broadcastCcSettings(clan: Clan, room: CcRoom) {
        val packet = buildClanSettingsFull(clan, room.updateNum.toInt())
        sendBatchToUsers(room.members, packet)
    }

    private suspend fun joinClanChat(username: String) {
        val account = Accounts.find(username) ?: return
        val clanName = account.social.clanName
        if (clanName == null) {
            // Try guest join
            val guestClan = account.social.guestedClanChat
            if (guestClan != null) {
                joinClanChatAsGuest(username, guestClan)
            }
            return
        }

        val clan = Clans.find(clanName) ?: return
        if (!clan.members.containsKey(username)) return

        val existingClan = ccClanByMember[username]
        if (existingClan != null) {
            if (existingClan == clanName) return // already in this CC
            leaveClanChat(username, notify = false)
        }

        val room = ccRoomsByClanName.computeIfAbsent(clanName) {
            CcRoom(clanName = clanName, members = ConcurrentHashMap.newKeySet())
        }
        room.members.add(username)
        ccClanByMember[username] = clanName

        sendCcSettingsFull(clan, username)
        sendToUser(username, VarclanEnable())
        broadcastCcChannel(clan, room)
    }

    private suspend fun joinClanChatAsGuest(username: String, clanName: String) {
        val clan = Clans.find(clanName) ?: return

        // Check if guests are allowed
        if (clan.ccChatRank != ClanRank.NONE) return

        val room = ccRoomsByClanName.computeIfAbsent(clanName) {
            CcRoom(clanName = clanName, members = ConcurrentHashMap.newKeySet())
        }

        // Check temp ban
        val banExpiry = room.tempBans[username]
        if (banExpiry != null && System.currentTimeMillis() < banExpiry) return

        val existingClan = ccClanByMember[username]
        if (existingClan != null) {
            if (existingClan == clanName) return
            leaveClanChat(username, notify = false)
        }

        room.members.add(username)
        ccClanByMember[username] = clanName

        sendCcSettingsFull(clan, username)
        sendToUser(username, VarclanEnable())
        broadcastCcChannel(clan, room)
    }

    private suspend fun leaveClanChat(username: String, notify: Boolean) {
        val clanName = ccClanByMember.remove(username) ?: return
        val room = ccRoomsByClanName[clanName] ?: return
        room.members.remove(username)
        if (notify) {
            sendToUser(username, ClanChannelFull(main = true))
            sendToUser(username, VarclanDisable())
        }
        val clan = Clans.find(clanName)
        if (clan != null && room.members.isNotEmpty()) {
            broadcastCcChannel(clan, room)
        }
        if (room.members.isEmpty()) {
            ccRoomsByClanName.remove(clanName, room)
        }
    }

    private suspend fun handleCcKick(kickerUsername: String, packet: ClanChannelKickUser) {
        val clanName = ccClanByMember[kickerUsername] ?: return
        val room = ccRoomsByClanName[clanName] ?: return
        val clan = Clans.find(clanName) ?: return

        // Resolve target: by username from packet
        val targetUsername = packet.username.formatPlayerNameForProtocol()
        if (!room.members.contains(targetUsername)) return

        val kickerRank = getClanRank(clan, kickerUsername)
        val targetRank = getClanRank(clan, targetUsername)

        if (kickerRank.iconId < clan.ccKickRank.iconId) {
            sendToUser(kickerUsername, GameMessage(ChatMessageType.CLAN_NOTIFICATION, "You do not have permission to kick from this clan chat."))
            return
        }
        if (kickerUsername != clan.leaderUsername && targetRank.ordinal >= kickerRank.ordinal) {
            sendToUser(kickerUsername, GameMessage(ChatMessageType.CLAN_NOTIFICATION, "You cannot kick that user."))
            return
        }

        // If target is a guest (not in clan members), add temp ban
        if (!clan.members.containsKey(targetUsername)) {
            room.tempBans[targetUsername] = System.currentTimeMillis() + 3600000L
        }

        ccClanByMember.remove(targetUsername, clanName)
        room.members.remove(targetUsername)
        sendToUser(targetUsername, ClanChannelFull(main = true))
        sendToUser(targetUsername, VarclanDisable())
        broadcastCcChannel(clan, room)
    }

    private suspend fun handleClanChatMessage(fromUsername: String, message: String) {
        val clanName = ccClanByMember[fromUsername] ?: return
        val room = ccRoomsByClanName[clanName] ?: return
        if (!room.members.contains(fromUsername)) return

        val clan = Clans.find(clanName) ?: return
        val fromAccount = Accounts.find(fromUsername) ?: return
        if (fromAccount.checkMuted()) return

        val fromRank = getClanRank(clan, fromUsername)
        if (!clanRankAllowed(fromRank.iconId, clan.ccChatRank.iconId)) {
            sendToUser(fromUsername, GameMessage(ChatMessageType.CLAN_NOTIFICATION, "You are not allowed to speak in that clan chat."))
            return
        }

        val guest = !clan.members.containsKey(fromUsername)
        sendBatchToUsers(
            room.members,
            MessageClanChannel(
                guest = guest,
                crown = fromAccount.rights.crown,
                displayName = fromAccount.displayName,
                message = message,
            )
        )
    }

    // ─── Clan Management ─────────────────────────────────────────────

    suspend fun createClan(leaderUsername: String, clanName: String): Boolean {
        if (clanName.isBlank() || clanName.length > 12) return false
        if (!clanName.matches(Regex("^[a-zA-Z0-9 ]+$"))) return false

        val existing = Clans.find(clanName)
        if (existing != null) return false

        val account = Accounts.find(leaderUsername) ?: return false
        if (account.social.clanName != null) return false

        val clan = Clan(
            name = clanName,
            leaderUsername = leaderUsername,
        )
        clan.members[leaderUsername] = ClanMemberData(rank = ClanRank.OWNER)
        Clans.save(clan)

        account.social.clanName = clanName
        Accounts.save(account)

        joinClanChat(leaderUsername)
        return true
    }

    suspend fun addClanMember(clanName: String, targetUsername: String, inviterUsername: String): Boolean {
        val clan = Clans.find(clanName) ?: return false
        if (clan.members.size >= Clan.MAX_MEMBERS) return false

        val targetAccount = Accounts.find(targetUsername) ?: return false
        if (targetAccount.social.clanName != null) return false

        clan.members[targetUsername] = ClanMemberData(rank = ClanRank.RECRUIT)
        Clans.save(clan)

        targetAccount.social.clanName = clanName
        Accounts.save(targetAccount)

        // If target is online, join them to CC
        if (presenceByUsername.containsKey(targetUsername) || lobbyByUsername.containsKey(targetUsername)) {
            joinClanChat(targetUsername)
        }

        // Broadcast updated settings to current CC members
        val room = ccRoomsByClanName[clanName]
        if (room != null) {
            broadcastCcSettings(clan, room)
        }
        return true
    }

    suspend fun removeClanMember(clanName: String, targetUsername: String, kickerUsername: String? = null): Boolean {
        val clan = Clans.find(clanName) ?: return false

        if (kickerUsername != null) {
            val kickerRank = getClanRank(clan, kickerUsername)
            val targetRank = getClanRank(clan, targetUsername)
            if (kickerRank.iconId < clan.ccKickRank.iconId) return false
            if (kickerUsername != clan.leaderUsername && targetRank.ordinal >= kickerRank.ordinal) return false
        }

        // Owner leaving requires transferring ownership
        if (targetUsername == clan.leaderUsername) {
            val deputy = clan.members.entries
                .filter { it.key != targetUsername }
                .maxByOrNull { it.value.rank.ordinal }
            if (deputy == null) {
                // Last member, delete clan
                leaveClanChat(targetUsername, notify = true)
                Clans.delete(clanName)
                val account = Accounts.find(targetUsername)
                if (account != null) {
                    account.social.clanName = null
                    Accounts.save(account)
                }
                return true
            }
            clan.leaderUsername = deputy.key
            deputy.value.rank = ClanRank.OWNER
        }

        clan.members.remove(targetUsername)
        Clans.save(clan)

        val account = Accounts.find(targetUsername)
        if (account != null) {
            account.social.clanName = null
            Accounts.save(account)
        }

        leaveClanChat(targetUsername, notify = true)

        val room = ccRoomsByClanName[clanName]
        if (room != null) {
            broadcastCcSettings(clan, room)
        }
        return true
    }

    suspend fun leaveClan(username: String): Boolean {
        val account = Accounts.find(username) ?: return false
        val clanName = account.social.clanName ?: return false
        return removeClanMember(clanName, username)
    }

    suspend fun updateClanSettings(clanName: String, updates: Map<Int, Any>) {
        val clan = Clans.find(clanName) ?: return
        clan.settings.putAll(updates.mapKeys { it.key.toString() })
        Clans.save(clan)
        val room = ccRoomsByClanName[clanName]
        if (room != null) {
            broadcastCcSettings(clan, room)
        }
    }

    // ─── Friend Status ───────────────────────────────────────────────

    private fun friendStatusUpdateFor(viewer: Account, friend: Account): FriendStatus.FriendStatusUpdate {
        val canSeeFriend = viewer.onlineTo(friend)
        val friendPresence = if (canSeeFriend) presenceByUsername[friend.username] else null
        val worldId = friendPresence?.worldId ?: 0
        val worldName = friendPresence?.worldName
        val rank = viewer.social.friendsChat.getRank(friend.username).id
        return FriendStatus.FriendStatusUpdate(
            warnMessage = false,
            displayName = friend.displayName,
            previousDisplayName = friend.prevDisplayName,
            world = worldId,
            friendsChatRank = rank,
            referrer = false,
            worldName = worldName,
        )
    }

    private fun ignoreDisplayName(username: String, accountsByUsername: Map<String, Account>): String {
        return accountsByUsername[username]?.displayName ?: username.formatPlayerNameForDisplay()
    }

    private suspend fun sendFriendListFull(toUsername: String) {
        val account = Accounts.find(toUsername) ?: return
        val friendAccounts = Accounts.findByUsernames(account.social.friends)
        val updates = friendAccounts.map { friendStatusUpdateFor(account, it) }.toTypedArray()
        sendToUser(account.username, FriendStatus(updates))
        sendToUser(account.username, FriendlistLoaded())
    }

    private suspend fun sendIgnoreListFull(toUsername: String) {
        val account = Accounts.find(toUsername) ?: return
        val ignoreAccounts = Accounts.findByUsernames(account.social.ignores)
        val ignoreMap = ignoreAccounts.associateBy { it.username }
        val ignores = account.social.ignores.map { ign ->
            UpdateIgnoreList.IgnoreEntry(username = ign, displayName = ignoreDisplayName(ign, ignoreMap))
        }.toTypedArray()
        sendToUser(account.username, UpdateIgnoreList(ignores))
    }

    private suspend fun notifyMutualFriendsAbout(account: Account) {
        for (friendUsername in account.social.friends) {
            val friendAccount = Accounts.find(friendUsername) ?: continue
            if (!friendAccount.social.friends.contains(account.username)) continue

            val update = friendStatusUpdateFor(friendAccount, account)
            sendToUser(friendAccount.username, FriendStatus(arrayOf(update)))
        }
    }

    /**
     * Sends the full friend list, ignore list, chat filter, and mutual-friend
     * notifications using a single batch DB query instead of 6+N individual lookups.
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
            UpdateIgnoreList.IgnoreEntry(
                username = ign,
                displayName = ignoreDisplayName(ign, accountsByUsername),
            )
        }.toTypedArray()
        sendToUser(username, UpdateIgnoreList(ignores))

        // Send chat filter echo
        sendToUser(username, ChatFilterSettingsPrivateChat(account.social.status.toInt()))

        // Notify mutual friends — no additional DB queries needed
        for (friendUsername in account.social.friends) {
            val friendAccount = accountsByUsername[friendUsername] ?: continue
            if (!friendAccount.social.friends.contains(username)) continue
            if (presenceByUsername[friendUsername] == null && lobbyByUsername[friendUsername] == null) continue
            val update = friendStatusUpdateFor(friendAccount, account)
            sendToUser(friendUsername, FriendStatus(arrayOf(update)))
        }
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
        initializeSocial(account)
    }

    suspend fun unregisterLobbyPlayerSession(username: String) {
        val prior = lobbyByUsername.remove(username) ?: return
        usernameByDisplayLower.remove(prior.displayName.lowercase(), username)
        val acc = Accounts.find(username)
        if (acc != null) notifyMutualFriendsAbout(acc)
        leaveFriendsChat(username, notify = false)
        leaveClanChat(username, notify = false)
    }

    suspend fun broadcastAnnouncement(msg: AnnouncementBroadcast) {
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

                when (msg) {
                    is WorldHello -> {
                        if (msg.token != EnvVars.socialGatewayToken) {
                            val resp = WorldHelloAck(ok = false, message = "Unauthorized")
                            socket.send(Frame.Text(SocialGatewayWireJson.json.encodeToString(SocialGatewayWireMessage.serializer(), resp)))
                            socket.close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Unauthorized"))
                            return@coroutineScope
                        }

                        val worldId = msg.world.worldId
                        val session = WorldSession(worldId, msg.world.worldName, socket)
                        worldsById[worldId]?.markClosed()
                        worldsById[worldId] = session
                        worldSession = session
                        worldInfoById[worldId] = msg.world
                        Lobby.worldList.put(World(msg.world.toWorldMetadata()))

                        logInfo("SocialGateway: world connected worldId=${worldId} name=${msg.world.worldName} host=${msg.world.publicHost}:${msg.world.port}")
                        val resp = WorldHelloAck(ok = true, message = null)
                        socket.send(Frame.Text(SocialGatewayWireJson.json.encodeToString(SocialGatewayWireMessage.serializer(), resp)))
                    }

                    is PlayerOnline -> {
                        val session = worldSession ?: continue
                        val username = msg.username.formatPlayerNameForProtocol()
                        // Player has moved to a world — evict stale lobby entry so
                        // sendToUser routes packets to the world session, not the
                        // (now-dead) lobby session.
                        lobbyByUsername.remove(username)
                        val presence = Presence(
                            username = username,
                            displayName = msg.displayName,
                            rightsCrown = msg.rightsCrown,
                            privateStatus = msg.privateStatus,
                            worldId = session.worldId,
                            worldName = session.worldName,
                            lastSeenMs = System.currentTimeMillis(),
                        )
                        presenceByUsername[username] = presence
                        usernameByDisplayLower[msg.displayName.lowercase()] = username
                        Lobby.worldList.get(session.worldId)?.let { it.playersOnline++ }
                        launch {
                            try {
                                val acc = Accounts.find(username) ?: return@launch
                                initializeSocial(acc)
                                joinClanChat(username)
                                // Auto-rejoin friends chat from saved state
                                val savedFc = acc.social.currentFriendsChat
                                if (savedFc.isNotBlank()) {
                                    handleClientPacket(username, FcJoin(savedFc))
                                }
                            } catch (e: Exception) {
                                logWarn("SocialGateway: failed initial snapshots user=$username", e)
                            }
                        }
                    }

                    is PlayerOffline -> {
                        val username = msg.username.formatPlayerNameForProtocol()
                        val prior = presenceByUsername.remove(username)
                        if (prior != null) {
                            usernameByDisplayLower.remove(prior.displayName.lowercase(), username)
                            Lobby.worldList.get(prior.worldId)?.let { if (it.playersOnline > 0) it.playersOnline-- }
                        }
                        launch {
                            val acc = Accounts.find(username)
                            if (acc != null) notifyMutualFriendsAbout(acc)
                        }
                        launch { leaveFriendsChat(username, notify = false) }
                        launch { leaveClanChat(username, notify = false) }
                    }

                    is ClientPacketEnvelope -> {
                        handleClientPacket(msg.username.formatPlayerNameForProtocol(), msg.packet)
                    }

                    is AnnouncementBroadcast -> {
                        // Allow worlds to rebroadcast if needed.
                        broadcastAnnouncement(msg)
                    }

                    is FcSettingsUpdate -> {
                        launch { handleFcSettingsUpdate(msg) }
                    }

                    is ClanCreateRequest -> {
                        launch {
                            val success = createClan(msg.username, msg.clanName)
                            val response = ClanCreateResponse(
                                username = msg.username,
                                success = success,
                                message = if (success) null else "Unable to create clan.",
                            )
                            sendToWorld(worldSession?.worldId ?: return@launch, response)
                        }
                    }

                    is ClanLeaveRequest -> {
                        launch { leaveClan(msg.username) }
                    }

                    is ClanAddMemberRequest -> {
                        launch {
                            val account = Accounts.find(msg.inviterUsername) ?: return@launch
                            val clanName = account.social.clanName ?: return@launch
                            addClanMember(clanName, msg.targetUsername, msg.inviterUsername)
                        }
                    }

                    is ClanKickMemberRequest -> {
                        launch {
                            val account = Accounts.find(msg.kickerUsername) ?: return@launch
                            val clanName = account.social.clanName ?: return@launch
                            removeClanMember(clanName, msg.targetUsername, msg.kickerUsername)
                        }
                    }

                    is ClanSetRankRequest -> {
                        launch {
                            val account = Accounts.find(msg.username) ?: return@launch
                            val clanName = account.social.clanName ?: return@launch
                            setClanMemberRank(clanName, msg.targetUsername, msg.rank, msg.username)
                        }
                    }

                    is ClanBanMemberRequest -> {
                        launch {
                            val account = Accounts.find(msg.username) ?: return@launch
                            val clanName = account.social.clanName ?: return@launch
                            banClanMember(clanName, msg.targetUsername, msg.username)
                        }
                    }

                    is ClanUnbanMemberRequest -> {
                        launch {
                            val account = Accounts.find(msg.username) ?: return@launch
                            val clanName = account.social.clanName ?: return@launch
                            unbanClanMember(clanName, msg.targetUsername, msg.username)
                        }
                    }

                    is ClanGuestJoinRequest -> {
                        launch {
                            val account = Accounts.find(msg.username) ?: return@launch
                            account.social.guestedClanChat = msg.clanName
                            Accounts.save(account)
                            joinClanChatAsGuest(msg.username, msg.clanName)
                        }
                    }

                    is ClanGuestLeaveRequest -> {
                        launch {
                            val account = Accounts.find(msg.username) ?: return@launch
                            account.social.guestedClanChat = null
                            Accounts.save(account)
                            leaveClanChat(msg.username, notify = true)
                        }
                    }

                    else -> {
                        logTrace("SocialGateway ignoring message type=${msg::class.simpleName}")
                    }
                }
            }
        } catch (_: CancellationException) {
            // ignore
        } catch (e: Exception) {
            Logger.logError("SocialGateway socket error", e)
        } finally {
            val session = worldSession
            if (session != null) {
                session.markClosed()
                Lobby.worldList.remove(session.worldId)
                worldsById.remove(session.worldId, session)
                worldInfoById.remove(session.worldId)

                val toRemove = presenceByUsername
                    .filterValues { it.worldId == session.worldId }
                    .keys
                for (username in toRemove) {
                    val prior = presenceByUsername.remove(username)
                    if (prior != null) {
                        usernameByDisplayLower.remove(prior.displayName.lowercase(), username)
                    }
                    leaveFriendsChat(username, notify = false)
                    leaveClanChat(username, notify = false)
                }

                logInfo("SocialGateway: world disconnected worldId=${session.worldId}")
            }
        }
    }

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

    private suspend fun sendToWorld(worldId: Int, msg: SocialGatewayWireMessage) {
        val session = worldsById[worldId] ?: return
        val text = SocialGatewayWireJson.json.encodeToString(SocialGatewayWireMessage.serializer(), msg)
        try {
            session.sendText(text)
        } catch (e: Exception) {
            logWarn("Failed sending message to world=$worldId type=${msg::class.simpleName}", e)
        }
    }

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
                notifyMutualFriendsAbout(account)
            }

            is AddFriend -> {
                val from = Accounts.find(username) ?: return
                val target = Accounts.findByDisplayName(packet.name)
                if (target == null) {
                    sendToUser(username, GameMessage(ChatMessageType.FRIEND_NOTIFICATION, "Player not found."))
                    return
                }
                if (from.social.friends.add(target.username)) Accounts.save(from)
                sendFriendListFull(from.username)
                val targetAcc = Accounts.find(target.username) ?: target
                if (targetAcc.social.friends.contains(from.username)) {
                    val update = friendStatusUpdateFor(targetAcc, from)
                    sendToUser(targetAcc.username, FriendStatus(arrayOf(update)))
                }
            }

            is RemoveFriend -> {
                val from = Accounts.find(username) ?: return
                val target = Accounts.findByDisplayName(packet.name) ?: return
                if (from.social.friends.remove(target.username)) Accounts.save(from)
                sendFriendListFull(from.username)
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

                val fromRank = if (from.username == owner.username) FriendsChatRank.OWNER.id else owner.social.friendsChat.getRank(from.username).id
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

                from.social.currentFriendsChat = owner.username
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
                val effectiveType = if (packet.type != -1) packet.type else (chatTypeByUsername[username] ?: 0)
                when (effectiveType) {
                    1 -> handleFriendsChatMessage(username, packet.message)
                    2 -> handleClanChatMessage(username, packet.message)
                    else -> { /* ignore */ }
                }
            }

            is PrivateMessage -> {
                handlePrivateMessage(username, packet.toDisplayName, packet.message)
            }

            is ClanChannelKickUser -> {
                handleCcKick(username, packet)
            }

            is QuickChatPrivate -> {
                handleQuickChatPrivate(username, packet)
            }

            is QuickChatPublic -> {
                val effectiveType = if (packet.chatType != 0) packet.chatType else (chatTypeByUsername[username] ?: 0)
                when (effectiveType) {
                    1 -> handleQuickChatFriendsChat(username, packet)
                    2 -> handleQuickChatClanChat(username, packet)
                    else -> { /* ignore */ }
                }
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

    private suspend fun handleQuickChatClanChat(fromUsername: String, packet: QuickChatPublic) {
        val clanName = ccClanByMember[fromUsername] ?: return
        val room = ccRoomsByClanName[clanName] ?: return
        if (!room.members.contains(fromUsername)) return

        val clan = Clans.find(clanName) ?: return
        val fromAccount = Accounts.find(fromUsername) ?: return
        if (fromAccount.checkMuted()) return

        val fromRank = getClanRank(clan, fromUsername)
        if (!clanRankAllowed(fromRank.iconId, clan.ccChatRank.iconId)) {
            sendToUser(fromUsername, GameMessage(ChatMessageType.CLAN_NOTIFICATION, "You are not allowed to speak in that clan chat."))
            return
        }

        val guest = !clan.members.containsKey(fromUsername)
        val qcMessage = QuickChatMessage(message = "", fileId = packet.qcId, data = packet.messageData)
        sendBatchToUsers(
            room.members,
            MessageQuickChatClanChannel(
                guest = guest,
                crown = fromAccount.rights.crown,
                displayName = fromAccount.displayName,
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
                sendToUser(member, FriendsChatChannel(clear = true))
                sendToUser(member, GameMessage(ChatMessageType.FC_NOTIFICATION, "The channel has been disabled."))
            }
            room.members.clear()
            fcRoomsByOwner.remove(msg.username, room)
        } else if (room != null) {
            broadcastFcChannel(account, room)
        }
    }

    // ─── Clan Management via Gateway ─────────────────────────────────────

    private suspend fun setClanMemberRank(clanName: String, targetUsername: String, rank: Int, setterUsername: String) {
        val clan = Clans.find(clanName) ?: return
        val setterRank = getClanRank(clan, setterUsername)
        if (setterRank.iconId < ClanRank.ADMIN.iconId && setterUsername != clan.leaderUsername) {
            sendToUser(setterUsername, GameMessage(ChatMessageType.CLAN_NOTIFICATION, "You do not have permission to change ranks."))
            return
        }
        val memberData = clan.members[targetUsername]
        if (memberData == null) {
            sendToUser(setterUsername, GameMessage(ChatMessageType.CLAN_NOTIFICATION, "That player is not a clan member."))
            return
        }
        val newRank = ClanRank.fromIconId(rank)
        memberData.rank = newRank
        Clans.save(clan)
        val room = ccRoomsByClanName[clanName]
        if (room != null) {
            broadcastCcSettings(clan, room)
            broadcastCcChannel(clan, room)
        }
    }

    private suspend fun banClanMember(clanName: String, targetUsername: String, bannerUsername: String) {
        val clan = Clans.find(clanName) ?: return
        val bannerRank = getClanRank(clan, bannerUsername)
        if (bannerRank.iconId < clan.ccKickRank.iconId) {
            sendToUser(bannerUsername, GameMessage(ChatMessageType.CLAN_NOTIFICATION, "You do not have permission to ban users."))
            return
        }
        if (clan.bannedUsers.contains(targetUsername)) return
        clan.bannedUsers.add(targetUsername)
        Clans.save(clan)
        // Also kick from CC if online
        leaveClanChat(targetUsername, notify = true)
        val room = ccRoomsByClanName[clanName]
        if (room != null) broadcastCcSettings(clan, room)
    }

    private suspend fun unbanClanMember(clanName: String, targetUsername: String, unbannerUsername: String) {
        val clan = Clans.find(clanName) ?: return
        val unbannerRank = getClanRank(clan, unbannerUsername)
        if (unbannerRank.iconId < clan.ccKickRank.iconId) {
            sendToUser(unbannerUsername, GameMessage(ChatMessageType.CLAN_NOTIFICATION, "You do not have permission to unban users."))
            return
        }
        if (!clan.bannedUsers.remove(targetUsername)) return
        Clans.save(clan)
        val room = ccRoomsByClanName[clanName]
        if (room != null) broadcastCcSettings(clan, room)
    }
}


