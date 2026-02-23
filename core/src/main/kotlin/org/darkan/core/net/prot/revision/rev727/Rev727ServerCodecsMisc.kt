package org.darkan.core.net.prot.revision.rev727

import io.ktor.utils.io.*
import org.darkan.core.clientwatch.ReflectionCheckType
import org.darkan.core.net.prot.*
import world.gregs.voidps.buffer.*
import java.io.ByteArrayOutputStream
import java.io.ObjectOutputStream
import kotlin.io.use

internal fun Codec.registerRev727MiscPackets() {
    // Reflection/clientwatch
    serverProt<ReflectionRequest>(opcode = 98, size = ProtSize.VarShort) { out ->
        out.writeByte(checks.size)
        out.writeInt(id)
        for (check in checks) {
            out.writeByte(check.type.ordinal)
            when (check.type) {
                ReflectionCheckType.GET_INT, ReflectionCheckType.SET_INT, ReflectionCheckType.GET_FIELD_MODIFIERS -> {
                    out.writeRSString(check.className)
                    out.writeRSString(check.methodName)
                    if (check.type == ReflectionCheckType.SET_INT)
                        check.fieldValue?.let { out.writeInt(it) }
                }
                ReflectionCheckType.GET_METHOD_RETURN_VALUE, ReflectionCheckType.GET_METHOD_MODIFIERS -> {
                    out.writeRSString(check.className)
                    out.writeRSString(check.methodName)
                    out.writeByte(check.paramTypes.size)
                    check.paramTypes.forEach { out.writeRSString(it) }
                    out.writeRSString(check.returnType ?: "void")
                    if (check.type == ReflectionCheckType.GET_METHOD_RETURN_VALUE) {
                        check.paramValues.forEach { param ->
                            ByteArrayOutputStream().use { bos ->
                                ObjectOutputStream(bos).use { oos ->
                                    oos.writeObject(param)
                                    val data = bos.toByteArray()
                                    out.writeInt(data.size)
                                    out.writeBytes(data)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Region/map building
    serverProt<BuildRegion>(opcode = 85, size = ProtSize.VarShort) { out ->
        if (localPlayerPid != null && localPlayerBaseTileHash != null && otherPlayerRegionIds != null) {
            out.bitAccess {
                writeBits(30, localPlayerBaseTileHash)
                for (index in 1 until otherPlayerRegionIds.size) {
                    if (index != localPlayerPid)
                        writeBits(18, otherPlayerRegionIds[index])
                }
            }
        }
        out.writeByte(mapSize.ordinal)
        out.writeShort(chunkX)
        out.writeShort(chunkY)
        out.writeBoolean(forceMapRefresh)
        xteas.forEach { keys ->
            keys.forEach { key -> out.writeInt(key) }
        }
    }

    serverProt<BuildInstancedRegion>(opcode = 51, size = ProtSize.VarShort) { out ->
        if (localPlayerPid != null && localPlayerBaseTileHash != null && otherPlayerRegionIds != null) {
            out.bitAccess {
                writeBits(30, localPlayerBaseTileHash)
                for (index in 1 until otherPlayerRegionIds.size) {
                    if (index != localPlayerPid)
                        writeBits(18, otherPlayerRegionIds[index])
                }
            }
        }
        out.writeBooleanInverse(forceMapRefresh)
        out.writeByteSubtract(instanceLoadType.index)
        out.writeByteAdd(mapSize.ordinal)
        out.writeShortAdd(chunkY)
        out.writeShort(chunkX)
        out.bitAccess {
            chunks.forEach {
                if (it == -1) writeBit(false)
                else {
                    writeBit(true)
                    writeBits(26, it)
                }
            }
        }
        xteas.forEach { keys ->
            keys.forEach { key -> out.writeInt(key) }
        }
    }

    // Camera protocols
    serverProt<CamLookAt>(opcode = 24, size = 6) { out ->
        out.writeByte(viewLocalX)
        out.writeShortAdd(viewZ shr 2)
        out.writeByteAdd(viewLocalY)
        out.writeByteInverse(speed1)
        out.writeByteSubtract(speed2)
    }

    serverProt<CamMoveTo>(opcode = 39, size = 6) { out ->
        out.writeByte(moveLocalY)
        out.writeByte(moveLocalX)
        out.writeByteSubtract(speed1)
        out.writeShortLittle(moveZ shr 2)
        out.writeByteAdd(speed2)
    }

    serverProt<CamRemoveRoof>(opcode = 40, size = 4) { out ->
        out.writeIntLittle(tile.id)
    }

    serverProt<CamResetHard>(opcode = 66)
    serverProt<CamResetSmooth>(opcode = 89)

    serverProt<CamShake>(opcode = 71, size = 6) { out ->
        out.writeByteSubtract(v2)
        out.writeShort(v4)
        out.writeByteInverse(slotId)
        out.writeByteInverse(v1)
        out.writeByteSubtract(v3)
    }

    serverProt<CamForceAngle>(opcode = 118, size = 4) { out ->
        out.writeShortAdd(angleY)
        out.writeShortAddLittle(angleX)
    }

    // Login and system protocols
    serverProt<LogoutLobby>(opcode = 9)

    serverProt<LogoutFull>(opcode = 62)

    serverProt<OpenUrl>(opcode = 16, size = ProtSize.VarShort) { out ->
        val hasDisplayUrl = displayUrl != null
        out.writeBoolean(hasDisplayUrl)
        out.writeRSString(url)
        if (hasDisplayUrl) {
            out.writeRSString(displayUrl?.ifEmpty { url } ?: url)
        }
    }

    serverProt<WorldListPacket>(opcode = 103, size = ProtSize.VarShort) { out ->
        val worlds = worldList.getWorldArray()
        out.writeByte(1)
        out.writeByte(if (refreshOnClient) 2 else 0)
        if (!refreshOnlyPlayerCounts) {
            out.writeByte(1)
            val size: Int = worlds.size
            out.writeSmart(size)
            for (world in worlds) {
                out.writeSmart(world.metadata.country.id)
                out.writeJagString(world.metadata.activity)
            }
            out.writeSmart(0)
            out.writeSmart(worldList.maxWorlds+1)
            out.writeSmart(size)
            for (world in worlds) {
                out.writeSmart(world.metadata.number)
                out.writeByte(world.index)
                var flags = 0
                if (world.metadata.members) flags = flags or 0x1
                if (world.metadata.quickchat) flags = flags or 0x2
                if (world.metadata.pvp) flags = flags or 0x4
                if (world.metadata.lootShare) flags = flags or 0x8
                if (world.metadata.highlighted) flags = flags or 0x10
                if (world.metadata.port != 43595) flags = flags or 0x40000000 // Custom port flag

                out.writeInt(flags)
                out.writeJagString("")
                out.writeJagString(world.metadata.ipAddress)
                if (world.metadata.port != 43595) out.writeInt(world.metadata.port)
            }
            out.writeInt(worldList.revision)
        } else
            out.writeByte(0)
        for (world in worlds) {
            out.writeSmart(world.metadata.number)
            out.writeShort(if (world.offline) -1 else world.playersOnline)
        }
    }

    serverProt<IdentifyHostName>(opcode = 147, size = 4) { out ->
        out.writeInt(ip)
    }

    serverProt<UpdateRebootTimer>(opcode = 102, size = 2) { out ->
        out.writeShort(seconds)
    }

    serverProt<RequestFps>(opcode = 159, size = 8) { out ->
        out.writeInt(key)
        out.writeInt(flags)
    }

    serverProt<UpdateSitesettingsCookie>(opcode = 91, size = ProtSize.VarByte) { out ->
        // Client no-ops this packet on non-applet builds. Send empty payload by default.
    }

    // Account protocols
    serverProt<CreateCheckEmailReply>(opcode = 1, size = 1) { out ->
        out.writeByte(responseCode)
    }

    serverProt<CreateAccountReply>(opcode = 87, size = 1) { out ->
        out.writeByte(code.value)
    }

    // Miscellaneous protocols
    serverProt<ProcessDevConsoleCommand>(opcode = 2, size = ProtSize.VarByte) { out ->
        out.writeRSString(command)
    }

    serverProt<RunScript>(opcode = 99, size = ProtSize.VarShort) { out ->
        val signature = buildString {
            for (param in params) {
                when (param) {
                    is String -> append('s')
                    is Int -> append('I')
                    else -> error("RunScript params must be String or Int, got: ${param::class.simpleName}")
                }
            }
        }
        out.writeRSString(signature)
        for (i in params.lastIndex downTo 0) {
            when (val param = params[i]) {
                is String -> out.writeRSString(param)
                is Int -> out.writeInt(param)
            }
        }
        out.writeInt(scriptId)
    }

    serverProt<SetCursor>(opcode = 153, size = ProtSize.VarByte) { out ->
        if (uptext != null) {
            out.writeRSString(uptext)
            out.writeShort(if (cursorId == -1) 65535 else cursorId)
        } else if (cursorId != -1) {
            out.writeShort(cursorId)
        }
    }

    serverProt<Cutscene>(opcode = 110, size = ProtSize.VarShort) { out ->
        out.writeShort(cutsceneId)
        out.writeShort(mapXteas.size)
        for (keys in mapXteas) {
            for (key in keys) {
                out.writeInt(key)
            }
        }
        out.writeByte(data.size)
        out.writeBytes(data)
    }

    serverProt<TriggerOnDialogAbort>(opcode = 146)

    serverProt<KeepAlive>(opcode = 29)

    serverProt<LoyaltyUpdate>(opcode = 32, size = 5) { out ->
        out.writeInt(balance)
        out.writeBooleanInverse(enabled)
    }

    serverProt<JCoinsUpdate>(opcode = 75, size = 4) { out ->
        out.writeIntLittle(amount)
    }

    serverProt<ApplyDebug>(opcode = 76, size = 2) { out ->
        out.writeShort(opcode)
    }

    serverProt<QuickHopWorlds>(opcode = 58, size = ProtSize.VarByte) { out ->
        out.writeShort(port)
        out.writeRSString(host)
        out.writeBoolean(reconnecting)
    }

    serverProt<DebugServerTriggers>(opcode = 73, size = ProtSize.VarByte) { out ->
        out.writeShort(interfaceId)
        out.writeShort(fromComponent)
        out.writeShort(toComponent)
        for (trigger in triggers) {
            out.writeMedium(trigger)
        }
    }

    serverProt<ShowFaceHere>(opcode = 21, size = 1) { out ->
        out.writeBoolean(enabled)
    }

    serverProt<MinimapFlag>(opcode = 70, size = 2) { out ->
        if (x < 0 || y < 0) {
            out.writeByteSubtract(255)
            out.writeByteSubtract(255)
        } else {
            out.writeByteSubtract(x)
            out.writeByteSubtract(y)
        }
    }

    serverProt<HintArrow>(opcode = 79, size = 14) { out ->
        val flags = ((iconIndex and 0x7) shl 5) or (targetType and 0x1F)
        out.writeByte(flags)
        if (targetType == 0) {
            out.writeBytes(ByteArray(13))
            return@serverProt
        }
        out.writeByte(arrowSprite)
        if (targetType == 1 || targetType == 10) {
            out.writeShort(targetIndex)
            out.writeShort(denominator)
            out.writeInt(0) // unused 4 bytes
        } else {
            out.writeByte(plane)
            out.writeShort(x)
            out.writeShort(y)
            out.writeByte(height shr 2)
            out.writeShort(distance)
        }
        out.writeInt(modelId)
    }

    serverProt<HintTrail>(opcode = 149, size = ProtSize.VarShort) { out ->
        out.writeByte(index)
        out.writeBigSmart(modelId)
        out.writeBytes(data)
    }

    serverProt<SetDrawOrder>(opcode = 151, size = 1) { out ->
        out.writeBooleanInverse(playerFirst)
    }

    serverProt<BlockMinimapState>(opcode = 154, size = 1) { out ->
        out.writeByte(state)
    }

    serverProt<ResetAllAnimations>(opcode = 122)

    // Audio protocols (non-zone)
    serverProt<VorbisSound>(opcode = 18, size = 8) { out ->
        out.writeShort(if (soundId == -1) 65535 else soundId)
        out.writeByte(repeat)
        out.writeShort(delay)
        out.writeByte(volume)
        out.writeShort(sampleRate)
    }

    serverProt<VorbisSpeechSound>(opcode = 104, size = 6) { out ->
        out.writeShort(if (soundId == -1) 65535 else soundId)
        out.writeByte(soundType)
        out.writeShort(delay)
        out.writeByte(volume)
    }

    serverProt<SoundSynth>(opcode = 136, size = 8) { out ->
        out.writeShort(if (soundId == -1) 65535 else soundId)
        out.writeByte(flags)
        out.writeShort(unknown)
        out.writeByte(delay)
        out.writeShort(unknown2)
    }

    serverProt<MusicEffect>(opcode = 60, size = 6) { out ->
        out.writeMediumReverseEnd(key)
        out.writeByteSubtract(volume)
        out.writeShortLittle(if (musicId == -1) 65535 else musicId)
    }

    serverProt<MusicTrack>(opcode = 63, size = 4) { out ->
        out.writeByteAdd(key)
        out.writeByteAdd(flags)
        out.writeShortAdd(if (musicId == -1) 65535 else musicId)
    }

    serverProt<PreloadSong>(opcode = 77, size = 2) { out ->
        out.writeShortAddLittle(if (songId == -1) 65535 else songId)
    }

    serverProt<SoundMixbussSetLevel>(opcode = 134, size = 2) { out ->
        out.writeByte(level)
        out.writeByteInverse(index)
    }

    serverProt<ResetSounds>(opcode = 120)

    // Login details
    serverProt<LobbyLoginDetails>(opcode = 2, size = ProtSize.VarByte) { out ->
        var ipHash = 0
        out.writeByte(account.rights.crown) // rights
        out.writeByte(0) // PLAYER_MOD_LEVEL
        out.writeByte(0) // USERDETAIL_QUICKCHAT_ONLY
        out.writeMedium(0)
        out.writeByte(0) // Gender.
        out.writeByte(0) // VERIFIED_EMAIL_ADDRESS
        out.writeByte(1)
        out.writeLong(Long.MAX_VALUE) // members subscription end
        out.write5(12)
        out.writeByte(0x1) // 0x1 - if members, 0x2 - subscription
        out.writeInt(1) // jcoins?
        out.writeByte(1) // is loyalty member?
        out.writeInt(1) // loyalty points?
        out.writeShort(592) // recovery questions set date
        out.writeShort(0) // Messages add support for forum integration
        out.writeShort(0) // last logged in date
        if (account.lastIp != null) {
            val ipSplit = account.lastIp?.replace("[", "")?.replace("]", "")?.split(".")
            if (ipSplit != null)
                ipHash = ipSplit[0].toInt() shl 24 or (ipSplit[1].toInt() shl 16) or (ipSplit[2].toInt() shl 8) or ipSplit[3].toInt()
        }
        out.writeInt(ipHash) // ip part
        out.writeRSString(worldLoginToken)
        out.writeByte(2) // email status (0 - no email, 1 - pending parental confirmation, 2 - pending confirmation, 3 - registered)
        out.writeShort(302)
        out.writeShort(1)
        out.writeByte(1)
        out.writeJagString(account.displayName)
        out.writeByte(1)
        out.writeInt(1)
        out.writeByte(0) // Removed on EOC revisions for some reason. idk
        //val defWorld: World? = Lobby.getWorlds().getDefault().getInfo()
        //out.writeShort(if (defWorld == null) Lobby.WORLD_INFO.number() else defWorld.number()) // Default world ID
        //out.writeJagString(if (defWorld == null) Lobby.WORLD_INFO.ipAddress() else defWorld.ipAddress())
        out.writeShort(0)
        out.writeJagString("127.0.0.1")
    }

    // World-login details blob (LobbyStage.LOBBY_TO_WORLD).
    // This intentionally uses opcode=2 (success) + VarByte length, matching the client's world-login handshake.
    serverProt<WorldLoginDetails>(opcode = 2, size = ProtSize.VarByte) { out ->
        out.writeByte(rights)
        out.writeByte(modLevel)
        out.writeByte(if (quickChat) 1 else 0)
        out.writeByte(if (verifiedEmail) 1 else 0)
        out.writeByte(if (aBool7322) 1 else 0)
        out.writeByte(if (quickChatOnly) 1 else 0)
        out.writeShort(playerIndex)
        out.writeByte(if (members) 1 else 0)
        out.writeMedium(dob)
        out.writeByte(if (memberWorld) 1 else 0)
        out.writeRSString(worldName)
    }
}
