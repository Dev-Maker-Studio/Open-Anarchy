package org.darkan.tools.rsinterface

import org.lwjgl.opengl.GL11.glDeleteTextures
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.definition.data.IndexedSprite
import world.gregs.voidps.cache.definition.data.SpriteDefinition
import world.gregs.voidps.cache.definition.decoder.SpriteDecoder

/**
 * A sprite uploaded to the GPU, ready for [org.darkan.tools.engine.render.Canvas2D].
 *
 * [width] and [height] are the *group canvas* size, not the trimmed size: the
 * client's `NativeSprite.getSpriteWidth()` reports the padded size and component
 * layout compares it against the component's own width/height, so the trimmed
 * pixels are re-inset into a full-size canvas at upload time.
 */
class LoadedSprite(
    val textureId: Int,
    val width: Int,
    val height: Int
)

/**
 * Lazily decodes sprite groups from the cache and uploads them as GL textures.
 *
 * Sprites are keyed by id plus the flip flags, because the client flips pixels
 * in the decoded sprite rather than at blit time, and a flipped sprite is a
 * different image as far as the GPU is concerned.
 */
class SpriteCache(private val cache: Cache) {

    private val decoder = SpriteDecoder()
    private val definitions: Array<SpriteDefinition> = decoder.create(decoder.size(cache) + 1)
    private val decoded = BooleanArray(definitions.size)

    private val textures = HashMap<Long, LoadedSprite?>()

    /** Decodes (once) and returns the raw sprite group for [id]. */
    fun definition(id: Int): SpriteDefinition? {
        if (id < 0 || id >= definitions.size) {
            return null
        }
        if (!decoded[id]) {
            decoded[id] = true
            try {
                decoder.load(definitions, cache, id)
            } catch (e: Exception) {
                System.err.println("Failed to decode sprite $id: ${e.message}")
            }
        }
        return if (definitions[id].sprites == null) null else definitions[id]
    }

    /**
     * Returns the uploaded sprite for [id], or null when the group is missing.
     *
     * @param frame index within the sprite group; interface components always
     *   use frame 0, matching the client's `SpriteType.listSingle(index, id, 0)`.
     */
    fun sprite(id: Int, flipVertical: Boolean = false, flipHorizontal: Boolean = false, frame: Int = 0): LoadedSprite? {
        val key = (id.toLong() shl 24) or
            (frame.toLong() shl 2) or
            (if (flipVertical) 2L else 0L) or
            (if (flipHorizontal) 1L else 0L)
        // Misses are cached as nulls too: interfaces routinely reference sprites
        // that are absent from the cache, and retrying every frame would stall.
        if (textures.containsKey(key)) {
            return textures[key]
        }
        val uploaded = upload(id, frame, flipVertical, flipHorizontal)
        textures[key] = uploaded
        return uploaded
    }

    private fun upload(id: Int, frame: Int, flipVertical: Boolean, flipHorizontal: Boolean): LoadedSprite? {
        val sprites = definition(id)?.sprites ?: return null
        val sprite = sprites.getOrNull(frame) ?: return null
        val width = sprite.offsetX + sprite.width + sprite.deltaWidth
        val height = sprite.offsetY + sprite.height + sprite.deltaHeight
        if (width <= 0 || height <= 0) {
            return null
        }

        val pixels = expand(sprite, width, height, flipVertical, flipHorizontal)
        return LoadedSprite(UiTextures.createArgb(pixels, width, height, repeat = true), width, height)
    }

    /**
     * Expands a trimmed, palette-indexed sprite into a full-canvas ARGB image.
     * Palette index 0 is transparent; when the group carries no alpha plane every
     * other index is fully opaque.
     */
    private fun expand(
        sprite: IndexedSprite,
        canvasWidth: Int,
        canvasHeight: Int,
        flipVertical: Boolean,
        flipHorizontal: Boolean
    ): IntArray {
        val pixels = IntArray(canvasWidth * canvasHeight)
        val palette = sprite.palette
        val raster = sprite.raster
        val alpha = sprite.alpha
        for (y in 0 until sprite.height) {
            for (x in 0 until sprite.width) {
                val source = x + y * sprite.width
                val index = raster[source].toInt() and 0xff
                val opacity = if (alpha != null) alpha[source].toInt() and 0xff else if (index == 0) 0 else 255
                if (opacity == 0) {
                    continue
                }
                var destX = x + sprite.offsetX
                var destY = y + sprite.offsetY
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
        textures.values.forEach { sprite ->
            if (sprite != null) {
                glDeleteTextures(sprite.textureId)
            }
        }
        textures.clear()
    }
}
