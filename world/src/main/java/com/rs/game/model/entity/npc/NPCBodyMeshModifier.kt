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

import org.darkan.core.update.NpcCustomisation
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

    fun appendModels(vararg models: Int): NPCBodyMeshModifier {
        modelIds = (modelIds ?: defs!!.modelIds!!) + models
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

    /**
     * Converts this modifier into the core [NpcCustomisation] schema.
     *
     * Which models, colours and textures apply is decided here; how they are framed on the wire is
     * decided by the revision encoder in core. A modifier that overrides nothing means "reset to
     * the definition's own appearance", which the schema carries as [NpcCustomisation.clear].
     */
    fun toCustomisation(): NpcCustomisation = NpcCustomisation(
        clear = modelIds == null && modifiedColors == null && modifiedTextures == null,
        models = modelIds,
        recolors = modifiedColors,
        retextures = modifiedTextures,
    )

    companion object {
        /** Sentinel meaning "drop every override and render the definition's own appearance". */
        @JvmField
        val RESET: NPCBodyMeshModifier = NPCBodyMeshModifier(null as NPCDefinition?)
    }
}
