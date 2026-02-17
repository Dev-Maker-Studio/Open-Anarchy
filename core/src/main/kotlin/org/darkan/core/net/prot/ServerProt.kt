package org.darkan.core.net.prot

import kotlinx.serialization.Serializable
import org.darkan.core.clientwatch.ReflectionCheck
import org.darkan.core.model.Account
import org.darkan.core.model.ChatMessageType
import org.darkan.core.model.RegionSize
import org.darkan.core.social.ChatMessage
import org.darkan.core.social.QuickChatMessage
import org.darkan.core.worldlist.WorldList
import world.gregs.voidps.type.Tile

interface ServerProt

data class IfSetPlayerHead(val interfaceId: Int, val componentId: Int) : ServerProt

data class IfSetPlayerHeadIgnoreWorn(val interfaceId: Int, val componentId: Int, val identiKit1: Int, val identiKit2: Int, val identiKit3: Int) : ServerProt

data class IfSetPlayerModel(val interfaceId: Int, val componentId: Int) : ServerProt

data class IfSetTextFont(val interfaceId: Int, val componentId: Int, val fontId: Int) : ServerProt

data class IfMoveSub(val fromInterfaceId: Int, val fromComponentId: Int, val toInterfaceId: Int, val toComponentId: Int) : ServerProt

data class IfOpenSubActiveObject(
    val interfaceId: Int,
    val componentId: Int,
    val subInterfaceId: Int,
    val overlay: Boolean,
    val xteas: IntArray,
    val objectId: Int,
    val tile: Tile,
    val type: Int,
    val rotation: Int,
) : ServerProt {
    init {
        require(xteas.size == 4) { "xteas must be length 4" }
    }
}

data class IfSetHide(val interfaceId: Int, val componentId: Int, val hidden: Boolean) : ServerProt

data class IfOpenSubActivePlayer(
    val interfaceId: Int,
    val componentId: Int,
    val subInterfaceId: Int,
    val overlay: Boolean,
    val playerIndex: Int,
    val xteas: IntArray,
) : ServerProt {
    init {
        require(xteas.size == 4) { "xteas must be length 4" }
    }
}

data class IfSetGraphic(val interfaceId: Int, val componentId: Int, val spriteId: Int) : ServerProt

data class IfSetTextAntiMacro(val interfaceId: Int, val componentId: Int, val enabled: Boolean) : ServerProt

data class IfOpenTop(
    val interfaceId: Int,
    val type: Int,
    val xteas: IntArray,
) : ServerProt {
    init {
        require(xteas.size == 4) { "xteas must be length 4" }
    }
}

data class IfOpenSub(
    val interfaceId: Int,
    val componentId: Int,
    val subInterfaceId: Int,
    val overlay: Boolean,
    val xteas: IntArray,
) : ServerProt {
    init {
        require(xteas.size == 4) { "xteas must be length 4" }
    }
}

data class IfSetAngle(val interfaceId: Int, val componentId: Int, val pitch: Int, val roll: Int, val scale: Int) : ServerProt

data class IfSetPosition(val interfaceId: Int, val componentId: Int, val x: Int, val y: Int) : ServerProt

data class IfSetPlayerModelOther(val interfaceId: Int, val componentId: Int, val primaryModelId: Int, val secondaryModelId: Int) : ServerProt

data class IfSetScrollPos(val interfaceId: Int, val componentId: Int, val scrollPos: Int) : ServerProt

data class IfOpenSubActiveNpc(
    val interfaceId: Int,
    val componentId: Int,
    val subInterfaceId: Int,
    val overlay: Boolean,
    val npcIndex: Int,
    val xteas: IntArray,
) : ServerProt {
    init {
        require(xteas.size == 4) { "xteas must be length 4" }
    }
}

data class IfCloseSub(val interfaceId: Int, val componentId: Int) : ServerProt

data class IfSetAnim(val interfaceId: Int, val componentId: Int, val animationId: Int) : ServerProt

data class IfSetColor(val interfaceId: Int, val componentId: Int, val color: Int) : ServerProt

data class IfSetNpcHead(val interfaceId: Int, val componentId: Int, val npcId: Int) : ServerProt

data class IfSetItem(val interfaceId: Int, val componentId: Int, val itemId: Int, val amount: Int) : ServerProt

data class IfSetEvents(
    val interfaceId: Int,
    val componentId: Int,
    val fromSlot: Int,
    val toSlot: Int,
    val settings: Int,
) : ServerProt

data class IfSetText(val interfaceId: Int, val componentId: Int, val text: String) : ServerProt

