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

    fun toCustomisation(): NpcCustomisation = NpcCustomisation(
        clear = modelIds == null && modifiedColors == null && modifiedTextures == null,
        models = modelIds,
        recolors = modifiedColors,
        retextures = modifiedTextures,
    )

    companion object {
        @JvmField
        val RESET: NPCBodyMeshModifier = NPCBodyMeshModifier(null as NPCDefinition?)
    }
}
