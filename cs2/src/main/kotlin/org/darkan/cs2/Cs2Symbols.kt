package org.darkan.cs2

import org.darkan.cs2.ir.Cs2Type
import org.darkan.cs2.ir.VarRef
import org.darkan.cs2.ir.VarSpace

object Cs2Symbols {

    private val legacyPrefixes = mapOf(
        "varp" to VarSpace.VARP,
        "varbit" to VarSpace.VARBIT,
        "varc" to VarSpace.VARC,
        "varcstring" to VarSpace.VARC_STRING,
        "varn" to VarSpace.VARN,
        "varnbit" to VarSpace.VARNBIT,
        "clanvar" to VarSpace.CLAN,
        "clansetting" to VarSpace.CLAN_SETTING,
    )

    private val orderedPrefixes = legacyPrefixes.keys.sortedByDescending { it.length }

    private val numbered = Regex("^([a-z]+)(\\d+)(Old)?$")

    fun variableName(value: VarRef): String = Cs2Names.variableIdentifier(value)

    fun variableOf(name: String): VarRef? = Cs2Names.variableOf(name) ?: numberedVariable(name)

    private fun numberedVariable(name: String): VarRef? {
        val match = numbered.matchEntire(name) ?: return null
        val prefix = orderedPrefixes.firstOrNull { name.startsWith(it) } ?: return null
        val id = name.removePrefix(prefix).removeSuffix("Old").toIntOrNull() ?: return null
        val space = legacyPrefixes[prefix] ?: return null
        val type = if (space == VarSpace.VARC_STRING) Cs2Type.STRING else Cs2Type.INT
        return VarRef(space, id, type, if (match.groupValues[3].isEmpty()) null else "old")
    }

    fun scriptName(id: Int): String = Cs2Names.scriptIdentifier(id)

    fun scriptOf(name: String): Int? = numberedScript(name) ?: Cs2Names.scriptIdOf(name)

    private fun numberedScript(name: String): Int? =
        if (name.startsWith("script")) name.removePrefix("script").toIntOrNull() else null
}
