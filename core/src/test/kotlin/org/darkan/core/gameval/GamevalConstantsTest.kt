package org.darkan.core.gameval

import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The generated constants (`org.darkan.core.gameval.gen.*`, from `gamevals/gen_kotlin.py`) must
 * agree with the runtime catalog they were generated from. The generated classes exist only for
 * the types whose catalog existed at generation time, so they are reached by reflection and each
 * check skips itself when its class, field or catalog is not there yet.
 */
class GamevalConstantsTest {

    private val genPackage = "org.darkan.core.gameval.gen"

    /** `<Class>.<field>` walked up the generated superclass chain, or `null` when either is absent. */
    private fun constant(cls: String, field: String): Int? {
        val type = try {
            Class.forName("$genPackage.$cls")
        } catch (_: ClassNotFoundException) {
            return null
        }
        var current: Class<*>? = type
        while (current != null) {
            val f = current.declaredFields.firstOrNull { it.name == field && Modifier.isStatic(it.modifiers) }
            if (f != null) return f.also { it.isAccessible = true }.getInt(null)
            current = current.superclass
        }
        return null
    }

    /** Every public static int constant reachable through the class, superclass chain included. */
    private fun constants(cls: String): Map<String, Int>? {
        val type = try {
            Class.forName("$genPackage.$cls")
        } catch (_: ClassNotFoundException) {
            return null
        }
        val out = LinkedHashMap<String, Int>()
        var current: Class<*>? = type
        while (current != null) {
            for (f in current.declaredFields) {
                if (Modifier.isStatic(f.modifiers) && f.type == Int::class.javaPrimitiveType) {
                    f.isAccessible = true // the overflow shards (Loc2, Loc3 ...) are package-private classes
                    out.putIfAbsent(f.name, f.getInt(null))
                }
            }
            current = current.superclass
        }
        return out
    }

    @Test
    fun `Obj coins matches the catalog`() {
        if (!Gameval.has(Gameval.OBJ) || Gameval.id(Gameval.OBJ, "coins") == null) {
            println("skipped: no obj.json catalog with 'coins'")
            return
        }
        val generated = constant("Obj", "coins")
        assertNotNull(generated, "obj.json exists but Obj.coins was not generated; run python3 gamevals/gen_kotlin.py")
        assertEquals(Gameval.requireId(Gameval.OBJ, "coins"), generated)
    }

    @Test
    fun `Cs2 quicksort matches the catalog`() {
        val id = Gameval.id(Gameval.CLIENTSCRIPT, "[proc,quicksort]")
        if (id == null) {
            println("skipped: no clientscript.json catalog with [proc,quicksort]")
            return
        }
        // Bare name unless the crosswalk also proved a [clientscript,quicksort], then category-prefixed.
        val generated = constant("Cs2", "quicksort") ?: constant("Cs2", "proc_quicksort")
        assertNotNull(generated, "clientscript.json exists but Cs2.quicksort was not generated; run python3 gamevals/gen_kotlin.py")
        assertEquals(id, generated)
    }

    @Test
    fun `every generated constant is a named id of its catalog`() {
        val classes = mapOf(
            Gameval.OBJ to "Obj", Gameval.NPC to "Npc", Gameval.LOC to "Loc", Gameval.SEQ to "Seq",
            Gameval.SPOTANIM to "Spotanim", Gameval.INV to "Inv", Gameval.CLIENTSCRIPT to "Cs2",
            Gameval.VARP to "Varp", Gameval.VARBIT to "Varbit", Gameval.MODEL to "Model", Gameval.SOUND to "Sound",
        )
        var checked = 0
        for ((type, cls) in classes) {
            val generated = constants(cls) ?: continue
            if (!Gameval.has(type)) continue
            val named = Gameval.entries(type)
            val unknown = generated.filterValues { it !in named }
            assertTrue(
                unknown.isEmpty(),
                "$cls has ${unknown.size} constant(s) whose id is no longer named in $type.json (e.g. ${unknown.entries.take(3)}); regenerate with python3 gamevals/gen_kotlin.py",
            )
            assertEquals(named.size, generated.size, "$cls constant count differs from the named ids in $type.json; regenerate with python3 gamevals/gen_kotlin.py")
            checked++
        }
        println("checked $checked generated class(es) against their catalogs")
    }
}