data class IfSetReTex(val interfaceId: Int, val componentId: Int, val index: Int, val fromTextureId: Int, val toTextureId: Int) : ServerProt

data class IfSetModel(val interfaceId: Int, val componentId: Int, val modelId: Int) : ServerProt

data class IfSetReCol(val interfaceId: Int, val componentId: Int, val index: Int, val fromColor: Int, val toColor: Int) : ServerProt

data class IfSetTargetParam(
    val interfaceId: Int,
    val componentId: Int,
    val targetInterfaceId: Int,
    val fromSlot: Int,
    val toSlot: Int,
) : ServerProt

data class IfSetClickMask(val interfaceId: Int, val componentId: Int, val enabled: Boolean) : ServerProt

data class IfOpenSubActiveGroundItem(
    val interfaceId: Int,
    val componentId: Int,
    val subInterfaceId: Int,
    val overlay: Boolean,
    val tile: Tile,
    val itemId: Int,
    val xteas: IntArray,
) : ServerProt {
    init {
        require(xteas.size == 4) { "xteas must be length 4" }
    }
}

data class IfSetPlayerHeadOther(
    val interfaceId: Int,
    val componentId: Int,
    val key: Int,
    val value: Int,
) : ServerProt

/**
 * Update and inventory protocols
 */
data class UpdateInvPartial(
    val containerId: Int,
    val negativeKey: Boolean,
    val updates: Array<InvSlotUpdate>,
) : ServerProt {
    data class InvSlotUpdate(val slot: Int, val itemId: Int, val amount: Int)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as UpdateInvPartial
        if (containerId != other.containerId) return false
        if (negativeKey != other.negativeKey) return false
        if (!updates.contentEquals(other.updates)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = containerId
        result = 31 * result + negativeKey.hashCode()
        result = 31 * result + updates.contentHashCode()
        return result
    }
}

data class UpdateInvFull(
    val containerId: Int,
    val negativeKey: Boolean,
    val itemIds: IntArray,
    val amounts: IntArray,
) : ServerProt {
    init {
        require(itemIds.size == amounts.size) { "itemIds and amounts must have same length" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as UpdateInvFull
        if (containerId != other.containerId) return false
        if (negativeKey != other.negativeKey) return false
        if (!itemIds.contentEquals(other.itemIds)) return false
        if (!amounts.contentEquals(other.amounts)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = containerId
        result = 31 * result + negativeKey.hashCode()
        result = 31 * result + itemIds.contentHashCode()
        result = 31 * result + amounts.contentHashCode()
        return result
    }
}

/**
 * NPC update (small scene view).
 *
 * The actual on-wire byte stream is revision-specific and is encoded via the revision [Codec]'s npcUpdate encoder.
 */
data class NpcUpdate(
    val snapshot: org.darkan.core.net.prot.update.NpcUpdateSnapshot,
    val state: org.darkan.core.net.prot.update.NpcUpdateTrackingState,
) : ServerProt

data class UpdateZoneFullFollows(val chunkX: Int, val chunkY: Int, val plane: Int) : ServerProt

data class UpdateFriendchatChannelSingleUser(
    val displayName: String,
    val username: String = displayName,
    val worldId: Int,
    val rank: Int,
    val worldName: String? = null,
) : ServerProt

data class UpdateZonePartialFollows(val chunkX: Int, val chunkY: Int, val plane: Int) : ServerProt

data class UpdateInvStopTransmit(val containerId: Int, val negativeKey: Boolean) : ServerProt

/**
 * Player update.
 *
 * The actual on-wire byte stream is revision-specific and is encoded via the revision [Codec]'s playerUpdate encoder.
 */
data class PlayerUpdate(
    val snapshot: org.darkan.core.net.prot.update.PlayerUpdateSnapshot,
    val state: org.darkan.core.net.prot.update.PlayerUpdateTrackingState,
) : ServerProt

/**
 * NPC update (large scene view).
 *
 * Same structured payload as [NpcUpdate], but different opcode for some revisions (e.g. 727).
 */
data class NpcUpdateLarge(
    val snapshot: org.darkan.core.net.prot.update.NpcUpdateSnapshot,
    val state: org.darkan.core.net.prot.update.NpcUpdateTrackingState,
) : ServerProt

data class ClanChannelDelta(val main: Boolean, val data: ByteArray) : ServerProt {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ClanChannelDelta
        if (main != other.main) return false
        return data.contentEquals(other.data)
    }

    override fun hashCode(): Int = 31 * main.hashCode() + data.contentHashCode()
}

@Serializable data class ClanChannelFull(
    val main: Boolean,
    val clanName: String? = null,
    val updateNum: Long = 0,
    val kickRank: Int = -1,
    val talkRank: Int = -1,
    val chatters: Array<ClanChannelChatter>? = null,
) : ServerProt {
    @Serializable data class ClanChannelChatter(
        val displayName: String,
        val rank: Int,
        val worldId: Int,
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ClanChannelFull
        if (main != other.main) return false
        if (clanName != other.clanName) return false
        if (updateNum != other.updateNum) return false
        if (kickRank != other.kickRank) return false
        if (talkRank != other.talkRank) return false
        return chatters.contentEquals(other.chatters)
    }

    override fun hashCode(): Int {
        var result = main.hashCode()
        result = 31 * result + (clanName?.hashCode() ?: 0)
        result = 31 * result + updateNum.hashCode()
        result = 31 * result + kickRank
        result = 31 * result + talkRank
        result = 31 * result + (chatters?.contentHashCode() ?: 0)
        return result
    }
}

data class ClanSettingsDelta(val main: Boolean, val data: ByteArray) : ServerProt {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ClanSettingsDelta
        if (main != other.main) return false
        return data.contentEquals(other.data)
    }

    override fun hashCode(): Int = 31 * main.hashCode() + data.contentHashCode()
}

