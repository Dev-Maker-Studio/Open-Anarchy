package com.rs.game.content.voice

import com.rs.engine.command.Commands
import com.rs.game.model.entity.player.Player
import com.rs.game.tasks.WorldTasks
import com.rs.plugin.annotations.ServerStartupEvent
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.launch
import org.darkan.core.EnvVars
import org.darkan.core.Logger
import org.darkan.core.model.Rights

private const val CONNECT_RANGE = 32

private class VoicePeer(val session: WebSocketSession) {
    @Volatile var player: Player? = null
    @Volatile var status = 0
    @Volatile var near = emptyMap<Int, VoicePeer>()
    var sent = ""

    fun send(text: String) {
        session.outgoing.trySend(Frame.Text(text))
    }
}

private val pending = ConcurrentHashMap<String, VoicePeer>()
private val peers = ConcurrentHashMap.newKeySet<VoicePeer>()

private fun link(player: Player, nonce: String?) {
    val peer = pending.remove(nonce ?: return) ?: return
    peers.filter { it.player === player }.forEach { it.session.launch { it.session.close() } }
    peer.player = player
    peers += peer
    peer.send("m${player.index}")
}

private fun tick() {
    peers.removeIf { peer -> peer.player!!.hasFinished().also { if (it) peer.session.launch { peer.session.close() } } }
    for (peer in peers) {
        val me = peer.player!!
        val near = peers.filter { other ->
            val p = other.player!!
            other !== peer && p.plane == me.plane && max(abs(p.x - me.x), abs(p.y - me.y)) <= CONNECT_RANGE
        }
        peer.near = near.associateBy { it.player!!.index }
        val text = "p" + near.joinToString(";") { "${it.player!!.index},${it.player!!.x - me.x},${it.player!!.y - me.y},${it.status}" }
        if (text != peer.sent) {
            peer.sent = text
            peer.send(text)
        }
    }
}

private suspend fun serve(session: WebSocketSession) {
    val peer = VoicePeer(session)
    var nonce: String? = null
    try {
        for (frame in session.incoming) {
            val text = (frame as? Frame.Text)?.readText() ?: continue
            val index = peer.player?.index
            when {
                text.startsWith("n") && nonce == null && text.length in 17..65 -> {
                    nonce = text.substring(1)
                    pending[nonce] = peer
                    peer.send("ok")
                }
                index == null -> {}
                text.startsWith("s") -> {
                    peer.status = (text.getOrNull(1)?.digitToIntOrNull() ?: 0).coerceAtMost(3)
                    peer.near.values.forEach { it.send("s$index,${peer.status}") }
                }
                text.startsWith("t") -> {
                    val space = text.indexOf(' ')
                    if (space > 1)
                        peer.near[text.substring(1, space).toIntOrNull()]?.send("f$index${text.substring(space)}")
                }
            }
        }
    } finally {
        nonce?.let(pending::remove)
        peers -= peer
    }
}

@ServerStartupEvent
fun startVoice() {
    Commands.add(Rights.PLAYER, "voice [nonce]", "Links the browser's voice connection to you.") { p, args -> link(p, args.getOrNull(0)) }
    WorldTasks.scheduleTimer { tick(); true }
    runCatching {
        embeddedServer(Netty, port = EnvVars.voicePort, host = "127.0.0.1") {
            install(WebSockets) { maxFrameSize = 16384 }
            routing { webSocket("/voice") { serve(this) } }
        }.start(wait = false)
    }.onFailure { Logger.handle(VoicePeer::class.java, "startVoice", it) }
}
