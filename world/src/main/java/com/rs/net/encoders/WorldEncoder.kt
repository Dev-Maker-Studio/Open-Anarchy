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
package com.rs.net.encoders

import com.rs.cache.loaders.ClanVarDefinitions
import com.rs.cache.loaders.cs2.CS2Type
import com.rs.game.World
import com.rs.game.map.Chunk
import com.rs.game.map.ChunkManager
import com.rs.game.model.WorldProjectile
import com.rs.game.model.entity.npc.NPC
import com.rs.game.model.entity.player.Player
import com.rs.game.model.entity.player.managers.InterfaceManager.Sub
import com.rs.game.model.gameobject.GameObject
import com.rs.game.model.gameobject.ObjectMeshModifier
import com.rs.game.model.item.ItemsContainer
import world.gregs.voidps.buffer.write.BufferWriter
import com.rs.utils.MapXTEAs
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import org.darkan.core.Utils
import org.darkan.core.model.Animation
import org.darkan.core.model.ChatMessageType
import org.darkan.core.model.GroundItem
import org.darkan.core.model.HintIcon
import org.darkan.core.model.Item
import org.darkan.core.model.RegionSize
import org.darkan.core.model.Sound
import org.darkan.core.model.SpotAnim
import org.darkan.core.net.Session
import org.darkan.core.net.prot.BlockMinimapState
import org.darkan.core.net.prot.BuildInstancedRegion
import org.darkan.core.net.prot.BuildRegion
import org.darkan.core.net.prot.CamLookAt
import org.darkan.core.net.prot.CamMoveTo
import org.darkan.core.net.prot.CamResetHard
import org.darkan.core.net.prot.CamResetSmooth
import org.darkan.core.net.prot.CamShake
import org.darkan.core.net.prot.ChatFilterSettings
import org.darkan.core.net.prot.ChatFilterSettingsPrivateChat
import org.darkan.core.net.prot.ClearVarps
import org.darkan.core.net.prot.CreateGroundItem
import org.darkan.core.net.prot.CreateObject
import org.darkan.core.net.prot.CustomizeObject
import org.darkan.core.net.prot.Cutscene
import org.darkan.core.net.prot.DestroyObject
import org.darkan.core.net.prot.GameMessage
import org.darkan.core.net.prot.GroundItemCount
import org.darkan.core.net.prot.HintArrow
import org.darkan.core.net.prot.IfCloseSub
import org.darkan.core.net.prot.IfOpenSub
import org.darkan.core.net.prot.IfOpenSubActiveGroundItem
import org.darkan.core.net.prot.IfOpenSubActiveNpc
import org.darkan.core.net.prot.IfOpenSubActiveObject
import org.darkan.core.net.prot.IfOpenSubActivePlayer
import org.darkan.core.net.prot.IfOpenTop
import org.darkan.core.net.prot.IfSetAnim
import org.darkan.core.net.prot.IfSetAngle
import org.darkan.core.net.prot.IfSetEvents
import org.darkan.core.net.prot.IfSetGraphic
import org.darkan.core.net.prot.IfSetHide
import org.darkan.core.net.prot.IfSetItem
import org.darkan.core.net.prot.IfSetModel
import org.darkan.core.net.prot.IfSetNpcHead
import org.darkan.core.net.prot.IfSetPlayerHead
import org.darkan.core.net.prot.IfSetPosition
import org.darkan.core.net.prot.IfSetTargetParam
import org.darkan.core.net.prot.IfSetText
import org.darkan.core.net.prot.InstanceLoadType
import org.darkan.core.net.prot.LogoutFull
import org.darkan.core.net.prot.LogoutLobby
import org.darkan.core.net.prot.MapProjAnim
import org.darkan.core.net.prot.MessageClanChannel
import org.darkan.core.net.prot.MessageFriendsChat
import org.darkan.core.net.prot.MessagePrivate
import org.darkan.core.net.prot.MessagePrivateEcho
import org.darkan.core.net.prot.MessagePublic
import org.darkan.core.net.prot.MessageQuickChatClanChannel
import org.darkan.core.net.prot.MessageQuickChatFriendsChat
import org.darkan.core.net.prot.MessageQuickChatPrivate
import org.darkan.core.net.prot.MessageQuickChatPrivateEcho
import org.darkan.core.net.prot.NpcUpdateLargeRaw
import org.darkan.core.net.prot.NpcUpdateRaw
import org.darkan.core.net.prot.ObjAnim
import org.darkan.core.net.prot.OpenUrl
import org.darkan.core.net.prot.PlayerOption
import org.darkan.core.net.prot.PlayerUpdateRaw
import org.darkan.core.net.prot.PlayerWeight
import org.darkan.core.net.prot.RemoveGroundItem
import org.darkan.core.net.prot.ResetSounds
import org.darkan.core.net.prot.RunEnergy
import org.darkan.core.net.prot.RunScript
import org.darkan.core.net.prot.SetCursor
import org.darkan.core.net.prot.SetDrawOrder
import org.darkan.core.net.prot.SpotAnimSpecific
import org.darkan.core.net.prot.TileMessage
import org.darkan.core.net.prot.UpdateGESlot
import org.darkan.core.net.prot.UpdateInvFull
import org.darkan.core.net.prot.UpdateInvPartial
import org.darkan.core.net.prot.UpdateRebootTimer
import org.darkan.core.net.prot.UpdateStat
import org.darkan.core.net.prot.UpdateZonePartialFollows
import org.darkan.core.net.prot.VarclanSetByte
import org.darkan.core.net.prot.VarclanSetInt
import org.darkan.core.net.prot.VarclanSetLong
import org.darkan.core.net.prot.VarclanSetString
import org.darkan.core.net.prot.VorbisSound
import org.darkan.core.net.prot.setVarbitPacket
import org.darkan.core.net.prot.setVarcPacket
import org.darkan.core.net.prot.setVarcStrPacket
import org.darkan.core.net.prot.setVarpPacket
import org.darkan.core.social.ChatMessage
import org.darkan.core.social.PublicChatMessage
import org.darkan.core.social.QuickChatMessage
import org.darkan.core.util.MapUtils
import org.darkan.core.util.Structure
import org.darkan.core.model.IFEvents as IFEventsModel
import world.gregs.voidps.type.Tile

