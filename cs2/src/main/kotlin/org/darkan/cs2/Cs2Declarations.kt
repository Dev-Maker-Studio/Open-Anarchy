package org.darkan.cs2

import org.darkan.core.gameval.Gameval
import org.darkan.core.gameval.GamevalCatalog
import org.darkan.cs2.ir.Cs2Type
import org.darkan.cs2.ir.VarRef

object Cs2Declarations {

    val HELPERS: Set<String> = setOf(
        "array0", "array1", "array2", "array3", "array4",
        "defineArray", "hook", "noHook", "discard", "isTruthy", "isFalsy", "goto",
        "colour", "coord",
        "component", "tile", "item", "obj", "npc", "loc", "seq", "spotanim", "enumId",
        "struct", "param", "inv", "skill", "stat", "scriptId", "graphic",
        "interfaceId", "sound", "midi", "cursor", "mapelement", "quest", "worldmap", "model",
    ) + Cs2Events.NAMES.values + Cs2TypeChars.NAMES.values

    const val GAMEVALS_FILE = "gamevals.d.ts"

    const val VARS_FILE = "vars.d.ts"

    const val OPCODES_FILE = "cs2.d.ts"

    fun tsconfig(): String = """
        {
          "compilerOptions": {
            "target": "es2020",
            "lib": ["es2020"],
            "noEmit": true,
            "noLib": false,
            "allowJs": false,
            "strict": false,
            "skipLibCheck": true,
            "disableSizeLimit": true
          },
          "include": ["**/*.ts"]
        }
    """.trimIndent() + "\n"

    fun gamevals(): String {
        val out = StringBuilder(1 shl 22)
        out.appendLine("// GENERATED from gamevals/<type>.json - those catalogs are the only source of truth for these names.")
        out.appendLine("// Do not edit a name here expecting it to stick: `cs2 apply-names <dir>` re-derives this file")
        out.appendLine("// from the catalogs, and `cs2 sync-names <dir>` writes an editor rename back into them.")
        out.appendLine("// The id in each doc comment is what binds the name; renaming the name never changes it.")
        out.appendLine()

        for (type in Cs2Names.SIMPLE_TYPES) {
            val entries = Cs2Names.identifiers(type)
            val namespace = Cs2Names.NAMESPACES.getValue(type)
            out.append("/** Revision-727 ").append(type)
                .append(" ids by Jagex dev-name (").append(entries.size).append(" named of ")
                .append(Gameval.allEntries(type).size).appendLine("). */")
            out.append("declare namespace ").append(namespace).appendLine(" {")
            for ((id, identifier) in entries) {
                out.append("    /** ").append(type).append(' ').append(id).append(" */ const ")
                    .append(identifier).appendLine(": number;")
            }
            out.appendLine("}")
            out.appendLine()
        }

        val interfaces = Cs2Names.componentInterfaces()
        val components = interfaces.sumOf { it.components.size }
        out.append("/** Revision-727 interface components: `Component.<interface>.<component>` is the packed ")
        out.appendLine("`(iface << 16) | comp`,")
        out.append("  * and `Component.<interface>.IFACE` the interface id (")
            .append(components).append(" named components across ")
            .append(interfaces.size).appendLine(" interfaces). */")
        out.appendLine("declare namespace ${Cs2Names.NAMESPACES.getValue(Gameval.COMPONENT)} {")
        for (group in interfaces) {
            out.append("    /** ").append(group.rawName).append(", interface ")
                .append(group.interfaceId).appendLine(". */")
            out.append("    namespace ").append(group.identifier).appendLine(" {")
            out.append("        /** interface id ").append(group.interfaceId)
                .appendLine(" */ const ${Cs2Identifiers.IFACE_MEMBER}: number;")
            for (component in group.components) {
                out.append("        /** ").append(Gameval.COMPONENT).append(' ')
                    .append(GamevalCatalog.componentKey(group.interfaceId, component.componentId))
                    .append(" */ const ").append(component.identifier).appendLine(": number;")
            }
            out.appendLine("    }")
        }
        out.appendLine("}")
        return out.toString()
    }

