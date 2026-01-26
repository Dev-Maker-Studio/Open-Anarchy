package org.darkan.lobby.social

import io.ktor.websocket.*
import java.util.concurrent.atomic.AtomicBoolean

class WorldSession(
    val worldId: Int,
    val worldName: String,
    private val socket: WebSocketSession,
) {
    private val closed = AtomicBoolean(false)

    fun isClosed(): Boolean = closed.get()

    suspend fun sendText(text: String) {
        if (isClosed()) return
        socket.send(Frame.Text(text))
    }

    fun markClosed() {
        closed.set(true)
    }
}


