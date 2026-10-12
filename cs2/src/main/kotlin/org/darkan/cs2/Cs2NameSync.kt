package org.darkan.cs2

import org.darkan.core.gameval.Gameval
import org.darkan.core.gameval.GamevalCatalog
import org.darkan.cs2.compile.Cs2Lexer
import org.darkan.cs2.compile.Token
import org.darkan.cs2.compile.TokenKind
import java.io.File

object Cs2NameSync {

    class Change(val what: String, val key: String, val from: String, val to: String) {
        override fun toString(): String = "$what $key: $from -> $to"
    }

    class ApplyReport(
        val declarations: List<String>,
        val changes: List<Change>,
        val filesRewritten: Int,
        val filesMoved: Int,
        val filesScanned: Int,
        val problems: List<String>,
    ) {
        val ok: Boolean get() = problems.isEmpty()
    }

    class SyncReport(
        val catalogs: Map<String, Int>,
        val changes: List<Change>,
        val refusals: List<String>,
        val applied: ApplyReport?,
    ) {
        val ok: Boolean get() = refusals.isEmpty()
    }


    fun apply(folder: File): ApplyReport {
        val problems = ArrayList<String>()
        if (!folder.isDirectory) {
            return ApplyReport(emptyList(), emptyList(), 0, 0, 0, listOf("no such folder: ${folder.absolutePath}"))
        }
        val symbols = Cs2SymbolTable.read(folder)
        return apply(folder, symbols, symbols.scriptFiles)
    }

    private fun apply(folder: File, symbols: Cs2SymbolTable, bindings: Map<String, Int>): ApplyReport {
        val problems = ArrayList<String>()
        val files = Cs2Layout.scriptFiles(folder)
        val resolved = resolve(folder, files, bindings, problems)
        val bound = symbols.renames.filter { it.what != Gameval.CLIENTSCRIPT } + scriptRenames(resolved)
        val renames = renamesFrom(bound)
        val changes = bound.map { Change(it.what, it.key, it.project, it.catalog) }

        val declarations = ArrayList<String>()
        folder.resolve(Cs2Declarations.OPCODES_FILE).writeText(Cs2Declarations.generate())
        declarations.add(Cs2Declarations.OPCODES_FILE)
        folder.resolve(Cs2Declarations.GAMEVALS_FILE).writeText(Cs2Declarations.gamevals())
        declarations.add(Cs2Declarations.GAMEVALS_FILE)
        folder.resolve("tsconfig.json").writeText(Cs2Declarations.tsconfig())
        declarations.add("tsconfig.json")

        val variables = symbols.variableBindings().values.distinct()
        if (variables.isNotEmpty()) {
            folder.resolve(Cs2Declarations.VARS_FILE).writeText(Cs2Declarations.variables(variables))
            declarations.add(Cs2Declarations.VARS_FILE)
        }

        var rewritten = 0
        var moved = 0
        for (file in files) {
            val id = resolved[file] ?: continue
            val source = try {
                file.readText()
            } catch (e: Exception) {
                problems.add("cannot read ${Cs2Layout.relativePath(folder, file) ?: file.name}: ${e.message}")
                continue
            }
            val updated = header(rewrite(source, renames), id)
            if (updated != source) {
                file.writeText(updated)
                rewritten++
            }
            if (Cs2Layout.move(file, Cs2Layout.fileFor(folder, id))) moved++
        }
        return ApplyReport(declarations, changes, rewritten, moved, files.size, problems)
    }

    private fun scriptRenames(resolved: Map<File, Int>): List<Cs2SymbolTable.Rename> {
        val out = ArrayList<Cs2SymbolTable.Rename>()
        for ((file, id) in resolved) {
            val name = try {
                FUNCTION.find(file.readText())?.groupValues?.get(1)
            } catch (e: Exception) {
                null
            } ?: continue
            val derived = Cs2Names.scriptIdentifier(id)
            if (derived != name) {
                out.add(Cs2SymbolTable.Rename(Gameval.CLIENTSCRIPT, id.toString(), derived, name))
            }
        }
        return out
    }

