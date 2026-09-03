package org.darkan.world.server

import com.rs.Settings
import com.rs.db.WorldDB
import com.rs.game.World
import com.rs.game.tasks.WorldTasks
import com.rs.game.content.clans.ClansManager
import com.rs.game.model.entity.player.Player
import com.rs.utils.AccountLimiter
import com.rs.utils.MachineInformation
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.util.network.*
import io.ktor.utils.io.*
import io.ktor.utils.io.CancellationException
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import kotlinx.io.readByteArray
import kotlinx.io.readUByte
import kotlinx.io.readUShort
import org.darkan.core.EnvVars
import org.darkan.core.Logger.logError
import org.darkan.core.Logger.logInfo
import org.darkan.core.Logger.logTrace
import org.darkan.core.Logger.logWarn
import org.darkan.core.formatPlayerNameForProtocol
import org.darkan.core.model.MachineInformation as CoreMachineInformation
import org.darkan.core.net.*
import org.darkan.core.net.prot.Codec
import org.darkan.core.net.prot.packet.WorldLoginDetails
import world.gregs.voidps.buffer.*
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.secure.RSA
import world.gregs.voidps.cache.secure.decryptXtea
import org.darkan.core.Logger
import org.darkan.core.model.Account
import org.darkan.core.mongo.collections.Accounts
import org.darkan.core.net.login.LoginToken
import org.darkan.core.model.Clan
import org.darkan.core.mongo.collections.Clans
import org.darkan.core.net.prot.ClientProt
import org.darkan.core.net.prot.ServerProt
import org.darkan.core.net.prot.packet.FcJoin
import org.darkan.core.social.gateway.AnnouncementBroadcast
import org.darkan.core.social.gateway.ClanCheckNameResponse
import org.darkan.core.social.gateway.ClanCreateResponse
import org.darkan.core.social.gateway.ServerPacketBatch
import org.darkan.core.social.gateway.ServerPacketEnvelope
import org.darkan.core.social.gateway.SocialGatewayWireMessage
import org.darkan.world.social.SocialClient
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.function.Consumer

object WorldServer {
    private lateinit var job: Job
    private lateinit var dispatcher: ExecutorCoroutineDispatcher

    private lateinit var serverSocket: ServerSocket
    private lateinit var selectorManager: SelectorManager

    lateinit var socialClient: SocialClient
        private set

    private val worldRsaMod = BigInteger(EnvVars.worldRsaModulus)
    private val worldRsaExp = BigInteger(EnvVars.worldRsaExponent)

    private val pendingLogins = ConcurrentHashMap.newKeySet<String>()

    fun start(port: Int): Job {
        val executor = Executors.newCachedThreadPool()
        dispatcher = executor.asCoroutineDispatcher()
        selectorManager = ActorSelectorManager(dispatcher)
        val scope = CoroutineScope(dispatcher)

        socialClient = SocialClient(scope) { msg -> handleSocialMessage(msg) }
        socialClient.start()
        logInfo("Starting world server...")
        serverSocket = runBlocking {
            aSocket(selectorManager).tcp().bind("0.0.0.0", port) { reuseAddress = true }
        }
        job = scope.launch {
            try {
                supervisorScope {
                    logInfo("World server started on port $port")
                    while (isActive) {
                        val socket = serverSocket.accept()
                        logTrace("Client connected: ${socket.remoteAddress.ipString()}")
                        connectClient(socket)
                    }
                }
            } catch (_: CancellationException) {
                logInfo("World server stopping...")
            } catch (e: Exception) {
                logError("Error in World server", e)
            }
        }
        return job
    }

    fun stop() {
        try {
            job.cancel()
            dispatcher.close()
            if (::serverSocket.isInitialized)
                serverSocket.close()
            if (::selectorManager.isInitialized)
                selectorManager.close()
        } catch (e: Exception) {
            logError("Error stopping World server", e)
            throw e
        }
    }

    private fun CoroutineScope.connectClient(socket: Socket) = launch(dispatcher + CoroutineExceptionHandler { _, throwable -> logTrace("Error connecting client: ${throwable.message}") }) {
        try {
            val input = socket.openReadChannel()
            val output = socket.openWriteChannel(autoFlush = false)
            val ip = socket.remoteAddress.ipString()

            socket.use { socket ->
                when (val reqOpcode = input.readByte().toInt()) {
                    RequestOpcode.CONNECT_LOGIN -> initWorldLogin(input, output, ip)
                    else -> {
                        logTrace("Invalid request opcode for world: $reqOpcode")
                        output.finish(ResponseOpcode.INVALID_LOGIN_SERVER)
                    }
                }
            }
        } catch (e: Exception) {
            when {
                e is CancellationException -> {}
                Session.isExpectedDisconnect(e) -> logTrace("World client disconnected: ${e::class.simpleName}")
                else -> logError("Error handling world client connection", e)
            }
        } finally {
            socket.close()
        }
    }

