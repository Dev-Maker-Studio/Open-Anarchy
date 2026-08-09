package org.darkan.tools.rsinterface

import world.gregs.voidps.cache.definition.data.InterfaceComponentDefinition

/**
 * Mutable render state for a single interface component.
 *
 * This is the seam between the immutable cache definition and everything that
 * mutates a live interface - the editor's property panel today, a CS2
 * interpreter tomorrow. [InterfaceRenderer] reads *only* from here and never
 * from [definition]; the definition is kept purely as the reset baseline and as
 * a source of the fields nothing can change (type, parent, hook scripts).
 *
 * ### Driving this from CS2
 *
 * Resolve a component with [InterfaceInstance.state] (component index) or
 * [InterfaceInstance.stateByHash] (the packed `interfaceId shl 16 or index`
 * hash that CS2 and the network protocol pass around), then:
 *
 * - **Single-field setters assign the property directly.** `IF_SETHIDE` is
 *   `state.hidden = value`, `IF_SETTEXT` is `state.text = value`,
 *   `IF_SETCOLOUR` is `state.colour = rgb`, `IF_SETTRANS` is
 *   `state.transparency = value`, `IF_SETGRAPHIC` is `state.spriteId = id`,
 *   and so on. Nothing else needs to be called.
 * - **The methods below exist for the ops that move more than one field, or
 *   that change the box model.** Everything that feeds the layout pass
 *   ([basePositionX], [baseWidth], the position/size modes, the scroll fields)
 *   has a private setter precisely so those go through a method that marks the
 *   interface dirty - an interpreter can never forget to trigger a relayout.
 *
 * | Opcode | Call |
 * |---|---|
 * | `IF_SETPOSITION` | [setPosition] |
 * | `IF_SETSIZE` | [setSize] |
 * | `IF_SETASPECT` | [setAspect] |
 * | `IF_SETSCROLLPOS` | [setScrollPos] |
 * | `IF_SETSCROLLSIZE` | [setScrollSize] |
 * | `IF_SETMODEL` | [setModel] |
 * | `IF_SETMODELANGLE` | [setModelAngle] |
 * | `IF_SETOBJECT` | [setObject] |
 * | `IF_SETTEXTALIGN` | [setTextAlign] |
 */
