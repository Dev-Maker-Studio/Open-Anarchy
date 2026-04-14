// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program.  If not, see <http://www.gnu.org/licenses/>.
//
//  Copyright (C) 2021 Trenton Kress
//  This file is part of project: Darkan
//
package com.rs.game.model.entity.player

import com.rs.game.World
import com.rs.utils.aBool4872
import com.rs.utils.basId
import com.rs.utils.rotate180Animation
import com.rs.utils.rotate90LeftAnimation
import com.rs.utils.rotate90RightAnimation
import com.rs.utils.specialByte
import com.rs.utils.walkingAnimation
import org.darkan.core.Logger
import org.darkan.core.Utils
import org.darkan.core.cache.EnumDefinitions
import org.darkan.core.cache.NPCDefinitions
import org.darkan.core.cache.StructDefinitions
import org.darkan.core.model.Item
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.definition.data.EnumDefinition
import world.gregs.voidps.cache.definition.data.ItemDefinition
import world.gregs.voidps.cache.definition.data.NPCDefinition
import world.gregs.voidps.cache.definition.data.StructDefinition

class Appearance {

    var title: Int = -1
        private set
    private var lookI: IntArray = IntArray(7)
    private var colour: IntArray = IntArray(10)
    var isMale: Boolean = true
        private set

    @Transient
    var appeareanceData: ByteArray? = null
        private set

    @Transient
    var mD5AppeareanceDataHash: ByteArray? = null
        private set

    @Transient
    private var transformedNpcId: Short = -1

    @Transient
    private var hidePlayer: Boolean = false

    @Transient
    private var player: Player? = null

    init {
        resetAppearance()
    }

    fun setPlayer(player: Player) {
        this.player = player
        transformedNpcId = -1
        if (lookI == null)
            resetAppearance()
    }

    fun transformIntoNPC(id: Int) {
        transformedNpcId = id.toShort()
        generateAppearanceData()
    }

    var isHidden: Boolean
        get() = hidePlayer
        set(hidden) {
            hidePlayer = hidden
            generateAppearanceData()
        }

    private fun getHatHairStyle(baseStyle: Int, isFaceMask: Boolean): Int {
        val lookup: EnumDefinition = EnumDefinitions.getEnum(if (isMale) MALE_HAIR_SLOT_LOOKUP else FEMALE_HAIR_SLOT_LOOKUP)
        val slot = lookup.getIntValue(baseStyle)
        val structLookup: EnumDefinition = EnumDefinitions.getEnum(if (isMale) MALE_HAIR_STRUCT_LOOKUP else FEMALE_HAIR_STRUCT_LOOKUP)
        val structID = structLookup.getIntValue(slot)
        return StructDefinitions.getStruct(structID).getIntValue(if (isFaceMask) HAIR_WITH_FACE_MASK_PARAM else HAIR_WITH_HAT_PARAM, -1)
    }

