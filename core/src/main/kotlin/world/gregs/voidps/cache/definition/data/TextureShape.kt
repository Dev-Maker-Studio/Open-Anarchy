package world.gregs.voidps.cache.definition.data

data class TextureShape(
    var shape: Int = 0,
    var coordinates: IntArray = IntArray(0),
    var fill: Int? = null,
    var stroke: Int = 0,
    var strokeWidth: Int = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TextureShape

        if (shape != other.shape) return false
        if (!coordinates.contentEquals(other.coordinates)) return false
        if (fill != other.fill) return false
        if (stroke != other.stroke) return false
        if (strokeWidth != other.strokeWidth) return false

        return true
    }

    override fun hashCode(): Int {
        var result = shape
        result = 31 * result + coordinates.contentHashCode()
        result = 31 * result + (fill ?: 0)
        result = 31 * result + stroke
        result = 31 * result + strokeWidth
        return result
    }
}
