package world.gregs.voidps.cache.definition.data

data class TextureOp(
    var type: Int = 0,
    var imageCacheCapacity: Int = 0,
    var opcodes: List<TextureOpcode> = emptyList(),
    var children: IntArray = IntArray(0)
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TextureOp

        if (type != other.type) return false
        if (imageCacheCapacity != other.imageCacheCapacity) return false
        if (opcodes != other.opcodes) return false
        if (!children.contentEquals(other.children)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = type
        result = 31 * result + imageCacheCapacity
        result = 31 * result + opcodes.hashCode()
        result = 31 * result + children.contentHashCode()
        return result
    }
}
