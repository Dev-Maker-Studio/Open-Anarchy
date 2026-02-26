// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program.  If not, see <http://www.gnu.org/licenses/>.
//
//  Copyright (C) 2021 Trenton Kress
//  This file is part of project: Darkan
//
package com.rs.game.model.entity.player

import com.rs.Settings
import com.rs.game.model.entity.Hit
import com.rs.game.model.entity.HitBar
import com.rs.game.model.entity.npc.NPC
import com.rs.game.model.entity.npc.combat.MovementType
import com.rs.utils.movementType
import org.darkan.core.Utils
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.type.Tile
import java.util.LinkedList

class LocalNPCUpdate(private val player: Player) {

    private val localNPCs: LinkedList<NPC> = LinkedList()

    fun reset() {
        localNPCs.clear()
    }

    fun write(stream: BufferWriter, largeSceneView: Boolean) {
        val updateBlockData = BufferWriter(8192)
        processLocalNPCsInform(stream, updateBlockData, largeSceneView)
        stream.writeBytes(updateBlockData.array(), 0, updateBlockData.position())
    }

    private fun processLocalNPCsInform(stream: BufferWriter, updateBlockData: BufferWriter, largeSceneView: Boolean) {
        stream.startBitAccess()
        processInScreenNPCs(stream, updateBlockData, largeSceneView)
        addInScreenNPCs(stream, updateBlockData, largeSceneView)
        if (updateBlockData.position() > 0)
            stream.writeBits(15, 32767)
        stream.stopBitAccess()
    }

    private fun processInScreenNPCs(stream: BufferWriter, updateBlockData: BufferWriter, largeSceneView: Boolean) {
        stream.writeBits(8, localNPCs.size)
        val it = localNPCs.iterator()
        while (it.hasNext()) {
            val n = it.next()
            if (n.hasFinished() || !n.withinDistance(player, if (largeSceneView) 126 else 14) || n.hasTeleported()) {
                stream.writeBits(1, 1)
                stream.writeBits(2, 3)
                it.remove()
                continue
            }
            val needUpdate = n.needMasksUpdate()
            val walkUpdate = n.nextWalkDirection != null
            stream.writeBits(1, if (needUpdate || walkUpdate) 1 else 0)
            if (walkUpdate) {
                if (n.nextRunDirection == null) {
                    if (n.definitions.movementType == MovementType.HALF_WALK) {
                        stream.writeBits(2, 2)
                        stream.writeBits(1, 0)
                        stream.writeBits(3, n.nextWalkDirection.id)
                    } else {
                        stream.writeBits(2, 1)
                        stream.writeBits(3, n.nextWalkDirection.id)
                    }
                } else {
                    stream.writeBits(2, 2)
                    stream.writeBits(1, 1)
                    stream.writeBits(3, n.nextWalkDirection.id)
                    stream.writeBits(3, n.nextRunDirection.id)
                }
                stream.writeBits(1, if (needUpdate) 1 else 0)
            } else if (needUpdate) {
                stream.writeBits(2, 0)
            }
            if (needUpdate)
                appendUpdateBlock(n, updateBlockData, false)
        }
    }

    private fun addInScreenNPCs(stream: BufferWriter, updateBlockData: BufferWriter, largeSceneView: Boolean) {
        val radius = if (largeSceneView) 126 else 14
        for (n in player.queryNearbyNPCsByTileRange(radius) { npc -> !localNPCs.contains(npc) && npc.withinDistance(player, radius) }) {
            if (localNPCs.size == Settings.LOCAL_NPCS_LIMIT)
                break
            stream.writeBits(15, n.index)
            val needUpdate = n.needMasksUpdate() || n.lastFaceEntity != -1
            var x = n.x - player.x
            var y = n.y - player.y
            if (largeSceneView) {
                if (x < 127) x += 256
                if (y < 127) y += 256
            } else {
                if (x < 15) x += 32
                if (y < 15) y += 32
            }
            stream.writeBits(1, if (needUpdate) 1 else 0)
            stream.writeBits(if (largeSceneView) 8 else 5, y)
            stream.writeBits(3, (n.faceAngle shr 11) - 4)
            stream.writeBits(15, n.id)
            stream.writeBits(if (largeSceneView) 8 else 5, x)
            stream.writeBits(1, if (n.hasTeleported()) 1 else 0)
            stream.writeBits(2, n.plane)
            localNPCs.add(n)
            if (needUpdate)
                appendUpdateBlock(n, updateBlockData, true)
        }
    }

