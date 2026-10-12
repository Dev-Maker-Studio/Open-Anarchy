package org.darkan.tools.rsinterface

import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.definition.data.InterfaceComponentDefinition
import world.gregs.voidps.cache.definition.data.InterfaceComponentHook

object LayoutMode {
    const val SIZE_ABSOLUTE = 0

    const val SIZE_INSET = 1

    const val SIZE_PROPORTIONAL = 2

    const val SIZE_UNCHANGED = 3

    const val SIZE_ASPECT = 4

    const val POSITION_ABSOLUTE = 0

    const val POSITION_CENTRE = 1

    const val POSITION_END = 2

    const val POSITION_PROPORTIONAL = 3

    const val POSITION_PROPORTIONAL_CENTRE = 4

    const val POSITION_PROPORTIONAL_END = 5
}

class TemplateComponent(
    val definition: InterfaceComponentDefinition,
    val parent: Int = ComponentTemplates.ATTACH
)

class ComponentTemplate(
    val id: String,
    val name: String,
    val description: String,
    val category: String,
    val create: () -> List<TemplateComponent>
)

object ComponentTemplates {

    const val ATTACH = ComponentDefinitions.ROOT

    const val BASIC = "Basic"
    const val PRESET = "Presets"

    var defaultGraphicId: Int = -1

    var defaultFontId: Int = -1

    private const val WHITE = 0xffffff
    private const val PANEL_GREY = 0x3d3529
    private const val BORDER_GREY = 0x766a55

    @Suppress("LongMethod")
    fun defaults(type: Int): InterfaceComponentDefinition {
        val definition = InterfaceComponentDefinition(type = type)
        definition.baseWidth = DEFAULT_SIZE
        definition.baseHeight = DEFAULT_SIZE
        when (type) {
            ComponentType.CONTAINER -> {
                definition.scrollWidth = 0
                definition.scrollHeight = 0
                definition.disableHover = false
            }
            ComponentType.FIGURE -> {
                definition.baseHeight = DEFAULT_SIZE / 2
                definition.colour = PANEL_GREY
                definition.filled = true
                definition.alpha = 0
            }
            ComponentType.TEXT -> {
                definition.baseHeight = TEXT_HEIGHT
                definition.fontId = defaultFontId
                definition.text = "Text"
                definition.colour = WHITE
                definition.lineHeight = 0
                definition.horizontalTextAlign = 0
                definition.verticalTextAlign = 0
                definition.shaded = false
                definition.alpha = 0
            }
            ComponentType.GRAPHIC -> {
                definition.baseWidth = GRAPHIC_SIZE
                definition.baseHeight = GRAPHIC_SIZE
                definition.defaultImage = defaultGraphicId
                definition.alpha = 0
                definition.colour = 0
                definition.rotation = 0
            }
            ComponentType.MODEL -> {
                definition.defaultMediaType = MediaType.RAW_MODEL
                definition.defaultMediaId = 0
                definition.animation = -1
                definition.graphicScale = MODEL_SCALE
                definition.centreType = false
            }
            ComponentType.LINE -> {
                definition.baseHeight = 0
                definition.lineWidth = 1
                definition.colour = WHITE
                definition.lineMirrored = false
            }
        }
        return definition
    }

    fun hook(script: Int, vararg args: Any): InterfaceComponentHook =
        InterfaceComponentHook(script, arrayOf(*args))

    const val EVENT_SOURCE_COMPONENT = -2147483645

    const val EVENT_CLICKED_OPTION = -2147483643