    fun variables(
        references: Collection<VarRef>,
        kinds: (VarRef) -> ArgType? = { null },
    ): String {
        val out = StringBuilder()
        out.appendLine("// GENERATED - the default names come from gamevals/<type>.json, the only source of truth for them.")
        out.appendLine("// A name may be edited here; the id in the comment above it is what binds it. `cs2 sync-names <dir>`")
        out.appendLine("// writes an edit back to the catalog, `cs2 apply-names <dir>` re-derives this file from the catalogs.")
        out.appendLine()
        val seen = LinkedHashSet<String>()
        for (reference in references.sortedWith(compareBy({ it.space.ordinal }, { it.id }, { it.variant }))) {
            val name = Cs2Symbols.variableName(reference)
            if (!seen.add(name)) continue
            val variant = if (reference.variant == "old") " old" else ""
            val space = Cs2Names.VAR_PREFIXES.getValue(reference.space)
            val type = when {
                reference.type == Cs2Type.STRING -> "string"
                else -> kinds(reference)?.let { Cs2Names.aliasFor(it) } ?: "number"
            }
            out.appendLine("/** $space ${reference.id}$variant */")
            out.appendLine("declare let $name: $type;")
        }
        return out.toString()
    }

    fun generate(
        learnedArguments: Map<String, ArgType> = emptyMap(),
        stringParams: Set<Int> = emptySet(),
    ): String {
        val out = StringBuilder()
        out.appendLine("// GENERATED - do not edit by hand.")
        out.appendLine("// Ambient declarations for decompiled CS2 clientscripts.")
        out.appendLine("// Regenerate with: tools cs2 declarations <dir>")
        out.appendLine()
        appendPreamble(out)
        out.append(stringParams(stringParams))
        out.appendLine()

        for (op in Cs2Op.entries.sortedBy { it.tsName }) {
            if (op in HANDWRITTEN) continue
            appendOpcode(out, op, learnedArguments)
        }
        return out.toString()
    }

    fun stringParams(ids: Set<Int>): String {
        val out = StringBuilder()
        out.appendLine("/**")
        out.appendLine(" * The params a `*_PARAM` opcode yields a string for; every other id yields an int.")
        out.appendLine(" *")
        out.appendLine(" * A param's type belongs to its param definition rather than to any script, and the")
        out.appendLine(" * bytecode of a lookup does not say which stack it pushed onto - so this is the one thing")
        out.appendLine(" * about compiling this folder that no script's own source states. It is what lets the")
        out.appendLine(" * folder be compiled back with nothing but the folder; see docs/cache-source.md.")
        out.appendLine(" */")
        if (ids.isEmpty()) {
            out.appendLine("type $STRING_PARAM = never;")
            return out.toString()
        }
        out.appendLine("type $STRING_PARAM =")
        val sorted = ids.sorted()
        for ((index, id) in sorted.withIndex()) {
            out.append("    | ").append(id).appendLine(if (index == sorted.lastIndex) ";" else "")
        }
        return out.toString()
    }

    fun readStringParams(source: String): Set<Int>? {
        val body = STRING_PARAMS.find(source)?.groupValues?.get(1) ?: return null
        return NUMBER.findAll(body).mapNotNull { it.value.toIntOrNull() }.toCollection(LinkedHashSet())
    }

    private const val STRING_PARAM = "StringParam"

    private val STRING_PARAMS = Regex("""type $STRING_PARAM =([^;]*);""")

    private val NUMBER = Regex("""\d+""")