@Serializable data class ClanSettingsFull(
    val main: Boolean,
    val clanName: String? = null,
    val updateCount: Int = 0,
    val allowGuests: Boolean = false,
    val talkRank: Int = -1,
    val kickRank: Int = -1,
    val members: Array<ClanSettingsMember>? = null,
    val bannedUsers: Array<String>? = null,
    val settings: Array<ClanVarSetting>? = null,
) : ServerProt {
    @Serializable data class ClanSettingsMember(
        val displayName: String,
        val rank: Int,
        val flags: Int = 0,
    )
    @Serializable data class ClanVarSetting(
        val key: Int,
        val intValue: Int? = null,
        val longValue: Long? = null,
        val stringValue: String? = null,
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ClanSettingsFull
        if (main != other.main) return false
        if (clanName != other.clanName) return false
        if (updateCount != other.updateCount) return false
        if (allowGuests != other.allowGuests) return false
        if (talkRank != other.talkRank) return false
        if (kickRank != other.kickRank) return false
        if (!members.contentEquals(other.members)) return false
        if (!bannedUsers.contentEquals(other.bannedUsers)) return false
        return settings.contentEquals(other.settings)
    }

    override fun hashCode(): Int {
        var result = main.hashCode()
        result = 31 * result + (clanName?.hashCode() ?: 0)
        result = 31 * result + updateCount
        result = 31 * result + allowGuests.hashCode()
        result = 31 * result + talkRank
        result = 31 * result + kickRank
        result = 31 * result + (members?.contentHashCode() ?: 0)
        result = 31 * result + (bannedUsers?.contentHashCode() ?: 0)
        result = 31 * result + (settings?.contentHashCode() ?: 0)
        return result
    }
}

data class UpdateZonePartialEnclosed(val chunkX: Int, val chunkY: Int, val plane: Int, val data: ByteArray) : ServerProt {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as UpdateZonePartialEnclosed
        if (chunkX != other.chunkX) return false
        if (chunkY != other.chunkY) return false
        if (plane != other.plane) return false
        return data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = chunkX
        result = 31 * result + chunkY
        result = 31 * result + plane
        result = 31 * result + data.contentHashCode()
        return result
    }
}

data class UpdateGESlot(val slot: Int, val offer: Offer?) : ServerProt {
    data class Offer(
        val progress: Int,
        val itemId: Int,
        val price: Int,
        val amount: Int,
        val currentAmount: Int,
        val totalPrice: Int,
    )
}

@JvmInline value class UpdateUid192(val data: ByteArray) : ServerProt

@Serializable data class UpdateIgnoreList(val ignores: Array<IgnoreEntry>) : ServerProt {
    @Serializable data class IgnoreEntry(val username: String, val displayName: String)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as UpdateIgnoreList
        return ignores.contentEquals(other.ignores)
    }

    override fun hashCode(): Int = ignores.contentHashCode()
}

data class UpdateStat(val xp: Int, val skillId: Int, val level: Int) : ServerProt

/**
 * Region and map related protocols
 */