class WorldEncoder(private val player: Player, @JvmField val session: Session) {

    // ======================== Interface packets ========================

    fun sendWindowsPane(id: Int, type: Int) {
        session.queuePacket(IfOpenTop(id, type, EMPTY_XTEAS))
    }

    fun sendInterface(overlay: Boolean, topId: Int, topChildId: Int, subId: Int) {
        session.queuePacket(IfOpenSub(topId, topChildId, subId, overlay, EMPTY_XTEAS))
    }

    fun sendNPCInterface(npc: NPC, overlay: Boolean, topId: Int, topChildId: Int, subId: Int) {
        session.queuePacket(IfOpenSubActiveNpc(topId, topChildId, subId, overlay, npc.index, EMPTY_XTEAS))
    }

    fun sendObjectInterface(obj: GameObject, overlay: Boolean, topId: Int, topChildId: Int, subId: Int) {
        session.queuePacket(
            IfOpenSubActiveObject(
                topId, topChildId, subId, overlay, EMPTY_XTEAS,
                obj.id, obj.tile, obj.type.id, obj.rotation
            )
        )
    }

    fun sendPlayerInterface(overlay: Boolean, topId: Int, topChildId: Int, subId: Int) {
        session.queuePacket(IfOpenSubActivePlayer(topId, topChildId, subId, overlay, player.index, EMPTY_XTEAS))
    }

    fun sendGroundItemInterface(item: GroundItem, overlay: Boolean, topId: Int, topChildId: Int, subId: Int) {
        session.queuePacket(IfOpenSubActiveGroundItem(topId, topChildId, subId, overlay, item.tile, item.id, EMPTY_XTEAS))
    }

    fun closeInterface(topChildId: Int) {
        session.queuePacket(IfCloseSub(topChildId ushr 16, topChildId and 0xFFFF))
    }

    fun setIFGraphic(interfaceId: Int, componentId: Int, spriteId: Int) {
        session.queuePacket(IfSetGraphic(interfaceId, componentId, spriteId))
    }

    fun setIFPosition(interfaceId: Int, componentId: Int, x: Int, y: Int) {
        session.queuePacket(IfSetPosition(interfaceId, componentId, x, y))
    }

    fun setIFModel(interfaceId: Int, componentId: Int, modelId: Int) {
        session.queuePacket(IfSetModel(interfaceId, componentId, modelId))
    }

    fun setIFHidden(interfaceId: Int, componentId: Int, hidden: Boolean) {
        session.queuePacket(IfSetHide(interfaceId, componentId, hidden))
    }

    fun setIFEvents(params: IFEventsModel) {
        session.queuePacket(IfSetEvents(params.interfaceId, params.componentId, params.fromSlot, params.toSlot, params.settings))
    }

    fun setIFRightClickOps(interfaceId: Int, componentId: Int, fromSlot: Int, toSlot: Int, vararg optionsSlots: Int) {
        setIFEvents(IFEventsModel(interfaceId, componentId, fromSlot, toSlot).enableRightClickOptions(*optionsSlots))
    }

    fun setIFTargetParam(interfaceId: Int, componentId: Int, fromSlot: Int, toSlot: Int, targetParam: Int) {
        session.queuePacket(IfSetTargetParam(interfaceId, componentId, targetParam, fromSlot, toSlot))
    }

    fun setIFText(interfaceId: Int, componentId: Int, text: String) {
        session.queuePacket(IfSetText(interfaceId, componentId, text))
    }

