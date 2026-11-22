package org.darkan.world

import com.rs.utils.music.Biome
import com.rs.utils.music.BiomeMusic
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val RAT_A_TAT_TAT = 482

class MusicBanListTest {

    @Test
    fun `a banned song is in no dungeon, biome or boss fight list`() {
        assertTrue(RAT_A_TAT_TAT in BiomeMusic.banned)
        assertFalse(BiomeMusic.playable(RAT_A_TAT_TAT))
        assertFalse(RAT_A_TAT_TAT in BiomeMusic.bossSongs())
        Biome.entries.forEach { assertFalse(RAT_A_TAT_TAT in BiomeMusic.generatedSongs(it), "$it plays a banned song") }
    }
}
