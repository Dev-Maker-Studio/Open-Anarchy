package org.darkan.cs2

import java.io.File
import world.gregs.voidps.cache.Cache

class Cs2Compiler private constructor(
    val folder: File,
    project: () -> Cs2SourceProject,
) {

    constructor(
        cache: Cache,
        folder: File,
        analysis: () -> Cs2Analyzer = { Cs2Analyzer(cache).also { it.analyse() } },
    ) : this(folder, { Cs2SourceProject(cache, analysis(), folder) })

    private val project: Cs2SourceProject by lazy(project)

    fun compile(file: File): ByteArray = when (val result = project.compileFile(file)) {
        is Cs2SourceProject.CompileResult.Ok -> result.result.bytes
        is Cs2SourceProject.CompileResult.Failed -> throw Cs2CompileException(result.error)
    }

    fun scriptIdOf(file: File): Int = project.scriptIdOf(file)
        ?: throw Cs2CompileException(
            Cs2SourceProject.Diagnostic(
                file = file,
                scriptId = -1,
                message = "no clientscript header, and the path names no script in gamevals/clientscript.json",
                line = 1,
                column = 1,
                located = false,
            ),
        )

    fun sourceFileOf(scriptId: Int): File? = project.fileFor(scriptId).takeIf { it.isFile }

    fun sourceIds(): List<Int> = project.sourceIds()

    fun reloadSources() = project.reloadSymbols()

    companion object {

        fun fromSources(folder: File): Cs2Compiler =
            Cs2Compiler(folder) { Cs2SourceProject(Cs2SourceContext.read(folder), folder) }
    }
}

class Cs2CompileException(val diagnostic: Cs2SourceProject.Diagnostic) :
    RuntimeException(diagnostic.toString())
