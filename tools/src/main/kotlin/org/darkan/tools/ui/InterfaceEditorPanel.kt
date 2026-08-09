package org.darkan.tools.ui

import imgui.ImGui
import imgui.flag.ImGuiCol
import imgui.flag.ImGuiTreeNodeFlags
import imgui.flag.ImGuiWindowFlags
import imgui.type.ImBoolean
import imgui.type.ImInt
import imgui.type.ImString
import org.darkan.tools.rsinterface.ComponentState
import org.darkan.tools.rsinterface.ComponentType
import org.darkan.tools.rsinterface.InterfaceInstance

/**
 * Editor UI for the interface viewer: an interface picker, the component tree,
 * and a live property editor for the selected component.
 *
 * Every property widget writes straight into the selected [ComponentState], the
 * same object the renderer reads, so edits show up on the very next frame. The
 * panel never touches the cache definition, which stays available as the reset
 * baseline behind the "Revert" buttons.
 */
class InterfaceEditorPanel {

    var visible: Boolean = true

    /** Currently displayed interface, set by the viewer. */
    var instance: InterfaceInstance? = null

    /** Index of the selected component, or -1. */
    var selected: Int = -1
        private set

    /** Notified when the interface id in the picker changes. */
    var onInterfaceChanged: ((Int) -> Unit)? = null

    /** Toggle for the renderer's per-component debug outlines. */
    var debugOutlines: Boolean = false
        private set

    private val interfaceIdInput = ImInt(0)
    private val textInput = ImString(512)

    private val positionX = ImInt(0)
    private val positionY = ImInt(0)
    private val sizeWidth = ImInt(0)
    private val sizeHeight = ImInt(0)
    private val positionModeX = intArrayOf(0)
    private val positionModeY = intArrayOf(0)
    private val sizeModeWidth = intArrayOf(0)
    private val sizeModeHeight = intArrayOf(0)
    private val transparency = intArrayOf(0)
    private val spriteId = ImInt(0)
    private val modelId = ImInt(0)
    private val itemId = ImInt(-1)
    private val itemAmount = ImInt(0)
    private val fontId = ImInt(-1)
    private val lineWidth = ImInt(1)
    private val scrollX = ImInt(0)
    private val scrollY = ImInt(0)
    private val colour = FloatArray(3)

    private var syncedIndex = -1
    private var browserFirstFrame = true
    private var propertiesFirstFrame = true

    fun setInterfaceId(id: Int) {
        interfaceIdInput.set(id)
    }

    /** Selects a component by index; -1 clears the selection. */
    fun select(index: Int) {
        if (selected != index) {
            selected = index
            syncedIndex = -1
        }
    }

    fun render(screenWidth: Int, screenHeight: Int) {
        if (!visible) {
            return
        }
        renderBrowser(screenHeight)
        renderProperties(screenWidth, screenHeight)
    }

    private fun renderBrowser(screenHeight: Int) {
        ImGui.setNextWindowSizeConstraints(280f, 200f, 460f, screenHeight * 0.9f)
        if (browserFirstFrame) {
            ImGui.setNextWindowPos(10f, 10f)
            ImGui.setNextWindowSize(320f, (screenHeight * 0.7f).coerceAtLeast(300f))
            browserFirstFrame = false
        }

        if (ImGui.begin("Interface Tree", ImGuiWindowFlags.None)) {
            ImGui.setNextItemWidth(140f)
            if (ImGui.inputInt("Interface", interfaceIdInput)) {
                interfaceIdInput.set(interfaceIdInput.get().coerceAtLeast(0))
                select(-1)
                onInterfaceChanged?.invoke(interfaceIdInput.get())
            }

            val current = instance
            if (current == null) {
                ImGui.textDisabled("No interface loaded")
                ImGui.end()
                return
            }

            ImGui.text("Components: ${current.components.size}")
            ImGui.sameLine()
            if (ImGui.smallButton("Revert all")) {
                current.reset()
                syncedIndex = -1
            }

            val outlines = ImBoolean(debugOutlines)
            if (ImGui.checkbox("Outline components", outlines)) {
                debugOutlines = outlines.get()
            }

            ImGui.separator()
            if (ImGui.beginChild("ComponentTree", 0f, 0f, true)) {
                renderTreeLevel(current, -1)
            }
            ImGui.endChild()
        }
        ImGui.end()
    }

