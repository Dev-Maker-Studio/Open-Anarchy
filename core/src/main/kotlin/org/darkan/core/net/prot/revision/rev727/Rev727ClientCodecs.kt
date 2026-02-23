package org.darkan.core.net.prot.revision.rev727

import io.ktor.utils.io.core.remaining
import kotlinx.io.readByteArray
import kotlinx.io.readUByte
import kotlinx.io.readUShort
import org.darkan.core.clientwatch.MouseTrailStep
import org.darkan.core.clientwatch.MouseTrailStep.Type
import org.darkan.core.clientwatch.ReflectionResponseCode
import org.darkan.core.model.Preference
import org.darkan.core.net.prot.*
import world.gregs.voidps.buffer.*
import org.darkan.core.fixChatMessage
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.type.Tile

internal fun Codec.registerRev727ClientProts() {
    //Utility/clientwatch
    clientProt<Ping>(opcode = 0)

    clientProt(opcode = 39, size = ProtSize.VarByte) {
        readUByte() // client-supplied payload length for the remaining fields; ignore
        UnusedClanOp(
            pid = readUShort().toInt(),
            username = readRSString()
        )
    }

    clientProt(opcode = 36, size = ProtSize.VarByte) {
        val responseCode = ReflectionResponseCode.fromId(readByte().toInt()) ?: throw IllegalArgumentException("Invalid response code")
        ReflectionResponse(responseCode, readByteArray(remaining.toInt()))
    }

    clientProt(opcode = 62, size = 1) { ClientFocus(readBoolean()) }

    clientProt(opcode = 84, size = 6) {
        ClientScreenSize(
            displayMode = readUByte().toInt(),
            width = readUShort().toInt(),
            height = readUShort().toInt(),
            switchDisplayMode = readBoolean()
        )
    }

    clientProt(opcode = 83, size = 4) {
        MoveCamera(angleX = readShortAddLittle().toInt(), angleY = readShortAdd().toInt())
    }

    clientProt(opcode = 44, size = 9) { //TODO test
        SendFps(
            key1 = readIntInverseMiddle(),
            key2 = readUIntMiddle(),
            fps = readByteAdd().toInt()
        )
    }

    clientProt(opcode = 59, size = 6) {
        val positionHash = readUIntLittle()
        val mouseHash = readShort()
        MouseClick(
            mouseButton = mouseHash.toInt() ushr 15,
            time = mouseHash.toInt() and 0x7FFF,
            x = positionHash and 0xFFFF,
            y = positionHash ushr 16
        )
    }

    clientProt(opcode = 57, size = 7) {
        val positionHash = readUIntLittle()
        val flags = readByteAdd()
        MouseButtonClick(
            mouseButton = flags ushr 1,
            time = readUShortLittle(),
            x = positionHash and 0xFFFF,
            y = positionHash ushr 16,
            hw = (flags and 1) == 1
        )
    }

    clientProt(opcodes = intArrayOf(40, 78), size = ProtSize.VarByte) { opcode ->
        val steps = mutableListOf<MouseTrailStep>()
        val frameSteps = readUByte().toInt()
        val frameCount = readUByte().toInt()
        while (remaining > 0) {
            val firstByte = readByte().toUByte().toInt()

            var frames: Int
            var dX: Int
            var dY: Int
            var type = Type.MOVE_OFFSET

            when {
                firstByte >= 224 -> {
                    val secondByte = readByte().toUByte().toInt()
                    frames = ((firstByte shl 8) or secondByte) - 57344
                    val posHash = readInt()
                    dY = posHash shr 16
                    dX = posHash and 0xFFFF
                    type = Type.SET_POSITION
                }
                firstByte >= 192 -> {
                    frames = firstByte - 192
                    val posHash = readInt()
                    dY = posHash shr 16
                    dX = posHash and 0xFFFF
                    type = Type.SET_POSITION
                }
                firstByte >= 128 -> {
                    frames = firstByte - 128
                    val posHash = readUShort().toInt()
                    dY = posHash shr 8
                    dX = posHash and 0xFF
                }
                else -> {
                    val secondByte = readByte().toUByte().toInt()
                    val hash = (firstByte shl 8) or secondByte
                    frames = hash shr 12
                    dX = (hash shr 6) and 0x3F
                    dY = hash and 0x3F
                }
            }

            steps.add(MouseTrailStep(type, frames, dX, dY, if (opcode == 40) readBoolean() else true))
        }
        MoveMouseData(
            steps = steps.toTypedArray(),
            frameCount = frameCount,
            frameSteps = frameSteps
        )
    }

    clientProt(opcode = 28, size = ProtSize.VarShort) { KeyPress(keyCode = readByte().toInt(), time = readMedium()) }

    clientProt(opcode = 85, size = ProtSize.VarByte) { ClientCheat(client = readBoolean(), command = readRSString()) }

    // Entity interactions
    val playerOpOpcodes = intArrayOf(66, 6, 31, 89, 103, 1, 51, 94, 53, 70)
    clientProt(opcodes = playerOpOpcodes, size = 3) { opcode ->
        OpPlayer(
            opNum = playerOpOpcodes.indexOf(opcode),
            playerIndex = readShort().toInt(),
            forceRun = readBooleanSubtract()
        )
    }

    val npcOpOpcodes = intArrayOf(65, 16, 50, 77, 95, 3)
    clientProt(opcodes = npcOpOpcodes, size = 3) { opcode ->
        OpNpc(
            opNum = npcOpOpcodes.indexOf(opcode),
            npcIndex = readUShort().toInt(),
            forceRun = readBoolean()
        )
    }

    val objOpOpcodes = intArrayOf(75, 93, 38, 32, 48, 73)
    clientProt(opcodes = objOpOpcodes, size = 9) { opcode ->
        OpObj(
            opNum = objOpOpcodes.indexOf(opcode),
            y = readUShort().toInt(),
            x = readUShort().toInt(),
            objectId = readInt(),
            forceRun = readBooleanAdd()
        )
    }

    val groundItemOpOpcodes = intArrayOf(24, 25, 54, 8, 43, 61)
    clientProt(opcodes = groundItemOpOpcodes, size = 7) { opcode ->
        OpGroundItem(
            opNum = groundItemOpOpcodes.indexOf(opcode),
            itemId = readUShortAddLittle(),
            forceRun = readBooleanInverse(),
            y = readUShort().toInt(),
            x = readUShortAdd()
        )
    }

    clientProt(opcode = 33, size = 5) {
        val forceRun = readBoolean()
        val x = readUShort().toInt()
        val y = readUShortLittle()
        Walk(x, y, forceRun, false)
    }

    clientProt(opcode = 42, size = 18) {
        val forceRun = readBoolean()
        val x = readUShort().toInt()
        val y = readUShortLittle()
        readByte()
        readByte() //always -1
        val camAngle = readUShort().toInt()
        readByte() //always 57
        val minimapRotation = readUByte() //TODO
        val minimapZoom = readUByte()
        readByte() //always 89
        val absX = readUShort().toInt()
        val absY = readUShort().toInt()
        readByte() //always 63
        Walk(x, y, forceRun, true)
    }

    // Interface interactions
    val ifButtonOpcodes = intArrayOf(96, 27, 68, 9, 72, 19, 23, 21, 22, 81)
    clientProt(opcodes = ifButtonOpcodes, size = 8) { opcode ->
        val interfaceHash = readUIntLittle()
        IfButton(
            opNum = ifButtonOpcodes.indexOf(opcode) + 1,
            interfaceId = interfaceHash shr 16,
            componentId = interfaceHash and 0xFFFF,
            slotId = readUShort().toInt(),
            itemId = readShortAdd()
        )
    }

    clientProt(opcode = 4, size = 16) {
        val toSlot = readShortAddLittle()
        val fromSlot = readUShortLittle()
        val toItemId = readShortAddLittle()
        val toHash = readUIntLittle()
        val fromHash = readIntInverseMiddle()
        val fromItemId = readUShortLittle()
        IfOnIf(
            fromInter = fromHash shr 16,
            toInter = toHash shr 16,
            fromComp = fromHash and 0xFFFF,
            toComp = toHash and 0xFFFF,
            fromSlot = fromSlot,
            toSlot = toSlot,
            fromItemId = fromItemId,
            toItemId = toItemId
        )
    }

    clientProt(opcode = 98, size = 17) {
        val x = readShortAddLittle()
        val forceRun = readBooleanAdd()
        val objectId = readUIntMiddle()
        val interfaceHash = readInt()
        val itemId = readUShortLittle()
        val slotId = readShortAdd()
        val y = readUShortLittle()
        IfOnObject(
            interfaceId = interfaceHash shr 16,
            componentId = interfaceHash and 0xFFFF,
            slotId = slotId,
            itemId = itemId,
            objectId = objectId,
            x = x,
            y = y,
            forceRun = forceRun
        )
    }

    clientProt(opcode = 67, size = 15) {
        val itemIdContainer = readShortAdd()
        val interfaceHash = readIntInverseMiddle()
        val itemId = readShort().toInt()
        val forceRun = readBooleanAdd()
        val slotId = readShortAddLittle()
        val y = readShortAddLittle()
        val x = readUShortLittle()
        IfOnGroundItem(
            interfaceId = interfaceHash shr 16,
            componentId = interfaceHash and 0xFFFF,
            slotId = slotId,
            itemIdContainer = itemIdContainer,
            itemId = itemId,
            x = x,
            y = y,
            forceRun = forceRun
        )
    }

    clientProt(opcode = 41, size = 11) {
        val interfaceHash = readIntInverseMiddle()
        val npcIndex = readUShortAddLittle()
        val forceRun = readBooleanSubtract()
        val itemId = readUShortAddLittle()
        val slotId = readUShortAdd()
        IfOnNpc(
            interfaceId = interfaceHash shr 16,
            componentId = interfaceHash and 0xFFFF,
            slotId = slotId,
            itemId = itemId,
            npcIndex = npcIndex,
            forceRun = forceRun
        )
    }

    clientProt(opcode = 13, size = 11) {
        val slotId = readUShort().toInt()
        val playerIndex = readUShortLittle()
        val forceRun = readBooleanSubtract()
        val interfaceHash = readIntInverseMiddle()
        val itemId = readUShortLittle()
        IfOnPlayer(
            interfaceId = interfaceHash shr 16,
            componentId = interfaceHash and 0xFFFF,
            slotId = slotId,
            itemId = itemId,
            playerIndex = playerIndex,
            forceRun = forceRun
        )
    }

    clientProt(opcode = 46, size = 12) {
        val itemId = readUShortLittle()
        val y = readUShortLittle()
        val interfaceHash = readIntInverseMiddle()
        val slotId = readShortAdd()
        val x = readUShortLittle()
        IfOnTile(
            interfaceId = interfaceHash shr 16,
            componentId = interfaceHash and 0xFFFF,
            slotId = slotId,
            itemId = itemId,
            x = x,
            y = y
        )
    }

    clientProt(opcode = 49, size = 6) {
        val interfaceHash = readUIntMiddle()
        val slotId = readShortAddLittle()
        IfContinue(
            interfaceId = interfaceHash shr 16,
            componentId = interfaceHash and 0xFFFF,
            slotId = slotId
        )
    }

    clientProt(opcode = 74, size = 16) {
        val toSlot = readShortAddLittle()
        val fromSlot = readUShortLittle()
        val toItemId = readShort().toInt()
        val fromItemId = readShortAddLittle()
        val fromInterfaceHash = readUIntMiddle()
        val toInterfaceHash = readUIntLittle()
        IfDragOntoIf(
            fromInter = fromInterfaceHash shr 16,
            toInter = toInterfaceHash shr 16,
            fromComp = fromInterfaceHash and 0xFFFF,
            toComp = toInterfaceHash and 0xFFFF,
            fromSlot = fromSlot,
            toSlot = toSlot,
            fromItemId = fromItemId,
            toItemId = toItemId
        )
    }

    clientProt<CloseInterface>(opcode = 60)

    // Dialog interactions
    clientProt(opcode = 87, size = ProtSize.VarByte) { ResumeTextDialog(readRSString()) }
    clientProt(opcode = 80, size = ProtSize.VarByte) { ResumeNameDialog(readRSString()) }
    clientProt(opcode = 69, size = ProtSize.VarByte) { ResumeClanForumQFCDialog(readRSString()) }
    clientProt(opcode = 11, size = 2) { ResumeHSLDialog(readUShort().toInt()) }
    clientProt(opcode = 58, size = 4) { ResumeCountDialog(readInt()) }
    clientProt(opcode = 17, size = 2) { ResumeItemSelect(readShort().toInt()) }

    // Communication
    clientProt(opcode = 86, size = ProtSize.VarByte) {
        Chat(
            color = readUByte().toInt().coerceIn(0, 12),
            effect = readUByte().toInt().coerceIn(0, 5),
            message = (Cache.huffman.decompress(length = readSmart(), message = readByteArray(remaining.toInt())) ?: "").fixChatMessage()
        )
    }

    clientProt(opcode = 15, size = ProtSize.VarShort) {
        PrivateMessage(readRSString(), (Cache.huffman.decompress(length = readSmart(), message = readByteArray(remaining.toInt())) ?: "").fixChatMessage())
    }

    clientProt(opcode = 64, size = ProtSize.VarByte) {
        QuickChatPublic(
            chatType = readByte().toInt(),
            qcId = readUShort().toInt(),
            messageData = if (remaining > 0) readByteArray(remaining.toInt()) else null
        )
    }

    clientProt(opcode = 14, size = ProtSize.VarByte) {
        QuickChatPrivate(
            toUsername = readRSString(),
            qcId = readUShort().toInt(),
            messageData = if (remaining > 0) readByteArray(remaining.toInt()) else null
        )
    }

    clientProt(opcode = 30, size = 1) { ChatType(readUByte().toInt()) }
    clientProt(opcode = 20, size = 3) { ChatSetFilter(readUByte().toInt(), readUByte().toInt(), readUByte().toInt()) }

    // Friends/Ignore List
    clientProt(opcode = 26, size = ProtSize.VarByte) { AddFriend(readRSString()) }
    clientProt(opcode = 29, size = ProtSize.VarByte) { RemoveFriend(readRSString()) }
    clientProt(opcode = 34, size = ProtSize.VarByte) { AddIgnoreReq(readRSString(), readBoolean()) }
    clientProt(opcode = 12, size = ProtSize.VarByte) { RemoveIgnore(readRSString()) }

    // Friend Chat
    clientProt(opcode = 71, size = ProtSize.VarByte) { FcJoin(if (remaining > 0) readRSString() else null) }
    clientProt(opcode = 91, size = ProtSize.VarByte) { FcKick(readRSString()) }
    clientProt(opcode = 7, size = ProtSize.VarByte) { FcSetRank(rank = readByteSubtract(), username = readRSString()) }

    // Clan Chat
    clientProt(opcode = 90, size = ProtSize.VarByte) {
        ClanChannelKickUser(
            guest = !readBoolean(),
            pid = readUShort().toInt(),
            username = readRSString()
        )
    }

    // Game State
    clientProt<RegionLoadedConfirm>(opcode = 76)
    clientProt(opcode = 2, size = 4) { SoundEffectMusicEnded(readInt()) }
    clientProt(opcode = 18, size = 4) { SongLoaded(readInt()) }
    clientProt(opcode = 45, size = 1) { CutsceneFinished(readBoolean()) }
    clientProt(opcode = 88, size = 2) { WritePing(readUShort().toInt()) }
    clientProt(opcode = 5, size = 4) { WorldMapClick(Tile(readUIntLittle())) }

    clientProt(opcode = 10, size = ProtSize.VarByte) {
        SendPreferences(buildMap {
            var currIdx = 0
            while (remaining > 0) {
                Preference.forIndex(currIdx++)?.let {
                    put(it, readUByte().toInt())
                } ?: readUByte()
            }
        })
    }

    clientProt(opcode = 55, size = 4) { TransmitvarVerifyId(readInt()) }
    clientProt(opcode = 47, size = 4) { RequestWorldList(readInt()) }

    // Reporting/Bug Tracking
    clientProt(opcode = 100, size = ProtSize.VarByte) {
        ReportAbuse(
            username = readRSString(),
            type = readUByte().toInt(),
            mute = readBoolean(),
            reason = readRSString()
        )
    }

    clientProt(opcode = 35, size = ProtSize.VarShort) {
        BugReport(
            category = readUByte().toInt(),
            body = readJagString(),
            reproSteps = readJagString()
        )
    }

    // Account/Login
    clientProt(opcode = 52, size = ProtSize.VarByte) { EmailValidationSubmitCode(readRSString()) }
    clientProt(opcode = 92, size = ProtSize.VarShort) { EmailValidationAddNewAddress(readRSString(), readByte().toInt()) }
    clientProt(opcode = 99, size = ProtSize.VarShort) { EmailValidationChangeAddress(readRSString(), readRSString()) }
    clientProt(opcode = 102, size = ProtSize.VarShort) { CheckEmailValidity(readByteArray(remaining.toInt())) }
    clientProt(opcode = 101, size = ProtSize.VarShort) { SendSignUpForm(readByteArray(remaining.toInt())) }
    clientProt(opcode = 79, size = 1) { AccountCreationStage(readUByte().toInt()) }

    clientProt(opcode = 56, size = ProtSize.VarShort) {
        LobbyHyperlink(
            service = readRSString(),
            page = readRSString(),
            query = readRSString(),
            flags = readByte().toInt()
        )
    }

    // Unknown/Misc
    clientProt(opcode = 37, size = 2) { PlayVorbis(readShort().toInt()) }
    clientProt(opcode = 63, size = 4) { AltWalk(x = readShortAdd(), y = readShort().toInt()) }
    clientProt(opcode = 82, size = 4) { AppletLoadingPleaseWait(readInt() /*Always 1057001181*/) }
    clientProt(opcode = 97, size = ProtSize.VarByte) { UnkCs2StringResponse(readRSString()) }
}