    private val HANDWRITTEN = setOf(
        Cs2Op.PUSH_CONSTANT_INT, Cs2Op.PUSH_CONSTANT_STRING, Cs2Op.PUSH_LONG_CONSTANT,
        Cs2Op.PUSH_INT_LOCAL, Cs2Op.PUSH_STRING_LOCAL, Cs2Op.PUSH_LONG_LOCAL,
        Cs2Op.POP_INT_LOCAL, Cs2Op.POP_STRING_LOCAL, Cs2Op.POP_LONG_LOCAL,
        Cs2Op.POP_INT_DISCARD, Cs2Op.POP_STRING_DISCARD, Cs2Op.POP_LONG_DISCARD,
        Cs2Op.PUSH_VAR, Cs2Op.POP_VAR, Cs2Op.PUSH_VARBIT, Cs2Op.POP_VARBIT,
        Cs2Op.PUSH_VARC_INT, Cs2Op.POP_VARC_INT, Cs2Op.PUSH_VARC_STRING, Cs2Op.POP_VARC_STRING,
        Cs2Op.GET_VARP_OLD, Cs2Op.GET_VARPBIT_OLD, Cs2Op.GET_VARN_OLD, Cs2Op.GET_VARNBIT_OLD,
        Cs2Op.DEFINE_ARRAY, Cs2Op.PUSH_ARRAY_INT, Cs2Op.POP_ARRAY_INT, Cs2Op.JOIN_STRING,
        Cs2Op.BRANCH, Cs2Op.BRANCH_NOT, Cs2Op.BRANCH_EQUALS, Cs2Op.BRANCH_LESS_THAN, Cs2Op.BRANCH_GREATER_THAN,
        Cs2Op.BRANCH_LESS_THAN_OR_EQUALS, Cs2Op.BRANCH_GREATER_THAN_OR_EQUALS,
        Cs2Op.BRANCH_IF_TRUE, Cs2Op.BRANCH_IF_FALSE, Cs2Op.SWITCH, Cs2Op.RETURN, Cs2Op.GOSUB_WITH_PARAMS,
        Cs2Op.LONG_BRANCH_NOT, Cs2Op.LONG_BRANCH_EQUALS, Cs2Op.LONG_BRANCH_LESS_THAN, Cs2Op.LONG_BRANCH_GREATER_THAN,
        Cs2Op.LONG_BRANCH_LESS_THAN_OR_EQUALS, Cs2Op.LONG_BRANCH_GREATER_THAN_OR_EQUALS,
        Cs2Op.ADD, Cs2Op.SUB, Cs2Op.MULTIPLY, Cs2Op.DIVIDE, Cs2Op.MODULO, Cs2Op.AND, Cs2Op.OR,
    )

    private fun appendPreamble(out: StringBuilder) {
        out.appendLine(
            """
            /** The five global integer arrays scripts share. */
            declare const array0: number[];
            declare const array1: number[];
            declare const array2: number[];
            declare const array3: number[];
            declare const array4: number[];

            /** Allocates one of the global arrays. */
            declare function defineArray(array: number, elementType: number, size: number): void;

            /**
             * Binds a callback to a component event.
             *
             * `spec` is the compiler's type string, one character per bound
             * argument - `i` int, `s` string, `I` component, `o` obj, and so on -
             * with a trailing `Y` when a trigger list follows.
             */
            declare function hook(
                script: Function,
                spec: string,
                args: unknown[],
                triggers?: number[],
            ): unknown;

            /** Clears a component event. */
            declare function noHook(spec: string): unknown;

            /** Throws away a value a call left on the stack. */
            declare function discard(value: unknown): void;

            /** `x != 0`, compiled as the dedicated truthiness branch. */
            declare function isTruthy(value: number): boolean;

            /** `x == 0`, compiled as the dedicated truthiness branch. */
            declare function isFalsy(value: number): boolean;

            /** An unstructured jump, used where a script's shape has no direct form. */
            declare function goto(label: string): void;

            /**
             * Composite values, spelled out rather than left as packed integers.
             * Each one packs straight back to the integer the bytecode holds.
             *
             * An id goes through its gameval constant instead - `Obj.abyssal_whip`,
             * `Component.chatbox.dialogue` - which says what it is without a
             * wrapper. Only `colour` and `coord` are still written this way, having
             * no name to carry them; the rest are here because an older dump wrote
             * them and still has to compile.
             */
            declare function component(interfaceId: number, component?: number): number;
            declare function coord(x: number, y?: number, level?: number): number;
            declare function tile(x: number, y?: number, plane?: number): number;
            declare function obj(id: number): number;
            declare function item(id: number): number;
            declare function npc(id: number): number;
            declare function loc(id: number): number;
            declare function seq(id: number): number;
            declare function spotanim(id: number): number;
            declare function enumId(id: number): number;
            declare function struct(id: number): number;
            declare function param(id: number): number;
            declare function inv(id: number): number;
            declare function stat(id: number): number;
            declare function skill(id: number): number;
            declare function colour(rgb: number): number;
            declare function graphic(id: number): number;
            declare function interfaceId(id: number): number;
            declare function sound(id: number): number;
            declare function midi(id: number): number;
            declare function cursor(id: number): number;
            declare function mapelement(id: number): number;
            declare function quest(id: number): number;
            declare function worldmap(id: number): number;
            declare function model(id: number): number;

            /**
             * A clientscript referred to by id rather than called. The script's own
             * function is the name, so renaming the function renames the reference.
             */
            declare function scriptId(script: Function | number): number;
            """.trimIndent(),
        )
        out.appendLine()
        appendKinds(out)
    }

