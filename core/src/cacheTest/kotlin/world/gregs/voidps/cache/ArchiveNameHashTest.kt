package world.gregs.voidps.cache

import org.darkan.core.EnvVars
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArchiveNameHashTest {

    @Test
    fun `client script index enumerates every archive with a name hash`() {
        val cache = cache() ?: return
        val ids = cache.archiveIds(Index.CLIENT_SCRIPTS)
        val hashes = cache.archiveNameHashes(Index.CLIENT_SCRIPTS)

        assertTrue(ids.isNotEmpty(), "expected client script archives")
        assertEquals(ids.size, hashes.size, "every archive should carry a name hash entry")
        assertEquals(ids.toSet(), hashes.keys, "hash table keys should be the archive ids")
        val named = hashes.filterValues { it != 0 }
        assertEquals(ids.size - 1, named.size)
        assertEquals(named.size, named.values.toSet().size, "name hashes should be unique")
        assertEquals(named.size, cache.archiveNameCount(Index.CLIENT_SCRIPTS))
        assertTrue(cache.hasNameFlag(Index.CLIENT_SCRIPTS))

        assertTrue(ids.asSequence().zipWithNext().all { (a, b) -> a < b }, "archive ids should ascend")
        val (archive, hash) = named.entries.first().let { it.key to it.value }
        assertEquals(archive, cache.archiveId(Index.CLIENT_SCRIPTS, hash))
    }

    @Test
    fun `item index has archives but no archive names`() {
        val cache = cache() ?: return
        assertTrue(cache.archiveIds(Index.ITEMS).isNotEmpty(), "expected item archives")
        assertTrue(cache.archiveNameHashes(Index.ITEMS).isEmpty(), "item archives are unnamed")
        assertEquals(0, cache.archiveNameCount(Index.ITEMS))
    }

    @Test
    fun `unknown index enumerates nothing`() {
        val cache = cache() ?: return
        assertTrue(cache.archiveIds(-1).isEmpty())
        assertTrue(cache.archiveIds(cache.indexCount() + 100).isEmpty())
        assertTrue(cache.archiveNameHashes(-1).isEmpty())
        assertTrue(cache.archiveNameHashes(cache.indexCount() + 100).isEmpty())
    }

    private fun cache(): Cache? {
        val directory = File(EnvVars.cachePath)
        if (!directory.isDirectory) {
            println("Skipping archive name hash test; no cache at ${directory.absolutePath}")
            return null
        }
        return Cache.get()
    }
}
