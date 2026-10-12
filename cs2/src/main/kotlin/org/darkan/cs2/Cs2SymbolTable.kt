package org.darkan.cs2

import org.darkan.core.gameval.Gameval
import org.darkan.core.gameval.GamevalCatalog
import org.darkan.cs2.ir.VarRef
import org.darkan.cs2.ir.VarSpace
import java.io.File

class Cs2SymbolTable(
    private val variables: Map<String, VarRef>,
    private val scripts: Map<String, Int>,
    private val gamevals: Map<String, Int>,
    val conflicts: List<Conflict>,
    val renames: List<Rename>,
    val scriptFiles: Map<String, Int>,
    val orphans: List<String>,
) {
    fun variable(name: String): VarRef? = variables[name]

    fun variableBindings(): Map<String, VarRef> = variables

    fun script(name: String): Int? = scripts[name]

    fun gameval(path: String): Int? = gamevals[path]

    fun refusal(): String? {
        if (conflicts.isEmpty()) return null
        val lines = StringBuilder(
            if (conflicts.size == 1) {
                "one name means different things here and in the gameval catalogs, " +
                    "so nothing in this folder can be compiled:"
            } else {
                "${conflicts.size} names mean different things here and in the gameval catalogs, " +
                    "so nothing in this folder can be compiled:"
            },
        )
        for (conflict in conflicts.take(10)) lines.append("\n  ").append(conflict)
        if (conflicts.size > 10) lines.append("\n  ... ${conflicts.size - 10} more")
        lines.append("\nRun `cs2 sync-names <dir>` to write this folder's names into the catalogs, ")
        lines.append("or `cs2 apply-names <dir>` to take the catalogs' names instead.")
        return lines.toString()
    }

    class Conflict(val what: String, val name: String, val projectId: String, val catalogId: String) {
        override fun toString(): String =
            "$what '$name' is $projectId in this folder but $catalogId in gamevals/"
    }

    class Rename(
        val what: String,
        val key: String,
        val catalog: String,
        val project: String,
        val location: String? = null,
    ) {
        override fun toString(): String = "$what $key: $catalog -> $project"
    }

    companion object {
        val EMPTY = Cs2SymbolTable(emptyMap(), emptyMap(), emptyMap(), emptyList(), emptyList(), emptyMap(), emptyList())

        private val SPACES: Map<String, VarSpace> =
            Cs2Names.VAR_PREFIXES.entries.associate { (space, prefix) -> prefix to space } +
                mapOf(
                    "varcstring" to VarSpace.VARC_STRING,
                    "clanvar" to VarSpace.CLAN,
                    "clansetting" to VarSpace.CLAN_SETTING,
                )

        private val DECLARATION = Regex(
            "/\\*\\* (" + SPACES.keys.sortedByDescending { it.length }.joinToString("|") +
                ") (\\d+)(?: (old))? \\*/\\s*\\R\\s*declare (?:let|const) (\\w+)",
        )

        private val FUNCTION = Regex("""^function (\w+)\s*\(""", RegexOption.MULTILINE)

        private val CONSTANT = Regex("""/\*\* (\w+) (\d+(?::\d+)?) \*/ const (\w+):""")

        private val NAMESPACE = Regex("""^\s*(?:declare )?namespace (\w+) \{$""")

        fun read(directory: File): Cs2SymbolTable {
            val conflicts = ArrayList<Conflict>()
            val renames = ArrayList<Rename>()

            val variables = HashMap<String, VarRef>()
            val declarations = directory.resolve(Cs2Declarations.VARS_FILE)
            if (declarations.isFile) {
                for (match in DECLARATION.findAll(declarations.readText())) {
                    val (label, id, variant, name) = match.destructured
                    val space = SPACES[label] ?: continue
                    val reference = VarRef(
                        space, id.toIntOrNull() ?: continue, Cs2Names.variableType(space),
                        if (variant.isEmpty()) null else "old",
                    )
                    variables[name] = reference
                    val derived = Cs2Symbols.variableName(reference)
                    if (derived == name) continue
                    renames.add(Rename(Cs2Names.VAR_PREFIXES.getValue(space), id, derived, name))
                    val clash = Cs2Symbols.variableOf(name)
                    if (clash != null && (clash.space != reference.space || clash.id != reference.id)) {
                        conflicts.add(
                            Conflict(
                                "variable", name, "$label ${reference.id}",
                                "${Cs2Names.VAR_PREFIXES[clash.space]} ${clash.id}",
                            ),
                        )
                    }
                }
            }

            val gamevals = HashMap<String, Int>()
            val constants = directory.resolve(Cs2Declarations.GAMEVALS_FILE)
            if (constants.isFile) readGamevals(constants, gamevals, renames, conflicts)

            val scripts = HashMap<String, Int>()
            val files = HashMap<String, Int>()
            val orphans = ArrayList<String>()
            for (file in Cs2Layout.scriptFiles(directory)) {
                val text = file.readText()
                val relative = Cs2Layout.relativePath(directory, file)
                val id = Cs2Layout.scriptIdOf(text, relative)
                if (id == null) {
                    val claimed = Cs2Layout.gamevalHeader(text)
                    if (claimed != null) orphans.add("${relative ?: file.name}: '$claimed'")
                    continue
                }
                if (relative != null) files[relative] = id
                val name = FUNCTION.find(text)?.groupValues?.get(1) ?: continue
                scripts[name] = id
                val derived = Cs2Names.scriptIdentifier(id)
                if (derived == name) continue
                renames.add(Rename(Gameval.CLIENTSCRIPT, id.toString(), derived, name, relative))
                val clash = Cs2Names.scriptIdOf(name)
                if (clash != null && clash != id) {
                    conflicts.add(Conflict("clientscript", name, id.toString(), clash.toString()))
                }
            }

            return Cs2SymbolTable(variables, scripts, gamevals, conflicts, renames, files, orphans)
        }

        private fun readGamevals(
            file: File,
            into: HashMap<String, Int>,
            renames: MutableList<Rename>,
            conflicts: MutableList<Conflict>,
        ) {
            val componentNamespace = Cs2Names.NAMESPACES.getValue(Gameval.COMPONENT)
            var root: String? = null
            var group: String? = null
            file.forEachLine { line ->
                if (line.isEmpty() || line == "}") {
                    return@forEachLine
                }
                if (line.startsWith("declare namespace ")) {
                    root = NAMESPACE.matchEntire(line)?.groupValues?.get(1)
                    group = null
                    return@forEachLine
                }
                if (line.startsWith("    namespace ")) {
                    group = NAMESPACE.matchEntire(line)?.groupValues?.get(1)
                    return@forEachLine
                }
                val namespace = root ?: return@forEachLine
                val match = CONSTANT.find(line) ?: return@forEachLine
                val (type, key, name) = match.destructured
                val path = if (namespace == componentNamespace) {
                    "$namespace.${group ?: return@forEachLine}.$name"
                } else {
                    "$namespace.$name"
                }
                val id = idOf(type, key) ?: return@forEachLine
                into[path] = id
                val derived = derivedPath(type, key) ?: return@forEachLine
                if (derived == path) return@forEachLine
                renames.add(Rename(type, key, derived, path))
                val clash = catalogId(namespace, group, name)
                if (clash != null && clash != key) {
                    conflicts.add(Conflict(type, path, key, clash))
                }
            }
        }

        private fun idOf(type: String, key: String): Int? {
            val colon = key.indexOf(':')
            if (colon < 0) return key.toIntOrNull()
            val iface = key.substring(0, colon).toIntOrNull() ?: return null
            val component = key.substring(colon + 1).toIntOrNull() ?: return null
            return GamevalCatalog.pack(iface, component)
        }

        private fun derivedPath(type: String, key: String): String? {
            val namespace = Cs2Names.NAMESPACES[type] ?: return null
            if (type != Gameval.COMPONENT) {
                val id = key.toIntOrNull() ?: return null
                return "$namespace.${Cs2Names.identifier(type, id) ?: return null}"
            }
            val packed = idOf(type, key) ?: return null
            val (interfaceName, componentName) = Cs2Names.componentPath(packed) ?: return null
            return "$namespace.$interfaceName.$componentName"
        }

        private fun catalogId(namespace: String, group: String?, name: String): String? {
            val type = Cs2Names.NAMESPACES.entries.firstOrNull { it.value == namespace }?.key ?: return null
            if (type != Gameval.COMPONENT) return Cs2Names.idOf(type, name)?.toString()
            val interfaceName = group ?: return null
            if (name == Cs2Identifiers.IFACE_MEMBER) return null
            val packed = Cs2Names.componentOf(interfaceName, name) ?: return null
            return GamevalCatalog.componentKey(GamevalCatalog.ifaceOf(packed), GamevalCatalog.compOf(packed))
        }
    }
}
