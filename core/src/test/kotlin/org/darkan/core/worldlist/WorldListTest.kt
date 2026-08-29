package org.darkan.core.worldlist

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WorldListTest {
    private fun metadata(number: Int, host: String) =
        WorldMetadata(number, host, 43601, "test", Country.USA, false, true, true, false, false)

    @Test
    fun `pinned local world survives registration and removal`() {
        val list = WorldList(300, localWorldId = 300)
        assertEquals("localhost", assertNotNull(list.get(300)).metadata.ipAddress)

        list.put(World(metadata(300, "someone.elses.host")))
        assertEquals("localhost", assertNotNull(list.get(300)).metadata.ipAddress)

        assertNull(list.remove(300))
        assertEquals("localhost", assertNotNull(list.get(300)).metadata.ipAddress)
    }

    @Test
    fun `other worlds register and unregister normally around the pinned one`() {
        val list = WorldList(300, localWorldId = 300)
        list.put(World(metadata(1, "xmr.darkan.org")))
        assertEquals(listOf(1, 300), list.getWorldArray().map { it.metadata.number })
        assertNotNull(list.remove(1))
        assertEquals(listOf(300), list.getWorldArray().map { it.metadata.number })
    }

    @Test
    fun `no local world when unset`() {
        val list = WorldList(300, localWorldId = 0)
        assertNull(list.get(300))
        assertNull(list.getDefault())
    }
}
