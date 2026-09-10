package org.darkan.world

import com.rs.engine.command.Commands
import com.rs.game.World
import com.rs.game.content.skills.thieving.PickPocketableNPC
import com.rs.game.map.ChunkManager
import com.rs.game.model.entity.player.Equipment
import com.rs.plugin.PluginManager
import com.rs.plugin.events.ButtonClickEvent
import com.rs.plugin.events.NPCClickEvent
import com.rs.plugin.events.ObjectClickEvent
import com.rs.utils.blocks
import com.rs.utils.clipType
import org.darkan.core.Utils
import org.darkan.core.cache.AnimationDefinitions
import org.darkan.core.cache.BASDefinitions
import org.darkan.core.cache.NPCDefinitions
import org.darkan.core.cache.ObjectDefinitions
import org.darkan.core.gameval.gen.Component
import org.darkan.core.gameval.gen.Interface
import org.darkan.core.gameval.gen.Loc
import org.darkan.core.gameval.gen.Npc
import org.darkan.core.gameval.gen.Obj
import org.darkan.core.model.Item
import org.darkan.world.harness.HarnessPlayer
import org.darkan.world.harness.WorldHarness
import world.gregs.voidps.type.Tile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReportedBugFixesTest {

    private val loggedIn = mutableListOf<HarnessPlayer>()

    @AfterTest
    fun tearDown() {
        loggedIn.forEach { WorldHarness.logout(it) }
        loggedIn.clear()
    }

    private fun login(name: String) = WorldHarness.login(name).also {
        loggedIn.add(it)
        WorldHarness.tick()
    }.player

    @Test
    fun locThatClearsProjectileBlockingStillBlocksWalkingButNotSight() {
        WorldHarness.boot()
        val gate = ObjectDefinitions.getDefs(Loc.fai_varrock_bank_bars_door)
        assertEquals(2, gate.clipType)
        assertFalse(gate.blocks())
    }

    @Test
    fun varrockVaultGoldBarIsInSightThroughTheBarsDoor() {
        WorldHarness.boot()
        val standing = Tile.of(3192, 9825, 0)
        val goldBar = Tile.of(3192, 9822, 0)
        ChunkManager.getChunk(standing.getChunkId())
        ChunkManager.getChunk(goldBar.getChunkId())
        assertNotNull(World.getObjectWithId(standing, Loc.fai_varrock_bank_bars_door_mirror))
        assertTrue(World.hasLineOfSight(standing, 1, goldBar, 1))
    }

    @Test
    fun anyExecutedCommandRefreshesTheIdleTimer() {
        val player = login("idlecommand")
        player.idleTime = 0
        assertTrue(player.isIdle)
        assertTrue(Commands.processCommand(player, "coords", false, false))
        assertFalse(player.isIdle)
    }

    @Test
    fun rubbingAWornSlayerRingAsksWhereToTeleportInsteadOfTeleporting() {
        val player = login("wornslayerring")
        val start = player.tile
        player.equipment.setSlot(Equipment.RING, Item(Obj.smki_ring_of_slaying_8, 1))
        val component = Utils.componentIdFromHash(Component.interface_387.component_387_12)
        PluginManager.handle(ButtonClickEvent(player, Interface.interface_387, component, -1, Obj.smki_ring_of_slaying_8, 2))
        assertNotNull(player.conversation)
        WorldHarness.tick(8)
        assertEquals(start, player.tile)
        assertEquals(Obj.smki_ring_of_slaying_8, player.equipment.getItem(Equipment.RING)?.id)
    }

    @Test
    fun shearingABlackSheepGivesBlackWoolAndABlackShornSheep() {
        val player = login("blacksheep")
        player.inventory.addItem(Obj.shears, 1)
        val sheep = World.spawnNPC(Npc.sheep_black, player.tile.transform(1, 0, 0))
        try {
            repeat(50) {
                if (!player.inventory.containsItem(Obj.black_wool, 1))
                    PluginManager.handle(NPCClickEvent(player, sheep, 1, true))
            }
            assertTrue(player.inventory.containsItem(Obj.black_wool, 1))
            assertFalse(player.inventory.containsItem(Obj.wool, 1))
            assertEquals(Npc.npc_8877, sheep.id)
        } finally {
            sheep.finish()
        }
    }

    @Test
    fun dwarfTraderStunSeqAnimatesTheTradersOwnSkeleton() {
        WorldHarness.boot()
        val stun = assertNotNull(PickPocketableNPC.DWARF_TRADER.stunAnimation).ids[0]
        for (npc in PickPocketableNPC.DWARF_TRADER.npcIds) {
            val bas = BASDefinitions.getDefs(NPCDefinitions.getDefs(npc.toInt()).renderEmote)
            assertEquals(frameSets(bas.walkAnimation), frameSets(stun), "npc $npc walk")
            assertEquals(frameSets(bas.standAnimation), frameSets(stun), "npc $npc stand")
        }
    }

    @Test
    fun seerHouseLadderClimbsToTheTrapdoorFloorAndTheTrapdoorClimbsBack() {
        val player = login("seerladder")
        val ladderTile = Tile.of(2631, 3663, 0)
        val trapdoorTile = Tile.of(2631, 3663, 2)
        ChunkManager.getChunk(ladderTile.getChunkId())
        ChunkManager.getChunk(trapdoorTile.getChunkId())
        val ladder = assertNotNull(World.getObjectWithId(ladderTile, Loc.viking_seer_up_ladder))
        val trapdoor = assertNotNull(World.getObjectWithId(trapdoorTile, Loc.viking_seer_trapdoor_open))

        player.tele(Tile.of(2631, 3664, 0))
        WorldHarness.tick()
        PluginManager.handle(ObjectClickEvent(player, ladder, 1, true))
        WorldHarness.tick(4)
        assertEquals(Tile.of(2631, 3664, 2), player.tile)

        PluginManager.handle(ObjectClickEvent(player, trapdoor, 1, true))
        WorldHarness.tick(4)
        assertEquals(Tile.of(2631, 3664, 0), player.tile)
    }

    private fun frameSets(seq: Int): Set<Int> =
        assertNotNull(AnimationDefinitions.getDefs(seq).frames).map { it ushr 16 }.toSet()
}
