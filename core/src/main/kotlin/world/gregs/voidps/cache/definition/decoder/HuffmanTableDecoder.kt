package world.gregs.voidps.cache.definition.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.DefinitionDecoder
import world.gregs.voidps.cache.Index.HUFFMAN
import world.gregs.voidps.cache.definition.data.HuffmanTableDefinition

class HuffmanTableDecoder : DefinitionDecoder<HuffmanTableDefinition>(HUFFMAN) {

    override fun create(size: Int) = Array(size) { HuffmanTableDefinition(it) }

    override fun getFile(id: Int) = 0

    override fun getArchive(id: Int) = id

    override fun readLoop(definition: HuffmanTableDefinition, buffer: Reader) {
        definition.read(-1, buffer)
    }

    override fun HuffmanTableDefinition.read(opcode: Int, buffer: Reader) {
        lengths = IntArray(buffer.readableBytes()) { buffer.readUnsignedByte() }
    }
}
