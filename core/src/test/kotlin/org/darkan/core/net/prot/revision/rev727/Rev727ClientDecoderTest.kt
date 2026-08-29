package org.darkan.core.net.prot.revision.rev727

import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.Source
import org.darkan.core.model.Preference
import org.darkan.core.net.prot.ClientProt
import org.darkan.core.net.prot.Codec
import org.darkan.core.net.prot.packet.IfButton
import org.darkan.core.net.prot.packet.IfOnTile
import org.darkan.core.net.prot.packet.MoveCamera
import org.darkan.core.net.prot.packet.OpNpc
import org.darkan.core.net.prot.packet.OpPlayer
import org.darkan.core.net.prot.packet.SendFps
import org.darkan.core.net.prot.packet.SendPreferences
import org.darkan.core.net.prot.packet.Walk
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Byte-level tests for the revision 727 client decoders.
 *
 * Each test builds the exact bytes the client would put on the wire, using an independent
 * implementation of the client's write transforms, and asserts what the decoder makes of them.
 * Writing the transforms out separately is the point: asserting a decoder against itself would
 * pass just as happily with the byte order reversed.
 *
 * Values are deliberately chosen with the high bit set where a transform previously
 * sign-extended, since those are the inputs that distinguish a correct decoder from a broken one.
 */
class Rev727ClientDecoderTest {

    private lateinit var codec: Codec

    @BeforeTest
    fun setUp() {
        codec = register727()
    }

    /** Mirrors the client's buffer writes, so the decoders are checked against an independent encoder. */
    private class ClientWriter {
        private val bytes = mutableListOf<Byte>()

        fun bytes(): ByteArray = bytes.toByteArray()

        private fun put(value: Int) = apply { bytes.add((value and 0xff).toByte()) }

        fun writeByte(value: Int) = put(value)
        fun writeByteAdd(value: Int) = put(value + 128)
        fun writeByteSubtract(value: Int) = put(128 - value)

        fun writeShort(value: Int) = apply { put(value shr 8); put(value) }
        fun writeShortAdd(value: Int) = apply { put(value shr 8); put(value + 128) }
        fun writeShortLittle(value: Int) = apply { put(value); put(value shr 8) }
        fun writeShortAddLittle(value: Int) = apply { put(value + 128); put(value shr 8) }

        fun writeInt(value: Int) = apply { put(value shr 24); put(value shr 16); put(value shr 8); put(value) }
        fun writeIntLittle(value: Int) = apply { put(value); put(value shr 8); put(value shr 16); put(value shr 24) }

        /** Byte order `[b8, b0, b24, b16]`, the inverse of `readUIntMiddle`. */
        fun writeIntMiddle(value: Int) = apply {
            put(value shr 8); put(value); put(value ushr 24); put(value shr 16)
        }

        /** Byte order `[b16, b24, b0, b8]`, the inverse of `readUIntInverseMiddle`. */
        fun writeIntInverseMiddle(value: Int) = apply {
            put(value shr 16); put(value ushr 24); put(value); put(value shr 8)
        }
    }

    private fun write(block: ClientWriter.() -> Unit): ByteArray = ClientWriter().apply(block).bytes()

    private fun source(bytes: ByteArray): Source = Buffer().apply { write(bytes) }

    @Suppress("UNCHECKED_CAST")
    private fun <T : ClientProt> decode(opcode: Int, bytes: ByteArray): T {
        val entry = codec.clientProtsByOpcode[opcode] ?: error("no decoder registered for opcode $opcode")
        val decoder = entry.decoder ?: error("opcode $opcode has no decoder body")
        return runBlocking { decoder.invoke(source(bytes), opcode) } as T
    }

    private fun interfaceHash(interfaceId: Int, componentId: Int) = (interfaceId shl 16) or componentId

    @Test
    fun `player ops map their opcode to the operation number`() {
        // The client picks a distinct opcode per menu option; the decoder recovers the option index.
        val opcodesInOrder = listOf(66, 6, 31, 89, 103, 1, 51, 94, 53, 70)
        opcodesInOrder.forEachIndexed { index, opcode ->
            val packet: OpPlayer = decode(opcode, write { writeShort(1234); writeByteSubtract(1) })
            assertEquals(index + 1, packet.opNum, "opcode $opcode")
            assertEquals(1234, packet.playerIndex)
            assertEquals(true, packet.forceRun)
        }
    }

