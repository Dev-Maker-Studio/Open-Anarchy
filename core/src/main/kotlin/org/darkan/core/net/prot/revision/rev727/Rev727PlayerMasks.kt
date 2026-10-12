package org.darkan.core.net.prot.revision.rev727

import org.darkan.core.net.prot.update.*
import world.gregs.voidps.buffer.write.BufferWriter

internal data class PlayerMaskCtx(
    val state: PlayerUpdateTrackingState,
    val player: PlayerUpdatePlayer,
    val added: Boolean,
)

internal val playerHeader = MaskHeader(
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

internal val playerMasks: MaskRegistry<PlayerMaskCtx, PlayerMaskKey> =
    MaskRegistryBuilder<PlayerMaskCtx, PlayerMaskKey>(header = playerHeader).apply {
        mask(PlayerMaskKey.TINT, 0x20000) { ctx, out ->
                val t = ctx.player.blocks.common.tint!!
                out.writeByte(t.hue)
                out.writeByteInverse(t.saturation)
                out.writeByteSubtract(t.lightness)
                out.writeByteSubtract(t.opacity)
                out.writeShortAdd(t.startCycles)
                out.writeShort(t.endCycles)
        }

        mask(PlayerMaskKey.VARN_NO_CLEAR, 0x400000) { ctx, out ->
                val d = ctx.player.blocks.playerConfigsNoClear!!
                out.writeByte(d.keys.size)
                for (i in d.keys.indices) {
                    out.writeShortLittle(d.keys[i])
                    out.writeInt(d.values[i])
                }
        }

        mask(PlayerMaskKey.SPOT_ANIM_2, 0x200) { ctx, out ->
                val s1 = ctx.player.blocks.common.spotAnims[1]!!
                val settingsHash = (s1.height shl 16) or (s1.speed and 0xFFFF)
                val settings2Hash = s1.rotation and 0x7
                out.writeShort(if (s1.id == -1) 65535 else s1.id)
                out.writeIntInverseMiddle(settingsHash)
                out.writeByteSubtract(settings2Hash)
        }

        mask(PlayerMaskKey.FACE_DIRECTION, 0x20) { ctx, out ->
                val b = ctx.player.blocks
                val angle = b.faceDirection ?: b.persistentFaceDirection!!
                out.writeShortAdd(angle)
        }

        mask(PlayerMaskKey.VARN_CLEAR, 0x800000) { ctx, out ->
                val d = ctx.player.blocks.playerConfigsClear!!
                out.writeByte(d.keys.size)
                for (i in d.keys.indices) {
                    out.writeShortLittle(d.keys[i])
                    out.writeInt(d.values[i])
                }
        }

        mask(PlayerMaskKey.MAP_DOT_2, 0x10000) { ctx, out ->
                out.writeByteSubtract(if (ctx.player.blocks.reduceAttackPriority == true) 1 else 0)
        }

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

        mask(PlayerMaskKey.FORCE_TALK, 0x4000) { ctx, out ->
                out.writeString(ctx.player.blocks.forceTalk!!)
        }

        mask(PlayerMaskKey.FORCE_CHAT, 0x80000) { ctx, out ->
                val chat = ctx.player.blocks.publicChat!!
                out.writeString(chat.message)
                out.writeByteAdd(chat.chatFlags)
        }

        mask(PlayerMaskKey.SPOT_ANIM_3, 0x40000) { ctx, out ->
                val s2 = ctx.player.blocks.common.spotAnims[2]!!
                val settingsHash = (s2.height shl 16) or (s2.speed and 0xFFFF)
                val settings2Hash = s2.rotation and 0x7
                out.writeShortAddLittle(if (s2.id == -1) 65535 else s2.id)
                out.writeIntInverseMiddle(settingsHash)
                out.writeByteAdd(settings2Hash)
        }

        mask(PlayerMaskKey.MAP_DOT_1, 0x8000) { ctx, out ->
                out.writeByteSubtract(if (ctx.player.blocks.mapDot!!) 1 else 0)
        }

        mask(PlayerMaskKey.HITS, 0x40) { ctx, out ->
                encodeHits(out, ctx.player.blocks.common.hits!!,
                    writeSplatCount = { o, c -> o.writeByteSubtract(c) },
                    writeBarCount = { o, c -> o.writeByte(c) })
        }

        mask(PlayerMaskKey.APPEARANCE, 0x1) { ctx, out ->
                val app = ctx.player.blocks.appearance!!
                ctx.state.totalRenderDataSentLength += app.data.size
                ctx.state.cachedAppearanceHashes[ctx.player.index] = app.hash
                out.writeByteInverse(app.data.size)
                out.writeBytes(app.data)
        }

        mask(PlayerMaskKey.ANIMATION, 0x10) { ctx, out ->
                val ma = ctx.player.blocks.movementAnimations!!
                for (id in ma.ids) out.writeBigSmart(id)
                out.writeByte(ma.speed)
        }

        mask(PlayerMaskKey.FACE_ENTITY, 0x2) { ctx, out ->
                val c = ctx.player.blocks.common
                val face = c.faceEntity ?: c.persistentFaceEntity ?: -1
                val idx = if (face == -1) 65535 else face
                out.writeShortAdd(idx)
        }

        mask(PlayerMaskKey.TEMPORARY_MOVE_TYPE, 0x1000) { ctx, out ->
                out.writeByteInverse(ctx.player.blocks.temporaryMoveTypeId!!)
        }

        mask(PlayerMaskKey.ANIMATION_SEQUENCE, 0x2000) { ctx, out ->
                val s = ctx.player.blocks.sequences!!
                out.writeByteSubtract(s.ids.size)
                for (i in s.ids.indices) {
                    out.writeBigSmart(s.ids[i])
                    out.writeByteInverse(s.delays[i])
                    out.writeShortAddLittle(s.flags[i])
                }
        }

        mask(PlayerMaskKey.MOVE_TYPE, 0x4) { ctx, out ->
                val b = ctx.player.blocks
                val id = b.movementTypeId ?: b.persistentMovementTypeId!!
                out.writeByteSubtract(id)
        }

        mask(PlayerMaskKey.SPOT_ANIM_4, 0x200000) { ctx, out ->
                val s3 = ctx.player.blocks.common.spotAnims[3]!!
                val settingsHash = (s3.height shl 16) or (s3.speed and 0xFFFF)
                val settings2Hash = s3.rotation and 0x7
                out.writeShortLittle(if (s3.id == -1) 65535 else s3.id)
                out.writeIntLittle(settingsHash)
                out.writeByteSubtract(settings2Hash)
        }

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

        mask(PlayerMaskKey.SPOT_ANIM_1, 0x80) { ctx, out ->
                val s0 = ctx.player.blocks.common.spotAnims[0]!!
                val settingsHash = (s0.height shl 16) or (s0.speed and 0xFFFF)
                val settings2Hash = s0.rotation and 0x7
                out.writeShortLittle(if (s0.id == -1) 65535 else s0.id)
                out.writeInt(settingsHash)
                out.writeByteAdd(settings2Hash)
        }

    }.build()