    /** Recursively renders one level of the component tree in draw order. */
    private fun renderTreeLevel(instance: InterfaceInstance, parentIndex: Int) {
        for (state in instance.children(parentIndex)) {
            val children = instance.children(state.index)
            val label = "[${state.index}] ${ComponentType.name(state.type)}${describe(state)}"
            var flags = ImGuiTreeNodeFlags.OpenOnArrow or ImGuiTreeNodeFlags.SpanAvailWidth
            if (children.isEmpty()) {
                flags = flags or ImGuiTreeNodeFlags.Leaf or ImGuiTreeNodeFlags.NoTreePushOnOpen
            }
            if (selected == state.index) {
                flags = flags or ImGuiTreeNodeFlags.Selected
            }
            if (state.hidden) {
                ImGui.pushStyleColor(ImGuiCol.Text, 0.55f, 0.55f, 0.55f, 1f)
            }
            val open = ImGui.treeNodeEx("component_${state.index}", flags, label)
            if (state.hidden) {
                ImGui.popStyleColor()
            }
            if (ImGui.isItemClicked()) {
                select(state.index)
            }
            if (open && children.isNotEmpty()) {
                renderTreeLevel(instance, state.index)
                ImGui.treePop()
            }
        }
    }

    /** A short hint of what a component holds, to make the tree scannable. */
    private fun describe(state: ComponentState): String = when (state.type) {
        ComponentType.TEXT -> if (state.text.isEmpty()) "" else " \"${state.text.take(24)}\""
        ComponentType.SPRITE -> if (state.spriteId == -1) "" else " #${state.spriteId}"
        ComponentType.MODEL -> if (state.mediaId <= 0) "" else " #${state.mediaId}"
        else -> ""
    }

    private fun renderProperties(screenWidth: Int, screenHeight: Int) {
        ImGui.setNextWindowSizeConstraints(300f, 200f, 480f, screenHeight * 0.9f)
        if (propertiesFirstFrame) {
            ImGui.setNextWindowPos(screenWidth - 340f, 10f)
            ImGui.setNextWindowSize(330f, (screenHeight * 0.7f).coerceAtLeast(300f))
            propertiesFirstFrame = false
        }

        if (ImGui.begin("Component", ImGuiWindowFlags.None)) {
            val state = instance?.components?.getOrNull(selected)
            if (state == null) {
                ImGui.textDisabled("Select a component")
                ImGui.end()
                return
            }
            if (syncedIndex != state.index) {
                syncWidgets(state)
                syncedIndex = state.index
            }
            renderCommonProperties(state)
            renderTypeProperties(state)

            ImGui.separator()
            if (ImGui.button("Revert component")) {
                state.reset()
                syncedIndex = -1
            }
        }
        ImGui.end()
    }

    private fun renderCommonProperties(state: ComponentState) {
        ImGui.text("Hash: 0x%08X".format(state.hash))
        ImGui.text("Type: ${ComponentType.name(state.type)} (${state.type})")
        ImGui.text("Parent: ${if (state.parent == -1) "root" else state.parent.toString()}")
        ImGui.text("Screen: ${state.screenX}, ${state.screenY} (${state.width} x ${state.height})")
        if (state.definition.name.isNotEmpty()) {
            ImGui.textWrapped("Name: ${state.definition.name}")
        }
        ImGui.separator()

        val hidden = ImBoolean(state.hidden)
        if (ImGui.checkbox("Hidden", hidden)) {
            state.hidden = hidden.get()
        }

        var positionChanged = false
        ImGui.setNextItemWidth(90f)
        positionChanged = ImGui.inputInt("X", positionX) || positionChanged
        ImGui.setNextItemWidth(90f)
        positionChanged = ImGui.inputInt("Y", positionY) || positionChanged
        ImGui.setNextItemWidth(120f)
        positionChanged = ImGui.sliderInt("X mode", positionModeX, 0, 5) || positionChanged
        ImGui.setNextItemWidth(120f)
        positionChanged = ImGui.sliderInt("Y mode", positionModeY, 0, 5) || positionChanged
        if (positionChanged) {
            state.setPosition(positionX.get(), positionY.get(), positionModeX[0], positionModeY[0])
        }

        var sizeChanged = false
        ImGui.setNextItemWidth(90f)
        sizeChanged = ImGui.inputInt("Width", sizeWidth) || sizeChanged
        ImGui.setNextItemWidth(90f)
        sizeChanged = ImGui.inputInt("Height", sizeHeight) || sizeChanged
        ImGui.setNextItemWidth(120f)
        sizeChanged = ImGui.sliderInt("W mode", sizeModeWidth, 0, 4) || sizeChanged
        ImGui.setNextItemWidth(120f)
        sizeChanged = ImGui.sliderInt("H mode", sizeModeHeight, 0, 4) || sizeChanged
        if (sizeChanged) {
            state.setSize(sizeWidth.get(), sizeHeight.get(), sizeModeWidth[0], sizeModeHeight[0])
        }

        if (ImGui.colorEdit3("Colour", colour)) {
            state.colour = packColour(colour)
        }
        ImGui.setNextItemWidth(140f)
        if (ImGui.sliderInt("Transparency", transparency, 0, 255)) {
            state.transparency = transparency[0].coerceIn(0, 255)
        }
    }

