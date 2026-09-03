package org.darkan.world.social

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import org.darkan.core.EnvVars
import org.darkan.core.Logger.logInfo
import org.darkan.core.Logger.logWarn
import org.darkan.core.net.prot.ClientProt
import java.net.ConnectException
import org.darkan.core.social.gateway.ClanAddMemberRequest
import org.darkan.core.social.gateway.ClanBanMemberRequest
import org.darkan.core.social.gateway.ClanCheckNameRequest
import org.darkan.core.social.gateway.ClanCreateRequest
import org.darkan.core.social.gateway.ClanGuestJoinRequest
import org.darkan.core.social.gateway.ClanGuestLeaveRequest
import org.darkan.core.social.gateway.ClanKickMemberRequest
import org.darkan.core.social.gateway.ClanLeaveRequest
import org.darkan.core.social.gateway.ClanSetRankRequest
import org.darkan.core.social.gateway.ClanUnbanMemberRequest
import org.darkan.core.social.gateway.ClientPacketEnvelope
import org.darkan.core.social.gateway.FcSettingsUpdate
import org.darkan.core.social.gateway.GatewayWorldInfo
import org.darkan.core.social.gateway.PlayerOffline
import org.darkan.core.social.gateway.PlayerOnline
import org.darkan.core.social.gateway.SocialGatewayWireJson
import org.darkan.core.social.gateway.SocialGatewayWireMessage
import org.darkan.core.social.gateway.WorldHello
import org.darkan.core.social.gateway.WorldHelloAck
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SocialClient(
    private val scope: CoroutineScope,
    private val onMessage: suspend (SocialGatewayWireMessage) -> Unit,
) {
    private val client = HttpClient(CIO) {
        install(WebSockets) {
            pingInterval = 15.seconds
            maxFrameSize = Long.MAX_VALUE
        }
    }

    private val state = AtomicReference<State>(State.Stopped)

    sealed interface State {
        data object Stopped : State
        data class Connecting(val attempt: Int) : State
        data class Connected(val session: DefaultClientWebSocketSession) : State
    }

    fun start() {
        if (!state.compareAndSet(State.Stopped, State.Connecting(attempt = 0))) return
        scope.launch(Dispatchers.IO + SupervisorJob()) { connectLoop() }
    }

    /**
     * @return true if the message was handed to the gateway socket, false if it was dropped
     *         because the gateway is down (or the write failed). Callers that can reach a player
     *         should tell them, the way legacy LobbyCommunicator.forwardPackets did.
     */
    suspend fun send(msg: SocialGatewayWireMessage): Boolean {
        val st = state.get()
        val session = (st as? State.Connected)?.session
        if (session == null) {
            logWarn("SocialClient dropped ${msg::class.simpleName}: gateway not connected (state=${st::class.simpleName})")
            return false
        }
        val text = SocialGatewayWireJson.json.encodeToString(SocialGatewayWireMessage.serializer(), msg)
        return try {
            session.send(Frame.Text(text))
            true
        } catch (e: Exception) {
            logWarn("SocialClient send failed type=${msg::class.simpleName}", e)
            false
        }
    }

    suspend fun sendClientPacket(username: String, packet: ClientProt): Boolean =
        send(ClientPacketEnvelope(username = username, packet = packet))

    suspend fun sendPlayerOnline(username: String, displayName: String, rightsCrown: Int, privateStatus: Int): Boolean =
        send(PlayerOnline(username = username, displayName = displayName, rightsCrown = rightsCrown, privateStatus = privateStatus))

    suspend fun sendPlayerOffline(username: String) {
        send(PlayerOffline(username = username))
    }

    suspend fun sendFcSettingsUpdate(username: String, name: String?, rankToEnter: Int, rankToSpeak: Int, rankToKick: Int, rankToLS: Int) {
        send(FcSettingsUpdate(username = username, name = name, rankToEnter = rankToEnter, rankToSpeak = rankToSpeak, rankToKick = rankToKick, rankToLS = rankToLS))
    }

    suspend fun sendClanCreate(username: String, clanName: String) {
        send(ClanCreateRequest(username = username, clanName = clanName))
    }

    suspend fun sendClanCheckName(username: String, clanName: String) {
        send(ClanCheckNameRequest(username = username, clanName = clanName))
    }

    suspend fun sendClanLeave(username: String) {
        send(ClanLeaveRequest(username = username))
    }

    suspend fun sendClanAddMember(inviterUsername: String, targetUsername: String) {
        send(ClanAddMemberRequest(inviterUsername = inviterUsername, targetUsername = targetUsername))
    }

    suspend fun sendClanKickMember(kickerUsername: String, targetUsername: String) {
        send(ClanKickMemberRequest(kickerUsername = kickerUsername, targetUsername = targetUsername))
    }

    suspend fun sendClanSetRank(username: String, targetUsername: String, rank: Int) {
        send(ClanSetRankRequest(username = username, targetUsername = targetUsername, rank = rank))
    }

    suspend fun sendClanBanMember(username: String, targetUsername: String) {
        send(ClanBanMemberRequest(username = username, targetUsername = targetUsername))
    }

    suspend fun sendClanUnbanMember(username: String, targetUsername: String) {
        send(ClanUnbanMemberRequest(username = username, targetUsername = targetUsername))
    }

    suspend fun sendClanGuestJoin(username: String, clanName: String) {
        send(ClanGuestJoinRequest(username = username, clanName = clanName))
    }

    suspend fun sendClanGuestLeave(username: String) {
        send(ClanGuestLeaveRequest(username = username))
    }

    private suspend fun connectLoop() {
        var attempt = 0
        while (scope.isActive) {
            try {
                state.set(State.Connecting(attempt))
                val url = EnvVars.socialGatewayUrl
                logInfo("SocialClient connecting url=$url attempt=$attempt")

                client.webSocket(urlString = url) {
                    val hello = WorldHello(
                        world = GatewayWorldInfo.fromEnv(),
                        token = EnvVars.socialGatewayToken,
                    )
                    send(Frame.Text(SocialGatewayWireJson.json.encodeToString(SocialGatewayWireMessage.serializer(), hello)))

                    // Wait for ack (best-effort)
                    val first = incoming.receiveCatching().getOrNull()
                    val ack = (first as? Frame.Text)?.readText()?.let { txt ->
                        try {
                            SocialGatewayWireJson.json.decodeFromString(SocialGatewayWireMessage.serializer(), txt) as? WorldHelloAck
                        } catch (_: Exception) {
                            null
                        }
                    }
                    if (ack != null && !ack.ok) {
                        logWarn("SocialClient unauthorized: ${ack.message ?: "no message"}")
                        close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Unauthorized"))
                        return@webSocket
                    }

                    state.set(State.Connected(this))
                    logInfo("SocialClient connected")

                    for (frame in incoming) {
                        val txt = (frame as? Frame.Text)?.readText() ?: continue
                        val msg = try {
                            SocialGatewayWireJson.json.decodeFromString(SocialGatewayWireMessage.serializer(), txt)
                        } catch (e: Exception) {
                            logWarn("SocialClient received invalid JSON: $txt", e)
                            continue
                        }
                        logInfo("SocialClient received: ${msg::class.simpleName}")
                        onMessage(msg)
                    }
                }
            } catch (_: CancellationException) {
                return
            } catch (e: Exception) {
                if (e is ConnectException) {
                    logInfo("SocialClient connection refused (attempt $attempt, retrying...)")
                } else {
                    logWarn("SocialClient connection error: ${e::class.simpleName}: ${e.message}")
                }
            } finally {
                state.set(State.Stopped)
            }

            attempt++
            val backoff = (250L * attempt).coerceAtMost(5_000L).milliseconds
            delay(backoff)
        }
    }
}


