package world.gregs.voidps.cache.definition.data

import world.gregs.voidps.cache.Definition
import world.gregs.voidps.cache.definition.OpcodeOrdered

data class BillboardType(
    override var id: Int = -1,
    var materialId: Int = -1,
    var size2d: Int = 64,
    var size3d: Int = 64,
    var shape: Int = 2,
    var blendType: Int = 1,
    var stationary: Boolean = false,
    var hasUid: Boolean = false,
    var unknown3: Int = -1
) : Definition, OpcodeOrdered {

    override var opcodeOrder: IntArray? = null

    override var shadowedPayloads: Array<ByteArray?>? = null
}