    fun setIFAnimation(emoteId: Int, interfaceId: Int, componentId: Int) {
        session.queuePacket(IfSetAnim(interfaceId, componentId, emoteId))
    }

    fun setIFAngle(interfaceId: Int, componentId: Int, pitch: Int, roll: Int, scale: Int) {
        session.queuePacket(IfSetAngle(interfaceId, componentId, pitch, roll, scale))
    }

    fun setIFItem(interfaceId: Int, componentId: Int, id: Int, amount: Int) {
        session.queuePacket(IfSetItem(interfaceId, componentId, id, amount))
    }

    fun setIFPlayerHead(interfaceId: Int, componentId: Int) {
        session.queuePacket(IfSetPlayerHead(interfaceId, componentId))
    }

    fun setIFNPCHead(interfaceId: Int, componentId: Int, npcId: Int) {
        session.queuePacket(IfSetNpcHead(interfaceId, componentId, npcId))
    }

    // ======================== GE / Draw / Hint ========================

    fun updateGESlot(slot: Int, progress: Int, item: Int, price: Int, amount: Int, currAmount: Int, totalPrice: Int) {
        session.queuePacket(UpdateGESlot(slot, UpdateGESlot.Offer(progress, item, price, amount, currAmount, totalPrice)))
    }

    fun sendDrawOrder(playerFirst: Boolean) {
        session.queuePacket(SetDrawOrder(playerFirst))
    }

    fun sendHintIcon(icon: HintIcon) {
        session.queuePacket(
            HintArrow(
                iconIndex = icon.index,
                targetType = icon.targetType,
                arrowSprite = icon.arrowType,
                plane = icon.plane,
                x = icon.coordX,
                y = icon.coordY,
                targetIndex = icon.targetIndex,
                modelId = icon.modelId,
                distance = icon.distanceFromFloor
            )
        )
    }

    // ======================== Camera packets ========================

    fun sendCameraShake(slotId: Int, v1: Int, v2: Int, v3: Int, v4: Int) {
        session.queuePacket(CamShake(slotId, v1, v2, v3, v4))
    }

    fun sendStopCameraShake() {
        session.queuePacket(CamResetHard())
    }

    fun sendCameraLook(tile: Tile, viewZ: Int) {
        sendCameraLook(tile.getXInScene(player.sceneBaseChunkId), tile.getYInScene(player.sceneBaseChunkId), viewZ)
    }

    fun sendCameraLook(tile: Tile, viewZ: Int, speedToExactDestination: Int, speedOnRoutePath: Int) {
        sendCameraLook(tile.getXInScene(player.sceneBaseChunkId), tile.getYInScene(player.sceneBaseChunkId), viewZ, speedToExactDestination, speedOnRoutePath)
    }

    fun sendCameraLook(viewLocalX: Int, viewLocalY: Int, viewZ: Int) {
        sendCameraLook(viewLocalX, viewLocalY, viewZ, -1, -1)
    }

    fun sendCameraLook(viewLocalX: Int, viewLocalY: Int, viewZ: Int, speedToExactDestination: Int, speedOnRoutePath: Int) {
        session.queuePacket(CamLookAt(viewLocalX, viewLocalY, viewZ, speedToExactDestination, speedOnRoutePath))
    }

    fun sendResetCamera() {
        session.queuePacket(CamResetHard())
    }

    fun sendResetCameraSoft() {
        session.queuePacket(CamResetSmooth())
    }

    fun sendCameraPos(tile: Tile, z: Int) {
        sendCameraPos(tile.getXInScene(player.sceneBaseChunkId), tile.getYInScene(player.sceneBaseChunkId), z)
    }

    fun sendCameraPos(tile: Tile, z: Int, speedToTile: Int, speedToExactDestination: Int) {
        sendCameraPos(tile.getXInScene(player.sceneBaseChunkId), tile.getYInScene(player.sceneBaseChunkId), z, speedToTile, speedToExactDestination)
    }

    fun sendCameraPos(moveLocalX: Int, moveLocalY: Int, moveZ: Int) {
        sendCameraPos(moveLocalX, moveLocalY, moveZ, -1, -1)
    }

    /**
     * @param speedToTile defines speed of the camera to the world tile with the default height then the exact position
     * @param speedToExactDestination defines speed of camera to the exact position specified by previous parameters.
     */
    fun sendCameraPos(moveLocalX: Int, moveLocalY: Int, moveZ: Int, speedToTile: Int, speedToExactDestination: Int) {
        session.queuePacket(CamMoveTo(moveLocalX, moveLocalY, moveZ, speedToTile, speedToExactDestination))
    }

    // ======================== RunScript ========================

    fun sendRunScript(scriptId: Int, vararg params: Any) {
        val l = params.toMutableList()
        l.reverse()
        sendRunScriptReverse(scriptId, *l.toTypedArray())
    }

