package org.darkan.world.server

import com.rs.Settings
import com.rs.db.WorldDB
import com.rs.game.World
import com.rs.game.model.entity.player.Player
import com.rs.game.tasks.WorldTasks
import com.rs.utils.AccountLimiter
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.util.network.*
import io.ktor.utils.io.*
import io.ktor.utils.io.CancellationException
import io.ktor.utils.io.core.*
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.function.Consumer
import kotlin.use
import kotlinx.coroutines.*
import kotlinx.io.readByteArray
import kotlinx.io.readUByte
import kotlinx.io.readUShort
import org.darkan.core.EnvVars
import org.darkan.core.Logger
import org.darkan.core.Logger.logError
import org.darkan.core.Logger.logInfo
import org.darkan.core.Logger.logTrace
import org.darkan.core.Logger.logWarn
import org.darkan.core.formatPlayerNameForProtocol
import org.darkan.core.model.AccountView
import org.darkan.core.model.MachineInformation
import org.darkan.core.net.*
import org.darkan.core.net.prot.Codec
import org.darkan.core.net.prot.packet.WorldLoginDetails
import org.darkan.core.social.SocialAction
import org.darkan.core.social.SocialEvent
import org.darkan.core.social.SocialPackets
import org.darkan.core.social.gateway.AccountAction
import org.darkan.core.social.gateway.AccountCommand
import org.darkan.core.social.gateway.AccountUpdate
import org.darkan.core.social.gateway.AnnouncementBroadcast
import org.darkan.core.social.gateway.GatewayPlayerPresence
import org.darkan.core.social.gateway.SocialCommand
import org.darkan.core.social.gateway.SocialGatewayWireMessage
import org.darkan.core.social.gateway.SocialNotification
import org.darkan.world.clan.ClanRegistry
import org.darkan.world.social.SocialClient
import org.darkan.world.social.SocialSync
import world.gregs.voidps.buffer.*
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.file.FileProvider
import world.gregs.voidps.cache.file.prefetchKeys
import world.gregs.voidps.cache.secure.RSA
import world.gregs.voidps.cache.secure.decryptXtea

object WorldServer {
    const val FRIEND_SERVER_UNAVAILABLE = "Unable to reach the friend server."

    private lateinit var job: Job
    private lateinit var dispatcher: ExecutorCoroutineDispatcher

    private lateinit var serverSocket: ServerSocket
    private lateinit var selectorManager: SelectorManager

    lateinit var socialClient: SocialClient
        private set

    private val worldRsaMod = BigInteger(EnvVars.worldRsaModulus)
    private val worldRsaExp = BigInteger(EnvVars.worldRsaExponent)

    private val pendingLogins = ConcurrentHashMap.newKeySet<String>()

    private lateinit var js5: JS5Server

    fun start(port: Int): Job {
        val cache = Cache.get()
        js5 = JS5Server(FileProvider.load(cache), prefetchKeys(cache))
        val executor = Executors.newCachedThreadPool()
        dispatcher = executor.asCoroutineDispatcher()
        selectorManager = ActorSelectorManager(dispatcher)
        val scope = CoroutineScope(dispatcher)

        // The clan replica is only valid while the socket that filled it is up: every connection
        // change drops it, and the lobby's connect-time resync refills it (docs/clans.md).
        socialClient = SocialClient(scope, ::onlinePresence, { ClanRegistry.clear() }) { msg -> handleSocialMessage(msg) }
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
                    RequestOpcode.JS5_INIT -> js5.init(input, output, ip)
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
        val machineInfo = MachineInformation.parse(xtea)
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
        // Everything the world can refuse on its own is checked before the token is presented:
        // the lobby spends a token the moment it verifies one, so a refusal afterwards would
        // burn it and report the next attempt as a credentials failure.
        if (World.containsPlayer(username))
            return output.finish(ResponseOpcode.ACCOUNT_ONLINE)
        if (!pendingLogins.add(username))
            return output.finish(ResponseOpcode.LOGIN_LIMIT_EXCEEDED)

        val admitted = try {
            when (val admission = admitThroughLobby(username, lobbyAuthToken)) {
                is Admission.Admitted -> admission
                is Admission.Refused -> return output.finish(admission.response)
            }
        } finally {
            pendingLogins.remove(username)
        }
        val account = admitted.account
        val player = admitted.player

        val session = initSession(output, isaacKeys, ip, codec)

        // Anything that goes wrong while wiring up the player is a login failure, not a silent
        // drop: tell the client so it can retry, and flag the player so the world tick unwinds
        // whatever init() already registered.
        try {
            // init() calls World.addPlayer() which assigns player.index
            player.init(session, account, displayMode, screenWidth, screenHeight, machineInfo)

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
            player.start()
            session.flush() // Flush WorldLoginDetails + all init packets before reading client input
            player.setStarted() // Mark started AFTER flush so the world thread cannot interleave writes
        } catch (e: CancellationException) {
            abortLogin(player, ip)
            throw e
        } catch (e: Throwable) {
            abortLogin(player, ip)
            if (Session.isExpectedDisconnect(e)) {
                logTrace("World client disconnected during login for ${account.username}: ${e::class.simpleName}")
                return
            }
            logError("Error completing login for ${account.username}", e)
            return output.finish(ResponseOpcode.COULD_NOT_COMPLETE_LOGIN)
        }
        session.readPackets(input)
    }

    private sealed interface Admission {
        data class Admitted(val account: AccountView, val player: Player) : Admission
        data class Refused(val response: Int) : Admission
    }