    val all: List<ComponentTemplate> = listOf(
        ComponentTemplate(
            "layer", "Layer", "Empty container. The only type that lays out children.", BASIC
        ) { single(defaults(ComponentType.CONTAINER)) },
        ComponentTemplate(
            "unclipped", "Unclipped layer",
            "Type 2. Never narrows the clip rectangle, so children can draw outside it.", BASIC
        ) { single(defaults(ComponentType.TYPE_2)) },
        ComponentTemplate(
            "rectangle", "Rectangle", "Filled or outlined block of colour.", BASIC
        ) { single(defaults(ComponentType.FIGURE)) },
        ComponentTemplate(
            "text", "Text", "Single line of text in a cache font.", BASIC
        ) { single(defaults(ComponentType.TEXT)) },
        ComponentTemplate(
            "graphic", "Graphic", "One frame of a cache graphic group.", BASIC
        ) { single(defaults(ComponentType.GRAPHIC)) },
        ComponentTemplate(
            "model", "Model", "Model viewport; media type 1 is a raw cache model.", BASIC
        ) { single(defaults(ComponentType.MODEL)) },
        ComponentTemplate(
            "line", "Line", "Straight line from the component's origin to its extent.", BASIC
        ) { single(defaults(ComponentType.LINE)) },
        ComponentTemplate(
            "scroll-layer", "Scrollable layer",
            "Container with a scroll area taller than itself, which is what makes the " +
                "client draw a scrollbar and clamp scrollY to the overflow.", PRESET
        ) { single(scrollLayer()) },
        ComponentTemplate(
            "backdrop", "Backdrop",
            "Rectangle sized by inset so it always fills its parent exactly.", PRESET
        ) { single(backdrop()) },
        ComponentTemplate(
            "label", "Centred label",
            "Text centred on both axes and stretched across its parent.", PRESET
        ) { single(label()) },
        ComponentTemplate(
            "graphic-button", "Graphic button",
            "Graphic with a right click option and a centred caption, ready for an onClick hook.",
            PRESET,
            ::graphicButton
        ),
        ComponentTemplate(
            "model-viewport", "Model viewport",
            "Layer with a model inside it, centred and clipped to the layer.", PRESET,
            ::modelViewport
        ),
        ComponentTemplate(
            "window", "Window frame",
            "Layer with a backdrop, a title bar caption and a close button; fill in the " +
                "graphic ids from the cache.", PRESET,
            ::windowFrame
        )
    )

    private val index: Map<String, ComponentTemplate> = all.associateBy { it.id }

    fun byId(id: String): ComponentTemplate? = index[id]

    val byCategory: Map<String, List<ComponentTemplate>> = all.groupBy { it.category }

    fun forType(type: Int): ComponentTemplate? = when (type) {
        ComponentType.CONTAINER -> byId("layer")
        ComponentType.TYPE_2 -> byId("unclipped")
        ComponentType.FIGURE -> byId("rectangle")
        ComponentType.TEXT -> byId("text")
        ComponentType.GRAPHIC -> byId("graphic")
        ComponentType.MODEL -> byId("model")
        ComponentType.LINE -> byId("line")
        else -> null
    }

    fun extract(
        document: InterfaceDocument,
        rootIndex: Int,
        id: String,
        name: String,
        description: String = "Extracted from interface ${document.id} component $rootIndex",
        category: String = PRESET
    ): ComponentTemplate? {
        val subtree = document.subtree(rootIndex)
        if (subtree.isEmpty()) {
            return null
        }
        val positions = HashMap<Int, Int>(subtree.size)
        subtree.forEachIndexed { offset, node -> positions[node] = offset }
        val sources = subtree.map { document.components[it] }
        val parents = subtree.map { node ->
            if (node == rootIndex) ATTACH else positions[document.components[node].parent] ?: ATTACH
        }
        return ComponentTemplate(id, name, description, category) {
            sources.mapIndexed { offset, source ->
                TemplateComponent(ComponentDefinitions.deepCopy(source), parents[offset])
            }
        }
    }

    fun extract(
        cache: Cache,
        interfaceId: Int,
        rootIndex: Int,
        id: String,
        name: String,
        description: String = "Extracted from interface $interfaceId component $rootIndex",
        category: String = PRESET
    ): ComponentTemplate? =
        extract(InterfaceDocument.decode(cache, interfaceId), rootIndex, id, name, description, category)


    private fun single(definition: InterfaceComponentDefinition) = listOf(TemplateComponent(definition))

    private fun scrollLayer(): InterfaceComponentDefinition {
        val layer = defaults(ComponentType.CONTAINER)
        layer.baseWidth = SCROLL_WIDTH
        layer.baseHeight = SCROLL_HEIGHT
        layer.scrollWidth = SCROLL_WIDTH
        layer.scrollHeight = SCROLL_HEIGHT * 2
        return layer
    }

