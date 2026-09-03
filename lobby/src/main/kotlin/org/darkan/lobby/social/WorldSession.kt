package org.darkan.lobby.social

import io.ktor.websocket.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One connected world, as the gateway sees it: an id, a name and a way to push a line of JSON at
 * it. The transport is a lambda rather than the [WebSocketSession] itself so the gateway's message
 * handling can be driven from a test with no socket behind it.
 */
class WorldSession(
    val worldId: Int,
    val worldName: String,
    private val transport: suspend (String) -> Unit,
) {
    constructor(worldId: Int, worldName: String, socket: WebSocketSession) :
        this(worldId, worldName, { text -> socket.send(Frame.Text(text)) })

    private val closed = AtomicBoolean(false)

    fun isClosed(): Boolean = closed.get()

    suspend fun sendText(text: String) {
        if (isClosed()) return
        transport(text)
    }

    fun markClosed() {
        closed.set(true)
    }
}
