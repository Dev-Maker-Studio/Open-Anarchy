package org.darkan.world.social

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import org.darkan.core.EnvVars
import org.darkan.core.Logger.logInfo
import org.darkan.core.Logger.logWarn
import java.net.ConnectException
import org.darkan.core.social.gateway.FcSettingsUpdate
import org.darkan.core.social.gateway.DisplayNameResult
import org.darkan.core.social.gateway.DisplayNameLookup
import org.darkan.core.social.gateway.GatewayPlayerPresence
import org.darkan.core.social.gateway.GatewayWorldInfo
import org.darkan.core.social.gateway.LoginTokenVerdict
import org.darkan.core.social.gateway.PlayerOffline
import org.darkan.core.social.gateway.PlayerOnline
import org.darkan.core.social.gateway.PresenceSyncReason
import org.darkan.core.social.gateway.VerifyLoginToken
import org.darkan.core.social.gateway.WorldPresenceSnapshot
import org.darkan.core.social.gateway.SocialGatewayWireJson
import org.darkan.core.social.gateway.SocialGatewayWireMessage
import org.darkan.core.social.gateway.WorldHello
import org.darkan.core.social.gateway.WorldHelloAck
import world.gregs.voidps.cache.Cache
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The world's end of the social gateway socket.
 *
 * Presence is pushed, not asked for: [onlinePlayers] is sent as a [WorldPresenceSnapshot] the
 * moment the socket comes up and again every [EnvVars.socialPresenceResyncSeconds], so the lobby
 * can always rebuild its picture of this world from the world's own. That is what makes a restart
 * on either side recoverable - a world that outlived a lobby restart re-announces its players and
 * gets their whole social state pushed back without anybody relogging, and a lobby that never saw
 * a `PlayerOffline` (because the socket was down) drops those players at the next snapshot.
 */
class SocialClient(
    private val scope: CoroutineScope,
    private val onlinePlayers: () -> List<GatewayPlayerPresence> = { emptyList() },
    /**
     * Told whenever the socket comes up or goes down. Replicas the world only holds because the
     * lobby pushed them - the clan registry - are dropped here, so a reconnect starts from the
     * lobby's resync rather than from stale copies.
     */
    private val onConnectionChange: (connected: Boolean) -> Unit = {},
    private val onMessage: suspend (SocialGatewayWireMessage) -> Unit,
) {
    private val client = HttpClient(CIO) {
        install(WebSockets) {
            pingInterval = 15.seconds
            maxFrameSize = Long.MAX_VALUE
        }
    }

    private val state = AtomicReference<State>(State.Stopped)

    private val pendingVerdicts = ConcurrentHashMap<Int, CompletableDeferred<LoginTokenVerdict>>()
    private val pendingDisplayNames = ConcurrentHashMap<Int, CompletableDeferred<DisplayNameResult>>()
    private val nextRequestId = AtomicInteger()

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

    suspend fun sendPlayerOnline(username: String, displayName: String, rightsCrown: Int, privateStatus: Int): Boolean =
        send(PlayerOnline(username = username, displayName = displayName, rightsCrown = rightsCrown, privateStatus = privateStatus))

    suspend fun sendPlayerOffline(username: String) {
        send(PlayerOffline(username = username))
    }

    /** Tells the lobby exactly who this world has in game right now. */
    suspend fun sendPresenceSnapshot(reason: PresenceSyncReason): Boolean =
        send(WorldPresenceSnapshot(players = onlinePlayers(), reason = reason))

    suspend fun sendFcSettingsUpdate(username: String, name: String?, rankToEnter: Int, rankToSpeak: Int, rankToKick: Int, rankToLS: Int) {
        send(FcSettingsUpdate(username = username, name = name, rankToEnter = rankToEnter, rankToSpeak = rankToSpeak, rankToKick = rankToKick, rankToLS = rankToLS))
    }

    /** The lobby's answer for a community world's login, or null when the lobby could not be asked. */
    suspend fun verifyLoginToken(username: String, token: String): LoginTokenVerdict? {
        val requestId = nextRequestId.incrementAndGet()
        val verdict = CompletableDeferred<LoginTokenVerdict>()
        pendingVerdicts[requestId] = verdict
        try {
            if (!send(VerifyLoginToken(requestId = requestId, username = username, token = token))) return null
            return withTimeoutOrNull(VERDICT_TIMEOUT) { verdict.await() }
        } finally {
            pendingVerdicts.remove(requestId)
        }
    }

    suspend fun lookupDisplayName(username: String): String? {
        val requestId = nextRequestId.incrementAndGet()
        val result = CompletableDeferred<DisplayNameResult>()
        pendingDisplayNames[requestId] = result
        try {
            if (!send(DisplayNameLookup(requestId = requestId, username = username))) return null
            return withTimeoutOrNull(VERDICT_TIMEOUT) { result.await() }?.displayName
        } finally {
            pendingDisplayNames.remove(requestId)
        }
    }

    /**
     * Re-sends the online list on a timer. Nothing is expected to be wrong; this exists so that
     * drift - a dropped `PlayerOnline`, a socket that died mid-logout - cannot outlive one
     * interval.
     */
    private suspend fun resyncLoop() {
        val interval = EnvVars.socialPresenceResyncSeconds.seconds
        while (true) {
            delay(interval)
            if (!sendPresenceSnapshot(PresenceSyncReason.PERIODIC)) return
        }
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
                        world = GatewayWorldInfo.fromEnv(cacheCrcs = Cache.get().indexCrcs().toList()),
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
                    onConnectionChange(true)
                    logInfo("SocialClient connected")

                    // The handshake is only half the story: the lobby cannot know who is already
                    // in game here, so hand it the full list before anything else.
                    sendPresenceSnapshot(PresenceSyncReason.CONNECT)
                    val resync = launch { resyncLoop() }

                    for (frame in incoming) {
                        val txt = (frame as? Frame.Text)?.readText() ?: continue
                        val msg = try {
                            SocialGatewayWireJson.json.decodeFromString(SocialGatewayWireMessage.serializer(), txt)
                        } catch (e: Exception) {
                            logWarn("SocialClient received invalid JSON: $txt", e)
                            continue
                        }
                        logInfo("SocialClient received: ${msg::class.simpleName}")
                        if (msg is LoginTokenVerdict) {
                            pendingVerdicts.remove(msg.requestId)?.complete(msg)
                            continue
                        }
                        if (msg is DisplayNameResult) {
                            pendingDisplayNames.remove(msg.requestId)?.complete(msg)
                            continue
                        }
                        onMessage(msg)
                    }
                    resync.cancel()
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
                onConnectionChange(false)
            }

            attempt++
            val backoff = (250L * attempt).coerceAtMost(5_000L).milliseconds
            delay(backoff)
        }
    }

    private companion object {
        val VERDICT_TIMEOUT = 10.seconds
    }
}
