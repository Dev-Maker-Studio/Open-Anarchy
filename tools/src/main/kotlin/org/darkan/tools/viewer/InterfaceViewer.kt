package org.darkan.tools.viewer

import imgui.ImGui
import imgui.flag.ImGuiWindowFlags
import imgui.type.ImBoolean
import imgui.type.ImInt
import org.darkan.tools.engine.Engine
import org.darkan.tools.engine.render.Canvas2D
import org.darkan.tools.engine.window.WindowConfig
import org.darkan.tools.cs2.Cs2Analyzer
import org.darkan.tools.cs2.vm.Cs2Event
import org.darkan.tools.cs2.vm.Cs2HookDispatcher
import org.darkan.tools.rsinterface.ComponentState
import org.darkan.tools.rsinterface.ComponentType
import org.darkan.tools.rsinterface.InterfaceCs2Host
import org.darkan.tools.rsinterface.FontCache
import org.darkan.tools.rsinterface.InterfaceInstance
import org.darkan.tools.rsinterface.InterfaceModelRenderer
import org.darkan.tools.rsinterface.InterfaceRenderer
import org.darkan.tools.rsinterface.SpriteCache
import org.darkan.tools.ui.ImGuiLayer
import org.darkan.tools.ui.Cs2HookPanel
import org.darkan.tools.ui.InterfaceEditorPanel
import org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE
import org.lwjgl.glfw.GLFW.GLFW_KEY_F2
import org.lwjgl.glfw.GLFW.GLFW_KEY_F3
import org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT
import org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT
import org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT
import org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT
import org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT
import org.lwjgl.opengl.GL11.glClear
import org.lwjgl.opengl.GL11.glClearColor
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.definition.data.InterfaceDefinition
import world.gregs.voidps.cache.definition.decoder.InterfaceDecoder

/**
 * Interface editor: renders a cache interface the way the client draws it, with
 * a component tree, live property editing and click-to-pick selection.
 *
 * The interface is drawn into a "stage" rectangle centred in the window. The
 * stage stands in for the container the client mounts the interface in: the
 * client only ever lays out one interface against the canvas (the base window),
 * and everything else is laid out against the container it was opened into. The
 * stage is therefore what every root-level component's centred, far-edge and
 * proportional mode resolves against, and getting it wrong slides those
 * components sideways while leaving the absolutely placed ones in place.
 *
 * It defaults to the 512 x 334 fixed-mode game window, which is the size the
 * overwhelming majority of interfaces are authored for - 419 of the 1321
 * interfaces in the cache have a root component sized exactly that. The presets
 * cover the resizable canvas sizes for the interfaces that are built to fill it.
 */
