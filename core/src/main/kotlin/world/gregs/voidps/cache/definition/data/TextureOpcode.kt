package world.gregs.voidps.cache.definition.data

data class TextureOpcode(
    var opcode: Int = 0,
    var value: Int? = null,
    var values: IntArray? = null,
    var shapes: List<TextureShape>? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TextureOpcode

        if (opcode != other.opcode) return false
        if (value != other.value) return false
        if (!values.contentEquals(other.values)) return false
        if (shapes != other.shapes) return false

        return true
    }

    override fun hashCode(): Int {
        var result = opcode
        result = 31 * result + (value ?: 0)
        result = 31 * result + (values?.contentHashCode() ?: 0)
        result = 31 * result + (shapes?.hashCode() ?: 0)
        return result
    }
}