    private fun resolve(
        folder: File,
        files: List<File>,
        bindings: Map<String, Int>,
        problems: MutableList<String>,
    ): Map<File, Int> {
        val resolved = HashMap<File, Int>(files.size * 2)
        val orphans = ArrayList<File>()
        for (file in files) {
            val relative = Cs2Layout.relativePath(folder, file)
            val id = bindings[relative] ?: try {
                Cs2Layout.scriptIdOf(file.readText(), relative)
            } catch (e: Exception) {
                null
            }
            if (id == null) orphans.add(file) else resolved[file] = id
        }
        if (orphans.isEmpty()) return resolved

        val missing = Cs2Names.scriptPaths().keys - resolved.values.toSet()
        if (orphans.size == 1 && missing.size == 1) {
            resolved[orphans.first()] = missing.first()
            return resolved
        }
        for (orphan in orphans) {
            val relative = Cs2Layout.relativePath(folder, orphan) ?: orphan.name
            problems.add(
                "$relative: nothing in gamevals/clientscript.json is called " +
                    "'${headerNameOf(orphan) ?: relative}', and ${missing.size} scripts have no file, " +
                    "so which one this is cannot be settled - left where it is",
            )
        }
        return resolved
    }

    private fun headerNameOf(file: File): String? = try {
        Cs2Layout.gamevalHeader(file.readText())
    } catch (e: Exception) {
        null
    }

    private fun header(source: String, scriptId: Int): String {
        var out = source
        val binding = "// ${Cs2Layout.headerOf(scriptId)}"
        val existing = GAMEVAL_HEADER.find(out)
        out = when {
            existing != null -> out.replaceRange(existing.range, binding)
            else -> NUMERIC_HEADER.find(out)?.let { out.replaceRange(it.range, binding) } ?: out
        }
        val up = Cs2Layout.referencePrefix(scriptId)
        return REFERENCE.replace(out) { match -> "/// <reference path=\"$up${match.groupValues[1]}\" />" }
    }


    fun sync(folder: File, catalogs: File, dryRun: Boolean = false): SyncReport {
        val refusals = ArrayList<String>()
        if (!folder.isDirectory) {
            return SyncReport(emptyMap(), emptyList(), listOf("no such folder: ${folder.absolutePath}"), null)
        }
        if (!catalogs.isDirectory) {
            return SyncReport(emptyMap(), emptyList(), listOf("no such catalog folder: ${catalogs.absolutePath}"), null)
        }
        val symbols = Cs2SymbolTable.read(folder)
        val bindings = symbols.scriptFiles
        for (conflict in symbols.conflicts) refusals.add("$conflict")

        val edits = LinkedHashMap<String, LinkedHashMap<String, String>>()
        fun edit(type: String, key: String, name: String) {
            edits.getOrPut(type) { LinkedHashMap() }[key] = name
        }

        val components = Components()

        val changes = ArrayList<Change>()
        for (rename in symbols.renames) {
            val result = plan(rename, components, ::edit)
            if (result == null) {
                refusals.add("${rename.what} ${rename.key}: cannot write '${rename.project}' back to a catalog")
                continue
            }
            if (result.startsWith("!")) {
                refusals.add(result.substring(1))
                continue
            }
            changes.add(Change(rename.what, rename.key, rename.catalog, rename.project))
        }
        components.materialise(::edit)

        for ((type, entries) in edits) {
            validate(type, entries, refusals)
        }
        if (refusals.isNotEmpty() || dryRun) return SyncReport(emptyMap(), changes, refusals, null)

        val written = LinkedHashMap<String, Int>()
        for ((type, entries) in edits) {
            val file = catalogs.resolve("$type.json")
            val applied = try {
                writeCatalog(file, entries)
            } catch (e: Exception) {
                refusals.add("cannot write ${file.path}: ${e.message}")
                continue
            }
            written[type] = applied
        }
        if (refusals.isNotEmpty()) return SyncReport(written, changes, refusals, null)

        Cs2Names.reload()
        return SyncReport(written, changes, refusals, apply(folder, Cs2SymbolTable.read(folder), bindings))
    }