data class BuildRegion(
    val regionIds: IntArray,
    val mapSize: RegionSize = RegionSize.SIZE_104,
    val xteas: Array<IntArray>,
    val chunkX: Int = 0,
    val chunkY: Int = 0,
    val forceMapRefresh: Boolean = false,
    val localPlayerPid: Int? = null,
    val localPlayerBaseTileHash: Int? = null,
    val otherPlayerRegionIds: IntArray? = null
) : ServerProt {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as BuildRegion

        if (chunkX != other.chunkX) return false
        if (chunkY != other.chunkY) return false
        if (forceMapRefresh != other.forceMapRefresh) return false
        if (localPlayerPid != other.localPlayerPid) return false
        if (localPlayerBaseTileHash != other.localPlayerBaseTileHash) return false
        if (!regionIds.contentEquals(other.regionIds)) return false
        if (mapSize != other.mapSize) return false
        if (!xteas.contentDeepEquals(other.xteas)) return false
        if (!otherPlayerRegionIds.contentEquals(other.otherPlayerRegionIds)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = chunkX
        result = 31 * result + chunkY
        result = 31 * result + forceMapRefresh.hashCode()
        result = 31 * result + (localPlayerPid ?: 0)
        result = 31 * result + (localPlayerBaseTileHash ?: 0)
        result = 31 * result + regionIds.contentHashCode()
        result = 31 * result + mapSize.hashCode()
        result = 31 * result + xteas.contentDeepHashCode()
        result = 31 * result + (otherPlayerRegionIds?.contentHashCode() ?: 0)
        return result
    }
}

enum class InstanceLoadType(val index: Int) {
    // not sure if zero is even a valid input to the packet glancing at the
    // client logic, it shouldn't really perform different than 1 as they both
    // set the loading type to the same value
    STANDARD(1),                   //No difference from non-dynamic logic essentially
    STANDARD_NO_ENTITY_RESET(2),   //Same as above except this one won't reset local entities on load (Matrix and most other bases default to this)
    LARGE(3),                      //Ignores render-distance restrictions on zone updates (current Void default)
    LARGE_NO_UPDATEZONE_RESET(4)   //Ignores render-distance restrictions on zone updates and doesn't reset objs, locs, etc on load
}

data class BuildInstancedRegion(
    val mapSize: RegionSize = RegionSize.SIZE_104,
    val chunks: IntArray,
    val xteas: Array<IntArray>,
    val chunkX: Int,
    val chunkY: Int,
    val forceMapRefresh: Boolean = false,
    val instanceLoadType: InstanceLoadType = InstanceLoadType.STANDARD,
    val localPlayerPid: Int? = null,
    val localPlayerBaseTileHash: Int? = null,
    val otherPlayerRegionIds: IntArray? = null
) : ServerProt {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as BuildInstancedRegion
        if (chunkX != other.chunkX) return false
        if (chunkY != other.chunkY) return false
        if (forceMapRefresh != other.forceMapRefresh) return false
        if (localPlayerPid != other.localPlayerPid) return false
        if (localPlayerBaseTileHash != other.localPlayerBaseTileHash) return false
        if (mapSize != other.mapSize) return false
        if (!chunks.contentEquals(other.chunks)) return false
        if (!xteas.contentDeepEquals(other.xteas)) return false
        if (instanceLoadType != other.instanceLoadType) return false
        if (!otherPlayerRegionIds.contentEquals(other.otherPlayerRegionIds)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = chunkX
        result = 31 * result + chunkY
        result = 31 * result + forceMapRefresh.hashCode()
        result = 31 * result + (localPlayerPid ?: 0)
        result = 31 * result + (localPlayerBaseTileHash ?: 0)
        result = 31 * result + mapSize.hashCode()
        result = 31 * result + chunks.contentHashCode()
        result = 31 * result + xteas.contentDeepHashCode()
        result = 31 * result + instanceLoadType.hashCode()
        result = 31 * result + (otherPlayerRegionIds?.contentHashCode() ?: 0)
        return result
    }
}

@JvmInline value class ShowFaceHere(val enabled: Boolean) : ServerProt

data class MinimapFlag(val x: Int, val y: Int) : ServerProt

/**
 * Represents a single hint arrow slot update.
 *
 * - If [targetType] == 0, the hint arrow is cleared and other fields are ignored.
 * - If [targetType] is 1 or 10, [targetIndex] and [denominator] are used.
 * - Otherwise, [plane], [x], [y], [height], [distance] are used (tile based).
 */
