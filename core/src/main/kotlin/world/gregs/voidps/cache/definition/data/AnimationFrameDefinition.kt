package world.gregs.voidps.cache.definition.data

import world.gregs.voidps.cache.Definition

data class AnimationFrameDefinition(
    override var id: Int = -1,
    var baseId: Int = -1,
    var header: Int = 1,
    var flags: IntArray = IntArray(0),
    var values: IntArray = IntArray(0),
    var wideValues: IntArray? = null
) : Definition {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AnimationFrameDefinition
        if (id != other.id) return false
        if (baseId != other.baseId) return false
        if (header != other.header) return false
        if (!flags.contentEquals(other.flags)) return false
        if (!values.contentEquals(other.values)) return false
        if (wideValues != null) {
            if (other.wideValues == null) return false
            if (!wideValues.contentEquals(other.wideValues)) return false
        } else if (other.wideValues != null) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + baseId
        result = 31 * result + header
        result = 31 * result + flags.contentHashCode()
        result = 31 * result + values.contentHashCode()
        result = 31 * result + (wideValues?.contentHashCode() ?: 0)
        return result
    }

    companion object {
        const val WRAPPED_COUNT = 256
    }
}
