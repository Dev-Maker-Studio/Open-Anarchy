package org.darkan.tools.cache

import org.darkan.core.EnvVars
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.source.Builder
import world.gregs.voidps.cache.source.Packer
import world.gregs.voidps.cache.source.SourceTree
import world.gregs.voidps.cache.source.Unpacker
import world.gregs.voidps.cache.source.Verifier
import java.io.File
import kotlin.system.exitProcess

fun runCache(args: List<String>) {
    when (args.firstOrNull()?.lowercase()) {
        "unpack" -> unpack(args.drop(1))
        "pack" -> pack(args.drop(1))
        "build" -> build(args.drop(1))
        "verify" -> verify(args.drop(1))
        "status" -> status(args.drop(1))
        else -> printCacheUsage()
    }
}

fun printCacheUsage() {
    println(
        """
        cache unpack <tree> [packedDir]  Unpack a packed cache into a source tree.
                                         packedDir defaults to CACHE_PATH (${EnvVars.cachePath}).
        cache pack <tree> <outDir>       Pack a source tree into a cache, from scratch.
        cache build <tree> <outDir>      Rebuild a cache, repacking only what changed.
        cache verify <tree> <packedDir>  Prove packing <tree> reproduces <packedDir> exactly.
                                         Exits 1 on any difference.
        cache status <tree>              Per index: codec, archives, files, pristine, opaque.
        """.trimIndent()
    )
}

private fun unpack(args: List<String>) {
    val treePath = args.getOrNull(0)
    if (treePath == null) {
        println("usage: cache unpack <tree> [packedDir]")
        return
    }
    val packed = args.getOrNull(1) ?: EnvVars.cachePath
    println("Unpacking ${File(packed).absolutePath} into ${File(treePath).absolutePath}")
    val tree = SourceTree.create(File(treePath))
    val keys = Unpacker.mapKeys()
    println("map keys: ${keys.size} regions")
    val cache = FileCache.load(packed)
    val report = try {
        Unpacker.unpack(cache, tree, keys) { println("  $it") }
    } finally {
        cache.close()
    }
    println()
    println("  indices  : ${report.indices}")
    println("  archives : ${report.archives}")
    println("  files    : ${report.files} (${report.bytes} bytes)")
    println("  written  : ${report.written}")
    println("  deleted  : ${report.deleted}")
    println("  opaque   : ${report.opaque}")
    println("  pristine : ${report.pristine} files, ${report.inexact} archives")
    println("  missing  : ${report.missing}")
    println("  time     : ${report.millis} ms")
}

private fun pack(args: List<String>) {
    val treePath = args.getOrNull(0)
    val output = args.getOrNull(1)
    if (treePath == null || output == null) {
        println("usage: cache pack <tree> <outDir>")
        return
    }
    val tree = open(treePath) ?: return
    println("Packing $tree into ${File(output).absolutePath}")
    val result = Packer.pack(tree, File(output)) { println("  $it") }
    println()
    println("  indices  : ${result.indices}")
    println("  archives : ${result.archives}")
    println("  bytes    : ${result.bytes}")
    println("  time     : ${result.millis} ms")
}

private fun build(args: List<String>) {
    val treePath = args.getOrNull(0)
    val output = args.getOrNull(1)
    if (treePath == null || output == null) {
        println("usage: cache build <tree> <outDir>")
        return
    }
    val tree = open(treePath) ?: return
    println("Building $tree into ${File(output).absolutePath}")
    val result = Builder.build(tree, File(output)) { println("  $it") }
    println()
    if (result.full) {
        println("  full pack     : ${result.reason}")
    } else {
        println("  incremental   : ${if (result.unchanged) "nothing changed" else "patched"}")
    }
    println("  changed files : ${result.changedFiles} (${result.hashedFiles} hashed)")
    println("  archives      : ${result.archives} repacked, ${result.removed} removed")
    println("  tables        : ${result.indices} rewritten")
    println("  time          : ${result.millis} ms")
}

private fun verify(args: List<String>) {
    val treePath = args.getOrNull(0)
    val packed = args.getOrNull(1)
    if (treePath == null || packed == null) {
        println("usage: cache verify <tree> <packedDir>")
        return
    }
    val tree = open(treePath) ?: return
    println("Verifying $tree against ${File(packed).absolutePath}")
    val report = Verifier.verify(tree, File(packed), report = { println("  $it") })
    println()
    println("  containers   : ${report.identical} identical, ${report.recompiled} recompiled, " +
        "${report.different} different, ${report.missing} missing, ${report.extra} extra")
    println("  tables       : ${report.tables}/${report.indices.size} identical" +
        if (report.tablesRecompiled > 0) ", ${report.tablesRecompiled} recompiled" else "")
    println("  version table: " + when {
        report.versionTablesMatch -> "matches"
        report.versionTableAccountedFor -> "recompiled"
        else -> "DIFFERS"
    })
    println("  time         : ${report.millis} ms")
    if (report.parity) {
        val total = report.identical + report.recompiled + report.indices.size
        println()
        if (report.recompiled == 0) {
            println("Parity: every one of the $total containers came out byte for byte identical.")
        } else {
            println(
                "Parity: $total containers and tables, byte for byte except the ${report.recompiled} " +
                    "clientscripts the compiler reproduces equivalently, the reference table carrying " +
                    "their CRCs and the version table derived from it."
            )
        }
        return
    }
    println()
    println("PARITY FAILED - the tree does not reproduce ${File(packed).absolutePath}.")
    exitProcess(1)
}

private fun status(args: List<String>) {
    val treePath = args.getOrNull(0)
    if (treePath == null) {
        println("usage: cache status <tree>")
        return
    }
    val tree = open(treePath) ?: return
    val status = tree.status()
    println("$tree, format ${tree.format}, ${tree.indexCount} indices")
    println(
        "gamevals: " + if (status.gamevals < 0) {
            "MISSING (${tree.gamevals.toAbsolutePath()})"
        } else {
            "${status.gamevals} catalogs"
        }
    )
    println()
    println(
        "%-3s %-21s %-15s %9s %9s %9s %7s %7s".format(
            "id", "index", "codec", "archives", "files", "pristine", "opaque", "stale"
        )
    )
    for (index in status.indices) {
        println(
            "%-3d %-21s %-15s %9d %9d %9d %7d %7d".format(
                index.index, index.name, index.codec, index.archives, index.files,
                index.pristine, index.opaque, index.stale.size
            )
        )
    }
    println(
        "%-3s %-21s %-15s %9d %9d %9d %7d %7d".format(
            "", "total", "", status.archives, status.files, status.pristine, status.opaque,
            status.stale.size
        )
    )
    val stale = status.stale
    for (example in stale.take(STALE_EXAMPLES)) {
        println("  stale: $example")
    }
    if (stale.size > STALE_EXAMPLES) {
        println("  ... and ${stale.size - STALE_EXAMPLES} more")
    }
}

private fun open(path: String): SourceTree? {
    return try {
        SourceTree.open(File(path))
    } catch (e: Exception) {
        println("Not a cache source tree: ${e.message}")
        null
    }
}

private const val STALE_EXAMPLES = 10