data class HintArrow(
    val iconIndex: Int,
    val targetType: Int,
    val arrowSprite: Int = 0,
    val plane: Int = 0,
    val x: Int = 0,
    val y: Int = 0,
    val height: Int = 0,
    val distance: Int = 0,
    val targetIndex: Int = 0,
    val denominator: Int = 0,
    val modelId: Int = 0,
) : ServerProt

data class HintTrail(val index: Int, val modelId: Int, val data: ByteArray) : ServerProt {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as HintTrail
        if (index != other.index) return false
        if (modelId != other.modelId) return false
        return data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = index
        result = 31 * result + modelId
        result = 31 * result + data.contentHashCode()
        return result
    }
}

data class MapProjAnim(
    val localX: Int,
    val localY: Int,
    val xOffset: Int,
    val yOffset: Int,
    val lockOn: Int,
    val spotAnimId: Int,
    val startHeight: Int,
    val endHeight: Int,
    val startTime: Int,
    val endTime: Int,
    val angle: Int,
    val slope: Int,
    val useFloorHeight: Boolean,
) : ServerProt

data class MapProjAnimHalfSq(
    val localX: Int,
    val localY: Int,
    val xOffset: Int,
    val yOffset: Int,
    val source: Int,
    val lockOn: Int,
    val spotAnimId: Int,
    val startHeight: Int,
    val endHeight: Int,
    val startTime: Int,
    val endTime: Int,
    val angle: Int,
    val slope: Int,
    val basOffIdx: Int = -1,
    val useFloorHeight: Boolean = false,
) : ServerProt

@JvmInline value class SetDrawOrder(val playerFirst: Boolean) : ServerProt

@JvmInline value class BlockMinimapState(val state: Int) : ServerProt

/**
 * Object and ground item protocols
 */
data class GroundItemCount(val localX: Int, val localY: Int, val itemId: Int, val oldAmount: Int, val newAmount: Int) : ServerProt

data class CreateGroundItem(val localX: Int, val localY: Int, val itemId: Int, val amount: Int) : ServerProt

data class GroundItemReveal(val playerId: Int, val localX: Int, val localY: Int, val itemId: Int, val amount: Int) : ServerProt

data class RemoveGroundItem(val localX: Int, val localY: Int, val itemId: Int) : ServerProt

data class DestroyObject(val localX: Int, val localY: Int, val shape: Int, val rotation: Int) : ServerProt

data class ObjAnim(val localX: Int, val localY: Int, val shape: Int, val rotation: Int, val animationId: Int) : ServerProt

data class ObjAnimSpecific(val tile: Tile, val shape: Int, val rotation: Int, val animationId: Int) : ServerProt

data class CreateObject(val localX: Int, val localY: Int, val objectId: Int, val shape: Int, val rotation: Int) : ServerProt

data class TileMessage(val localX: Int, val localY: Int, val priority: Int, val duration: Int, val color: Int, val message: String) : ServerProt

data class CustomizeObject(
    val localX: Int,
    val localY: Int,
    val objectId: Int,
    val shape: Int,
    val rotation: Int,
    val clear: Boolean = false,
    val modelIds: IntArray? = null,
    val recolors: ShortArray? = null,
    val retextures: ShortArray? = null,
) : ServerProt

data class ObjectPrefetch(val objectId: Int, val typeIndex: Int) : ServerProt

/**
 * Animation related protocols
 */
data class SpotAnim(val localX: Int, val localY: Int, val spotAnimId: Int, val height: Int, val speed: Int, val rotation: Int) : ServerProt

data class SpotAnimSpecific(
    val slot: Int,
    val spotAnimId: Int,
    val settings: Int,
    val speed: Int,
    val targetHash: Int,
    val height: Int,
) : ServerProt

data class AnimateNpc(val npcIndex: Int, val animationIds: IntArray, val speed: Int) : ServerProt {
    init {
        require(animationIds.size == 4) { "animationIds must be length 4" }
    }
}

data class ProjAnimSpecific(
    val endHeight: Int,
    val source: Int,
    val endTime: Int,
    val lockOn: Int,
    val flags: Int,
    val y: Int,
    val slope: Int,
    val startTime: Int,
    val spotAnimId: Int,
    val angle: Int,
    val xDist: Int,
    val yDist: Int,
    val x: Int,
    val startHeight: Int,
) : ServerProt

@JvmInline value class ResetAllAnimations(val dummy: Int = 0) : ServerProt

/**
 * Audio related protocols
 */
data class VorbisSound(val soundId: Int, val repeat: Int, val delay: Int, val volume: Int, val sampleRate: Int) : ServerProt

