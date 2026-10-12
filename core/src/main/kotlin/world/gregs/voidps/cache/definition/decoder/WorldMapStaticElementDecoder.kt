package world.gregs.voidps.cache.definition.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.DefinitionDecoder
import world.gregs.voidps.cache.Index.WORLD_MAP
import world.gregs.voidps.cache.definition.data.WorldMapStaticElementDefinition

class WorldMapStaticElementDecoder : DefinitionDecoder<WorldMapStaticElementDefinition>(WORLD_MAP) {

    override fun create(size: Int) = Array(size) { WorldMapStaticElementDefinition(it) }

    override fun readLoop(definition: WorldMapStaticElementDefinition, buffer: Reader) {
        definition.read(-1, buffer)
    }

    override fun WorldMapStaticElementDefinition.read(opcode: Int, buffer: Reader) {
        position = buffer.readInt()
        mapElement = buffer.readUnsignedShort()
        members = buffer.readUnsignedByte()
        check(buffer.readableBytes() == 0) {
            "World map static element $id has ${buffer.readableBytes()} bytes past its seven."
        }
    }
}
