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
import com.rs.game.World
import com.rs.game.model.entity.Hit
import com.rs.game.model.entity.HitBar
import org.darkan.core.Constants
import org.darkan.core.model.MoveType
import org.darkan.core.Utils
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.buffer.write.Writer
import java.security.MessageDigest
import kotlin.math.abs

class LocalPlayerUpdate(private val player: Player) {

    private val slotFlags = ByteArray(2048)

    @JvmField
    val localPlayers = arrayOfNulls<Player>(2048)
    private val localPlayersIndexes = IntArray(Settings.PLAYERS_LIMIT)
    private var localPlayersIndexesCount = 0

    private val outPlayersIndexes = IntArray(2048)
    private var outPlayersIndexesCount = 0

    private val regionHashes = IntArray(2048)

    private val cachedAppearencesHashes = arrayOfNulls<ByteArray>(Settings.PLAYERS_LIMIT)
    private var totalRenderDataSentLength = 0

    /** The amount of local players added this tick. */
    private var localAddedPlayers = 0

    fun needAppearenceUpdate(index: Int, hash: ByteArray?): Boolean {
        if (totalRenderDataSentLength > ((Constants.PACKET_SIZE_LIMIT - 500) / 2) || hash == null)
            return false
        return cachedAppearencesHashes[index] == null || !MessageDigest.isEqual(cachedAppearencesHashes[index], hash)
    }

    fun init(stream: BufferWriter) {
        stream.startBitAccess()
        stream.writeBits(30, player.tileHash)
        localPlayers[player.index] = player
        localPlayersIndexes[localPlayersIndexesCount++] = player.index
        for (playerIndex in 1 until 2048) {
            if (playerIndex == player.index) continue
            val p = World.players[playerIndex]
            stream.writeBits(18, (p?.regionHash ?: 0).also { regionHashes[playerIndex] = it })
            outPlayersIndexes[outPlayersIndexesCount++] = playerIndex
        }
        stream.stopBitAccess()
    }

    private fun needsRemove(p: Player): Boolean {
        return p.hasFinished() || !player.withinDistance(p, if (player.hasLargeSceneView()) 126 else 14)
    }

    private fun needsAdd(p: Player?): Boolean {
        return p != null && !p.hasFinished() && player.withinDistance(p, if (player.hasLargeSceneView()) 126 else 14) && localAddedPlayers < MAX_PLAYER_ADD
    }

    private fun updateRegionHash(stream: BufferWriter, lastRegionHash: Int, currentRegionHash: Int) {
        val lastRegionX = lastRegionHash shr 8
        val lastRegionY = 0xff and lastRegionHash
        val lastPlane = lastRegionHash shr 16
        val currentRegionX = currentRegionHash shr 8
        val currentRegionY = 0xff and currentRegionHash
        val currentPlane = currentRegionHash shr 16
        val planeOffset = currentPlane - lastPlane
        if (lastRegionX == currentRegionX && lastRegionY == currentRegionY) {
            stream.writeBits(2, 1)
            stream.writeBits(2, planeOffset)
        } else if (abs(currentRegionX - lastRegionX) <= 1 && abs(currentRegionY - lastRegionY) <= 1) {
            val dx = currentRegionX - lastRegionX
            val dy = currentRegionY - lastRegionY
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
            val xOffset = currentRegionX - lastRegionX
            val yOffset = currentRegionY - lastRegionY
            stream.writeBits(2, 3)
            stream.writeBits(18, (yOffset and 0xff) + ((xOffset and 0xff) shl 8) + (planeOffset shl 16))
        }
    }

