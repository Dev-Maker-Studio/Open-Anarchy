package world.gregs.voidps.cache.definition

import world.gregs.voidps.buffer.read.Reader

interface Transforms {
    var varbit: Int
    var varp: Int
    var transforms: IntArray?

    fun readTransforms(buffer: Reader, isLast: Boolean) {
        varbit = buffer.readUnsignedShort()
        if (varbit == 65535) {
            varbit = -1
        }
        varp = buffer.readUnsignedShort()
        if (varp == 65535) {
            varp = -1
        }
        var last = -1
        if (isLast) {
            // Legacy uses readBigSmart() for the "last" object ID
            last = buffer.readBigSmart()
        }
        // Legacy: newProtocol (>= 910) uses readSmart(), old uses readUnsignedByte()
        // Revision 727 < 910, so use readUnsignedByte()
        val length = buffer.readUnsignedByte()
        transforms = IntArray(length + 2)
        for (count in 0..length) {
            // Legacy uses readBigSmart() for transform IDs
            transforms!![count] = buffer.readBigSmart()
        }
        transforms!![length + 1] = last
    }
}