data class SoundEffectTile(
    val localX: Int,
    val localY: Int,
    val soundId: Int,
    val range: Int,
    val mode: Int,
    val delay: Int,
    val volume: Int,
    val sampleRate: Int,
) : ServerProt

data class VorbisSpeechSound(val soundId: Int, val soundType: Int, val delay: Int, val volume: Int) : ServerProt

data class SoundSynth(val soundId: Int, val flags: Int, val unknown: Int, val delay: Int, val unknown2: Int) : ServerProt

data class MusicEffect(val key: Int, val volume: Int, val musicId: Int) : ServerProt

data class MusicTrack(val key: Int, val flags: Int, val musicId: Int) : ServerProt

data class MusicTrackTile(val plane: Int, val x: Int, val y: Int, val near: Int, val far: Int, val volume: Int, val musicArchive: Int) : ServerProt

@JvmInline value class PreloadSong(val songId: Int) : ServerProt

data class SoundMixbussSetLevel(val level: Int, val index: Int) : ServerProt

@JvmInline value class ResetSounds(val dummy: Int = 0) : ServerProt

/**
 * Camera related protocols
 */
data class CamLookAt(val viewLocalX: Int, val viewLocalY: Int, val viewZ: Int, val speed1: Int, val speed2: Int) : ServerProt
data class CamMoveTo(val moveLocalX: Int, val moveLocalY: Int, val moveZ: Int, val speed1: Int, val speed2: Int) : ServerProt
@JvmInline value class CamRemoveRoof(val tile: Tile) : ServerProt
@JvmInline value class CamResetHard(val dummy: Int = 0) : ServerProt
@JvmInline value class CamResetSmooth(val dummy: Int = 0) : ServerProt
data class CamShake(val slotId: Int, val v1: Int, val v2: Int, val v3: Int, val v4: Int) : ServerProt
data class CamForceAngle(val angleX: Int, val angleY: Int) : ServerProt

/**
 * Messaging protocols
 */
@Serializable data class MessagePrivate(val crown: Int, val displayName: String, val quickResponseName: String = displayName, val message: String) : ServerProt
@Serializable data class MessagePrivateEcho(val senderDisplayName: String, val message: String) : ServerProt

data class MessageQuickChatPrivate(val crown: Int, val displayName: String, val quickResponseName: String = displayName, val message: QuickChatMessage) : ServerProt
data class MessageQuickChatPrivateEcho(val senderDisplayName: String, val message: QuickChatMessage) : ServerProt

@Serializable data class MessageFriendsChat(val crown: Int, val displayName: String, val quickResponseName: String = displayName, val chatName: String, val message: String) : ServerProt
data class MessageQuickChatFriendsChat(val chatName: String, val crown: Int, val displayName: String, val quickResponseName: String = displayName, val message: QuickChatMessage) : ServerProt

@Serializable data class MessageClanChannel(val guest: Boolean, val crown: Int, val displayName: String, val message: String) : ServerProt
data class MessageQuickChatClanChannel(val guest: Boolean, val crown: Int, val displayName: String, val message: QuickChatMessage) : ServerProt

data class MessagePlayerGroup(val crown: Int, val displayName: String, val quickResponseName: String = displayName, val message: String) : ServerProt
data class MessageQuickChatPlayerGroup(val crown: Int, val displayName: String, val quickResponseName: String = displayName, val message: QuickChatMessage) : ServerProt

data class MessagePublic(val pid: Int, val messageIcon: Int, val message: ChatMessage) : ServerProt

@Serializable data class GameMessage(val type: ChatMessageType, val message: String, val targetDisplayName: String? = null, val effectFlags: Int = 0) : ServerProt

/**
 * Variable related protocols
 */
data class VarpSmall(val id: Int, val value: Int) : ServerProt
data class VarpLarge(val id: Int, val value: Int) : ServerProt
fun setVarpPacket(id: Int, value: Int) = if (value < Byte.MIN_VALUE || value > Byte.MAX_VALUE) VarpLarge(id, value) else VarpSmall(id, value)

data class ClientSetVarcSmall(val id: Int, val value: Int) : ServerProt
data class ClientSetVarcLarge(val id: Int, val value: Int) : ServerProt
fun setVarcPacket(id: Int, value: Int) = if (value < Byte.MIN_VALUE || value > Byte.MAX_VALUE) ClientSetVarcLarge(id, value) else ClientSetVarcSmall(id, value)

