package org.darkan.lobby.harness

import io.ktor.utils.io.ByteChannel
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.darkan.core.net.Isaac
import org.darkan.core.net.ProtSession
import org.darkan.core.net.Session
import org.darkan.core.net.prot.ClientProt
import org.darkan.core.net.prot.Codec
import org.darkan.core.net.prot.ServerProt

class RecordingSession(codec: Codec) : Session(
    write = ByteChannel(autoFlush = true),
    isaacIn = Isaac(intArrayOf(0, 0, 0, 0)),
    isaacOut = null,
    ip = "127.0.0.1",
    codec = codec,
) {

    sealed interface Event {
        data class Queued(val packet: ServerProt) : Event
        data object Flush : Event
    }

    private val outbound: Channel<Any>
    private val recorded = mutableListOf<Event>()

    init {
        val writerJobField = ProtSession::class.java.getDeclaredField("writerJob").apply { isAccessible = true }
        runBlocking { (writerJobField.get(this@RecordingSession) as Job).cancelAndJoin() }
        val outboundField = ProtSession::class.java.getDeclaredField("outbound").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        outbound = outboundField.get(this) as Channel<Any>
    }

    val events: List<Event>
        get() {
            drainOutbound()
            return recorded.toList()
        }

    val sent: List<ServerProt>
        get() = events.filterIsInstance<Event.Queued>().map { it.packet }

    val flushed: List<ServerProt>
        get() {
            val all = events
            val lastFlush = all.indexOfLast { it is Event.Flush }
            if (lastFlush == -1) return emptyList()
            return all.take(lastFlush).filterIsInstance<Event.Queued>().map { it.packet }
        }

    val flushes: Int
        get() = events.count { it is Event.Flush }

    fun clearSent() {
        drainOutbound()
        recorded.clear()
    }

    fun pushClientPacket(packet: ClientProt) {
        check(readChannel.trySend(packet).isSuccess) { "Read channel full; the player is not draining packets" }
    }

    private fun drainOutbound() {
        while (true) {
            val item = outbound.tryReceive().getOrNull() ?: return
            val prot = protOf(item)
            recorded.add(if (prot != null) Event.Queued(prot) else Event.Flush)
        }
    }

    private fun protOf(item: Any): ServerProt? {
        val field = item.javaClass.declaredFields.firstOrNull { it.name == "prot" } ?: return null
        field.isAccessible = true
        return field.get(item) as? ServerProt
    }
}
