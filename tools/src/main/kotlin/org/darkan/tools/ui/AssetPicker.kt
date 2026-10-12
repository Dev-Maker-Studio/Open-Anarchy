package org.darkan.tools.ui

import imgui.ImGui
import imgui.flag.ImGuiCol
import imgui.flag.ImGuiInputTextFlags
import imgui.type.ImInt
import imgui.type.ImString
import org.darkan.tools.engine.render.ObjIconRequest
import org.darkan.tools.engine.render.ObjIcons
import org.darkan.tools.rsinterface.FontCache
import org.darkan.tools.rsinterface.GraphicCache
import world.gregs.voidps.cache.definition.data.ItemDefinition

class AssetPicker(
    private val graphics: GraphicCache,
    private val fonts: FontCache,
    private val items: (Int) -> ItemDefinition?,
    private val itemCount: Int,
    private val icons: ObjIcons? = null,
) {
    private val graphicFilter = ImString(64)
    private val itemFilter = ImString(64)
    private val graphicJump = ImInt(0)

    private var open: String? = null

    private var pending: ((Int) -> Unit)? = null


    fun graphicField(label: String, value: ImInt): Boolean {
        var changed = false
        ImGui.pushID(label)
        ImGui.setNextItemWidth(90f)
        if (ImGui.inputInt("##id", value)) {
            changed = true
        }
        ImGui.sameLine()
        if (ImGui.button("...")) {
            open = GRAPHIC_POPUP
            pending = { picked -> value.set(picked) }
            ImGui.openPopup(GRAPHIC_POPUP)
        }
        ImGui.sameLine()
        thumbnail(value.get(), THUMBNAIL)
        ImGui.sameLine()
        ImGui.text(label)
        if (drawGraphicPopup()) {
            changed = true
        }
        ImGui.popID()
        return changed
    }

    fun fontField(label: String, value: ImInt): Boolean {
        var changed = false
        ImGui.pushID(label)
        ImGui.setNextItemWidth(90f)
        if (ImGui.inputInt("##id", value)) {
            changed = true
        }
        ImGui.sameLine()
        if (ImGui.smallButton("pick")) {
            open = FONT_POPUP
            pending = { picked -> value.set(picked) }
            ImGui.openPopup(FONT_POPUP)
        }
        ImGui.sameLine()
        val font = fonts.font(value.get())
        if (font == null) {
            ImGuiText.disabled(if (value.get() == -1) "(none)" else "(missing)")
        } else {
            ImGui.text("ok")
        }
        ImGui.sameLine()
        ImGui.text(label)
        if (drawFontPopup()) {
            changed = true
        }
        ImGui.popID()
        return changed
    }

    fun itemField(label: String, value: ImInt, amount: Int = 1): Boolean {
        var changed = false
        ImGui.pushID(label)
        objThumbnail(value.get(), amount)
        ImGui.sameLine()
        ImGui.setNextItemWidth(90f)
        if (ImGui.inputInt("##id", value)) {
            changed = true
        }
        ImGui.sameLine()
        if (ImGui.smallButton("pick")) {
            open = ITEM_POPUP
            pending = { picked -> value.set(picked) }
            ImGui.openPopup(ITEM_POPUP)
        }
        ImGui.sameLine()
        val name = items(value.get())?.name
        if (name.isNullOrEmpty() || name == "null") {
            ImGuiText.disabled(if (value.get() == -1) "(none)" else "(unnamed)")
        } else {
            ImGui.text(name.take(28))
        }
        ImGui.sameLine()
        ImGui.text(label)
        if (drawItemPopup()) {
            changed = true
        }
        ImGui.popID()
        return changed
    }

    fun thumbnail(id: Int, size: Float) {
        val graphic = if (id < 0) null else graphics.graphic(id)
        if (graphic == null || graphic.width <= 0 || graphic.height <= 0) {
            ImGui.pushStyleColor(ImGuiCol.Button, 0.16f, 0.16f, 0.18f, 1f)
            ImGui.button("##empty", size, size)
            ImGui.popStyleColor()
            return
        }
        val scale = size / maxOf(graphic.width, graphic.height).toFloat()
        ImGui.image(graphic.textureId.toLong(), graphic.width * scale, graphic.height * scale)
    }

    fun objThumbnail(obj: Int, amount: Int = 1) {
        val icon = if (obj < 0) null else icons?.icon(ObjIconRequest(obj, amount.coerceAtLeast(1)))
        if (icon == null) {
            ImGui.pushStyleColor(ImGuiCol.Button, 0.16f, 0.16f, 0.18f, 1f)
            ImGui.button("##noicon", ObjIcons.WIDTH.toFloat(), ObjIcons.HEIGHT.toFloat())
            ImGui.popStyleColor()
            return
        }
        ImGui.image(icon.textureId.toLong(), ObjIcons.WIDTH.toFloat(), ObjIcons.HEIGHT.toFloat())
    }


    private fun drawGraphicPopup(): Boolean {
        if (open != GRAPHIC_POPUP) {
            return false
        }
        var picked = false
        ImGui.setNextWindowSize(560f, 520f)
        if (ImGui.beginPopup(GRAPHIC_POPUP)) {
            ImGui.setNextItemWidth(200f)
            ImGui.inputTextWithHint("##filter", "id, or id range like 1000-1200", graphicFilter)
            ImGui.sameLine()
            ImGui.setNextItemWidth(90f)
            if (ImGui.inputInt("go to", graphicJump, 0, 0, ImGuiInputTextFlags.EnterReturnsTrue)) {
                graphicFilter.set(graphicJump.get().toString())
            }
            ImGui.sameLine()
            ImGuiText.disabled("${graphics.count} groups")
            ImGui.separator()

            val range = parseRange(graphicFilter.get(), graphics.count)
            if (ImGui.beginChild("grid", 0f, 0f)) {
                picked = drawGraphicGrid(range)
            }
            ImGui.endChild()
            ImGui.endPopup()
        } else {
            open = null
        }
        return picked
    }

    private fun drawGraphicGrid(range: IntRange): Boolean {
        val available = ImGui.getContentRegionAvailX()
        val columns = ((available + CELL_SPACING) / (CELL + CELL_SPACING)).toInt().coerceAtLeast(1)
        val total = range.last - range.first + 1
        if (total <= 0) {
            ImGuiText.disabled("Nothing in range.")
            return false
        }
        val rows = (total + columns - 1) / columns
        val rowHeight = CELL + CELL_SPACING

        val scrollTop = ImGui.getScrollY()
        val viewHeight = ImGui.getContentRegionAvailY()
        val firstRow = (scrollTop / rowHeight).toInt().coerceAtLeast(0)
        val lastRow = ((scrollTop + viewHeight) / rowHeight).toInt().coerceAtMost(rows - 1)

        if (firstRow > 0) {
            ImGui.dummy(1f, firstRow * rowHeight)
        }

        var picked = false
        for (row in firstRow..lastRow) {
            for (column in 0 until columns) {
                val index = row * columns + column
                if (index >= total) {
                    break
                }
                if (column > 0) {
                    ImGui.sameLine()
                }
                val id = range.first + index
                if (graphicCell(id)) {
                    pending?.invoke(id)
                    picked = true
                    ImGui.closeCurrentPopup()
                    open = null
                }
            }
        }

        val remaining = rows - 1 - lastRow
        if (remaining > 0) {
            ImGui.dummy(1f, remaining * rowHeight)
        }
        return picked
    }

    private fun graphicCell(id: Int): Boolean {
        val graphic = graphics.graphic(id)
        ImGui.pushID(id)
        val clicked = if (graphic == null || graphic.width <= 0 || graphic.height <= 0) {
            ImGui.pushStyleColor(ImGuiCol.Button, 0.14f, 0.14f, 0.15f, 1f)
            val hit = ImGui.button("##cell", CELL, CELL)
            ImGui.popStyleColor()
            hit
        } else {
            val scale = (CELL - 6f) / maxOf(graphic.width, graphic.height).toFloat()
            ImGui.imageButton("##graphic", graphic.textureId.toLong(), graphic.width * scale, graphic.height * scale)
        }
        if (ImGui.isItemHovered()) {
            ImGui.beginTooltip()
            ImGui.text("graphic $id")
            if (graphic != null) {
                ImGuiText.disabled("${graphic.width} x ${graphic.height}")
            } else {
                ImGuiText.disabled("not in the cache")
            }
            ImGui.endTooltip()
        }
        ImGui.popID()
        return clicked
    }

    private fun drawFontPopup(): Boolean {
        if (open != FONT_POPUP) {
            return false
        }
        var picked = false
        if (ImGui.beginPopup(FONT_POPUP)) {
            if (ImGui.selectable("-1  (none)")) {
                pending?.invoke(-1)
                picked = true
                ImGui.closeCurrentPopup()
                open = null
            }
            for (id in 0 until fonts.count) {
                if (fonts.font(id) == null) {
                    continue
                }
                if (ImGui.selectable("$id")) {
                    pending?.invoke(id)
                    picked = true
                    ImGui.closeCurrentPopup()
                    open = null
                }
            }
            ImGui.endPopup()
        } else {
            open = null
        }
        return picked
    }

    private fun drawItemPopup(): Boolean {
        if (open != ITEM_POPUP) {
            return false
        }
        var picked = false
        ImGui.setNextWindowSize(420f, 480f)
        if (ImGui.beginPopup(ITEM_POPUP)) {
            ImGui.setNextItemWidth(-1f)
            ImGui.inputTextWithHint("##filter", "name or id", itemFilter)
            ImGui.separator()
            val filter = itemFilter.get().trim()
            if (filter.isEmpty()) {
                ImGuiText.disabled("Type to search ${itemCount} items.")
            } else if (ImGui.beginChild("items", 0f, 0f)) {
                var shown = 0
                val byId = filter.toIntOrNull()
                if (byId != null) {
                    itemRow(byId)?.let { if (it) picked = true }
                    shown++
                }
                var id = 0
                while (id < itemCount && shown < ITEM_RESULT_LIMIT) {
                    val name = items(id)?.name
                    if (!name.isNullOrEmpty() && name != "null" && name.contains(filter, ignoreCase = true)) {
                        itemRow(id)?.let { if (it) picked = true }
                        shown++
                    }
                    id++
                }
                if (shown >= ITEM_RESULT_LIMIT) {
                    ImGuiText.disabled("...first $ITEM_RESULT_LIMIT matches; narrow the search.")
                }
            }
            if (filter.isNotEmpty()) {
                ImGui.endChild()
            }
            ImGui.endPopup()
        } else {
            open = null
        }
        return picked
    }

    private fun itemRow(id: Int): Boolean? {
        val name = items(id)?.name ?: return null
        if (ImGui.selectable("$id  $name")) {
            pending?.invoke(id)
            ImGui.closeCurrentPopup()
            open = null
            return true
        }
        return false
    }

    private fun parseRange(text: String, limit: Int): IntRange {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return 0..(limit - 1).coerceAtLeast(0)
        }
        val dash = trimmed.indexOf('-')
        if (dash > 0) {
            val from = trimmed.substring(0, dash).trim().toIntOrNull()
            val to = trimmed.substring(dash + 1).trim().toIntOrNull()
            if (from != null && to != null && to >= from) {
                return from.coerceIn(0, limit - 1)..to.coerceIn(0, limit - 1)
            }
        }
        val single = trimmed.toIntOrNull()
        if (single != null) {
            val from = (single - 8).coerceAtLeast(0)
            val to = (single + 120).coerceAtMost(limit - 1)
            return from..to
        }
        return 0..(limit - 1).coerceAtLeast(0)
    }

    companion object {
        private const val GRAPHIC_POPUP = "pick graphic"
        private const val FONT_POPUP = "pick font"
        private const val ITEM_POPUP = "pick item"

        private const val CELL = 56f
        private const val CELL_SPACING = 6f
        private const val THUMBNAIL = 22f
        private const val ITEM_RESULT_LIMIT = 200
    }
}
