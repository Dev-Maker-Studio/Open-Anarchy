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
package com.rs.game.model.entity.npc

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.definition.data.NPCDefinition

class NPCBodyMeshModifier(private val defs: NPCDefinition?) {

    private var modelIds: IntArray? = null
    private var modifiedColors: ShortArray? = null
    private var modifiedTextures: ShortArray? = null

    fun setModel(index: Int, modelId: Int): NPCBodyMeshModifier {
        val d = defs!!
        if (modelIds == null) {
            modelIds = d.modelIds!!.copyOf()
        }
        if (index < 0 || index >= modelIds!!.size)
            throw RuntimeException("Index $index for models of ${d.id} is out of bounds.")
        modelIds!![index] = if (modelId == -1) d.modelIds!![index] else modelId
        return this
    }

    fun addModels(vararg models: Int): NPCBodyMeshModifier {
        val d = defs!!
        val defModels = d.modelIds!!
        if (models.contentEquals(defModels)) return this
        modelIds = defModels.copyOf()
        for (i in defModels.indices) {
            if (i >= models.size) continue
            modelIds!![i] = models[i]
        }
        return this
    }

    fun setColor(index: Int, color: Int): NPCBodyMeshModifier {
        val d = defs!!
        if (modifiedColors == null && d.modifiedColours != null) {
            modifiedColors = d.modifiedColours!!.copyOf()
        }
        if (modifiedColors == null || index < 0 || index >= modifiedColors!!.size)
            throw RuntimeException("Index $index for models of ${d.id} is out of bounds.")
        modifiedColors!![index] = if (color == -1) d.modifiedColours!![index] else color.toShort()
        return this
    }

    fun addColors(vararg colors: Int): NPCBodyMeshModifier {
        val d = defs!!
        if (d.modifiedColours != null) {
            modifiedColors = d.modifiedColours!!.copyOf()
        }
        if (modifiedColors == null) return this
        for (i in modifiedColors!!.indices) {
            if (i >= colors.size) break
            modifiedColors!![i] = colors[i].toShort()
        }
        return this
    }

    fun setTexture(index: Int, texId: Int): NPCBodyMeshModifier {
        val d = defs!!
        if (modifiedTextures == null && d.modifiedTextureColours != null) {
            modifiedTextures = d.modifiedTextureColours!!.copyOf()
        }
        if (modifiedTextures == null || index < 0 || index >= modifiedTextures!!.size)
            throw RuntimeException("Index $index for models of ${d.id} is out of bounds.")
        modifiedTextures!![index] = if (texId == -1) d.modifiedTextureColours!![index] else texId.toShort()
        return this
    }

    fun addTextures(vararg textures: Int): NPCBodyMeshModifier {
        val d = defs!!
        if (d.modifiedTextureColours != null) {
            modifiedTextures = d.modifiedTextureColours!!.copyOf()
        }
        if (modifiedTextures == null) return this
        for (i in modifiedTextures!!.indices) {
            if (i >= textures.size) break
            modifiedTextures!![i] = textures[i].toShort()
        }
        return this
    }

    fun encode(stream: Writer): Int {
        val flags = getModificationFlags()
        if (flags == 0) return -1

        stream.writeByteSubtract(flags)
        if ((flags and MODEL_FLAG) != 0) encodeModels(stream)
        if ((flags and COLOR_FLAG) != 0) encodeColors(stream)
        if ((flags and TEXTURE_FLAG) != 0) encodeTextures(stream)
        return flags
    }

    fun encodeModels(stream: Writer) {
        for (modelId in modelIds!!) stream.writeBigSmart(modelId)
    }

    fun encodeColors(stream: Writer) {
        for (color in modifiedColors!!) stream.writeShort(color.toInt())
    }

    fun encodeTextures(stream: Writer) {
        for (texture in modifiedTextures!!) stream.writeShort(texture.toInt())
    }

    fun getModificationFlags(): Int {
        var flags = 0
        if (modelIds != null) flags = flags or MODEL_FLAG
        if (modifiedColors != null) flags = flags or COLOR_FLAG
        if (modifiedTextures != null) flags = flags or TEXTURE_FLAG
        if (flags == 0) flags = flags or RESET_FLAG
        return flags
    }

    companion object {
        @JvmField
        val RESET: NPCBodyMeshModifier = NPCBodyMeshModifier(null as NPCDefinition?)

        private const val RESET_FLAG = 0x1
        private const val MODEL_FLAG = 0x2
        private const val COLOR_FLAG = 0x4
        private const val TEXTURE_FLAG = 0x8
    }
}
