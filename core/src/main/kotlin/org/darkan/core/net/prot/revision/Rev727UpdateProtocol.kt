package org.darkan.core.net.prot.revision

import org.darkan.core.net.prot.update.*
import org.darkan.core.update.HitSplat
import org.darkan.core.update.HitsUpdate
import org.darkan.core.update.NpcCustomisation
import world.gregs.voidps.buffer.write.BufferWriter
import kotlin.math.abs

/**
 * Rev727 entity update protocol (PlayerUpdate/NpcUpdate).
 *
 * All revision-oriented update encoding lives here.
 */
object Rev727UpdateProtocol {

    private data class PlayerMaskCtx(
        val packet: PlayerUpdatePacket,
        val player: PlayerUpdatePlayer,
        val added: Boolean,
    )

    private data class NpcMaskCtx(
        val npc: NpcUpdateNpc,
    )

    private val playerHeader = MaskHeader(
        withExtensions = { mask ->
            var m = mask
            if (m >= 256) m = m or 0x8
            if (m >= 65536) m = m or 0x100
            m
        },
        writeHeader = { mask, out ->
            out.writeByte(mask)
            if (mask >= 256) out.writeByte(mask shr 8)
            if (mask >= 65536) out.writeByte(mask shr 16)
        }
    )

    private val npcHeader = MaskHeader(
        withExtensions = { mask ->
            var m = mask
            if (m > 0xff) m = m or 0x40
            if (m > 0xffff) m = m or 0x2000
            if (m > 0xffffff) m = m or 0x100000
            m
        },
        writeHeader = { mask, out ->
            out.writeByte(mask)
            if (mask > 0xff) out.writeByte(mask shr 8)
            if (mask > 0xffff) out.writeByte(mask shr 16)
            if (mask > 0xffffff) out.writeByte(mask shr 24)
        }
    )

