package world.gregs.voidps.cache.source

import world.gregs.voidps.cache.source.codec.SourceCodecs
import world.gregs.voidps.cache.store.ArchiveEntry
import world.gregs.voidps.cache.store.FileStore
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

class PackResult(
    val indices: Int,
    val archives: Int,
    val bytes: Long,
    val millis: Long
) {
    override fun toString(): String = "$indices indices, $archives archives, $bytes bytes, in ${millis}ms"
}

object Packer {

    fun pack(tree: SourceTree, output: File, report: (String) -> Unit = {}): PackResult {
        val start = System.nanoTime()
        val threads = PackThreads.count()
        val pool = Executors.newFixedThreadPool(threads) { runnable ->
            Thread(runnable, "cache-pack").apply { isDaemon = true }
        }
        val manifest = BuildManifest(tree.directory.toAbsolutePath().toString())
        var archives = 0
        var bytes = 0L
        var indices = 0
        try {
            clear(output)
            FileStore.create(output, tree.indexCount).use { store ->
                store.durable = false
                for (index in tree.indices()) {
                    val written = packIndex(tree, index, store, pool, manifest, threads, report)
                    archives += written.first
                    bytes += written.second
                    indices++
                }
            }
            manifest.files.putAll(BuildManifest.scan(tree, emptyMap(), pool).files)
            manifest.write(output)
        } finally {
            pool.shutdown()
        }
        return PackResult(indices, archives, bytes, (System.nanoTime() - start) / 1_000_000)
    }

    private fun packIndex(
        tree: SourceTree,
        index: Int,
        store: FileStore,
        pool: ExecutorService,
        manifest: BuildManifest,
        threads: Int,
        report: (String) -> Unit
    ): Pair<Int, Long> {
        val metadata = tree.metadata(index)
        val directory = tree.indexDirectory(index)
        val codec = SourceCodecs.codec(index)
        val ids = metadata.archiveIds()
        val entries = ArrayList<ArchiveEntry>(ids.size)
        val states = manifest.archives(index)
        var bytes = 0L
        if (ids.isNotEmpty()) {
            codec.packIndex(directory, index, ids, emptyList())
        }

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
            val packed = future.get()
            store.write(index, archive, packed.bytes)
            entries.add(packed.entry)
            states[archive] = ArchiveState(packed.entry.crc, SourceFiles.digest(metadata.archives.getValue(archive).write()))
            bytes += packed.bytes.size
        }

        val table = SourcePack.tableContainer(metadata, SourcePack.table(metadata, entries))
        store.write(FileStore.REFERENCE_INDEX, index, table)
        bytes += table.size
        report("${IndexNames.name(index)}: ${ids.size} archives, $bytes bytes")
        return ids.size to bytes
    }

    private fun clear(output: File) {
        output.mkdirs()
        for (file in output.listFiles() ?: emptyArray()) {
            if (file.isFile && (file.name.startsWith("${FileStore.CACHE_FILE_NAME}.") || file.name == BuildManifest.FILE)) {
                file.delete()
            }
        }
    }

    private const val PIPELINE = 8
}
