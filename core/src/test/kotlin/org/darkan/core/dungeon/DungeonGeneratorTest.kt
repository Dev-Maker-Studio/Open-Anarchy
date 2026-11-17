package org.darkan.core.dungeon

import org.darkan.core.city.Side
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.Test

class DungeonGeneratorTest {

    private fun dungeon(theme: DungeonTheme, seed: Long, slot: Int = 0) = Dungeon("test$seed${theme.id}$slot", "Test", theme.id, seed, slot, 7000, 7000, "biome:test")

    private fun reachable(layout: DungeonLayout): Set<Int> {
        val blocked = layout.pieces.filter { it.shape == DungeonLayout.SCENERY_SHAPE }.mapTo(HashSet()) { DungeonLayout.index(it.x, it.y) }
        val seen = hashSetOf(DungeonLayout.index(layout.arrivalX, layout.arrivalY))
        val queue = ArrayDeque(seen)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (side in Side.entries) {
                val x = current / DungeonGrid.SIZE + side.dx
                val y = current % DungeonGrid.SIZE + side.dy
                val next = DungeonLayout.index(x, y)
                if (layout.floorAt(x, y) && next !in blocked && seen.add(next)) queue += next
            }
        }
        return seen
    }

    @Test
    fun `the same seed always builds the same dungeon`() {
        for (theme in DungeonTheme.entries) {
            val first = DungeonText.render(dungeon(theme, 42L, 0))
            val second = DungeonText.render(dungeon(theme, 42L, 1))
            assertEquals(first, second)
            assertNotEquals(first, DungeonText.render(dungeon(theme, 43L)))
        }
    }

    @Test
    fun `every respawn point and the boss can be walked to from the entrance`() {
        for (theme in DungeonTheme.entries) for (seed in 1L..40L) {
            val layout = dungeon(theme, seed).layout
            val reached = reachable(layout)
            assertTrue(layout.spawns.all { DungeonLayout.index(it.x, it.y) in reached }, "$theme seed $seed has an unreachable respawn point")
            assertTrue(Side.entries.any { DungeonLayout.index(layout.portalX + it.dx, layout.portalY + it.dy) in reached }, "$theme seed $seed portal")
            assertEquals(1, layout.spawns.count { it.boss })
        }
    }

    @Test
    fun `respawn points get deeper towards the boss and none sit in the entry or boss room`() {
        for (theme in DungeonTheme.entries) for (seed in 1L..40L) {
            val layout = dungeon(theme, seed).layout
            val mobs = layout.spawns.filter { !it.boss }
            assertTrue(mobs.size >= DungeonGenerator.MIN_ROOMS, "$theme seed $seed has ${mobs.size} respawn points")
            assertTrue(mobs.all { it.progress in 0.0..1.0 })
            val sealed = layout.rooms.filter { it.kind == RoomKind.ENTRY || it.kind == RoomKind.BOSS }
            assertTrue(mobs.none { mob -> sealed.any { mob.x in it.x until it.x + it.width && mob.y in it.y until it.y + it.depth } })
            assertEquals(1.0, layout.spawns.first { it.boss }.progress)
        }
    }
}
