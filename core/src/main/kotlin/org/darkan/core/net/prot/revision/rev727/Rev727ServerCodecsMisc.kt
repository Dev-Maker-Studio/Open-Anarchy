package org.darkan.core.net.prot.revision.rev727

import io.ktor.utils.io.*
import org.darkan.core.clientwatch.ReflectionCheckType
import org.darkan.core.net.prot.*
import org.darkan.core.net.prot.packet.*
import world.gregs.voidps.buffer.*
import java.io.ByteArrayOutputStream
import java.io.ObjectOutputStream
import kotlin.io.use

/**
 * Port the client assumes for a world unless the world list marks it as custom.
 *
 * A world on this port omits its port from the list entry; anything else sets the custom-port
 * flag and appends the real port.
 */
private const val DEFAULT_WORLD_PORT = 43595

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
                    // The client reads this int unconditionally for SET_INT, so it
                    // must always be written or the rest of the packet desyncs.
                    if (check.type == ReflectionCheckType.SET_INT)
                        out.writeInt(check.fieldValue ?: 0)
                }
                ReflectionCheckType.GET_METHOD_RETURN_VALUE, ReflectionCheckType.GET_METHOD_MODIFIERS -> {
                    out.writeRSString(check.className)
                    out.writeRSString(check.methodName)
                    // The client reads exactly this many argument blobs back, so the
                    // two arrays must agree in length.
                    require(check.paramValues.size == check.paramTypes.size || check.type != ReflectionCheckType.GET_METHOD_RETURN_VALUE) {
                        "ReflectionCheck ${check.className}#${check.methodName}: paramValues (${check.paramValues.size}) must match paramTypes (${check.paramTypes.size})"
                    }
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

    // OPEN_SOCIAL_NETWORK_URL: raw, un-terminated url; the packet length is the string length.
    serverProt<OpenSocialNetworkUrl>(opcode = 86, size = ProtSize.VarShort) { out ->
        out.writeBytes(Cp1252.encode(url))
    }

    // WINDOW_CALL_WITH_ARGS: raw, un-terminated argument string; length comes from the packet length.
    serverProt<WindowCallWithArgs>(opcode = 156, size = ProtSize.VarShort) { out ->
        out.writeBytes(Cp1252.encode(args))
    }

    serverProt<DisplayNameChangeState>(opcode = 52, size = 1) { out ->
        out.writeByte(state)
    }

    serverProt<WorldListPacket>(opcode = 103, size = ProtSize.VarShort) { out ->
        val entries = worlds.entries
        // Byte 0 is the client's "decode now" flag - when 0 the client only buffers
        // the chunk. Byte 1 is a decode-mode constant: the client bails out of
        // WorldDescriptor.decodeWorldList unless it is exactly 2.
        out.writeByte(if (refreshOnClient) 1 else 0)
        out.writeByte(2)
        if (!refreshOnlyPlayerCounts) {
            out.writeByte(1)
            out.writeSmart(entries.size)
            for (world in entries) {
                out.writeSmart(world.countryId)
                out.writeJagString(world.activity)
            }
            out.writeSmart(0)
            out.writeSmart(worlds.maxWorlds + 1)
            out.writeSmart(entries.size)
            for (world in entries) {
                out.writeSmart(world.number)
                out.writeByte(world.index)
                var flags = 0
                if (world.members) flags = flags or 0x1
                if (world.quickChat) flags = flags or 0x2
                if (world.botsAllowed) flags = flags or 0x4
                if (world.lootShare) flags = flags or 0x8
                if (world.highlighted) flags = flags or 0x10
                val customPort = world.port != DEFAULT_WORLD_PORT
                if (customPort) flags = flags or 0x40000000

                out.writeInt(flags)
                out.writeJagString("")
                out.writeJagString(world.address)
                if (customPort) out.writeInt(world.port)
            }
            out.writeInt(worlds.revision)
        } else
            out.writeByte(0)
        for (world in entries) {
            out.writeSmart(world.number)
            out.writeShort(world.playersOnline)
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
            for (i in params.lastIndex downTo 0) {
                when (params[i]) {
                    is String -> append('s')
                    is Int -> append('i')
                    else -> error("RunScript params must be String or Int, got: ${params[i]::class.simpleName}")
                }
            }
        }
        out.writeRSString(signature)
        for (param in params) {
            when (param) {
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

    serverProt<StopVorbisStream>(opcode = 11, size = 2) { out ->
        out.writeShortAddLittle(soundId)
    }

    serverProt<VorbisPreloadSound>(opcode = 17, size = 3) { out ->
        out.writeByte(priority)
        out.writeShortAdd(soundId)
    }

    serverProt<SetVorbisSound>(opcode = 106, size = 2) { out ->
        out.writeShort(soundId)
    }

    serverProt<SoundMixbussSetLevel>(opcode = 134, size = 2) { out ->
        out.writeByte(level)
        out.writeByteInverse(index)
    }

    serverProt<ResetSounds>(opcode = 120)

    // Login details
    serverProt<LobbyLoginDetails>(opcode = 2, size = ProtSize.VarByte) { out ->
        out.writeByte(crown)
        out.writeByte(0) // PLAYER_MOD_LEVEL
        out.writeByte(0) // USERDETAIL_QUICKCHAT_ONLY
        out.writeMedium(0)
        out.writeByte(0) // Gender.
        out.writeByte(0) // VERIFIED_EMAIL_ADDRESS
        out.writeByte(1)
        out.writeLong(Long.MAX_VALUE) // members subscription end
        out.write5(12L)
        out.writeByte(0x1) // 0x1 - if members, 0x2 - subscription
        out.writeInt(1) // jcoins?
        out.writeByte(1) // is loyalty member?
        out.writeInt(1) // loyalty points?
        out.writeShort(592) // recovery questions set date
        out.writeShort(0) // Messages add support for forum integration
        out.writeShort(0) // last logged in date
        out.writeInt(lastIpHash)
        out.writeRSString(worldLoginToken)
        out.writeByte(2) // email status (0 - no email, 1 - pending parental confirmation, 2 - pending confirmation, 3 - registered)
        out.writeShort(302)
        out.writeShort(1)
        out.writeByte(1)
        out.writeJagString(displayName)
        out.writeByte(1)
        out.writeInt(1)
        out.writeByte(0) // Removed on EOC revisions for some reason. idk
        out.writeShort(defaultWorldId) // default world id
        out.writeJagString(defaultWorldAddress) // default world address
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
