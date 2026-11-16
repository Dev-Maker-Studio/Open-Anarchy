package org.darkan.world.server

import com.rs.Settings
import com.rs.db.WorldDB
import com.rs.game.World
import com.rs.game.map.ChunkManager
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
import org.darkan.core.EnvVars
import org.darkan.core.Logger
import org.darkan.core.Logger.logError
import org.darkan.core.Logger.logInfo
import org.darkan.core.Logger.logTrace
import org.darkan.core.Logger.logWarn
import org.darkan.core.model.AccountView
import org.darkan.core.model.MachineInformation
import org.darkan.core.model.Rights
import org.darkan.core.net.*
import org.darkan.core.net.prot.Codec
import org.darkan.core.net.login.HeadlessClient
import org.darkan.core.net.login.LoginDecode
import org.darkan.core.net.login.LoginRsaKey
import org.darkan.core.net.login.LoginToken
import org.darkan.core.net.login.WorldLoginRequest
import org.darkan.core.net.prot.packet.WorldLoginDetails
import org.darkan.core.net.web.DiscordWebhook
import org.darkan.core.social.SocialAction
import org.darkan.core.social.SocialEvent
import org.darkan.core.social.SocialPackets
import org.darkan.core.social.gateway.AccountAction
import org.darkan.core.social.gateway.AccountCommand
import org.darkan.core.social.gateway.AccountUpdate
import org.darkan.core.social.gateway.AnnouncementBroadcast
import org.darkan.core.social.gateway.GatewayPlayerPresence
import org.darkan.core.social.gateway.LoginRefusal
import org.darkan.core.social.gateway.SocialCommand
import org.darkan.core.social.gateway.SocialGatewayWireMessage
import org.darkan.core.social.gateway.SocialNotification
import org.darkan.world.clan.ClanRegistry
import org.darkan.world.social.SocialClient
import org.darkan.world.social.SocialSync
import com.rs.game.content.HelpChat
import world.gregs.voidps.buffer.*
import world.gregs.voidps.cache.Cache
import org.darkan.core.net.js5.DiscoveredMapFileProvider
import world.gregs.voidps.cache.file.FileProvider
import world.gregs.voidps.cache.file.prefetchKeys

object WorldServer {
    const val FRIEND_SERVER_UNAVAILABLE = "Unable to reach the friend server."

    private lateinit var job: Job
    private lateinit var dispatcher: ExecutorCoroutineDispatcher

    private lateinit var serverSocket: ServerSocket
    private lateinit var selectorManager: SelectorManager

    lateinit var socialClient: SocialClient
        private set

    private val loginKey = LoginRsaKey(BigInteger(EnvVars.worldRsaModulus), BigInteger(EnvVars.worldRsaExponent))

    private val pendingLogins = ConcurrentHashMap.newKeySet<String>()
    private val botIpWhitelist = IpAllowList(EnvVars.botIpWhitelist)

    private const val MAX_LOGINS_IN_FLIGHT = 20
    private const val INDEX_CRCS_CHECKED = 32

    private lateinit var js5: JS5Server