    private fun renderTypeProperties(state: ComponentState) {
        ImGui.separator()
        when (state.type) {
            ComponentType.CONTAINER -> {
                var changed = false
                ImGui.setNextItemWidth(90f)
                changed = ImGui.inputInt("Scroll X", scrollX) || changed
                ImGui.setNextItemWidth(90f)
                changed = ImGui.inputInt("Scroll Y", scrollY) || changed
                if (changed) {
                    state.setScrollPos(scrollX.get(), scrollY.get())
                }
                ImGui.text("Scroll size: ${state.scrollWidth} x ${state.scrollHeight}")
            }
            ComponentType.FIGURE -> {
                val filled = ImBoolean(state.filled)
                if (ImGui.checkbox("Filled", filled)) {
                    state.filled = filled.get()
                }
            }
            ComponentType.TEXT -> {
                if (ImGui.inputTextMultiline("Text", textInput, -1f, 70f)) {
                    state.text = textInput.get()
                }
                ImGui.setNextItemWidth(90f)
                if (ImGui.inputInt("Font", fontId)) {
                    state.fontId = fontId.get()
                }
                val shadow = ImBoolean(state.shadow)
                if (ImGui.checkbox("Shadow", shadow)) {
                    state.shadow = shadow.get()
                }
                ImGui.text("Align: h=${state.horizontalTextAlign} v=${state.verticalTextAlign}")
                ImGui.text("Line spacing: ${state.lineSpacing}, max lines: ${state.maxTextLines}")
            }
            ComponentType.SPRITE -> {
                ImGui.setNextItemWidth(120f)
                if (ImGui.inputInt("Sprite", spriteId)) {
                    state.spriteId = spriteId.get()
                }
                val tiled = ImBoolean(state.tiling)
                if (ImGui.checkbox("Tiled", tiled)) {
                    state.tiling = tiled.get()
                }
                val flipV = ImBoolean(state.flipVertical)
                if (ImGui.checkbox("Flip V", flipV)) {
                    state.flipVertical = flipV.get()
                }
                ImGui.sameLine()
                val flipH = ImBoolean(state.flipHorizontal)
                if (ImGui.checkbox("Flip H", flipH)) {
                    state.flipHorizontal = flipH.get()
                }
                ImGui.text("Angle: ${state.spriteAngle}")
                renderItemProperties(state)
            }
            ComponentType.MODEL -> {
                ImGui.setNextItemWidth(120f)
                if (ImGui.inputInt("Model", modelId)) {
                    state.setModel(modelId.get())
                }
                ImGui.text("Media type: ${state.mediaType}")
                ImGui.text("Pitch/roll/yaw: ${state.modelPitch}/${state.modelRoll}/${state.modelYaw}")
                ImGui.text("Zoom: ${state.modelScale}")
                renderItemProperties(state)
            }
            ComponentType.LINE -> {
                ImGui.setNextItemWidth(90f)
                if (ImGui.inputInt("Thickness", lineWidth)) {
                    state.lineWidth = lineWidth.get().coerceAtLeast(1)
                }
                val mirrored = ImBoolean(state.lineMirrored)
                if (ImGui.checkbox("Mirrored", mirrored)) {
                    state.lineMirrored = mirrored.get()
                }
            }
        }
    }

    /** Item slot fields, shared by the sprite and model component editors. */
    private fun renderItemProperties(state: ComponentState) {
        var changed = false
        ImGui.setNextItemWidth(90f)
        changed = ImGui.inputInt("Item", itemId) || changed
        ImGui.setNextItemWidth(90f)
        changed = ImGui.inputInt("Amount", itemAmount) || changed
        if (changed) {
            state.setObject(itemId.get(), itemAmount.get())
        }
    }

    /** Pulls the selected component's values into the widget buffers. */
    private fun syncWidgets(state: ComponentState) {
        positionX.set(state.basePositionX)
        positionY.set(state.basePositionY)
        sizeWidth.set(state.baseWidth)
        sizeHeight.set(state.baseHeight)
        positionModeX[0] = state.horizontalPositionMode
        positionModeY[0] = state.verticalPositionMode
        sizeModeWidth[0] = state.horizontalSizeMode
        sizeModeHeight[0] = state.verticalSizeMode
        transparency[0] = state.transparency
        spriteId.set(state.spriteId)
        modelId.set(state.mediaId)
        itemId.set(state.itemId)
        itemAmount.set(state.itemAmount)
        fontId.set(state.fontId)
        lineWidth.set(state.lineWidth)
        scrollX.set(state.scrollX)
        scrollY.set(state.scrollY)
        textInput.set(state.text)
        colour[0] = ((state.colour shr 16) and 0xff) / 255f
        colour[1] = ((state.colour shr 8) and 0xff) / 255f
        colour[2] = (state.colour and 0xff) / 255f
    }

    private fun packColour(rgb: FloatArray): Int {
        val red = (rgb[0] * 255f).toInt().coerceIn(0, 255)
        val green = (rgb[1] * 255f).toInt().coerceIn(0, 255)
        val blue = (rgb[2] * 255f).toInt().coerceIn(0, 255)
        return (red shl 16) or (green shl 8) or blue
    }
}
