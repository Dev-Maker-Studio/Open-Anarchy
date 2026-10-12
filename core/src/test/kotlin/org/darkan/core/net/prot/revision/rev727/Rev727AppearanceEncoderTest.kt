package org.darkan.core.net.prot.revision.rev727

import org.darkan.core.update.AppearanceSlot
import org.darkan.core.update.AppearanceTitle
import org.darkan.core.update.BodyModelOverride
import org.darkan.core.update.HeadModelOverride
import org.darkan.core.update.ItemMeshOverride
import org.darkan.core.update.NpcRenderAnimations
import org.darkan.core.update.PlayerAppearanceData
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class Rev727AppearanceEncoderTest {

    private fun baseData(
        slots: List<AppearanceSlot> = List(13) { AppearanceSlot.Empty },
        meshOverrides: List<ItemMeshOverride> = emptyList(),
        male: Boolean = true,
        title: AppearanceTitle? = null,
        showSkillTotal: Boolean = false,
        transformedNpcId: Int = -1,
        npcRenderAnimations: NpcRenderAnimations? = null,
        npcTransformSizeFlag: Boolean = false,
    ) = PlayerAppearanceData(
        male = male,
        npcTransformSizeFlag = npcTransformSizeFlag,
        showSkillTotal = showSkillTotal,
        title = title,
        skullIcon = -1,
        headIcon = -1,
        hidden = false,
        transformedNpcId = transformedNpcId,
        slots = slots,
        meshOverrides = meshOverrides,
        colours = IntArray(10),
        renderEmote = 1426,
        displayName = "Test",
        combatLevel = 3,
        totalLevel = 32,
        summoningCombatLevel = 0,
        pvpCombatLevelThreshold = 0,
        npcRenderAnimations = npcRenderAnimations,
    )

    private fun trailer(showSkillTotal: Boolean = false, transformed: Boolean = false) = buildList {
        repeat(10) { add(0x00) }
        addAll(listOf(0x05, 0x92))
        addAll("Test".map { it.code })
        add(0x00)
        add(0x03)
        if (showSkillTotal) addAll(listOf(0x00, 0x20))
        else addAll(listOf(0x00, 0x00))
        add(if (transformed) 0x01 else 0x00)
    }

    private fun ByteArray.toInts() = map { it.toInt() and 0xFF }

    @Test
    fun `an empty male appearance encodes header, slots, mesh bitmask and trailer`() {
        val encoded = Rev727AppearanceEncoder.encode(baseData())
        val expected = buildList {
            add(0x00)
            add(0xFF)
            add(0xFF)
            add(0x00)
            repeat(13) { add(0x00) }
            addAll(listOf(0x00, 0x00))
            addAll(trailer())
        }
        assertContentEquals(expected.map { it.toByte() }, encoded.toList())
    }

    @Test
    fun `body slots are offset by 0x100 and worn slots by 16384`() {
        val slots = listOf(AppearanceSlot.Body(5), AppearanceSlot.Worn(1038)) +
            List(11) { AppearanceSlot.Empty }
        val encoded = Rev727AppearanceEncoder.encode(baseData(slots = slots)).toInts()
        assertEquals(listOf(0x01, 0x05, 0x44, 0x0E), encoded.subList(4, 8))
        assertEquals(List(11) { 0x00 }, encoded.subList(8, 19))
    }

    @Test
    fun `the female flag is set and a title is length-prefixed after the flags byte`() {
        val encoded = Rev727AppearanceEncoder.encode(
            baseData(male = false, title = AppearanceTitle("Sir", after = false)),
        ).toInts()
        assertEquals(0x41, encoded[0])
        assertEquals(listOf(0x00, 'S'.code, 'i'.code, 'r'.code, 0x00), encoded.subList(1, 6))
    }

    @Test
    fun `a title rendered after the name uses the high flag bit`() {
        val encoded = Rev727AppearanceEncoder.encode(
            baseData(title = AppearanceTitle("the Brave", after = true)),
        ).toInts()
        assertEquals(0x80, encoded[0])
    }

    @Test
    fun `a transformed player replaces the slots with the npc id and appends render animations`() {
        val anims = NpcRenderAnimations(walk = 819, rotate180 = 820, rotate90Right = 821, rotate90Left = 822, special = 5)
        val encoded = Rev727AppearanceEncoder.encode(
            baseData(transformedNpcId = 2234, npcRenderAnimations = anims, npcTransformSizeFlag = true),
        ).toInts()
        val expected = buildList {
            add(0x02)
            add(0xFF)
            add(0xFF)
            add(0x00)
            addAll(listOf(0xFF, 0xFF))
            addAll(listOf(0x08, 0xBA))
            add(0x00)
            addAll(trailer(transformed = true))
            addAll(listOf(0x03, 0x33))
            addAll(listOf(0x03, 0x34))
            addAll(listOf(0x03, 0x35))
            addAll(listOf(0x03, 0x36))
            add(0x05)
        }
        assertEquals(expected, encoded)
    }

    @Test
    fun `showing the skill total swaps the level footer for the total level`() {
        val encoded = Rev727AppearanceEncoder.encode(baseData(showSkillTotal = true)).toInts()
        assertEquals(0x04, encoded[0])
        val expected = buildList {
            add(0x00); add(0xFF); add(0xFF); add(0x00)
            repeat(13) { add(0x00) }
            addAll(listOf(0x00, 0x00))
            addAll(trailer(showSkillTotal = true))
        }
        assertEquals(expected.drop(1), encoded.drop(1))
    }

    @Test
    fun `mesh overrides back-patch a slot bitmask and are skipped when empty`() {
        val overrides = listOf(
            ItemMeshOverride(slot = 3),
            ItemMeshOverride(slot = 5, colours = intArrayOf(11, 22)),
        )
        val encoded = Rev727AppearanceEncoder.encode(baseData(meshOverrides = overrides)).toInts()
        val meshStart = 4 + 13
        assertEquals(listOf(0x00, 0x20), encoded.subList(meshStart, meshStart + 2))
        val body = encoded.subList(meshStart + 2, meshStart + 2 + 7)
        assertEquals(
            listOf(
                0x04,
                0xFF, 0x10,
                0x00, 0x0B,
                0x00, 0x16,
            ),
            body,
        )
    }

    @Test
    fun `model overrides emit secondary and tertiary pairs only when the item defines them`() {
        val override = ItemMeshOverride(
            slot = 0,
            bodyModels = BodyModelOverride(
                male = intArrayOf(1, 2, 3),
                female = intArrayOf(4, 5, 6),
                includeSecondary = true,
                includeTertiary = false,
            ),
            headModels = HeadModelOverride(
                male = intArrayOf(7, 8),
                female = intArrayOf(9, 10),
                includeSecondary = false,
            ),
        )
        val encoded = Rev727AppearanceEncoder.encode(baseData(meshOverrides = listOf(override))).toInts()
        val meshStart = 4 + 13
        assertEquals(listOf(0x00, 0x01), encoded.subList(meshStart, meshStart + 2))
        assertEquals(
            listOf(
                0x03,
                0x00, 0x01,
                0x00, 0x04,
                0x00, 0x02,
                0x00, 0x05,
                0x00, 0x07,
                0x00, 0x09,
            ),
            encoded.subList(meshStart + 2, meshStart + 2 + 13),
        )
    }

    @Test
    fun `encoding is deterministic for equal input`() {
        assertContentEquals(
            Rev727AppearanceEncoder.encode(baseData()),
            Rev727AppearanceEncoder.encode(baseData()),
        )
    }
}
