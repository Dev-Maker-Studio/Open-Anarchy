package org.darkan.core.net.prot.revision.rev727

import io.ktor.utils.io.*
import org.darkan.core.net.prot.*
import org.darkan.core.net.prot.packet.*
import world.gregs.voidps.buffer.*

internal fun Codec.registerRev727ZonePackets() {
    serverProt<UpdateZoneFullFollows>(opcode = 15, size = 3) { out ->
        out.writeByteSubtract(chunkX)
        out.writeByte(chunkY)
        out.writeByte(plane)
    }

    serverProt<UpdateZonePartialFollows>(opcode = 41, size = 3) { out ->
        out.writeByteAdd(chunkY)
        out.writeByteAdd(plane)
        out.writeByteAdd(chunkX)
    }

    serverProt<UpdateZonePartialEnclosed>(opcode = 65, size = ProtSize.VarShort) { out ->
        out.writeByteSubtract(chunkY)
        out.writeByteAdd(plane)
        out.writeByte(chunkX)
        out.writeBytes(data)
    }

    serverProt<TileMessage>(opcode = 114, size = ProtSize.VarByte) { out ->
        out.writeByte(0) // Unused
        out.writeByte(((localX and 0x7) shl 4) or (localY and 0x7))
        out.writeShort(priority)
        out.writeByte(duration)
        out.writeMedium(color)
        out.writeRSString(message)
    }

    // Ground item protocols
    serverProt<GroundItemCount>(opcode = 3, size = 7) { out ->
        out.writeByte(((localX and 0x7) shl 4) or (localY and 0x7))
        out.writeShort(itemId)
        out.writeShort(oldAmount)
        out.writeShort(newAmount)
    }

    serverProt<CreateGroundItem>(opcode = 27, size = 5) { out ->
        out.writeShortAddLittle(amount)
        out.writeShortAdd(itemId)
        out.writeByteInverse(((localX and 0x7) shl 4) or (localY and 0x7))
    }

    serverProt<GroundItemReveal>(opcode = 107, size = 7) { out ->
        out.writeShort(playerId)
        out.writeByte(((localX and 0x7) shl 4) or (localY and 0x7))
        out.writeShortAddLittle(amount)
        out.writeShortAddLittle(itemId)
    }

    serverProt<RemoveGroundItem>(opcode = 125, size = 3) { out ->
        out.writeByteInverse(((localX and 0x7) shl 4) or (localY and 0x7))
        out.writeShortAddLittle(itemId)
    }

    // Object protocols
    serverProt<DestroyObject>(opcode = 69, size = 2) { out ->
        out.writeByteAdd((shape shl 2) or (rotation and 0x3))
        out.writeByte(((localX and 0x7) shl 4) or (localY and 0x7))
    }

    serverProt<CreateObject>(opcode = 117, size = 6) { out ->
        out.writeInt(objectId)
        out.writeByteSubtract(((localX and 0x7) shl 4) or (localY and 0x7))
        out.writeByteSubtract((shape shl 2) or (rotation and 0x3))
    }

    serverProt<CustomizeObject>(opcode = 84, size = ProtSize.VarByte) { out ->
        out.writeInt(objectId)
        out.writeByteAdd((shape shl 2) or (rotation and 0x3))
        var flags = 0
        if (clear) {
            flags = flags or 0x1
        } else {
            if (modelIds != null) flags = flags or 0x2
            if (recolors != null) flags = flags or 0x4
            if (retextures != null) flags = flags or 0x8
        }
        out.writeByte(flags)
        out.writeByteAdd(((localX and 0x7) shl 4) or (localY and 0x7))
        if (!clear) {
            modelIds?.forEach { out.writeInt(it) }
            recolors?.forEach { out.writeShort(it.toInt() and 0xFFFF) }
            retextures?.forEach { out.writeShort(it.toInt() and 0xFFFF) }
        }
    }

    serverProt<ObjectPrefetch>(opcode = 88, size = 5) { out ->
        out.writeInt(objectId)
        out.writeByte(typeIndex)
    }

    serverProt<ObjAnim>(opcode = 113, size = 6) { out ->
        out.writeByteSubtract((shape shl 2) or (rotation and 0x3))
        out.writeByteSubtract(((localX and 0x7) shl 4) or (localY and 0x7))
        out.writeIntLittle(animationId)
    }

    serverProt<ObjAnimSpecific>(opcode = 80, size = 9) { out ->
        out.writeIntInverseMiddle(tile.id)
        out.writeIntLittle(animationId)
        out.writeByteSubtract((shape shl 2) or (rotation and 0x3))
    }

    // Spot anim and projectile protocols
    serverProt<SpotAnim>(opcode = 139, size = 8) { out ->
        out.writeByte(((localX and 0x7) shl 4) or (localY and 0x7))
        out.writeShort(if (spotAnimId == -1) 65535 else spotAnimId)
        out.writeShort(height)
        out.writeShort(speed)
        out.writeByte(rotation)
    }

    serverProt<SpotAnimSpecific>(opcode = 126, size = 12) { out ->
        out.writeByteInverse(slot)
        out.writeShortAdd(if (spotAnimId == -1) 65535 else spotAnimId)
        out.writeByteInverse(settings)
        out.writeShortAdd(speed)
        out.writeIntLittle(targetHash)
        out.writeShortLittle(height)
    }

    serverProt<MapProjAnim>(opcode = 143, size = 16) { out ->
        val flags = (if (useFloorHeight) 0x80 else 0) or ((localX and 0x7) shl 3) or (localY and 0x7)
        out.writeByte(flags)
        out.writeByte(xOffset)
        out.writeByte(yOffset)
        out.writeShort(lockOn)
        out.writeShort(spotAnimId)
        out.writeByte(startHeight / 4)
        out.writeByte(endHeight / 4)
        out.writeShort(startTime)
        out.writeShort(endTime)
        out.writeByte(if (angle == -1) 255 else angle)
        out.writeShort(slope)
    }

    // The client frames opcode 95 with ServerProt size 17 but decodes 19 bytes
    // (its two size tables contradict each other), so a standalone send always
    // desyncs the stream. Send this enclosed in UpdateZonePartialEnclosed, where
    // the sub-packet stream is self-delimiting and 19 is correct.
    serverProt<MapProjAnimHalfSq>(opcode = 95, size = 19) { out ->
        val coords = ((localX and 0xF) shl 4) or (localY and 0xF)
        out.writeByte(coords)
        val hasBas = basOffIdx != -1
        val flags = (if (useFloorHeight) 0x1 else 0) or (if (hasBas) 0x2 else 0) or (if (hasBas) (basOffIdx shl 2) else 0)
        out.writeByte(flags)
        out.writeByte(xOffset)
        out.writeByte(yOffset)
        out.writeShort(source)
        out.writeShort(lockOn)
        out.writeShort(spotAnimId)
        if (hasBas) {
            out.writeByte(startHeight)
        } else {
            out.writeByte(startHeight / 4)
        }
        out.writeByte(endHeight / 4)
        out.writeShort(startTime)
        out.writeShort(endTime)
        out.writeByte(if (angle == -1) 255 else angle)
        out.writeShort(slope)
    }

    serverProt<ProjAnimSpecific>(opcode = 56, size = 22) { out ->
        out.writeByteAdd(endHeight / 4)
        out.writeShortAddLittle(source)
        out.writeShortLittle(endTime)
        out.writeShort(lockOn)
        out.writeByteAdd(flags)
        out.writeShortAdd(y)
        out.writeShort(slope)
        out.writeShortAddLittle(startTime)
        out.writeShortAddLittle(spotAnimId)
        out.writeByteAdd(if (angle == -1) 255 else angle)
        out.writeByteInverse(xDist)
        out.writeByteInverse(yDist)
        out.writeShortAdd(x)
        // The client scales startHeight by 4 unless the BAS-offset flag (0x2) is
        // set, in which case it reinterprets the byte as signed - same rule as
        // MapProjAnimHalfSq, and it must match the `endHeight / 4` above.
        out.writeByteInverse(if ((flags and 0x2) != 0) startHeight else startHeight / 4)
    }

    // Sound protocols (zone-specific)
    // The client frames opcode 128 with ServerProt size 6 but decodes 8 bytes -
    // same self-contradiction as opcode 95. Send this enclosed in
    // UpdateZonePartialEnclosed rather than standalone.
    serverProt<SoundEffectTile>(opcode = 128, size = 8) { out ->
        out.writeByte(((localX and 0x7) shl 4) or (localY and 0x7))
        out.writeShort(if (soundId == -1) 65535 else soundId)
        out.writeByte(((range and 0xF) shl 4) or (mode and 0x7))
        out.writeByte(delay)
        out.writeByte(volume)
        out.writeShort(sampleRate)
    }

    serverProt<MusicTrackTile>(opcode = 93, size = 11) { out ->
        out.writeByteSubtract(near)
        out.writeByte(volume)
        out.writeByteInverse(far)
        out.writeInt(musicArchive)
        out.writeIntMiddle(((plane and 0x3) shl 28) or ((x and 0x3FFF) shl 14) or (y and 0x3FFF))
    }

    serverProt<AnimateNpc>(opcode = 145, size = 19) { out ->
        out.writeShortAddLittle(npcIndex)
        for (id in animationIds) {
            out.writeIntLittle(id)
        }
        out.writeByte(speed)
    }
}