    /**
     * Player mask registry for revision 727, in client decode order.
     */
    private val playerMasks: MaskRegistry<PlayerMaskCtx, PlayerMaskKey> =
        MaskRegistryBuilder<PlayerMaskCtx, PlayerMaskKey>(header = playerHeader).apply {
            // 0x20000 Tint
            mask(PlayerMaskKey.BODY_GLOW, 0x20000) { ctx, out ->
                    val t = ctx.player.blocks.common.tint!!
                    out.writeByte(t.hue)
                    out.writeByteInverse(t.saturation)
                    out.writeByteSubtract(t.lightness)
                    out.writeByteSubtract(t.opacity)
                    out.writeShortAdd(t.startCycles)
                    out.writeShort(t.endCycles)
            }

            // 0x400000 Player configs delta (no clear)
            mask(PlayerMaskKey.VARN_NO_CLEAR, 0x400000) { ctx, out ->
                    val d = ctx.player.blocks.playerConfigsNoClear!!
                    out.writeByte(d.keys.size)
                    for (i in d.keys.indices) {
                        out.writeShortLittle(d.keys[i])
                        out.writeInt(d.values[i])
                    }
            }

            // 0x200 SpotAnim slot=1
            mask(PlayerMaskKey.GRAPHICS_2, 0x200) { ctx, out ->
                    val s1 = ctx.player.blocks.common.spotAnims[1]!!
                    out.writeShort(if (s1.id == -1) 65535 else s1.id)
                    out.writeIntInverseMiddle(s1.settingsHash)
                    out.writeByteSubtract(s1.settings2Hash)
            }

            // 0x20 Face direction
            mask(PlayerMaskKey.FACE_DIRECTION, 0x20) { ctx, out ->
                    out.writeShortAdd(ctx.player.blocks.faceDirection!!)
            }

            // 0x800000 Player configs clear+set
            mask(PlayerMaskKey.VARN_CLEAR, 0x800000) { ctx, out ->
                    val d = ctx.player.blocks.playerConfigsClear!!
                    out.writeByte(d.keys.size)
                    for (i in d.keys.indices) {
                        out.writeShortLittle(d.keys[i])
                        out.writeInt(d.values[i])
                    }
            }

            // 0x10000 Reduce attack priority
            mask(PlayerMaskKey.MAP_DOT_2, 0x10000) { ctx, out ->
                    out.writeByteSubtract(if (ctx.player.blocks.reduceAttackPriority == true) 1 else 0)
            }

            // 0x100000 Model overrides
            mask(PlayerMaskKey.BODY_MODEL_ROTATOR, 0x100000) { ctx, out ->
                    val o = ctx.player.blocks.common.modelOverrides!!
                    out.writeByteSubtract(o.keys.size)
                    for (i in o.keys.indices) {
                        val key = o.keys[i]
                        if (key ushr 16 != 0) {
                            out.writeShortAdd(key ushr 16)
                            out.writeShortAdd(key and 0xffff)
                        } else {
                            out.writeShortAdd(key)
                        }
                        out.writeShort(o.values[i])
                    }
            }

            // 0x4000 ForceTalk
            mask(PlayerMaskKey.FORCE_TALK, 0x4000) { ctx, out ->
                    out.writeString(ctx.player.blocks.forceTalk!!)
            }

            // 0x80000 Public chat
            mask(PlayerMaskKey.FORCE_CHAT, 0x80000) { ctx, out ->
                    val chat = ctx.player.blocks.publicChat!!
                    out.writeString(chat.message)
                    out.writeByteAdd(chat.chatFlags)
            }

            // 0x40000 SpotAnim slot=2
            mask(PlayerMaskKey.GRAPHICS_3, 0x40000) { ctx, out ->
                    val s2 = ctx.player.blocks.common.spotAnims[2]!!
                    out.writeShortAddLittle(if (s2.id == -1) 65535 else s2.id)
                    out.writeIntInverseMiddle(s2.settingsHash)
                    out.writeByteAdd(s2.settings2Hash)
            }

            // 0x8000 Map dot
            mask(PlayerMaskKey.MAP_DOT_1, 0x8000) { ctx, out ->
                    out.writeByteSubtract(if (ctx.player.blocks.mapDot!!) 1 else 0)
            }

            // 0x40 Hits
            mask(PlayerMaskKey.HITS, 0x40) { ctx, out ->
                    encodeHitsPlayer(out, ctx.player.blocks.common.hits!!)
            }

            // 0x1 Appearance (conditional by cache)
            mask(PlayerMaskKey.APPEARANCE, 0x1) { ctx, out ->
                    val app = ctx.player.blocks.appearance!!
                    ctx.packet.totalRenderDataSentLength += app.data.size
                    ctx.packet.cachedAppearanceHashes[ctx.player.index] = app.hash
                    out.writeByteInverse(app.data.size)
                    out.writeBytes(app.data)
            }

            // 0x10 Movement animations
            mask(PlayerMaskKey.ANIMATION, 0x10) { ctx, out ->
                    val ma = ctx.player.blocks.movementAnimations!!
                    for (id in ma.ids) writeBigSmart(out, id)
                    out.writeByte(ma.speed)
            }

            // 0x2 Face entity
            mask(PlayerMaskKey.FACE_ENTITY, 0x2) { ctx, out ->
                    val face = ctx.player.blocks.common.faceEntity!!
                    val idx = if (face == -1) 65535 else face
                    out.writeShortAdd(idx)
            }

            // 0x1000 Temporary move type
            mask(PlayerMaskKey.TEMPORARY_MOVE_TYPE, 0x1000) { ctx, out ->
                    out.writeByteInverse(ctx.player.blocks.temporaryMoveTypeId!!)
            }

            // 0x2000 Animate sequences
            mask(PlayerMaskKey.ANIMATION_SEQUENCE, 0x2000) { ctx, out ->
                    val s = ctx.player.blocks.sequences!!
                    out.writeByteSubtract(s.ids.size)
                    for (i in s.ids.indices) {
                        writeBigSmart(out, s.ids[i])
                        out.writeByteInverse(s.delays[i])
                        out.writeShortAddLittle(s.flags[i])
                    }
            }

            // 0x4 Movement type
            mask(PlayerMaskKey.MOVE_TYPE, 0x4) { ctx, out ->
                    out.writeByteSubtract(ctx.player.blocks.movementTypeId!!)
            }

            // 0x200000 SpotAnim slot=3
            mask(PlayerMaskKey.GRAPHICS_4, 0x200000) { ctx, out ->
                    val s3 = ctx.player.blocks.common.spotAnims[3]!!
                    out.writeShortLittle(if (s3.id == -1) 65535 else s3.id)
                    out.writeIntLittle(s3.settingsHash)
                    out.writeByteSubtract(s3.settings2Hash)
            }

            // 0x800 Force movement
            mask(PlayerMaskKey.FORCE_MOVEMENT, 0x800) { ctx, out ->
                    val fm = ctx.player.blocks.common.forceMovement!!
                    out.writeByteInverse(fm.diffX1)
                    out.writeByteSubtract(fm.diffY1)
                    out.writeByteAdd(fm.diffX2)
                    out.writeByteInverse(fm.diffY2)
                    out.writeShortAddLittle(fm.startClientCycles)
                    out.writeShortLittle(fm.speedClientCycles)
                    out.writeShortAdd(fm.direction)
            }

            // 0x80 SpotAnim slot=0
            mask(PlayerMaskKey.GRAPHICS_1, 0x80) { ctx, out ->
                    val s0 = ctx.player.blocks.common.spotAnims[0]!!
                    out.writeShortLittle(if (s0.id == -1) 65535 else s0.id)
                    out.writeInt(s0.settingsHash)
                    out.writeByteAdd(s0.settings2Hash)
            }
        }.build()

