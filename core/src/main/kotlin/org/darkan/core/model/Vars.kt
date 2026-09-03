package org.darkan.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.jvm.Transient as JvmTransient
import org.darkan.core.net.Session
import org.darkan.core.net.prot.packet.ClearVarps
import org.darkan.core.net.prot.packet.setVarcPacket
import org.darkan.core.net.prot.packet.setVarcStrPacket
import org.darkan.core.net.prot.packet.setVarpPacket
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Config
import world.gregs.voidps.cache.Index

@Serializable
class Vars(val vars: MutableMap<Int, Int> = HashMap()) {
    companion object {
        val BIT_MASKS = IntArray(32).apply {
            var value = 2
            for (i in 0 until 32) {
                this[i] = value - 1
                value *= 2
            }
        }
    }

    @Transient
    @JvmTransient
    private var modified: MutableSet<Int> = HashSet()
    @Transient
    @JvmTransient
    private lateinit var varpValues: IntArray
    @Transient
    @JvmTransient
    private lateinit var session: Session

    fun setSession(session: Session): Vars {
        this.session = session
        varpValues = IntArray(Cache.get().lastFileId(Index.CONFIGS, Config.VARP) + 1)
        modified = HashSet()
        vars.forEach { (varId, value) -> setVar(varId, value) }
        return this
    }

    /**
     * Initialize vars without a session for offline/tool usage.
     * This allows using getVar/setVar/getVarBit/setVarBit without network sync.
     */
    fun initWithoutSession(): Vars {
        varpValues = IntArray(Cache.get().lastFileId(Index.CONFIGS, Config.VARP) + 1)
        modified = HashSet()
        vars.forEach { (varId, value) -> setVar(varId, value) }
        return this
    }

    @JvmOverloads
    fun setVar(id: Int, value: Int, forceSend: Boolean = false, save: Boolean = false) {
        if (forceSend)
            modified.add(id)
        if (id < 0 || id >= varpValues.size || varpValues[id] == value)
            return
        varpValues[id] = value
        if (save)
            vars[id] = value
        modified.add(id)
    }

    fun saveVar(id: Int, value: Int) = setVar(id, value, forceSend = false, save = true)

    @JvmOverloads
    fun setVarBit(id: Int, value: Int, forceSend: Boolean = false, save: Boolean = false) {
        val defs = Cache.varbits[id]
        val bitLength = defs.endBit - defs.startBit
        val mask = BIT_MASKS[bitLength]

        val cappedValue = value.coerceIn(0, mask)
        val shiftedMask = mask shl defs.startBit
        val varpValue = (varpValues[defs.index] and shiftedMask.inv()) or ((cappedValue shl defs.startBit) and shiftedMask)

        if (varpValue != varpValues[defs.index])
            setVar(defs.index, varpValue, forceSend, save)
    }

    fun saveVarBit(id: Int, value: Int) = setVarBit(id, value, forceSend = false, save = true)

    fun getVar(id: Int) = varpValues[id]

    /**
     * Reads a varbit, treating anything the cache cannot resolve as unset (0).
     *
     * This used to index straight through and throw. The cache genuinely contains
     * dangling references: 40 objects name a varbit past the end of the varbit table
     * (the table holds 2697 entries; object transforms reference ids as high as 2703).
     * Since object transforms are resolved during region loading, one of those objects
     * coming into view took the whole load path down with an
     * `ArrayIndexOutOfBoundsException` -- a hard crash caused purely by data, on a path
     * with no way to recover.
     *
     * 0 is the right answer rather than merely a safe one: it is what an unset var
     * reads as, so an unresolvable varbit selects the transform's default.
     */
    fun getVarBit(id: Int): Int {
        val varbits = Cache.varbits
        if (id < 0 || id >= varbits.size) return 0
        val defs = varbits[id] ?: return 0
        if (defs.index < 0 || defs.index >= varpValues.size) return 0
        val bitLength = defs.endBit - defs.startBit
        if (bitLength < 0 || bitLength >= BIT_MASKS.size) return 0
        return (varpValues[defs.index] shr defs.startBit) and BIT_MASKS[bitLength]
    }

    fun bitFlagged(id: Int, bit: Int) = (varpValues[id] and (1 shl bit)) != 0

    fun syncVarsToClient() {
        modified.forEach { id ->
            if (id < 0 || id >= varpValues.size) return@forEach
            session.queuePacket(setVarpPacket(id, varpValues[id]))
        }
        modified.clear()
    }

    suspend fun clearVars() {
        varpValues.fill(0)
        session.send(ClearVarps())
    }

    suspend fun setVarc(id: Int, value: Int) = session.send(setVarcPacket(id, value))
    suspend fun setVarcStr(id: Int, value: String) = session.send(setVarcStrPacket(id, value))
}
