package org.darkan.tools.cache

import org.darkan.core.EnvVars
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.source.ArchiveMetadata
import world.gregs.voidps.cache.source.Builder
import world.gregs.voidps.cache.source.IndexMetadata
import world.gregs.voidps.cache.source.IndexNames
import world.gregs.voidps.cache.source.Packer
import world.gregs.voidps.cache.source.SourceTree
import world.gregs.voidps.cache.source.Unpacker
import world.gregs.voidps.cache.source.Verifier
import world.gregs.voidps.cache.source.codec.SourceCodec
import world.gregs.voidps.cache.source.codec.SourceCodecs
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.name
import kotlin.system.exitProcess

/**
 * `tools cache ...` - the cache source tree from the command line.
 *
 * The tree is the whole packed cache unpacked into one file per asset; `docs/cache-source.md` is
 * the contract it upholds. Everything here is a thin shell over
 * [world.gregs.voidps.cache.source]: [Unpacker] one way, [Packer] and [Builder] the other,
 * [Verifier] to prove the two agree byte for byte, and a read-only inventory for `status`.
 *
 * ```
 * cache unpack <tree> [packedDir]     unpack a packed cache (default CACHE_PATH) into a tree
 * cache pack <tree> <outDir>          full pack
 * cache build <tree> <outDir>         incremental pack (what the server runs on startup)
 * cache verify <tree> <packedDir>     container-level parity report against a packed cache
 * cache status <tree>                 codec per index, pristine counts, stale sidecars
 * ```
 *
 * Only `unpack` opens a packed cache, which is why `Main` dispatches this before it loads one.
 */
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

/** `cache unpack <tree> [packedDir]` - a packed cache into a source tree. */
private fun unpack(args: List<String>) {
    val treePath = args.getOrNull(0)
    if (treePath == null) {
        println("usage: cache unpack <tree> [packedDir]")
        return
    }
    val packed = args.getOrNull(1) ?: EnvVars.cachePath
    println("Unpacking ${File(packed).absolutePath} into ${File(treePath).absolutePath}")
    val tree = SourceTree.create(File(treePath))
    // The keys are the only thing the tree cannot derive: the `l`/`n` map archives are stored
    // decrypted with their key in the metadata, so an unpack without them leaves every encrypted
    // archive opaque and the tree becomes a worse copy of the cache than it should be.
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

/** `cache pack <tree> <outDir>` - every archive, from scratch. */
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

/** `cache build <tree> <outDir>` - what the server runs on startup. */
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

/** `cache verify <tree> <packedDir>` - the parity guarantee, as an exit code. */
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
    println("  containers   : ${report.identical} identical, ${report.different} different, " +
        "${report.missing} missing, ${report.extra} extra")
    println("  tables       : ${report.tables}/${report.indices.size} identical")
    println("  version table: ${if (report.versionTablesMatch) "matches" else "DIFFERS"}")
    println("  time         : ${report.millis} ms")
    if (report.parity) {
        val total = report.identical + report.indices.size
        println()
        println("Parity: every one of the $total containers came out byte for byte identical.")
        return
    }
    println()
    println("PARITY FAILED - the tree does not reproduce ${File(packed).absolutePath}.")
    exitProcess(1)
}

/**
 * `cache status <tree>` - what the tree holds and what it is still keeping original bytes for.
 *
 * The pristine count is the number of editable files whose codec could not reproduce the shipped
 * bytes, so a `.pristine` sidecar is carrying them; the goal is zero. A *stale* sidecar is one
 * whose editable file no longer hashes to the value `index.json` recorded, which is how a pack
 * knows to compile the edit rather than emit the original - it is reported here so an edit that
 * has not been packed yet is visible.
 */