    private fun processOutsidePlayers(stream: BufferWriter, updateBlockData: BufferWriter, nsn2: Boolean) {
        stream.startBitAccess()
        var skip = 0
        localAddedPlayers = 0
        for (i in 0 until outPlayersIndexesCount) {
            val playerIndex = outPlayersIndexes[i]
            if (nsn2 == ((0x1 and slotFlags[playerIndex].toInt()) == 0))
                continue
            if (skip > 0) {
                skip--
                slotFlags[playerIndex] = (slotFlags[playerIndex].toInt() or 2).toByte()
                continue
            }
            val p = World.players[playerIndex]
            if (needsAdd(p)) {
                stream.writeBits(1, 1)
                stream.writeBits(2, 0) // request add
                val hash = p!!.regionHash
                if (hash == regionHashes[playerIndex])
                    stream.writeBits(1, 0)
                else {
                    stream.writeBits(1, 1)
                    updateRegionHash(stream, regionHashes[playerIndex], hash)
                    regionHashes[playerIndex] = hash
                }
                stream.writeBits(6, p.xInRegion)
                stream.writeBits(6, p.yInRegion)
                val needAppearenceUpdate = needAppearenceUpdate(p.index, p.appearance.mD5AppeareanceDataHash)
                appendUpdateBlock(p, updateBlockData, needAppearenceUpdate, true)
                stream.writeBits(1, 1)
                localAddedPlayers++
                localPlayers[p.index] = p
                slotFlags[playerIndex] = (slotFlags[playerIndex].toInt() or 2).toByte()
            } else {
                val hash = if (p == null) regionHashes[playerIndex] else p.regionHash
                if (p != null && hash != regionHashes[playerIndex]) {
                    stream.writeBits(1, 1)
                    updateRegionHash(stream, regionHashes[playerIndex], hash)
                    regionHashes[playerIndex] = hash
                } else {
                    stream.writeBits(1, 0) // no update needed
                    for (i2 in i + 1 until outPlayersIndexesCount) {
                        val p2Index = outPlayersIndexes[i2]
                        if (nsn2 == ((0x1 and slotFlags[p2Index].toInt()) == 0))
                            continue
                        val p2 = World.players[p2Index]
                        if (needsAdd(p2) || (p2 != null && p2.regionHash != regionHashes[p2Index]))
                            break
                        skip++
                    }
                    skipPlayers(stream, skip)
                    slotFlags[playerIndex] = (slotFlags[playerIndex].toInt() or 2).toByte()
                }
            }
        }
        stream.stopBitAccess()
    }

