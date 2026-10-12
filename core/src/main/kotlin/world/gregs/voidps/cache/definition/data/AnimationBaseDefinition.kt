package world.gregs.voidps.cache.definition.data

import world.gregs.voidps.cache.Definition

data class AnimationBaseDefinition(
    override var id: Int = -1,
    var types: IntArray = IntArray(0),
    var shadowed: IntArray = IntArray(0),
    var submeshes: IntArray = IntArray(0),
    var labels: Array<IntArray?> = emptyArray(),
    var trailing: ByteArray? = null
) : Definition {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AnimationBaseDefinition
        if (id != other.id) return false
        if (!types.contentEquals(other.types)) return false
        if (!shadowed.contentEquals(other.shadowed)) return false
        if (!submeshes.contentEquals(other.submeshes)) return false
        if (!labels.contentDeepEquals(other.labels)) return false
        if (trailing != null) {
            if (other.trailing == null) return false
            if (!trailing.contentEquals(other.trailing)) return false
        } else if (other.trailing != null) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + types.contentHashCode()
        result = 31 * result + shadowed.contentHashCode()
        result = 31 * result + submeshes.contentHashCode()
        result = 31 * result + labels.contentDeepHashCode()
        result = 31 * result + (trailing?.contentHashCode() ?: 0)
        return result
    }

    companion object {
        const val WRAPPED_COUNT = 256
    }
}