    /**
     * NPC mask registry for revision 727, in client decode order.
     */
    private val npcMasks: MaskRegistry<NpcMaskCtx, NpcMaskKey> =
        MaskRegistryBuilder<NpcMaskCtx, NpcMaskKey>(header = npcHeader).apply {
            // 0x10 movement animations
            mask(NpcMaskKey.ANIMATION, 0x10) { ctx, out ->
                    val ma = ctx.npc.blocks.movementAnimations!!
                    for (id in ma.ids) writeBigSmart(out, id)
                    out.writeByte(ma.speed)
            }

            // 0x100 customise (body)
            mask(NpcMaskKey.BODY_MESH_MODIFIER, 0x100) { ctx, out -> encodeNpcCustomisation(out, ctx.npc.blocks.customisation!!) }

            // 0x400 force move
            mask(NpcMaskKey.FORCE_MOVEMENT, 0x400) { ctx, out ->
                    val fm = ctx.npc.blocks.common.forceMovement!!
                    out.writeByteSubtract(fm.diffX1)
                    out.writeByteAdd(fm.diffY1)
                    out.writeByteInverse(fm.diffX2)
                    out.writeByteAdd(fm.diffY2)
                    out.writeShortLittle(fm.startClientCycles)
                    out.writeShortLittle(fm.speedClientCycles)
                    out.writeShortAddLittle(fm.direction)
            }

            // 0x1000 BAS
            mask(NpcMaskKey.BAS, 0x1000) { ctx, out ->
                    val v = ctx.npc.blocks.basId!!
                    out.writeShort(if (v == -1) 65535 else v)
            }

            // 0x200000 SET_VARN
            mask(NpcMaskKey.VARN_SET, 0x200000) { ctx, out ->
                    val d = ctx.npc.blocks.varnsNoClear!!
                    out.writeByte(d.keys.size)
                    for (i in d.keys.indices) {
                        out.writeShortAddLittle(d.keys[i])
                        out.writeIntMiddle(d.values[i])
                    }
            }

            // 0x1000000 PLAY_GFX slot=3
            mask(NpcMaskKey.GRAPHICS_4, 0x1000000) { ctx, out ->
                    val s3 = ctx.npc.blocks.common.spotAnims[3]!!
                    out.writeShortAdd(if (s3.id == -1) 65535 else s3.id)
                    out.writeIntInverseMiddle(s3.settingsHash)
                    out.writeByteAdd(s3.settings2Hash)
            }

            // 0x80 face entity
            mask(NpcMaskKey.FACE_ENTITY, 0x80) { ctx, out ->
                    val face = ctx.npc.blocks.common.faceEntity!!
                    out.writeShortLittle(if (face == -1) 65535 else face)
            }

            // 0x8000 animate sequences
            mask(NpcMaskKey.ANIMATION_SEQUENCE, 0x8000) { ctx, out ->
                    val s = ctx.npc.blocks.sequences!!
                    out.writeByteInverse(s.ids.size)
                    for (i in s.ids.indices) {
                        writeBigSmart(out, s.ids[i])
                        out.writeByte(s.delays[i])
                        out.writeShortLittle(s.flags[i])
                    }
            }

            // 0x800 GFX_ slot=1
            mask(NpcMaskKey.GRAPHICS_2, 0x800) { ctx, out ->
                    val s1 = ctx.npc.blocks.common.spotAnims[1]!!
                    out.writeShortLittle(if (s1.id == -1) 65535 else s1.id)
                    out.writeInt(s1.settingsHash)
                    out.writeByteAdd(s1.settings2Hash)
            }

            // 0x1 hits
            mask(NpcMaskKey.HITS, 0x1) { ctx, out -> encodeHitsNpc(out, ctx.npc.blocks.common.hits!!) }

            // 0x8 transform
            mask(NpcMaskKey.TRANSFORMATION, 0x8) { ctx, out -> writeBigSmart(out, ctx.npc.blocks.transformNpcId!!) }

            // 0x2000000 spotanim slot=2
            mask(NpcMaskKey.GRAPHICS_3, 0x2000000) { ctx, out ->
                    val s2 = ctx.npc.blocks.common.spotAnims[2]!!
                    out.writeShortLittle(if (s2.id == -1) 65535 else s2.id)
                    out.writeIntLittle(s2.settingsHash)
                    out.writeByte(s2.settings2Hash)
            }

            // 0x80000 update stats
            mask(NpcMaskKey.STATS_UPDATE, 0x80000) { ctx, out ->
                    val st = ctx.npc.blocks.updateStats!!
                    out.writeByte(st.indices.size)
                    for (i in st.indices.indices) {
                        out.writeByteAdd(st.indices[i])
                        out.writeInt(st.maxValues[i])
                        val current = st.currentValues[i]
                        out.writeByte(current shr 16)
                        out.writeByte(current)
                        out.writeByte(current shr 8)
                    }
            }

            // 0x40000 head customisation
            mask(NpcMaskKey.HEAD_MESH_MODIFIER, 0x40000) { ctx, out -> encodeNpcHeadCustomisation(out, ctx.npc.blocks.headCustomisation!!) }

            // 0x10000 combat level
            mask(NpcMaskKey.CHANGE_COMBAT_LEVEL, 0x10000) { ctx, out -> out.writeShortAdd(ctx.npc.blocks.combatLevel!!) }

            // 0x400000 clear varns
            mask(NpcMaskKey.VARN_CLEAR, 0x400000) { ctx, out ->
                    val d = ctx.npc.blocks.varnsClear!!
                    out.writeByte(d.keys.size)
                    for (i in d.keys.indices) {
                        out.writeShort(d.keys[i])
                        out.writeIntMiddle(d.values[i])
                    }
            }

            // 0x4 face tile
            mask(NpcMaskKey.FACE_TILE, 0x4) { ctx, out ->
                    val ft = ctx.npc.blocks.common.faceTile!!
                    out.writeShortAddLittle((ft.x shl 1) + 1)
                    out.writeShortLittle((ft.y shl 1) + 1)
            }

            // 0x20000 tint
            mask(NpcMaskKey.BODY_GLOW, 0x20000) { ctx, out ->
                    val t = ctx.npc.blocks.common.tint!!
                    out.writeByteSubtract(t.hue)
                    out.writeByte(t.saturation)
                    out.writeByte(t.lightness)
                    out.writeByteSubtract(t.opacity)
                    out.writeShortLittle(t.startCycles)
                    out.writeShort(t.endCycles)
            }

            // 0x800000 name
            mask(NpcMaskKey.NAME_CHANGE, 0x800000) { ctx, out -> out.writeString(ctx.npc.blocks.name!!) }

            // 0x2 say text
            mask(NpcMaskKey.FORCE_TALK, 0x2) { ctx, out -> out.writeString(ctx.npc.blocks.say!!) }

            // 0x4000 model overrides
            mask(NpcMaskKey.BODY_MODEL_ROTATOR, 0x4000) { ctx, out ->
                    val o = ctx.npc.blocks.common.modelOverrides!!
                    out.writeByteSubtract(o.keys.size)
                    for (i in o.keys.indices) {
                        val key = o.keys[i]
                        val first = if (key ushr 16 != 0) (key ushr 16) else key
                        out.writeShortAddLittle(first)
                        if (key ushr 16 != 0) out.writeShort(key and 0xffff)
                        out.writeShortAddLittle(o.values[i])
                    }
            }

            // 0x20 spotanim slot=0
            mask(NpcMaskKey.GRAPHICS_1, 0x20) { ctx, out ->
                    val s0 = ctx.npc.blocks.common.spotAnims[0]!!
                    out.writeShort(if (s0.id == -1) 65535 else s0.id)
                    out.writeIntInverseMiddle(s0.settingsHash)
                    out.writeByteInverse(s0.settings2Hash)
            }
        }.build()