    @Test
    fun `npc ops map their opcode to the operation number`() {
        val opcodesInOrder = listOf(65, 16, 50, 77, 95, 3)
        opcodesInOrder.forEachIndexed { index, opcode ->
            val packet: OpNpc = decode(opcode, write { writeShort(4321); writeByte(0) })
            assertEquals(index + 1, packet.opNum, "opcode $opcode")
            assertEquals(4321, packet.npcIndex)
            assertEquals(false, packet.forceRun)
        }
    }

    @Test
    fun `interface buttons unpack a little-endian hash and an add-transformed slot`() {
        // Interface 750 puts 0xEE in the hash's low byte, which a signed read would turn negative.
        val hash = interfaceHash(750, 31)
        val packet: IfButton = decode(96, write {
            writeIntLittle(hash)
            writeShort(12345)
            writeShortAdd(40000)
        })
        assertEquals(1, packet.opNum)
        assertEquals(750, packet.interfaceId)
        assertEquals(31, packet.componentId)
        assertEquals(12345, packet.itemId)
        assertEquals(40000, packet.slotId, "slot ids above 32767 must not decode negative")
    }

    @Test
    fun `interface button opcodes map to their operation number`() {
        val opcodesInOrder = listOf(96, 27, 68, 9, 72, 19, 23, 21, 22, 81)
        opcodesInOrder.forEachIndexed { index, opcode ->
            val packet: IfButton = decode(opcode, write {
                writeIntLittle(interfaceHash(1, 2)); writeShort(0); writeShortAdd(0)
            })
            assertEquals(index + 1, packet.opNum, "opcode $opcode")
        }
    }

    @Test
    fun `use-item-on-tile reads a middle-endian hash`() {
        val hash = interfaceHash(750, 31)
        val packet: IfOnTile = decode(46, write {
            writeShortLittle(4151)     // itemId
            writeShortLittle(3218)     // y
            writeIntMiddle(hash)
            writeShortAdd(9)           // slotId
            writeShortLittle(3222)     // x
        })
        assertEquals(750, packet.interfaceId)
        assertEquals(31, packet.componentId)
        assertEquals(4151, packet.itemId)
        assertEquals(9, packet.slotId)
        assertEquals(3222, packet.x)
        assertEquals(3218, packet.y)
    }

    @Test
    fun `fps telemetry reads two scrambled ints and a subtract-transformed byte`() {
        val packet: SendFps = decode(44, write {
            writeIntInverseMiddle(-889275714)   // 0xCAFEBABE, every byte high-bit set
            writeIntMiddle(0x0BADF00D)
            writeByteSubtract(60)
        })
        assertEquals(-889275714, packet.key1)
        assertEquals(0x0BADF00D, packet.key2)
        assertEquals(60, packet.fps)
    }

    @Test
    fun `camera angles above 32767 decode unsigned`() {
        val packet: MoveCamera = decode(83, write {
            writeShortAddLittle(40000)
            writeShortAdd(50000)
        })
        assertEquals(40000, packet.angleX)
        assertEquals(50000, packet.angleY)
    }

    @Test
    fun `walking reads the destination and the run flag`() {
        val packet: Walk = decode(33, write {
            writeByte(1)
            writeShort(3222)
            writeShortLittle(3218)
        })
        assertEquals(3222, packet.x)
        assertEquals(3218, packet.y)
        assertEquals(true, packet.forceRun)
        assertEquals(false, packet.minimap, "opcode 33 is a scene click, not a minimap click")
    }

    @Test
    fun `preferences consume exactly one byte per index`() {
        // Index 0 maps to no Preference. A decoder that consumes an extra byte for unmapped
        // indices - or for successfully mapped ones - shifts every later preference.
        val values = IntArray(13) { it + 1 }
        val packet: SendPreferences = decode(10, write { values.forEach { writeByte(it) } })
        val prefs = packet.preferences
        assertEquals(12, prefs.size, "index 0 is the entry count and maps to no Preference")
        assertEquals(2, prefs[Preference.ANTI_ALIASING], "index 1 is the second byte")
        assertEquals(3, prefs[Preference.BLOOM])
        assertEquals(4, prefs[Preference.BRIGHTNESS])
        assertEquals(8, prefs[Preference.FOG], "index 7")
        assertEquals(13, prefs[Preference.SCENERY_SHADOWS], "index 12, the last byte")
    }
}