data class VarbitSmall(val id: Int, val value: Int) : ServerProt
data class VarbitLarge(val id: Int, val value: Int) : ServerProt
fun setVarbitPacket(id: Int, value: Int) = if (value < Byte.MIN_VALUE || value > Byte.MAX_VALUE) VarbitLarge(id, value) else VarbitSmall(id, value)

data class ClientSetVarcStrSmall(val id: Int, val value: String) : ServerProt
data class ClientSetVarcStrLarge(val id: Int, val value: String) : ServerProt
fun setVarcStrPacket(id: Int, value: String) = if (value.length+2 > Byte.MAX_VALUE) ClientSetVarcStrLarge(id, value) else ClientSetVarcStrSmall(id, value)

data class VarclanSetLong(val id: Int, val value: Long) : ServerProt
data class VarclanSetByte(val id: Int, val value: Int) : ServerProt
data class VarclanSetInt(val id: Int, val value: Int) : ServerProt
data class VarclanSetString(val id: Int, val value: String) : ServerProt

@Serializable @JvmInline value class VarclanEnable(val dummy: Int = 0) : ServerProt
@Serializable @JvmInline value class VarclanDisable(val dummy: Int = 0) : ServerProt
@JvmInline value class ClearVarps(val dummy: Int = 0) : ServerProt

/**
 * Player related protocols
 */
@JvmInline value class PlayerWeight(val weight: Int) : ServerProt

data class PlayerOption(val option: String, val slot: Int, val top: Boolean, val cursor: Int) : ServerProt

@JvmInline value class RunEnergy(val energy: Int) : ServerProt

@Serializable data class FriendStatus(val updates: Array<FriendStatusUpdate>) : ServerProt {
    @Serializable data class FriendStatusUpdate(
        val warnMessage: Boolean,
        val displayName: String,
        val previousDisplayName: String,
        val world: Int,
        val friendsChatRank: Int,
        val referrer: Boolean,
        val worldName: String? = null,
        val platform: Int = -1,
        val worldFlags: Int = 0,
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as FriendStatus
        return updates.contentEquals(other.updates)
    }

    override fun hashCode(): Int = updates.contentHashCode()
}

@Serializable @JvmInline value class FriendlistLoaded(val dummy: Int = 0) : ServerProt

data class AddIgnore(val username: String, val displayName: String, val temporary: Boolean = false, val editDisplayName: Boolean = false) : ServerProt

@JvmInline value class ReduceAttackPriority(val enabled: Boolean) : ServerProt

@JvmInline value class SetTarget(val target: Int) : ServerProt

/**
 * Friend and clan chat protocols
 */