    // ----------------------------
    // Player update
    // ----------------------------

    fun encodePlayerUpdate(packet: PlayerUpdatePacket): ByteArray {
        val local = packet.playersByIndex[packet.localIndex] ?: return ByteArray(0)

        val stream = BufferWriter(capacity = 16384)
        val updateBlocks = BufferWriter(capacity = 16384)

        if (packet.localIndexCount == 0 && packet.outIndexCount == 0) {
            writeInit(stream, packet, local)
        }

        processLocalPlayers(stream, updateBlocks, packet, local, packet.largeSceneView, true)
        processLocalPlayers(stream, updateBlocks, packet, local, packet.largeSceneView, false)
        processOutsidePlayers(stream, updateBlocks, packet, local, packet.playersByIndex, packet.largeSceneView, true)
        processOutsidePlayers(stream, updateBlocks, packet, local, packet.playersByIndex, packet.largeSceneView, false)

        stream.writeBytes(updateBlocks.toArray())

        packet.totalRenderDataSentLength = 0
        packet.localIndexCount = 0
        packet.outIndexCount = 0

        for (playerIndex in 1 until 2048) {
            packet.slotFlags[playerIndex] = (packet.slotFlags[playerIndex].toInt() shr 1).toByte()
            val p = packet.localPlayers[playerIndex]
            if (p == null) {
                packet.outIndexes[packet.outIndexCount++] = playerIndex
            } else {
                packet.localIndexes[packet.localIndexCount++] = playerIndex
            }
        }

        return stream.toArray()
    }

    private fun writeInit(stream: BufferWriter, packet: PlayerUpdatePacket, local: PlayerUpdatePlayer) {
        stream.startBitAccess()
        stream.writeBits(30, packet.localTileHash30)

        packet.localPlayers[local.index] = local
        packet.localIndexes[packet.localIndexCount++] = local.index

        for (playerIndex in 1 until 2048) {
            if (playerIndex == local.index) continue
            val p = packet.playersByIndex[playerIndex]
            val regionHash = p?.regionHash18 ?: 0
            stream.writeBits(18, regionHash)
            packet.regionHashes[playerIndex] = regionHash
            packet.outIndexes[packet.outIndexCount++] = playerIndex
        }

        stream.stopBitAccess()
    }

    private fun withinDistance(local: PlayerUpdatePlayer, other: PlayerUpdatePlayer, largeSceneView: Boolean): Boolean {
            val radius = if (largeSceneView) 126 else 14
            val localX = (local.tileHash30 shr 14) and 0x3fff
            val localY = local.tileHash30 and 0x3fff
            val otherX = (other.tileHash30 shr 14) and 0x3fff
            val otherY = other.tileHash30 and 0x3fff
            return abs(localX - otherX) <= radius && abs(localY - otherY) <= radius
        }

    private fun needsRemove(local: PlayerUpdatePlayer, p: PlayerUpdatePlayer, largeSceneView: Boolean): Boolean {
            return p.finished || !withinDistance(local, p, largeSceneView)
        }

    private fun needsAdd(packet: PlayerUpdatePacket, local: PlayerUpdatePlayer, p: PlayerUpdatePlayer?, largeSceneView: Boolean): Boolean {
            return p != null && !p.finished && withinDistance(local, p, largeSceneView) && packet.localAddedPlayers < 15
        }

    private fun updateRegionHash(stream: BufferWriter, lastHash: Int, currentHash: Int) {
            val lastX = lastHash shr 8
            val lastY = lastHash and 0xff
            val lastPlane = lastHash shr 16
            val currentX = currentHash shr 8
            val currentY = currentHash and 0xff
            val currentPlane = currentHash shr 16
            val planeOffset = currentPlane - lastPlane

            if (lastX == currentX && lastY == currentY) {
                stream.writeBits(2, 1)
                stream.writeBits(2, planeOffset)
            } else if (abs(currentX - lastX) <= 1 && abs(currentY - lastY) <= 1) {
                val dx = currentX - lastX
                val dy = currentY - lastY
                val opcode = when {
                    dx == -1 && dy == -1 -> 0
                    dx == 1 && dy == -1 -> 2
                    dx == -1 && dy == 1 -> 5
                    dx == 1 && dy == 1 -> 7
                    dy == -1 -> 1
                    dx == -1 -> 3
                    dx == 1 -> 4
                    else -> 6
                }
                stream.writeBits(2, 2)
                stream.writeBits(5, (planeOffset shl 3) + (opcode and 0x7))
            } else {
                val xOff = currentX - lastX
                val yOff = currentY - lastY
                stream.writeBits(2, 3)
                stream.writeBits(18, (yOff and 0xff) + ((xOff and 0xff) shl 8) + (planeOffset shl 16))
            }
        }

