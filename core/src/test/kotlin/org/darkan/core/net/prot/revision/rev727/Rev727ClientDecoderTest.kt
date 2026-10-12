package org.darkan.core.net.prot.revision.rev727

import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.Source
import org.darkan.core.model.Preference
import org.darkan.core.net.prot.ClientProt
import org.darkan.core.net.prot.Codec
import org.darkan.core.net.prot.packet.IfButton
import org.darkan.core.net.prot.packet.IfContinue
import org.darkan.core.net.prot.packet.IfDragOntoIf
import org.darkan.core.net.prot.packet.IfOnGroundItem
import org.darkan.core.net.prot.packet.IfOnIf
import org.darkan.core.net.prot.packet.IfOnNpc
import org.darkan.core.net.prot.packet.IfOnObject
import org.darkan.core.net.prot.packet.IfOnPlayer
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

class Rev727ClientDecoderTest {

    private lateinit var codec: Codec

    @BeforeTest
    fun setUp() {
        codec = register727()
    }

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

        fun writeIntMiddle(value: Int) = apply {
            put(value shr 8); put(value); put(value ushr 24); put(value shr 16)
        }

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
            writeShortLittle(4151)
            writeShortLittle(3218)
            writeIntMiddle(hash)
            writeShortAdd(9)
            writeShortLittle(3222)
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
            writeIntInverseMiddle(-889275714)
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


    @Test
    fun `a bank drag onto a tab header decodes the empty target slot as -1`() {
        val packet: IfDragOntoIf = decode(74, write {
            writeShortAddLittle(-1)
            writeShortLittle(5)
            writeShort(-1)
            writeShortAddLittle(995)
            writeIntMiddle(interfaceHash(762, 39))
            writeIntLittle(interfaceHash(762, 43))
        })
        assertEquals(-1, packet.toSlot, "a drop onto a tab header must reach Bank.switchItem as -1")
        assertEquals(-1, packet.toItemId, "a tab header holds no obj")
        assertEquals(5, packet.fromSlot)
        assertEquals(995, packet.fromItemId)
        assertEquals(762, packet.fromInter)
        assertEquals(762, packet.toInter)
        assertEquals(39, packet.fromComp)
        assertEquals(43, packet.toComp)
    }

    @Test
    fun `a drag between two occupied slots keeps both slots unsigned`() {
        val packet: IfDragOntoIf = decode(74, write {
            writeShortAddLittle(40000)
            writeShortLittle(50000)
            writeShort(1050)
            writeShortAddLittle(995)
            writeIntMiddle(interfaceHash(762, 39))
            writeIntLittle(interfaceHash(762, 39))
        })
        assertEquals(40000, packet.toSlot)
        assertEquals(50000, packet.fromSlot)
        assertEquals(1050, packet.toItemId)
    }

    @Test
    fun `an unselected slot and obj decode as -1 across the use-item packets`() {
        val hash = interfaceHash(750, 31)

        val onIf: IfOnIf = decode(4, write {
            writeShortAddLittle(-1)
            writeShortLittle(-1)
            writeShortAddLittle(-1)
            writeIntLittle(hash)
            writeIntInverseMiddle(hash)
            writeShortLittle(-1)
        })
        assertEquals(-1, onIf.toSlot)
        assertEquals(-1, onIf.fromSlot)
        assertEquals(-1, onIf.toItemId)
        assertEquals(-1, onIf.fromItemId)

        val onObject: IfOnObject = decode(98, write {
            writeShortAddLittle(3222)
            writeByteSubtract(0)
            writeIntMiddle(1276)
            writeInt(hash)
            writeShortLittle(-1)
            writeShortAdd(-1)
            writeShortLittle(3218)
        })
        assertEquals(-1, onObject.itemId)
        assertEquals(-1, onObject.slotId)

        val onNpc: IfOnNpc = decode(41, write {
            writeIntInverseMiddle(hash)
            writeShortAddLittle(1234)
            writeByteSubtract(0)
            writeShortAddLittle(-1)
            writeShortAdd(-1)
        })
        assertEquals(-1, onNpc.itemId)
        assertEquals(-1, onNpc.slotId)
        assertEquals(1234, onNpc.npcIndex, "the npc index is a real index, not a sentinel")

        val onPlayer: IfOnPlayer = decode(13, write {
            writeShort(-1)
            writeShortLittle(1234)
            writeByteSubtract(0)
            writeIntInverseMiddle(hash)
            writeShortLittle(-1)
        })
        assertEquals(-1, onPlayer.slotId)
        assertEquals(-1, onPlayer.itemId)
        assertEquals(1234, onPlayer.playerIndex)

        val onGroundItem: IfOnGroundItem = decode(67, write {
            writeShortAdd(-1)
            writeIntInverseMiddle(hash)
            writeShort(1050)
            writeByteSubtract(0)
            writeShortAddLittle(-1)
            writeShortAddLittle(3218)
            writeShortLittle(3222)
        })
        assertEquals(-1, onGroundItem.itemIdContainer)
        assertEquals(-1, onGroundItem.slotId)
        assertEquals(1050, onGroundItem.itemId)

        val onTile: IfOnTile = decode(46, write {
            writeShortLittle(-1)
            writeShortLittle(3218)
            writeIntMiddle(hash)
            writeShortAdd(-1)
            writeShortLittle(3222)
        })
        assertEquals(-1, onTile.itemId)
        assertEquals(-1, onTile.slotId)

        val button: IfButton = decode(96, write {
            writeIntLittle(hash)
            writeShort(-1)
            writeShortAdd(-1)
        })
        assertEquals(-1, button.itemId)
        assertEquals(-1, button.slotId)

        val continued: IfContinue = decode(49, write {
            writeIntMiddle(hash)
            writeShortAddLittle(-1)
        })
        assertEquals(-1, continued.slotId)
    }

    @Test
    fun `a hash naming a whole interface decodes its component as -1`() {
        val wholeInterface = interfaceHash(750, 0xFFFF)

        val onNpc: IfOnNpc = decode(41, write {
            writeIntInverseMiddle(wholeInterface)
            writeShortAddLittle(1234)
            writeByteSubtract(0)
            writeShortAddLittle(-1)
            writeShortAdd(-1)
        })
        assertEquals(750, onNpc.interfaceId)
        assertEquals(-1, onNpc.componentId)

        val onIf: IfOnIf = decode(4, write {
            writeShortAddLittle(3)
            writeShortLittle(4)
            writeShortAddLittle(1050)
            writeIntLittle(wholeInterface)
            writeIntInverseMiddle(interfaceHash(762, 39))
            writeShortLittle(995)
        })
        assertEquals(-1, onIf.toComp, "the drop target is the interface itself")
        assertEquals(39, onIf.fromComp)

        val button: IfButton = decode(96, write {
            writeIntLittle(wholeInterface)
            writeShort(-1)
            writeShortAdd(-1)
        })
        assertEquals(750, button.interfaceId)
        assertEquals(-1, button.componentId)
    }
}
