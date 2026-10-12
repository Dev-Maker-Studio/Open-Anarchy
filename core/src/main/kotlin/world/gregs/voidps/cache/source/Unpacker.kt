package world.gregs.voidps.cache.source

import org.darkan.core.EnvVars
import org.darkan.core.map.MapXteas
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.MapSquare
import world.gregs.voidps.cache.source.codec.SourceArchive
import world.gregs.voidps.cache.source.codec.SourceCodec
import world.gregs.voidps.cache.source.codec.SourceCodecs
import world.gregs.voidps.cache.store.ArchiveEntry
import world.gregs.voidps.cache.store.ArchiveGroup
import world.gregs.voidps.cache.store.BZip2Variant
import world.gregs.voidps.cache.store.Compression
import world.gregs.voidps.cache.store.Container
import world.gregs.voidps.cache.store.FileStore
import world.gregs.voidps.cache.store.ReferenceTable
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.name

class UnpackReport(
    val indices: Int,
    val archives: Int,
    val files: Int,
    val written: Int,
    val deleted: Int,
    val opaque: Int,
    val inexact: Int,
    val pristine: Int,
    val missing: Int,
    val bytes: Long,
    val millis: Long
) {
    override fun toString(): String =
        "$archives archives, $files files ($bytes bytes), $written written, $deleted deleted, " +
            "$opaque opaque, $pristine pristine, $inexact inexact, $missing missing, in ${millis}ms"
}

object Unpacker {

    fun unpack(
        cache: Cache,
        tree: SourceTree,
        xteas: Map<Int, IntArray> = emptyMap(),
        threads: Int = Runtime.getRuntime().availableProcessors(),
        report: (String) -> Unit = {}
    ): UnpackReport {
        val start = System.nanoTime()
        SourceFiles.write(tree.cacheFile, tree.describe().toByteArray(Charsets.UTF_8))
        val gamevals = copyGamevals(tree)
        if (gamevals > 0) {
            report("gamevals: $gamevals catalogs")
        }
        val counts = Counts()
        val pool = Executors.newFixedThreadPool(maxOf(threads, 1)) { runnable ->
            Thread(runnable, "cache-unpack").apply { isDaemon = true }
        }
        try {
            for (index in 0 until cache.indexCount()) {
                val table = cache.referenceTable(index) ?: continue
                unpackIndex(cache, tree, index, table, xteas, pool, counts, report)
                counts.indices.incrementAndGet()
            }
        } finally {
            pool.shutdown()
        }
        return counts.report(System.nanoTime() - start)
    }

    fun mapKeys(): Map<Int, IntArray> {
        if (!MapXteas.isLoaded()) {
            MapXteas.load()
        }
        val keys = HashMap<Int, IntArray>()
        for (region in MapXteas.getEncryptedRegionIds()) {
            keys[region] = MapXteas.getKeys(region) ?: continue
        }
        return keys
    }