    private fun skipPlayers(stream: BufferWriter, amount: Int) {
            when {
                amount == 0 -> stream.writeBits(2, 0)
                amount < 32 -> {
                    stream.writeBits(2, 1)
                    stream.writeBits(5, amount)
                }
                amount < 256 -> {
                    stream.writeBits(2, 2)
                    stream.writeBits(8, amount)
                }
                else -> {
                    stream.writeBits(2, 3)
                    stream.writeBits(11, amount)
                }
            }
        }

    private fun processOutsidePlayers(stream: BufferWriter, updateBlocks: BufferWriter, packet: PlayerUpdatePacket, local: PlayerUpdatePlayer, playersByIndex: Array<PlayerUpdatePlayer?>, largeSceneView: Boolean, nsn2: Boolean) {
            stream.startBitAccess()
            var skip = 0
            packet.localAddedPlayers = 0

            for (i in 0 until packet.outIndexCount) {
                val playerIndex = packet.outIndexes[i]
                if (nsn2 == ((packet.slotFlags[playerIndex].toInt() and 0x1) == 0)) continue

                if (skip > 0) {
                    skip--
                    packet.slotFlags[playerIndex] = (packet.slotFlags[playerIndex].toInt() or 2).toByte()
                    continue
                }

                val p = playersByIndex[playerIndex]
                if (needsAdd(packet, local, p, largeSceneView)) {
                    val player = p!!
                    stream.writeBits(1, 1)
                    stream.writeBits(2, 0)

                    val hash = player.regionHash18
                    if (hash == packet.regionHashes[playerIndex]) {
                        stream.writeBits(1, 0)
                    } else {
                        stream.writeBits(1, 1)
                        updateRegionHash(stream, packet.regionHashes[playerIndex], hash)
                        packet.regionHashes[playerIndex] = hash
                    }

                    stream.writeBits(6, player.xInRegion)
                    stream.writeBits(6, player.yInRegion)

                    appendUpdateBlock(updateBlocks, packet, player, added = true)
                    stream.writeBits(1, 1)

                    packet.localAddedPlayers++
                    packet.localPlayers[player.index] = player
                    packet.slotFlags[playerIndex] = (packet.slotFlags[playerIndex].toInt() or 2).toByte()
                } else {
                    val hash = p?.regionHash18 ?: packet.regionHashes[playerIndex]
                    if (p != null && hash != packet.regionHashes[playerIndex]) {
                        stream.writeBits(1, 1)
                        updateRegionHash(stream, packet.regionHashes[playerIndex], hash)
                        packet.regionHashes[playerIndex] = hash
                    } else {
                        stream.writeBits(1, 0)
                        for (i2 in i + 1 until packet.outIndexCount) {
                            val p2Index = packet.outIndexes[i2]
                            if (nsn2 == ((packet.slotFlags[p2Index].toInt() and 0x1) == 0)) continue
                            val p2 = playersByIndex[p2Index]
                            if (needsAdd(packet, local, p2, largeSceneView) || (p2 != null && p2.regionHash18 != packet.regionHashes[p2Index])) break
                            skip++
                        }
                        skipPlayers(stream, skip)
                        packet.slotFlags[playerIndex] = (packet.slotFlags[playerIndex].toInt() or 2).toByte()
                    }
                }
            }
            stream.stopBitAccess()
        }