class ComponentState(
    /** The immutable cache definition this state was seeded from. */
    val definition: InterfaceComponentDefinition,
    /** Index of this component within its interface's component array. */
    val index: Int,
    /** Id of the interface that owns this component. */
    val interfaceId: Int
) {
    /** Packed component hash used by CS2 and the network protocol. */
    val hash: Int = (interfaceId shl 16) or index

    /** Invoked whenever a change invalidates the computed box model. */
    internal var invalidateLayout: () -> Unit = {}

    // --- Box model inputs (mutated through the methods at the bottom) ------

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

    /**
     * Aspect ratio numerator/denominator used by size modes of 4. The cache
     * never encodes these; the client defaults them to 1:1 and only CS2 changes
     * them, so they live here rather than on the definition.
     */
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

    // --- Common draw state ------------------------------------------------

    var hidden: Boolean = definition.hidden

    /** RGB colour, 0xRRGGBB. Alpha comes from [transparency], not from here. */
    var colour: Int = definition.colour

    /** 0 = fully opaque, 255 = fully transparent (the client's convention). */
    var transparency: Int = definition.alpha

    // --- Rectangle (type 3) -----------------------------------------------

    var filled: Boolean = definition.filled

    // --- Text (type 4) ----------------------------------------------------

    var text: String = definition.text
    var fontId: Int = definition.fontId
    var horizontalTextAlign: Int = definition.horizontalTextAlign
    var verticalTextAlign: Int = definition.verticalTextAlign
    var lineSpacing: Int = definition.lineHeight
    var maxTextLines: Int = definition.lineCount
    var shadow: Boolean = definition.shaded

    // --- Sprite (type 5) --------------------------------------------------

    var spriteId: Int = definition.defaultImage

    /** 2D rotation; the cache stores 65536 units per full turn. */
    var spriteAngle: Int = definition.imageRotation

    /** The cache's sprite flag bit 0: repeat the sprite instead of stretching. */
    var tiling: Boolean = definition.aBoolean4861
    var flipVertical: Boolean = definition.flipVertical
    var flipHorizontal: Boolean = definition.flipHorizontal

    /** Outline thickness added around the sprite by the client's `draw`. */
    var borderThickness: Int = definition.rotation

    /** Drop-shadow colour baked into the sprite by the client's `draw`. */
    var spriteShadow: Int = definition.backgroundColour

    // --- Model (type 6) ---------------------------------------------------

    var mediaType: Int = definition.defaultMediaType
    var mediaId: Int = definition.defaultMediaId
    var animation: Int = definition.animation
    var modelOriginX: Int = definition.viewportX
    var modelOriginY: Int = definition.viewportY
    var modelOriginZ: Int = definition.viewportZ
    var modelPitch: Int = definition.spritePitch
    var modelRoll: Int = definition.spriteRoll
    var modelYaw: Int = definition.spriteYaw
    var modelScale: Int = definition.spriteScale

    /** Model viewport aspect, the client's `aspectWidth` / `aspectHeight`. */
    var modelAspectWidth: Int = definition.viewportWidth
    var modelAspectHeight: Int = definition.viewportHeight

    // --- Line (type 9) ----------------------------------------------------

    var lineWidth: Int = definition.lineWidth

    /** When set the line runs bottom-left to top-right instead of top-left down. */
    var lineMirrored: Boolean = definition.lineMirrored

    // --- Item slot --------------------------------------------------------

    /**
     * Item occupying this component, or -1. Sprite, text and model components
     * all change behaviour when an item is set: sprites and models draw the
     * item's icon, text draws the item's name.
     */
    var itemId: Int = -1
    var itemAmount: Int = 0

    // --- Computed box model (output of the layout pass) --------------------

    /** Position relative to the parent's content origin, in pixels. */
    var x: Int = 0
        internal set
    var y: Int = 0
        internal set
    var width: Int = definition.baseWidth
        internal set
    var height: Int = definition.baseHeight
        internal set

    /** Absolute screen position, valid after the last draw pass. */
    var screenX: Int = 0
        internal set
    var screenY: Int = 0
        internal set

    /** The component's type; never mutable, so it is read straight through. */
    val type: Int get() = definition.type

    /** Index of the parent component within the same interface, or -1. */
    val parent: Int get() = definition.parent

    // --- Layout mutators --------------------------------------------------

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

    // --- Multi-field mutators ---------------------------------------------

    /** `IF_SETMODEL`: points the component at a raw cache model. */
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

    /** `IF_SETOBJECT`: puts an item in the component; -1 clears it. */
    fun setObject(itemId: Int, amount: Int) {
        this.itemId = itemId
        this.itemAmount = amount
    }

    fun setTextAlign(horizontal: Int, vertical: Int, lineSpacing: Int) {
        horizontalTextAlign = horizontal
        verticalTextAlign = vertical
        this.lineSpacing = lineSpacing
    }

    /** Restores every field to the value decoded from the cache. */
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
        spriteId = definition.defaultImage
        spriteAngle = definition.imageRotation
        tiling = definition.aBoolean4861
        flipVertical = definition.flipVertical
        flipHorizontal = definition.flipHorizontal
        borderThickness = definition.rotation
        spriteShadow = definition.backgroundColour
        mediaType = definition.defaultMediaType
        mediaId = definition.defaultMediaId
        animation = definition.animation
        modelOriginX = definition.viewportX
        modelOriginY = definition.viewportY
        modelOriginZ = definition.viewportZ
        modelPitch = definition.spritePitch
        modelRoll = definition.spriteRoll
        modelYaw = definition.spriteYaw
        modelScale = definition.spriteScale
        modelAspectWidth = definition.viewportWidth
        modelAspectHeight = definition.viewportHeight
        lineWidth = definition.lineWidth
        lineMirrored = definition.lineMirrored
        itemId = -1
        itemAmount = 0
        invalidateLayout()
    }
}