    private fun appendKinds(out: StringBuilder) {
        out.appendLine("/** What an integer means, where the corpus agrees on one thing. */")
        for (alias in Cs2Names.ALIASES.values.distinct()) {
            if (alias == "boolean") continue
            out.appendLine("type $alias = number;")
        }
        out.appendLine()
        out.appendLine("/** The type codes a compiler writes into `enumOp` and hook formats. */")
        for (name in Cs2TypeChars.NAMES.values) out.appendLine("declare const $name: number;")
        out.appendLine()
        out.appendLine("/**")
        out.appendLine(" * What the client substitutes into a hook's arguments when it fires it.")
        out.appendLine(" * A hook is bound long before it runs, so these stand in for the values")
        out.appendLine(" * that only exist at the moment of the event.")
        out.appendLine(" */")
        for ((name, value) in Cs2Events.VALUES) {
            val type = if (name in Cs2Events.COMPONENTS) "component" else "number"
            out.appendLine("/** ${Cs2Events.MEANINGS[name]} ($value) */ declare const $name: $type;")
        }
        out.appendLine()
    }

    private fun appendOpcode(out: StringBuilder, op: Cs2Op, learned: Map<String, ArgType>) {
        out.appendLine("/** `${op.name}` (opcode ${op.id}) */")
        out.appendLine("declare function ${op.tsName}<Operand = void>(${parameters(op, learned)}): ${returnType(op)};")
    }

    private fun parameters(op: Cs2Op, learned: Map<String, ArgType>): String {
        if (op.kind == OpKind.HOOK) {
            val component = if (op.popInt == 1) ", component: number" else ""
            return "callback: unknown$component"
        }
        val stacks = listOf(op.popInt, op.popStr, op.popLong).count { it > 0 }
        if (stacks > 1) {
            val total = op.popInt + op.popStr + op.popLong
            return (0 until total).joinToString(", ") { "arg$it: number | string | bigint | boolean" }
        }
        val names = ArrayList<String>()
        repeat(op.popInt) {
            val kind = op.argTypes.getOrNull(names.size)?.takeIf { it != ArgType.INT }
                ?: learned["${op.name}:${names.size}"]
            val alias = kind?.let { Cs2Names.aliasFor(it) } ?: "number"
            names.add("arg${names.size}: $alias | boolean")
        }
        repeat(op.popStr) { names.add("arg${names.size}: string") }
        repeat(op.popLong) { names.add("arg${names.size}: bigint") }
        return names.joinToString(", ")
    }

    private fun returnType(op: Cs2Op): String {
        if (op.kind == OpKind.PARAM) return "any"

        val parts = ArrayList<String>()
        repeat(op.pushInt) { parts.add("number") }
        repeat(op.pushStr) { parts.add("string") }
        repeat(op.pushLong) { parts.add("bigint") }
        return when (parts.size) {
            0 -> "void"
            1 -> parts.first()
            else -> "[" + parts.joinToString(", ") + "]"
        }
    }
}
