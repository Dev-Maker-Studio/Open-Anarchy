package world.gregs.voidps.cache.definition.data

class ShaderProgramVariable(
    var type: Int,
    var elementType: Int?,
    var arraySize: Int?,
    var name: String?,
    var alias: String?,
    var program: IntArray,
    var slot: IntArray
) {

    companion object {
        const val ARRAY = 2
    }
}
