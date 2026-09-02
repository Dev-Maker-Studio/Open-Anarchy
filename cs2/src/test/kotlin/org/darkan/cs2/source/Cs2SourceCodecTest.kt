package org.darkan.cs2.source

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.darkan.core.EnvVars
import org.darkan.cs2.Cs2Corpus
import org.darkan.cs2.Cs2Declarations
import org.darkan.cs2.Cs2Layout
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.source.ArchiveMetadata
import world.gregs.voidps.cache.source.codec.SourceArchive
import world.gregs.voidps.cache.source.codec.SourceCodecs
import world.gregs.voidps.cache.store.ArchiveGroup

/**
 * The parity proof for index 12 as TypeScript.
 *
 * Every one of the 6,567 clientscripts is unpacked into a temporary directory through
 * [Cs2SourceCodec] and packed straight back out of it, and the bytes have to come back
 * *identical* - the 5,501 the compiler reproduces exactly because it reproduces them, and the
 * 1,066 it normalises because the codec kept the originals in a `.pristine` sidecar. That is
 * the whole contract the cache source tree rests on for this index (`docs/cache-source.md`).
 *
 * Skips with a message when there is no cache to read.
 */
class Cs2SourceCodecTest {

    @Test
    fun `every clientscript packs back to the bytes it was unpacked from`() {
        val cache = cache() ?: return
        try {
            Cs2SourceCodec.cache = { cache }
            val directory = Files.createTempDirectory("clientscripts")
            try {
                run(cache, directory)
            } finally {
                directory.toFile().deleteRecursively()
            }
        } finally {
            Cs2SourceCodec.reset()
            cache.close()
        }
    }

    private fun run(cache: Cache, directory: Path) {
        val ids = cache.archives(Index.CLIENT_SCRIPTS).sortedArray()
        assertTrue(ids.isNotEmpty(), "index ${Index.CLIENT_SCRIPTS} has no archives")

        // --- unpack -------------------------------------------------------------------------
        val metadata = HashMap<Int, ArchiveMetadata>(ids.size * 2)
        val groups = HashMap<Int, ByteArray>(ids.size * 2)
        val unpackStart = System.nanoTime()
        var pristine = 0
        for (id in ids) {
            val fileIds = cache.files(Index.CLIENT_SCRIPTS, id)
            val group = cache.data(Index.CLIENT_SCRIPTS, id, fileIds[0]) ?: continue
            groups[id] = group
            val split = ArchiveGroup.split(group, fileIds)
            val entry = ArchiveMetadata()
            entry.fileIds(fileIds)
            val unpacked = Cs2SourceCodec.unpack(
                directory,
                SourceArchive(
                    Index.CLIENT_SCRIPTS, id, null, fileIds, group, split.files, split.chunks, entry
                )
            )
            for (file in unpacked.files) {
                val path = directory.resolve(file.path)
                Files.createDirectories(path.parent)
                Files.write(path, file.bytes)
            }
            if (unpacked.pristine.isNotEmpty()) {
                entry.pristine = unpacked.pristine
                pristine++
            }
            metadata[id] = entry
        }
        val unpackMillis = (System.nanoTime() - unpackStart) / 1_000_000
        val exact = groups.size - pristine

        // --- the layout ---------------------------------------------------------------------
        for (name in listOf(
            Cs2Declarations.OPCODES_FILE,
            Cs2Declarations.GAMEVALS_FILE,
            Cs2Declarations.VARS_FILE,
            Cs2Corpus.TSCONFIG_FILE,
            Cs2Corpus.DEAD_CODE_FILE
        )) {
            assertTrue(Files.isRegularFile(directory.resolve(name)), "$name was not written")
        }
        for (id in ids) {
            val path = Cs2Layout.pathOf(id)
            assertTrue(Files.isRegularFile(directory.resolve(path)), "clientscript $id has no $path")
            assertEquals(id, Cs2SourceCodec.archiveOf(path), "$path does not map back to $id")
            assertEquals(id, Cs2SourceCodec.archiveOf(directory, path), "$path's header does not say $id")
        }

        // The project files are carried out of whichever archive is unpacked first - here the
        // lowest id, since this loop is sequential. Unpacking it again has to claim them again:
        // the unpacker deletes every file no archive claimed, so a second pass that dropped them
        // would delete the declarations.
        val first = ids[0]
        val fileIds = cache.files(Index.CLIENT_SCRIPTS, first)
        val group = groups.getValue(first)
        val split = ArchiveGroup.split(group, fileIds)
        val again = Cs2SourceCodec.unpack(
            directory,
            SourceArchive(
                Index.CLIENT_SCRIPTS, first, null, fileIds, group, split.files, split.chunks,
                ArchiveMetadata().apply { fileIds(fileIds) }
            )
        )
        assertTrue(
            again.files.any { it.path == Cs2Declarations.VARS_FILE },
            "re-unpacking archive $first has to carry the project files again"
        )

        // --- pack ---------------------------------------------------------------------------
        val packStart = System.nanoTime()
        var identical = 0
        val differing = ArrayList<Int>()
        for (id in ids) {
            val group = groups[id] ?: continue
            val packed = Cs2SourceCodec.pack(directory, id, metadata.getValue(id))
            if (packed.group.contentEquals(group)) {
                identical++
            } else {
                differing.add(id)
            }
        }
        val packMillis = (System.nanoTime() - packStart) / 1_000_000

        println("clientscripts: ${groups.size} archives")
        println("  exact       : $exact compiled back to the cache's own bytes")
        println("  pristine    : $pristine kept their original bytes beside the source")
        println("  unpack      : ${unpackMillis}ms (decompile, round-trip compile and the declarations)")
        println("  pack        : ${packMillis}ms (compile $exact, read $pristine sidecars)")
        assertEquals(emptyList(), differing.take(20), "archives that did not pack back identically")
        assertEquals(groups.size, identical, "every archive has to pack back byte for byte")
    }

    /** Index 12 claims [Cs2SourceCodec] by being on the classpath, with nothing registering it. */
    @Test
    fun `the provider claims index 12`() {
        assertEquals(Cs2SourceCodec, SourceCodecs.codec(Index.CLIENT_SCRIPTS))
        assertTrue(SourceCodecs.registered().contains(Index.CLIENT_SCRIPTS))
    }

    /** Project files belong to no archive, and a script's path resolves to its id. */
    @Test
    fun `paths map to the archives they belong to`() {
        for (name in listOf("cs2.d.ts", "gamevals.d.ts", "vars.d.ts", "tsconfig.json", "dead-code.md")) {
            assertEquals(null, Cs2SourceCodec.archiveOf(name), "$name should belong to no archive")
        }
        assertEquals(null, Cs2SourceCodec.archiveOf("proc/nothing_is_called_this.ts"))
        val known = Cs2Layout.pathOf(0)
        assertEquals(0, Cs2SourceCodec.archiveOf(known), "$known is clientscript 0")
        assertEquals(0, Cs2SourceCodec.archiveOf("${Cs2Layout.LEGACY_PREFIX}0.ts"))
    }

    /** The prepacked cache, opened directly, or null with a message when it is not there. */
    private fun cache(): Cache? {
        val path = File(EnvVars.prepackedCachePath)
        if (!File(path, "main_file_cache.dat2").isFile) {
            println("Skipping: no cache at ${path.absolutePath}")
            return null
        }
        return FileCache.load(path.path)
    }
}
