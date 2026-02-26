package org.darkan.core.net.prot

import java.io.ByteArrayOutputStream

/**
 * Encodes a list of zone [ServerProt] sub-packets into a [ByteArray] suitable for
 * embedding in [UpdateZonePartialEnclosed].
 *
 * Each sub-packet is written as:
 *   1. One byte: the ordinal of the zone packet type (from the UpdateZonePacket enum)
 *   2. The encoded body bytes matching exactly what the Rev727 codec produces
 *
 * Ordinal mapping:
 * ```
 *  0 = CustomizeObject
 *  1 = RemoveGroundItem
 *  2 = CreateGroundItem
 *  3 = DestroyObject
 *  4 = CreateObject
 *  5 = MapProjAnim
 *  6 = MapProjAnimHalfSq
 *  7 = ObjectPrefetch
 *  8 = GroundItemReveal
 *  9 = GroundItemCount
 * 10 = SoundEffectTile
 * 11 = TileMessage
 * 12 = ObjAnim
 * 13 = MusicTrackTile
 * 14 = SpotAnim
 * ```
 */
object ZonePacketEncoder {

    fun encodeZoneSubPackets(packets: List<ServerProt>): ByteArray {
        val out = ByteArrayOutputStream(256)
        for (packet in packets) {
            encodeSubPacket(out, packet)
        }
        return out.toByteArray()
    }

    private fun encodeSubPacket(out: ByteArrayOutputStream, packet: ServerProt) {
        when (packet) {
            is CustomizeObject -> {
                out.write(0)
                encodeCustomizeObject(out, packet)
            }
            is RemoveGroundItem -> {
                out.write(1)
                encodeRemoveGroundItem(out, packet)
            }
            is CreateGroundItem -> {
                out.write(2)
                encodeCreateGroundItem(out, packet)
            }
            is DestroyObject -> {
                out.write(3)
                encodeDestroyObject(out, packet)
            }
            is CreateObject -> {
                out.write(4)
                encodeCreateObject(out, packet)
            }
            is MapProjAnim -> {
                out.write(5)
                encodeMapProjAnim(out, packet)
            }
            is MapProjAnimHalfSq -> {
                out.write(6)
                encodeMapProjAnimHalfSq(out, packet)
            }
            is ObjectPrefetch -> {
                out.write(7)
                encodeObjectPrefetch(out, packet)
            }
            is GroundItemReveal -> {
                out.write(8)
                encodeGroundItemReveal(out, packet)
            }
            is GroundItemCount -> {
                out.write(9)
                encodeGroundItemCount(out, packet)
            }
            is SoundEffectTile -> {
                out.write(10)
                encodeSoundEffectTile(out, packet)
            }
            is TileMessage -> {
                out.write(11)
                encodeTileMessage(out, packet)
            }
            is ObjAnim -> {
                out.write(12)
                encodeObjAnim(out, packet)
            }
            is MusicTrackTile -> {
                out.write(13)
                encodeMusicTrackTile(out, packet)
            }
            is SpotAnim -> {
                out.write(14)
                encodeSpotAnim(out, packet)
            }
            else -> error("Unknown zone packet type: ${packet::class.simpleName}")
        }
    }

    // --- Individual encoders matching Rev727ServerCodecsZone.kt exactly ---

    // opcode 84, VarByte
    private fun encodeCustomizeObject(out: ByteArrayOutputStream, p: CustomizeObject) {
        writeInt(out, p.objectId)
        writeByteAdd(out, (p.shape shl 2) or (p.rotation and 0x3))
        var flags = 0
        if (p.clear) {
            flags = flags or 0x1
        } else {
            if (p.modelIds != null) flags = flags or 0x2
            if (p.recolors != null) flags = flags or 0x4
            if (p.retextures != null) flags = flags or 0x8
        }
        writeByte(out, flags)
        writeByteAdd(out, (p.localX shl 4) or (p.localY and 0x7))
        if (!p.clear) {
            p.modelIds?.forEach { writeInt(out, it) }
            p.recolors?.forEach { writeShort(out, it.toInt() and 0xFFFF) }
            p.retextures?.forEach { writeShort(out, it.toInt() and 0xFFFF) }
        }
    }