class InterfaceViewer(
    private val cache: Cache,
    startInterfaceId: Int = 0
) : Engine(WindowConfig(title = "Darkan Interface Editor", width = 1600, height = 900)) {

    private lateinit var imGui: ImGuiLayer
    private lateinit var canvas: Canvas2D
    private lateinit var sprites: SpriteCache
    private lateinit var fonts: FontCache
    private lateinit var modelRenderer: InterfaceModelRenderer
    private lateinit var renderer: InterfaceRenderer
    private val editor = InterfaceEditorPanel()

    /** The CS2 runtime driving this interface. */
    private lateinit var scripts: Cs2Analyzer
    private lateinit var cs2Host: InterfaceCs2Host
    private lateinit var dispatcher: Cs2HookDispatcher
    private lateinit var hookPanel: Cs2HookPanel

    /** Fire a component's click hook on click, rather than only selecting it. */
    private var runHooksOnClick = true

    private lateinit var decoder: InterfaceDecoder
    private lateinit var definitions: Array<InterfaceDefinition>
    private lateinit var decoded: BooleanArray

    private var currentInterfaceId = startInterfaceId.coerceAtLeast(0)
    private var instance: InterfaceInstance? = null

    private val stageWidth = ImInt(DEFAULT_STAGE_WIDTH)
    private val stageHeight = ImInt(DEFAULT_STAGE_HEIGHT)
    private var stageMatchesWindow = false
    private var stageFirstFrame = true

    override fun onInit() {
        imGui = ImGuiLayer(window)
        imGui.init()

        canvas = Canvas2D(loadShaderSource("/shaders/ui.vert"), loadShaderSource("/shaders/ui.frag"))
        sprites = SpriteCache(cache)
        fonts = FontCache(cache, sprites)
        modelRenderer = InterfaceModelRenderer(cache, window, renderSystem, colorShader, texturedShader)
        renderer = InterfaceRenderer(canvas, sprites, fonts, modelRenderer)

        decoder = InterfaceDecoder()
        definitions = decoder.create(decoder.size(cache) + 1)
        decoded = BooleanArray(definitions.size)

        // The analyzer resolves script return signatures and param types once,
        // which is what lets the virtual machine keep its stacks straight.
        scripts = Cs2Analyzer(cache)
        scripts.analyse()
        cs2Host = InterfaceCs2Host(
            interfaces = { id -> if (id == currentInterfaceId) instance else null },
            enums = { id -> Cache.enums.getOrNull(id) },
            structs = { id -> Cache.structs.getOrNull(id) },
            params = { id -> Cache.params.getOrNull(id) },
        )
        dispatcher = Cs2HookDispatcher(scripts, cs2Host)
        hookPanel = Cs2HookPanel(dispatcher, cs2Host)

        editor.onInterfaceChanged = { id -> openInterface(id) }
        openInterface(currentInterfaceId)
    }

    /** Decodes (once) and mounts the interface with the given id. */
    private fun openInterface(id: Int) {
        currentInterfaceId = id
        editor.setInterfaceId(id)
        editor.select(-1)
        if (id < 0 || id >= definitions.size) {
            instance = null
            editor.instance = null
            return
        }
        if (!decoded[id]) {
            decoded[id] = true
            try {
                decoder.load(definitions, cache, id)
            } catch (e: Exception) {
                System.err.println("Failed to decode interface $id: ${e.message}")
            }
        }
        val definition = definitions[id]
        instance = if (definition.components == null) null else InterfaceInstance(id, definition)
        editor.instance = instance
        runLoadScripts()
    }

    /**
     * Interfaces build themselves in their components' load scripts, so running
     * them on open is what makes the render match what the client would show.
     */
    private fun runLoadScripts() {
        val current = instance ?: return
        if (!this::dispatcher.isInitialized) return
        cs2Host.clearHooks()
        cs2Host.log.clear()
        for (component in current.components) {
            val hook = component.definition.onLoadScript ?: continue
            cs2Host.setActive(0, component)
            dispatcher.dispatch(hook, Cs2Event(source = component.hash))
        }
    }

    /** Runs whichever callback a component has bound for an event. */
    private fun fireHook(component: ComponentState, event: String, data: Cs2Event) {
        cs2Host.setActive(0, component)
        cs2Host.boundHooks[component.hash]?.get(event)?.let {
            dispatcher.dispatch(it, data)
            return
        }
        val defined = when (event) {
            "CLICK" -> component.definition.onClick
            "MOUSEOVER" -> component.definition.onMouseOver
            "MOUSELEAVE" -> component.definition.onMouseLeaveScript
            else -> null
        } ?: return
        dispatcher.dispatch(defined, data)
    }

    override fun onInput() {
        // The base engine grabs the cursor on right click for its fly cameras;
        // a 2D editor always wants a visible pointer.
        if (input.mouseCaptured) {
            input.releaseMouse()
        }
        if (imGui.wantCaptureKeyboard()) {
            return
        }
        if (input.keyboard.isKeyPressed(GLFW_KEY_LEFT) && currentInterfaceId > 0) {
            openInterface(currentInterfaceId - 1)
        }
        if (input.keyboard.isKeyPressed(GLFW_KEY_RIGHT)) {
            openInterface(currentInterfaceId + 1)
        }
        if (input.keyboard.isKeyPressed(GLFW_KEY_F2)) {
            editor.visible = !editor.visible
        }
        if (input.keyboard.isKeyPressed(GLFW_KEY_F3)) {
            hookPanel.visible = !hookPanel.visible
        }
        if (input.keyboard.isKeyPressed(GLFW_KEY_ESCAPE)) {
            editor.select(-1)
        }
        if (!imGui.wantCaptureMouse() && input.mouse.isButtonPressed(GLFW_MOUSE_BUTTON_LEFT)) {
            pickComponent(input.mouse.x.toInt(), input.mouse.y.toInt())
        }
    }

    /**
     * Picks the component under the cursor. Hits arrive back to front, so the
     * last one is the topmost; layers are only picked when nothing they contain
     * was hit, which is what makes clicking a button select the button and not
     * the panel behind it.
     */
    private fun pickComponent(mouseX: Int, mouseY: Int) {
        val current = instance ?: return
        val width = stageWidth()
        val height = stageHeight()
        val originX = stageOriginX(width)
        val originY = stageOriginY(height)
        val hits = current.hitTest(originX, originY, width, height, mouseX, mouseY)
        val picked = hits.lastOrNull { it.type != ComponentType.CONTAINER } ?: hits.lastOrNull()
        editor.select(picked?.index ?: -1)

        if (runHooksOnClick && picked != null) {
            fireHook(
                picked,
                "CLICK",
                Cs2Event(
                    mouseX = mouseX - originX,
                    mouseY = mouseY - originY,
                    source = picked.hash,
                    clickedOption = 1,
                ),
            )
        }
    }

    override fun onRender() {
        glClearColor(BACKGROUND, BACKGROUND, BACKGROUND, 1f)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)

        val width = stageWidth()
        val height = stageHeight()
        val originX = stageOriginX(width)
        val originY = stageOriginY(height)

        renderer.debugOutlines = editor.debugOutlines

        canvas.begin(window.width, window.height)
        canvas.fillRect(originX, originY, width, height, STAGE_BACKGROUND)
        instance?.let { renderer.render(it, originX, originY, width, height) }
        drawSelection()
        canvas.drawRectOutline(originX, originY, width, height, STAGE_BORDER)
        canvas.end()

        imGui.beginFrame()
        editor.render(window.width, window.height)
        hookPanel.render(instance, instance?.components?.getOrNull(editor.selected), window.height)
        renderStagePanel()
        imGui.endFrame()
    }

    /** Outlines the selected component on top of the rendered interface. */
    private fun drawSelection() {
        val state = instance?.components?.getOrNull(editor.selected) ?: return
        if (state.width <= 0 || state.height <= 0) {
            return
        }
        canvas.drawRectOutline(
            state.screenX - 1, state.screenY - 1,
            state.width + 2, state.height + 2,
            SELECTION_OUTLINE
        )
        canvas.drawRectOutline(state.screenX, state.screenY, state.width, state.height, SELECTION_INNER)
    }

    private fun renderStagePanel() {
        if (stageFirstFrame) {
            ImGui.setNextWindowPos(10f, (window.height - 150).toFloat())
            ImGui.setNextWindowSize(320f, 140f)
            stageFirstFrame = false
        }
        if (ImGui.begin("Stage", ImGuiWindowFlags.None)) {
            ImGui.text("Interface $currentInterfaceId  -  $fps FPS")
            val match = ImBoolean(stageMatchesWindow)
            if (ImGui.checkbox("Match window", match)) {
                stageMatchesWindow = match.get()
            }
            if (!stageMatchesWindow) {
                ImGui.setNextItemWidth(100f)
                if (ImGui.inputInt("Width", stageWidth)) {
                    stageWidth.set(stageWidth.get().coerceIn(1, MAX_STAGE))
                }
                ImGui.setNextItemWidth(100f)
                if (ImGui.inputInt("Height", stageHeight)) {
                    stageHeight.set(stageHeight.get().coerceIn(1, MAX_STAGE))
                }
                if (ImGui.smallButton("512 x 334")) {
                    stageWidth.set(DEFAULT_STAGE_WIDTH)
                    stageHeight.set(DEFAULT_STAGE_HEIGHT)
                }
                ImGui.sameLine()
                if (ImGui.smallButton("765 x 503")) {
                    stageWidth.set(765)
                    stageHeight.set(503)
                }
                ImGui.sameLine()
                if (ImGui.smallButton("1024 x 768")) {
                    stageWidth.set(1024)
                    stageHeight.set(768)
                }
            }
            ImGui.textDisabled("Left click: pick  |  F2: panels  |  Arrows: interface")
        }
        ImGui.end()
    }

    private fun stageWidth(): Int = if (stageMatchesWindow) window.width else stageWidth.get()

    private fun stageHeight(): Int = if (stageMatchesWindow) window.height else stageHeight.get()

    private fun stageOriginX(width: Int): Int = if (stageMatchesWindow) 0 else (window.width - width) / 2

    private fun stageOriginY(height: Int): Int = if (stageMatchesWindow) 0 else (window.height - height) / 2

    override fun onCleanup() {
        modelRenderer.cleanup()
        fonts.cleanup()
        sprites.cleanup()
        canvas.cleanup()
        imGui.cleanup()
    }

    companion object {
        /** The client's fixed-mode game window, which most interfaces target. */
        private const val DEFAULT_STAGE_WIDTH = 512
        private const val DEFAULT_STAGE_HEIGHT = 334
        private const val MAX_STAGE = 4096

        private const val BACKGROUND = 0.12f
        private const val STAGE_BACKGROUND = 0xff1b1b1b.toInt()
        private const val STAGE_BORDER = 0xff4a4a4a.toInt()
        private const val SELECTION_OUTLINE = 0xff000000.toInt()
        private const val SELECTION_INNER = 0xff2fd0ff.toInt()
    }
}
