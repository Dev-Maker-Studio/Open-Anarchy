package world.gregs.voidps.cache.definition

object TextureOpFormat {

    const val NONE = 0

    const val BYTE = 1

    const val SHORT = 2

    const val MEDIUM = 3

    const val CURVE = 4

    const val GRADIENT = 5

    const val RASTER = 6

    const val PERLIN = 7

    const val PERLIN_STEPS = 4

    const val LINE = 0

    const val BEZIER = 1

    const val RECTANGLE = 2

    const val ELLIPSE = 3

    private val CHILDREN = intArrayOf(
        0, 0, 0, 0, 0, 1, 1, 2, 1, 1,
        1, 1, 0, 0, 0, 0, 0, 1, 0, 3,
        1, 3, 1, 1, 1, 1, 1, 0, 0, 0,
        1, 0, 1, 1, 0, 1, 0, 0, 0, 0
    )

    private val PAYLOADS: Array<IntArray> = arrayOf(
        intArrayOf(BYTE),
        intArrayOf(MEDIUM),
        IntArray(0),
        IntArray(0),
        intArrayOf(BYTE, BYTE, SHORT, SHORT, SHORT, SHORT, SHORT, SHORT),
        intArrayOf(BYTE, BYTE, BYTE),
        intArrayOf(SHORT, SHORT, BYTE),
        intArrayOf(BYTE, BYTE),
        intArrayOf(CURVE),
        intArrayOf(BYTE, BYTE, BYTE),
        intArrayOf(GRADIENT),
        intArrayOf(SHORT, SHORT, SHORT),
        intArrayOf(BYTE, BYTE, NONE, BYTE),
        IntArray(0),
        intArrayOf(SHORT),
        intArrayOf(BYTE, BYTE, SHORT, BYTE, BYTE, BYTE, BYTE),
        intArrayOf(BYTE, BYTE, SHORT),
        intArrayOf(SHORT, BYTE, BYTE),
        intArrayOf(SHORT),
        intArrayOf(SHORT, BYTE),
        intArrayOf(BYTE, BYTE),
        intArrayOf(BYTE),
        intArrayOf(BYTE),
        intArrayOf(BYTE),
        IntArray(0),
        intArrayOf(SHORT, SHORT, SHORT, SHORT, MEDIUM),
        intArrayOf(SHORT, SHORT),
        intArrayOf(BYTE, SHORT, BYTE),
        intArrayOf(BYTE, SHORT, SHORT, SHORT, SHORT, SHORT, BYTE, SHORT, SHORT),
        intArrayOf(RASTER, BYTE),
        intArrayOf(SHORT, SHORT, BYTE),
        intArrayOf(SHORT, SHORT, SHORT, SHORT),
        intArrayOf(SHORT, SHORT, SHORT),
        intArrayOf(SHORT, BYTE),
        intArrayOf(BYTE, BYTE, PERLIN, BYTE, BYTE, BYTE, BYTE),
        intArrayOf(SHORT),
        intArrayOf(SHORT),
        intArrayOf(SHORT, SHORT, SHORT, SHORT, SHORT, SHORT, SHORT),
        intArrayOf(BYTE, SHORT, BYTE, SHORT, SHORT),
        intArrayOf(SHORT)
    )

    val types: Int
        get() = PAYLOADS.size

    fun children(type: Int): Int {
        require(type in CHILDREN.indices) { "Texture operation type $type does not exist." }
        return CHILDREN[type]
    }

    fun payload(type: Int, opcode: Int): Int {
        require(type in PAYLOADS.indices) { "Texture operation type $type does not exist." }
        val payloads = PAYLOADS[type]
        return if (opcode in payloads.indices) payloads[opcode] else NONE
    }

    fun coordinates(shape: Int): Int = when (shape) {
        LINE, RECTANGLE, ELLIPSE -> 4
        BEZIER -> 8
        else -> error("Texture rasterizer shape $shape does not exist; the client reads nothing for it.")
    }

    fun filled(shape: Int): Boolean = shape == RECTANGLE || shape == ELLIPSE
}
