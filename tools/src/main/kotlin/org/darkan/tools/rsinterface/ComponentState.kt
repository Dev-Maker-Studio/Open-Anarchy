package org.darkan.tools.rsinterface

import org.darkan.tools.engine.render.ObjIconStack
import world.gregs.voidps.cache.definition.data.InterfaceComponentDefinition

class ComponentState(
    val definition: InterfaceComponentDefinition,
    val index: Int,
    val interfaceId: Int,
    val slotId: Int = -1
) {
    val hash: Int = (interfaceId shl 16) or index

    val runtime: Boolean get() = slotId >= 0

    internal var invalidateLayout: () -> Unit = {}


    var basePositionX: Int = definition.basePositionX
        private set
    var basePositionY: Int = definition.basePositionY
        private set
    var baseWidth: Int = definition.baseWidth
        private set
    var baseHeight: Int = definition.baseHeight
        private set
    var horizontalSizeMode: Int = definition.horizontalSizeMode.toInt()
        private set
    var verticalSizeMode: Int = definition.verticalSizeMode.toInt()
        private set
    var horizontalPositionMode: Int = definition.horizontalPositionMode.toInt()
        private set
    var verticalPositionMode: Int = definition.verticalPositionMode.toInt()
        private set

    var aspectX: Int = 1
        private set
    var aspectY: Int = 1
        private set

    var scrollWidth: Int = definition.scrollWidth
        private set
    var scrollHeight: Int = definition.scrollHeight
        private set
    var scrollX: Int = 0
        internal set
    var scrollY: Int = 0
        internal set


    var hidden: Boolean = definition.hidden

    var colour: Int = definition.colour

    var transparency: Int = definition.alpha


    var filled: Boolean = definition.filled


    var text: String = definition.text
    var fontId: Int = definition.fontId
    var horizontalTextAlign: Int = definition.horizontalTextAlign
    var verticalTextAlign: Int = definition.verticalTextAlign
    var lineSpacing: Int = definition.lineHeight
    var maxTextLines: Int = definition.lineCount
    var shadow: Boolean = definition.shaded


    var graphicId: Int = definition.defaultImage

    var graphicAngle: Int = definition.imageRotation

    var tiling: Boolean = definition.aBoolean4861
    var flipVertical: Boolean = definition.flipVertical
    var flipHorizontal: Boolean = definition.flipHorizontal

    var borderThickness: Int = definition.rotation

    var graphicShadow: Int = definition.backgroundColour


    var mediaType: Int = definition.defaultMediaType
    var mediaId: Int = definition.defaultMediaId
    var animation: Int = definition.animation
    var modelOriginX: Int = definition.viewportX
    var modelOriginY: Int = definition.viewportY
    var modelOriginZ: Int = definition.viewportZ
    var modelPitch: Int = definition.graphicPitch
    var modelRoll: Int = definition.graphicRoll
    var modelYaw: Int = definition.graphicYaw
    var modelScale: Int = definition.graphicScale

    var modelAspectWidth: Int = definition.viewportWidth
    var modelAspectHeight: Int = definition.viewportHeight


    var lineWidth: Int = definition.lineWidth

    var lineMirrored: Boolean = definition.lineMirrored


    var itemId: Int = -1
    var itemAmount: Int = 0

    var stackNumbers: ObjIconStack = ObjIconStack.WHEN_MULTIPLE


    var events: Int = definition.setting.setting

    var options: Array<String?> = optionArray(definition.options)
        private set

    var opBase: String = definition.name

    var continueText: String? = definition.optionOverride

    var useOption: String = definition.useOption

    var noClickThrough: Boolean = definition.disableHover

    var clickable: Boolean = definition.clickable

    var optionCursors: IntArray = definition.mouseIcon?.copyOf() ?: IntArray(0)
        private set

    var hoverCursor: Int = -1

    var targetCursor: Int = definition.anInt4698

    var targetLeaveCursor: Int = definition.anInt4839

    var dragDeadzone: Int = definition.anInt4708

    var dragDeadTime: Int = definition.anInt4795

    var dragType: Int = definition.anInt4860

    var depthChild: ComponentState? = null
        internal set

    var optionKeyCodes: IntArray = keyArray(definition.keyRepeats)
        private set
    var optionKeyModifiers: IntArray = keyArray(definition.keyCodes)
        private set
    var optionKeyRepeats: IntArray = definition.keyModifiers?.copyOf(OPTION_KEYS) ?: IntArray(OPTION_KEYS)
        private set

    var optionKeyChars: IntArray = IntArray(OPTION_KEYS)
        private set

    val optionKeyCooldowns: IntArray = IntArray(OPTION_KEYS)


    var slotColumns: Int = 0
        private set
    var slotRows: Int = 0
        private set
    var slotWidth: Int = 0
        private set
    var slotHeight: Int = 0
        private set
    var slotPaddingX: Int = 0
        private set
    var slotPaddingY: Int = 0
        private set


    var pressed: Boolean = false
        internal set

    var hovered: Boolean = false
        internal set

    var scripted: Boolean = false
        internal set

    var optionHook: Boolean = definition.params != null
        internal set


    var x: Int = 0
        internal set
    var y: Int = 0
        internal set
    var width: Int = definition.baseWidth
        internal set
    var height: Int = definition.baseHeight
        internal set

    var screenX: Int = 0
        internal set
    var screenY: Int = 0
        internal set

    val type: Int get() = definition.type

    val parent: Int get() = definition.parent

    val contentType: Int get() = definition.contentType


    fun optionEnabled(index: Int): Boolean = ((events shr (index + 1)) and 0x1) != 0

    val continueEnabled: Boolean get() = (events and 0x1) != 0

    val useOptionFlags: Int get() = (events shr 11) and 0x7f

    val dragDepth: Int get() = (events shr 18) and 0x7

    val dropTarget: Boolean get() = ((events shr 21) and 0x1) != 0

    val useTarget: Boolean get() = ((events shr 22) and 0x1) != 0

    val dragUnbounded: Boolean get() = ((events shr 23) and 0x1) != 0

    val draggable: Boolean get() = dragDepth > 0 || dragUnbounded || depthChild != null

    val interactive: Boolean
        get() = type == ComponentType.CONTAINER ||
            events != 0 ||
            contentType != 0 ||
            definition.hasScript ||
            scripted

    fun optionAt(index: Int): String? {
        if (!optionEnabled(index) && !optionHook) {
            return null
        }
        val label = options.getOrNull(index) ?: return null
        return label.takeIf { it.isNotBlank() }
    }

    fun optionCursor(index: Int): Int {
        if (!optionEnabled(index) && !optionHook) {
            return -1
        }
        return optionCursors.getOrElse(index) { -1 }
    }

    fun slotAt(localX: Int, localY: Int): Int {
        if (slotColumns <= 0 || slotRows <= 0 || slotWidth <= 0 || slotHeight <= 0) {
            return -1
        }
        val strideX = slotWidth + slotPaddingX
        val strideY = slotHeight + slotPaddingY
        if (localX < 0 || localY < 0) {
            return -1
        }
        val column = localX / strideX
        val row = localY / strideY
        if (column >= slotColumns || row >= slotRows) {
            return -1
        }
        if (localX - (column * strideX) >= slotWidth || localY - (row * strideY) >= slotHeight) {
            return -1
        }
        return (row * slotColumns) + column
    }


    fun setPosition(x: Int, y: Int, xMode: Int = horizontalPositionMode, yMode: Int = verticalPositionMode) {
        basePositionX = x
        basePositionY = y
        horizontalPositionMode = xMode
        verticalPositionMode = yMode
        invalidateLayout()
    }

    fun setSize(width: Int, height: Int, widthMode: Int = horizontalSizeMode, heightMode: Int = verticalSizeMode) {
        baseWidth = width
        baseHeight = height
        horizontalSizeMode = widthMode
        verticalSizeMode = heightMode
        invalidateLayout()
    }

    fun setAspect(aspectX: Int, aspectY: Int) {
        this.aspectX = aspectX.coerceAtLeast(1)
        this.aspectY = aspectY.coerceAtLeast(1)
        invalidateLayout()
    }

    fun setScrollPos(scrollX: Int, scrollY: Int) {
        this.scrollX = scrollX
        this.scrollY = scrollY
        invalidateLayout()
    }

    fun setScrollSize(scrollWidth: Int, scrollHeight: Int) {
        this.scrollWidth = scrollWidth
        this.scrollHeight = scrollHeight
        invalidateLayout()
    }


    fun setModel(modelId: Int) {
        mediaType = MediaType.RAW_MODEL
        mediaId = modelId
    }

    fun setModelAngle(pitch: Int, roll: Int, yaw: Int, scale: Int) {
        modelPitch = pitch
        modelRoll = roll
        modelYaw = yaw
        modelScale = scale
    }

    fun setObject(itemId: Int, amount: Int, stackNumbers: ObjIconStack = ObjIconStack.WHEN_MULTIPLE) {
        this.itemId = itemId
        this.itemAmount = amount
        this.stackNumbers = stackNumbers
    }

    fun setTextAlign(horizontal: Int, vertical: Int, lineSpacing: Int) {
        horizontalTextAlign = horizontal
        verticalTextAlign = vertical
        this.lineSpacing = lineSpacing
    }


    fun setOption(index: Int, label: String) {
        if (index < 0 || index >= OPTION_COUNT) {
            return
        }
        if (options.size <= index) {
            options = options.copyOf(index + 1)
        }
        options[index] = label
    }

    fun clearOptions() {
        options = arrayOfNulls(0)
    }

    fun setOptionCursor(index: Int, cursor: Int) {
        if (index < 0 || index >= OPTION_COUNT) {
            return
        }
        if (optionCursors.size <= index) {
            val grown = IntArray(index + 1) { -1 }
            optionCursors.copyInto(grown)
            optionCursors = grown
        }
        optionCursors[index] = cursor
    }

    fun setOptionKey(index: Int, code: Int, modifiers: Int, repeat: Int = 0) {
        if (index < 0 || index >= OPTION_KEYS) {
            return
        }
        optionKeyCodes[index] = code
        optionKeyModifiers[index] = modifiers
        optionKeyRepeats[index] = repeat
        optionKeyCooldowns[index] = 0
        refreshClickable()
    }

    fun setOptionKeyChar(index: Int, char: Int) {
        if (index < 0 || index >= OPTION_KEYS) {
            return
        }
        optionKeyChars[index] = char
        refreshClickable()
    }

    fun setDraggable(deadzone: Int, deadTime: Int) {
        dragDeadzone = deadzone
        dragDeadTime = deadTime
    }

    fun setSlotGrid(
        columns: Int,
        rows: Int,
        slotWidth: Int,
        slotHeight: Int,
        paddingX: Int = 0,
        paddingY: Int = 0
    ) {
        slotColumns = columns.coerceAtLeast(0)
        slotRows = rows.coerceAtLeast(0)
        this.slotWidth = slotWidth.coerceAtLeast(0)
        this.slotHeight = slotHeight.coerceAtLeast(0)
        slotPaddingX = paddingX.coerceAtLeast(0)
        slotPaddingY = paddingY.coerceAtLeast(0)
    }

    private fun refreshClickable() {
        clickable = optionKeyCodes.indices.any { optionKeyCodes[it] != 0 || optionKeyChars[it] > 0 }
    }

    fun reset() {
        basePositionX = definition.basePositionX
        basePositionY = definition.basePositionY
        baseWidth = definition.baseWidth
        baseHeight = definition.baseHeight
        horizontalSizeMode = definition.horizontalSizeMode.toInt()
        verticalSizeMode = definition.verticalSizeMode.toInt()
        horizontalPositionMode = definition.horizontalPositionMode.toInt()
        verticalPositionMode = definition.verticalPositionMode.toInt()
        aspectX = 1
        aspectY = 1
        scrollWidth = definition.scrollWidth
        scrollHeight = definition.scrollHeight
        scrollX = 0
        scrollY = 0
        hidden = definition.hidden
        colour = definition.colour
        transparency = definition.alpha
        filled = definition.filled
        text = definition.text
        fontId = definition.fontId
        horizontalTextAlign = definition.horizontalTextAlign
        verticalTextAlign = definition.verticalTextAlign
        lineSpacing = definition.lineHeight
        maxTextLines = definition.lineCount
        shadow = definition.shaded
        graphicId = definition.defaultImage
        graphicAngle = definition.imageRotation
        tiling = definition.aBoolean4861
        flipVertical = definition.flipVertical
        flipHorizontal = definition.flipHorizontal
        borderThickness = definition.rotation
        graphicShadow = definition.backgroundColour
        mediaType = definition.defaultMediaType
        mediaId = definition.defaultMediaId
        animation = definition.animation
        modelOriginX = definition.viewportX
        modelOriginY = definition.viewportY
        modelOriginZ = definition.viewportZ
        modelPitch = definition.graphicPitch
        modelRoll = definition.graphicRoll
        modelYaw = definition.graphicYaw
        modelScale = definition.graphicScale
        modelAspectWidth = definition.viewportWidth
        modelAspectHeight = definition.viewportHeight
        lineWidth = definition.lineWidth
        lineMirrored = definition.lineMirrored
        itemId = -1
        itemAmount = 0
        stackNumbers = ObjIconStack.WHEN_MULTIPLE
        events = definition.setting.setting
        options = optionArray(definition.options)
        opBase = definition.name
        continueText = definition.optionOverride
        useOption = definition.useOption
        noClickThrough = definition.disableHover
        clickable = definition.clickable
        optionCursors = definition.mouseIcon?.copyOf() ?: IntArray(0)
        hoverCursor = -1
        targetCursor = definition.anInt4698
        targetLeaveCursor = definition.anInt4839
        dragDeadzone = definition.anInt4708
        dragDeadTime = definition.anInt4795
        dragType = definition.anInt4860
        depthChild = null
        optionKeyCodes = keyArray(definition.keyRepeats)
        optionKeyModifiers = keyArray(definition.keyCodes)
        optionKeyRepeats = definition.keyModifiers?.copyOf(OPTION_KEYS) ?: IntArray(OPTION_KEYS)
        optionKeyChars = IntArray(OPTION_KEYS)
        optionKeyCooldowns.fill(0)
        slotColumns = 0
        slotRows = 0
        slotWidth = 0
        slotHeight = 0
        slotPaddingX = 0
        slotPaddingY = 0
        pressed = false
        hovered = false
        scripted = false
        optionHook = definition.params != null
        invalidateLayout()
    }

    companion object {
        const val OPTION_COUNT = 10

        const val OPTION_KEYS = OPTION_COUNT + 1

        private fun optionArray(source: Array<String>?): Array<String?> =
            Array(source?.size ?: 0) { source?.get(it) }

        private fun keyArray(source: ByteArray?): IntArray {
            val codes = IntArray(OPTION_KEYS)
            if (source != null) {
                for (index in 0 until minOf(source.size, OPTION_KEYS)) {
                    codes[index] = source[index].toInt()
                }
            }
            return codes
        }
    }
}
