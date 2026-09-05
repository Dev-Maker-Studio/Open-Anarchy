/**
 * Region building, minimap, hint arrows and camera packets.
 */
package org.darkan.core.net.prot.packet

import org.darkan.core.model.RegionSize
import org.darkan.core.net.prot.ServerProt
import org.darkan.core.net.prot.dto.InstanceLoadType
import world.gregs.voidps.type.Tile

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
    val arrowGraphic: Int = 0,
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

@JvmInline value class BlockMinimapState(val state: Int) : ServerProt

// Camera related protocols
data class CamLookAt(val viewLocalX: Int, val viewLocalY: Int, val viewZ: Int, val speed1: Int, val speed2: Int) : ServerProt

data class CamMoveTo(val moveLocalX: Int, val moveLocalY: Int, val moveZ: Int, val speed1: Int, val speed2: Int) : ServerProt

@JvmInline value class CamRemoveRoof(val tile: Tile) : ServerProt

@JvmInline value class CamResetHard(val dummy: Int = 0) : ServerProt

@JvmInline value class CamResetSmooth(val dummy: Int = 0) : ServerProt

data class CamShake(val slotId: Int, val v1: Int, val v2: Int, val v3: Int, val v4: Int) : ServerProt

data class CamForceAngle(val angleX: Int, val angleY: Int) : ServerProt