private fun status(args: List<String>) {
    val treePath = args.getOrNull(0)
    if (treePath == null) {
        println("usage: cache status <tree>")
        return
    }
    val tree = open(treePath) ?: return
    println("$tree, format ${tree.format}, ${tree.indexCount} indices")
    val gamevals = if (Files.isDirectory(tree.gamevals)) {
        Files.list(tree.gamevals).use { stream -> stream.filter { it.name.endsWith(".json") }.count() }
    } else {
        -1L
    }
    println("gamevals: " + if (gamevals < 0) "MISSING (${tree.gamevals.toAbsolutePath()})" else "$gamevals catalogs")
    println()
    println(
        "%-3s %-21s %-15s %9s %9s %9s %7s %7s".format(
            "id", "index", "codec", "archives", "files", "pristine", "opaque", "stale"
        )
    )

    var archives = 0
    var files = 0L
    var pristine = 0
    var opaque = 0
    var stale = 0
    val staleExamples = ArrayList<String>()
    for (index in tree.indices()) {
        val metadata = tree.metadata(index)
        val directory = tree.indexDirectory(index)
        val codec = SourceCodecs.codec(index)
        val contents = walk(directory)
        val indexFiles = contents.files
        val indexPristine = metadata.archives.values.sumOf { it.pristine.size }
        val indexOpaque = metadata.archives.values.count { it.opaque }
        val indexStale = staleSidecars(directory, contents.sidecars, metadata, codec, staleExamples)
        archives += metadata.archives.size
        files += indexFiles
        pristine += indexPristine
        opaque += indexOpaque
        stale += indexStale
        println(
            "%-3d %-21s %-15s %9d %9d %9d %7d %7d".format(
                index, IndexNames.name(index), codec.id, metadata.archives.size,
                indexFiles, indexPristine, indexOpaque, indexStale
            )
        )
    }
    println(
        "%-3s %-21s %-15s %9d %9d %9d %7d %7d".format(
            "", "total", "", archives, files, pristine, opaque, stale
        )
    )
    for (example in staleExamples) {
        println("  stale: $example")
    }
    if (stale > staleExamples.size) {
        println("  ... and ${stale - staleExamples.size} more")
    }
}

/** What one walk of an index directory found. */
private class IndexContents(
    /** Every regular file but the index's own `index.json`. */
    val files: Long,
    val sidecars: List<Path>
)

/** One pass over an index directory: the file count and the `.pristine` sidecars, together. */
private fun walk(directory: Path): IndexContents {
    if (!Files.isDirectory(directory)) {
        return IndexContents(0, emptyList())
    }
    var files = 0L
    val sidecars = ArrayList<Path>()
    Files.walk(directory).use { stream ->
        for (path in stream) {
            if (!Files.isRegularFile(path)) {
                continue
            }
            if (path.name != SourceTree.INDEX_FILE) {
                files++
            }
            if (path.name.endsWith(PRISTINE_EXTENSION)) {
                sidecars.add(path)
            }
        }
    }
    return IndexContents(files, sidecars)
}

/**
 * How many of [sidecars] no longer apply: their guarded file does not hash to the value
 * `index.json` recorded for it.
 *
 * The sidecar sits beside the editable file it guards - `<name>.pristine` next to `<name>` - and
 * the hash is filed by file id, which only the codec's layout knows. Matching on the *set* of
 * hashes an archive recorded needs nothing from the layout but the archive the path belongs to,
 * which every codec answers with [SourceCodec.archiveOf].
 */
private fun staleSidecars(
    directory: Path,
    sidecars: List<Path>,
    metadata: IndexMetadata,
    codec: SourceCodec,
    examples: MutableList<String>
): Int {
    var stale = 0
    for (sidecar in sidecars) {
        val guarded = sidecar.resolveSibling(sidecar.name.dropLast(PRISTINE_EXTENSION.length))
        val relative = directory.relativize(guarded).joinToString("/") { it.name }
        val reason = staleReason(guarded, relative, metadata, codec)
        if (reason != null) {
            stale++
            if (examples.size < STALE_EXAMPLES) {
                examples.add("${directory.name}/$relative: $reason")
            }
        }
    }
    return stale
}

/** Why the sidecar guarding [guarded] no longer applies, or null when it still does. */
private fun staleReason(guarded: Path, relative: String, metadata: IndexMetadata, codec: SourceCodec): String? {
    if (!Files.isRegularFile(guarded)) {
        return "the file it guards is gone"
    }
    val archive = codec.archiveOf(relative) ?: return "belongs to no archive"
    val recorded: ArchiveMetadata = metadata.archive(archive) ?: return "archive $archive is not in index.json"
    if (recorded.pristine.isEmpty()) {
        return "archive $archive records no pristine hash"
    }
    val hash = try {
        sha256(guarded)
    } catch (e: IOException) {
        return "could not be read: ${e.message}"
    }
    if (!recorded.pristine.containsValue(hash)) {
        return "edited since it was unpacked; the pack will compile it"
    }
    return null
}

private fun sha256(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
    val out = StringBuilder(digest.size * 2)
    for (byte in digest) {
        val value = byte.toInt() and 0xff
        out.append(HEX[value shr 4]).append(HEX[value and 0xf])
    }
    return out.toString()
}

/** Open the tree at [path], or explain why it is not one. */
private fun open(path: String): SourceTree? {
    return try {
        SourceTree.open(File(path))
    } catch (e: Exception) {
        println("Not a cache source tree: ${e.message}")
        null
    }
}

/** How many stale sidecars are named individually. */
private const val STALE_EXAMPLES = 10

/** The sidecar a codec keeps the original bytes in when it cannot reproduce them. */
private const val PRISTINE_EXTENSION = ".pristine"

private val HEX = "0123456789abcdef".toCharArray()
