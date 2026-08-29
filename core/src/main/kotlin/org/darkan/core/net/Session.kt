package org.darkan.core.net

import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.Source
import org.darkan.core.EnvVars
import org.darkan.core.Logger.logSevere
import org.darkan.core.Logger.logTrace
import org.darkan.core.Logger.logWarn
import org.darkan.core.net.prot.*
import org.darkan.core.net.prot.packet.*
import world.gregs.voidps.buffer.readUByte
import world.gregs.voidps.buffer.readUShort
import world.gregs.voidps.buffer.writeByte
import world.gregs.voidps.buffer.writeSmart
import java.io.EOFException
import java.io.IOException
import java.net.SocketException
import java.nio.channels.ClosedChannelException
import java.util.concurrent.atomic.AtomicBoolean

open class Session(
    private val write: ByteWriteChannel,
    val isaacIn: Isaac,
    private val isaacOut: Isaac?,
    val ip: String,
    val codec: Codec,
) {
    enum class State { CONNECTED, LOST_CONNECTION, DISCONNECTED }

    /**
     * A unit of outgoing work handed to the session's writer coroutine. Every byte this session
     * writes goes through [outbound], which keeps the stream strictly ordered and keeps the
     * socket single-writer - [ByteWriteChannel] explicitly forbids concurrent writes.
     */
    private sealed interface Outbound {
        /** Encode [prot] and write it, advancing the outgoing ISAAC cipher unless [noIsaac]. */
        data class Packet(val prot: ServerProt, val noIsaac: Boolean) : Outbound

        /** Push everything written so far to the socket, completing [completion] once it lands. */
        data class Flush(val completion: CompletableDeferred<Unit>?) : Outbound
    }

    val readChannel = Channel<ClientProt>(capacity = EnvVars.packetQueueCapacity)

    private val outbound = Channel<Outbound>(capacity = EnvVars.outboundQueueCapacity)
    private val writerScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineName("session-writer-$ip"))
    private val writerJob: Job = writerScope.launch { writeLoop() }

    /** Set whenever a packet is queued, cleared by [requestFlush] so idle ticks stay free. */
    private val hasPendingWrites = AtomicBoolean(false)

    /** Read from the writer coroutine and written from tick/handler threads, so it must be volatile. */
    @Volatile
    var disconnected: Boolean = false
    private var disconnect: (() -> Unit)? = null
    private var disconnecting: (() -> Unit)? = null
    @Volatile
    private var state: State = State.CONNECTED

    // --- Java interop ---

    /** Legacy alias for [ip] with capital IP naming. */
    @JvmName("getIP")
    fun getIPAddress(): String = ip

    /** Legacy alias for checking if session is closed. */
    fun isClosed(): Boolean = disconnected

    /** No-op for legacy code that tried to set decoder. */
    @Suppress("UNUSED_PARAMETER")
    fun setDecoder(decoder: Any?) { /* no-op in new architecture */ }

    /** Legacy close method - marks session as disconnected. */
    fun close() {
        disconnected = true
        state = State.DISCONNECTED
        // Close the queue rather than killing the writer so anything already queued (the logout
        // packet, typically) still reaches the client before the writer coroutine exits.
        outbound.close()
        disconnect?.invoke()
    }

    fun onDisconnected(block: () -> Unit) {
        disconnect = block
    }

    fun onDisconnecting(block: () -> Unit) {
        disconnecting = block
    }

    suspend fun disconnect(reason: Int) {
        if (disconnected) {
            return
        }
        disconnected = true
        // The response code bypasses the packet queue, so the writer has to be off the socket first.
        if (stopWriter())
            write.writeByte(reason)
        finishDisconnect()
    }

    suspend fun disconnect() {
        if (disconnected) return
        disconnected = true
        stopWriter()
        finishDisconnect()
    }

    fun exit() {
        if (state == State.CONNECTED) {
            state = State.LOST_CONNECTION
            disconnecting?.invoke()
        }
    }

    /**
     * Queues a packet to be sent on the next flush. Safe to call from non-suspend contexts.
     */
    fun queuePacket(packet: ServerProt) {
        enqueue(Outbound.Packet(packet, noIsaac = false))
    }

    /**
     * Queues [serverProt] on the outbound stream. Encoding and the socket write both happen on the
     * session's writer coroutine, so this never touches the socket on the caller's thread.
     */
    open suspend fun send(serverProt: ServerProt, noIsaac: Boolean = false) {
        enqueue(Outbound.Packet(serverProt, noIsaac))
    }

    /**
     * Asks the writer to push everything queued so far and returns immediately. This is what the
     * tick loops call: a client that has stopped draining its socket can never hold up the world,
     * it just backs up in [outbound] until the queue limit drops the session.
     */
    fun requestFlush() {
        if (!hasPendingWrites.compareAndSet(true, false)) return
        enqueue(Outbound.Flush(null))
    }

    /**
     * Suspending flush that only returns once everything queued so far has actually been written
     * to the socket. Used by the login handshakes to order the connection setup - never call this
     * from a tick loop, use [requestFlush] there.
     */
    open suspend fun flush() {
        if (disconnected) return
        hasPendingWrites.set(false)
        val completion = CompletableDeferred<Unit>()
        if (!enqueue(Outbound.Flush(completion))) return
        // Bounded so a writer that died without draining its queue can never strand a login.
        if (withTimeoutOrNull(WRITER_DRAIN_TIMEOUT_MS) { completion.await() } == null)
            logTrace("Timed out waiting on flush for $ip")
    }

    /**
     * Non-suspend poll for the next decoded packet.
     * Returns null if channel is empty. Safe to call from Java.
     */
    fun pollPacket(): ClientProt? = readChannel.tryReceive().getOrNull()

    suspend fun readPackets(input: ByteReadChannel) {
        try {
            while (!disconnected) {
                val cipher = isaacIn.nextInt()
                val opcode = (input.readUByte() - cipher) and 0xff
                val clientProt = codec.clientProtsByOpcode[opcode]
                if (clientProt == null) {
                    logSevere("Missing ClientProt with opcode $opcode")
                    return
                }
                val size = when (clientProt.size) {
                    is ProtSize.Fixed -> clientProt.size.length
                    ProtSize.VarByte -> input.readUByte()
                    ProtSize.VarShort -> input.readUShort()
                }
                val packet = input.readPacket(size)

                val packetData = try {
                    clientProt.decoder?.invoke(packet, opcode) ?: codec.createInstanceForOpcode<ClientProt>(opcode)
                } catch (e: Exception) {
                    logSevere("Decoder exception for opcode $opcode (${clientProt.protClass.simpleName}, size=$size): ${e::class.simpleName}: ${e.message}")
                    continue
                }
                if (packetData == null) {
                    logSevere("Failed to create packet instance for opcode $opcode")
                    continue
                }
                logTrace("Decoded packet: ${packetData::class.simpleName} from opcode $opcode (size=$size)")
                readChannel.send(packetData)
            }
        } catch (e: Exception) {
            if (isExpectedDisconnect(e)) {
                logTrace("Session read ended: ${e::class.simpleName}: ${e.message}")
            } else {
                throw e
            }
        } finally {
            if (!disconnected) {
                disconnected = true
                state = State.LOST_CONNECTION
                disconnecting?.invoke()
            }
            // Read side is gone, so nothing new can be queued: let the writer drain and exit.
            outbound.close()
        }
    }

    /**
     * Hands [item] to the writer coroutine. Returns false if the session is gone or the client is
     * too far behind to keep up, in which case the session has been dropped.
     */
    private fun enqueue(item: Outbound): Boolean {
        if (disconnected) return false
        val result = outbound.trySend(item)
        if (result.isSuccess) {
            if (item is Outbound.Packet) hasPendingWrites.set(true)
            return true
        }
        if (result.isClosed) return false
        // The writer is stuck on a socket the client is not draining. Dropping individual packets
        // would desync the client and an unbounded queue is a memory/DoS hazard, so the session
        // goes instead - explicitly and loudly.
        logWarn("Outbound queue overflow (${EnvVars.outboundQueueCapacity} packets) for $ip, dropping session")
        dropSlowClient()
        return false
    }

    /**
     * Single writer for the socket. Encodes queued packets in order and only touches the socket
     * from this one coroutine, so ordering and the outgoing ISAAC stream stay consistent no matter
     * how many threads queued the packets.
     */
    private suspend fun writeLoop() {
        try {
            for (item in outbound) {
                when (item) {
                    is Outbound.Packet -> writePacket(item.prot, item.noIsaac)
                    is Outbound.Flush -> {
                        write.flush()
                        item.completion?.complete(Unit)
                    }
                }
            }
            write.flush()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isExpectedDisconnect(e))
                logTrace("Session write ended: ${e::class.simpleName}: ${e.message}")
            else
                logWarn("Client write error:", e)
            if (!disconnected) disconnected = true
            exit()
        } finally {
            releaseFlushWaiters()
        }
    }

    private suspend fun writePacket(serverProt: ServerProt, noIsaac: Boolean) {
        val encoder = codec.serverProts[serverProt::class]
        if (encoder == null) {
            logWarn("Missing ServerProt encoder: ${serverProt::class}")
            return
        }
        if (serverProt is FriendStatus || serverProt is FriendlistLoaded || serverProt is UpdateIgnoreList) {
            logTrace("Session.send SOCIAL: ${serverProt::class.simpleName} opcode=${encoder.opcode} ip=$ip")
        }

        when (encoder.size) {
            is ProtSize.Fixed -> {
                writeOpcode(encoder.opcode, if (noIsaac) null else isaacOut)
                encoder.encoder?.invoke(serverProt, write)
            }
            ProtSize.VarByte, ProtSize.VarShort -> {
                val dataChannel = ByteChannel()
                encoder.encoder?.invoke(serverProt, dataChannel)
                val packetData = dataChannel.toByteReadPacket()
                val packetLength = packetData.remaining

                if (encoder.size == ProtSize.VarByte && packetLength > 255)
                    logWarn("Packet length exceeds VarByte maximum (${packetLength} > 255)")
                else if (encoder.size == ProtSize.VarShort && packetLength > 65535)
                    logWarn("Packet length exceeds VarShort maximum (${packetLength} > 65535)")

                writeOpcode(encoder.opcode, if (noIsaac) null else isaacOut)

                if (encoder.size == ProtSize.VarByte)
                    write.writeByte(packetLength.toByte())
                else
                    write.writeShort(packetLength.toShort())

                write.writePacket(packetData)
            }
        }
    }

    /**
     * Closes the queue and waits, bounded, for the writer to finish what it already had, so the
     * caller can safely write to the socket itself afterwards. Returns false if it never settled.
     */
    private suspend fun stopWriter(): Boolean {
        outbound.close()
        val drained = withTimeoutOrNull(WRITER_DRAIN_TIMEOUT_MS) { writerJob.join() } != null
        if (!drained)
            logTrace("Timed out draining outbound packets for $ip")
        shutdownWriter()
        return drained
    }

    /** Stops the writer coroutine for good and releases anything still waiting on [flush]. */
    private fun shutdownWriter() {
        outbound.close()
        writerScope.cancel()
        releaseFlushWaiters()
    }

    /** Completes every queued [Outbound.Flush] so no caller is left awaiting a dead writer. */
    private fun releaseFlushWaiters() {
        while (true) {
            val item = outbound.tryReceive().getOrNull() ?: break
            if (item is Outbound.Flush) item.completion?.complete(Unit)
        }
    }

    /** Terminates a client that has fallen too far behind to be worth buffering for. */
    private fun dropSlowClient() {
        if (disconnected) return
        disconnected = true
        shutdownWriter()
        write.cancel(IOException("Outbound packet queue overflow"))
        exit()
    }

    private suspend fun finishDisconnect() {
        if (withTimeoutOrNull(WRITER_DRAIN_TIMEOUT_MS) { write.flushAndClose() } == null)
            write.cancel(IOException("Timed out closing session for $ip"))
        state = State.DISCONNECTED
        disconnect?.invoke()
    }

    private suspend fun writeOpcode(opcode: Int, cipher: Isaac?) {
        if (opcode < 0) return
        if (cipher != null) {
            if (opcode >= 128) {
                write.writeByte(((opcode shr 8) + 128) + cipher.nextInt())
                write.writeByte(opcode + cipher.nextInt())
            } else
                write.writeByte(opcode + cipher.nextInt())
        } else
            write.writeSmart(opcode)
    }

    private suspend fun ByteChannel.toByteReadPacket(): Source {
        flush()
        val packet = ByteReadPacket(ByteArray(availableForRead).also { readFully(it) })
        close()
        return packet
    }

    companion object {
        /** How long a disconnect waits for queued packets to reach the socket before giving up. */
        private const val WRITER_DRAIN_TIMEOUT_MS = 1000L

        /**
         * Returns true if the exception represents an expected client disconnect
         * (connection reset, EOF, closed channel) rather than a real error.
         */
        fun isExpectedDisconnect(e: Throwable): Boolean {
            return e is ClosedByteChannelException
                || e is EOFException
                || e is SocketException
                || e is ClosedChannelException
                || (e is IOException && e.message?.let {
                    it.contains("reset", ignoreCase = true)
                    || it.contains("broken pipe", ignoreCase = true)
                    || it.contains("closed", ignoreCase = true)
                } == true)
                || (e.cause != null && isExpectedDisconnect(e.cause!!))
        }
    }
}
