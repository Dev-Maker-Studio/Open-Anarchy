package org.darkan.world.server

import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.util.network.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import kotlinx.io.readByteArray
import kotlinx.io.readUByte
import org.darkan.core.EnvVars
import org.darkan.core.Logger
import org.darkan.core.Logger.logError
import org.darkan.core.Logger.logInfo
import org.darkan.core.Logger.logTrace
import org.darkan.core.model.ChatMessageType
import org.darkan.core.formatPlayerNameForProtocol
import org.darkan.core.model.MachineInformation
import org.darkan.core.mongo.collections.Accounts
import org.darkan.core.net.*
import org.darkan.core.net.login.LoginToken
import org.darkan.core.net.prot.Codec
import org.darkan.core.net.prot.GameMessage
import org.darkan.core.net.prot.WorldLoginDetails
import org.darkan.core.social.gateway.AnnouncementBroadcast
import org.darkan.core.social.gateway.AnnouncementChannel
import org.darkan.core.social.gateway.AnnouncementTarget
import org.darkan.core.social.gateway.ServerPacketBatch
import org.darkan.core.social.gateway.ServerPacketEnvelope
import org.darkan.core.social.gateway.SocialGatewayWireMessage
import world.gregs.voidps.buffer.*
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.secure.RSA
import world.gregs.voidps.cache.secure.decryptXtea
import java.math.BigInteger
import java.io.EOFException
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.milliseconds
import org.darkan.world.model.WorldPlayer
import org.darkan.world.model.WorldPlayers
import org.darkan.world.social.SocialClient

class WorldServer {
    private lateinit var job: Job
    private lateinit var dispatcher: ExecutorCoroutineDispatcher
    private lateinit var selectorManager: ActorSelectorManager
    private val limiter = MultilogLimiter()
    private lateinit var socialClient: SocialClient