    private suspend fun initWorldLogin(input: ByteReadChannel, output: ByteWriteChannel, ip: String) {
        output.respond(ResponseOpcode.JS5_SYNC)

        val opcode = input.readByte().toInt()
        if (opcode != RequestOpcode.LOGIN && opcode != RequestOpcode.RECONNECT)
            return output.finish(ResponseOpcode.LOGIN_SERVER_REJECTED_SESSION)
        if (pendingLogins.size > 20)
            return output.finish(ResponseOpcode.LOGIN_SERVER_REJECTED_SESSION)

        val update = World.ticksTillUpdate
        if (update != -1L && update < 20)
            return output.finish(ResponseOpcode.SERVER_BEING_UPDATED)

        val size = input.readShort().toInt()
        val packet = input.readPacket(size)

        val major = packet.readInt()
        val codec = Codec.get(major) ?: return output.finish(ResponseOpcode.GAME_UPDATE).also { logInfo("Invalid codec: $major") }
        val minor = packet.readInt()

        val gameStateIsUnk10 = packet.readUByte().toInt() == 1

        val rsaSize = packet.readUShort().toInt()
        val sensitiveData = ByteReadPacket(RSA.crypt(packet.readByteArray(rsaSize), worldRsaMod, worldRsaExp))
        if (sensitiveData.readUByte().toInt() != 10)
            return output.finish(ResponseOpcode.BAD_SESSION_ID)

        val isaacKeys = IntArray(4) { sensitiveData.readInt() }
        if (sensitiveData.readLong().toInt() != 0)
            return output.finish(ResponseOpcode.BAD_SESSION_ID)

        val lobbyAuthToken = sensitiveData.readRSString() // HMAC-signed LoginToken (lobby puts it in RSA block's password slot)
        sensitiveData.readLong()
        sensitiveData.readLong()

        val xtea = packet.decryptXtea(isaacKeys)
        val stringUsername = xtea.readBoolean()
        val username = (if (stringUsername) xtea.readRSString() else xtea.readLong().toRSString()).formatPlayerNameForProtocol()
        val displayMode = xtea.readUByte().toInt()
        val screenWidth = xtea.readUShort().toInt()
        val screenHeight = xtea.readUShort().toInt()
        xtea.readUByte() // unknown2
        xtea.readByteArray(24) // randomDat
        val settings = xtea.readRSString()
        val affid = xtea.readInt()

        val prefSize = xtea.readUByte().toInt()
        for (i in 0 until prefSize)
            xtea.readUByte() // prefs

        // parse() consumes the machine-info version byte itself; reading it here too shifted every field.
        val machineInfo = CoreMachineInformation.parse(xtea)
        if (machineInfo.version != 6)
            logError("Unexpected machine info version for $username (version=${machineInfo.version})")

        xtea.readInt() // varVerifyId
        xtea.readLong() // machineUid
        xtea.readRSString() // static loginToken (applet param 13, not the HMAC auth token)
        if (xtea.readUByte().toInt() == 1)
            xtea.readRSString() // supplementaryLoginData
        xtea.readUByte() // jagtheora
        xtea.readUByte() // isApplet
        xtea.readUByte() // signedTerms
        xtea.readByte() // loginType
        xtea.readInt() // sessionToken

        val js5AuthToken = xtea.readRSString()
        if (js5AuthToken != EnvVars.worldToken) {
            logError("World token mismatch: expected=${EnvVars.worldToken}, got=$js5AuthToken from $username")
        }

        xtea.readUByte() // unknown
        val worldId = xtea.readInt()

        // CRC validation
        for (index in 0 until 36) {
            val receivedCRC = xtea.readInt()
            if (index < Cache.get().indexCount()) {
                val crc = Cache.get().indexCrcs()[index]
                if (crc != receivedCRC && index < 32) {
                    logError("CRC mismatch at index $index: expected=$crc, got=$receivedCRC from $username")
                    return output.finish(ResponseOpcode.GAME_UPDATE)
                }
            }
        }

        if (World.players.size() >= Settings.PLAYERS_LIMIT - 10)
            return output.finish(ResponseOpcode.WORLD_FULL)
        if (AccountLimiter.getSessionsIP(ip) >= 3)
            return output.finish(ResponseOpcode.LOGIN_LIMIT_EXCEEDED)
        if (!pendingLogins.add(username))
            return output.finish(ResponseOpcode.LOGIN_LIMIT_EXCEEDED)

        val verified = LoginToken.verify(lobbyAuthToken, System.currentTimeMillis(), EnvVars.worldLoginTokenSecret)
        if (verified == null || verified.username != username) {
            logError("LoginToken verify failed for $username: verified=${verified?.username}")
            pendingLogins.remove(username)
            return output.finish(ResponseOpcode.INVALID_CREDENTIALS)
        }

        val account = try {
            Accounts.find(username)
        } catch (e: Exception) {
            logError("Error loading account for $username", e)
            pendingLogins.remove(username)
            return output.finish(ResponseOpcode.LOGIN_SERVER_OFFLINE)
        }
        if (account == null || account.username.isEmpty()) {
            pendingLogins.remove(username)
            return output.finish(ResponseOpcode.INVALID_CREDENTIALS)
        }
        if (account.checkBanned()) {
            pendingLogins.remove(username)
            return output.finish(ResponseOpcode.ACCOUNT_DISABLED)
        }
        if (World.containsPlayer(account.username)) {
            pendingLogins.remove(username)
            return output.finish(ResponseOpcode.ACCOUNT_ONLINE)
        }

        // Load or create player. A decode failure must not fall through to a fresh character -
        // that would overwrite the real save on the next flush.
        val loaded = WorldDB.getPlayers().loadForLogin(account.username)
        if (loaded.loadError()) {
            logError("Failed to decode player save for ${account.username}; refusing login")
            pendingLogins.remove(username)
            return output.finish(ResponseOpcode.COULD_NOT_COMPLETE_LOGIN)
        }
        val player = loaded.player() ?: Player(account)
        pendingLogins.remove(username)

        val session = initSession(output, isaacKeys, ip, codec)

        // Anything that goes wrong while wiring up the player is a login failure, not a silent
        // drop: tell the client so it can retry, and flag the player so the world tick unwinds
        // whatever init() already registered.
        try {
            // init() calls World.addPlayer() which assigns player.index
            player.init(session, account, displayMode, screenWidth, screenHeight, MachineInformation.from(machineInfo))

            // Send WorldLoginDetails before ISAAC is active (noIsaac=true)
            session.send(
                WorldLoginDetails(
                    rights = if (Settings.getConfig().isDebug) 2 else player.rights.crown,
                    modLevel = 0,
                    quickChat = false,
                    verifiedEmail = false,
                    aBool7322 = false,
                    quickChatOnly = false,
                    playerIndex = player.index,
                    members = Settings.getConfig().worldInfo.members(),
                    dob = 0,
                    memberWorld = Settings.getConfig().worldInfo.members(),
                    worldName = Settings.getConfig().serverName
                ),
                noIsaac = true
            )

            session.onDisconnecting {
                player.disconnected = true
            }
            session.onDisconnected {
                AccountLimiter.remove(ip)
            }
            player.start()
            session.flush() // Flush WorldLoginDetails + all init packets before reading client input
            player.setStarted() // Mark started AFTER flush so the world thread cannot interleave writes
        } catch (e: CancellationException) {
            player.disconnected = true
            throw e
        } catch (e: Throwable) {
            player.disconnected = true
            if (Session.isExpectedDisconnect(e)) {
                logTrace("World client disconnected during login for ${account.username}: ${e::class.simpleName}")
                return
            }
            logError("Error completing login for ${account.username}", e)
            return output.finish(ResponseOpcode.COULD_NOT_COMPLETE_LOGIN)
        }
        session.readPackets(input)
    }