    fun setIFSprite(interfaceId: Int, componentId: Int, slotId: Int, spriteId: Int) {
        sendRunScript(1351, Utils.toInterfaceHash(interfaceId, componentId), slotId, spriteId)
    }

    fun setIFText(interfaceId: Int, componentId: Int, slotId: Int, text: String) {
        sendRunScript(6566, Utils.toInterfaceHash(interfaceId, componentId), slotId, text)
    }

    fun sendRunScriptReverse(scriptId: Int, vararg params: Any) {
        session.queuePacket(RunScript(scriptId, arrayOf(*params)))
    }

    // ======================== Var packets ========================

    fun sendVarc(id: Int, value: Int) {
        session.queuePacket(setVarcPacket(id, value))
    }

    fun sendVarcString(id: Int, string: String) {
        session.queuePacket(setVarcStrPacket(id, string))
    }

    @Deprecated("Use modern var system")
    fun sendVar(id: Int, value: Int) {
        session.queuePacket(setVarpPacket(id, value))
    }

    @Deprecated("Use modern var system")
    fun sendVarBit(id: Int, value: Int) {
        session.queuePacket(setVarbitPacket(id, value))
    }

    @Deprecated("Use modern var system")
    fun clearVarps() {
        session.queuePacket(ClearVarps())
    }

    fun setClanVar(varId: Int, value: Any) {
        val def = ClanVarDefinitions.getDefs(varId)
        when (def.type) {
            CS2Type.STRING -> session.queuePacket(VarclanSetString(varId, value as String))
            CS2Type.LONG -> session.queuePacket(VarclanSetLong(varId, (value as Number).toLong()))
            CS2Type.INT -> {
                val intVal = (value as Number).toInt()
                if (intVal < Byte.MIN_VALUE || intVal > Byte.MAX_VALUE)
                    session.queuePacket(VarclanSetInt(varId, intVal))
                else
                    session.queuePacket(VarclanSetByte(varId, intVal))
            }
            else -> error("Mismatching types for clan var $varId")
        }
    }

    // ======================== Player stats / weight / energy ========================

    fun sendRunEnergy(energy: Double) {
        session.queuePacket(RunEnergy(energy.toInt()))
    }

    fun refreshWeight(weight: Double) {
        session.queuePacket(PlayerWeight(weight.toInt()))
    }

    fun updateStats(vararg skills: Int) {
        for (skill in skills) {
            session.queuePacket(UpdateStat(player.skills.getXp(skill).toInt(), skill, player.skills.getLevel(skill)))
        }
    }

    // ======================== Zone update packets ========================

    private fun sendZoneHeader(tile: Tile) {
        val baseCoords = MapUtils.decode(Structure.CHUNK, player.sceneBaseChunkId)
        val localCoords = MapUtils.decode(Structure.CHUNK, tile.getChunkId())
        session.queuePacket(
            UpdateZonePartialFollows(
                localCoords[0] - baseCoords[0],
                localCoords[1] - baseCoords[1],
                localCoords[2]
            )
        )
    }

    fun sendObjectAnimation(obj: GameObject, animation: Animation) {
        sendZoneHeader(obj.tile)
        session.queuePacket(ObjAnim(obj.tile.getXInChunk(), obj.tile.getYInChunk(), obj.type.id, obj.rotation, animation.primaryId))
    }

    fun removeGroundItem(item: GroundItem) {
        sendZoneHeader(item.tile)
        session.queuePacket(RemoveGroundItem(item.tile.getXInChunk(), item.tile.getYInChunk(), item.id))
    }

    fun sendGroundItem(item: GroundItem) {
        sendZoneHeader(item.tile)
        session.queuePacket(CreateGroundItem(item.tile.getXInChunk(), item.tile.getYInChunk(), item.id, item.amount))
    }

    fun sendSetGroundItemAmount(item: GroundItem, oldAmount: Int) {
        sendZoneHeader(item.tile)
        session.queuePacket(GroundItemCount(item.tile.getXInChunk(), item.tile.getYInChunk(), item.id, oldAmount, item.amount))
    }

    fun sendProjectile(projectile: WorldProjectile) {
        sendZoneHeader(projectile.source)
        val flags = if (projectile.useTerrainHeight) 0x80 else 0
        session.queuePacket(
            MapProjAnim(
                localX = projectile.source.getXInChunk(),
                localY = projectile.source.getYInChunk(),
                xOffset = projectile.destination.x - projectile.source.x,
                yOffset = projectile.destination.y - projectile.source.y,
                lockOn = projectile.lockOnId,
                spotAnimId = projectile.spotAnimId,
                startHeight = projectile.startHeight,
                endHeight = projectile.endHeight,
                startTime = projectile.startDelayClientCycles,
                endTime = projectile.startDelayClientCycles + projectile.inAirClientCycles,
                angle = projectile.angle,
                slope = projectile.offset,
                useFloorHeight = projectile.useTerrainHeight
            )
        )
    }

