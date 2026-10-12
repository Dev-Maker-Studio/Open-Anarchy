package world.gregs.voidps.cache.definition

import org.darkan.core.model.Vars
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
            last = buffer.readBigSmart()
        }
        val length = buffer.readUnsignedByte()
        transforms = IntArray(length + 2)
        for (count in 0..length) {
            transforms!![count] = buffer.readBigSmart()
        }
        transforms!![length + 1] = last
    }

    fun getIdForVars(defaultId: Int, vars: Vars?): Int {
        val transforms = transforms
        if (transforms == null || transforms.isEmpty()) {
            return defaultId
        }
        val fallback = transforms[transforms.size - 1]
        if (vars == null) {
            return fallback
        }
        var index = -1
        if (varbit != -1) {
            index = vars.getVarBit(varbit)
        } else if (varp != -1) {
            index = vars.getVar(varp)
        }
        if (index >= 0 && index < transforms.size - 1 && transforms[index] != -1) {
            return transforms[index]
        }
        return fallback
    }
}
