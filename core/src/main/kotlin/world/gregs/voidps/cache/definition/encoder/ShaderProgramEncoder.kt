package world.gregs.voidps.cache.definition.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.definition.data.ShaderProgramDefine
import world.gregs.voidps.cache.definition.data.ShaderProgramDefinition
import world.gregs.voidps.cache.definition.data.ShaderProgramMapping
import world.gregs.voidps.cache.definition.data.ShaderProgramVariable

class ShaderProgramEncoder {

    fun Writer.encode(definition: ShaderProgramDefinition) {
        writeShortLittle(definition.header)
        string(definition.name)
        writeShortLittle(definition.variables.size)
        writeShortLittle(definition.optionalVariables.size)
        writeShortLittle(definition.mappings.size)
        for (variable in definition.variables) {
            encode(variable)
        }
        for (variable in definition.optionalVariables) {
            encode(variable)
        }
        for (mapping in definition.mappings) {
            encode(mapping)
        }
    }

    private fun Writer.encode(variable: ShaderProgramVariable) {
        writeShortLittle(variable.type)
        if (variable.type == ShaderProgramVariable.ARRAY) {
            writeShortLittle(
                requireNotNull(variable.elementType) { "An array shader uniform needs an element type." }
            )
            writeShortLittle(
                requireNotNull(variable.arraySize) { "An array shader uniform needs a size." }
            )
        }
        string(variable.name)
        string(variable.alias)
        require(variable.program.size == variable.slot.size) {
            "A shader uniform binds ${variable.program.size} registers and ${variable.slot.size} slots."
        }
        writeShortLittle(variable.program.size)
        for (binding in variable.program.indices) {
            writeShortLittle(variable.program[binding])
            writeShortLittle(variable.slot[binding])
        }
    }

    private fun Writer.encode(mapping: ShaderProgramMapping) {
        string(mapping.name)
        string(mapping.vertexSource)
        string(mapping.fragmentSource)
        writeShortLittle(mapping.vertexDefines.size)
        writeShortLittle(mapping.fragmentDefines.size)
        for (define in mapping.vertexDefines) {
            encode(define)
        }
        for (define in mapping.fragmentDefines) {
            encode(define)
        }
    }

    private fun Writer.encode(define: ShaderProgramDefine) {
        string(define.name)
        string(define.value)
    }

    private fun Writer.string(value: String?) {
        if (value == null) {
            writeShortLittle(NULL)
            return
        }
        val bytes = value.toByteArray(Charsets.ISO_8859_1)
        writeShortLittle(bytes.size)
        for (byte in bytes) {
            writeByte(byte.toInt())
        }
    }

    private companion object {
        private const val NULL = -1
    }
}