    private fun processLocalPlayers(stream: BufferWriter, updateBlocks: BufferWriter, packet: PlayerUpdatePacket, local: PlayerUpdatePlayer, largeSceneView: Boolean, nsn0: Boolean) {
            stream.startBitAccess()
            var skip = 0

            for (i in 0 until packet.localIndexCount) {
                val playerIndex = packet.localIndexes[i]
                if (nsn0 == ((packet.slotFlags[playerIndex].toInt() and 0x1) != 0)) continue

                if (skip > 0) {
                    skip--
                    packet.slotFlags[playerIndex] = (packet.slotFlags[playerIndex].toInt() or 2).toByte()
                    continue
                }

                val p = packet.localPlayers[playerIndex] ?: continue
                if (needsRemove(local, p, largeSceneView)) {
                    stream.writeBits(1, 1)
                    stream.writeBits(1, 0)
                    stream.writeBits(2, 3)
                    packet.regionHashes[playerIndex] = p.regionHash18
                    packet.localPlayers[playerIndex] = null
                    packet.slotFlags[playerIndex] = (packet.slotFlags[playerIndex].toInt() or 2).toByte()
                    continue
                }

                val needUpdate = needsUpdateBlock(p)
                if (needUpdate) appendUpdateBlock(updateBlocks, packet, p, added = false)

                if (p.hasTeleported) {
                    stream.writeBits(1, 1)
                    stream.writeBits(1, if (needUpdate) 1 else 0)
                    stream.writeBits(2, 3)
                    val last = p.lastTileHash30
                    val curr = p.tileHash30
                    val lastX = (last shr 14) and 0x3fff
                    val lastY = last and 0x3fff
                    val lastPlane = (last shr 28) and 0x3
                    val currX = (curr shr 14) and 0x3fff
                    val currY = curr and 0x3fff
                    val currPlane = (curr shr 28) and 0x3
                    var xOff = currX - lastX
                    var yOff = currY - lastY
                    val planeOff = currPlane - lastPlane
                    if (abs(xOff) <= 14 && abs(yOff) <= 14) {
                        stream.writeBits(1, 0)
                        if (xOff < 0) xOff += 32
                        if (yOff < 0) yOff += 32
                        stream.writeBits(12, yOff + (xOff shl 5) + (planeOff shl 10))
                    } else {
                        stream.writeBits(1, 1)
                        stream.writeBits(30, (yOff and 0x3fff) + ((xOff and 0x3fff) shl 14) + ((planeOff and 0x3) shl 28))
                    }
                } else if (p.nextWalkDirection != null) {
                    stream.writeBits(1, 1)
                    stream.writeBits(1, if (needUpdate) 1 else 0)
                    if (p.nextRunDirectionOrOpcode != null) {
                        stream.writeBits(2, 2)
                        stream.writeBits(4, p.nextRunDirectionOrOpcode)
                    } else {
                        stream.writeBits(2, 1)
                        stream.writeBits(3, p.nextWalkDirection)
                    }
                    packet.slotFlags[playerIndex] = (packet.slotFlags[playerIndex].toInt() or 2).toByte()
                } else if (needUpdate) {
                    stream.writeBits(1, 1)
                    stream.writeBits(1, 1)
                    stream.writeBits(2, 0)
                    packet.slotFlags[playerIndex] = (packet.slotFlags[playerIndex].toInt() or 2).toByte()
                } else {
                    stream.writeBits(1, 0)
                    for (i2 in i + 1 until packet.localIndexCount) {
                        val p2Index = packet.localIndexes[i2]
                        if (nsn0 == ((packet.slotFlags[p2Index].toInt() and 0x1) != 0)) continue
                        val p2 = packet.localPlayers[p2Index] ?: continue
                        val p2Need = needsRemove(local, p2, largeSceneView) || p2.hasTeleported || p2.nextWalkDirection != null || needsUpdateBlock(p2)
                        if (p2Need) break
                        skip++
                    }
                    skipPlayers(stream, skip)
                    packet.slotFlags[playerIndex] = (packet.slotFlags[playerIndex].toInt() or 2).toByte()
                }
            }
            stream.stopBitAccess()
        }

    private fun needsUpdateBlock(p: PlayerUpdatePlayer): Boolean {
            // Any non-null update block means “needs update”.
            val b = p.blocks
            val c = b.common
            if (c.tint != null) return true
            if (c.faceEntity != null) return true
            if (c.faceTile != null) return true
            if (c.forceMovement != null) return true
            if (c.hits != null) return true
            if (c.modelOverrides != null) return true
            for (s in c.spotAnims) if (s != null) return true
            if (b.appearance != null) return true
            if (b.movementAnimations != null) return true
            if (b.faceDirection != null) return true
            if (b.movementTypeId != null) return true
            if (b.temporaryMoveTypeId != null) return true
            if (b.reduceAttackPriority != null) return true
            if (b.mapDot != null) return true
            if (b.playerConfigsNoClear != null) return true
            if (b.playerConfigsClear != null) return true
            if (b.forceTalk != null) return true
            if (b.publicChat != null) return true
            if (b.sequences != null) return true
            return false
        }

    private fun appendUpdateBlock(out: BufferWriter, packet: PlayerUpdatePacket, p: PlayerUpdatePlayer, added: Boolean) {
        val block = BufferWriter(4096)
        val ctx = PlayerMaskCtx(packet = packet, player = p, added = added)
        val present = buildPlayerMaskPresence(packet, p, added)
        playerMasks.encode(ctx, present, out, block)
        out.writeBytes(block.toArray())
    }

    // ----------------------------
    // NPC update
    // ----------------------------

    fun encodeNpcUpdate(packet: NpcUpdatePacket): ByteArray {
        val stream = BufferWriter(capacity = 16384)
        val maskData = BufferWriter(capacity = 16384)

        stream.startBitAccess()
        processInScreen(stream, maskData, packet)
        addInScreen(stream, maskData, packet)
        if (maskData.position() > 0) {
            stream.writeBits(15, 32767)
        }
        stream.stopBitAccess()

        stream.writeBytes(maskData.toArray())
        return stream.toArray()
    }

    private fun within(localTileHash30: Int, npc: NpcUpdateNpc, radius: Int): Boolean {
        val localX = (localTileHash30 shr 14) and 0x3fff
        val localY = localTileHash30 and 0x3fff
        return abs(npc.x - localX) <= radius && abs(npc.y - localY) <= radius
    }