    private fun appendUpdateBlock(n: NPC, data: BufferWriter, added: Boolean) {
        val block = BufferWriter(256)
        var maskData = 0

        if (n.nextAnimation != null) {
            maskData = maskData or 0x10
            applyAnimationMask(n, block)
        }
        if (n.bodyMeshModifier != null) {
            maskData = maskData or 0x100
            applyBodyMeshModifierMask(n, block)
        }
        if (n.nextForceMovement != null) {
            maskData = maskData or 0x400
            applyForceMovementMask(n, block)
        }
        if (n.bas != -1) {
            maskData = maskData or 0x1000
            block.writeShort(if (n.bas == -2) -1 else n.bas)
        }
        if (n.nextSpotAnim4 != null) {
            maskData = maskData or 0x1000000
            applyGraphicsMask4(n, block)
        }
        if (n.nextFaceEntity != -2 || (added && n.lastFaceEntity != -1)) {
            maskData = maskData or 0x80
            applyFaceEntityMask(n, block)
        }
        if (n.nextSpotAnim2 != null) {
            maskData = maskData or 0x800
            applyGraphicsMask2(n, block)
        }
        if (n.nextHits.isNotEmpty() || n.nextHitBars.isNotEmpty()) {
            maskData = maskData or 0x1
            applyHitMask(n, block)
        }
        if (n.nextTransformation != null) {
            maskData = maskData or 0x8
            applyTransformationMask(n, block)
        }
        if (n.nextSpotAnim3 != null) {
            maskData = maskData or 0x2000000
            applyGraphicsMask3(n, block)
        }
        if (n.hasChangedCombatLevel() || (added && n.customCombatLevel >= 0)) {
            maskData = maskData or 0x10000
            applyChangeLevelMask(n, block)
        }
        if (n.fixedFaceTile != null || (n.nextFaceTile != null && n.nextRunDirection == null && n.nextWalkDirection == null)) {
            maskData = maskData or 0x4
            applyFaceTileMask(n, block)
        }
        if (n.nextBodyGlow != null) {
            maskData = maskData or 0x20000
            applyBodyGlowMask(n, block)
        }
        if (n.hasChangedName() || (added && n.customName != null)) {
            maskData = maskData or 0x800000
            applyNameChangeMask(n, block)
        }
        if (n.nextForceTalk != null) {
            maskData = maskData or 0x2
            applyForceTalkMask(n, block)
        }
        if (n.bodyModelRotator != null) {
            maskData = maskData or 0x4000
            n.bodyModelRotator.encodeNPC(block)
        }
        if (n.nextSpotAnim1 != null) {
            maskData = maskData or 0x20
            applyGraphicsMask1(n, block)
        }

        if (maskData > 0xff)
            maskData = maskData or 0x40
        if (maskData > 0xffff)
            maskData = maskData or 0x2000
        if (maskData > 0xffffff)
            maskData = maskData or 0x100000

        data.writeByte(maskData)
        if (maskData > 0xff)
            data.writeByte(maskData shr 8)
        if (maskData > 0xffff)
            data.writeByte(maskData shr 16)
        if (maskData > 0xffffff)
            data.writeByte(maskData shr 24)
        data.writeBytes(block.toArray())
    }

    private fun applyBodyMeshModifierMask(n: NPC, block: Writer) {
        n.bodyMeshModifier.encode(block)
    }

    private fun applyBodyGlowMask(n: NPC, data: Writer) {
        data.writeByteSubtract(n.nextBodyGlow.redAdd)
        data.writeByte(n.nextBodyGlow.greenAdd)
        data.writeByte(n.nextBodyGlow.blueAdd)
        data.writeByteSubtract(n.nextBodyGlow.scalar)
        data.writeShortLittle(0)
        data.writeShort(n.nextBodyGlow.time)
    }

    private fun applyChangeLevelMask(n: NPC, data: Writer) {
        data.writeShortAdd(n.combatLevel)
    }

    private fun applyNameChangeMask(npc: NPC, data: Writer) {
        data.writeString(npc.name)
    }

    private fun applyTransformationMask(n: NPC, data: Writer) {
        data.writeBigSmart(n.nextTransformation.toNPCId())
    }

    private fun applyForceTalkMask(n: NPC, data: Writer) {
        data.writeString(n.nextForceTalk.text())
    }

