package org.darkan.tools.rsinterface

import org.darkan.tools.engine.render.Canvas2D
import org.darkan.tools.engine.render.ObjIcon
import org.darkan.tools.engine.render.ObjIconRequest
import org.darkan.tools.engine.render.ObjIcons
import kotlin.math.PI

class InterfaceRenderer(
    private val canvas: Canvas2D,
    private val graphics: GraphicCache,
    private val fonts: FontCache,
    private val models: InterfaceModelRenderer?,
    private val icons: ObjIcons? = null
) {
    var fallbackFontId: Int = -1

    var debugOutlines: Boolean = false

    fun render(instance: InterfaceInstance, x: Int, y: Int, width: Int, height: Int) {
        instance.layout(width, height)
        drawChildren(instance, -1, x, y, x + width, y + height, x, y)
    }

    @Suppress("LongParameterList")
    private fun drawChildren(
        instance: InterfaceInstance,
        parentIndex: Int,
        clipLeft: Int,
        clipTop: Int,
        clipRight: Int,
        clipBottom: Int,
        offsetX: Int,
        offsetY: Int
    ) {
        val visible = canvas.pushClip(clipLeft, clipTop, clipRight, clipBottom)
        if (!visible) {
            canvas.popClip()
            return
        }

        for (state in instance.children(parentIndex)) {
            if (state.hidden) {
                continue
            }
            val startX = state.x + offsetX
            val startY = state.y + offsetY
            state.screenX = startX
            state.screenY = startY

            val box = instance.clipBox(state, startX, startY, clipLeft, clipTop, clipRight, clipBottom)
            if (box[0] >= box[2] || box[1] >= box[3]) {
                continue
            }

            if (state.type == ComponentType.CONTAINER) {
                drawChildren(
                    instance, state.index, box[0], box[1], box[2], box[3],
                    startX - state.scrollX, startY - state.scrollY
                )
                val sub = instance.subInterface(state.index)
                if (sub != null) {
                    val innerWidth = if (state.scrollWidth != 0) state.scrollWidth else state.width
                    val innerHeight = if (state.scrollHeight != 0) state.scrollHeight else state.height
                    sub.layout(innerWidth, innerHeight)
                    drawChildren(sub, -1, box[0], box[1], box[2], box[3], startX, startY)
                }
            }

            drawComponent(state, startX, startY)

            if (debugOutlines) {
                canvas.drawRectOutline(startX, startY, state.width, state.height, DEBUG_OUTLINE)
            }
        }

        canvas.popClip()
    }

    private fun drawComponent(state: ComponentState, startX: Int, startY: Int) {
        when (state.type) {
            ComponentType.FIGURE -> drawRectangle(state, startX, startY)
            ComponentType.TEXT -> drawText(state, startX, startY)
            ComponentType.GRAPHIC -> drawGraphic(state, startX, startY)
            ComponentType.MODEL -> drawModel(state, startX, startY)
            ComponentType.LINE -> drawLine(state, startX, startY)
        }
    }

    private fun drawRectangle(state: ComponentState, startX: Int, startY: Int) {
        val argb = tint(state.colour, state.transparency)
        if (state.filled) {
            canvas.fillRect(startX, startY, state.width, state.height, argb)
        } else {
            canvas.drawRectOutline(startX, startY, state.width, state.height, argb)
        }
    }

    private fun drawText(state: ComponentState, startX: Int, startY: Int) {
        val font = fonts.font(state.fontId) ?: fonts.font(fallbackFontId) ?: return
        val content = textContent(state)
        if (content.isEmpty()) {
            return
        }
        val alpha = 255 - (state.transparency and 0xff)
        val colour = (alpha shl 24) or (state.colour and 0xffffff)
        val shadow = if (state.shadow) (alpha shl 24) else -1
        font.drawText(
            canvas, content, startX, startY, state.width, state.height,
            colour, shadow, state.horizontalTextAlign, state.verticalTextAlign,
            state.lineSpacing, state.maxTextLines
        )
    }

    private fun textContent(state: ComponentState): String {
        if (state.itemId == -1) {
            return state.text
        }
        val definition = models?.item(state.itemId) ?: return state.text
        val name = definition.name
        if (definition.isStackable() || state.itemAmount != 1) {
            if (state.itemAmount != -1) {
                return "$name x${state.itemAmount}"
            }
        }
        return name
    }

    private fun drawGraphic(state: ComponentState, startX: Int, startY: Int) {
        val textureId: Int
        val graphicWidth: Int
        val graphicHeight: Int
        if (state.itemId != -1) {
            val icon = objIcon(state) ?: return
            textureId = icon.textureId
            graphicWidth = ObjIcons.WIDTH
            graphicHeight = ObjIcons.HEIGHT
        } else {
            val graphic = graphics.graphic(state.graphicId, state.flipVertical, state.flipHorizontal) ?: return
            if (graphic.width <= 0 || graphic.height <= 0) {
                return
            }
            textureId = graphic.textureId
            graphicWidth = graphic.width
            graphicHeight = graphic.height
        }
        val argb = graphicTint(state)
        val angle = state.graphicAngle and 0xffff

        if (state.tiling) {
            drawTiledGraphic(state, textureId, graphicWidth, graphicHeight, startX, startY, argb, angle)
            return
        }
        if (angle != 0) {
            val scale = state.width.toFloat() / graphicWidth
            canvas.drawTextureRotated(
                textureId,
                startX + state.width / 2f, startY + state.height / 2f,
                graphicWidth * scale, graphicHeight * scale,
                radians(angle), argb
            )
            return
        }
        canvas.drawTexture(
            textureId,
            startX.toFloat(), startY.toFloat(),
            state.width.toFloat(), state.height.toFloat(),
            argb
        )
    }

    private fun objIcon(state: ComponentState): ObjIcon? {
        val icons = icons ?: return null
        val request = ObjIconRequest(
            state.itemId, state.itemAmount, state.borderThickness,
            OPAQUE or (state.graphicShadow and 0xffffff), state.stackNumbers
        )
        if (icons.isCached(request)) {
            return icons.icon(request)
        }
        canvas.flush()
        val icon = icons.icon(request)
        canvas.restoreState()
        return icon
    }

    @Suppress("LongParameterList")
    private fun drawTiledGraphic(
        state: ComponentState,
        textureId: Int,
        graphicWidth: Int,
        graphicHeight: Int,
        startX: Int,
        startY: Int,
        argb: Int,
        angle: Int
    ) {
        val visible = canvas.pushClip(startX, startY, startX + state.width, startY + state.height)
        if (visible) {
            if (angle != 0) {
                val columns = (state.width + graphicWidth - 1) / graphicWidth
                val rows = (state.height + graphicHeight - 1) / graphicHeight
                for (row in 0 until rows) {
                    for (column in 0 until columns) {
                        canvas.drawTextureRotated(
                            textureId,
                            startX + column * graphicWidth + graphicWidth / 2f,
                            startY + row * graphicHeight + graphicHeight / 2f,
                            graphicWidth.toFloat(), graphicHeight.toFloat(),
                            radians(angle), argb
                        )
                    }
                }
            } else {
                canvas.drawTexture(
                    textureId,
                    startX.toFloat(), startY.toFloat(),
                    state.width.toFloat(), state.height.toFloat(),
                    argb,
                    0f, 0f,
                    state.width.toFloat() / graphicWidth,
                    state.height.toFloat() / graphicHeight
                )
            }
        }
        canvas.popClip()
    }

    private fun drawModel(state: ComponentState, startX: Int, startY: Int) {
        val renderer = models ?: return
        canvas.flush()
        val clip = canvas.clip()
        renderer.render(state, startX, startY, clip)
        canvas.restoreState()
    }

    private fun drawLine(state: ComponentState, startX: Int, startY: Int) {
        val argb = OPAQUE or (state.colour and 0xffffff)
        if (state.lineMirrored) {
            canvas.drawLine(
                startX, startY + state.height,
                startX + state.width, startY,
                argb, state.lineWidth
            )
        } else {
            canvas.drawLine(
                startX, startY,
                startX + state.width, startY + state.height,
                argb, state.lineWidth
            )
        }
    }

    private fun graphicTint(state: ComponentState): Int {
        if (state.colour == 0 && state.transparency == 0) {
            return WHITE
        }
        val rgb = if (state.colour != 0) state.colour and 0xffffff else 0xffffff
        return ((255 - (state.transparency and 0xff)) shl 24) or rgb
    }

    private fun tint(colour: Int, transparency: Int): Int {
        if (transparency == 0) {
            return OPAQUE or (colour and 0xffffff)
        }
        return ((255 - (transparency and 0xff)) shl 24) or (colour and 0xffffff)
    }

    companion object {
        private const val OPAQUE = 0xff shl 24
        private const val WHITE = -1
        private const val DEBUG_OUTLINE = 0x40ff00ff

        private fun radians(angle: Int): Float = -(angle / 65536f) * 2f * PI.toFloat()
    }
}