    fun start(port: Int): Job {
        val cache = Cache.get()
        js5 = JS5Server(FileProvider.load(cache).let { if (EnvVars.gateMaps) DiscoveredMapFileProvider(it, cache) else it }, prefetchKeys(cache))
        val executor = Executors.newCachedThreadPool()
        dispatcher = executor.asCoroutineDispatcher()
        selectorManager = ActorSelectorManager(dispatcher)
        val scope = CoroutineScope(dispatcher)

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
            val ip = ProxyProtocol.clientIp(socket.remoteAddress.ipString(), input) ?: return@launch

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
            return output.finish(ResponseOpcode.COULD_NOT_COMPLETE_LOGIN)
        if (pendingLogins.size > MAX_LOGINS_IN_FLIGHT)
            return output.finish(ResponseOpcode.NO_REPLY_FROM_LOGIN_SERVER)

        val update = World.ticksTillUpdate
        if (update != -1L && update < 20)
            return output.finish(ResponseOpcode.SERVER_BEING_UPDATED)

        val size = input.readShort().toInt()
        val packet = input.readPacket(size)

        val major = packet.readInt()
        val codec = Codec.get(major) ?: return output.finish(ResponseOpcode.GAME_UPDATE).also { logInfo("Invalid codec: $major") }
        val minor = packet.readInt()

        val request = when (val decoded = WorldLoginRequest.decode(major, minor, opcode == RequestOpcode.RECONNECT, packet, loginKey)) {
            is LoginDecode.Rejected -> return output.finish(decoded.response)
            is LoginDecode.Decoded -> decoded.request
        }
        val isaacKeys = request.isaacSeed
        val lobbyAuthToken = request.authToken
        if (!LoginToken.hasTokenShape(lobbyAuthToken)) {
            logWarn("World login from $ip presented a malformed auth token; refusing")
            return output.finish(ResponseOpcode.INVALID_CREDENTIALS)
        }
        val username = request.username
        val displayMode = request.displayMode
        val screenWidth = request.screenWidth
        val screenHeight = request.screenHeight
        val machineInfo = request.machine
        if (machineInfo.version != WorldLoginRequest.MACHINE_INFO_VERSION)
            logError("Unexpected machine info version for $username (version=${machineInfo.version})")
        if (request.worldToken != EnvVars.worldToken)
            logError("World token mismatch: expected=${EnvVars.worldToken}, got=${request.worldToken} from $username")

        val crcs = Cache.get().indexCrcs()
        for (index in 0 until minOf(Cache.get().indexCount(), INDEX_CRCS_CHECKED)) {
            if (crcs[index] != request.indexCrcs[index]) {
                logError("CRC mismatch at index $index: expected=${crcs[index]}, got=${request.indexCrcs[index]} from $username")
                return output.finish(ResponseOpcode.GAME_UPDATE)
            }
        }

        if (World.players.size() >= Settings.PLAYERS_LIMIT - 10)
            return output.finish(ResponseOpcode.WORLD_FULL)
        if (World.containsPlayer(username))
            return output.finish(ResponseOpcode.ACCOUNT_ONLINE)
        if (!pendingLogins.add(username))
            return output.finish(ResponseOpcode.LOGIN_LIMIT_EXCEEDED)

        val claimed = hashSetOf(username)
        val admitted = try {
            when (val admission = admitThroughLobby(username, lobbyAuthToken) { claimed.add(it) && pendingLogins.add(it) }) {
                is Admission.Admitted -> admission
                is Admission.Refused -> return output.finish(admission.response)
            }
        } finally {
            pendingLogins.removeAll(claimed)
        }
        val account = admitted.account
        val player = admitted.player
        clientRefusal(account, machineInfo, ip)?.let { return output.finish(it) }

        val session = initSession(output, isaacKeys, ip, codec)

        try {
            player.init(session, account, displayMode, screenWidth, screenHeight, machineInfo)
            if (EnvVars.worldOwner.isNotEmpty() && player.username.equals(EnvVars.worldOwner, ignoreCase = true))
                player.rights = Rights.OWNER

            session.send(
                WorldLoginDetails(
                    rights = 2, // the client reads rights 2 to send the ctrl+shift click teleport; what a player may run is decided by Commands
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
            session.flush()
            player.setStarted()
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

    private fun clientRefusal(account: AccountView, machine: MachineInformation, ip: String): Int? {
        val headless = HeadlessClient.isHeadless(machine)
        if (account.bot) {
            if (headless && botIpWhitelist.contains(ip)) return null
            logWarn("Refused bot ${account.username} from $ip (headless=$headless)")
            return ResponseOpcode.INVALID_CREDENTIALS
        }
        if (headless && !EnvVars.worldBotsAllowed) {
            if (!" ${machine.cpuData} ".contains(" override ")) {
                logInfo("Refused headless client login for ${account.username} from $ip (${machine.cpuData}): bots are not allowed on this world")
                return ResponseOpcode.INVALID_LOGIN_SERVER
            }
            logWarn("Headless override login for ${account.username} from $ip on bot-prohibited world ${EnvVars.worldId}: ${machine.cpuData}")
            DiscordWebhook.sendStaffMessage("Real player **${account.username}** logged in headlessly to bot-prohibited world ${EnvVars.worldId} from $ip (`${machine.cpuData}`)")
        } else if (headless) logInfo("Headless client login for ${account.username} from $ip: ${machine.cpuData}")
        if (AccountLimiter.getSessionsIP(ip) >= 3)
            return ResponseOpcode.LOGIN_LIMIT_EXCEEDED
        return null
    }

    private sealed interface Admission {
        data class Admitted(val account: AccountView, val player: Player) : Admission
        data class Refused(val response: Int) : Admission
    }

    private fun responseFor(refusal: LoginRefusal?) = when (refusal) {
        LoginRefusal.BANNED -> ResponseOpcode.ACCOUNT_DISABLED
        LoginRefusal.NAME_REQUIRED -> ResponseOpcode.DISPLAY_NAME_CHANGE_REQUIRED
        LoginRefusal.NO_ACCOUNT -> ResponseOpcode.COULD_NOT_COMPLETE_LOGIN
        LoginRefusal.TOKEN, null -> ResponseOpcode.INVALID_CREDENTIALS
    }

    private suspend fun admitThroughLobby(typedName: String, lobbyAuthToken: String, claim: (String) -> Boolean): Admission {
        val verdict = socialClient.verifyLoginToken(typedName, lobbyAuthToken)
            ?: return Admission.Refused(ResponseOpcode.LOGIN_SERVER_OFFLINE)
        val account = verdict.account ?: return Admission.Refused(responseFor(verdict.refusal))
        if (account.checkBanned())
            return Admission.Refused(ResponseOpcode.ACCOUNT_DISABLED)
        if (account.username != typedName && !claim(account.username))
            return Admission.Refused(ResponseOpcode.ACCOUNT_ONLINE)
        if (World.containsPlayer(account.username))
            return Admission.Refused(ResponseOpcode.ACCOUNT_ONLINE)

        val loaded = WorldDB.getPlayers().loadForLogin(account.username)
        if (loaded.loadError()) {
            logError("Failed to decode player save for ${account.username}; refusing login")
            return Admission.Refused(ResponseOpcode.COULD_NOT_COMPLETE_LOGIN)
        }
        return Admission.Admitted(account, loaded.player() ?: Player(account))
    }

    private fun abortLogin(player: Player, ip: String) {
        player.disconnected = true
        if (player.hasStarted() || !World.containsPlayer(player.username)) return
        player.setFinished(true)
        ChunkManager.updateChunks(player)
        World.removePlayer(player)
        if (!player.isBot) AccountLimiter.remove(ip)
    }

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

    fun onWorldThread(username: String, block: (Player) -> Unit) {
        WorldTasks.schedule(0) {
            val player = World.getPlayerByUsername(username) ?: return@schedule
            if (player.hasStarted() && !player.hasFinished()) block(player)
        }
    }

    private fun handleSocialMessage(msg: SocialGatewayWireMessage) {
        if (ClanRegistry.handle(msg)) return
        when (msg) {
            is SocialNotification -> msg.usernames.forEach { deliverEvent(it, msg.event) }
            is AnnouncementBroadcast -> announceToEveryone(msg)
            is AccountUpdate -> onWorldThread(msg.account.username) { it.account = msg.account }
            else -> logInfo("Ignoring gateway message: ${msg::class.simpleName}")
        }
    }

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
            HelpChat.sync(player, event)
            for (packet in SocialPackets.render(username, event)) player.session.queuePacket(packet)
        }
    }

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