    suspend fun forwardToLobby(username: String, packet: ClientProt) {
        if (!socialClient.sendClientPacket(username, packet)) {
            logWarn("Failed to forward ${packet::class.simpleName} to lobby for $username: social gateway unavailable")
            onWorldThread(username) { it.sendMessage("Error forwarding packet to lobby.") }
        }
    }

    /** Runs [block] against the named player on the next world tick, if they are still in game. */
    private fun onWorldThread(username: String, block: (Player) -> Unit) {
        WorldTasks.schedule(0) {
            val player = World.getPlayerByUsername(username) ?: return@schedule
            if (player.hasStarted() && !player.hasFinished()) block(player)
        }
    }

    private fun handleSocialMessage(msg: SocialGatewayWireMessage) {
        when (msg) {
            is ServerPacketEnvelope -> {
                logInfo("handleSocialMessage: ServerPacketEnvelope username=${msg.username} packet=${msg.packet::class.simpleName}")
                deliverToPlayer(msg.username, msg.packet)
            }
            is ServerPacketBatch -> {
                logInfo("handleSocialMessage: ServerPacketBatch usernames=${msg.usernames} packet=${msg.packet::class.simpleName}")
                msg.usernames.forEach { deliverToPlayer(it, msg.packet) }
            }
            // Gateway messages arrive on the social client's coroutine; anything that touches player
            // state (appearance, dialogues, attribs) has to run on the world thread.
            is ClanCreateResponse -> onWorldThread(msg.username) { player ->
                if (msg.success) {
                    player.sendMessage("Your clan has been created!")
                    player.appearance.generateAppearanceData()
                } else {
                    player.sendMessage(msg.message ?: "Unable to create clan.")
                }
                player.tempAttribs.removeB("ccCreateLock")
            }
            is ClanCheckNameResponse -> onWorldThread(msg.username) { player ->
                player.tempAttribs.removeB("ccCreateLock")
                // The lobby has already told the player why the name was rejected, so a rejection
                // just puts them back on the name prompt.
                if (msg.approved) ClansManager.create(player, msg.clanName) else ClansManager.promptName(player)
            }
            is AnnouncementBroadcast -> logInfo("Received announcement: ${msg.message}")
            else -> logInfo("Ignoring gateway message: ${msg::class.simpleName}")
        }
    }

