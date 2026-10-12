package org.darkan.tools.rsinterface

import org.lwjgl.opengl.GL11.glDeleteTextures
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.definition.data.GraphicDefinition
import world.gregs.voidps.cache.definition.data.IndexedGraphic
import world.gregs.voidps.cache.definition.decoder.GraphicDecoder

class LoadedGraphic(
    val textureId: Int,
    val width: Int,
    val height: Int
)

class GraphicCache(private val cache: Cache) {

    private val decoder = GraphicDecoder()
    private val definitions: Array<GraphicDefinition> = decoder.create(decoder.size(cache) + 1)
    private val decoded = BooleanArray(definitions.size)

    private val textures = HashMap<Long, LoadedGraphic?>()

    val count: Int get() = definitions.size

    fun definition(id: Int): GraphicDefinition? {
        if (id < 0 || id >= definitions.size) {
            return null
        }
        if (!decoded[id]) {
            decoded[id] = true
            try {
                decoder.load(definitions, cache, id)
            } catch (e: Exception) {
                System.err.println("Failed to decode graphic $id: ${e.message}")
            }
        }
        return if (definitions[id].graphics == null) null else definitions[id]
    }

    fun graphic(id: Int, flipVertical: Boolean = false, flipHorizontal: Boolean = false, frame: Int = 0): LoadedGraphic? {
        val key = (id.toLong() shl 24) or
            (frame.toLong() shl 2) or
            (if (flipVertical) 2L else 0L) or
            (if (flipHorizontal) 1L else 0L)
        if (textures.containsKey(key)) {
            return textures[key]
        }
        val uploaded = upload(id, frame, flipVertical, flipHorizontal)
        textures[key] = uploaded
        return uploaded
    }

    private fun upload(id: Int, frame: Int, flipVertical: Boolean, flipHorizontal: Boolean): LoadedGraphic? {
        val graphics = definition(id)?.graphics ?: return null
        val graphic = graphics.getOrNull(frame) ?: return null
        val width = graphic.offsetX + graphic.width + graphic.deltaWidth
        val height = graphic.offsetY + graphic.height + graphic.deltaHeight
        if (width <= 0 || height <= 0) {
            return null
        }

        val pixels = expand(graphic, width, height, flipVertical, flipHorizontal)
        return LoadedGraphic(UiTextures.createArgb(pixels, width, height, repeat = true), width, height)
    }

    private fun expand(
        graphic: IndexedGraphic,
        canvasWidth: Int,
        canvasHeight: Int,
        flipVertical: Boolean,
        flipHorizontal: Boolean
    ): IntArray {
        val pixels = IntArray(canvasWidth * canvasHeight)
        val palette = graphic.palette
        val raster = graphic.raster
        val alpha = graphic.alpha
        for (y in 0 until graphic.height) {
            for (x in 0 until graphic.width) {
                val source = x + y * graphic.width
                val index = raster[source].toInt() and 0xff
                val opacity = if (alpha != null) alpha[source].toInt() and 0xff else if (index == 0) 0 else 255
                if (opacity == 0) {
                    continue
                }
                var destX = x + graphic.offsetX
                var destY = y + graphic.offsetY
                if (flipHorizontal) {
                    destX = canvasWidth - 1 - destX
                }
                if (flipVertical) {
                    destY = canvasHeight - 1 - destY
                }
                pixels[destX + destY * canvasWidth] = (opacity shl 24) or palette[index]
            }
        }
        return pixels
    }

    fun cleanup() {
        textures.values.forEach { graphic ->
            if (graphic != null) {
                glDeleteTextures(graphic.textureId)
            }
        }
        textures.clear()
    }
}