@Serializable data class FriendsChatChannel(
    val clear: Boolean = false,
    val ownerDisplayName: String? = null,
    val ownerUsername: String? = ownerDisplayName,
    val chatName: String? = null,
    val minRankCanKick: Int = 0,
    val players: Array<FriendsChatPlayer>? = null,
) : ServerProt {
    @Serializable data class FriendsChatPlayer(
        val displayName: String,
        val username: String = displayName,
        val worldId: Int,
        val rank: Int,
        val worldName: String,
    )

    init {
        if (!clear) {
            require(ownerDisplayName != null) { "ownerDisplayName is required unless clear=true" }
            require(chatName != null) { "chatName is required unless clear=true" }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as FriendsChatChannel
        if (clear != other.clear) return false
        if (ownerDisplayName != other.ownerDisplayName) return false
        if (ownerUsername != other.ownerUsername) return false
        if (chatName != other.chatName) return false
        if (minRankCanKick != other.minRankCanKick) return false
        return players.contentEquals(other.players)
    }

    override fun hashCode(): Int {
        var result = clear.hashCode()
        result = 31 * result + (ownerDisplayName?.hashCode() ?: 0)
        result = 31 * result + (ownerUsername?.hashCode() ?: 0)
        result = 31 * result + (chatName?.hashCode() ?: 0)
        result = 31 * result + minRankCanKick
        result = 31 * result + (players?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * Login and system related protocols
 */
@JvmInline value class LogoutLobby(val dummy: Int = 0) : ServerProt

@JvmInline value class LogoutFull(val dummy: Int = 0) : ServerProt

data class OpenUrl(val url: String, val displayUrl: String? = null) : ServerProt

data class WorldListPacket(val worldList: WorldList, val refreshOnClient: Boolean, val refreshOnlyPlayerCounts: Boolean) : ServerProt

@JvmInline value class IdentifyHostName(val ip: Int) : ServerProt

@JvmInline value class UpdateRebootTimer(val seconds: Int) : ServerProt

data class RequestFps(val key: Int, val flags: Int) : ServerProt

@JvmInline value class UpdateSitesettingsCookie(val dummy: Int = 0) : ServerProt

data class ChatFilterSettings(val tradeFilter: Int, val publicFilter: Int) : ServerProt

@Serializable @JvmInline value class ChatFilterSettingsPrivateChat(val privateFilter: Int) : ServerProt

/**
 * Account related protocols
 */
@JvmInline value class CreateCheckEmailReply(val responseCode: Int) : ServerProt

enum class CreateAccountReplyOpcode(val value: Int) {
    ERROR_CONTACTING_SERVER(1),
    LOGIN(2),
    CANNOT_CREATE_ACCOUNT_ATM(9),
    UNEXPECTED_SERVER_RESPONSE(10),
    EMAIL_ALREADY_IN_USE(20),
    INVALID_EMAIL(21),
    PLEASE_SUPPLY_VALID_PASS(30),
    PASSWORDS_MAY_ONLY_CONTAIN_LETTERS_AND_NUMBERS(31),
    PASSWORD_TOO_EASY(32)
}

@JvmInline value class CreateAccountReply(val code: CreateAccountReplyOpcode) : ServerProt

data class UpdateDob(val dob: Int, val quickChatOnly: Boolean) : ServerProt

/**
 * Miscellaneous protocols
 */
class Pong : ServerProt

@JvmInline value class ProcessDevConsoleCommand(val command: String) : ServerProt

data class ReflectionRequest(val id: Int, val checks: Array<ReflectionCheck>) : ServerProt {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ReflectionRequest
        if (id != other.id) return false
        if (!checks.contentEquals(other.checks)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + checks.contentHashCode()
        return result
    }
}

data class RunCs2Script(val signature: String, val trigger: Int, val args: Array<Any?>) : ServerProt {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as RunCs2Script
        if (signature != other.signature) return false
        if (trigger != other.trigger) return false
        return args.contentEquals(other.args)
    }

    override fun hashCode(): Int {
        var result = signature.hashCode()
        result = 31 * result + trigger
        result = 31 * result + args.contentHashCode()
        return result
    }
}

data class SetCursor(val cursorId: Int = -1, val uptext: String? = null) : ServerProt

data class Cutscene(val cutsceneId: Int, val mapXteas: Array<IntArray>, val data: ByteArray) : ServerProt {
    init {
        require(mapXteas.all { it.size == 4 }) { "Each xtea set must be length 4" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Cutscene
        if (cutsceneId != other.cutsceneId) return false
        if (!mapXteas.contentDeepEquals(other.mapXteas)) return false
        return data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = cutsceneId
        result = 31 * result + mapXteas.contentDeepHashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}

@JvmInline value class TriggerOnDialogAbort(val dummy: Int = 0) : ServerProt

@JvmInline value class KeepAlive(val dummy: Int = 0) : ServerProt

data class LoyaltyUpdate(val balance: Int, val enabled: Boolean) : ServerProt

@JvmInline value class JCoinsUpdate(val amount: Int) : ServerProt

@JvmInline value class ApplyDebug(val opcode: Int) : ServerProt

data class QuickHopWorlds(val host: String, val port: Int, val reconnecting: Boolean) : ServerProt

data class DebugServerTriggers(val interfaceId: Int, val fromComponent: Int, val toComponent: Int, val triggers: IntArray) : ServerProt {
    init {
        require(toComponent >= fromComponent) { "toComponent must be >= fromComponent" }
        require(triggers.size == (toComponent - fromComponent)) { "triggers length must match component range" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as DebugServerTriggers
        if (interfaceId != other.interfaceId) return false
        if (fromComponent != other.fromComponent) return false
        if (toComponent != other.toComponent) return false
        return triggers.contentEquals(other.triggers)
    }

    override fun hashCode(): Int {
        var result = interfaceId
        result = 31 * result + fromComponent
        result = 31 * result + toComponent
        result = 31 * result + triggers.contentHashCode()
        return result
    }
}

// Note: These pre-world packets would need special handling
data class WorldLoginDetails(
    val rights: Int,
    val modLevel: Int,
    val quickChat: Boolean,
    val verifiedEmail: Boolean,
    val aBool7322: Boolean,
    val quickChatOnly: Boolean,
    val playerIndex: Int,
    val members: Boolean,
    val dob: Int,
    val memberWorld: Boolean,
    val worldName: String,
) : ServerProt

data class LobbyLoginDetails(val account: Account, val worldLoginToken: String) : ServerProt