    private fun processInScreen(stream: BufferWriter, maskOut: BufferWriter, packet: NpcUpdatePacket) {
        stream.writeBits(8, packet.localCount)
        val radius = if (packet.largeSceneView) 126 else 14

        var writeIndex = 0
        for (i in 0 until packet.localCount) {
            val idx = packet.localIndexes[i]
            val npc = packet.nearbyNpcs.firstOrNull { it.index == idx }
            if (npc == null || npc.finished || npc.hasTeleported || !within(packet.localTileHash30, npc, radius)) {
                stream.writeBits(1, 1)
                stream.writeBits(2, 3)
                continue
            }

            val needUpdate = needsNpcUpdateBlock(npc)
            val walkUpdate = npc.nextWalkDirection != null
            stream.writeBits(1, if (needUpdate || walkUpdate) 1 else 0)
            if (walkUpdate) {
                when (npc.movementType) {
                    NpcMovementType.WALK -> {
                        stream.writeBits(2, 1)
                        stream.writeBits(3, npc.nextWalkDirection)
                    }
                    NpcMovementType.HALF_WALK -> {
                        stream.writeBits(2, 2)
                        stream.writeBits(1, 0)
                        stream.writeBits(3, npc.nextWalkDirection)
                    }
                    NpcMovementType.RUN -> {
                        stream.writeBits(2, 2)
                        stream.writeBits(1, 1)
                        stream.writeBits(3, npc.nextWalkDirection)
                        stream.writeBits(3, npc.nextRunDirection ?: 0)
                    }
                }
                stream.writeBits(1, if (needUpdate) 1 else 0)
            } else if (needUpdate) {
                stream.writeBits(2, 0)
            }

            if (needUpdate) {
                appendNpcMasks(maskOut, npc)
            }

            packet.localIndexes[writeIndex++] = idx
        }

        packet.localCount = writeIndex
    }

    private fun addInScreen(stream: BufferWriter, maskOut: BufferWriter, packet: NpcUpdatePacket) {
        val radius = if (packet.largeSceneView) 126 else 14
        val localX = (packet.localTileHash30 shr 14) and 0x3fff
        val localY = packet.localTileHash30 and 0x3fff

        for (npc in packet.nearbyNpcs) {
            if (packet.localCount >= packet.localIndexes.size) break
            if (packet.localIndexes.take(packet.localCount).any { it == npc.index }) continue
            if (npc.finished || !within(packet.localTileHash30, npc, radius)) continue

            stream.writeBits(15, npc.index)
            val needUpdate = needsNpcUpdateBlock(npc)

            val dx = npc.x - localX
            val dy = npc.y - localY
            val encX = if (packet.largeSceneView) ((dx and 0xff) + 256) % 256 else ((dx and 0x1f) + 32) % 32
            val encY = if (packet.largeSceneView) ((dy and 0xff) + 256) % 256 else ((dy and 0x1f) + 32) % 32

            stream.writeBits(1, if (needUpdate) 1 else 0)
            stream.writeBits(if (packet.largeSceneView) 8 else 5, encY)
            stream.writeBits(3, (npc.faceAngle shr 11) - 4)
            stream.writeBits(15, npc.id)
            stream.writeBits(if (packet.largeSceneView) 8 else 5, encX)
            stream.writeBits(1, if (npc.hasTeleported) 1 else 0)
            stream.writeBits(2, npc.plane)

            packet.localIndexes[packet.localCount++] = npc.index
            if (needUpdate) {
                appendNpcMasks(maskOut, npc)
            }
        }
    }

