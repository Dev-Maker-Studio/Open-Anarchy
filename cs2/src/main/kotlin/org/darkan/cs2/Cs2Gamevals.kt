package org.darkan.cs2

import org.darkan.core.gameval.Gameval
import org.darkan.core.gameval.GamevalCatalog
import org.darkan.cs2.ir.Cs2Type
import org.darkan.cs2.ir.VarRef
import org.darkan.cs2.ir.VarSpace

object Cs2Identifiers {

    private val JAVA_RESERVED = """
        abstract assert boolean break byte case catch char class const continue default do double else
        enum extends final finally float for goto if implements import instanceof int interface long
        native new package private protected public return short static strictfp super switch
        synchronized this throw throws transient try void volatile while true false null _
    """.trimIndent().split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()

    private val KOTLIN_RESERVED = """
        as break class continue do else false for fun if in interface is null object package return
        super this throw true try typealias typeof val var when while
    """.trimIndent().split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()

    private val TYPESCRIPT_RESERVED = """
        let function with yield await export debugger delete arguments eval undefined NaN Infinity
        globalThis Function Object Number String Boolean Array Symbol BigInt declare namespace module
        require async constructor
    """.trimIndent().split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()

    val RESERVED_WORDS: Set<String> = JAVA_RESERVED + KOTLIN_RESERVED + TYPESCRIPT_RESERVED

    const val IFACE_MEMBER = "IFACE"

    private val ILLEGAL = Regex("[^A-Za-z0-9_]")

    fun escape(raw: String, key: String): String {
        var ident = ILLEGAL.replace(raw, "_")
        if (ident.isEmpty() || ident[0] in '0'..'9') ident = "_$ident"
        if (ident in RESERVED_WORDS) ident += "_"
        if (ident == IFACE_MEMBER) ident += "_"
        if (ident.all { it == '_' }) ident = "_" + key.replace(':', '_')
        return ident
    }
}

object Cs2Names {

    val NAMESPACES: Map<String, String> = linkedMapOf(
        Gameval.OBJ to "Obj",
        Gameval.NPC to "Npc",
        Gameval.LOC to "Loc",
        Gameval.SEQ to "Seq",
        Gameval.SPOTANIM to "Spotanim",
        Gameval.ENUM to "Enum",
        Gameval.STRUCT to "Struct",
        Gameval.PARAM to "Param",
        Gameval.INV to "Inv",
        Gameval.GRAPHIC to "Graphic",
        Gameval.INTERFACE to "Interface",
        Gameval.COMPONENT to "Component",
        Gameval.SOUND to "Sound",
        Gameval.MIDI to "Midi",
        Gameval.CURSOR to "Cursor",
        Gameval.MAPELEMENT to "Mapelement",
        Gameval.QUEST to "Quest",
        Gameval.WORLDMAP to "Worldmap",
        Gameval.MODEL to "Model",
    )

    val SIMPLE_TYPES: List<String> = NAMESPACES.keys.filter { it != Gameval.COMPONENT }

    val WRAPPED: Map<ArgType, String> = linkedMapOf(
        ArgType.COLOUR to "colour",
        ArgType.COORD to "coord",
    )

    fun helperFor(kind: ArgType): String? = WRAPPED[kind]

    private val helperKinds: Map<String, ArgType> = linkedMapOf(
        "colour" to ArgType.COLOUR,
        "coord" to ArgType.COORD,
        "tile" to ArgType.COORD,
        "component" to ArgType.COMPONENT,
        "obj" to ArgType.OBJ,
        "item" to ArgType.OBJ,
        "npc" to ArgType.NPC,
        "loc" to ArgType.LOC,
        "seq" to ArgType.SEQ,
        "spotanim" to ArgType.SPOTANIM,
        "enumId" to ArgType.ENUM,
        "struct" to ArgType.STRUCT,
        "param" to ArgType.PARAM,
        "inv" to ArgType.INV,
        "stat" to ArgType.STAT,
        "skill" to ArgType.STAT,
        "scriptId" to ArgType.SCRIPT,
        "graphic" to ArgType.GRAPHIC,
        "interfaceId" to ArgType.INTERFACE,
        "sound" to ArgType.SOUND,
        "midi" to ArgType.MIDI,
        "cursor" to ArgType.CURSOR,
        "mapelement" to ArgType.MAPELEMENT,
        "quest" to ArgType.QUEST,
        "worldmap" to ArgType.WORLDMAP,
        "model" to ArgType.MODEL,
    )

