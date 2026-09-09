package org.darkan.world

import com.rs.game.content.skills.crafting.urns.Urn
import com.rs.game.content.skills.crafting.urns.Urns
import org.darkan.core.cache.AnimationDefinitions
import org.darkan.core.cache.EnumDefinitions
import org.darkan.core.cache.ItemDefinitions
import org.darkan.core.cache.StructDefinitions
import org.darkan.core.gameval.Gameval
import org.darkan.core.gameval.gen.Enum
import org.darkan.core.gameval.gen.Param
import org.darkan.world.harness.WorldHarness
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class UrnTableTest {

    private companion object {
        val TIERS = listOf("Cracked", "Fragile", "Plain", "Strong", "Decorated", "Impious", "Accursed", "Infernal")
        val GAMEVAL = Regex("urns_[a-z]+[1-5]_(unfired|norune|rune|started|full)")
    }

    @BeforeTest
    fun boot() = WorldHarness.boot()

    @Test
    fun everyUrnIsNamedTheRs3Way() {
        for (urn in Urn.entries) {
            val base = ItemDefinitions.getDefs(urn.started).name
            assertTrue(TIERS.any { base.startsWith("$it ") } && base.endsWith(" urn"), "$urn started obj is named '$base'")
            assertEquals("$base (unfired)", ItemDefinitions.getDefs(urn.unfired).name, "$urn unfired")
            assertEquals("$base (no rune)", ItemDefinitions.getDefs(urn.noRune).name, "$urn no rune")
            assertEquals("$base (empty)", ItemDefinitions.getDefs(urn.empty).name, "$urn empty")
            assertEquals("$base (full)", ItemDefinitions.getDefs(urn.full).name, "$urn full")
        }
    }

    @Test
    fun everyUrnObjCarriesItsRs3Gameval() {
        for (urn in Urn.entries) {
            for (obj in listOf(urn.unfired, urn.noRune, urn.empty, urn.started, urn.full)) {
                val name = assertNotNull(Gameval.name(Gameval.OBJ, obj), "$urn obj $obj has no gameval name")
                assertTrue(GAMEVAL.matches(name), "$urn obj $obj is named $name")
            }
        }
    }

    @Test
    fun onlyTheEmptyUrnStacksAndTheNoRuneUrnOffersItsActivator() {
        for (urn in Urn.entries) {
            assertEquals(1, ItemDefinitions.getDefs(urn.empty).stackable, "$urn empty")
            for (obj in listOf(urn.unfired, urn.noRune, urn.started, urn.full)) {
                assertEquals(0, ItemDefinitions.getDefs(obj).stackable, "$urn obj $obj")
            }
            assertEquals(Urns.activatorOption(urn), ItemDefinitions.getDefs(urn.noRune).options[0], "$urn option")
        }
    }

    @Test
    fun theCraftingGuideRowMatchesTheUrnLevel() {
        val rows = EnumDefinitions.getEnum(Enum.enum_5465).getValues().values.map { StructDefinitions.getStruct(it as Int) }
        for (urn in Urn.entries) {
            val row = assertNotNull(rows.firstOrNull { it.getIntValue(Param.skillguide_item) == urn.empty }, "$urn has no crafting guide row")
            assertEquals(urn.level, row.getIntValue(Param.skillguide_level), "$urn guide level")
            assertEquals(ItemDefinitions.getDefs(urn.started).name, row.getStringValue(Param.skillguide_name), "$urn guide name")
        }
    }

    @Test
    fun theAnimationsHoldTheUrnTheyBelongTo() {
        for (urn in Urn.entries) {
            assertEquals(urn.full, AnimationDefinitions.getDefs(urn.teleport.primaryId).rightHandItem, "$urn teleport seq")
            assertEquals(urn.noRune, AnimationDefinitions.getDefs(urn.runeIn.primaryId).rightHandItem, "$urn rune-in seq")
        }
    }
}
