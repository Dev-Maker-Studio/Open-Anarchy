package world.gregs.voidps.cache.store

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.darkan.core.EnvVars
import org.darkan.core.map.MapXteas
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import java.io.File
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Proves the store layer against the real 727 cache: every reference table and every container
 * in it has to come back out of [ReferenceTable], [Container] and [ArchiveGroup] byte for byte.
 *
 * bzip2 containers are counted and skipped for the recompression half of the check while
 * [world.gregs.voidps.cache.compress.BZip2Encoder] is still being ported - their decode, group
 * split and group join are still checked, as are their table CRCs.
 *
 * Skipped entirely when there is no cache to test against, like the other whole-cache tests.
 */
class CacheParityTest {

    @Test
    fun `every reference table re-encodes byte for byte`() {
        val directory = cacheDirectory() ?: return
        FileStore.open(directory).use { store ->
            var tables = 0
            for (index in 0 until store.indexCount()) {
                val bytes = store.read(FileStore.REFERENCE_INDEX, index) ?: continue
                val container = Container.decode(bytes)
                assertEquals(null, container.version, "index $index's table should have no version trailer")
                val decompressed = container.data()
                val table = ReferenceTable.decode(decompressed)
                assertContentEquals(
                    decompressed,
                    table.encode(),
                    "index $index's reference table (format ${table.format}, flags ${table.flags}) does not re-encode"
                )
                tables++
                println(
                    "index ${index.toString().padStart(2)} table: format=${table.format} revision=${table.revision} " +
                        "flags=${table.flags} archives=${table.archiveCount} " +
                        "files=${table.archives.sumOf { it.files.size }} compression=${container.compression}"
                )
            }
            assertEquals(store.indexCount(), tables, "every index should have a reference table")
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `every container and group re-encodes byte for byte`() {
        val directory = cacheDirectory() ?: return
        MapXteas.load()
        val failures = ConcurrentLinkedQueue<String>()
        val totals = Counts()
        val lines = ConcurrentLinkedQueue<String>()
        FileStore.open(directory).use { store ->
            newFixedThreadPoolContext(THREADS, "cache-parity").use { dispatcher ->
                runBlocking(dispatcher) {
                    supervisorScope {
                        for (index in 0 until store.indexCount()) {
                            launch {
                                val counts = checkIndex(store, index, failures)
                                lines.add(
                                    "index ${index.toString().padStart(2)}: ${counts.archives.get()} archives, " +
                                        "${counts.multiFile.get()} multi-file, ${counts.chunked.get()} multi-chunk, " +
                                        "${counts.encrypted.get()} encrypted, ${counts.recompressed.get()} recompressed, " +
                                        "${counts.skipped.get()} bzip2 skipped, ${counts.undecryptable.get()} unkeyed"
                                )
                                totals.add(counts)
                            }
                        }
                    }
                }
            }
        }
        for (line in lines.sorted()) {
            println(line)
        }
        println(
            "totals: ${totals.archives.get()} archives, ${totals.multiFile.get()} multi-file, " +
                "${totals.chunked.get()} multi-chunk, ${totals.encrypted.get()} encrypted, " +
                "${totals.recompressed.get()} recompressed byte-identical, ${totals.skipped.get()} bzip2 skipped, " +
                "${totals.undecryptable.get()} without a usable xtea key"
        )
        if (failures.isNotEmpty()) {
            fail("${failures.size} archives are not reproducible:\n  " + failures.take(EXAMPLES).joinToString("\n  "))
        }
        assertTrue(totals.archives.get() > 100_000, "the cache should have far more archives than that")
        assertTrue(totals.recompressed.get() > 100_000, "most containers should have been recompressed")
        assertTrue(
            totals.undecryptable.get() <= UNKEYED_LIMIT,
            "${totals.undecryptable.get()} archives could not be decrypted, more than the $UNKEYED_LIMIT known bad keys"
        )
    }

    private fun checkIndex(store: FileStore, index: Int, failures: MutableCollection<String>): Counts {
        val counts = Counts()
        val tableBytes = store.read(FileStore.REFERENCE_INDEX, index) ?: return counts
        val table = ReferenceTable.decode(Container.decode(tableBytes).data())
        val keysByArchive = if (index == Index.MAPS) mapKeys(table) else emptyMap()
        for (entry in table.archives) {
            val bytes = store.read(index, entry.id) ?: continue
            counts.archives.incrementAndGet()
            val keys = keysByArchive[entry.id]
            try {
                // The container's own bytes are checked without keys: encryption is symmetric,
                // so a decode/encode round trip has to be the identity either way, and the
                // table's crc and whirlpool cover the bytes as stored.
                val stored = Container.decode(bytes)
                assertEquals(
                    entry.version and 0xffff,
                    stored.version,
                    "the container trailer should be the low 16 bits of the table version"
                )
                assertContentEquals(bytes, stored.encode(), "the container does not re-encode")
                assertEquals(entry.crc, stored.crc(), "the container crc should match the table")
                if (table.whirlpool) {
                    assertContentEquals(
                        entry.whirlpool,
                        stored.whirlpool(),
                        "the container whirlpool should match the table"
                    )
                }

                val container = if (keys == null) stored else Container.decode(bytes, keys)
                val group = try {
                    container.data()
                } catch (e: Throwable) {
                    // Eight index 5 archives are encrypted with keys nobody has - they are not
                    // in xteaKeys.json - so their plaintext cannot be reached to check the group
                    // or the recompression. The container's own bytes are checked above.
                    counts.undecryptable.incrementAndGet()
                    continue
                }
                if (keys != null) {
                    counts.encrypted.incrementAndGet()
                }
                val ids = entry.fileIds
                if (ids.size > 1) {
                    counts.multiFile.incrementAndGet()
                }
                val split = ArchiveGroup.split(group, ids)
                if (split.chunks.size > 1) {
                    counts.chunked.incrementAndGet()
                }
                assertEquals(ids.size, split.files.size)
                assertContentEquals(group, ArchiveGroup.join(split.files, split.chunks), "the group does not rejoin")

                if (container.compression == Compression.BZIP2) {
                    counts.skipped.incrementAndGet()
                } else {
                    val rebuilt = Container.compress(group, container.compression, container.version, container.gzipOs)
                    assertContentEquals(bytes, rebuilt.encode(keys), "the container does not recompress")
                    counts.recompressed.incrementAndGet()
                }
            } catch (e: Throwable) {
                failures.add("index $index archive ${entry.id}: ${e.message}")
            }
        }
        return counts
    }

    /**
     * XTEA keys per index 5 archive. The keys are filed by region, and an archive's region is
     * only recoverable from its name hash, so the `l` and `n` names are rehashed to find them.
     */
    private fun mapKeys(table: ReferenceTable): Map<Int, IntArray> {
        val byHash = HashMap<Int, Int>(table.archiveCount)
        for (entry in table.archives) {
            byHash[entry.nameHash] = entry.id
        }
        val keys = HashMap<Int, IntArray>()
        for (prefix in ENCRYPTED_MAP_PREFIXES) {
            for (x in 0 until 256) {
                for (y in 0 until 256) {
                    val archive = byHash["$prefix${x}_$y".hashCode()] ?: continue
                    val region = MapXteas.getKeys((x shl 8) or y) ?: continue
                    keys[archive] = region
                }
            }
        }
        return keys
    }

    @Test
    fun `a writable cache rewrites archives, tables and the version table`() {
        val source = cacheDirectory() ?: return
        val directory = Files.createTempDirectory("cache-parity-write").toFile()
        try {
            copy(source, directory)
            val exponent = BigInteger(EnvVars.js5RsaExponent)
            val modulus = BigInteger(EnvVars.js5RsaModulus)

            val itemArchive = 0
            val itemFile = 3
            val original: ByteArray
            val originalVersion: Int
            val originalCrc: Int
            val originalIndexCrcs: IntArray
            val originalVersionTable: ByteArray
            val newArchive: Int

            val before = FileCache.load(directory.absolutePath, exponent, modulus)
            try {
                original = assertNotNull(before.data(Index.ITEMS, itemArchive, itemFile))
                originalVersion = before.archiveVersion(Index.ITEMS, itemArchive)
                originalCrc = before.archiveCrc(Index.ITEMS, itemArchive)
                originalIndexCrcs = before.indexCrcs().copyOf()
                originalVersionTable = before.versionTable.copyOf()
                newArchive = before.lastArchiveId(Index.MODELS) + 1
                assertEquals(-1, before.archiveVersion(Index.MODELS, newArchive), "the new archive should not exist yet")
            } finally {
                before.close()
            }

            val edited = original.copyOf()
            edited[0] = (edited[0] + 1).toByte()
            val model = ByteArray(600) { ((it * 13) + 5).toByte() }

            val writable = FileCache.load(directory.absolutePath, exponent, modulus, writable = true)
            try {
                writable.write(Index.ITEMS, itemArchive, itemFile, edited)
                writable.write(Index.MODELS, newArchive, 0, model)
                assertContentEquals(edited, writable.data(Index.ITEMS, itemArchive, itemFile))
                assertContentEquals(model, writable.data(Index.MODELS, newArchive, 0))
                assertTrue(!originalVersionTable.contentEquals(writable.versionTable))
            } finally {
                writable.close()
            }

            val reopened = FileCache.load(directory.absolutePath, exponent, modulus)
            try {
                assertContentEquals(
                    edited,
                    reopened.data(Index.ITEMS, itemArchive, itemFile),
                    "the edited file should have persisted"
                )
                assertContentEquals(model, reopened.data(Index.MODELS, newArchive, 0), "the new archive should have persisted")
                // Everything else in the edited archive must have survived the repack.
                for (file in reopened.files(Index.ITEMS, itemArchive)) {
                    assertNotNull(reopened.data(Index.ITEMS, itemArchive, file), "file $file went missing")
                }

                assertEquals(
                    originalVersion + 1,
                    reopened.archiveVersion(Index.ITEMS, itemArchive),
                    "the version should have been bumped"
                )
                assertNotEquals(originalCrc, reopened.archiveCrc(Index.ITEMS, itemArchive), "the crc should have changed")
                assertEquals(1, reopened.archiveVersion(Index.MODELS, newArchive), "a new archive starts at version 1")
                assertContentEquals(intArrayOf(0), reopened.files(Index.MODELS, newArchive))

                // The reference table has to describe the containers that are actually there.
                FileStore.open(directory).use { store ->
                    val container = Container.decode(assertNotNull(store.read(Index.ITEMS, itemArchive)))
                    assertEquals(reopened.archiveCrc(Index.ITEMS, itemArchive), container.crc())
                    assertEquals(reopened.archiveVersion(Index.ITEMS, itemArchive) and 0xffff, container.version)
                    val added = Container.decode(assertNotNull(store.read(Index.MODELS, newArchive)))
                    assertContentEquals(model, added.data())
                    assertEquals(reopened.archiveCrc(Index.MODELS, newArchive), added.crc())
                }

                val crcs = reopened.indexCrcs()
                assertNotEquals(originalIndexCrcs[Index.ITEMS], crcs[Index.ITEMS], "index 19's table crc should have changed")
                assertNotEquals(originalIndexCrcs[Index.MODELS], crcs[Index.MODELS], "index 7's table crc should have changed")
                assertEquals(originalIndexCrcs[Index.MAPS], crcs[Index.MAPS], "untouched indices keep their crc")
                assertTrue(reopened.versionTable.isNotEmpty())
                assertTrue(!originalVersionTable.contentEquals(reopened.versionTable), "the version table should have changed")
            } finally {
                reopened.close()
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun copy(from: File, to: File) {
        to.mkdirs()
        val names = from.listFiles { file -> file.name.startsWith("${FileStore.CACHE_FILE_NAME}.") } ?: emptyArray()
        assertTrue(names.isNotEmpty(), "no cache files in ${from.absolutePath}")
        for (file in names) {
            Files.copy(file.toPath(), File(to, file.name).toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun cacheDirectory(): File? {
        val directory = File(EnvVars.cachePath)
        if (!File(directory, "${FileStore.CACHE_FILE_NAME}.dat2").exists()) {
            println("Skipping cache parity; no cache at ${directory.absolutePath}")
            return null
        }
        return directory
    }

    private class Counts {
        val archives = AtomicInteger()
        val multiFile = AtomicInteger()
        val chunked = AtomicInteger()
        val encrypted = AtomicInteger()
        val recompressed = AtomicInteger()
        val skipped = AtomicInteger()
        val undecryptable = AtomicInteger()

        fun add(other: Counts) {
            archives.addAndGet(other.archives.get())
            multiFile.addAndGet(other.multiFile.get())
            chunked.addAndGet(other.chunked.get())
            encrypted.addAndGet(other.encrypted.get())
            recompressed.addAndGet(other.recompressed.get())
            skipped.addAndGet(other.skipped.get())
            undecryptable.addAndGet(other.undecryptable.get())
        }
    }

    private companion object {
        private const val THREADS = 8
        private const val EXAMPLES = 20

        /** Map archives `data/map/xteaKeys.json` has no working key for. Eight, as of 727. */
        private const val UNKEYED_LIMIT = 16

        /** The two index 5 archive families the client is sent keys for. */
        private val ENCRYPTED_MAP_PREFIXES = listOf("l", "n")
    }
}