    private suspend fun admitThroughLobby(username: String, lobbyAuthToken: String): Admission {
        val verdict = socialClient.verifyLoginToken(username, lobbyAuthToken)
            ?: return Admission.Refused(ResponseOpcode.LOGIN_SERVER_OFFLINE)
        val account = verdict.account ?: return Admission.Refused(ResponseOpcode.INVALID_CREDENTIALS)
        if (account.checkBanned())
            return Admission.Refused(ResponseOpcode.ACCOUNT_DISABLED)
        if (World.containsPlayer(account.username))
            return Admission.Refused(ResponseOpcode.ACCOUNT_ONLINE)

        // A decode failure must not fall through to a fresh character - that would overwrite the
        // real save on the next flush.
        val loaded = WorldDB.getPlayers().loadForLogin(account.username)
        if (loaded.loadError()) {
            logError("Failed to decode player save for ${account.username}; refusing login")
            return Admission.Refused(ResponseOpcode.COULD_NOT_COMPLETE_LOGIN)
        }
        return Admission.Admitted(account, loaded.player() ?: Player(account))
    }

    /**
     * init() has already registered the player with [World]; a login that dies after that point
     * never starts, so the world tick will never process it and never finish it.
     */
    private fun abortLogin(player: Player, ip: String) {
        player.disconnected = true
        if (player.hasStarted() || !World.containsPlayer(player.username)) return
        World.removePlayer(player)
        AccountLimiter.remove(ip)
    }

    /** False when no gateway is attached (headless tools, the test harness) or it is down. */
    fun sendToGateway(msg: SocialGatewayWireMessage): Boolean {
        if (!::socialClient.isInitialized) {
            logWarn("No social gateway client attached; dropping ${msg::class.simpleName}")
            return false
        }
        return socialClient.send(msg)
    }

    @JvmStatic
    fun act(username: String, action: SocialAction) {
        if (!sendToGateway(SocialCommand(username, action))) {
            logWarn("Could not send ${action::class.simpleName} for $username: social gateway unavailable")
            onWorldThread(username) { it.sendMessage(FRIEND_SERVER_UNAVAILABLE) }
        }
    }

    /** Runs [block] against the named player on the next world tick, if they are still in game. */
    fun onWorldThread(username: String, block: (Player) -> Unit) {
        WorldTasks.schedule(0) {
            val player = World.getPlayerByUsername(username) ?: return@schedule
            if (player.hasStarted() && !player.hasFinished()) block(player)
        }
    }

    private fun handleSocialMessage(msg: SocialGatewayWireMessage) {
        // Clans are their own protocol (docs/clans.md): the registry owns every clan event and
        // builds the client packets itself, so nothing clan-shaped reaches the dispatch below.
        if (ClanRegistry.handle(msg)) return
        when (msg) {
            is SocialNotification -> msg.usernames.forEach { deliverEvent(it, msg.event) }
            is AnnouncementBroadcast -> announceToEveryone(msg)
            is AccountUpdate -> onWorldThread(msg.account.username) { it.account = msg.account }
            else -> logInfo("Ignoring gateway message: ${msg::class.simpleName}")
        }
    }

    /**
     * An announcement the lobby fanned out to the worlds. It is a game message to everybody in
     * game here; the lobby has already decided who should get it, so the world does not filter.
     */
    private fun announceToEveryone(msg: AnnouncementBroadcast) {
        logInfo("Received announcement: ${msg.message}")
        WorldTasks.schedule(0) {
            for (player in World.players) {
                if (player == null || !player.hasStarted() || player.hasFinished()) continue
                player.sendMessage(msg.message)
            }
        }
    }

    private fun deliverEvent(username: String, event: SocialEvent) {
        onWorldThread(username) { player ->
            SocialSync.apply(player, event)
            for (packet in SocialPackets.render(username, event)) player.session.queuePacket(packet)
        }
    }

    /**
     * Everyone this world currently has in game, for the presence snapshots the lobby reconciles
     * against. The gate is the same one [onWorldThread] uses - a player still logging in or
     * already finishing is not somebody the lobby can route events to.
     */
    private fun onlinePresence(): List<GatewayPlayerPresence> =
        World.players.mapNotNull { player ->
            if (player == null || !player.hasStarted() || player.hasFinished()) return@mapNotNull null
            val account = player.account
            GatewayPlayerPresence(
                username = account.username,
                displayName = account.displayName,
                rightsCrown = account.rights.crown,
                privateStatus = account.social.status.toInt(),
            )
        }

    /**
     * Called once the player can receive packets. A world with no gateway client at all (headless
     * tools, the test harness) is not a lobby rejection, so nobody is logged out for it; a
     * gateway that will not take the player is, because they are not online as far as anybody's
     * friends list is concerned.
     */
    fun notifyPlayerOnline(account: AccountView) {
        if (!::socialClient.isInitialized) {
            logWarn("No social gateway client attached; skipping online notify for ${account.username}")
            return
        }
        val ok = socialClient.sendPlayerOnline(
            username = account.username,
            displayName = account.displayName,
            rightsCrown = account.rights.crown,
            privateStatus = account.social.status.toInt(),
        )
        if (!ok) {
            logWarn("Lobby did not accept ${account.username} as online; forcing logout")
            onWorldThread(account.username) { it.forceLogout() }
        }
    }

    fun notifyPlayerOffline(username: String) {
        if (!::socialClient.isInitialized) return
        socialClient.sendPlayerOffline(username)
    }

    fun accountAction(actor: Player, action: AccountAction) {
        sendToGateway(AccountCommand(actor.username, action))
    }

    fun displayNameAsync(username: String, callback: Consumer<String?>) {
        CoroutineScope(dispatcher).launch {
            try {
                callback.accept(socialClient.lookupDisplayName(username))
            } catch (e: Exception) {
                logError("Failed to look up the display name of $username", e)
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
