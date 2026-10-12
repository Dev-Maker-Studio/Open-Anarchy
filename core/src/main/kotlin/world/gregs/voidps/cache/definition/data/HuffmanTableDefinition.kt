package world.gregs.voidps.cache.definition.data

import world.gregs.voidps.cache.Definition

data class HuffmanTableDefinition(
    override var id: Int = -1,
    var lengths: IntArray = IntArray(0)
) : Definition {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as HuffmanTableDefinition
        return id == other.id && lengths.contentEquals(other.lengths)
    }

    override fun hashCode(): Int = 31 * id + lengths.contentHashCode()
}