    suspend fun start(): Job {
        val executor = Executors.newCachedThreadPool()
        dispatcher = executor.asCoroutineDispatcher()
        selectorManager = ActorSelectorManager(dispatcher)
        val scope = CoroutineScope(dispatcher + SupervisorJob())

        socialClient = SocialClient(scope) { msg -> handleGatewayMessage(msg) }
        socialClient.start()

        val serverSocket = aSocket(selectorManager).tcp().bind("0.0.0.0", EnvVars.worldPort) { reuseAddress = true }
        logInfo("World server started on port ${EnvVars.worldPort}")

        job = scope.launch {
            try {
                supervisorScope {
                    while (isActive) {
                        val socket = serverSocket.accept()
                        connectClient(scope, socket)
                    }
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                logInfo("World server stopping...")
            } catch (e: Exception) {
                logError("Error in World server", e)
            } finally {
                serverSocket.close()
            }
        }
        return job
    }

    fun stop() {
        try {
            job.cancel()
            if (::selectorManager.isInitialized) selectorManager.close()
            if (::dispatcher.isInitialized) dispatcher.close()
        } catch (e: Exception) {
            logError("Error stopping World server", e)
            throw e
        }
    }

    private fun connectClient(scope: CoroutineScope, socket: Socket) = scope.launch(
        dispatcher + CoroutineExceptionHandler { _, throwable ->
            logTrace("Error connecting world client: ${throwable.message}")
        }
    ) {
        try {
            val input = socket.openReadChannel()
            val output = socket.openWriteChannel(autoFlush = false)
            val ip = socket.remoteAddress.ipString()

            if (!limiter.add(ip)) {
                logInfo("MultilogLimiter reject ip=$ip count=${limiter.getCount(ip)} limit=${EnvVars.multiLogLimit}")
                output.finish(ResponseOpcode.LOGIN_LIMIT_EXCEEDED)
                return@launch
            }

            try {
                when (val reqOpcode = input.readByte().toInt()) {
                    RequestOpcode.CONNECT_LOGIN -> init(input, output, ip)
                    else -> {
                        logTrace("Invalid request opcode: $reqOpcode")
                        output.finish(ResponseOpcode.INVALID_LOGIN_SERVER)
                    }
                }
            } finally {
                limiter.remove(ip)
            }
        } catch (e: Exception) {
            if (e is EOFException || e is kotlinx.coroutines.CancellationException) {
                logTrace("World client disconnected: ${e.message}")
            } else {
                logError("Error handling world client connection", e)
            }
        } finally {
            socket.close()
        }
    }

    private val worldRsaMod = BigInteger(EnvVars.worldRsaModulus)
    private val worldRsaExp = BigInteger(EnvVars.worldRsaExponent)

    private suspend fun init(input: ByteReadChannel, output: ByteWriteChannel, ip: String) {
        output.respond(ResponseOpcode.JS5_SYNC)

        val opcode = input.readByte().toInt()
        val reconnecting = opcode == RequestOpcode.RECONNECT
        if (opcode != RequestOpcode.LOGIN && opcode != RequestOpcode.RECONNECT)
            return output.finish(ResponseOpcode.LOGIN_SERVER_REJECTED_SESSION)

        val size = input.readShort().toInt()
        val packet = input.readPacket(size)

        val major = packet.readInt()
        val minor = packet.readInt()
        val gameStateFlag = packet.readUByte()
        val rsaSize = packet.readUShort()

        val codec = Codec.get(major) ?: return output.finish(ResponseOpcode.GAME_UPDATE)
        if (major != EnvVars.majorVersion || minor != EnvVars.minorVersion) return output.finish(ResponseOpcode.GAME_UPDATE)
        if (rsaSize > packet.remaining.toInt()) return output.finish(ResponseOpcode.BAD_SESSION_ID)

        val rsaBlock = packet.readByteArray(rsaSize)
        val rsaBytes = RSA.crypt(rsaBlock, worldRsaMod, worldRsaExp)
        val sensitiveData = ByteReadPacket(rsaBytes)
        val rsaHeader = sensitiveData.readUByte().toInt()
        if (rsaHeader != 10) return output.finish(ResponseOpcode.BAD_SESSION_ID)
        val isaacKeys = IntArray(4) { sensitiveData.readInt() }
        if (sensitiveData.readLong().toInt() != 0) return output.finish(ResponseOpcode.BAD_SESSION_ID)
        val lobbyAuthToken = sensitiveData.readRSString()
        val unk1 = sensitiveData.readLong()
        val unk2 = sensitiveData.readLong()

        val verifiedToken = LoginToken.verify(lobbyAuthToken, System.currentTimeMillis(), EnvVars.worldLoginTokenSecret)
            ?: return output.finish(ResponseOpcode.INVALID_CREDENTIALS)
        val xtea = packet.decryptXtea(isaacKeys)
        val usernameIsString = xtea.readUByte().toInt()
        val usernameFromClient = if (usernameIsString == 1) xtea.readRSString() else xtea.readLong().toRSString()

        xtea.readUByte() // windowMode
        xtea.readUShort() // screenWidth
        xtea.readUShort() // screenHeight
        xtea.readUByte() // antiAliasingPreview / unknown
        xtea.readByteArray(24) // random.dat
        xtea.readRSString() // affiliate string
        xtea.readInt() // affiliate

        val prefSize = xtea.readUByte().toInt()
        xtea.readByteArray(prefSize)

        MachineInformation.parse(xtea)

        xtea.readInt() // varVerifyId
        xtea.readLong() // aLong7153
        xtea.readRSString() // aString5555
        if (xtea.readBoolean()) xtea.readRSString()
        xtea.readUByte() // jagtheora loaded
        xtea.readUByte() // isApplet
        xtea.readUByte() // aBool7160
        xtea.readByte() // anInt2831
        xtea.readInt() // anInt7163
        xtea.readRSString() // aString7164
        xtea.readUByte() // same world flag
        xtea.readInt() // world id

        // CRCs (match lobby and legacy)
        for (index in 0..<Cache.get().indexCount()) {
            if (index > 35) break
            val crc = Cache.get().indexCrcs()[index]
            val receivedCrc = xtea.readInt()
            if (crc != receivedCrc && index < 32) {
                logError("CRC mismatch: expected=$crc got=$receivedCrc from ${verifiedToken.username}")
                return output.finish(ResponseOpcode.GAME_UPDATE)
            }
        }

        val account = Accounts.find(verifiedToken.username) ?: return output.finish(ResponseOpcode.INVALID_CREDENTIALS)

        val session = initSession(output, isaacKeys, ip, codec)
        session.send(
            WorldLoginDetails(
                rights = account.rights.crown,
                modLevel = 0,
                quickChat = false,
                verifiedEmail = true,
                aBool7322 = false,
                quickChatOnly = false,
                playerIndex = 1, // TODO
                members = EnvVars.members,
                dob = 0,
                memberWorld = EnvVars.members,
                worldName = EnvVars.serverName,
            ),
            noIsaac = true,
        )
        session.flush()

        logInfo("World login ok user=${verifiedToken.username} reconnecting=$reconnecting clientUser=$usernameFromClient")

        val player = WorldPlayer(session, account, socialClient)
        WorldPlayers.put(player)

        session.onDisconnected {
            WorldPlayers.remove(account.username)
        }

        try {
            socialClient.sendPlayerOnline(
                username = account.username,
                displayName = account.displayName,
                rightsCrown = account.rights.crown,
                privateStatus = account.social.status.toInt(),
            )

            coroutineScope {
                val reader = launch { session.readPackets(input) }
                while (!session.disconnected) {
                    player.handleDecodedPackets()
                    session.flush()
                    delay(10.milliseconds)
                }
                reader.cancelAndJoin()
            }
        } catch (e: Exception) {
            if (e !is kotlinx.coroutines.CancellationException && e !is EOFException) {
                Logger.logError("World player loop error user=${account.username}", e)
            }
        } finally {
            WorldPlayers.remove(account.username)
            socialClient.sendPlayerOffline(account.username)
            session.exit()
            session.disconnect()
        }
    }

    private fun initSession(write: ByteWriteChannel, isaacKeys: IntArray, hostname: String, codec: Codec): Session {
        val inCipher = Isaac(isaacKeys)
        for (i in isaacKeys.indices) isaacKeys[i] += 50
        val outCipher = Isaac(isaacKeys)
        return Session(write, inCipher, outCipher, hostname, codec)
    }

    private suspend fun handleGatewayMessage(msg: SocialGatewayWireMessage) {
        when (msg) {
            is ServerPacketEnvelope -> {
                val player = WorldPlayers.get(msg.username) ?: return
                player.session.send(msg.packet)
            }

            is ServerPacketBatch -> {
                for (username in msg.usernames) {
                    val player = WorldPlayers.get(username) ?: continue
                    player.session.send(msg.packet)
                }
            }

            is AnnouncementBroadcast -> {
                val type = when (msg.channel) {
                    AnnouncementChannel.GAME -> ChatMessageType.UNFILTERABLE
                    AnnouncementChannel.FRIENDS_CHAT -> ChatMessageType.FC_NOTIFICATION
                }
                when (val target = msg.target) {
                    is AnnouncementTarget.AllWorlds -> {
                        for (player in WorldPlayers.values()) {
                            player.session.send(GameMessage(type, msg.message))
                        }
                    }
                    is AnnouncementTarget.World -> {
                        if (target.worldId == EnvVars.worldId) {
                            for (player in WorldPlayers.values()) {
                                player.session.send(GameMessage(type, msg.message))
                            }
                        }
                    }
                    is AnnouncementTarget.User -> {
                        val player = WorldPlayers.get(target.username.formatPlayerNameForProtocol()) ?: return
                        player.session.send(GameMessage(type, msg.message))
                    }
                    is AnnouncementTarget.FriendsChat -> {
                        val owner = target.ownerUsername.formatPlayerNameForProtocol()
                        for (player in WorldPlayers.values()) {
                            if (player.friendsChatOwnerUsername == owner) {
                                player.session.send(GameMessage(type, msg.message))
                            }
                        }
                    }
                }
            }

            else -> {
                // ignore for now
            }
        }
    }
}


