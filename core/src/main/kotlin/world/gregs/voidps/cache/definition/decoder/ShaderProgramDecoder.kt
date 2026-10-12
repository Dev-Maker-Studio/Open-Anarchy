package world.gregs.voidps.cache.definition.decoder

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.definition.data.ShaderProgramDefine
import world.gregs.voidps.cache.definition.data.ShaderProgramDefinition
import world.gregs.voidps.cache.definition.data.ShaderProgramMapping
import world.gregs.voidps.cache.definition.data.ShaderProgramVariable

class ShaderProgramDecoder {

    fun isProgram(data: ByteArray): Boolean =
        data.size >= 2 && short(data, 0) == ShaderProgramDefinition.HEADER

    fun read(definition: ShaderProgramDefinition, data: ByteArray) {
        val buffer = BufferReader(data)
        read(definition, buffer)
        check(buffer.remaining == 0) {
            "A shader program definition has ${buffer.remaining} bytes left over after its mappings."
        }
    }

    fun read(definition: ShaderProgramDefinition, buffer: Reader) {
        definition.header = buffer.readShortLittle()
        check(definition.header == ShaderProgramDefinition.HEADER) {
            "A shader program definition's header is ${definition.header}, not ${ShaderProgramDefinition.HEADER}."
        }
        definition.name = string(buffer)
        val variables = buffer.readShortLittle()
        val optional = buffer.readShortLittle()
        val mappings = buffer.readShortLittle()
        definition.variables = List(variables) { variable(buffer) }
        definition.optionalVariables = List(optional) { variable(buffer) }
        definition.mappings = List(mappings) { mapping(buffer) }
    }

    private fun variable(buffer: Reader): ShaderProgramVariable {
        val type = buffer.readShortLittle()
        val array = type == ShaderProgramVariable.ARRAY
        val elementType = if (array) buffer.readShortLittle() else null
        val arraySize = if (array) buffer.readShortLittle() else null
        val name = string(buffer)
        val alias = string(buffer)
        val bindings = buffer.readShortLittle()
        val program = IntArray(bindings)
        val slot = IntArray(bindings)
        for (binding in 0 until bindings) {
            program[binding] = buffer.readShortLittle()
            slot[binding] = buffer.readShortLittle()
        }
        return ShaderProgramVariable(type, elementType, arraySize, name, alias, program, slot)
    }

    private fun mapping(buffer: Reader): ShaderProgramMapping {
        val name = string(buffer)
        val vertex = string(buffer)
        val fragment = string(buffer)
        val vertexDefines = buffer.readShortLittle()
        val fragmentDefines = buffer.readShortLittle()
        return ShaderProgramMapping(
            name = name,
            vertexSource = vertex,
            fragmentSource = fragment,
            vertexDefines = List(vertexDefines) { define(buffer) },
            fragmentDefines = List(fragmentDefines) { define(buffer) }
        )
    }

    private fun define(buffer: Reader) = ShaderProgramDefine(string(buffer), string(buffer))

    private fun string(buffer: Reader): String? {
        val length = buffer.readShortLittle()
        if (length == NULL) {
            return null
        }
        check(length in 0..LIMIT) { "A shader program definition string is $length bytes long." }
        val bytes = ByteArray(length)
        buffer.readBytes(bytes)
        return String(bytes, Charsets.ISO_8859_1)
    }

    private fun short(data: ByteArray, position: Int): Int =
        (data[position].toInt() and 0xff) or (data[position + 1].toInt() shl 8)

    private companion object {
        private const val NULL = -1

        private const val LIMIT = 256
    }
}