    fun sendTileMessage(message: String, tile: Tile, delay: Int, height: Int, color: Int) {
        sendZoneHeader(tile)
        session.queuePacket(TileMessage(tile.getXInChunk(), tile.getYInChunk(), height, delay, color, message))
    }

    fun sendTileMessage(message: String, tile: Tile, color: Int) {
        sendTileMessage(message, tile, 5000, 255, color)
    }

    fun sendRemoveObject(obj: GameObject) {
        sendZoneHeader(obj.tile)
        session.queuePacket(DestroyObject(obj.tile.getXInChunk(), obj.tile.getYInChunk(), obj.type.id, obj.rotation))
    }

    fun sendAddObject(obj: GameObject) {
        sendZoneHeader(obj.tile)
        session.queuePacket(CreateObject(obj.tile.getXInChunk(), obj.tile.getYInChunk(), obj.id, obj.type.id, obj.rotation))
        if (obj.meshModifier != null)
            sendCustomizeObject(obj.meshModifier)
    }

    fun sendCustomizeObject(modifier: ObjectMeshModifier) {
        val obj = modifier.`object`
        sendZoneHeader(obj.tile)
        session.queuePacket(
            CustomizeObject(
                localX = obj.tile.getXInChunk(),
                localY = obj.tile.getYInChunk(),
                objectId = obj.id,
                shape = obj.type.id,
                rotation = obj.rotation,
                modelIds = modifier.modelIds,
                recolors = modifier.modifiedColors?.let { arr -> ShortArray(arr.size) { arr[it].toShort() } },
                retextures = modifier.modifiedTextures?.let { arr -> ShortArray(arr.size) { arr[it].toShort() } }
            )
        )
    }

    // ======================== Message packets ========================

    fun sendMessage(type: ChatMessageType, text: String, p: Player?) {
        session.queuePacket(GameMessage(type, text, p?.account?.displayName))
    }

    fun sendPublicMessage(p: Player, message: ChatMessage) {
        session.queuePacket(MessagePublic(p.index, p.messageIcon, message))
    }

    fun sendPrivateMessage(username: String, message: String) {
        session.queuePacket(MessagePrivate(player.account.rights.crown, player.displayName, player.displayName, message))
    }

    fun receivePrivateMessage(account: com.rs.game.Account, message: String) {
        session.queuePacket(MessagePrivateEcho(account.displayName, message))
    }

    fun receivePrivateChatQuickMessage(account: com.rs.game.Account, message: QuickChatMessage) {
        session.queuePacket(MessageQuickChatPrivateEcho(account.displayName, message))
    }

    fun sendPrivateQuickMessage(username: String, message: QuickChatMessage) {
        session.queuePacket(MessageQuickChatPrivate(player.account.rights.crown, player.displayName, player.displayName, message))
    }

    fun sendFriendsChatMessage(account: com.rs.game.Account, chatName: String, message: String) {
        session.queuePacket(MessageFriendsChat(account.rights.crown, account.displayName, account.displayName, chatName, message))
    }

    fun receiveFriendChatQuickMessage(account: com.rs.game.Account, chatName: String, message: QuickChatMessage) {
        session.queuePacket(MessageQuickChatFriendsChat(chatName, account.rights.crown, account.displayName, account.displayName, message))
    }

    fun receiveClanChatMessage(account: com.rs.game.Account, message: String, guest: Boolean) {
        session.queuePacket(MessageClanChannel(guest, account.rights.crown, account.displayName, message))
    }

    fun receiveClanChatQuickMessage(account: com.rs.game.Account, message: QuickChatMessage, guest: Boolean) {
        session.queuePacket(MessageQuickChatClanChannel(guest, account.rights.crown, account.displayName, message))
    }

    fun sendChatFilterSettings() {
        session.queuePacket(ChatFilterSettings(player.tradeStatus, player.publicStatus))
    }

    fun sendChatFilterSettingsPrivateChat() {
        session.queuePacket(ChatFilterSettingsPrivateChat(player.account.social.status.toInt()))
    }

    // ======================== Map region packets ========================

