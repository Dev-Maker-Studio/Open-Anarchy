package org.darkan.tools.rsinterface

import org.darkan.tools.engine.render.Canvas2D
import world.gregs.voidps.cache.definition.data.FontDefinition
import world.gregs.voidps.cache.definition.data.IndexedGraphic

class RsFont(
    val id: Int,
    val definition: FontDefinition,
    val textureId: Int,
    private val glyphs: Array<Glyph?>,
    private val atlasWidth: Int,
    private val atlasHeight: Int
) {

    class Glyph(
        val width: Int,
        val height: Int,
        val offsetX: Int,
        val offsetY: Int,
        val atlasX: Int,
        val atlasY: Int
    )

    val verticalSpacing: Int get() = definition.verticalSpacing

    @Suppress("LongParameterList")
    fun drawText(
        canvas: Canvas2D,
        text: String,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        colour: Int,
        shadowArgb: Int,
        horizontalAlign: Int,
        verticalAlign: Int,
        lineSpacing: Int,
        maxLines: Int
    ) {
        if (text.isEmpty() || width <= 0) {
            return
        }
        val spacing = if (lineSpacing == 0) definition.verticalSpacing.coerceAtLeast(1) else lineSpacing
        val topPadding = definition.topPadding
        val bottomPadding = definition.bottomPadding

        val wraps = height >= spacing + bottomPadding + topPadding || height >= spacing * 2
        var lines = if (wraps) {
            definition.splitLines(text, widths = intArrayOf(width), icons = null)
        } else {
            definition.splitLines(text, widths = null, icons = null)
        }
        if (lines.isEmpty()) {
            return
        }
        if (maxLines > 0 && lines.size >= maxLines) {
            lines = lines.take(maxLines).toMutableList().also { truncated ->
                truncated[truncated.size - 1] = definition.truncateText(truncated.last(), width)
            }
        }

        var effectiveSpacing = spacing
        var lineY = when (verticalAlign) {
            ALIGN_START -> y + topPadding
            ALIGN_CENTRE -> y + topPadding +
                (height - topPadding - bottomPadding - effectiveSpacing * (lines.size - 1)) / 2
            ALIGN_END -> y + height - bottomPadding - effectiveSpacing * (lines.size - 1)
            else -> {
                val slack = (height - topPadding - bottomPadding - effectiveSpacing * (lines.size - 1)) /
                    (lines.size + 1)
                val extra = slack.coerceAtLeast(0)
                effectiveSpacing += extra
                y + topPadding + extra
            }
        }

        for (line in lines) {
            val lineX = when (horizontalAlign) {
                ALIGN_CENTRE -> x + (width - definition.textWidth(line, null)) / 2
                ALIGN_END -> x + width - definition.textWidth(line, null)
                else -> x
            }
            drawLine(canvas, line, lineX, lineY, colour, shadowArgb)
            lineY += effectiveSpacing
        }
    }

    private fun drawLine(canvas: Canvas2D, line: String, x: Int, y: Int, colour: Int, shadowArgb: Int) {
        var penX = x
        val top = y - definition.verticalSpacing
        var previous = -1
        var tagStart = -1
        var currentColour = colour
        val baseAlpha = colour and ALPHA_MASK

        var index = 0
        while (index < line.length) {
            val character = line[index]
            if (character == '<') {
                tagStart = index
                index++
                continue
            }
            if (tagStart != -1) {
                if (character != '>') {
                    index++
                    continue
                }
                val tag = line.substring(tagStart + 1, index)
                tagStart = -1
                index++
                val substitute = entity(tag)
                if (substitute == null) {
                    currentColour = applyTag(tag, currentColour, colour, baseAlpha)
                    previous = -1
                    continue
                }
                penX = drawGlyph(canvas, substitute, penX, top, currentColour, shadowArgb, previous)
                previous = glyphIndex(substitute)
                continue
            }
            penX = drawGlyph(canvas, character, penX, top, currentColour, shadowArgb, previous)
            previous = glyphIndex(character)
            index++
        }
    }

    private fun drawGlyph(
        canvas: Canvas2D,
        character: Char,
        penX: Int,
        top: Int,
        colour: Int,
        shadowArgb: Int,
        previous: Int
    ): Int {
        val code = glyphIndex(character)
        var x = penX
        val kerning = definition.kerningAdjustments
        if (kerning != null && previous != -1) {
            x += kerning[previous][code].toInt()
        }
        val glyph = glyphs[code]
        if (glyph != null && glyph.width > 0 && glyph.height > 0) {
            val drawX = (x + glyph.offsetX).toFloat()
            val drawY = (top + glyph.offsetY).toFloat()
            val u0 = glyph.atlasX.toFloat() / atlasWidth
            val v0 = glyph.atlasY.toFloat() / atlasHeight
            val u1 = (glyph.atlasX + glyph.width).toFloat() / atlasWidth
            val v1 = (glyph.atlasY + glyph.height).toFloat() / atlasHeight
            if (shadowArgb != -1 && (shadowArgb and ALPHA_MASK) != 0) {
                canvas.drawTexture(
                    textureId, drawX + 1f, drawY + 1f,
                    glyph.width.toFloat(), glyph.height.toFloat(), shadowArgb, u0, v0, u1, v1
                )
            }
            canvas.drawTexture(
                textureId, drawX, drawY,
                glyph.width.toFloat(), glyph.height.toFloat(), colour, u0, v0, u1, v1
            )
        }
        return x + (definition.glyphWidths[code].toInt() and 0xff)
    }

    private fun applyTag(tag: String, current: Int, original: Int, alpha: Int): Int {
        if (tag.startsWith("col=")) {
            val rgb = tag.substring(4).toIntOrNull(16) ?: return current
            return alpha or (rgb and 0xffffff)
        }
        if (tag == "/col") {
            return alpha or (original and 0xffffff)
        }
        return current
    }

    fun textWidth(text: String): Int = definition.textWidth(text, null)

    fun lineCount(text: String, width: Int): Int {
        if (text.isEmpty()) return 1
        if (width <= 0) return 1
        return definition.splitLines(text, widths = intArrayOf(width), icons = null).size.coerceAtLeast(1)
    }

    companion object {
        private const val ALIGN_START = 0
        private const val ALIGN_CENTRE = 1
        private const val ALIGN_END = 2
        private const val ALPHA_MASK = 0xff shl 24

        private fun entity(tag: String): Char? = when (tag) {
            "lt" -> '<'
            "gt" -> '>'
            "nbsp" -> '\u00a0'
            "shy" -> '\u00ad'
            "times" -> '\u00d7'
            "euro" -> '\u20ac'
            "copy" -> '\u00a9'
            "reg" -> '\u00ae'
            else -> null
        }

        fun glyphIndex(character: Char): Int {
            val code = character.code
            if (code in 1..127 || code in 160..255) {
                return code
            }
            val mapped = when (code) {
                8364 -> -128
                8218 -> -126
                402 -> -125
                8222 -> -124
                8230 -> -123
                8224 -> -122
                8225 -> -121
                710 -> -120
                8240 -> -119
                352 -> -118
                8249 -> -117
                338 -> -116
                381 -> -114
                8216 -> -111
                8217 -> -110
                8220 -> -109
                8221 -> -108
                8226 -> -107
                8211 -> -106
                8212 -> -105
                732 -> -104
                8482 -> -103
                353 -> -102
                8250 -> -101
                339 -> -100
                382 -> -98
                376 -> -97
                else -> 63
            }
            return mapped and 0xff
        }

        fun buildAtlas(id: Int, definition: FontDefinition, graphics: Array<IndexedGraphic>): RsFont? {
            var cellWidth = 1
            var cellHeight = 1
            for (graphic in graphics) {
                cellWidth = maxOf(cellWidth, graphic.width)
                cellHeight = maxOf(cellHeight, graphic.height)
            }
            val columns = 16
            val rows = (graphics.size + columns - 1) / columns
            if (rows <= 0) {
                return null
            }
            val atlasWidth = cellWidth * columns
            val atlasHeight = cellHeight * rows
            val pixels = IntArray(atlasWidth * atlasHeight)
            val glyphs = arrayOfNulls<Glyph>(256)

            for (index in graphics.indices) {
                if (index >= glyphs.size) {
                    break
                }
                val graphic = graphics[index]
                val atlasX = (index % columns) * cellWidth
                val atlasY = (index / columns) * cellHeight
                glyphs[index] = Glyph(graphic.width, graphic.height, graphic.offsetX, graphic.offsetY, atlasX, atlasY)
                val alpha = graphic.alpha
                for (row in 0 until graphic.height) {
                    for (column in 0 until graphic.width) {
                        val source = column + row * graphic.width
                        val paletteIndex = graphic.raster[source].toInt() and 0xff
                        val coverage = if (alpha != null) {
                            alpha[source].toInt() and 0xff
                        } else {
                            if (paletteIndex == 0) 0 else 255
                        }
                        if (coverage == 0) {
                            continue
                        }
                        pixels[(atlasX + column) + (atlasY + row) * atlasWidth] = (coverage shl 24) or 0xffffff
                    }
                }
            }

            val textureId = UiTextures.createArgb(pixels, atlasWidth, atlasHeight)
            return RsFont(id, definition, textureId, glyphs, atlasWidth, atlasHeight)
        }
    }
}
