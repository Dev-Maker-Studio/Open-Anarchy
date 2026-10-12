package org.darkan.core.net.prot.update

data class PlayerBitConfig(
    val smallTeleportThreshold: Int,
    val smallTeleportBits: Int,
    val largeTeleportBits: Int,
    val walkDirectionBits: Int,
    val runDirectionBits: Int,
    val skipThresholds: IntArray,
    val skipBitWidths: IntArray,
    val addPositionBits: Int,
    val maxAddPerTick: Int,
) {
    init {
        require(skipThresholds.size == skipBitWidths.size) {
            "skipThresholds and skipBitWidths must have the same length"
        }
        require(skipThresholds.isNotEmpty() && skipThresholds[0] == 0) {
            "skipThresholds[0] must be 0 (exact-match tier)"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PlayerBitConfig) return false
        return smallTeleportThreshold == other.smallTeleportThreshold &&
            smallTeleportBits == other.smallTeleportBits &&
            largeTeleportBits == other.largeTeleportBits &&
            walkDirectionBits == other.walkDirectionBits &&
            runDirectionBits == other.runDirectionBits &&
            skipThresholds.contentEquals(other.skipThresholds) &&
            skipBitWidths.contentEquals(other.skipBitWidths) &&
            addPositionBits == other.addPositionBits &&
            maxAddPerTick == other.maxAddPerTick
    }

    override fun hashCode(): Int {
        var result = smallTeleportThreshold
        result = 31 * result + smallTeleportBits
        result = 31 * result + largeTeleportBits
        result = 31 * result + walkDirectionBits
        result = 31 * result + runDirectionBits
        result = 31 * result + skipThresholds.contentHashCode()
        result = 31 * result + skipBitWidths.contentHashCode()
        result = 31 * result + addPositionBits
        result = 31 * result + maxAddPerTick
        return result
    }
}

data class NpcBitConfig(
    val localCountBits: Int,
    val indexBits: Int,
    val normalPositionBits: Int,
    val largePositionBits: Int,
    val rotationBits: Int,
    val terminator: Int,
    val walkDirectionBits: Int,
    val idBits: Int,
    val planeBits: Int,
)