    private fun deliverToPlayer(username: String, packet: ServerProt) {
        val player = World.getPlayerByUsername(username)
        if (player == null) {
            logTrace("deliverToPlayer: player not found username=$username packet=${packet::class.simpleName}")
            return
        }
        if (!player.hasStarted()) {
            logTrace("deliverToPlayer: player not started username=$username packet=${packet::class.simpleName}")
            return
        }
        if (player.hasFinished()) {
            logTrace("deliverToPlayer: player finished username=$username packet=${packet::class.simpleName}")
            return
        }
        player.session.queuePacket(packet)
    }

    fun notifyPlayerOnline(account: Account) {
        // No gateway client at all means this process never started the world socket server
        // (headless tools, the test harness). That is not a lobby rejection, so it must not
        // log anybody out; only an attached client that refuses the player does.
        if (!::socialClient.isInitialized) {
            logWarn("No social gateway client attached; skipping online notify for ${account.username}")
            return
        }
        CoroutineScope(dispatcher).launch {
            val ok = try {
                socialClient.sendPlayerOnline(
                    username = account.username,
                    displayName = account.displayName,
                    rightsCrown = account.rights.crown,
                    privateStatus = account.social.status.toInt()
                )
            } catch (e: Exception) {
                logError("Failed to notify lobby of player online: ${account.username}", e)
                false
            }
            // Legacy Player#run -> LobbyCommunicator.addWorldPlayer(account, response -> {
            //   if (response == null || !response) forceLogout(); })
            // (world-server Player.java:1186-1189). A player the lobby will not register is
            // not logged in as far as social/presence is concerned, so drop them.
            if (!ok) {
                logWarn("Lobby did not accept ${account.username} as online; forcing logout")
                onWorldThread(account.username) { it.forceLogout() }
            }
        }
    }

    fun notifyPlayerOffline(username: String) {
        CoroutineScope(dispatcher).launch {
            try {
                socialClient.sendPlayerOffline(username)
            } catch (e: Exception) {
                logError("Failed to notify lobby of player offline: $username", e)
            }
        }
    }

    fun syncFcSettings(username: String, name: String?, rankToEnter: Int, rankToSpeak: Int, rankToKick: Int, rankToLS: Int) {
        CoroutineScope(dispatcher).launch {
            try {
                socialClient.sendFcSettingsUpdate(username, name, rankToEnter, rankToSpeak, rankToKick, rankToLS)
            } catch (e: Exception) {
                logError("Failed to sync FC settings for $username", e)
            }
        }
    }

    fun forwardFcJoin(username: String, fcName: String?) {
        CoroutineScope(dispatcher).launch {
            try {
                forwardToLobby(username, FcJoin(fcName))
            } catch (e: Exception) {
                logError("Failed to forward FC join for $username", e)
            }
        }
    }

