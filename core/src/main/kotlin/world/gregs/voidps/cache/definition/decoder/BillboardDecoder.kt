package world.gregs.voidps.cache.definition.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.DefinitionDecoder
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.BillboardType
import world.gregs.voidps.cache.definition.encoder.BillboardEncoder

class BillboardDecoder : DefinitionDecoder<BillboardType>(Index.BILLBOARDS) {

    private val encoder = BillboardEncoder()

    override fun create(size: Int) = Array(size) { BillboardType(id = it) }

    override fun getArchive(id: Int) = ARCHIVE

    override fun getFile(id: Int) = id

    override fun size(cache: Cache): Int = cache.lastFileId(Index.BILLBOARDS, ARCHIVE)

    override fun canonicalOpcodes(definition: BillboardType): IntArray = encoder.opcodes(definition)

    override fun BillboardType.read(opcode: Int, buffer: Reader) {
        when (opcode) {
            1 -> {
                materialId = buffer.readUnsignedShort()
                if (materialId == NO_MATERIAL) {
                    materialId = -1
                }
            }
            2 -> {
                size2d = buffer.readUnsignedShort() + 1
                size3d = buffer.readUnsignedShort() + 1
            }
            3 -> unknown3 = buffer.readUnsignedByte()
            4 -> shape = buffer.readUnsignedByte()
            5 -> blendType = buffer.readUnsignedByte()
            6 -> stationary = true
            7 -> hasUid = true
            else -> error("Unhandled billboard definition opcode: $opcode for def $id")
        }
    }

    companion object {
        const val ARCHIVE = 0

        const val NO_MATERIAL = 65535
    }
}
