package world.gregs.voidps.cache.source

import org.darkan.core.EnvVars
import world.gregs.voidps.cache.secure.VersionTableBuilder
import world.gregs.voidps.cache.secure.Whirlpool
import world.gregs.voidps.cache.source.codec.SourceCodecs
import world.gregs.voidps.cache.store.ArchiveEntry
import world.gregs.voidps.cache.store.Container
import world.gregs.voidps.cache.store.FileStore
import world.gregs.voidps.cache.store.ReferenceTable
import java.io.File
import java.math.BigInteger
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** How one index compared. */
class IndexVerification(
    val index: Int,
    /** Containers that came out of the tree byte for byte as the packed cache holds them. */
    val identical: Int,
    val different: Int,
    /** Archives the tree has and the packed cache does not. */
    val missing: Int,
    /** Archives the packed cache has and the tree does not. */
    val extra: Int,
    val tableIdentical: Boolean,
    /** The first few differences, as `archive: what differs`. */
    val examples: List<String>
) {
    override fun toString(): String =
        "${IndexNames.name(index)}: $identical identical, $different different, $missing missing, " +
            "$extra extra, table ${if (tableIdentical) "identical" else "DIFFERS"}" +
            if (examples.isEmpty()) "" else " (${examples.joinToString("; ")})"
}

/** Container level parity between a source tree and a packed cache. */
class VerifyReport(
    val indices: List<IndexVerification>,
    val versionTablesMatch: Boolean,
    val millis: Long
) {
    val identical: Int
        get() = indices.sumOf { it.identical }

    val different: Int
        get() = indices.sumOf { it.different }

    val missing: Int
        get() = indices.sumOf { it.missing }

    val extra: Int
        get() = indices.sumOf { it.extra }

    val tables: Int
        get() = indices.count { it.tableIdentical }

    /** Whether every container, every reference table and the version table came out identical. */
    val parity: Boolean
        get() = different == 0 && missing == 0 && extra == 0 && tables == indices.size && versionTablesMatch

    override fun toString(): String =
        "$identical identical containers, $different different, $missing missing, $extra extra, " +
            "$tables/${indices.size} tables identical, version table ${if (versionTablesMatch) "matches" else "DIFFERS"}, " +
            "in ${millis}ms"
}

/**
 * Proves that packing a source tree reproduces a packed cache exactly.
 *
 * Nothing is written: each archive is rebuilt in memory and compared against the container the
 * packed cache holds, and each reference table the same way. The version table - which is derived
 * and signed rather than stored - is built from both sides and compared too, because that is the
 * one thing the client checks with a signature rather than a CRC.
 *
 * `unpack` then `verify` against the cache that was unpacked is the parity guarantee in
 * `docs/cache-source.md`, and is what the whole-cache test runs.
 */
object Verifier {

    /** Compare a fresh pack of [tree] against the packed cache in [packed]. */
    fun verify(
        tree: SourceTree,
        packed: File,
        exponent: BigInteger = BigInteger(EnvVars.js5RsaExponent),
        modulus: BigInteger = BigInteger(EnvVars.js5RsaModulus),
        report: (String) -> Unit = {}
    ): VerifyReport {
        val start = System.nanoTime()
        val threads = Runtime.getRuntime().availableProcessors()
        val pool = Executors.newFixedThreadPool(threads) { runnable ->
            Thread(runnable, "cache-verify").apply { isDaemon = true }
        }
        val results = ArrayList<IndexVerification>()
        var versionTablesMatch: Boolean
        try {
            FileStore.open(packed).use { store ->
                val treeTables = HashMap<Int, ByteArray>()
                for (index in tree.indices()) {
                    val verification = verifyIndex(tree, index, store, pool, threads, treeTables)
                    results.add(verification)
                    report(verification.toString())
                }
                versionTablesMatch = versionTable(tree, treeTables, exponent, modulus)
                    .contentEquals(packedVersionTable(store, exponent, modulus))
            }
        } finally {
            pool.shutdown()
        }
        return VerifyReport(results, versionTablesMatch, (System.nanoTime() - start) / 1_000_000)
    }

