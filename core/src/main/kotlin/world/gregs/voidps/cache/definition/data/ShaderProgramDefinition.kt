package world.gregs.voidps.cache.definition.data

class ShaderProgramDefinition(
    var header: Int = HEADER,
    var name: String? = null,
    var variables: List<ShaderProgramVariable> = emptyList(),
    var optionalVariables: List<ShaderProgramVariable> = emptyList(),
    var mappings: List<ShaderProgramMapping> = emptyList()
) {

    companion object {
        const val HEADER = 4
    }
}
