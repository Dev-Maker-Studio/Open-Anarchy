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

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.definition.data.ItemDefinition

class ItemMeshModifier(
    private val defs: ItemDefinition,
    private val slotFlag: Int
) {
    private var maleBody: IntArray? = null
    private var femaleBody: IntArray? = null
    private var maleHead: IntArray? = null
    private var femaleHead: IntArray? = null
    private var modifiedColors: IntArray? = null
    private var modifiedTextures: IntArray? = null

    fun addBodyModels(maleBodies: IntArray, femaleBodies: IntArray): ItemMeshModifier {
        maleBody = IntArray(3)
        femaleBody = IntArray(3)
        maleBody!![0] = defs.primaryMaleModel
        maleBody!![1] = defs.secondaryMaleModel
        maleBody!![2] = defs.tertiaryMaleModel
        femaleBody!![0] = defs.primaryFemaleModel
        femaleBody!![1] = defs.secondaryFemaleModel
        femaleBody!![2] = defs.tertiaryFemaleModel

        for (i in maleBody!!.indices) {
            if (i >= maleBodies.size) continue
            maleBody!![i] = maleBodies[i]
            femaleBody!![i] = femaleBodies[i]
        }
        return this
    }

    fun addBodyModels(vararg bodies: Int): ItemMeshModifier {
        return addBodyModels(bodies, bodies)
    }

    fun addHeadModels(maleHeads: IntArray, femaleHeads: IntArray): ItemMeshModifier {
        maleHead = IntArray(2)
        femaleHead = IntArray(2)
        maleHead!![0] = defs.primaryMaleDialogueHead
        maleHead!![1] = defs.secondaryMaleDialogueHead
        femaleHead!![0] = defs.primaryFemaleDialogueHead
        femaleHead!![1] = defs.secondaryFemaleDialogueHead

        for (i in maleHead!!.indices) {
            if (i >= maleHeads.size) continue
            maleHead!![i] = maleHeads[i]
            femaleHead!![i] = femaleHeads[i]
        }
        return this
    }

    fun addHeadModels(vararg heads: Int): ItemMeshModifier {
        return addHeadModels(heads, heads)
    }

    fun addColors(vararg colors: Int): ItemMeshModifier {
        val mc = defs.modifiedColours
        if (mc != null) {
            modifiedColors = IntArray(mc.size) { mc[it].toInt() and 0xFFFF }
        }
        if (modifiedColors == null) return this
        for (i in modifiedColors!!.indices) {
            if (i >= colors.size) break
            modifiedColors!![i] = colors[i]
        }
        return this
    }

    fun addTextures(vararg textures: Int): ItemMeshModifier {
        val mt = defs.modifiedTextureColours
        if (mt != null) {
            modifiedTextures = IntArray(mt.size) { mt[it].toInt() and 0xFFFF }
        }
        if (modifiedTextures == null) return this
        for (i in modifiedTextures!!.indices) {
            if (i >= textures.size) break
            modifiedTextures!![i] = textures[i]
        }
        return this
    }

    fun encode(stream: Writer): Int {
        val flags = getModificationFlags()
        if (flags == 0) return -1

        stream.writeByte(flags)
        if ((flags and BODY_MODEL_FLAG) != 0) encodeBodyModels(stream)
        if ((flags and HEAD_MODEL_FLAG) != 0) encodeHeadModels(stream)
        if ((flags and COLOR_FLAG) != 0) encodeColors(stream)
        if ((flags and TEXTURE_FLAG) != 0) encodeTextures(stream)
        return slotFlag
    }

    fun encodeBodyModels(stream: Writer) {
        stream.writeBigSmart(maleBody!![0])
        stream.writeBigSmart(femaleBody!![0])
        if (defs.secondaryMaleModel != -1 || defs.secondaryFemaleModel != -1) {
            stream.writeBigSmart(maleBody!![1])
            stream.writeBigSmart(femaleBody!![1])
        }
        if (defs.tertiaryMaleModel != -1 || defs.tertiaryFemaleModel != -1) {
            stream.writeBigSmart(maleBody!![2])
            stream.writeBigSmart(femaleBody!![2])
        }
    }

    fun encodeHeadModels(stream: Writer) {
        stream.writeBigSmart(maleHead!![0])
        stream.writeBigSmart(femaleHead!![0])
        if (defs.secondaryMaleDialogueHead != -1 || defs.secondaryFemaleDialogueHead != -1) {
            stream.writeBigSmart(maleHead!![1])
            stream.writeBigSmart(femaleHead!![1])
        }
    }

    fun encodeColors(stream: Writer) {
        val colors = modifiedColors!!
        var slots = 0
        for (i in 1 until 4) {
            slots = if (i < colors.size) {
                slots or (i shl (i * 4))
            } else {
                slots or (EMPTY_VALUE shl (i * 4))
            }
        }
        stream.writeShort(slots)
        for (i in 0 until 4) {
            if (i < colors.size) stream.writeShort(colors[i])
        }
    }

    fun encodeTextures(stream: Writer) {
        val textures = modifiedTextures!!
        var slots = 0
        for (i in 1 until 2) {
            slots = if (i < textures.size) {
                slots or (i shl (i * 4))
            } else {
                slots or (EMPTY_VALUE shl (i * 4))
            }
        }
        stream.writeByte(slots)
        for (i in 0 until 2) {
            if (i < textures.size) stream.writeShort(textures[i])
        }
    }

    fun getModificationFlags(): Int {
        var flags = 0
        if (maleBody != null || femaleBody != null) flags = flags or BODY_MODEL_FLAG
        if (maleHead != null || femaleHead != null) flags = flags or HEAD_MODEL_FLAG
        if (modifiedColors != null) flags = flags or COLOR_FLAG
        if (modifiedTextures != null) flags = flags or TEXTURE_FLAG
        return flags
    }

    companion object {
        private const val BODY_MODEL_FLAG = 0x1
        private const val HEAD_MODEL_FLAG = 0x2
        private const val COLOR_FLAG = 0x4
        private const val TEXTURE_FLAG = 0x8
        private const val EMPTY_VALUE = 0xF
    }
}