    private fun unpackIndex(
        cache: Cache,
        tree: SourceTree,
        index: Int,
        table: ReferenceTable,
        xteas: Map<Int, IntArray>,
        pool: ExecutorService,
        counts: Counts,
        report: (String) -> Unit
    ) {
        val directory = tree.indexDirectory(index)
        Files.createDirectories(directory)
        val metadata = IndexMetadata(
            index = index,
            format = table.format,
            revision = table.revision,
            named = table.named,
            whirlpool = table.whirlpool
        )
        val tableBytes = cache.container(FileStore.REFERENCE_INDEX, index)
        if (tableBytes != null) {
            val container = Container.decode(tableBytes)
            metadata.compression = container.compression
            metadata.bzip2 = variant(container, container.data())
                ?: throw IllegalStateException("Index $index's reference table cannot be recompressed by any known bzip2.")
        }

        val names = if (index == Index.MAPS) mapNames(cache, table) else emptyMap()
        val codec = SourceCodecs.codec(index)
        val archives = ConcurrentHashMap<Int, ArchiveMetadata>(table.archiveCount)
        val expected = ConcurrentHashMap.newKeySet<String>()
        val futures = table.archives.map { entry ->
            pool.submit {
                val archive = unpackArchive(cache, directory, index, entry, names, xteas, codec, counts, expected)
                if (archive != null) {
                    archives[entry.id] = archive
                }
            }
        }
        for (future in futures) {
            future.get()
        }
        metadata.archives.putAll(archives)

        for (file in codec.unpackIndex(directory, index, archives.keys.sorted().toIntArray())) {
            expected.add(file.path)
            counts.files.incrementAndGet()
            counts.bytes.addAndGet(file.bytes.size.toLong())
            if (SourceFiles.write(directory.resolve(file.path), file.bytes)) {
                counts.written.incrementAndGet()
            }
        }

        expected.add(SourceTree.INDEX_FILE)
        if (tree.writeMetadata(metadata)) {
            counts.written.incrementAndGet()
        }
        counts.files.incrementAndGet()
        val deleted = prune(directory, expected)
        counts.deleted.addAndGet(deleted)
        report(
            "${IndexNames.name(index)}: ${archives.size} archives, ${expected.size - 1} files" +
                if (deleted > 0) ", $deleted deleted" else ""
        )
    }

    private fun unpackArchive(
        cache: Cache,
        directory: Path,
        index: Int,
        entry: ArchiveEntry,
        names: Map<Int, String>,
        xteas: Map<Int, IntArray>,
        codec: SourceCodec,
        counts: Counts,
        expected: MutableSet<String>
    ): ArchiveMetadata? {
        val bytes = cache.container(index, entry.id)
        if (bytes == null) {
            counts.missing.incrementAndGet()
            return null
        }
        counts.archives.incrementAndGet()
        val name = names[entry.id]
        val keys = if (name != null) xteas[regionOf(name)] else null
        val container = Container.decode(bytes, keys)
        val metadata = ArchiveMetadata(
            version = entry.version,
            compression = container.compression,
            gzipOs = container.gzipOs,
            nameHash = entry.nameHash
        )
        if (keys != null) {
            metadata.xtea = keys
        }
        val fileNames = entry.files.filter { it.nameHash != 0 }
        if (fileNames.isNotEmpty()) {
            metadata.fileNames = fileNames.associate { it.id to it.nameHash }
        }

        val group = try {
            container.data()
        } catch (e: Exception) {
            return opaque(directory, entry, bytes, metadata, counts, expected)
        }
        val bzip2 = variant(container, group)
            ?: return opaque(directory, entry, bytes, metadata, counts, expected)
        metadata.bzip2 = bzip2

        if (codec.fileIdsFromMetadata(entry.id)) {
            metadata.fileIds(entry.fileIds)
        }
        val split = ArchiveGroup.split(group, entry.fileIds)
        val source = SourceArchive(index, entry.id, name, entry.fileIds, group, split.files, split.chunks, metadata)
        val unpacked = codec.unpack(directory, source)
        if (unpacked.pristine.isNotEmpty()) {
            metadata.pristine = unpacked.pristine
            counts.pristine.addAndGet(unpacked.pristine.size)
        }
        for (file in unpacked.files) {
            expected.add(file.path)
            counts.files.incrementAndGet()
            counts.bytes.addAndGet(file.bytes.size.toLong())
            if (SourceFiles.write(directory.resolve(file.path), file.bytes)) {
                counts.written.incrementAndGet()
            }
        }
        if (!codec.exact(entry.id) && !codec.pack(directory, entry.id, metadata).group.contentEquals(group)) {
            counts.inexact.incrementAndGet()
        }
        return metadata
    }

