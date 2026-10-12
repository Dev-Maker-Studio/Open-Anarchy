package org.darkan.cs2

import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.darkan.cs2.compile.Cs2Parser
import org.darkan.cs2.ir.Cs2Type

class Cs2SourceContext private constructor(
    private val scripts: Map<Int, Cs2Script>,
    private val stringParams: Set<Int>?,
    val orphans: List<String>,
) : Cs2Context {

    val size: Int get() = scripts.size

    val declaresStringParams: Boolean get() = stringParams != null

    override fun script(id: Int): Cs2Script? = scripts[id]

    override fun paramIsString(paramId: Int): Boolean = stringParams?.contains(paramId) ?: false

    companion object {

        fun read(folder: File): Cs2SourceContext {
            val files = Cs2Layout.scriptFiles(folder)
            val threads = Runtime.getRuntime().availableProcessors()
            val pool = Executors.newFixedThreadPool(threads) { runnable ->
                Thread(runnable, "cs2-signatures").apply { isDaemon = true }
            }
            val read = try {
                pool.invokeAll(files.map { file -> Callable { signature(folder, file) } }).map { it.get() }
            } finally {
                pool.shutdown()
            }
            val scripts = HashMap<Int, Cs2Script>(files.size * 2)
            val orphans = ArrayList<String>()
            for (entry in read) {
                when (entry) {
                    null -> Unit
                    is Read.Script -> scripts[entry.id] = entry.script
                    is Read.Orphan -> orphans.add(entry.path)
                }
            }
            val declarations = folder.resolve(Cs2Declarations.OPCODES_FILE).takeIf { it.isFile }
            return Cs2SourceContext(
                scripts,
                declarations?.let { Cs2Declarations.readStringParams(it.readText()) },
                orphans,
            )
        }

        private sealed interface Read {
            class Script(val id: Int, val script: Cs2Script) : Read
            class Orphan(val path: String) : Read
        }

        private fun signature(folder: File, file: File): Read? {
            val source = try {
                file.readText()
            } catch (e: Exception) {
                return null
            }
            val relative = Cs2Layout.relativePath(folder, file)
            val id = Cs2Layout.scriptIdOf(source, relative)
            if (id == null) {
                val claimed = Cs2Layout.gamevalHeader(source) ?: return null
                return Read.Orphan("${relative ?: file.name}: '$claimed'")
            }
            val signature = Cs2Parser.signature(source) ?: return null
            return Read.Script(id, header(signature))
        }

        private fun header(signature: Cs2Parser.Signature): Cs2Script {
            val arguments = signature.arguments
            val ints = arguments.count { it == Cs2Type.INT }
            val strings = arguments.count { it == Cs2Type.STRING }
            val longs = arguments.count { it == Cs2Type.LONG }
            val script = Cs2Script(
                name = null,
                instructions = emptyList(),
                intLocalsCount = ints,
                stringLocalsCount = strings,
                longLocalsCount = longs,
                intArgsCount = ints,
                stringArgsCount = strings,
                longArgsCount = longs,
                switchTables = emptyList(),
            )
            val returns = signature.returns
            script.returnSignature = ReturnSignature(
                ints = returns.count { it == Cs2Type.INT },
                strings = returns.count { it == Cs2Type.STRING },
                longs = returns.count { it == Cs2Type.LONG },
            )
            script.returnOrder = returns
            script.argumentOrder = arguments
            return script
        }
    }
}
