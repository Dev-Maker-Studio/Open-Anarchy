package world.gregs.voidps.cache.definition.decoder

import world.gregs.voidps.buffer.read.Reader
import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Config.STRUCTS
import world.gregs.voidps.cache.DefinitionDecoder
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.ParamRecord
import world.gregs.voidps.cache.definition.data.StructDefinition
import world.gregs.voidps.cache.definition.encoder.StructEncoder

/**
 * Decodes struct definitions from the CONFIGS index (2), archive 26.
 *
 * Structs are simple key-value containers (opcode 249 params only).
 * Each struct is a single file within archive 26.
 */
class StructDecoder : DefinitionDecoder<StructDefinition>(Index.CONFIGS) {

    private val encoder = StructEncoder()

    override fun create(size: Int) = Array(size) { StructDefinition(it) }

    override fun getArchive(id: Int) = STRUCTS

    override fun size(cache: Cache): Int {
        return cache.lastFileId(Index.CONFIGS, STRUCTS)
    }

    override fun StructDefinition.read(opcode: Int, buffer: Reader) {
        if (opcode != 249) {
            error("Unhandled struct definition opcode: $opcode for def $id")
        }
        val length = buffer.readUnsignedByte()
        if (length == 0) {
            return
        }
        val records = ArrayList<ParamRecord>(length)
        for (index in 0 until length) {
            val string = buffer.readUnsignedBoolean()
            val key = buffer.readUnsignedMedium()
            records.add(ParamRecord(key, if (string) buffer.readString() else buffer.readInt()))
        }
        val values = Int2ObjectArrayMap<Any>(records.size)
        for (record in records) {
            values.put(record.key, record.value)
        }
        params = values
        // 37 shipped structs carry the same key twice - the client's map drops one too - so the
        // records are kept whole whenever the map is not enough to write the file back.
        paramRecords = if (values.size == records.size) null else records
    }

    override fun changeValues(definitions: Array<StructDefinition>, definition: StructDefinition) {
        encoder.normalise(definition)
    }
}