    private fun processLocalPlayers(stream: BufferWriter, updateBlockData: BufferWriter, nsn0: Boolean) {
        stream.startBitAccess()
        var skip = 0
        for (i in 0 until localPlayersIndexesCount) {
            val playerIndex = localPlayersIndexes[i]
            if (nsn0 == ((0x1 and slotFlags[playerIndex].toInt()) != 0))
                continue
            if (skip > 0) {
                skip--
                slotFlags[playerIndex] = (slotFlags[playerIndex].toInt() or 2).toByte()
                continue
            }
            val p = localPlayers[playerIndex]!!
            if (needsRemove(p)) {
                stream.writeBits(1, 1) // needs update
                stream.writeBits(1, 0) // no masks update needed
                stream.writeBits(2, 0) // request remove
                regionHashes[playerIndex] = if (p.lastTile == null) p.regionHash else p.lastTile.getRegionHash()
                val hash = p.regionHash
                if (hash == regionHashes[playerIndex])
                    stream.writeBits(1, 0)
                else {
                    stream.writeBits(1, 1)
                    updateRegionHash(stream, regionHashes[playerIndex], hash)
                    regionHashes[playerIndex] = hash
                }
                localPlayers[playerIndex] = null
                continue
            }
            val needAppearenceUpdate = needAppearenceUpdate(p.index, p.appearance.mD5AppeareanceDataHash)
            var needUpdate = p.needMasksUpdate() || needAppearenceUpdate
            if (needUpdate)
                appendUpdateBlock(p, updateBlockData, needAppearenceUpdate, false)
            if (p.hasTeleported()) {
                stream.writeBits(1, 1) // needs update
                stream.writeBits(1, if (needUpdate) 1 else 0)
                stream.writeBits(2, 3)
                var xOffset = p.x - p.lastTile.x
                var yOffset = p.y - p.lastTile.y
                val planeOffset = p.plane - p.lastTile.plane
                if (abs(xOffset) <= 14 && abs(yOffset) <= 14) {
                    stream.writeBits(1, 0)
                    if (xOffset < 0) xOffset += 32
                    if (yOffset < 0) yOffset += 32
                    stream.writeBits(12, yOffset + (xOffset shl 5) + (planeOffset shl 10))
                } else {
                    stream.writeBits(1, 1)
                    stream.writeBits(30, (yOffset and 0x3fff) + ((xOffset and 0x3fff) shl 14) + ((planeOffset and 0x3) shl 28))
                }
            } else if (p.nextWalkDirection != null) {
                val dx: Int
                val dy: Int
                val running: Boolean
                val opcode: Int
                if (p.nextRunDirection != null) {
                    val totalDx = p.nextWalkDirection.dx + p.nextRunDirection.dx
                    val totalDy = p.nextWalkDirection.dy + p.nextRunDirection.dy
                    val runOpcode = Utils.getPlayerRunningDirection(totalDx, totalDy)
                    if (runOpcode == -1) {
                        running = false
                        opcode = Utils.getPlayerWalkingDirection(totalDx, totalDy)
                    } else {
                        running = true
                        opcode = runOpcode
                    }
                    dx = totalDx
                    dy = totalDy
                } else {
                    running = false
                    opcode = Utils.getPlayerWalkingDirection(p.nextWalkDirection.dx, p.nextWalkDirection.dy)
                    dx = p.nextWalkDirection.dx
                    dy = p.nextWalkDirection.dy
                }
                stream.writeBits(1, 1)
                if (dx == 0 && dy == 0) {
                    stream.writeBits(1, 1) // quick fix
                    stream.writeBits(2, 0)
                    if (!needUpdate) // hasn't been sent yet
                        appendUpdateBlock(p, updateBlockData, needAppearenceUpdate, false)
                } else {
                    stream.writeBits(1, if (needUpdate) 1 else 0)
                    stream.writeBits(2, if (running) 2 else 1)
                    stream.writeBits(if (running) 4 else 3, opcode)
                }
            } else if (needUpdate) {
                stream.writeBits(1, 1) // needs update
                stream.writeBits(1, 1)
                stream.writeBits(2, 0)
            } else { // skip
                stream.writeBits(1, 0) // no update needed
                for (i2 in i + 1 until localPlayersIndexesCount) {
                    val p2Index = localPlayersIndexes[i2]
                    if (nsn0 == ((0x1 and slotFlags[p2Index].toInt()) != 0))
                        continue
                    val p2 = localPlayers[p2Index] ?: break
                    if (needsRemove(p2) || p2.hasTeleported() || p2.nextWalkDirection != null || (p2.needMasksUpdate() || needAppearenceUpdate(p2.index, p2.appearance.mD5AppeareanceDataHash)))
                        break
                    skip++
                }
                skipPlayers(stream, skip)
                slotFlags[playerIndex] = (slotFlags[playerIndex].toInt() or 2).toByte()
            }
        }
        stream.stopBitAccess()
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
            amount < 2048 -> {
                stream.writeBits(2, 3)
                stream.writeBits(11, amount)
            }
        }
    }

    private fun appendUpdateBlock(p: Player, data: BufferWriter, needAppearenceUpdate: Boolean, added: Boolean) {
        val block = BufferWriter(256)
        var maskData = 0

        if (p.nextBodyGlow != null) {
            maskData = maskData or 0x20000
            applyBodyGlowMask(p, block)
        }

        if (p.nextSpotAnim2 != null) {
            maskData = maskData or 0x200
            applyGraphicsMask2(p, block)
        }

        if (added || p.fixedFaceTile != null || (p.nextFaceTile != null && p.nextRunDirection == null && p.nextWalkDirection == null)) {
            maskData = maskData or 0x20
            applyFaceDirectionMask(p, block)
        }

        if (p.bodyModelRotator != null) {
            maskData = maskData or 0x100000
            p.bodyModelRotator.encodePlayer(block)
        }

        if (p.nextForceTalk != null) {
            maskData = maskData or 0x4000
            applyForceTalkMask(p, block)
        }

        if (p.nextSpotAnim3 != null) {
            maskData = maskData or 0x40000
            applyGraphicsMask3(p, block)
        }

        if (p.nextHits.isNotEmpty() || p.nextHitBars.isNotEmpty()) {
            maskData = maskData or 0x40
            applyHitsMask(p, block)
        }

        if (needAppearenceUpdate) {
            maskData = maskData or 0x1
            applyAppearanceMask(p, block)
        }

        if (p.nextAnimation != null) {
            maskData = maskData or 0x10
            applyAnimationMask(p, block)
        }

        if (p.nextFaceEntity != -2 || (added && p.lastFaceEntity != -1)) {
            maskData = maskData or 0x2
            applyFaceEntityMask(p, block)
        }

        if (p.temporaryMoveType != null) {
            maskData = maskData or 0x1000
            applyTemporaryMoveTypeMask(p, block)
        }

        if (added || p.isUpdateMovementType) {
            maskData = maskData or 0x4
            applyMoveTypeMask(p, block)
        }

        if (p.nextSpotAnim4 != null) {
            maskData = maskData or 0x200000
            applyGraphicsMask4(p, block)
        }

        if (p.nextForceMovement != null) {
            maskData = maskData or 0x800
            applyForceMovementMask(p, block)
        }

        if (p.nextSpotAnim1 != null) {
            maskData = maskData or 0x80
            applyGraphicsMask1(p, block)
        }

        if (maskData >= 256)
            maskData = maskData or 0x8
        if (maskData >= 65536)
            maskData = maskData or 0x100
        data.writeByte(maskData)
        if (maskData >= 256)
            data.writeByte(maskData shr 8)
        if (maskData >= 65536)
            data.writeByte(maskData shr 16)
        data.writeBytes(block.toArray())
    }

    private fun applyBodyGlowMask(p: Player, data: Writer) {
        data.writeByte(p.nextBodyGlow.redAdd)
        data.writeByteInverse(p.nextBodyGlow.greenAdd)
        data.writeByteSubtract(p.nextBodyGlow.blueAdd)
        data.writeByteSubtract(p.nextBodyGlow.scalar)
        data.writeShortAdd(0)
        data.writeShort(p.nextBodyGlow.time)
    }

    private fun applyForceTalkMask(p: Player, data: Writer) {
        data.writeString(p.nextForceTalk.text())
    }

    private fun applyHitsMask(p: Player, data: Writer) {
        data.writeByteSubtract(p.nextHits.size)
        for (hit in p.nextHits) {
            val interactingWith = hit.interactingWith(player, p)
            if (hit.text == null && hit.missed() && !interactingWith) {
                data.writeSmart(32766)
                data.writeByteInverse(0)
            } else if (hit.soaking != null) {
                if (hit.text != null || hit.damage > Short.MAX_VALUE / 2 || hit.soaking.damage > Short.MAX_VALUE / 2) {
                    data.writeSmart(32764)
                    data.writeSmart(hit.getMark(player, p))
                    data.writeString(hit.text ?: Utils.formatNumber(hit.damage))
                    data.writeSmart(hit.soaking.getMark(player, p))
                    data.writeString(Utils.formatNumber(hit.soaking.damage))
                } else {
                    data.writeSmart(32767)
                    data.writeSmart(hit.getMark(player, p))
                    data.writeSmart(hit.damage)
                    data.writeSmart(hit.soaking.getMark(player, p))
                    data.writeSmart(hit.soaking.damage)
                }
            } else {
                if (hit.text != null || hit.damage > Short.MAX_VALUE / 2) {
                    data.writeSmart(32765)
                    data.writeSmart(hit.getMark(player, p))
                    data.writeString(hit.text ?: Utils.formatNumber(hit.damage))
                } else {
                    data.writeSmart(hit.getMark(player, p))
                    data.writeSmart(hit.damage)
                }
            }
            data.writeSmart(hit.delay)
        }
        data.writeByte(p.nextHitBars.size)
        for (bar in p.nextHitBars) {
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

    private fun applyFaceEntityMask(p: Player, data: Writer) {
        data.writeShortAdd(if (p.nextFaceEntity == -2) p.lastFaceEntity else p.nextFaceEntity)
    }

    private fun applyFaceDirectionMask(p: Player, data: Writer) {
        data.writeShortAdd(p.faceAngle)
    }

    private fun applyMoveTypeMask(p: Player, data: Writer) {
        data.writeByteSubtract(if (p.movementType == MoveType.RUN) 2 else 1)
    }

    private fun applyTemporaryMoveTypeMask(p: Player, data: Writer) {
        data.writeByteInverse(p.temporaryMoveType.id)
    }

    private fun applyGraphicsMask1(p: Player, data: Writer) {
        data.writeShortLittle(p.nextSpotAnim1.id)
        data.writeInt(p.nextSpotAnim1.settingsHash)
        data.writeByteAdd(p.nextSpotAnim1.settings2Hash)
    }

    private fun applyGraphicsMask2(p: Player, data: Writer) {
        data.writeShort(p.nextSpotAnim2.id)
        data.writeIntInverseMiddle(p.nextSpotAnim2.settingsHash)
        data.writeByteSubtract(p.nextSpotAnim2.settings2Hash)
    }

    private fun applyGraphicsMask3(p: Player, data: Writer) {
        data.writeShortAddLittle(p.nextSpotAnim3.id)
        data.writeIntInverseMiddle(p.nextSpotAnim3.settingsHash)
        data.writeByteAdd(p.nextSpotAnim3.settings2Hash)
    }

    private fun applyGraphicsMask4(p: Player, data: Writer) {
        data.writeShortLittle(p.nextSpotAnim4.id)
        data.writeIntLittle(p.nextSpotAnim4.settingsHash)
        data.writeByteSubtract(p.nextSpotAnim4.settings2Hash)
    }

    private fun applyAnimationMask(p: Player, data: Writer) {
        for (id in p.nextAnimation.ids)
            data.writeBigSmart(id)
        data.writeByte(p.nextAnimation.speed)
    }

    private fun applyAppearanceMask(p: Player, data: Writer) {
        val renderData = p.appearance.appeareanceData!!
        totalRenderDataSentLength += renderData.size
        cachedAppearencesHashes[p.index] = p.appearance.mD5AppeareanceDataHash
        data.writeByteInverse(renderData.size)
        data.writeBytes(renderData)
    }

    private fun applyForceMovementMask(p: Player, data: Writer) {
        data.writeByteInverse(p.nextForceMovement.diffX1)
        data.writeByteSubtract(p.nextForceMovement.diffY1)
        data.writeByteAdd(p.nextForceMovement.diffX2)
        data.writeByteInverse(p.nextForceMovement.diffY2)
        data.writeShortAddLittle(p.nextForceMovement.startClientCycles)
        data.writeShortLittle(p.nextForceMovement.speedClientCycles)
        data.writeShortAdd(p.nextForceMovement.direction)
    }

    fun write(stream: BufferWriter) {
        val updateBlockData = BufferWriter(40960)
        processLocalPlayers(stream, updateBlockData, true)
        processLocalPlayers(stream, updateBlockData, false)
        processOutsidePlayers(stream, updateBlockData, true)
        processOutsidePlayers(stream, updateBlockData, false)
        stream.writeBytes(updateBlockData.array(), 0, updateBlockData.position())
        totalRenderDataSentLength = 0
        localPlayersIndexesCount = 0
        outPlayersIndexesCount = 0
        for (playerIndex in 1 until 2048) {
            slotFlags[playerIndex] = (slotFlags[playerIndex].toInt() shr 1).toByte()
            val p = localPlayers[playerIndex]
            if (p == null)
                outPlayersIndexes[outPlayersIndexesCount++] = playerIndex
            else
                localPlayersIndexes[localPlayersIndexesCount++] = playerIndex
        }
    }

    companion object {
        private const val MAX_PLAYER_ADD = 15
    }
}