    fun generateAppearanceData() {
        val player = this.player ?: return
        val stream = BufferWriter(512)
        val pvpArea = World.isPvpArea(player)
        val showSkillTotal = player.tempAttribs.getB("showSkillTotal") && !pvpArea
        var flag = 0
        if (!isMale)
            flag = flag or 0x1
        if (transformedNpcId >= 0 && NPCDefinitions.getDefs(transformedNpcId.toInt()).aBool4872)
            flag = flag or 0x2
        if (showSkillTotal)
            flag = flag or 0x4
        if (title != 0 || player.getTitle() != null)
            flag = flag or if (isTitleAfter(title) || player.isTitleAfter) 0x80 else 0x40

        stream.writeByte(flag)
        if (title != 0 || player.getTitle() != null)
            stream.writePrefixedString(player.formattedTitle)
        stream.writeByte(if (player.hasSkull()) player.skullId else -1)
        stream.writeByte(if (player.tempAttribs.getI("customHeadIcon", -1) != -1) player.tempAttribs.getI("customHeadIcon", 0) else player.prayer.prayerHeadIcon)
        stream.writeByte(if (hidePlayer) 1 else 0)

        if (transformedNpcId >= 0) {
            stream.writeShort(-1)
            stream.writeShort(transformedNpcId.toInt())
            stream.writeByte(0)
        } else {
            for (index in 0 until 4) {
                val item: Item? = player.equipment[index]
                if (item == null)
                    stream.writeByte(0)
                else
                    stream.writeShort(16384 + item.id)
            }

            var item: Item? = player.equipment[Equipment.CHEST]
            stream.writeShort(if (item == null) 0x100 + lookI[2] else 16384 + item.id)

            item = player.equipment[Equipment.SHIELD]
            if (item == null)
                stream.writeByte(0)
            else
                stream.writeShort(16384 + item.id)

            item = player.equipment[Equipment.CHEST]
            if (lookI[3] != -1 && (item == null || !Equipment.hideArms(item)))
                stream.writeShort(0x100 + lookI[3])
            else if (item != null && !Equipment.hideArms(item))
                stream.writeShort(0x100 + oldArms)
            else
                stream.writeByte(0)

            item = player.equipment[Equipment.LEGS]
            stream.writeShort(if (item == null) 0x100 + lookI[5] else 16384 + item.id)

            item = player.equipment[Equipment.HEAD]
            if (lookI[0] != -1 && (item == null || !Equipment.hideHair(item))) {
                if (item == null)
                    stream.writeShort(0x100 + lookI[0])
                else {
                    val hatHairStyle = getHatHairStyle(lookI[0], item.definitions.faceMask())
                    if (hatHairStyle != -1)
                        stream.writeShort(0x100 + hatHairStyle)
                    else
                        stream.writeByte(0)
                }
            } else
                stream.writeByte(0)

            item = player.equipment[Equipment.HANDS]
            stream.writeShort(if (item == null) 0x100 + lookI[4] else 16384 + item.id)

            item = player.equipment[Equipment.FEET]
            stream.writeShort(if (item == null) 0x100 + lookI[6] else 16384 + item.id)

            item = player.equipment[if (isMale) Equipment.HEAD else Equipment.CHEST]
            if (isMale && lookI[1] != -1 && (item == null || (isMale && !Equipment.hideBeard(item))))
                stream.writeShort(0x100 + lookI[1])
            else
                stream.writeByte(0)

            item = player.equipment[Equipment.AURA]
            if (item == null)
                stream.writeByte(0)
            else
                stream.writeShort(16384 + item.id)

            encodeMeshModifiers(stream)
        }

        for (element in colour)
            stream.writeByte(element)

        stream.writeShort(renderEmote)
        stream.writeString(player.displayName)
        stream.writeByte(if (pvpArea) player.skills.combatLevel else player.skills.combatLevelWithSummoning)
        if (showSkillTotal)
            stream.writeShort(player.skills.totalLevel)
        else {
            stream.writeByte(if (pvpArea) player.skills.combatLevelWithSummoning else 0)
            stream.writeByte(player.pvpCombatLevelThreshhold)
        }
        stream.writeByte(if (transformedNpcId >= 0) 1 else 0)
        if (transformedNpcId >= 0) {
            val defs: NPCDefinition = NPCDefinitions.getDefs(transformedNpcId.toInt())
            stream.writeShort(defs.walkingAnimation)
            stream.writeShort(defs.rotate180Animation)
            stream.writeShort(defs.rotate90RightAnimation)
            stream.writeShort(defs.rotate90LeftAnimation)
            stream.writeByte(defs.specialByte)
        }

        val data = stream.toArray()
        val md5Hash = Utils.encryptUsingMD5(data)
        appeareanceData = data
        mD5AppeareanceDataHash = md5Hash
    }