    private fun appendNpcMasks(out: BufferWriter, npc: NpcUpdateNpc) {
        val block = BufferWriter(4096)
        val ctx = NpcMaskCtx(npc = npc)
        val present = buildNpcMaskPresence(npc)
        npcMasks.encode(ctx, present, out, block)
        out.writeBytes(block.toArray())
    }
    }

    private fun needsNpcUpdateBlock(npc: NpcUpdateNpc): Boolean {
        val b = npc.blocks
        val c = b.common
        if (b.movementAnimations != null) return true
        if (b.customisation != null) return true
        if (b.headCustomisation != null) return true
        if (b.basId != null) return true
        if (b.varnsNoClear != null) return true
        if (b.varnsClear != null) return true
        if (b.transformNpcId != null) return true
        if (b.updateStats != null) return true
        if (b.combatLevel != null) return true
        if (b.name != null) return true
        if (b.say != null) return true
        if (b.sequences != null) return true
        if (c.tint != null) return true
        if (c.forceMovement != null) return true
        if (c.faceEntity != null) return true
        if (c.faceTile != null) return true
        if (c.hits != null) return true
        if (c.modelOverrides != null) return true
        for (s in c.spotAnims) if (s != null) return true
        return false
    }

    private fun encodeHitsPlayer(out: BufferWriter, hits: HitsUpdate) {
        out.writeByteSubtract(hits.hitSplats.size)

        for (splat in hits.hitSplats) {
            when (splat) {
                is HitSplat.Miss -> {
                    out.writeSmart(32766)
                    out.writeByteInverse(splat.missValue)
                    out.writeSmart(splat.delay)
                }
                is HitSplat.Soak -> {
                    val big = splat.value > Short.MAX_VALUE / 2 || splat.soakValue > Short.MAX_VALUE / 2
                    if (big) {
                        out.writeSmart(32764)
                        out.writeSmart(splat.id)
                        out.writeString(splat.value.toString())
                        out.writeSmart(splat.soakId)
                        out.writeString(splat.soakValue.toString())
                    } else {
                        out.writeSmart(32767)
                        out.writeSmart(splat.id)
                        out.writeSmart(splat.value)
                        out.writeSmart(splat.soakId)
                        out.writeSmart(splat.soakValue)
                    }
                    out.writeSmart(splat.delay)
                }
                is HitSplat.Standard -> {
                    val big = splat.value > Short.MAX_VALUE / 2
                    if (big) {
                        out.writeSmart(32765)
                        out.writeSmart(splat.id)
                        out.writeString(splat.value.toString())
                    } else {
                        out.writeSmart(splat.id)
                        out.writeSmart(splat.value)
                    }
                    out.writeSmart(splat.delay)
                }
                is HitSplat.CustomText -> {
                    if (splat.soakId != null && splat.soakText != null) {
                        out.writeSmart(32764)
                        out.writeSmart(splat.id)
                        out.writeString(splat.text)
                        out.writeSmart(splat.soakId)
                        out.writeString(splat.soakText)
                    } else {
                        out.writeSmart(32765)
                        out.writeSmart(splat.id)
                        out.writeString(splat.text)
                    }
                    out.writeSmart(splat.delay)
                }
            }
        }

        out.writeByte(hits.hitBars.size)
        for (bar in hits.hitBars) {
            out.writeSmart(bar.type)
            out.writeSmart(bar.timer)
            if (bar.timer != 32767) {
                out.writeSmart(bar.delay)
                out.writeByteSubtract(bar.fromPercentage)
                if (bar.toPercentage != bar.fromPercentage) {
                    out.writeByteAdd(bar.toPercentage)
                }
            }
        }
    }

    private fun encodeHitsNpc(out: BufferWriter, hits: HitsUpdate) {
        out.writeByteAdd(hits.hitSplats.size)

        for (splat in hits.hitSplats) {
            when (splat) {
                is HitSplat.Miss -> {
                    out.writeSmart(32766)
                    out.writeByteInverse(splat.missValue)
                    out.writeSmart(splat.delay)
                }
                is HitSplat.Soak -> {
                    val big = splat.value > Short.MAX_VALUE / 2 || splat.soakValue > Short.MAX_VALUE / 2
                    if (big) {
                        out.writeSmart(32764)
                        out.writeSmart(splat.id)
                        out.writeString(splat.value.toString())
                        out.writeSmart(splat.soakId)
                        out.writeString(splat.soakValue.toString())
                    } else {
                        out.writeSmart(32767)
                        out.writeSmart(splat.id)
                        out.writeSmart(splat.value)
                        out.writeSmart(splat.soakId)
                        out.writeSmart(splat.soakValue)
                    }
                    out.writeSmart(splat.delay)
                }
                is HitSplat.Standard -> {
                    val big = splat.value > Short.MAX_VALUE / 2
                    if (big) {
                        out.writeSmart(32765)
                        out.writeSmart(splat.id)
                        out.writeString(splat.value.toString())
                    } else {
                        out.writeSmart(splat.id)
                        out.writeSmart(splat.value)
                    }
                    out.writeSmart(splat.delay)
                }
                is HitSplat.CustomText -> {
                    if (splat.soakId != null && splat.soakText != null) {
                        out.writeSmart(32764)
                        out.writeSmart(splat.id)
                        out.writeString(splat.text)
                        out.writeSmart(splat.soakId)
                        out.writeString(splat.soakText)
                    } else {
                        out.writeSmart(32765)
                        out.writeSmart(splat.id)
                        out.writeString(splat.text)
                    }
                    out.writeSmart(splat.delay)
                }
            }
        }

        out.writeByteAdd(hits.hitBars.size)
        for (bar in hits.hitBars) {
            out.writeSmart(bar.type)
            out.writeSmart(bar.timer)
            if (bar.timer != 32767) {
                out.writeSmart(bar.delay)
                out.writeByteSubtract(bar.fromPercentage)
                if (bar.toPercentage != bar.fromPercentage) {
                    out.writeByteAdd(bar.toPercentage)
                }
            }
        }
    }

    private fun encodeNpcCustomisation(out: BufferWriter, custom: NpcCustomisation) {
        // Client expects a flag byte, then optional arrays depending on bits.
        // This is revision-agnostic semantics, but rev727 follows the same shape as the bot decode.
        if (custom.clear) {
            out.writeByteSubtract(0x1)
            return
        }
        var flags = 0
        if (custom.models != null) flags = flags or 0x2
        if (custom.recolors != null) flags = flags or 0x4
        if (custom.retextures != null) flags = flags or 0x8
        out.writeByteSubtract(flags)
        if (custom.models != null) {
            for (id in custom.models) writeBigSmart(out, id)
        }
        if (custom.recolors != null) {
            for (c in custom.recolors) out.writeShort(c.toInt() and 0xffff)
        }
        if (custom.retextures != null) {
            for (t in custom.retextures) out.writeShort(t.toInt() and 0xffff)
        }
    }

    private fun encodeNpcHeadCustomisation(out: BufferWriter, custom: NpcCustomisation) {
        // Matches bot decode semantics (note: client creates object but doesn't assign in provided source).
        if (custom.clear) {
            out.writeByteAdd(0x1)
            return
        }
        var flags = 0
        if (custom.models != null) flags = flags or 0x2
        if (custom.recolors != null) flags = flags or 0x4
        if (custom.retextures != null) flags = flags or 0x8
        out.writeByteAdd(flags)
        if (custom.models != null) {
            for (id in custom.models) writeBigSmart(out, id)
        }
        if (custom.recolors != null) {
            for (c in custom.recolors) out.writeShort(c.toInt() and 0xffff)
        }
        if (custom.retextures != null) {
            for (t in custom.retextures) out.writeShort(t.toInt() and 0xffff)
        }
    }

    private fun writeBigSmart(out: BufferWriter, value: Int) {
        when {
            value == -1 -> out.writeShort(32767)
            value in 0..32766 -> out.writeShort(value)
            else -> out.writeInt(value or 0x80000000.toInt())
        }
    }
 