    private fun verifyIndex(
        tree: SourceTree,
        index: Int,
        store: FileStore,
        pool: ExecutorService,
        threads: Int,
        treeTables: MutableMap<Int, ByteArray>
    ): IndexVerification {
        val metadata = tree.metadata(index)
        val directory = tree.indexDirectory(index)
        val codec = SourceCodecs.codec(index)
        val ids = metadata.archiveIds()
        val entries = ArrayList<ArchiveEntry>(ids.size)
        val examples = ArrayList<String>()
        var identical = 0
        var different = 0
        var missing = 0

        val window = threads * PIPELINE
        val pending = ArrayDeque<Pair<Int, Future<PackedContainer>>>()
        var next = 0
        while (next < ids.size || pending.isNotEmpty()) {
            while (next < ids.size && pending.size < window) {
                val archive = ids[next++]
                val archiveMetadata = metadata.archives.getValue(archive)
                pending.addLast(
                    archive to pool.submit<PackedContainer> {
                        SourcePack.container(directory, archive, archiveMetadata, codec, metadata.whirlpool)
                    }
                )
            }
            val (archive, future) = pending.removeFirst()
            val built = future.get()
            entries.add(built.entry)
            val stored = store.read(index, archive)
            if (stored == null) {
                missing++
                if (examples.size < EXAMPLES) {
                    examples.add("$archive: not in the packed cache")
                }
            } else if (stored.contentEquals(built.bytes)) {
                identical++
            } else {
                different++
                if (examples.size < EXAMPLES) {
                    examples.add("$archive: ${difference(stored, built.bytes)}")
                }
            }
        }

        var extra = 0
        val storedTable = store.read(FileStore.REFERENCE_INDEX, index)
        if (storedTable != null) {
            val table = ReferenceTable.decode(Container.decode(storedTable).data())
            for (entry in table.archives) {
                if (!metadata.archives.containsKey(entry.id)) {
                    extra++
                    if (examples.size < EXAMPLES) {
                        examples.add("${entry.id}: not in the tree")
                    }
                }
            }
        }
        val treeTable = SourcePack.tableContainer(metadata, SourcePack.table(metadata, entries))
        treeTables[index] = treeTable
        return IndexVerification(
            index = index,
            identical = identical,
            different = different,
            missing = missing,
            extra = extra,
            tableIdentical = storedTable != null && storedTable.contentEquals(treeTable),
            examples = examples
        )
    }

    /** What differs between two containers, in the terms the metadata is written in. */
    private fun difference(stored: ByteArray, built: ByteArray): String {
        if (stored.isEmpty() || built.isEmpty()) {
            return "one side is empty"
        }
        if (stored[0] != built[0]) {
            return "metadata (compression ${stored[0]} against ${built[0]})"
        }
        if (stored.size == built.size) {
            val payload = stored.size - 2
            var identical = true
            for (offset in 0 until payload) {
                if (stored[offset] != built[offset]) {
                    identical = false
                    break
                }
            }
            if (identical) {
                return "trailer"
            }
        }
        return "payload (${stored.size} bytes against ${built.size})"
    }

    private fun versionTable(
        tree: SourceTree,
        tables: Map<Int, ByteArray>,
        exponent: BigInteger,
        modulus: BigInteger
    ): ByteArray {
        val builder = VersionTableBuilder(exponent, modulus, tree.indexCount)
        val whirlpool = Whirlpool()
        for (index in 0 until tree.indexCount) {
            val bytes = tables[index]
            if (bytes == null) {
                builder.skip(index)
                continue
            }
            builder.sector(index, bytes, whirlpool)
            val table = ReferenceTable.decode(Container.decode(bytes).data())
            if (table.format >= 6) {
                builder.revision(index, table.revision)
            }
        }
        return builder.build(whirlpool)
    }

    private fun packedVersionTable(store: FileStore, exponent: BigInteger, modulus: BigInteger): ByteArray {
        val indexCount = store.indexCount()
        val builder = VersionTableBuilder(exponent, modulus, indexCount)
        val whirlpool = Whirlpool()
        for (index in 0 until indexCount) {
            val bytes = store.read(FileStore.REFERENCE_INDEX, index)
            if (bytes == null) {
                builder.skip(index)
                continue
            }
            builder.sector(index, bytes, whirlpool)
            val table = ReferenceTable.decode(Container.decode(bytes).data())
            if (table.format >= 6) {
                builder.revision(index, table.revision)
            }
        }
        return builder.build(whirlpool)
    }

    private const val EXAMPLES = 5

    /** How many archives per thread may be built ahead of the comparison. */
    private const val PIPELINE = 8
}
