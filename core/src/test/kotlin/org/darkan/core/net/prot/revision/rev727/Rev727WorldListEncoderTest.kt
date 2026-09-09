package org.darkan.core.net.prot.revision.rev727

import io.ktor.utils.io.*
import kotlinx.coroutines.runBlocking
import org.darkan.core.net.prot.Codec
import org.darkan.core.net.prot.dto.WorldListEntry
import org.darkan.core.net.prot.dto.WorldListSnapshot
import org.darkan.core.net.prot.packet.WorldListPacket
import world.gregs.voidps.buffer.read.BufferReader
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class Rev727WorldListEncoderTest {

    private lateinit var codec: Codec

    @BeforeTest
    fun setUp() {
        codec = register727()
    }

    private fun entry(number: Int, customCache: Boolean) = WorldListEntry(
        number = number, index = number - 1, countryId = 0, activity = "", address = "localhost", port = 43595,
        members = true, quickChat = false, botsAllowed = false, lootShare = true, highlighted = false,
        playersOnline = 0, customCache = customCache,
    )

    @Test
    fun `a world serving its own cache sets flag bit 5 and nothing else changes`() {
        val packet = WorldListPacket(WorldListSnapshot(listOf(entry(1, false), entry(2, true)), maxWorlds = 300, revision = 10), refreshOnClient = true, refreshOnlyPlayerCounts = false)
        val reader = BufferReader(encode(packet))
        assertEquals(1, reader.readUnsignedByte())
        assertEquals(2, reader.readUnsignedByte())
        assertEquals(1, reader.readUnsignedByte())
        assertEquals(2, reader.readSmart())
        repeat(2) { reader.readSmart(); reader.readJagString() }
        reader.readSmart(); reader.readSmart(); reader.readSmart()
        val flagsByWorld = (0 until 2).associate {
            val number = reader.readSmart()
            reader.readUnsignedByte()
            val flags = reader.readInt()
            reader.readJagString(); reader.readJagString()
            number to flags
        }
        assertEquals(0x1 or 0x8, flagsByWorld[1])
        assertEquals(0x1 or 0x8 or 0x20, flagsByWorld[2])
    }

    private fun BufferReader.readJagString(): String {
        assertEquals(0, readUnsignedByte())
        return readString()
    }

    private fun encode(packet: WorldListPacket): ByteArray = runBlocking {
        val channel = ByteChannel(autoFlush = true)
        codec.encodeServerProt(packet, channel)
        channel.flush()
        val bytes = ByteArray(channel.availableForRead)
        channel.readFully(bytes)
        channel.close()
        bytes
    }
}
