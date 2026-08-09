package org.darkan.tools.cs2

/**
 * Generates the ambient TypeScript declarations that make decompiled scripts
 * type-check and autocomplete in an editor.
 *
 * Every opcode becomes a function whose parameters and return type follow its
 * stack signature, so an editor can tell that `ifGetText` yields a string while
 * `ifGetWidth` yields a number. The handful of opcodes with runtime-dependent
 * shapes - the hook setters, the param lookups, `JOIN_STRING` - are declared
 * loosely rather than wrongly.
 */
import org.darkan.tools.cs2.ir.Cs2Type
import org.darkan.tools.cs2.ir.VarRef
import org.darkan.tools.cs2.ir.VarSpace

object Cs2Declarations {

    /**
     * The project file an editor needs. Without it every script is opened as an
     * isolated program, so neither `cs2.d.ts` nor any sibling script resolves and
     * every name comes back unknown.
     */
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
          "include": ["*.ts"]
        }
    """.trimIndent() + "\n"

    /**
     * Declares every variable the scripts touch, one named global each.
     *
     * The id lives in the doc comment rather than only in the name, so an editor
     * can rename the symbol across the whole project and the compiler still
     * knows which variable it is.
     */
    fun variables(references: Collection<VarRef>): String {
        val out = StringBuilder()
        out.appendLine("// GENERATED - names may be edited; the ids in the comments are what bind them.")
        out.appendLine()
        val seen = LinkedHashSet<String>()
        for (reference in references.sortedWith(compareBy({ it.space.ordinal }, { it.id }, { it.variant }))) {
            val name = Cs2Symbols.variableName(reference)
            if (!seen.add(name)) continue
            val variant = if (reference.variant == "old") " old" else ""
            val space = when (reference.space) {
                VarSpace.VARP -> "varp"
                VarSpace.VARBIT -> "varbit"
                VarSpace.VARC -> "varc"
                VarSpace.VARC_STRING -> "varcstring"
                VarSpace.VARN -> "varn"
                VarSpace.VARNBIT -> "varnbit"
                VarSpace.CLAN -> "clanvar"
                VarSpace.CLAN_SETTING -> "clansetting"
            }
            val type = if (reference.type == Cs2Type.STRING) "string" else "number"
            out.appendLine("/** $space ${reference.id}$variant */")
            out.appendLine("declare let $name: $type;")
        }
        return out.toString()
    }

    fun generate(): String {
        val out = StringBuilder()
        out.appendLine("// GENERATED - do not edit by hand.")
        out.appendLine("// Ambient declarations for decompiled CS2 clientscripts.")
        out.appendLine("// Regenerate with: tools cs2 declarations <dir>")
        out.appendLine()
        appendPreamble(out)

        for (op in Cs2Opcodes.all.sortedBy { it.tsName }) {
            if (op.tsName in HANDWRITTEN) continue
            appendOpcode(out, op)
        }
        return out.toString()
    }

    /** Opcodes whose shape is declared by hand below rather than derived. */
    private val HANDWRITTEN = setOf(
        "pushConstantInt", "pushConstantString", "pushLongConstant",
        "pushIntLocal", "pushStringLocal", "pushLongLocal",
        "popIntLocal", "popStringLocal", "popLongLocal",
        "popIntDiscard", "popStringDiscard", "popLongDiscard",
        "pushVar", "popVar", "pushVarbit", "popVarbit",
        "loadVarc", "storeVarc", "loadVarcString", "storeVarcString",
        "getVarpOld", "getVarpbitOld", "getVarnOld", "getVarnbitOld",
        "defineArray", "pushArrayInt", "popArrayInt", "joinString",
        "branch", "branchNot", "branchEquals", "branchLessThan", "branchGreaterThan",
        "branchLessThanOrEquals", "branchGreaterThanOrEquals",
        "branchIfTrue", "branchIfFalse", "switchOp", "returnOp", "gosubWithParams",
        "longBranchNot", "longBranchEquals", "longBranchLessThan", "longBranchGreaterThan",
        "longBranchLessThanOrEquals", "longBranchGreaterThanOrEquals",
        "add", "sub", "multiply", "divide", "modulo", "and", "or",
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
             */
            declare function component(interfaceId: number, component?: number): number;
            declare function tile(x: number, y?: number, plane?: number): number;
            declare function item(id: number): number;
            declare function npc(id: number): number;
            declare function loc(id: number): number;
            declare function seq(id: number): number;
            declare function spotanim(id: number): number;
            declare function enumId(id: number): number;
            declare function struct(id: number): number;
            declare function param(id: number): number;
            declare function inv(id: number): number;
            declare function skill(id: number): number;
            declare function scriptId(id: number): number;
            declare function colour(rgb: number): number;
            declare function graphic(id: number): number;
            """.trimIndent(),
        )
        out.appendLine()
    }

    private fun appendOpcode(out: StringBuilder, op: Cs2Op) {
        out.appendLine("/** `${op.opName}` (opcode ${op.id}) */")
        // The type parameter is what lets an instruction's own operand ride along
        // as `ccCreate<1>(...)`; it is never used as a type.
        out.appendLine("declare function ${op.tsName}<Operand = void>(${parameters(op)}): ${returnType(op)};")
    }

    private fun parameters(op: Cs2Op): String {
        if (op.kind == OpKind.HOOK) {
            val component = if (op.popInt == 1) ", component: number" else ""
            return "callback: unknown$component"
        }
        // An opcode drawing from more than one stack receives its arguments in
        // the order the compiler pushed them, which can interleave the types, so
        // those parameters are widened rather than declared in a fixed order.
        val stacks = listOf(op.popInt, op.popStr, op.popLong).count { it > 0 }
        if (stacks > 1) {
            val total = op.popInt + op.popStr + op.popLong
            return (0 until total).joinToString(", ") { "arg$it: number | string | bigint | boolean" }
        }
        val names = ArrayList<String>()
        repeat(op.popInt) {
            val kind = op.argTypes.getOrNull(names.size)
            // A flag is still an int underneath - `true`/`false` is only a nicer
            // spelling for the literals, and a local holding one is a number.
            val type = if (kind == ArgType.BOOLEAN) "number | boolean" else "number"
            names.add("arg${names.size}: $type")
        }
        repeat(op.popStr) { names.add("arg${names.size}: string") }
        repeat(op.popLong) { names.add("arg${names.size}: bigint") }
        return names.joinToString(", ")
    }

    private fun returnType(op: Cs2Op): String {
        // A param lookup yields an int or a string depending on which param was
        // asked for, so it cannot be narrowed here; `any` keeps it assignable to
        // whichever the caller uses it as.
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