    private fun applyForceMovementMask(n: NPC, data: Writer) {
        data.writeByteSubtract(n.nextForceMovement.diffX1)
        data.writeByteAdd(n.nextForceMovement.diffY1)
        data.writeByteInverse(n.nextForceMovement.diffX2)
        data.writeByteAdd(n.nextForceMovement.diffY2)
        data.writeShortLittle(n.nextForceMovement.startClientCycles)
        data.writeShortLittle(n.nextForceMovement.speedClientCycles)
        data.writeShortAddLittle(n.nextForceMovement.direction)
    }

    private fun applyFaceTileMask(n: NPC, data: Writer) {
        val tile: Tile = n.fixedFaceTile ?: n.nextFaceTile
        data.writeShortAddLittle((tile.x shl 1) + 1)
        data.writeShortLittle((tile.y shl 1) + 1)
    }

    private fun applyHitMask(n: NPC, data: Writer) {
        data.writeByteAdd(n.nextHits.size)
        for (hit in n.nextHits.toTypedArray()) {
            val interactingWith = hit.interactingWith(player, n)
            if (hit.text == null && hit.missed() && !interactingWith) {
                data.writeSmart(32766)
                data.writeByteInverse(0)
            } else if (hit.soaking != null) {
                if (hit.text != null || hit.damage > Short.MAX_VALUE / 2 || hit.soaking.damage > Short.MAX_VALUE / 2) {
                    data.writeSmart(32764)
                    data.writeSmart(hit.getMark(player, n))
                    data.writeString(hit.text ?: Utils.formatNumber(hit.damage))
                    data.writeSmart(hit.soaking.getMark(player, n))
                    data.writeString(Utils.formatNumber(hit.soaking.damage))
                } else {
                    data.writeSmart(32767)
                    data.writeSmart(hit.getMark(player, n))
                    data.writeSmart(hit.damage)
                    data.writeSmart(hit.soaking.getMark(player, n))
                    data.writeSmart(hit.soaking.damage)
                }
            } else {
                if (hit.text != null || hit.damage > Short.MAX_VALUE / 2) {
                    data.writeSmart(32765)
                    data.writeSmart(hit.getMark(player, n))
                    data.writeString(hit.text ?: Utils.formatNumber(hit.damage))
                } else {
                    data.writeSmart(hit.getMark(player, n))
                    data.writeSmart(hit.damage)
                }
            }
            data.writeSmart(hit.delay)
        }
        data.writeByteAdd(n.nextHitBars.size)
        for (bar in n.nextHitBars) {
            data.writeSmart(bar.type)
            val perc = bar.percentage
            val toPerc = bar.toPercentage
            val display = bar.display(player)
            data.writeSmart(if (display) (if (perc != toPerc) bar.timer else 0) else 32767)
            if (display) {
                data.writeSmart(bar.delay)
                data.writeByteSubtract(perc)
                if (toPerc != perc)
                    data.writeByteAdd(toPerc)
            }
        }
    }

    private fun applyFaceEntityMask(n: NPC, data: Writer) {
        data.writeShortLittle(if (n.nextFaceEntity == -2) n.lastFaceEntity else n.nextFaceEntity)
    }

    private fun applyAnimationMask(n: NPC, data: Writer) {
        for (id in n.nextAnimation.ids)
            data.writeBigSmart(id)
        data.writeByte(n.nextAnimation.speed)
    }

    private fun applyGraphicsMask4(n: NPC, data: Writer) {
        data.writeShortAdd(n.nextSpotAnim4.id)
        data.writeIntInverseMiddle(n.nextSpotAnim4.settingsHash)
        data.writeByteAdd(n.nextSpotAnim4.settings2Hash)
    }

    private fun applyGraphicsMask3(n: NPC, data: Writer) {
        data.writeShortLittle(n.nextSpotAnim3.id)
        data.writeIntLittle(n.nextSpotAnim3.settingsHash)
        data.writeByte(n.nextSpotAnim3.settings2Hash)
    }

    private fun applyGraphicsMask2(n: NPC, data: Writer) {
        data.writeShortLittle(n.nextSpotAnim2.id)
        data.writeInt(n.nextSpotAnim2.settingsHash)
        data.writeByteAdd(n.nextSpotAnim2.settings2Hash)
    }

    private fun applyGraphicsMask1(n: NPC, data: Writer) {
        data.writeShort(n.nextSpotAnim1.id)
        data.writeIntInverseMiddle(n.nextSpotAnim1.settingsHash)
        data.writeByteInverse(n.nextSpotAnim1.settings2Hash)
    }
}