    private fun backdrop(): InterfaceComponentDefinition {
        val rectangle = defaults(ComponentType.FIGURE)
        rectangle.baseWidth = 0
        rectangle.baseHeight = 0
        rectangle.horizontalSizeMode = LayoutMode.SIZE_INSET.toByte()
        rectangle.verticalSizeMode = LayoutMode.SIZE_INSET.toByte()
        rectangle.colour = PANEL_GREY
        rectangle.filled = true
        return rectangle
    }

    private fun label(): InterfaceComponentDefinition {
        val text = defaults(ComponentType.TEXT)
        text.baseWidth = 0
        text.horizontalSizeMode = LayoutMode.SIZE_INSET.toByte()
        text.horizontalTextAlign = 1
        text.verticalTextAlign = 1
        return text
    }

    private fun graphicButton(): List<TemplateComponent> {
        val graphic = defaults(ComponentType.GRAPHIC)
        graphic.baseWidth = BUTTON_WIDTH
        graphic.baseHeight = BUTTON_HEIGHT
        graphic.name = "Button"
        graphic.options = arrayOf("Ok")
        graphic.setting.setting = 0
        val caption = defaults(ComponentType.TEXT)
        caption.baseWidth = 0
        caption.baseHeight = 0
        caption.horizontalSizeMode = LayoutMode.SIZE_INSET.toByte()
        caption.verticalSizeMode = LayoutMode.SIZE_INSET.toByte()
        caption.horizontalTextAlign = 1
        caption.verticalTextAlign = 1
        caption.text = "Ok"
        return listOf(TemplateComponent(graphic), TemplateComponent(caption))
    }

    private fun modelViewport(): List<TemplateComponent> {
        val layer = defaults(ComponentType.CONTAINER)
        layer.baseWidth = VIEWPORT_SIZE
        layer.baseHeight = VIEWPORT_SIZE
        val model = defaults(ComponentType.MODEL)
        model.baseWidth = 0
        model.baseHeight = 0
        model.horizontalSizeMode = LayoutMode.SIZE_INSET.toByte()
        model.verticalSizeMode = LayoutMode.SIZE_INSET.toByte()
        return listOf(TemplateComponent(layer), TemplateComponent(model, parent = 0))
    }

    private fun windowFrame(): List<TemplateComponent> {
        val root = defaults(ComponentType.CONTAINER)
        root.baseWidth = WINDOW_WIDTH
        root.baseHeight = WINDOW_HEIGHT
        root.name = "Window"
        val background = backdrop()
        val border = defaults(ComponentType.FIGURE)
        border.baseWidth = 0
        border.baseHeight = 0
        border.horizontalSizeMode = LayoutMode.SIZE_INSET.toByte()
        border.verticalSizeMode = LayoutMode.SIZE_INSET.toByte()
        border.colour = BORDER_GREY
        border.filled = false
        val title = label()
        title.baseHeight = TITLE_HEIGHT
        title.verticalSizeMode = LayoutMode.SIZE_ABSOLUTE.toByte()
        title.text = "Window"
        val close = defaults(ComponentType.GRAPHIC)
        close.baseWidth = CLOSE_SIZE
        close.baseHeight = CLOSE_SIZE
        close.basePositionX = CLOSE_INSET
        close.basePositionY = CLOSE_INSET
        close.horizontalPositionMode = LayoutMode.POSITION_END.toByte()
        close.name = "Close"
        close.options = arrayOf("Close")
        return listOf(
            TemplateComponent(root),
            TemplateComponent(background, parent = 0),
            TemplateComponent(border, parent = 0),
            TemplateComponent(title, parent = 0),
            TemplateComponent(close, parent = 0)
        )
    }

    private const val DEFAULT_SIZE = 100
    private const val TEXT_HEIGHT = 16
    private const val GRAPHIC_SIZE = 32
    private const val MODEL_SCALE = 100
    private const val SCROLL_WIDTH = 160
    private const val SCROLL_HEIGHT = 200
    private const val BUTTON_WIDTH = 72
    private const val BUTTON_HEIGHT = 24
    private const val VIEWPORT_SIZE = 128
    private const val WINDOW_WIDTH = 256
    private const val WINDOW_HEIGHT = 192
    private const val TITLE_HEIGHT = 20
    private const val CLOSE_SIZE = 16
    private const val CLOSE_INSET = 4
}