    private fun getMeshModifiers(): ArrayList<ItemMeshModifier> {
        val player = this.player!!
        val modifiers = ArrayList<ItemMeshModifier>()
        var slotFlag = -1
        for (slotId in 0 until Equipment.SIZE) {
            if (Equipment.DISABLED_SLOTS[slotId] != 0) continue
            slotFlag++
            if (player.equipment.getId(slotId) == -1) continue
            val item: Item = player.equipment[slotId] ?: continue
            val defs: ItemDefinition = item.definitions ?: continue

            when (defs.id) {
                1833, 1835, 1171, 2637 -> {
                    val mod = ItemMeshModifier(defs, slotFlag)
                    var add = false
                    if (item.getMetaDataI("drTOr", -1) > 0) {
                        mod.addTextures(item.getMetaDataI("drTOr", -1), item.getMetaDataI("drTOr", -1), item.getMetaDataI("drTOr", -1))
                        add = true
                    }
                    if (item.getMetaDataI("drCOr", -1) > 0) {
                        mod.addColors(item.getMetaDataI("drCOr", -1))
                        add = true
                    }
                    if (add) modifiers.add(mod)
                }
                20767, 20768 -> {
                    modifiers.add(
                        ItemMeshModifier(defs, slotFlag)
                            .addColors(*player.maxedCapeCustomized)
                    )
                }
                20769, 20770, 20771, 20772 -> {
                    modifiers.add(
                        ItemMeshModifier(defs, slotFlag)
                            .addColors(*player.completionistCapeCustomized)
                    )
                }
                20708, 20709 -> {
                    if (player.clan != null) {
                        modifiers.add(
                            ItemMeshModifier(defs, slotFlag)
                                .addColors(*player.clan.getMotifColors())
                                .addTextures(*player.clan.getMotifTextures())
                        )
                    }
                }
            }

            if (slotId == Equipment.AURA && player.auraManager.isActive) {
                modifiers.add(
                    ItemMeshModifier(defs, slotFlag)
                        .addBodyModels(player.auraManager.auraModelId, player.auraManager.auraModelId2)
                )
            }
        }
        return modifiers
    }

    private fun encodeMeshModifiers(stream: BufferWriter) {
        val start = stream.position()
        stream.writeShort(0)
        var slotHash = 0
        for (modifier in getMeshModifiers()) {
            val slot = modifier.encode(stream)
            if (slot != -1)
                slotHash = slotHash or (1 shl slot)
        }
        val end = stream.position()
        stream.position(start)
        stream.writeShort(slotHash)
        stream.position(end)
    }

    val size: Int
        get() = if (transformedNpcId >= 0) NPCDefinitions.getDefs(transformedNpcId.toInt()).size else 1

    fun setBAS(id: Int) {
        player!!.bas = id
        generateAppearanceData()
    }

    fun isTitleAfter(title: Int): Boolean {
        if (title in 32..37) return true
        if (title == 64) return false
        if (title in 58..65) return true
        return title == 40 || title == 43 || title == 45 || title == 47 || title == 49 || title == 53 || title == 55 || title == 56 || title == 72 || title == 73
    }

    val transformedNPC: Int
        get() = transformedNpcId.toInt()

    val isNPC: Boolean
        get() = transformedNpcId.toInt() != -1

    val renderEmote: Int
        get() {
            val player = this.player!!
            if (player.bas >= 0) return player.bas
            if (transformedNpcId >= 0) return NPCDefinitions.getDefs(transformedNpcId.toInt()).basId
            return player.equipment.weaponBAS
        }

    fun resetAppearance() {
        lookI = IntArray(7)
        colour = IntArray(10)
        male()
    }

    val oldArms: Int
        get() = if (isMale) 26 else 61

    fun male() {
        lookI[0] = 310
        lookI[1] = 16
        lookI[2] = 452
        lookI[3] = -1
        lookI[4] = 371
        lookI[5] = 627
        lookI[6] = 433
        colour[0] = 12
        colour[1] = 218
        colour[2] = 218
        colour[3] = 180
        colour[4] = 110
        colour[5] = 0
        colour[6] = 0
        colour[7] = 0
        colour[8] = 0
        colour[9] = 0
        isMale = true
    }

    fun female() {
        lookI[0] = 274
        lookI[1] = -1
        lookI[2] = 561
        lookI[3] = -1
        lookI[4] = 514
        lookI[5] = 482
        lookI[6] = 547
        colour[0] = 12
        colour[1] = 218
        colour[2] = 218
        colour[3] = 180
        colour[4] = 110
        colour[5] = 0
        colour[6] = 0
        colour[7] = 0
        colour[8] = 0
        colour[9] = 0
        isMale = false
    }

    fun setLook(i: Int, i2: Int) {
        lookI[i] = i2
        if (i == 2) verifyArms()
    }

    fun setColor(i: Int, i2: Int) {
        colour[i] = i2.toByte().toInt()
    }

    fun setMale(male: Boolean) {
        isMale = male
    }

    fun setHairStyle(i: Int) {
        lookI[0] = i
    }

    fun setTopStyle(i: Int) {
        lookI[2] = i
        verifyArms()
    }

    val topStyle: Int
        get() = lookI[2]

    var armsStyle: Int
        get() = lookI[3]
        set(i) { lookI[3] = i }

