package org.darkan.tools.ui

import imgui.ImDrawList
import imgui.ImGui
import imgui.flag.ImGuiMouseButton
import imgui.flag.ImGuiStyleVar
import imgui.flag.ImGuiWindowFlags
import imgui.type.ImBoolean
import imgui.type.ImInt
import org.darkan.tools.engine.render.RenderTarget
import kotlin.math.floor
import kotlin.math.roundToInt

class StagePanel(private val target: RenderTarget) {

    var visible: Boolean = true

    val stageWidth = ImInt(DEFAULT_WIDTH)
    val stageHeight = ImInt(DEFAULT_HEIGHT)

    var zoom: Float = 1f
        private set

    var matchPanel: Boolean = false
        private set

    var fitToPanel: Boolean = false
        private set

    var showGrid: Boolean = false
        private set

    var gridSize: Int = 16
        private set

    var showCheckerboard: Boolean = true
        private set


    var imageX: Float = 0f
        private set
    var imageY: Float = 0f
        private set
    var imageScale: Float = 1f
        private set

    var pointerInside: Boolean = false
        private set

    var pointerX: Int = -1
        private set
    var pointerY: Int = -1
        private set

    var hovered: Boolean = false
        private set

    private var panX = 0f
    private var panY = 0f

    private var ready = false

    var toolbar: (() -> Unit)? = null

    var overlay: (() -> Unit)? = null

    var status: String = ""

    fun setZoom(value: Float) {
        zoom = value.coerceIn(MIN_ZOOM, MAX_ZOOM)
    }

    fun screenX(stageX: Int): Float = imageX + stageX * imageScale

    fun screenY(stageY: Int): Float = imageY + stageY * imageScale

    fun resetView() {
        panX = 0f
        panY = 0f
        zoom = 1f
        fitToPanel = false
    }

    fun render(fps: Int): Boolean {
        ready = false
        if (!visible) {
            return false
        }

        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0f, 0f)
        val open = ImGui.begin("Stage", ImGuiWindowFlags.NoScrollbar or ImGuiWindowFlags.NoScrollWithMouse)
        ImGui.popStyleVar()
        if (!open) {
            ImGui.end()
            return false
        }

        drawToolbar(fps)

        val availableWidth = ImGui.getContentRegionAvailX()
        val availableHeight = ImGui.getContentRegionAvailY()
        if (availableWidth < 1f || availableHeight < 1f) {
            ImGui.end()
            return false
        }

        if (matchPanel) {
            stageWidth.set(availableWidth.toInt().coerceIn(1, MAX_STAGE))
            stageHeight.set(availableHeight.toInt().coerceIn(1, MAX_STAGE))
        }
        val width = stageWidth.get().coerceIn(1, MAX_STAGE)
        val height = stageHeight.get().coerceIn(1, MAX_STAGE)

        if (!target.resize(width, height)) {
            ImGuiText.disabled("Could not allocate a ${width}x$height render target.")
            ImGui.end()
            return false
        }

        if (fitToPanel || matchPanel) {
            zoom = minOf(availableWidth / width, availableHeight / height).coerceIn(MIN_ZOOM, MAX_ZOOM)
        }
        handleZoomInput()

        val drawWidth = width * zoom
        val drawHeight = height * zoom
        val slackX = maxOf(0f, drawWidth - availableWidth)
        val slackY = maxOf(0f, drawHeight - availableHeight)
        panX = panX.coerceIn(-slackX, slackX)
        panY = panY.coerceIn(-slackY, slackY)

        val originX = ImGui.getCursorScreenPosX()
        val originY = ImGui.getCursorScreenPosY()
        imageX = originX + ((availableWidth - drawWidth) / 2f + panX).let { floor(it) }
        imageY = originY + ((availableHeight - drawHeight) / 2f + panY).let { floor(it) }
        imageScale = zoom

        val drawList = ImGui.getWindowDrawList()
        if (showCheckerboard) {
            drawCheckerboard(drawList, imageX, imageY, drawWidth, drawHeight)
        }

        ImGui.setCursorScreenPos(originX, originY)
        ImGui.invisibleButton("##stage", availableWidth, availableHeight)
        val panelHovered = ImGui.isItemHovered()

