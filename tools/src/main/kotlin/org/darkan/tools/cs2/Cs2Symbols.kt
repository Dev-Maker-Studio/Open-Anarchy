package org.darkan.tools.cs2

import org.darkan.tools.cs2.ir.Cs2Type
import org.darkan.tools.cs2.ir.VarRef
import org.darkan.tools.cs2.ir.VarSpace

/**
 * The default names for the things scripts refer to by id.
 *
 * Variables and scripts are emitted as ordinary identifiers rather than indexed
 * lookups so an editor can rename them. The default name carries the id, which
 * is what lets a file compile on its own; a renamed symbol is resolved through
 * the declarations written alongside the scripts instead.
 */
object Cs2Symbols {

    private val prefixes = mapOf(
        VarSpace.VARP to "varp",
        VarSpace.VARBIT to "varbit",
        VarSpace.VARC to "varc",
        VarSpace.VARC_STRING to "varcstring",
        VarSpace.VARN to "varn",
        VarSpace.VARNBIT to "varnbit",
        VarSpace.CLAN to "clanvar",
        VarSpace.CLAN_SETTING to "clansetting",
    )

    private val spaces = prefixes.entries.associate { (space, prefix) -> prefix to space }

    /** Longest first, so `varcstring12` does not read as `varc` + `string12`. */
    private val orderedPrefixes = prefixes.values.sortedByDescending { it.length }

    private val pattern = Regex("^([a-z]+)(\\d+)(Old)?$")

    fun variableName(value: VarRef): String {
        val prefix = prefixes.getValue(value.space)
        val suffix = if (value.variant == "old") "Old" else ""
        return "$prefix${value.id}$suffix"
    }

    /** The variable an identifier names, or null when it is not one. */
    fun variableOf(name: String): VarRef? {
        val match = pattern.matchEntire(name) ?: return null
        val prefix = orderedPrefixes.firstOrNull { name.startsWith(it) } ?: return null
        val id = name.removePrefix(prefix).removeSuffix("Old").toIntOrNull() ?: return null
        val space = spaces[prefix] ?: return null
        val type = if (space == VarSpace.VARC_STRING) Cs2Type.STRING else Cs2Type.INT
        return VarRef(space, id, type, if (match.groupValues[3].isEmpty()) null else "old")
    }

    fun scriptName(id: Int) = "script$id"

    /** The script an identifier names, or null when it is not one. */
    fun scriptOf(name: String): Int? =
        if (name.startsWith("script")) name.removePrefix("script").toIntOrNull() else null
}
