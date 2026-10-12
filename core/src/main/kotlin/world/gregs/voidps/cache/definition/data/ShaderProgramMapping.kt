package world.gregs.voidps.cache.definition.data

class ShaderProgramMapping(
    var name: String?,
    var vertexSource: String?,
    var fragmentSource: String?,
    var vertexDefines: List<ShaderProgramDefine>,
    var fragmentDefines: List<ShaderProgramDefine>
)