        drawList.addImage(
            target.textureId.toLong(),
            imageX, imageY,
            imageX + drawWidth, imageY + drawHeight,
            0f, 1f,
            1f, 0f,
        )

        if (showGrid && zoom >= GRID_MIN_ZOOM) {
            drawGrid(drawList, imageX, imageY, drawWidth, drawHeight)
        }
        drawList.addRect(
            imageX - 1f, imageY - 1f,
            imageX + drawWidth + 1f, imageY + drawHeight + 1f,
            BORDER_COLOUR,
        )

        updatePointer(width, height, drawWidth, drawHeight, panelHovered)
        handlePanInput(panelHovered)

        overlay?.invoke()

        drawStatus(width, height)
        ImGui.end()
        ready = true
        return true
    }

    fun shouldRender(): Boolean = ready

    private fun drawToolbar(fps: Int) {
        ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 6f, 3f)
        ImGui.dummy(0f, 1f)
        ImGui.indent(6f)

        val match = ImBoolean(matchPanel)
        if (ImGui.checkbox("Match panel", match)) {
            matchPanel = match.get()
        }
        if (!matchPanel) {
            ImGui.sameLine()
            val fit = ImBoolean(fitToPanel)
            if (ImGui.checkbox("Fit", fit)) {
                fitToPanel = fit.get()
            }
            ImGui.sameLine()
            ImGui.setNextItemWidth(70f)
            if (ImGui.inputInt("##w", stageWidth, 0)) {
                stageWidth.set(stageWidth.get().coerceIn(1, MAX_STAGE))
            }
            ImGui.sameLine()
            ImGuiText.disabled("x")
            ImGui.sameLine()
            ImGui.setNextItemWidth(70f)
            if (ImGui.inputInt("##h", stageHeight, 0)) {
                stageHeight.set(stageHeight.get().coerceIn(1, MAX_STAGE))
            }
            ImGui.sameLine()
            for ((label, size) in PRESETS) {
                if (ImGui.smallButton(label)) {
                    stageWidth.set(size.first)
                    stageHeight.set(size.second)
                    fitToPanel = false
                }
                ImGui.sameLine()
            }
        }

        ImGuiText.disabled("|")
        ImGui.sameLine()
        ImGuiText.disabled("%.0f%%".format(zoom * 100f))
        ImGui.sameLine()
        if (ImGui.smallButton("1x")) {
            setZoom(1f)
            fitToPanel = false
        }
        ImGui.sameLine()
        if (ImGui.smallButton("2x")) {
            setZoom(2f)
            fitToPanel = false
        }
        ImGui.sameLine()
        if (ImGui.smallButton("reset")) {
            resetView()
        }

        ImGui.sameLine()
        ImGuiText.disabled("|")
        ImGui.sameLine()
        val grid = ImBoolean(showGrid)
        if (ImGui.checkbox("Grid", grid)) {
            showGrid = grid.get()
        }
        if (showGrid) {
            ImGui.sameLine()
            ImGui.setNextItemWidth(60f)
            val size = ImInt(gridSize)
            if (ImGui.inputInt("##grid", size, 0)) {
                gridSize = size.get().coerceIn(1, 256)
            }
        }
        ImGui.sameLine()
        val checker = ImBoolean(showCheckerboard)
        if (ImGui.checkbox("Checker", checker)) {
            showCheckerboard = checker.get()
        }

        toolbar?.let {
            ImGui.sameLine()
            ImGuiText.disabled("|")
            ImGui.sameLine()
            it()
        }

        ImGui.sameLine()
        ImGuiText.disabled("   $fps FPS")

        ImGui.unindent(6f)
        ImGui.popStyleVar()
        ImGui.separator()
    }

    private fun handleZoomInput() {
        if (!ImGui.isWindowHovered() || !ImGui.getIO().keyCtrl) {
            return
        }
        val wheel = ImGui.getIO().mouseWheel
        if (wheel == 0f) {
            return
        }
        val before = zoom
        setZoom(zoom * if (wheel > 0f) ZOOM_STEP else 1f / ZOOM_STEP)
        fitToPanel = false
        if (zoom == before) {
            return
        }
        val anchorX = ImGui.getIO().mousePosX - imageX
        val anchorY = ImGui.getIO().mousePosY - imageY
        panX -= anchorX * (zoom / before - 1f)
        panY -= anchorY * (zoom / before - 1f)
    }

    private fun handlePanInput(panelHovered: Boolean) {
        val dragging = ImGui.isMouseDragging(ImGuiMouseButton.Middle) ||
            (ImGui.isMouseDragging(ImGuiMouseButton.Left) && ImGui.getIO().keyAlt)
        if (!dragging || !(panelHovered || ImGui.isWindowFocused())) {
            return
        }
        panX += ImGui.getIO().mouseDeltaX
        panY += ImGui.getIO().mouseDeltaY
    }

    private fun updatePointer(
        stagePixelWidth: Int,
        stagePixelHeight: Int,
        drawWidth: Float,
        drawHeight: Float,
        panelHovered: Boolean,
    ) {
        val mouseX = ImGui.getIO().mousePosX
        val mouseY = ImGui.getIO().mousePosY
        val inside = mouseX >= imageX && mouseX < imageX + drawWidth &&
            mouseY >= imageY && mouseY < imageY + drawHeight
        pointerInside = inside
        hovered = inside && panelHovered
        if (!inside) {
            pointerX = -1
            pointerY = -1
            return
        }
        pointerX = ((mouseX - imageX) / imageScale).toInt().coerceIn(0, stagePixelWidth - 1)
        pointerY = ((mouseY - imageY) / imageScale).toInt().coerceIn(0, stagePixelHeight - 1)
    }

    private fun drawStatus(width: Int, height: Int) {
        ImGui.setCursorScreenPos(
            ImGui.getWindowPosX() + 8f,
            ImGui.getWindowPosY() + ImGui.getWindowSizeY() - ImGui.getTextLineHeightWithSpacing() - 4f,
        )
        val position = if (pointerInside) "$pointerX, $pointerY" else "-"
        ImGuiText.disabled("${width}x$height   pointer $position   $status")
    }

    private fun drawCheckerboard(
        drawList: ImDrawList,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
    ) {
        drawList.addRectFilled(x, y, x + width, y + height, CHECKER_DARK)
        val columns = (width / CHECKER_CELL).toInt() + 1
        val rows = (height / CHECKER_CELL).toInt() + 1
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                if ((row + column) % 2 != 0) {
                    continue
                }
                val cellX = x + column * CHECKER_CELL
                val cellY = y + row * CHECKER_CELL
                drawList.addRectFilled(
                    cellX, cellY,
                    minOf(cellX + CHECKER_CELL, x + width),
                    minOf(cellY + CHECKER_CELL, y + height),
                    CHECKER_LIGHT,
                )
            }
        }
    }

    private fun drawGrid(
        drawList: ImDrawList,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
    ) {
        val step = gridSize * imageScale
        if (step < 2f) {
            return
        }
        var offset = 0f
        while (offset <= width) {
            val lineX = (x + offset).roundToInt().toFloat()
            drawList.addLine(lineX, y, lineX, y + height, GRID_COLOUR)
            offset += step
        }
        offset = 0f
        while (offset <= height) {
            val lineY = (y + offset).roundToInt().toFloat()
            drawList.addLine(x, lineY, x + width, lineY, GRID_COLOUR)
            offset += step
        }
    }

    companion object {
        const val DEFAULT_WIDTH = 512
        const val DEFAULT_HEIGHT = 334
        private const val MAX_STAGE = 4096

        private const val MIN_ZOOM = 0.25f
        private const val MAX_ZOOM = 16f
        private const val ZOOM_STEP = 1.25f
        private const val GRID_MIN_ZOOM = 0.5f

        private const val CHECKER_CELL = 8f

        private const val CHECKER_DARK = 0xff2b2b2bL.toInt()
        private const val CHECKER_LIGHT = 0xff333333L.toInt()
        private const val GRID_COLOUR = 0x30ffffffL.toInt()
        private const val BORDER_COLOUR = 0xff5a5a5aL.toInt()

        private val PRESETS = listOf(
            "512" to (512 to 334),
            "765" to (765 to 503),
            "1024" to (1024 to 768),
        )
    }
}
