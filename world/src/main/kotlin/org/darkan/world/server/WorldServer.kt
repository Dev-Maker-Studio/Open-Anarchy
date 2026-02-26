package org.darkan.world.server

import com.rs.Settings
import com.rs.db.WorldDB
import com.rs.game.World
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
import org.darkan.core.formatPlayerNameForProtocol
import org.darkan.core.model.MachineInformation as CoreMachineInformation
import org.darkan.core.net.*
import org.darkan.core.net.prot.Codec
import org.darkan.core.net.prot.WorldLoginDetails
import world.gregs.voidps.buffer.*
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.secure.RSA
import world.gregs.voidps.cache.secure.decryptXtea
import com.rs.net.LobbyCommunicator
import org.darkan.core.model.Account
import org.darkan.core.net.prot.ClientProt
import org.darkan.core.social.gateway.SocialGatewayWireMessage
import org.darkan.world.social.SocialClient
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

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

            try {
                when (val reqOpcode = input.readByte().toInt()) {
                    RequestOpcode.CONNECT_LOGIN -> initWorldLogin(input, output, ip)
                    else -> {
                        logTrace("Invalid request opcode for world: $reqOpcode")
                        output.finish(ResponseOpcode.INVALID_LOGIN_SERVER)
                    }
                }
            } finally {
                socket.close()
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
        val codec = Codec.get(major) ?: return output.finish(ResponseOpcode.GAME_UPDATE)
        if (packet.readInt() != 1) // CUSTOM_CLIENT_BUILD
            return output.finish(ResponseOpcode.GAME_UPDATE)

        val gameStateIsUnk10 = packet.readUByte().toInt() == 1

        val rsaSize = packet.readUShort().toInt()
        val sensitiveData = ByteReadPacket(RSA.crypt(packet.readByteArray(rsaSize), worldRsaMod, worldRsaExp))
        if (sensitiveData.readUByte().toInt() != 10)
            return output.finish(ResponseOpcode.BAD_SESSION_ID)

        val isaacKeys = IntArray(4) { sensitiveData.readInt() }
        if (sensitiveData.readLong().toInt() != 0)
            return output.finish(ResponseOpcode.BAD_SESSION_ID)

        val password = sensitiveData.readRSString()
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

        val success = xtea.readUByte().toInt()
        if (success != 6)
            logError("Failed to parse machine info for $username (success=$success)")
        val machineInfo = CoreMachineInformation.parse(xtea)

        xtea.readInt()
        xtea.readLong()
        xtea.readRSString()
        if (xtea.readUByte().toInt() == 1)
            xtea.readRSString()
        xtea.readUByte()
        xtea.readUByte()
        xtea.readUByte()
        xtea.readByte()
        xtea.readInt()

        val token = xtea.readRSString()
        if (token != EnvVars.worldToken) {
            logError("World token mismatch: expected=${EnvVars.worldToken}, got=$token from $username")
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

        val account = try {
            LobbyCommunicator.authWorldLogin(username, password)
        } catch (e: Exception) {
            logError("Error connecting to login server for $username", e)
            pendingLogins.remove(username)
            return output.finish(ResponseOpcode.LOGIN_SERVER_OFFLINE)
        }

        if (account == null || account.username.isNullOrEmpty()) {
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

        // Load or create player
        val player = loadOrCreatePlayer(account)
        pendingLogins.remove(username)

        val session = initSession(output, isaacKeys, ip, codec)

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
        session.readPackets(input)
    }

    suspend fun forwardToLobby(username: String, packet: ClientProt) {
        socialClient.sendClientPacket(username, packet)
    }

    private suspend fun handleSocialMessage(msg: SocialGatewayWireMessage) {
        // TODO: Handle lobby→world messages (friend updates, private messages, etc.)
    }

    private fun loadOrCreatePlayer(account: Account): Player {
        return WorldDB.getPlayers().getSyncUsername(account.username) ?: Player(account)
    }

    private fun initSession(write: ByteWriteChannel, isaacKeys: IntArray, hostname: String, codec: Codec): Session {
        val inCipher = Isaac(isaacKeys)
        for (i in isaacKeys.indices)
            isaacKeys[i] += 50
        val outCipher = Isaac(isaacKeys)
        return Session(write, inCipher, outCipher, hostname, codec)
    }
}