    fun sendDynamicMapRegion(sendLSWP: Boolean) {
        val mapHash = player.mapSize.size shr 4
        val chunkDescriptors = mutableListOf<Int>()
        val realRegionIds = IntOpenHashSet()

        var plane = 0
        while (plane < 4 * Chunk.PLANE_INC) {
            var cx = (player.chunkX - mapHash) * Chunk.X_INC
            while (cx <= (player.chunkX + mapHash) * Chunk.X_INC) {
                var cy = player.chunkY - mapHash
                while (cy <= player.chunkY + mapHash) {
                    val chunk = ChunkManager.getChunk(cx + cy + plane)
                    if (chunk.renderChunkX == 0 || chunk.renderChunkY == 0) {
                        chunkDescriptors.add(-1)
                    } else {
                        chunkDescriptors.add(
                            (chunk.rotation shl 1) or (chunk.renderPlane shl 24) or (chunk.renderChunkX shl 14) or (chunk.renderChunkY shl 3)
                        )
                        realRegionIds.add(chunk.renderRegionId)
                    }
                    cy++
                }
                cx += Chunk.X_INC
            }
            plane += Chunk.PLANE_INC
        }

        val xteas = Array(realRegionIds.size) { i ->
            val regionId = realRegionIds.toIntArray()[i]
            MapXTEAs.getMapKeys(regionId) ?: intArrayOf(0, 0, 0, 0)
        }

        if (sendLSWP) {
            player.localPlayerUpdate.init(BufferWriter(40960)) // initializes internal state
        }

        session.queuePacket(
            BuildInstancedRegion(
                mapSize = player.mapSize,
                chunks = chunkDescriptors.toIntArray(),
                xteas = xteas,
                chunkX = player.chunkX,
                chunkY = player.chunkY,
                forceMapRefresh = player.isForceNextMapLoadRefresh,
                localPlayerPid = if (sendLSWP) player.index else null,
                localPlayerBaseTileHash = if (sendLSWP) player.tile.id else null,
                otherPlayerRegionIds = if (sendLSWP) buildOtherPlayerRegionIds() else null,
            )
        )
    }

    fun sendMapRegion(sendLSWP: Boolean) {
        val mapRegionIds = IntOpenHashSet()
        for (chunkId in player.mapChunkIds) {
            val coords = MapUtils.decode(Structure.CHUNK, chunkId)
            mapRegionIds.add(MapUtils.encode(Structure.REGION, coords[0] shr 3, coords[1] shr 3))
        }
        val sortedRegionIds = mapRegionIds.toIntArray().also { it.sort() }
        val xteas = Array(sortedRegionIds.size) { i ->
            MapXTEAs.getMapKeys(sortedRegionIds[i]) ?: intArrayOf(0, 0, 0, 0)
        }

        if (sendLSWP) {
            player.localPlayerUpdate.init(BufferWriter(40960)) // initializes internal state
        }

        session.queuePacket(
            BuildRegion(
                regionIds = sortedRegionIds,
                mapSize = player.mapSize,
                xteas = xteas,
                chunkX = player.chunkX,
                chunkY = player.chunkY,
                forceMapRefresh = player.isForceNextMapLoadRefresh,
                localPlayerPid = if (sendLSWP) player.index else null,
                localPlayerBaseTileHash = if (sendLSWP) player.tile.id else null,
                otherPlayerRegionIds = if (sendLSWP) buildOtherPlayerRegionIds() else null,
            )
        )
    }

    private fun buildOtherPlayerRegionIds(): IntArray {
        val regionIds = IntArray(2048)
        for (playerIndex in 1 until 2048) {
            val p: Player? = World.players[playerIndex]
            regionIds[playerIndex] = p?.getRegionHash() ?: 0
        }
        return regionIds
    }

    // ======================== Cutscene ========================

    fun sendCutscene(id: Int) {
        // TODO: Load cutscene areas from cache to provide XTEA keys
        session.queuePacket(Cutscene(id, emptyArray(), player.appearance.appeareanceData!!))
    }

    // ======================== Player option ========================

    @Deprecated("Use modern player option system")
    fun sendPlayerOption(option: String, slot: Int, top: Boolean) {
        sendPlayerOption(option, slot, top, -1)
    }

    @Deprecated("Use modern player option system")
    fun sendPlayerOption(option: String, slot: Int, top: Boolean, cursor: Int) {
        session.queuePacket(PlayerOption(option, slot, top, cursor))
    }

    // ======================== Entity updates ========================

    fun sendLocalPlayersUpdate() {
        val stream = BufferWriter(40960)
        player.localPlayerUpdate.write(stream)
        session.queuePacket(PlayerUpdateRaw(stream.toArray()))
    }

    fun sendLocalNPCsUpdate() {
        val stream = BufferWriter(8192)
        player.localNPCUpdate.write(stream, player.hasLargeSceneView())
        if (player.hasLargeSceneView())
            session.queuePacket(NpcUpdateLargeRaw(stream.toArray()))
        else
            session.queuePacket(NpcUpdateRaw(stream.toArray()))
    }

    // ======================== Spot anim ========================

    fun sendSpotAnim(spotAnim: SpotAnim, target: Any) {
        val targetHash = when (target) {
            is Player -> (target.index and 0xffff) or (1 shl 28)
            is NPC -> (target.index and 0xffff) or (1 shl 29)
            is Tile -> (target.plane shl 28) or (target.x shl 14) or (target.y and 0x3fff) or (1 shl 30)
            else -> 0
        }
        session.queuePacket(
            SpotAnimSpecific(
                slot = 0,
                spotAnimId = spotAnim.id,
                settings = spotAnim.settingsHash,
                speed = spotAnim.speed,
                targetHash = targetHash,
                height = spotAnim.height
            )
        )
    }