    val ALIASES: Map<ArgType, String> = linkedMapOf(
        ArgType.OBJ to "obj",
        ArgType.NPC to "npc",
        ArgType.LOC to "loc",
        ArgType.SEQ to "seq",
        ArgType.SPOTANIM to "spotanim",
        ArgType.ENUM to "Enum",
        ArgType.STRUCT to "struct",
        ArgType.PARAM to "param",
        ArgType.INV to "inv",
        ArgType.GRAPHIC to "graphic",
        ArgType.INTERFACE to "Interface",
        ArgType.COMPONENT to "component",
        ArgType.SOUND to "sound",
        ArgType.MIDI to "midi",
        ArgType.CURSOR to "cursor",
        ArgType.MAPELEMENT to "mapelement",
        ArgType.QUEST to "quest",
        ArgType.WORLDMAP to "worldmap",
        ArgType.MODEL to "model",
        ArgType.COORD to "coord",
        ArgType.STAT to "stat",
        ArgType.COLOUR to "colour",
        ArgType.BOOLEAN to "boolean",
    )

    fun aliasFor(kind: ArgType): String? = ALIASES[kind]

    private val aliasKinds: Map<String, ArgType> =
        ALIASES.entries.associate { (kind, alias) -> alias to kind } + mapOf(
            "item" to ArgType.OBJ,
            "tile" to ArgType.COORD,
            "skill" to ArgType.STAT,
            "flag" to ArgType.BOOLEAN,
            "enumId" to ArgType.ENUM,
            "interfaceId" to ArgType.INTERFACE,
        )

    fun isAlias(name: String): Boolean = name in aliasKinds

    fun argTypeOf(helper: String): ArgType? = helperKinds[helper]

    val NAMESPACE_KINDS: Map<String, ArgType> = linkedMapOf(
        "Obj" to ArgType.OBJ,
        "Npc" to ArgType.NPC,
        "Loc" to ArgType.LOC,
        "Seq" to ArgType.SEQ,
        "Spotanim" to ArgType.SPOTANIM,
        "Enum" to ArgType.ENUM,
        "Struct" to ArgType.STRUCT,
        "Param" to ArgType.PARAM,
        "Inv" to ArgType.INV,
        "Graphic" to ArgType.GRAPHIC,
        "Interface" to ArgType.INTERFACE,
        "Component" to ArgType.COMPONENT,
        "Sound" to ArgType.SOUND,
        "Midi" to ArgType.MIDI,
        "Cursor" to ArgType.CURSOR,
        "Mapelement" to ArgType.MAPELEMENT,
        "Quest" to ArgType.QUEST,
        "Worldmap" to ArgType.WORLDMAP,
        "Model" to ArgType.MODEL,
    )

    fun catalogType(kind: ArgType): String? = when (kind) {
        ArgType.OBJ -> Gameval.OBJ
        ArgType.NPC -> Gameval.NPC
        ArgType.LOC -> Gameval.LOC
        ArgType.SEQ -> Gameval.SEQ
        ArgType.SPOTANIM -> Gameval.SPOTANIM
        ArgType.ENUM -> Gameval.ENUM
        ArgType.STRUCT -> Gameval.STRUCT
        ArgType.PARAM -> Gameval.PARAM
        ArgType.INV -> Gameval.INV
        ArgType.GRAPHIC -> Gameval.GRAPHIC
        ArgType.INTERFACE -> Gameval.INTERFACE
        ArgType.COMPONENT -> Gameval.COMPONENT
        ArgType.SOUND -> Gameval.SOUND
        ArgType.MIDI -> Gameval.MIDI
        ArgType.CURSOR -> Gameval.CURSOR
        ArgType.MAPELEMENT -> Gameval.MAPELEMENT
        ArgType.QUEST -> Gameval.QUEST
        ArgType.WORLDMAP -> Gameval.WORLDMAP
        ArgType.MODEL -> Gameval.MODEL
        ArgType.INT, ArgType.COORD, ArgType.STAT, ArgType.SCRIPT,
        ArgType.BOOLEAN, ArgType.COLOUR, ArgType.TYPECHAR,
        -> null
    }

    fun catalogType(space: VarSpace): String? = when (space) {
        VarSpace.VARP -> Gameval.VARP
        VarSpace.VARBIT -> Gameval.VARBIT
        VarSpace.VARC -> Gameval.VARC
        VarSpace.VARC_STRING -> Gameval.VARCSTR
        VarSpace.CLAN -> Gameval.VARCLAN
        VarSpace.CLAN_SETTING -> Gameval.VARCLANSETTING
        VarSpace.VARN, VarSpace.VARNBIT -> null
    }