    // opcode 125, size 3
    private fun encodeRemoveGroundItem(out: ByteArrayOutputStream, p: RemoveGroundItem) {
        writeByteInverse(out, (p.localX shl 4) or (p.localY and 0x7))
        writeShortAddLittle(out, p.itemId)
    }

    // opcode 27, size 5
    private fun encodeCreateGroundItem(out: ByteArrayOutputStream, p: CreateGroundItem) {
        writeShortAddLittle(out, p.amount)
        writeShortAdd(out, p.itemId)
        writeByteInverse(out, (p.localX shl 4) or (p.localY and 0x7))
    }

    // opcode 69, size 2
    private fun encodeDestroyObject(out: ByteArrayOutputStream, p: DestroyObject) {
        writeByteAdd(out, (p.shape shl 2) or (p.rotation and 0x3))
        writeByte(out, (p.localX shl 4) or (p.localY and 0x7))
    }

    // opcode 117, size 6
    private fun encodeCreateObject(out: ByteArrayOutputStream, p: CreateObject) {
        writeInt(out, p.objectId)
        writeByteSubtract(out, (p.localX shl 4) or (p.localY and 0x7))
        writeByteSubtract(out, (p.shape shl 2) or (p.rotation and 0x3))
    }

    // opcode 143, size 16
    private fun encodeMapProjAnim(out: ByteArrayOutputStream, p: MapProjAnim) {
        val flags = (if (p.useFloorHeight) 0x80 else 0) or ((p.localX and 0x7) shl 3) or (p.localY and 0x7)
        writeByte(out, flags)
        writeByte(out, p.xOffset)
        writeByte(out, p.yOffset)
        writeShort(out, p.lockOn)
        writeShort(out, p.spotAnimId)
        writeByte(out, p.startHeight / 4)
        writeByte(out, p.endHeight / 4)
        writeShort(out, p.startTime)
        writeShort(out, p.endTime)
        writeByte(out, if (p.angle == -1) 255 else p.angle)
        writeShort(out, p.slope)
    }

    // opcode 95, size 19
    private fun encodeMapProjAnimHalfSq(out: ByteArrayOutputStream, p: MapProjAnimHalfSq) {
        val coords = ((p.localX and 0xF) shl 4) or (p.localY and 0xF)
        writeByte(out, coords)
        val hasBas = p.basOffIdx != -1
        val flags = (if (p.useFloorHeight) 0x1 else 0) or (if (hasBas) 0x2 else 0) or (if (hasBas) (p.basOffIdx shl 2) else 0)
        writeByte(out, flags)
        writeByte(out, p.xOffset)
        writeByte(out, p.yOffset)
        writeShort(out, p.source)
        writeShort(out, p.lockOn)
        writeShort(out, p.spotAnimId)
        if (hasBas) {
            writeByte(out, p.startHeight)
        } else {
            writeByte(out, p.startHeight / 4)
        }
        writeByte(out, p.endHeight / 4)
        writeShort(out, p.startTime)
        writeShort(out, p.endTime)
        writeByte(out, if (p.angle == -1) 255 else p.angle)
        writeShort(out, p.slope)
    }

    // opcode 88, size 5
    private fun encodeObjectPrefetch(out: ByteArrayOutputStream, p: ObjectPrefetch) {
        writeInt(out, p.objectId)
        writeByte(out, p.typeIndex)
    }

    // opcode 107, size 7
    private fun encodeGroundItemReveal(out: ByteArrayOutputStream, p: GroundItemReveal) {
        writeShort(out, p.playerId)
        writeByte(out, (p.localX shl 4) or (p.localY and 0x7))
        writeShortAddLittle(out, p.amount)
        writeShortAddLittle(out, p.itemId)
    }

    // opcode 3, size 7
    private fun encodeGroundItemCount(out: ByteArrayOutputStream, p: GroundItemCount) {
        writeByte(out, (p.localX shl 4) or (p.localY and 0x7))
        writeShort(out, p.itemId)
        writeShort(out, p.oldAmount)
        writeShort(out, p.newAmount)
    }