    // ======================== System / misc ========================

    fun sendSystemUpdate(delay: Int) {
        session.queuePacket(UpdateRebootTimer(delay))
    }

    fun sendUpdateItems(key: Int, negativeKey: Boolean, items: Array<Item?>, vararg slots: Int) {
        val updates = Array(slots.size) { i ->
            val slot = slots[i]
            val item = if (slot in items.indices) items[slot] else null
            UpdateInvPartial.InvSlotUpdate(slot, item?.id ?: -1, item?.amount ?: 0)
        }
        session.queuePacket(UpdateInvPartial(key, negativeKey, updates))
    }

    fun sendUpdateItems(key: Int, items: ItemsContainer<*>, vararg slots: Int) {
        sendUpdateItems(key, items.array(), *slots)
    }

    fun sendUpdateItems(key: Int, items: Array<Item?>, vararg slots: Int) {
        sendUpdateItems(key, key < 0, items, *slots)
    }

    fun sendItems(key: Int, negativeKey: Boolean, items: Array<Item?>) {
        val itemIds = IntArray(items.size) { items[it]?.id ?: -1 }
        val amounts = IntArray(items.size) { items[it]?.amount ?: 0 }
        session.queuePacket(UpdateInvFull(key, negativeKey, itemIds, amounts))
    }

    fun sendItems(key: Int, items: ItemsContainer<*>) {
        sendItems(key, key < 0, items)
    }

    fun sendItems(key: Int, negativeKey: Boolean, items: ItemsContainer<*>) {
        sendItems(key, negativeKey, items.array())
    }

    fun sendItems(key: Int, items: Array<Item?>) {
        sendItems(key, key < 0, items)
    }

    fun sendLogout(lobby: Boolean) {
        session.queuePacket(if (lobby) LogoutLobby() else LogoutFull())
        if (player.hasFinished())
            player.session.close()
    }

    fun sendSound(sound: Sound) {
        session.queuePacket(VorbisSound(sound.id, sound.repeat, sound.delay, sound.volume, sound.sampleRate))
    }

    fun resetSounds() {
        session.queuePacket(ResetSounds())
    }

    fun setBlockMinimapState(state: Int) {
        session.queuePacket(BlockMinimapState(state))
    }

    fun sendOpenURL(url: String) {
        session.queuePacket(OpenUrl(url))
    }

    fun sendSetCursor(walkHereReplace: String, cursor: Int) {
        session.queuePacket(SetCursor(cursor, walkHereReplace))
    }

    // ======================== Convenience / composite methods ========================

    fun sendGameBarStages() {
        sendVar(1054, player.clanStatus)
        sendVar(1055, player.assistStatus)
        sendVar(1056, if (player.isFilterGame) 1 else 0)
        sendVar(2159, player.account.social.fcStatus.toInt())
        sendChatFilterSettings()
        sendChatFilterSettingsPrivateChat()
    }

    fun sendInventoryMessage(border: Int, slotId: Int, message: String) {
        sendGameMessage(message)
        sendRunScriptReverse(948, border, slotId, message)
    }

    fun sendGameMessage(text: String) {
        sendGameMessage(text, false)
    }

    fun sendGameMessage(text: String, filter: Boolean) {
        sendMessage(if (filter) ChatMessageType.FILTERABLE else ChatMessageType.UNFILTERABLE, text, null)
    }

    fun sendDevConsoleMessage(text: String) {
        sendMessage(ChatMessageType.DEV_CONSOLE, text, null)
    }

    fun sendTradeRequestMessage(p: Player) {
        sendMessage(ChatMessageType.TRADE_REQUEST, "wishes to trade with you.", p)
    }

    fun sendClanWarsRequestMessage(p: Player) {
        sendMessage(ChatMessageType.CLAN_CHALLENGE_REQUEST, "wishes to challenge your clan to a clan war.", p)
    }

    fun sendDuelChallengeRequestMessage(p: Player, friendly: Boolean) {
        sendMessage(ChatMessageType.DUEL_REQUEST, "wishes to duel with you (${if (friendly) "friendly" else "stake"}).", p)
    }

    fun sendDungeonneringRequestMessage(p: Player) {
        sendMessage(ChatMessageType.DUNGEONEERING_INVITE, "has invited you to a dungeon party.", p)
    }

    fun sendClanInviteMessage(p: Player) {
        sendMessage(ChatMessageType.CLAN_INVITE, "${p.displayName} is inviting you to join their clan.", p)
    }

