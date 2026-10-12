package org.darkan.tools.rsinterface

import org.lwjgl.opengl.GL11.glDeleteTextures
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.definition.data.FontDefinition
import world.gregs.voidps.cache.definition.decoder.FontDecoder

class FontCache(
    private val cache: Cache,
    private val graphics: GraphicCache
) {
    private val decoder = FontDecoder()
    private val definitions: Array<FontDefinition> = decoder.create(decoder.size(cache) + 1)
    private val decoded = BooleanArray(definitions.size)

    private val fonts = HashMap<Int, RsFont?>()

    val count: Int get() = definitions.size

    fun font(id: Int): RsFont? {
        if (id < 0 || id >= definitions.size) {
            return null
        }
        if (fonts.containsKey(id)) {
            return fonts[id]
        }
        val built = build(id)
        fonts[id] = built
        return built
    }

    private fun build(id: Int): RsFont? {
        if (!decoded[id]) {
            decoded[id] = true
            try {
                decoder.load(definitions, cache, id)
            } catch (e: Exception) {
                System.err.println("Failed to decode font metrics $id: ${e.message}")
                return null
            }
        }
        val definition = definitions[id]
        if (definition.glyphWidths.isEmpty()) {
            return null
        }
        val glyphs = graphics.definition(id)?.graphics ?: return null
        return RsFont.buildAtlas(id, definition, glyphs)
    }

    fun cleanup() {
        fonts.values.forEach { font ->
            if (font != null) {
                glDeleteTextures(font.textureId)
            }
        }
        fonts.clear()
    }
}
