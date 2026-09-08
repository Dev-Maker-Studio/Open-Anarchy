package org.darkan.cs2.source

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.darkan.core.EnvVars
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.source.SourceTree
import world.gregs.voidps.cache.source.Unpacker
import world.gregs.voidps.cache.source.Verifier
import world.gregs.voidps.cache.source.codec.SourceCodecs

/**
 * The whole cache, unpacked and packed back with index 12 stored as TypeScript.
 *
 * [Cs2SourceCodecTest] proves index 12 on its own; this proves it inside the tree, driven by the
 * framework rather than by hand - [Unpacker] writes the tree with whatever codec each index
 * claims, and [Verifier] packs every archive back in memory and compares it to the packed cache
 * container by container. Parity means every one of the cache's containers, every reference
 * table and the derived version table came back byte for byte, with roughly a thousand
 * clientscripts riding on their `.pristine` sidecars to get there.
 *
 * Slow and large - it writes the whole cache out - and skipped with a message when there is no
 * cache to read.
 */
class Cs2SourceTreeParityTest {

    @Test
    fun `unpacking and packing the whole cache reproduces every container`() {
        val packed = File(EnvVars.prepackedCachePath)
        if (!File(packed, "main_file_cache.dat2").isFile) {
            println("Skipping: no cache at ${packed.absolutePath}")
            return
        }
        assertEquals(
            Cs2SourceCodec,
            SourceCodecs.codec(Index.CLIENT_SCRIPTS),
            "index ${Index.CLIENT_SCRIPTS} must be claimed by the provider before the tree is written"
        )

        val directory = File("cs2/build/tmp/cache-source-parity")
        directory.deleteRecursively()
        val cache: Cache = FileCache.load(packed.path)
        try {
            Cs2SourceCodec.cache = { cache }
            val tree = SourceTree.create(directory)

            // The map keys are the only thing the tree cannot derive; without them every
            // encrypted map archive would be stored opaque, which still verifies but proves less.
            val keys = try {
                Unpacker.mapKeys()
            } catch (e: Exception) {
                println("no map keys (${e.message}); the encrypted map archives stay opaque")
                emptyMap()
            }
            val unpacked = Unpacker.unpack(cache, tree, keys)
            println("unpack: $unpacked")

            val report = Verifier.verify(tree, packed)
            println("verify: $report")
            for (index in report.indices) {
                if (index.different > 0 || index.missing > 0 || index.extra > 0 || !index.tableIdentical) {
                    println("  $index")
                }
            }
            println(
                "parity: ${report.identical}/${unpacked.archives} containers, " +
                    "${report.tables}/${report.indices.size} reference tables"
            )
            assertEquals(0, report.different, "containers that did not come back identical")
            assertEquals(0, report.missing, "archives the tree has and the cache does not")
            assertEquals(0, report.extra, "archives the cache has and the tree does not")
            assertEquals(report.indices.size, report.tables, "reference tables that differ")
            assertTrue(report.versionTablesMatch, "the derived version table has to match")
            assertEquals(unpacked.archives, report.identical, "every archive has to verify")
        } finally {
            Cs2SourceCodec.reset()
            cache.close()
            directory.deleteRecursively()
        }
    }
}