    fun sendPouchInfusionOptionsScript(dung: Boolean, interfaceId: Int, componentId: Int, slotLength: Int, width: Int, height: Int, vararg options: String) {
        val parameters = arrayOfNulls<Any>(5 + options.size)
        var index = 0
        if (dung) {
            parameters[index++] = 1159
            parameters[index++] = 1100
        } else {
            parameters[index++] = slotLength
            parameters[index++] = 1
        }
        for (count in options.indices.reversed())
            parameters[index++] = options[count]
        parameters[index++] = height
        parameters[index++] = width
        parameters[index++] = (interfaceId shl 16) or componentId
        @Suppress("UNCHECKED_CAST")
        sendRunScriptReverse(757, *(parameters as Array<Any>))
    }

    fun sendScrollInfusionOptionsScript(dung: Boolean, interfaceId: Int, componentId: Int, slotLength: Int, width: Int, height: Int, vararg options: String) {
        val parameters = arrayOfNulls<Any>(5 + options.size)
        var index = 0
        if (dung) {
            parameters[index++] = 1159
            parameters[index++] = 1100
        } else {
            parameters[index++] = slotLength
            parameters[index++] = 1
        }
        for (count in options.indices.reversed())
            parameters[index++] = options[count]
        parameters[index++] = height
        parameters[index++] = width
        parameters[index++] = (interfaceId shl 16) or componentId
        @Suppress("UNCHECKED_CAST")
        sendRunScriptReverse(763, *(parameters as Array<Any>))
    }

    fun sendRunScriptBlank(scriptId: Int) {
        sendRunScriptReverse(scriptId)
    }

    fun sendGroundItemMessage(item: GroundItem, message: String) {
        sendGroundItemMessage(0, 0xFFFFFF, item, message)
    }

    fun sendPlayerMessage(border: Int, color: Int, message: String) {
        sendGameMessage(message)
        sendVarcString(306, message)
        sendVarc(1699, color)
        sendVarc(1700, border)
        sendVarc(1695, 1)
        sendPlayerInterface(true, player.interfaceManager.topInterface, Sub.RENDER_SPACE.getComponent(player.resizeable()), 1177)
    }

    fun sendNPCMessage(border: Int, color: Int, npc: NPC, message: String) {
        sendGameMessage(message)
        sendVarcString(306, message)
        sendVarc(1699, color)
        sendVarc(1700, border)
        sendVarc(1695, 1)
        sendNPCInterface(npc, true, player.interfaceManager.topInterface, Sub.RENDER_SPACE.getComponent(player.resizeable()), 1177)
    }

    fun sendGroundItemMessage(border: Int, color: Int, item: GroundItem, message: String) {
        sendGameMessage(message)
        sendVarcString(306, message)
        sendVarc(1699, color)
        sendVarc(1700, border)
        sendVarc(1695, 1)
        sendGroundItemInterface(item, true, player.interfaceManager.topInterface, Sub.RENDER_SPACE.getComponent(player.resizeable()), 1177)
    }

    fun sendObjectMessage(border: Int, color: Int, obj: GameObject, message: String) {
        sendGameMessage(message)
        sendVarcString(306, message)
        sendVarc(1699, color)
        sendVarc(1700, border)
        sendVarc(1695, 1)
        sendObjectInterface(obj, true, player.interfaceManager.topInterface, Sub.RENDER_SPACE.getComponent(player.resizeable()), 1177)
    }

    fun openGESearch() {
        player.interfaceManager.sendChatBoxInterface(7, 389)
        sendRunScriptReverse(570, "Grand Exchange Item Search")
    }

    fun sendInterFlashScript(interfaceId: Int, componentId: Int, width: Int, height: Int, slot: Int) {
        sendRunScriptReverse(143, slot, height, width, (interfaceId shl 16) or componentId)
    }

    fun sendInterSetItemsOptionsScript(interfaceId: Int, componentId: Int, key: Int, width: Int, height: Int, vararg options: String) {
        sendInterSetItemsOptionsScript(interfaceId, componentId, key, false, width, height, *options)
    }

    fun sendInterSetItemsOptionsScript(interfaceId: Int, componentId: Int, key: Int, negativeKey: Boolean, width: Int, height: Int, vararg options: String) {
        val parameters = arrayOfNulls<Any>(6 + options.size)
        var index = 0
        for (count in options.indices.reversed())
            parameters[index++] = options[count]
        parameters[index++] = -1
        parameters[index++] = 0
        parameters[index++] = height
        parameters[index++] = width
        parameters[index++] = key
        parameters[index++] = (interfaceId shl 16) or componentId
        @Suppress("UNCHECKED_CAST")
        sendRunScriptReverse(if (negativeKey) 695 else 150, *(parameters as Array<Any>))
    }

    fun sendInputIntegerScript(message: String) {
        sendRunScriptReverse(108, message)
    }

    fun sendInputNameScript(message: String) {
        sendRunScriptReverse(109, message)
    }

    fun sendInputLongTextScript(message: String) {
        sendRunScriptReverse(110, message)
    }

    companion object {
        private val EMPTY_XTEAS = intArrayOf(0, 0, 0, 0)
    }
}