    fun forwardClanCreate(username: String, clanName: String) {
        CoroutineScope(dispatcher).launch {
            try {
                socialClient.sendClanCreate(username, clanName)
            } catch (e: Exception) {
                logError("Failed to forward clan create for $username", e)
                // Otherwise the player is locked out of clan creation until they relog.
                World.getPlayerByUsername(username)?.tempAttribs?.setB("ccCreateLock", false)
            }
        }
    }

    fun forwardClanCheckName(username: String, clanName: String) {
        CoroutineScope(dispatcher).launch {
            try {
                socialClient.sendClanCheckName(username, clanName)
            } catch (e: Exception) {
                logError("Failed to forward clan name check for $username", e)
                // Otherwise the player is locked out of clan creation until they relog.
                World.getPlayerByUsername(username)?.tempAttribs?.setB("ccCreateLock", false)
            }
        }
    }

    fun forwardClanLeave(username: String) {
        CoroutineScope(dispatcher).launch {
            try {
                socialClient.sendClanLeave(username)
            } catch (e: Exception) {
                logError("Failed to forward clan leave for $username", e)
            }
        }
    }

    fun forwardClanAddMember(inviterUsername: String, targetUsername: String) {
        CoroutineScope(dispatcher).launch {
            try {
                socialClient.sendClanAddMember(inviterUsername, targetUsername)
            } catch (e: Exception) {
                logError("Failed to forward clan add member $targetUsername", e)
            }
        }
    }

    fun forwardClanBanMember(username: String, targetUsername: String) {
        CoroutineScope(dispatcher).launch {
            try {
                socialClient.sendClanBanMember(username, targetUsername)
            } catch (e: Exception) {
                logError("Failed to forward clan ban $targetUsername", e)
            }
        }
    }

    fun forwardClanUnbanMember(username: String, targetUsername: String) {
        CoroutineScope(dispatcher).launch {
            try {
                socialClient.sendClanUnbanMember(username, targetUsername)
            } catch (e: Exception) {
                logError("Failed to forward clan unban $targetUsername", e)
            }
        }
    }

    fun forwardClanKickMember(kickerUsername: String, targetUsername: String) {
        CoroutineScope(dispatcher).launch {
            try {
                socialClient.sendClanKickMember(kickerUsername, targetUsername)
            } catch (e: Exception) {
                logError("Failed to forward clan kick $targetUsername", e)
            }
        }
    }

    fun forwardClanGuestJoin(username: String, clanName: String) {
        CoroutineScope(dispatcher).launch {
            try {
                socialClient.sendClanGuestJoin(username, clanName)
            } catch (e: Exception) {
                logError("Failed to forward clan guest join for $username", e)
            }
        }
    }

    fun forwardClanGuestLeave(username: String) {
        CoroutineScope(dispatcher).launch {
            try {
                socialClient.sendClanGuestLeave(username)
            } catch (e: Exception) {
                logError("Failed to forward clan guest leave for $username", e)
            }
        }
    }

    fun saveAccountAsync(account: Account) {
        CoroutineScope(dispatcher).launch {
            try {
                Accounts.save(account)
            } catch (e: Exception) {
                logError("Failed to save account ${account.username}", e)
            }
        }
    }

    fun getClanAsync(name: String, callback: Consumer<Clan?>) {
        CoroutineScope(dispatcher).launch {
            try {
                val clan = Clans.find(name)
                callback.accept(clan)
            } catch (e: Exception) {
                logError("Failed to load clan $name", e)
                callback.accept(null)
            }
        }
    }

    fun saveClanAsync(clan: Clan, callback: Consumer<Clan?>? = null) {
        CoroutineScope(dispatcher).launch {
            try {
                Clans.save(clan)
                callback?.accept(clan)
            } catch (e: Exception) {
                logError("Failed to save clan ${clan.name}", e)
                callback?.accept(null)
            }
        }
    }

    fun getAccountAsync(username: String, callback: Consumer<Account?>) {
        CoroutineScope(dispatcher).launch {
            try {
                callback.accept(Accounts.find(username))
            } catch (e: Exception) {
                logError("Failed to load account $username", e)
                callback.accept(null)
            }
        }
    }

    private fun initSession(write: ByteWriteChannel, isaacKeys: IntArray, hostname: String, codec: Codec): Session {
        val inCipher = Isaac(isaacKeys)
        for (i in isaacKeys.indices)
            isaacKeys[i] += 50
        val outCipher = Isaac(isaacKeys)
        return Session(write, inCipher, outCipher, hostname, codec)
    }
}