    val VAR_PREFIXES: Map<VarSpace, String> = linkedMapOf(
        VarSpace.VARP to Gameval.VARP,
        VarSpace.VARBIT to Gameval.VARBIT,
        VarSpace.VARC to Gameval.VARC,
        VarSpace.VARC_STRING to Gameval.VARCSTR,
        VarSpace.VARN to "varn",
        VarSpace.VARNBIT to "varnbit",
        VarSpace.CLAN to Gameval.VARCLAN,
        VarSpace.CLAN_SETTING to Gameval.VARCLANSETTING,
    )


    private class Derived {
        val byType = HashMap<String, Map<Int, String>>()
        val reverse = HashMap<String, Map<String, Int>>()

        var componentsBuilt = false
        var interfaces: List<ComponentInterface> = emptyList()
        var interfacesByName: Map<String, ComponentInterface> = emptyMap()
        var componentPaths: Map<Int, Pair<String, String>> = emptyMap()

        var scriptsBuilt = false
        var scripts: Map<Int, String> = emptyMap()
        var scriptsByName: Map<String, Int> = emptyMap()

        var rawScriptsBuilt = false
        var scriptsByRawName: Map<String, Int> = emptyMap()

        var layoutBuilt = false
        var paths: Map<Int, String> = emptyMap()
        var pathIds: Map<String, Int> = emptyMap()

        val vars = HashMap<VarSpace, Map<Int, String>>()
        val varsByName = HashMap<VarSpace, Map<String, Int>>()

        val warnings = ArrayList<String>()
    }

    class ComponentInterface(
        val interfaceId: Int,
        val identifier: String,
        val rawName: String,
        val components: List<Component>,
    )

    class Component(val componentId: Int, val identifier: String, val rawName: String)

    @Volatile
    private var derived: Derived = Derived()

    private fun state(): Derived = derived

    fun reload() {
        Gameval.reload()
        derived = Derived()
    }

    val warnings: List<String> get() = synchronized(state()) { ArrayList(state().warnings) }


    fun identifier(type: String, id: Int): String? = identifiers(type)[id]

    fun idOf(type: String, identifier: String): Int? {
        val current = state()
        identifiers(type)
        return synchronized(current) { current.reverse[type]?.get(identifier) }
    }

    fun identifiers(type: String): Map<Int, String> {
        val current = state()
        synchronized(current) {
            current.byType[type]?.let { return it }
            val entries = Gameval.allEntries(type).entries.sortedBy { it.key }
            val out = LinkedHashMap<Int, String>(entries.size * 2)
            val taken = HashMap<String, Int>(entries.size * 2)
            for ((id, name) in entries) {
                out[id] = unique(taken, Cs2Identifiers.escape(name, id.toString()), id, "$type $id", current)
            }
            current.byType[type] = out
            current.reverse[type] = taken
            return out
        }
    }

    private fun unique(taken: HashMap<String, Int>, ident: String, id: Int, what: String, into: Derived): String {
        if (taken.putIfAbsent(ident, id) == null) return ident
        var candidate = "${ident}_$id"
        var n = 2
        while (taken.putIfAbsent(candidate, id) != null) {
            candidate = "${ident}_${id}_$n"
            n++
        }
        into.warnings.add("$what: '$ident' is already taken, using '$candidate'")
        return candidate
    }


    fun componentInterfaces(): List<ComponentInterface> {
        components()
        return state().interfaces
    }

    fun componentPath(packed: Int): Pair<String, String>? {
        components()
        return state().componentPaths[packed]
    }

    fun componentOf(interfaceName: String, componentName: String): Int? {
        components()
        val group = state().interfacesByName[interfaceName] ?: return null
        val component = group.components.firstOrNull { it.identifier == componentName } ?: return null
        return GamevalCatalog.pack(group.interfaceId, component.componentId)
    }

    fun componentInterfaceId(interfaceName: String): Int? {
        components()
        return state().interfacesByName[interfaceName]?.interfaceId
    }

