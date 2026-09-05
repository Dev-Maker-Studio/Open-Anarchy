package org.darkan.core.net.prot.revision.rev727

import io.ktor.utils.io.*
import org.darkan.core.net.prot.*
import org.darkan.core.net.prot.packet.*
import org.darkan.core.toInterfaceHash
import world.gregs.voidps.buffer.*

internal fun Codec.registerRev727IfPackets() {
    serverProt<IfSetPlayerHead>(opcode = 0, size = 4) { out ->
        out.writeIntLittle(toInterfaceHash(interfaceId, componentId))
    }

    // Wire order is kit1, kit3, kit2: the client packs fields 1 and 3 into
    // primaryMediaId (primary/secondary head) and field 2 into secondaryMediaId
    // (tertiary head).
    serverProt<IfSetPlayerHeadIgnoreWorn>(opcode = 130, size = 10) { out ->
        out.writeShortLittle(identiKit1)
        out.writeShortAdd(identiKit3)
        out.writeShortAdd(identiKit2)
        out.writeIntInverseMiddle(toInterfaceHash(interfaceId, componentId))
    }

    serverProt<IfSetTextFont>(opcode = 7, size = 8) { out ->
        out.writeInt(fontId)
        out.writeIntMiddle(toInterfaceHash(interfaceId, componentId))
    }

    serverProt<IfMoveSub>(opcode = 13, size = 8) { out ->
        out.writeIntInverseMiddle(toInterfaceHash(fromInterfaceId, fromComponentId))
        out.writeInt(toInterfaceHash(toInterfaceId, toComponentId))
    }

    serverProt<IfOpenSubActiveObject>(opcode = 23, size = 32) { out ->
        out.writeIntInverseMiddle(toInterfaceHash(interfaceId, componentId))
        out.writeIntInverseMiddle(xteas[2])
        out.writeByteAdd(if (overlay) 1 else 0)
        out.writeInt(xteas[1])
        out.writeIntMiddle(xteas[0])
        out.writeShortAdd(subInterfaceId)
        out.writeByteSubtract((type shl 2) or (rotation and 0x3))
        out.writeInt(xteas[3])
        out.writeIntInverseMiddle(tile.id)
        out.writeInt(objectId)
    }

    serverProt<IfSetHide>(opcode = 33, size = 5) { out ->
        out.writeByteAdd(if (hidden) 1 else 0)
        out.writeInt(toInterfaceHash(interfaceId, componentId))
    }

    serverProt<IfOpenSubActivePlayer>(opcode = 59, size = 25) { out ->
        out.writeIntLittle(xteas[3])
        out.writeByte(if (overlay) 1 else 0)
        out.writeIntMiddle(xteas[0])
        out.writeShort(subInterfaceId)
        out.writeInt(xteas[1])
        out.writeIntMiddle(toInterfaceHash(interfaceId, componentId))
        out.writeIntMiddle(xteas[2])
        out.writeShortLittle(playerIndex)
    }

    serverProt<IfSetGraphic>(opcode = 35, size = 8) { out ->
        out.writeIntInverseMiddle(graphicId)
        out.writeIntInverseMiddle(toInterfaceHash(interfaceId, componentId))
    }

    serverProt<IfSetTextAntiMacro>(opcode = 36, size = 5) { out ->
        out.writeByteSubtract(if (enabled) 1 else 0)
        out.writeIntInverseMiddle(toInterfaceHash(interfaceId, componentId))
    }

    serverProt<IfOpenTop>(opcode = 37, size = 19) { out ->
        out.writeIntMiddle(xteas[2])
        out.writeIntMiddle(xteas[3])
        out.writeShortAdd(interfaceId)
        out.writeByteSubtract(type)
        // The client reads these two with big-endian readInt(), not readUIntLittle().
        out.writeInt(xteas[1])
        out.writeInt(xteas[0])
    }

    serverProt<IfOpenSub>(opcode = 38, size = 23) { out ->
        out.writeInt(xteas[0])
        out.writeIntInverseMiddle(xteas[2])
        out.writeIntInverseMiddle(xteas[1])
        out.writeShortAddLittle(subInterfaceId)
        out.writeIntLittle(xteas[3])
        out.writeInt(toInterfaceHash(interfaceId, componentId))
        out.writeByteInverse(if (overlay) 1 else 0)
    }

    serverProt<IfSetAngle>(opcode = 44, size = 10) { out ->
        out.writeInt(toInterfaceHash(interfaceId, componentId))
        out.writeShortLittle(pitch)
        out.writeShortLittle(roll)
        out.writeShortAddLittle(scale)
    }

    serverProt<IfSetPosition>(opcode = 48, size = 8) { out ->
        out.writeShortAdd(y)
        out.writeIntInverseMiddle(toInterfaceHash(interfaceId, componentId))
        out.writeShortAddLittle(x)
    }

    serverProt<IfSetPlayerModelOther>(opcode = 53, size = 10) { out ->
        out.writeShortAdd(primaryModelId)
        out.writeIntMiddle(toInterfaceHash(interfaceId, componentId))
        out.writeIntMiddle(secondaryModelId)
    }

    serverProt<IfSetScrollPos>(opcode = 55, size = 6) { out ->
        out.writeShort(scrollPos)
        out.writeInt(toInterfaceHash(interfaceId, componentId))
    }

    serverProt<IfOpenSubActiveNpc>(opcode = 34, size = 25) { out ->
        out.writeShortAdd(npcIndex)
        out.writeIntLittle(xteas[1])
        out.writeByteInverse(if (overlay) 1 else 0)
        out.writeInt(toInterfaceHash(interfaceId, componentId))
        out.writeIntLittle(xteas[3])
        out.writeShortAddLittle(subInterfaceId)
        out.writeInt(xteas[2])
        out.writeInt(xteas[0])
    }

    serverProt<IfSetPlayerModel>(opcode = 72, size = 4) { out ->
        out.writeIntMiddle(toInterfaceHash(interfaceId, componentId))
    }

    serverProt<IfCloseSub>(opcode = 78, size = 4) { out ->
        out.writeInt(toInterfaceHash(interfaceId, componentId))
    }

    serverProt<IfSetAnim>(opcode = 82, size = 8) { out ->
        out.writeIntInverseMiddle(animationId)
        out.writeInt(toInterfaceHash(interfaceId, componentId))
    }

    serverProt<IfSetColor>(opcode = 105, size = 6) { out ->
        out.writeIntLittle(toInterfaceHash(interfaceId, componentId))
        out.writeShortAdd(color)
    }

    serverProt<IfSetNpcHead>(opcode = 109, size = 8) { out ->
        out.writeIntLittle(toInterfaceHash(interfaceId, componentId))
        out.writeIntMiddle(npcId)
    }

    serverProt<IfSetItem>(opcode = 112, size = 10) { out ->
        out.writeShort(if (itemId == -1) 65535 else itemId)
        out.writeInt(toInterfaceHash(interfaceId, componentId))
        out.writeIntInverseMiddle(amount)
    }

    serverProt<IfSetEvents>(opcode = 121, size = 12) { out ->
        out.writeShortAddLittle(if (toSlot == -1) 65535 else toSlot)
        out.writeIntInverseMiddle(toInterfaceHash(interfaceId, componentId))
        out.writeShort(if (fromSlot == -1) 65535 else fromSlot)
        out.writeIntLittle(settings)
    }

    serverProt<IfSetText>(opcode = 124, size = ProtSize.VarShort) { out ->
        out.writeRSString(text)
        out.writeIntMiddle(toInterfaceHash(interfaceId, componentId))
    }

    // The client reads the destination texture first (little endian) and the
    // source texture second (big endian) - the opposite order to IF_SETRECOL.
    serverProt<IfSetReTex>(opcode = 132, size = 9) { out ->
        out.writeShortLittle(toTextureId)
        out.writeShort(fromTextureId)
        out.writeByteSubtract(index)
        out.writeIntLittle(toInterfaceHash(interfaceId, componentId))
    }

    serverProt<IfSetModel>(opcode = 135, size = 8) { out ->
        out.writeInt(toInterfaceHash(interfaceId, componentId))
        out.writeIntInverseMiddle(modelId)
    }

    serverProt<IfSetReCol>(opcode = 144, size = 9) { out ->
        out.writeIntInverseMiddle(toInterfaceHash(interfaceId, componentId))
        out.writeShortLittle(fromColor)
        out.writeShortLittle(toColor)
        out.writeByte(index)
    }

    serverProt<IfSetTargetParam>(opcode = 148, size = 10) { out ->
        out.writeIntMiddle(toInterfaceHash(interfaceId, componentId))
        out.writeShortAddLittle(targetInterfaceId)
        out.writeShort(if (toSlot == -1) 65535 else toSlot)
        out.writeShortAdd(if (fromSlot == -1) 65535 else fromSlot)
    }

    serverProt<IfSetClickMask>(opcode = 150, size = 5) { out ->
        out.writeByteSubtract(if (enabled) 1 else 0)
        out.writeIntLittle(toInterfaceHash(interfaceId, componentId))
    }

    serverProt<IfOpenSubActiveGroundItem>(opcode = 155, size = 29) { out ->
        out.writeIntInverseMiddle(toInterfaceHash(interfaceId, componentId))
        out.writeIntLittle(xteas[2])
        out.writeShortLittle(subInterfaceId)
        out.writeInt(xteas[3])
        out.writeIntMiddle(xteas[0])
        out.writeByteSubtract(if (overlay) 1 else 0)
        out.writeIntMiddle(tile.id)
        out.writeIntMiddle(xteas[1])
        out.writeShortAddLittle(itemId)
    }

    // OUTDATED_SET_THEORA_STRING_SOMETHING: queues a T17 pulse event on a component.
    serverProt<OutdatedSetTheoraString>(opcode = 20, size = 6) { out ->
        out.writeShortAdd(value)
        out.writeIntLittle(toInterfaceHash(interfaceId, componentId))
    }

    // OUTDATED_INTERFACE_PULSE_EVENT: queues a T13 pulse event on a component.
    // The client rebuilds the payload as `low + (high shl 16)`.
    serverProt<OutdatedInterfacePulseEvent>(opcode = 96, size = 8) { out ->
        out.writeShortAdd(value and 0xFFFF)
        out.writeIntInverseMiddle(toInterfaceHash(interfaceId, componentId))
        out.writeShortLittle((value shr 16) and 0xFFFF)
    }

    serverProt<IfSetPlayerHeadOther>(opcode = 158, size = 10) { out ->
        out.writeInt(key)
        out.writeShortAddLittle(value)
        out.writeIntMiddle(toInterfaceHash(interfaceId, componentId))
    }
}
