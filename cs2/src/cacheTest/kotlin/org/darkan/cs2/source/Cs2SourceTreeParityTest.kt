package org.darkan.cs2.source

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.darkan.core.EnvVars
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.source.SourceFiles
import world.gregs.voidps.cache.source.SourceTree
import world.gregs.voidps.cache.source.Unpacker
import world.gregs.voidps.cache.source.Verifier
import world.gregs.voidps.cache.source.codec.SourceCodecs

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

            val keys = try {
                Unpacker.mapKeys()
            } catch (e: Exception) {
                println("no map keys (${e.message}); the encrypted map archives stay opaque")
                emptyMap()
            }
            val unpacked = Unpacker.unpack(cache, tree, keys)
            println("unpack: $unpacked")
            assertEquals(0, unpacked.pristine, "nothing in the tree keeps the shipped bytes any more")
            assertEquals(
                emptyList(),
                sidecars(tree.indexDirectory(Index.CLIENT_SCRIPTS)),
                "index ${Index.CLIENT_SCRIPTS} writes no pristine sidecar"
            )

            val report = Verifier.verify(tree, packed)
            println("verify: $report")
            for (index in report.indices) {
                if (index.different > 0 || index.missing > 0 || index.extra > 0 || !index.tableIdentical) {
                    println("  $index")
                }
            }
            println(
                "parity: ${report.identical} identical and ${report.recompiled} recompiled of " +
                    "${unpacked.archives} containers, " +
                    "${report.tables}/${report.indices.size} reference tables"
            )
            assertEquals(0, report.different, "containers that did not come back identical")
            assertEquals(0, report.missing, "archives the tree has and the cache does not")
            assertEquals(0, report.extra, "archives the cache has and the tree does not")
            assertEquals(
                unpacked.archives,
                report.identical + report.recompiled,
                "every archive has to verify, identically or as a recompiled clientscript"
            )

            val clientscripts = report.indices.first { it.index == Index.CLIENT_SCRIPTS }
            val scripts = cache.archives(Index.CLIENT_SCRIPTS).size
            println(
                "clientscripts: ${clientscripts.identical} identical, " +
                    "${clientscripts.recompiled} recompiled of $scripts"
            )
            assertEquals(0, clientscripts.different, "clientscripts that did not come back equivalent")
            assertEquals(
                scripts,
                clientscripts.identical + clientscripts.recompiled,
                "every clientscript has to verify, identically or recompiled"
            )
            assertEquals(report.recompiled, clientscripts.recompiled, "only index 12 may be recompiled")
            assertFalse(clientscripts.tableIdentical, "index 12's table carries the recompiled CRCs")
            assertEquals(
                report.indices.size - 1,
                report.tables,
                "index 12's is the only reference table allowed to differ"
            )
            assertFalse(report.versionTablesMatch, "the version table is derived from index 12's")
        } finally {
            Cs2SourceCodec.reset()
            cache.close()
            directory.deleteRecursively()
        }
    }

    private fun sidecars(directory: Path): List<String> =
        Files.walk(directory).use { stream ->
            stream.filter { Files.isRegularFile(it) && SourceFiles.isPristine(it.fileName.toString()) }
                .map { directory.relativize(it).toString() }
                .toList()
        }
}