    private fun components() {
        val current = state()
        synchronized(current) {
            if (current.componentsBuilt) return
            current.componentsBuilt = true
            val byInterface = HashMap<Int, MutableList<Triple<Int, String, String?>>>()
            for ((key, name) in Gameval.rawEntries(Gameval.COMPONENT)) {
                val colon = key.indexOf(':')
                if (colon < 0) continue
                val interfaceId = key.substring(0, colon).toIntOrNull() ?: continue
                val componentId = key.substring(colon + 1).toIntOrNull() ?: continue
                val split = name.indexOf(':')
                val interfaceName = if (split < 0) null else name.substring(0, split)
                val componentName = if (split < 0) name else name.substring(split + 1)
                byInterface.getOrPut(interfaceId) { ArrayList() }
                    .add(Triple(componentId, componentName, interfaceName))
            }

            val groups = ArrayList<ComponentInterface>(byInterface.size)
            val takenInterfaces = HashMap<String, Int>(byInterface.size * 2)
            val paths = HashMap<Int, Pair<String, String>>(byInterface.size * 4)
            for (interfaceId in byInterface.keys.sorted()) {
                val raw = byInterface.getValue(interfaceId).firstNotNullOfOrNull { it.third }
                    ?: GamevalCatalog.placeholder(Gameval.INTERFACE, interfaceId.toString())
                val ident = unique(
                    takenInterfaces, Cs2Identifiers.escape(raw, interfaceId.toString()),
                    interfaceId, "component interface $interfaceId", current,
                )
                val taken = HashMap<String, Int>()
                taken[Cs2Identifiers.IFACE_MEMBER] = interfaceId
                val members = ArrayList<Component>()
                for ((componentId, componentName, _) in byInterface.getValue(interfaceId).sortedBy { it.first }) {
                    val key = "$interfaceId:$componentId"
                    val member = unique(
                        taken, Cs2Identifiers.escape(componentName, key),
                        componentId, "component $key", current,
                    )
                    members.add(Component(componentId, member, componentName))
                    paths[GamevalCatalog.pack(interfaceId, componentId)] = ident to member
                }
                groups.add(ComponentInterface(interfaceId, ident, raw, members))
            }
            current.interfaces = groups
            current.interfacesByName = groups.associateBy { it.identifier }
            current.componentPaths = paths
        }
    }


    private val reservedGlobals: Set<String> by lazy {
        val out = HashSet<String>()
        Cs2Op.entries.mapTo(out) { it.tsName }
        out.addAll(Cs2Declarations.HELPERS)
        out.addAll(NAMESPACES.values)
        out.addAll(Cs2Identifiers.RESERVED_WORDS)
        out
    }

    private val LOCAL_NAME = Regex("^(?:(?:int|str|long)(?:Arg)?\\d+|array\\d+|tmp\\w*|L\\d+)$")

    private val LEGACY_NAME =
        Regex("^(?:script|varp|varbit|varcstring|varcstr|varc|varnbit|varn|clanvar|clansetting|varclansetting|varclan)\\d+(?:Old)?$")

    private val VAR_PREFIX =
        Regex("^(?:varp|varbit|varcstring|varcstr|varc|varnbit|varn|clanvar|clansetting|varclansetting|varclan)_")

    private fun shadowsGlobal(name: String): Boolean =
        name in reservedGlobals || LOCAL_NAME.matches(name) || LEGACY_NAME.matches(name)

    fun scriptIdentifier(id: Int): String = scriptIdentifiers()[id]
        ?: GamevalCatalog.placeholder(Gameval.CLIENTSCRIPT, id.toString())

    fun scriptIdOf(identifier: String): Int? {
        scriptIdentifiers()
        return state().scriptsByName[identifier]
    }

    fun scriptIdOfRawName(raw: String): Int? {
        rawScripts()
        return state().scriptsByRawName[raw]
    }

    private fun rawScripts(): Map<String, Int> {
        val current = state()
        synchronized(current) {
            if (current.rawScriptsBuilt) return current.scriptsByRawName
            current.rawScriptsBuilt = true
            val all = Gameval.allEntries(Gameval.CLIENTSCRIPT)
            val out = HashMap<String, Int>(all.size * 2)
            for ((id, raw) in all.entries.sortedBy { it.key }) out.putIfAbsent(raw, id)
            current.scriptsByRawName = out
            return out
        }
    }

    fun scriptIdentifiers(): Map<Int, String> {
        val current = state()
        synchronized(current) {
            if (current.scriptsBuilt) return current.scripts
            current.scriptsBuilt = true
            val all = Gameval.allEntries(Gameval.CLIENTSCRIPT).entries.sortedBy { it.key }
            val categories = HashMap<String, MutableSet<String>>()
            for ((id, name) in all) {
                if (Gameval.name(Gameval.CLIENTSCRIPT, id) == null) continue
                val match = CLIENTSCRIPT_NAME.matchEntire(name) ?: continue
                categories.getOrPut(match.groupValues[2]) { LinkedHashSet() }.add(match.groupValues[1])
            }
            val out = LinkedHashMap<Int, String>(all.size * 2)
            val taken = HashMap<String, Int>(all.size * 2)
            for ((id, raw) in all) {
                val named = Gameval.name(Gameval.CLIENTSCRIPT, id) != null
                val match = if (named) CLIENTSCRIPT_NAME.matchEntire(raw) else null
                val source = when {
                    match == null -> raw
                    match.groupValues[1].isEmpty() -> match.groupValues[2]
                    categories[match.groupValues[2]]?.size == 1 -> match.groupValues[2]
                    else -> "${match.groupValues[1]}_${match.groupValues[2]}"
                }
                var ident = Cs2Identifiers.escape(source, id.toString())
                if (shadowsGlobal(ident) || VAR_PREFIX.containsMatchIn(ident)) {
                    current.warnings.add("clientscript $id: '$ident' already names something else, using '${ident}_$id'")
                    ident = "${ident}_$id"
                }
                out[id] = unique(taken, ident, id, "clientscript $id", current)
            }
            current.scripts = out
            current.scriptsByName = taken
            return out
        }
    }