    // opcode 128, size 8
    private fun encodeSoundEffectTile(out: ByteArrayOutputStream, p: SoundEffectTile) {
        writeByte(out, (p.localX shl 4) or (p.localY and 0x7))
        writeShort(out, if (p.soundId == -1) 65535 else p.soundId)
        writeByte(out, ((p.range and 0xF) shl 4) or (p.mode and 0x7))
        writeByte(out, p.delay)
        writeByte(out, p.volume)
        writeShort(out, p.sampleRate)
    }

    // opcode 114, VarByte
    private fun encodeTileMessage(out: ByteArrayOutputStream, p: TileMessage) {
        writeByte(out, 0) // Unused
        writeByte(out, (p.localX shl 4) or (p.localY and 0x7))
        writeShort(out, p.priority)
        writeByte(out, p.duration)
        writeMedium(out, p.color)
        writeRSString(out, p.message)
    }

    // opcode 113, size 6
    private fun encodeObjAnim(out: ByteArrayOutputStream, p: ObjAnim) {
        writeByteSubtract(out, (p.shape shl 2) or (p.rotation and 0x3))
        writeByteSubtract(out, (p.localX shl 4) or (p.localY and 0x7))
        writeIntLittle(out, p.animationId)
    }

    // opcode 93, size 11
    private fun encodeMusicTrackTile(out: ByteArrayOutputStream, p: MusicTrackTile) {
        writeByteSubtract(out, p.near)
        writeByte(out, p.volume)
        writeByteInverse(out, p.far)
        writeInt(out, p.musicArchive)
        writeIntMiddle(out, (p.plane shl 28) or (p.x shl 14) or (p.y and 0x3FFF))
    }

    // opcode 139, size 8
    private fun encodeSpotAnim(out: ByteArrayOutputStream, p: SpotAnim) {
        writeByte(out, (p.localX shl 4) or (p.localY and 0x7))
        writeShort(out, if (p.spotAnimId == -1) 65535 else p.spotAnimId)
        writeShort(out, p.height)
        writeShort(out, p.speed)
        writeByte(out, p.rotation)
    }

    // --- Byte-level write helpers matching JagExtensions.kt transformations ---

    private fun writeByte(out: ByteArrayOutputStream, value: Int) {
        out.write(value)
    }

    private fun writeByteAdd(out: ByteArrayOutputStream, value: Int) {
        out.write(value + 128)
    }

    private fun writeByteInverse(out: ByteArrayOutputStream, value: Int) {
        out.write(-value)
    }

    private fun writeByteSubtract(out: ByteArrayOutputStream, value: Int) {
        out.write(-value + 128)
    }

    private fun writeShort(out: ByteArrayOutputStream, value: Int) {
        out.write(value shr 8)
        out.write(value)
    }

    private fun writeShortAdd(out: ByteArrayOutputStream, value: Int) {
        out.write(value shr 8)
        writeByteAdd(out, value)
    }

    private fun writeShortAddLittle(out: ByteArrayOutputStream, value: Int) {
        writeByteAdd(out, value)
        out.write(value shr 8)
    }

    private fun writeInt(out: ByteArrayOutputStream, value: Int) {
        out.write(value shr 24)
        out.write(value shr 16)
        out.write(value shr 8)
        out.write(value)
    }

    private fun writeIntLittle(out: ByteArrayOutputStream, value: Int) {
        out.write(value)
        out.write(value shr 8)
        out.write(value shr 16)
        out.write(value shr 24)
    }

    private fun writeIntMiddle(out: ByteArrayOutputStream, value: Int) {
        out.write(value shr 8)
        out.write(value)
        out.write(value shr 24)
        out.write(value shr 16)
    }

    private fun writeMedium(out: ByteArrayOutputStream, value: Int) {
        out.write(value shr 16)
        out.write(value shr 8)
        out.write(value)
    }

    private fun writeRSString(out: ByteArrayOutputStream, value: String) {
        out.write(value.toByteArray())
        out.write(0)
    }
}