    private fun plan(
        rename: Cs2SymbolTable.Rename,
        components: Components,
        edit: (String, String, String) -> Unit,
    ): String? {
        val project = rename.project.removeSuffix(OLD_SUFFIX)
        val space = Cs2Names.VAR_PREFIXES.entries.firstOrNull { it.value == rename.what }?.key
        if (space != null) {
            val type = Cs2Names.catalogType(space)
                ?: return "!${rename.what} ${rename.key}: there is no ${rename.what} catalog to rename in"
            val name = project.removePrefix("${rename.what}_")
            legal(type, rename.key, name)?.let { return "!$it" }
            edit(type, rename.key, name)
            return name
        }
        if (rename.what == Gameval.CLIENTSCRIPT) return script(rename, project, edit)
        if (rename.what == Gameval.COMPONENT) return component(rename, project, components)
        if (rename.what in Cs2Names.NAMESPACES) {
            val name = project.substringAfterLast('.')
            legal(rename.what, rename.key, name)?.let { return "!$it" }
            edit(rename.what, rename.key, name)
            return name
        }
        return null
    }

    private fun script(
        rename: Cs2SymbolTable.Rename,
        project: String,
        edit: (String, String, String) -> Unit,
    ): String {
        val id = rename.key.toIntOrNull() ?: return "!clientscript ${rename.key}: not an id"
        legal(Gameval.CLIENTSCRIPT, rename.key, project)?.let { return "!$it" }
        val existing = Gameval.rawName(Gameval.CLIENTSCRIPT, id).orEmpty()
        val match = CLIENTSCRIPT_NAME.matchEntire(existing)
        val folder = rename.location?.substringBefore('/')?.takeIf { it.contains('/').not() }
        val category = match?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }
            ?: folder?.takeIf { it != Cs2Names.UNCATEGORISED && it != rename.location }
        val name = if (category == null) project else "[$category,$project]"
        val others = Gameval.entries(Gameval.CLIENTSCRIPT).entries.count { (other, raw) ->
            other != id && CLIENTSCRIPT_NAME.matchEntire(raw)?.groupValues?.get(2) == project
        }
        if (category != null) legal(Gameval.CLIENTSCRIPT, rename.key, category)?.let { return "!$it" }
        if (others > 0) {
            return "!clientscript $id: another category already has a script called '$project', " +
                "so the name would derive as '<category>_$project' rather than '$project'"
        }
        edit(Gameval.CLIENTSCRIPT, rename.key, name)
        return name
    }

    private fun component(
        rename: Cs2SymbolTable.Rename,
        project: String,
        components: Components,
    ): String? {
        val parts = project.split('.')
        val derived = rename.catalog.split('.')
        if (parts.size != 3 || derived.size != 3) return null
        val interfaceId = rename.key.substringBefore(':').toIntOrNull() ?: return null
        val existing = Gameval.rawEntries(Gameval.COMPONENT)[rename.key] ?: return null
        if (!existing.contains(':')) {
            return "!component ${rename.key}: '$existing' has no interface part to rename under"
        }
        val newComponent = parts[2]
        legal(Gameval.COMPONENT, rename.key, newComponent)?.let { return "!$it" }
        components.member(rename.key, newComponent)
        if (parts[1] != derived[1]) {
            legal(Gameval.INTERFACE, interfaceId.toString(), parts[1])?.let { return "!$it" }
            components.group(interfaceId, existing.substringBefore(':'), parts[1])
        }
        return "$newComponent"
    }

    private class Components {
        private val members = HashMap<String, String>()

        private val groups = HashMap<Int, String>()

        private val previous = HashMap<Int, String>()

        fun member(key: String, name: String) {
            members[key] = name
        }

        fun group(interfaceId: Int, from: String, to: String) {
            groups[interfaceId] = to
            previous[interfaceId] = from
        }

        fun materialise(edit: (String, String, String) -> Unit) {
            if (members.isEmpty() && groups.isEmpty()) return
            val catalog = Gameval.rawEntries(Gameval.COMPONENT)
            val keys = LinkedHashSet(members.keys)
            for (interfaceId in groups.keys) {
                for ((key, name) in catalog) {
                    if (key.substringBefore(':').toIntOrNull() != interfaceId) continue
                    if (!name.contains(':')) continue
                    keys.add(key)
                }
            }
            for (key in keys.sorted()) {
                val existing = catalog[key] ?: continue
                val interfaceId = key.substringBefore(':').toIntOrNull() ?: continue
                val group = groups[interfaceId] ?: existing.substringBefore(':')
                val member = members[key] ?: existing.substringAfter(':')
                edit(Gameval.COMPONENT, key, "$group:$member")
            }
            for ((interfaceId, name) in groups) {
                if (Gameval.rawName(Gameval.INTERFACE, interfaceId) != previous[interfaceId]) continue
                edit(Gameval.INTERFACE, interfaceId.toString(), name)
            }
        }
    }

    private fun legal(type: String, key: String, name: String): String? {
        if (name.isEmpty()) return "$type $key: an empty name"
        if (Cs2Identifiers.escape(name, key) != name) {
            return "$type $key: '$name' is not a name a catalog can hold as written; " +
                "it would come back as '${Cs2Identifiers.escape(name, key)}'"
        }
        val prefix = GamevalCatalog.placeholderPrefix(type)
        if (name != GamevalCatalog.placeholder(type, key) &&
            name.startsWith("${prefix}_") &&
            PLACEHOLDER_TAIL.matches(name.removePrefix("${prefix}_"))
        ) {
            return "$type $key: '$name' is the placeholder form of a different id"
        }
        return null
    }

    private fun validate(type: String, entries: Map<String, String>, refusals: MutableList<String>) {
        val existing = Gameval.rawEntries(type)
        val owner = HashMap<String, String>(existing.size * 2)
        for ((key, name) in existing) owner[entries[key] ?: name] = key
        for ((key, name) in entries) {
            val taken = owner[name]
            if (taken != null && taken != key) {
                refusals.add("$type $key: '$name' is already the name of $type $taken")
            }
        }
    }


    private fun writeCatalog(file: File, entries: Map<String, String>): Int {
        val type = file.name.removeSuffix(".json")
        val lines = file.readText().split("\n").toMutableList()
        val pending = HashMap(entries)
        var inEntries = false
        var named = 0
        var total = 0
        var applied = 0
        val coverage = ArrayList<Int>()
        for (index in lines.indices) {
            val line = lines[index]
            if (!inEntries) {
                if (line.trimEnd().endsWith("\"entries\": {")) inEntries = true
                else if (NUMBER.matchEntire(line)?.groupValues?.get(2) in COUNTS) coverage.add(index)
                continue
            }
            val match = ENTRY.matchEntire(line) ?: continue
            val key = match.groupValues[2]
            val replacement = pending.remove(key)
            val name = replacement ?: match.groupValues[3]
            total++
            if (name != GamevalCatalog.placeholder(type, key)) named++
            if (replacement == null) continue
            lines[index] = match.groupValues[1] + "\"" + key + "\": \"" + name + "\"" + match.groupValues[4]
            applied++
        }
        if (pending.isNotEmpty()) {
            error("${file.name} has no entry for ${pending.keys.sorted().take(5)}")
        }
        for (index in coverage) {
            val match = NUMBER.matchEntire(lines[index]) ?: continue
            val value = if (match.groupValues[2] == "named") named else total - named
            lines[index] = match.groupValues[1] + "\"" + match.groupValues[2] + "\": " + value + match.groupValues[4]
        }
        file.writeText(lines.joinToString("\n"))
        return applied
    }


    class Renames(
        val globals: Map<String, String>,
        val members: Map<String, Map<String, String>>,
        val interfaces: Map<String, String>,
        val components: Map<String, Map<String, String>>,
    ) {
        fun isEmpty(): Boolean =
            globals.isEmpty() && members.isEmpty() && interfaces.isEmpty() && components.isEmpty()

        fun isNotEmpty(): Boolean = !isEmpty()
    }

    fun renamesFrom(renames: List<Cs2SymbolTable.Rename>): Renames {
        val globals = HashMap<String, String>()
        val members = HashMap<String, HashMap<String, String>>()
        val interfaces = HashMap<String, String>()
        val components = HashMap<String, HashMap<String, String>>()
        val componentNamespace = Cs2Names.NAMESPACES.getValue(Gameval.COMPONENT)
        for (rename in renames) {
            if (!rename.project.contains('.')) {
                globals[rename.project] = rename.catalog
                continue
            }
            val from = rename.project.split('.')
            val to = rename.catalog.split('.')
            if (from.size != to.size) continue
            if (from.first() == componentNamespace && from.size == 3) {
                if (from[1] != to[1]) interfaces[from[1]] = to[1]
                if (from[2] != to[2]) components.getOrPut(from[1]) { HashMap() }[from[2]] = to[2]
                continue
            }
            if (from.size == 2 && from[1] != to[1]) {
                members.getOrPut(from.first()) { HashMap() }[from[1]] = to[1]
            }
        }
        return Renames(globals, members, interfaces, components)
    }

    fun rewrite(source: String, renames: Renames): String {
        if (renames.isEmpty()) return source
        val componentNamespace = Cs2Names.NAMESPACES.getValue(Gameval.COMPONENT)
        val namespaces = Cs2Names.NAMESPACES.values.toSet()
        val tokens = try {
            Cs2Lexer(source).tokenise()
        } catch (e: Exception) {
            return source
        }
        val out = StringBuilder(source.length + 64)
        var copied = 0

        fun replace(token: Token, text: String) {
            out.append(source, copied, token.start)
            out.append(text)
            copied = token.start + token.text.length
        }

        fun dot(index: Int): Boolean {
            val token = tokens.getOrNull(index) ?: return false
            return token.kind == TokenKind.PUNCT && token.text == "."
        }

        fun identifier(index: Int): Token? =
            tokens.getOrNull(index)?.takeIf { it.kind == TokenKind.IDENT }

        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            if (token.kind != TokenKind.IDENT || dot(i - 1)) {
                i++
                continue
            }
            if (token.text in namespaces && dot(i + 1)) {
                val first = identifier(i + 2)
                if (first == null) {
                    i++
                    continue
                }
                if (token.text == componentNamespace) {
                    val second = if (dot(i + 3)) identifier(i + 4) else null
                    val group = first.text
                    renames.interfaces[group]?.let { replace(first, it) }
                    if (second != null) {
                        renames.components[group]?.get(second.text)?.let { replace(second, it) }
                        i += 5
                        continue
                    }
                    i += 3
                    continue
                }
                renames.members[token.text]?.get(first.text)?.let { replace(first, it) }
                i += 3
                continue
            }
            renames.globals[token.text]?.let { replace(token, it) }
            i++
        }
        out.append(source, copied, source.length)
        return out.toString()
    }

    private const val OLD_SUFFIX = "Old"

    private val CLIENTSCRIPT_NAME = Regex("\\[([^,\\]]*),([^\\]]*)\\]")

    private val FUNCTION = Regex("""^function (\w+)\s*\(""", RegexOption.MULTILINE)

    private val NUMERIC_HEADER = Regex("""^// clientscript \d+$""", RegexOption.MULTILINE)

    private val GAMEVAL_HEADER = Regex("""^// (?:\[[^\]\n]*\]|\w+)$""", RegexOption.MULTILINE)

    private val REFERENCE = Regex("""/// <reference path="(?:\.\./)*\./?([\w.]+\.d\.ts)" />""")

    private val PLACEHOLDER_TAIL = Regex("\\d+(_\\d+)?")

    private val ENTRY = Regex("""(\s*)"([^"]+)": "([^"]*)"(,?)""")

    private val NUMBER = Regex("""(\s*)"(\w+)": (\d+)(,?)""")

    private val COUNTS = setOf("named", "placeholder")
}