    private val CLIENTSCRIPT_NAME = Regex("\\[([^,\\]]*),([^\\]]*)\\]")


    fun scriptPaths(): Map<Int, String> {
        layout()
        return state().paths
    }

    fun scriptIdOfPath(relative: String): Int? {
        layout()
        return state().pathIds[relative.replace('\\', '/')]
    }

    private fun layout() {
        val current = state()
        synchronized(current) {
            if (current.layoutBuilt) return
            current.layoutBuilt = true
            val paths = LinkedHashMap<Int, String>(Gameval.allEntries(Gameval.CLIENTSCRIPT).size * 2)
            val ids = HashMap<String, Int>(paths.size * 2)
            for ((id, raw) in Gameval.allEntries(Gameval.CLIENTSCRIPT).entries.sortedBy { it.key }) {
                val match = CLIENTSCRIPT_NAME.matchEntire(raw)
                val category = match?.groupValues?.get(1)?.takeIf { it.isNotEmpty() } ?: UNCATEGORISED
                val bare = match?.groupValues?.get(2) ?: raw
                var path = "${fileName(category, id)}/${fileName(bare, id)}.ts"
                if (ids.containsKey(path)) {
                    current.warnings.add("clientscript $id: '$path' is already taken, using an id suffix")
                    path = "${fileName(category, id)}/${fileName(bare, id)}_$id.ts"
                }
                paths[id] = path
                ids[path] = id
            }
            current.paths = paths
            current.pathIds = ids
        }
    }

    val UNCATEGORISED: String = GamevalCatalog.placeholderPrefix(Gameval.CLIENTSCRIPT)

    private fun fileName(raw: String, id: Int): String = Cs2Identifiers.escape(raw, id.toString())


    fun variableIdentifier(reference: VarRef): String {
        val prefix = VAR_PREFIXES.getValue(reference.space)
        val suffix = if (reference.variant == "old") "Old" else ""
        val name = variables(reference.space)[reference.id] ?: reference.id.toString()
        return "${prefix}_$name$suffix"
    }

    fun variableOf(identifier: String): VarRef? {
        for ((space, prefix) in VAR_PREFIXES) {
            if (!identifier.startsWith("${prefix}_")) continue
            val body = identifier.substring(prefix.length + 1)
            val old = body.endsWith("Old")
            val name = if (old) body.dropLast(3) else body
            val variant = if (old) "old" else null
            val id = name.toIntOrNull() ?: namedVariables(space)[name] ?: continue
            return VarRef(space, id, variableType(space), variant)
        }
        return null
    }

    fun variables(space: VarSpace): Map<Int, String> {
        val type = catalogType(space) ?: return emptyMap()
        val current = state()
        synchronized(current) {
            current.vars[space]?.let { return it }
            val prefix = VAR_PREFIXES.getValue(space)
            val entries = Gameval.entries(type).entries.sortedBy { it.key }
            val out = LinkedHashMap<Int, String>(entries.size * 2)
            val taken = HashMap<String, Int>(entries.size * 2)
            for ((id, name) in entries) {
                var ident = Cs2Identifiers.escape(name, id.toString())
                if (shadowsGlobal("${prefix}_$ident")) {
                    current.warnings.add("$type $id: '${prefix}_$ident' already names something else, using '${prefix}_${ident}_$id'")
                    ident = "${ident}_$id"
                }
                out[id] = unique(taken, ident, id, "$type $id", current)
            }
            current.vars[space] = out
            current.varsByName[space] = taken
            return out
        }
    }

    private fun namedVariables(space: VarSpace): Map<String, Int> {
        variables(space)
        return state().varsByName[space].orEmpty()
    }

    fun variableType(space: VarSpace): Cs2Type =
        if (space == VarSpace.VARC_STRING) Cs2Type.STRING else Cs2Type.INT
}