    var wristsStyle: Int
        get() = lookI[4]
        set(i) { lookI[4] = i }

    fun setLegsStyle(i: Int) {
        lookI[5] = i
    }

    val hairStyle: Int
        get() = lookI[0]

    var beardStyle: Int
        get() = lookI[1]
        set(i) { lookI[1] = i }

    var facialHair: Int
        get() = lookI[1]
        set(i) { lookI[1] = i }

    var skinColor: Int
        get() = colour[4]
        set(color) { colour[4] = color.toByte().toInt() }

    var hairColor: Int
        get() = colour[0]
        set(color) { colour[0] = color.toByte().toInt() }

    fun setTopColor(color: Int) {
        colour[1] = color.toByte().toInt()
    }

    fun setLegsColor(color: Int) {
        colour[2] = color.toByte().toInt()
    }

    fun setBootsStyle(i: Int) {
        lookI[6] = i
    }

    fun setBootsColor(color: Int) {
        colour[3] = color.toByte().toInt()
    }

    fun setTitle(title: Int) {
        this.title = title
        generateAppearanceData()
    }

    fun verifyArms() {
        val ts = topStyle
        val setId = getSetByStyle(ts, 3, !isMale)
        if (setId != -1) {
            val set: StructDefinition = StructDefinitions.getStruct(setId)
            armsStyle = set.getIntValue(1183, -1)
            wristsStyle = set.getIntValue(1184, -1)
        } else {
            if (EnumDefinitions.getEnum(if (isMale) 711 else 693).getKeyForValue(armsStyle) == -1)
                armsStyle = if (isMale) 26 else 61
            if (EnumDefinitions.getEnum(if (isMale) 749 else 751).getKeyForValue(wristsStyle) == -1)
                wristsStyle = if (isMale) 34 else 68
        }
    }

    fun printDebug() {
        for (i in lookI.indices)
            Logger.debug(Appearance::class.java, "printDebug", "lookI[$i] = ${lookI[i]};")
        for (i in colour.indices)
            Logger.debug(Appearance::class.java, "printDebug", "colour[$i] = ${colour[i]};")
    }

    companion object {
        const val FEMALE_HAIR_STRUCT_LOOKUP = 2341
        const val MALE_HAIR_STRUCT_LOOKUP = 2338
        const val MALE_HAIR_SLOT_LOOKUP = 2339
        const val FEMALE_HAIR_SLOT_LOOKUP = 2342
        const val HAIR_WITH_HAT_PARAM = 790
        const val HAIR_WITH_FACE_MASK_PARAM = 791

        @JvmStatic
        private fun getSetByStyle(styleID: Int, styleSlot: Int, female: Boolean): Int {
            val sets: EnumDefinition = EnumDefinitions.getEnum(5735)
            for (slot in sets.getSize() - 1 downTo 0) {
                val structId = sets.getIntValue(slot)
                if (structId != -1) {
                    val struct: StructDefinition = StructDefinitions.getStruct(structId)
                    var v7 = 0
                    var setId = getSetStruct(struct, 0, female)
                    while (setId != -1) {
                        val setStyles: StructDefinition = StructDefinitions.getStruct(setId)
                        when (styleSlot) {
                            3 -> if (setStyles.getIntValue(1182, -1) == styleID) return setId
                            4 -> if (setStyles.getIntValue(1183, -1) == styleID) return setId
                            5 -> if (setStyles.getIntValue(1184, -1) == styleID) return setId
                            6 -> if (setStyles.getIntValue(1185, -1) == styleID) return setId
                            else -> return -1
                        }
                        v7++
                        setId = getSetStruct(struct, v7, female)
                    }
                }
            }
            return -1
        }

        @JvmStatic
        private fun getSetStruct(struct: StructDefinition, slot: Int, female: Boolean): Int {
            return when (slot) {
                0 -> struct.getIntValue(if (female) 1175 else 1169, -1)
                1 -> struct.getIntValue(if (female) 1176 else 1170, -1)
                2 -> struct.getIntValue(if (female) 1177 else 1171, -1)
                3 -> struct.getIntValue(if (female) 1178 else 1172, -1)
                4 -> struct.getIntValue(if (female) 1179 else 1173, -1)
                5 -> struct.getIntValue(if (female) 1180 else 1174, -1)
                else -> -1
            }
        }
    }
}
