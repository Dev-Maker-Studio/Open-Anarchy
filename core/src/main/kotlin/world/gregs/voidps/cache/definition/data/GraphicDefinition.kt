package world.gregs.voidps.cache.definition.data

import world.gregs.voidps.cache.Definition

data class GraphicDefinition(
    override var id: Int = -1,
    var graphics: Array<IndexedGraphic>? = null,
    var rawPalette: IntArray = IntArray(0),
    var paletteZeroEntry: Boolean = false,
    var width: Int = 0,
    var height: Int = 0
) : Definition {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as GraphicDefinition

        if (id != other.id) return false
        if (graphics != null) {
            if (other.graphics == null) return false
            if (!graphics.contentEquals(other.graphics)) return false
        } else if (other.graphics != null) return false
        if (!rawPalette.contentEquals(other.rawPalette)) return false
        if (paletteZeroEntry != other.paletteZeroEntry) return false
        if (width != other.width) return false
        if (height != other.height) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + (graphics?.contentHashCode() ?: 0)
        result = 31 * result + rawPalette.contentHashCode()
        result = 31 * result + paletteZeroEntry.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        return result
    }
}