    private fun opaque(
        directory: Path,
        entry: ArchiveEntry,
        bytes: ByteArray,
        metadata: ArchiveMetadata,
        counts: Counts,
        expected: MutableSet<String>
    ): ArchiveMetadata {
        metadata.opaque = true
        metadata.xtea = null
        metadata.gzipOs = 0
        metadata.fileIds(entry.fileIds)
        val trailer = if (bytes.size >= 2 && (entry.version and 0xffff) ==
            (((bytes[bytes.size - 2].toInt() and 0xff) shl 8) or (bytes[bytes.size - 1].toInt() and 0xff))
        ) 2 else 0
        val path = "${entry.id}${SourceTree.OPAQUE_EXTENSION}"
        val payload = bytes.copyOf(bytes.size - trailer)
        expected.add(path)
        counts.opaque.incrementAndGet()
        counts.files.incrementAndGet()
        counts.bytes.addAndGet(payload.size.toLong())
        if (SourceFiles.write(directory.resolve(path), payload)) {
            counts.written.incrementAndGet()
        }
        return metadata
    }

    private fun variant(container: Container, data: ByteArray): BZip2Variant? {
        if (container.compression != Compression.BZIP2) {
            return BZip2Variant.LIBBZIP2
        }
        for (variant in BZip2Variant.entries) {
            val payload = Container.compress(data, Compression.BZIP2, container.version, 0, variant).payload
            if (payload.contentEquals(container.payload)) {
                return variant
            }
        }
        return null
    }

    private fun mapNames(cache: Cache, table: ReferenceTable): Map<Int, String> {
        val byHash = HashMap<Int, Int>(table.archiveCount * 2)
        for (entry in table.archives) {
            byHash[entry.nameHash] = entry.id
        }
        val names = HashMap<Int, String>(table.archiveCount * 2)
        for (regionX in 0 until REGIONS) {
            for (regionY in 0 until REGIONS) {
                for (name in listOf(MapSquare.locations(regionX, regionY), MapSquare.npcs(regionX, regionY))) {
                    val archive = byHash[name.hashCode()] ?: continue
                    names[archive] = name
                }
            }
        }
        return names
    }

    private fun regionOf(name: String): Int {
        val separator = name.indexOf('_')
        val regionX = name.substring(1, separator).toInt()
        val regionY = name.substring(separator + 1).toInt()
        return (regionX shl 8) or regionY
    }

    private fun prune(directory: Path, expected: Set<String>): Int {
        var deleted = 0
        val stale = ArrayList<Path>()
        Files.walk(directory).use { stream ->
            for (path in stream) {
                if (!Files.isRegularFile(path)) {
                    continue
                }
                val relative = directory.relativize(path).joinToString("/") { it.name }
                if (!expected.contains(relative)) {
                    stale.add(path)
                }
            }
        }
        for (path in stale) {
            SourceFiles.delete(path, directory)
            deleted++
        }
        return deleted
    }

    private fun copyGamevals(tree: SourceTree): Int {
        val source = File(EnvVars.gamevalPath)
        if (!source.isDirectory) {
            return 0
        }
        var copied = 0
        for (file in source.listFiles() ?: emptyArray()) {
            if (!file.isFile || (!file.name.endsWith(".json") && file.name != CONTRACT)) {
                continue
            }
            SourceFiles.write(tree.gamevals.resolve(file.name), file.readBytes())
            copied++
        }
        return copied
    }

    private const val CONTRACT = "CONTRACT.md"

    private const val REGIONS = 256

    private class Counts {
        val indices = AtomicInteger()
        val archives = AtomicInteger()
        val files = AtomicInteger()
        val written = AtomicInteger()
        val deleted = AtomicInteger()
        val opaque = AtomicInteger()
        val inexact = AtomicInteger()
        val pristine = AtomicInteger()
        val missing = AtomicInteger()
        val bytes = AtomicLong()

        fun report(nanos: Long) = UnpackReport(
            indices.get(), archives.get(), files.get(), written.get(), deleted.get(),
            opaque.get(), inexact.get(), pristine.get(), missing.get(), bytes.get(), nanos / 1_000_000
        )
    }
}
