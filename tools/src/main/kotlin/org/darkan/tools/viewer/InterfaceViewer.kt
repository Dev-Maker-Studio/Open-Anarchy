package org.darkan.tools.viewer

import imgui.ImDrawList
import imgui.ImGui
import imgui.flag.ImGuiCol
import imgui.flag.ImGuiDir
import imgui.flag.ImGuiDockNodeFlags
import imgui.flag.ImGuiMouseButton
import imgui.internal.ImGui as ImGuiInternal
import imgui.internal.flag.ImGuiDockNodeFlags as InternalDockNodeFlags
import imgui.type.ImInt
import imgui.type.ImString
import org.darkan.cs2.Cs2Analyzer
import org.darkan.cs2.Cs2HotReload
import org.darkan.cs2.OpKind
import org.darkan.cs2.vm.Cs2Event
import org.darkan.cs2.vm.Cs2Hook
import org.darkan.cs2.vm.Cs2Values
import org.darkan.cs2.vm.Cs2HookDispatcher
import org.darkan.tools.engine.Engine
import org.darkan.tools.engine.render.Canvas2D
import org.darkan.tools.engine.render.ObjIcons
import org.darkan.tools.engine.render.RenderTarget
import org.darkan.tools.engine.window.WindowConfig
import org.darkan.tools.rsinterface.ComponentState
import org.darkan.tools.rsinterface.ComponentTemplates
import org.darkan.tools.rsinterface.ComponentType
import org.darkan.tools.rsinterface.FontCache
import org.darkan.tools.rsinterface.GraphicCache
import org.darkan.tools.rsinterface.InterfaceAssets
import org.darkan.tools.rsinterface.InterfaceCs2Host
import org.darkan.tools.rsinterface.InterfaceDocument
import org.darkan.tools.rsinterface.InterfaceEvent
import org.darkan.tools.rsinterface.InterfaceInstance
import org.darkan.tools.rsinterface.InterfaceInteraction
import org.darkan.tools.rsinterface.InterfaceModelRenderer
import org.darkan.tools.rsinterface.InterfacePointer
import org.darkan.tools.rsinterface.InterfaceRenderer
import org.darkan.tools.rsinterface.InterfaceSourceTree
import org.darkan.tools.rsinterface.InterfaceState
import org.darkan.tools.rsinterface.InterfaceStore
import org.darkan.tools.rsinterface.InterfaceViewport
import org.darkan.tools.rsinterface.RsObjIconFont
import org.darkan.tools.rsinterface.SourceWatch
import org.darkan.tools.ui.AssetPicker
import org.darkan.tools.ui.CommandPalette
import org.darkan.tools.ui.Cs2HookPanel
import org.darkan.tools.ui.Cs2ReloadPanel
import org.darkan.tools.ui.GizmoBox
import org.darkan.tools.ui.GizmoHandle
import org.darkan.tools.ui.ImGuiLayer
import org.darkan.tools.ui.InterfaceEditContext
import org.darkan.tools.ui.InterfaceEditorPanel
import org.darkan.tools.ui.InterfaceEventPanel
import org.darkan.tools.ui.InterfaceInspectorPanel
import org.darkan.tools.ui.InterfaceLibraryPanel
import org.darkan.tools.ui.InterfaceMenuOverlay
import org.darkan.tools.ui.InterfaceStatePanel
import org.darkan.tools.ui.InterfaceValidationPanel
import org.darkan.tools.ui.SelectionGizmo
import org.darkan.tools.ui.StagePanel
import org.darkan.tools.ui.ImGuiText
import org.lwjgl.glfw.GLFW.GLFW_ARROW_CURSOR
import org.lwjgl.glfw.GLFW.GLFW_KEY_A
import org.lwjgl.glfw.GLFW.GLFW_KEY_C
import org.lwjgl.glfw.GLFW.GLFW_KEY_D
import org.lwjgl.glfw.GLFW.GLFW_KEY_DELETE
import org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE
import org.lwjgl.glfw.GLFW.GLFW_KEY_F1
import org.lwjgl.glfw.GLFW.GLFW_KEY_F5
import org.lwjgl.glfw.GLFW.GLFW_KEY_F6
import org.lwjgl.glfw.GLFW.GLFW_KEY_F7
import org.lwjgl.glfw.GLFW.GLFW_KEY_L
import org.lwjgl.glfw.GLFW.GLFW_KEY_N
import org.lwjgl.glfw.GLFW.GLFW_KEY_P
import org.lwjgl.glfw.GLFW.GLFW_KEY_S
import org.lwjgl.glfw.GLFW.GLFW_KEY_V
import org.lwjgl.glfw.GLFW.GLFW_KEY_Y
import org.lwjgl.glfw.GLFW.GLFW_KEY_Z
import org.lwjgl.glfw.GLFW.GLFW_MOD_ALT
import org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL
import org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT
import org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT
import org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_MIDDLE
import org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT
import org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT
import org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT
import org.lwjgl.opengl.GL11.glClear
import org.lwjgl.opengl.GL11.glClearColor
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.definition.data.InterfaceDefinition
import world.gregs.voidps.cache.definition.decoder.InterfaceDecoder
import world.gregs.voidps.cache.definition.decoder.ItemDecoder
import java.io.File

enum class EditorMode {
    DESIGN,

    PLAY,
}

class InterfaceViewer(
    private val cache: Cache,
    startInterfaceId: Int = 0,
    private val startup: InterfaceStartup = InterfaceStartup(),
) : Engine(WindowConfig(title = "Darkan Interface Editor", width = 1600, height = 900)) {


    private lateinit var imGui: ImGuiLayer
    private lateinit var canvas: Canvas2D
    private lateinit var graphics: GraphicCache
    private lateinit var fonts: FontCache
    private lateinit var modelRenderer: InterfaceModelRenderer
    private lateinit var icons: ObjIcons
    private lateinit var renderer: InterfaceRenderer
    private val stageTarget = RenderTarget()
    private lateinit var stagePanel: StagePanel


    private lateinit var store: InterfaceStore
    private lateinit var assets: InterfaceAssets
    private val context = InterfaceEditContext()
    private var frameInstance: InterfaceInstance? = null
    private var currentId = startInterfaceId.coerceAtLeast(0)

    private var scriptsDirty = false
    private var loadScriptMillis = 0L


    private var source: InterfaceSourceTree? = null
    private val sourceWatch = SourceWatch()

    private var sourceChangedAt = 0L
    private var sourceReloads = 0

    private val compiledScripts = HashSet<Int>()
    private var scriptReloads = 0
    private var lastLiveReload = 0L


    private lateinit var scripts: Cs2Analyzer
    private lateinit var reload: Cs2HotReload
    private lateinit var state: InterfaceState
    private lateinit var cs2Host: InterfaceCs2Host
    private lateinit var dispatcher: Cs2HookDispatcher
    private lateinit var interaction: InterfaceInteraction


    private lateinit var library: InterfaceLibraryPanel
    private lateinit var tree: InterfaceEditorPanel
    private lateinit var inspector: InterfaceInspectorPanel
    private lateinit var eventPanel: InterfaceEventPanel
    private lateinit var hookPanel: Cs2HookPanel
    private lateinit var reloadPanel: Cs2ReloadPanel
    private lateinit var statePanel: InterfaceStatePanel
    private lateinit var validation: InterfaceValidationPanel
    private lateinit var picker: AssetPicker
    private lateinit var menuOverlay: InterfaceMenuOverlay
    private val palette = CommandPalette()
    private val gizmo = SelectionGizmo()


    private var mode = EditorMode.DESIGN
    private var status = ""
    private var stageHeld = false
    private var gizmoPreview: GizmoBox? = null
    private val heldKeys = LinkedHashSet<Int>()

    private var layoutBuilt = false
    private var layoutExists = false

    private var dockFrames = 0
    private val dockNodes = HashMap<String, Int>()
    private var showDialog = false
    private var showSourceDialog = false
    private val sourcePath = ImString(320)
    private val dialogId = ImInt(0)
    private val dialogPath = ImString(320)
    private var dialogSource = 0


    private lateinit var decoder: InterfaceDecoder
    private lateinit var definitions: Array<InterfaceDefinition>
    private lateinit var decodedIds: BooleanArray


    override fun onInit() {
        layoutExists = File(LAYOUT_FILE).isFile

        imGui = ImGuiLayer(window)
        imGui.init()
        imGui.applyEditorTheme()

        canvas = Canvas2D(loadShaderSource("/shaders/ui.vert"), loadShaderSource("/shaders/ui.frag"))
        graphics = GraphicCache(cache)
        fonts = FontCache(cache, graphics)
        modelRenderer = InterfaceModelRenderer(cache, window, renderSystem, colorShader, texturedShader)
        icons = ObjIcons(cache, RsObjIconFont(fonts))
        renderer = InterfaceRenderer(canvas, graphics, fonts, modelRenderer, icons)
        stagePanel = StagePanel(stageTarget)

        decoder = InterfaceDecoder()
        definitions = decoder.create(decoder.size(cache) + 1)
        decodedIds = BooleanArray(definitions.size)

        store = InterfaceStore(cache)
        assets = InterfaceAssets.of(cache)
        seedTemplateDefaults()

        scripts = Cs2Analyzer(cache)
        scripts.analyse()
        reload = Cs2HotReload(cache, scripts)
        cs2Host = InterfaceCs2Host(
            interfaces = { id -> if (id == currentId) frameInstance else null },
            enums = { id -> Cache.enums.getOrNull(id) },
            structs = { id -> Cache.structs.getOrNull(id) },
            params = { id -> Cache.params.getOrNull(id) },
        )
        cs2Host.paragraphLines = { text, width, fontId -> fonts.font(fontId)?.lineCount(text, width) ?: 1 }
        dispatcher = Cs2HookDispatcher(reload, cs2Host)
        interaction = InterfaceInteraction({ frameInstance }, dispatcher, cs2Host)
        interaction.liveMode = false
        state = InterfaceState(cs2Host)
        reload.onReload = { event ->
            if (event.ok) {
                scriptsDirty = true
                if (event.scriptId >= 0) {
                    scriptReloads++
                    lastLiveReload = System.currentTimeMillis()
                }
            }
            status = event.message
            if (event.staleCallers.isNotEmpty()) {
                status = "${event.message} - ${event.staleCallers.size} callers need a re-analyse"
            }
        }

        context.onEdit = {
            scriptsDirty = true
        }

        useSource(startupTree(), announce = false)
        if (reload.project.folder.isDirectory && !reload.start()) {
            status = reload.watcherError ?: "could not watch ${reload.project.folder.path}"
        }

        buildPanels()
        registerCommands()
        applyStartup()
        openInterface(currentId)
    }


    private fun startupTree(): File? = when {
        startup.packedOnly -> null
        startup.source != null -> startup.source
        else -> InterfaceSourceTree.discover()
    }

    private fun useSource(root: File?, announce: Boolean = true) {
        if (root == null) {
            source = null
            sourceWatch.stop()
            if (announce) {
                status = "live source off; reading the packed cache"
                openInterface(currentId)
            }
            return
        }
        val tree = try {
            InterfaceSourceTree.open(root)
        } catch (e: Exception) {
            status = "not a cache source tree: ${root.path} (${e.message})"
            return
        }
        source = tree
        if (!reload.useFolder(tree.clientscripts)) {
            status = reload.watcherError ?: "cannot compile from ${tree.clientscripts.path}"
        }
        sourcePath.set(tree.root.path)
        if (announce) {
            status = "live source: ${tree.root.path}"
            openInterface(currentId)
        }
    }

    private fun watchSource() {
        val tree = source ?: return
        val directory = tree.directory(currentId)
        if (sourceWatch.directories != listOf(directory)) {
            sourceWatch.watch(listOf(directory))
        }
    }

    private fun reloadFromSource(): String {
        val tree = source ?: return "not in live source mode"
        if (!tree.has(currentId)) return "${tree.name} has no interface $currentId"
        val selection = context.primary
        return try {
            tree.invalidate()
            attach(InterfaceDocument(currentId, tree.components(currentId)))
            if (selection in 0 until context.componentCount) {
                context.setSelection(listOf(selection))
            }
            sourceReloads++
            lastLiveReload = System.currentTimeMillis()
            "interface $currentId re-read from ${tree.name}"
        } catch (e: Exception) {
            "interface $currentId: ${e.message ?: e::class.simpleName}"
        }
    }

    private fun pollSource() {
        val tree = source ?: return
        val directory = tree.directory(currentId)
        if (sourceWatch.poll().any { it.parentFile == directory }) {
            sourceChangedAt = System.currentTimeMillis()
        }
        if (sourceChangedAt != 0L && System.currentTimeMillis() - sourceChangedAt >= SOURCE_DEBOUNCE) {
            sourceChangedAt = 0L
            status = reloadFromSource()
        }
    }

    private fun compileInterfaceScripts(): Int {
        source ?: return 0
        val pending = ArrayDeque<Int>()
        val seen = HashSet<Int>()
        hookPanel.scripts().forEach { if (seen.add(it)) pending.add(it) }
        var compiled = 0
        while (pending.isNotEmpty() && seen.size <= SCRIPT_CLOSURE_LIMIT) {
            val id = pending.removeFirst()
            if (reload.project.hasSource(id) && compiledScripts.add(id) && reload.reload(id).ok) {
                compiled++
            }
            val script = scripts.script(id) ?: continue
            for (instruction in script.instructions) {
                if (instruction.op.kind == OpKind.GOSUB && seen.add(instruction.intOperand)) {
                    pending.add(instruction.intOperand)
                }
            }
        }
        return compiled
    }

    private fun applyStartup() {
        val reports = ArrayList<String>()
        startup.vars.forEach { reports.add(state.set(it)) }
        startup.stats.forEach { reports.add(state.setStat(it)) }
        startup.runs.forEach { reports.add(state.addRun(it)) }
        if (reports.isNotEmpty()) {
            reports.forEach { println("interface state: $it") }
            status = reports.last()
        }
    }

    private fun runStateScript(script: InterfaceState.ScriptRun): String = try {
        dispatcher.dispatch(Cs2Hook(script.scriptId, Cs2Values(script.ints, script.strings)), Cs2Event())
        "ran ${script.label}"
    } catch (e: Exception) {
        "${script.label} failed: ${e.message ?: e::class.simpleName}"
    }

    private fun liveStatus(): String {
        val tree = source ?: return ""
        val reloads = sourceReloads + scriptReloads
        if (reloads == 0 || lastLiveReload == 0L) {
            return "live: ${tree.name}"
        }
        val seconds = (System.currentTimeMillis() - lastLiveReload) / 1000.0
        return "live: ${tree.name}, $reloads reloads, last ${"%.1f".format(seconds)} s ago"
    }

    private fun seedTemplateDefaults() {
        for (id in 0 until minOf(graphics.count, TEMPLATE_SCAN)) {
            if (graphics.definition(id) != null) {
                ComponentTemplates.defaultGraphicId = id
                break
            }
        }
        for (id in 0 until minOf(fonts.count, TEMPLATE_SCAN)) {
            if (fonts.font(id) != null) {
                ComponentTemplates.defaultFontId = id
                renderer.fallbackFontId = id
                break
            }
        }
    }

    private fun buildPanels() {
        val itemCount = ItemDecoder().size(cache) + 1
        picker = AssetPicker(graphics, fonts, { id -> icons.definition(id) }, itemCount, icons)

        library = InterfaceLibraryPanel(definitions.size) { id -> definitionOf(id) }
        library.onOpen = { id -> openInterface(id) }

        tree = InterfaceEditorPanel(context)
        inspector = InterfaceInspectorPanel(context, picker, ::runHook, ::openScript)
        eventPanel = InterfaceEventPanel(context, interaction, cs2Host)
        hookPanel = Cs2HookPanel(context, cs2Host, ::runHook, ::openScript) { scriptsDirty = true }
        reloadPanel = Cs2ReloadPanel(reload, { hookPanel.scripts() }, ::liveStatus)
        reloadPanel.visible = true
        statePanel = InterfaceStatePanel(state, { scriptsDirty = true }, ::runStateScript)
        validation = InterfaceValidationPanel(context, store, assets)
        menuOverlay = InterfaceMenuOverlay(interaction)

        stagePanel.overlay = { drawStageOverlay() }
        stagePanel.toolbar = { drawStageToolbar() }
    }

    private fun definitionOf(id: Int): InterfaceDefinition? {
        if (id < 0 || id >= definitions.size) {
            return null
        }
        if (!decodedIds[id]) {
            decodedIds[id] = true
            try {
                decoder.load(definitions, cache, id)
            } catch (e: Exception) {
                return null
            }
        }
        return if (definitions[id].components == null) null else definitions[id]
    }


    private fun openInterface(id: Int) {
        val tree = source
        val document = if (tree != null && tree.has(id)) {
            try {
                InterfaceDocument(id, tree.components(id))
            } catch (e: Exception) {
                status = "${tree.name} interface $id: ${e.message}; falling back to the packed cache"
                fromCache(id)
            }
        } else {
            fromCache(id)
        }
        attach(document)
        watchSource()
    }

    private fun fromCache(id: Int): InterfaceDocument = try {
        store.open(id)
    } catch (e: Exception) {
        status = "could not decode interface $id: ${e.message}"
        InterfaceDocument.empty(id)
    }

    private fun attach(document: InterfaceDocument) {
        currentId = document.id
        context.attach(document)
        document.onRebuild = { instance ->
            frameInstance = instance
            scriptsDirty = true
        }
        frameInstance = document.instance()
        interaction.reset()
        cs2Host.clearHooks()
        cs2Host.log.clear()
        val compiled = compileInterfaceScripts()
        frameInstance?.let { runLoadScripts(it) }
        scriptsDirty = false
        library.currentId = document.id
        library.noteOpened(document.id)
        gizmo.cancel()
        updateTitle()
        status = "interface ${document.id}: ${document.componentCount} components, last id ${store.lastInterfaceId()}" +
            if (compiled > 0) "; $compiled scripts compiled from source" else ""
    }

    private fun runLoadScripts(instance: InterfaceInstance) {
        val started = System.nanoTime()
        cs2Host.log.clear()
        if (stageTarget.width > 0 && stageTarget.height > 0) {
            instance.layout(stageTarget.width, stageTarget.height)
        } else {
            instance.layoutDeclared()
        }
        val previous = interaction.liveMode
        interaction.liveMode = true
        try {
            for (component in instance.components) {
                if (component.definition.onLoadScript == null) {
                    continue
                }
                interaction.dispatch(component, InterfaceEvent.LOAD, Cs2Event(source = component.hash))
            }
        } finally {
            interaction.liveMode = previous
        }
        state.runs.forEach { runStateScript(it) }
        loadScriptMillis = (System.nanoTime() - started) / 1_000_000
    }

    private fun runHook(index: Int, event: InterfaceEvent) {
        val state = frameInstance?.state(index) ?: return
        val previous = interaction.liveMode
        interaction.liveMode = true
        try {
            val ran = interaction.dispatch(
                state,
                event,
                Cs2Event(source = state.hash, clickedOption = 1, optionName = state.opBase),
            )
            status = if (ran) "$event ran on component $index" else "$event has no hook on component $index"
        } finally {
            interaction.liveMode = previous
        }
    }

    private fun openScript(scriptId: Int) {
        val result = reload.project.exportSource(scriptId)
        status = result.message
        reloadPanel.visible = true
    }

    private fun saveSidecar() {
        val document = context.document ?: return
        status = try {
            val file = store.saveSidecar(document)
            document.markSaved()
            updateTitle()
            "saved ${file.path}"
        } catch (e: Exception) {
            "save failed: ${e.message}"
        }
    }

    private fun saveToCache() {
        val document = context.document ?: return
        status = try {
            val result = store.save(document)
            updateTitle()
            "wrote ${result.files} files to the cache" +
                if (result.warnings.isEmpty()) "" else "; ${result.warnings.joinToString("; ")}"
        } catch (e: Exception) {
            "cache save failed: ${e.message}"
        }
    }

    private fun updateTitle() {
        val document = context.document
        val dirty = if (document?.dirty == true) " *" else ""
        window.setTitle("Darkan Interface Editor - ${document?.id ?: "-"}$dirty")
    }


    override fun onInput() {
        if (input.mouseCaptured) {
            input.releaseMouse()
        }
    }

    override fun onRender() {
        glClearColor(BACKGROUND, BACKGROUND, BACKGROUND, 1f)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)

        pollSource()
        frameInstance = context.document?.instance()
        if (scriptsDirty) {
            frameInstance?.let { runLoadScripts(it) }
            scriptsDirty = false
        }
        context.frame(frameInstance)
        interaction.liveMode = mode == EditorMode.PLAY

        imGui.beginFrame()
        imGui.beginDockspace()
        if (!layoutBuilt) {
            layoutBuilt = true
            if (!layoutExists) {
                buildDefaultLayout()
            }
        }
        drawMenuBar()

        dock("Stage")
        val drawStage = stagePanel.render(fps)
        routePointer(drawStage)
        routeKeyboard()

        dock("Interfaces")
        library.render()
        tree.stageWidth = stageTarget.width
        tree.stageHeight = stageTarget.height
        dock("Tree")
        tree.render()
        dock("Inspector")
        inspector.render()
        eventPanel.playMode = mode == EditorMode.PLAY
        dock("Interaction")
        eventPanel.render()
        dock("CS2 Hooks")
        hookPanel.render()
        dock("Validation")
        validation.render()
        dock("CS2 Hot Reload")
        reloadPanel.render(window.height)
        dock("State")
        statePanel.render(window.height)
        menuOverlay.render(stagePanel::screenX, stagePanel::screenY)
        drawDialog()
        palette.render()

        imGui.endDockspace()
        if (dockFrames > 0) {
            dockFrames--
        }
        if (drawStage) {
            renderStage()
        }
        imGui.endFrame()
    }

    private fun renderStage() {
        val width = stageTarget.width
        val height = stageTarget.height
        modelRenderer.targetWidth = width
        modelRenderer.targetHeight = height

        stageTarget.begin()
        renderSystem.enableDepthWrite()
        glClearColor(0f, 0f, 0f, 0f)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)

        canvas.begin(width, height)
        frameInstance?.let { renderer.render(it, 0, 0, width, height) }
        canvas.end()

        stageTarget.end(window.width, window.height)
        modelRenderer.targetWidth = 0
        modelRenderer.targetHeight = 0
    }


    private fun routePointer(drawStage: Boolean) {
        val io = ImGui.getIO()
        val overStage = drawStage && stagePanel.hovered && !menuOverlay.hovered
        if (overStage && input.mouse.isButtonPressed(GLFW_MOUSE_BUTTON_LEFT)) {
            stageHeld = true
        }
        if (!input.mouse.isButtonDown(GLFW_MOUSE_BUTTON_LEFT)) {
            stageHeld = false
        }
        val active = overStage || stageHeld
        val live = mode == EditorMode.PLAY

        val wheel = if (overStage && !io.keyCtrl) input.mouse.scrollY.toInt() else 0
        val pointer = InterfacePointer(
            x = if (active) stagePanel.pointerX else -1,
            y = if (active) stagePanel.pointerY else -1,
            left = live && active && input.mouse.isButtonDown(GLFW_MOUSE_BUTTON_LEFT),
            right = live && active && input.mouse.isButtonDown(GLFW_MOUSE_BUTTON_RIGHT),
            middle = live && active && input.mouse.isButtonDown(GLFW_MOUSE_BUTTON_MIDDLE),
            wheel = if (live) wheel else 0,
        )
        interaction.update(
            InterfaceViewport(0, 0, stageTarget.width, stageTarget.height),
            pointer,
            (deltaTime * 1000f).toLong(),
        )

        if (!live && overStage && !gizmo.dragging &&
            input.mouse.isButtonPressed(GLFW_MOUSE_BUTTON_LEFT)
        ) {
            pickComponent(io.keyCtrl)
        }
    }

    private fun pickComponent(additive: Boolean) {
        val instance = frameInstance ?: return
        val hits = instance.hitTest(
            0, 0, stageTarget.width, stageTarget.height,
            stagePanel.pointerX, stagePanel.pointerY,
        )
        val editable = hits.filter { !it.runtime }
        val picked = editable.lastOrNull { it.type != ComponentType.CONTAINER }
            ?: editable.lastOrNull()
            ?: hits.lastOrNull()?.let { hit -> instance.ancestors(hit).firstOrNull { !it.runtime } }
        if (picked == null) {
            if (!additive) {
                context.clearSelection()
            }
            return
        }
        context.select(picked.index, additive)
        context.revealed = picked.index
    }

    private fun routeKeyboard() {
        val typing = ImGui.getIO().wantTextInput
        val presses = input.keyboard.presses
        val shortcuts = presses.map { it.key to it.mods }
        palette.handleShortcuts(shortcuts, typing)
        tree.keys = if (typing) emptyList() else shortcuts

        if (mode != EditorMode.PLAY || typing) {
            releaseKeys(all = true)
            return
        }
        val chars = ArrayDeque(input.keyboard.chars)
        for (event in presses) {
            if (isShortcut(event.key, event.mods)) {
                continue
            }
            val typed = if (chars.isEmpty()) NO_CHAR else chars.removeFirst().toChar()
            heldKeys.add(event.key)
            interaction.key(event.key, typed, event.mods, event.repeat)
        }
        releaseKeys(all = false)
    }

    private fun releaseKeys(all: Boolean) {
        if (heldKeys.isEmpty()) {
            return
        }
        val iterator = heldKeys.iterator()
        while (iterator.hasNext()) {
            val key = iterator.next()
            if (all || input.keyboard.isKeyReleased(key)) {
                interaction.keyReleased(key)
                iterator.remove()
            }
        }
    }

    private fun isShortcut(key: Int, mods: Int): Boolean {
        val relevant = mods and (GLFW_MOD_CONTROL or GLFW_MOD_SHIFT or GLFW_MOD_ALT)
        return palette.all.any { it.key == key && it.mods == relevant }
    }


    private fun drawStageToolbar() {
        if (mode == EditorMode.PLAY) {
            ImGui.pushStyleColor(ImGuiCol.Text, 0.4f, 0.9f, 0.55f, 1f)
            ImGui.text("PLAY")
        } else {
            ImGui.pushStyleColor(ImGuiCol.Text, 1f, 0.78f, 0.35f, 1f)
            ImGui.text("DESIGN")
        }
        ImGui.popStyleColor()
        ImGui.sameLine()
        if (ImGui.smallButton("F5")) {
            toggleMode()
        }
    }

    private fun drawStageOverlay() {
        val instance = frameInstance ?: return
        val drawList = ImGui.getWindowDrawList()
        if (mode == EditorMode.PLAY) {
            context.primaryState()?.let { outline(drawList, it, PLAY_OUTLINE) }
            return
        }

        if (stagePanel.hovered && !gizmo.dragging) {
            val hits = instance.hitTest(
                0, 0, stageTarget.width, stageTarget.height,
                stagePanel.pointerX, stagePanel.pointerY,
            )
            val hover = hits.lastOrNull { it.type != ComponentType.CONTAINER } ?: hits.lastOrNull()
            if (hover != null && hover.index != context.primary) {
                outline(drawList, hover, HOVER_OUTLINE)
            }
        }
        for (index in context.selection) {
            if (index != context.primary) {
                instance.state(index)?.let { outline(drawList, it, SECONDARY_OUTLINE) }
            }
        }
        context.primaryState()?.let { handleGizmo(drawList, it) }
    }

    private fun outline(drawList: ImDrawList, state: ComponentState, colour: Int) {
        drawList.addRect(
            stagePanel.screenX(state.screenX),
            stagePanel.screenY(state.screenY),
            stagePanel.screenX(state.screenX + maxOf(state.width, 1)),
            stagePanel.screenY(state.screenY + maxOf(state.height, 1)),
            colour,
        )
    }

    private fun handleGizmo(drawList: ImDrawList, state: ComponentState) {
        val box = GizmoBox(state.screenX, state.screenY, state.width, state.height)
        val pointerX = stagePanel.pointerX
        val pointerY = stagePanel.pointerY

        if (stagePanel.hovered || gizmo.dragging) {
            val handle = gizmo.hitTest(box, pointerX, pointerY, stagePanel.imageScale)
            if (!gizmo.dragging && handle != GizmoHandle.NONE &&
                input.mouse.isButtonPressed(GLFW_MOUSE_BUTTON_LEFT)
            ) {
                gizmo.begin(handle, box, pointerX, pointerY)
            }
            window.setCursorShape(if (gizmo.dragging) gizmo.active.cursor else handle.cursor)
        }

        var drawn = box
        if (gizmo.dragging) {
            if (!input.mouse.isButtonDown(GLFW_MOUSE_BUTTON_LEFT)) {
                gizmo.end()
                gizmoPreview = null
                context.endEdit()
                window.setCursorShape(GLFW_ARROW_CURSOR)
            } else {
                val updated = gizmo.update(pointerX, pointerY, ImGui.getIO().keyShift)
                if (updated != null) {
                    gizmoPreview = updated
                    applyGizmo(state, updated)
                    drawn = updated
                }
            }
        }
        gizmo.draw(drawList, drawn, stagePanel::screenX, stagePanel::screenY, true)
    }

    private fun applyGizmo(state: ComponentState, box: GizmoBox) {
        val instance = frameInstance ?: return
        val parent = instance.state(state.parent)
        val parentWidth = when {
            parent == null -> stageTarget.width
            parent.scrollWidth != 0 -> parent.scrollWidth
            else -> parent.width
        }
        val parentHeight = when {
            parent == null -> stageTarget.height
            parent.scrollHeight != 0 -> parent.scrollHeight
            else -> parent.height
        }
        val localX = box.x - (state.screenX - state.x)
        val localY = box.y - (state.screenY - state.y)

        context.editPrimary("Move or resize", "gizmo@${state.index}") { definition ->
            definition.baseWidth =
                inverseSize(definition.horizontalSizeMode.toInt(), box.width, parentWidth, definition.baseWidth)
            definition.baseHeight =
                inverseSize(definition.verticalSizeMode.toInt(), box.height, parentHeight, definition.baseHeight)
            definition.basePositionX = inversePosition(
                definition.horizontalPositionMode.toInt(), localX, box.width, parentWidth, definition.basePositionX
            )
            definition.basePositionY = inversePosition(
                definition.verticalPositionMode.toInt(), localY, box.height, parentHeight, definition.basePositionY
            )
        }
    }

    private fun inverseSize(mode: Int, resolved: Int, parent: Int, current: Int): Int = when (mode) {
        0 -> resolved
        1 -> parent - resolved
        2 -> if (parent > 0) (resolved shl 14) / parent else current
        else -> current
    }

    @Suppress("MagicNumber")
    private fun inversePosition(mode: Int, resolved: Int, size: Int, parent: Int, current: Int): Int = when (mode) {
        0 -> resolved
        1 -> resolved - ((parent - size) / 2)
        2 -> parent - size - resolved
        3 -> if (parent > 0) (resolved shl 14) / parent else current
        4 -> if (parent > 0) ((resolved - ((parent - size) / 2)) shl 14) / parent else current
        else -> if (parent > 0) ((parent - size - resolved) shl 14) / parent else current
    }


    private fun drawMenuBar() {
        if (!ImGui.beginMainMenuBar()) {
            return
        }
        val document = context.document
        if (ImGui.beginMenu("File")) {
            menuCommand("file.new")
            menuCommand("file.save")
            menuCommand("file.import")
            ImGui.separator()
            if (store.writable) {
                menuCommand("file.cache")
            } else {
                ImGui.beginDisabled()
                ImGui.menuItem("Save to cache")
                ImGui.endDisabled()
                if (ImGui.isItemHovered()) {
                    ImGuiText.tooltip((store.readOnlyReason ?: ""))
                }
            }
            ImGui.separator()
            if (ImGui.menuItem("Open cache-source tree...")) {
                sourcePath.set(source?.root?.path ?: InterfaceSourceTree.discover()?.path ?: "cache-source")
                showSourceDialog = true
            }
            if (ImGui.menuItem("Use the packed cache", "", false, source != null)) {
                useSource(null)
            }
            ImGui.separator()
            if (ImGui.menuItem("Reveal sidecar path") && document != null) {
                ImGui.setClipboardText(store.jsonFile(document.id).absolutePath)
                status = "copied ${store.jsonFile(document.id).absolutePath}"
            }
            ImGui.endMenu()
        }
        if (ImGui.beginMenu("Edit")) {
            menuCommand("edit.undo")
            menuCommand("edit.redo")
            ImGui.separator()
            menuCommand("edit.duplicate")
            menuCommand("edit.delete")
            menuCommand("edit.copy")
            menuCommand("edit.paste")
            ImGui.separator()
            menuCommand("edit.selectAll")
            menuCommand("edit.deselect")
            ImGui.endMenu()
        }
        if (ImGui.beginMenu("View")) {
            panelToggle("Stage") { stagePanel.visible = !stagePanel.visible }
            panelToggle("Interfaces") { library.visible = !library.visible }
            panelToggle("Tree") { tree.visible = !tree.visible }
            panelToggle("Inspector") { inspector.visible = !inspector.visible }
            panelToggle("Interaction") { eventPanel.visible = !eventPanel.visible }
            panelToggle("CS2 Hooks") { hookPanel.visible = !hookPanel.visible }
            panelToggle("CS2 Hot Reload") { reloadPanel.visible = !reloadPanel.visible }
            panelToggle("State") { statePanel.visible = !statePanel.visible }
            panelToggle("Validation") { validation.visible = !validation.visible }
            ImGui.separator()
            menuCommand("view.resetLayout")
            menuCommand("view.resetView")
            ImGui.endMenu()
        }
        if (ImGui.beginMenu("Run")) {
            menuCommand("run.mode")
            menuCommand("run.load")
            menuCommand("run.source")
            menuCommand("run.reset")
            menuCommand("run.clearTrace")
            ImGui.endMenu()
        }
        if (ImGui.beginMenu("Help")) {
            menuCommand("help.palette")
            if (ImGui.beginMenu("Shortcuts")) {
                palette.renderShortcutList()
                ImGui.endMenu()
            }
            ImGui.endMenu()
        }

        ImGui.sameLine(ImGui.getWindowSizeX() - STATUS_WIDTH)
        if (mode == EditorMode.PLAY) {
            ImGuiText.coloured(0.4f, 0.9f, 0.55f, "PLAY")
        } else {
            ImGuiText.coloured(1f, 0.78f, 0.35f, "DESIGN")
        }
        ImGui.sameLine()
        ImGuiText.disabled("|")
        ImGui.sameLine()
        if (document?.dirty == true) {
            ImGuiText.coloured(1f, 0.78f, 0.35f, "unsaved")
        } else {
            ImGuiText.disabled("saved")
        }
        ImGui.sameLine()
        ImGuiText.disabled("|")
        ImGui.sameLine()
        ImGuiText.disabled((status.take(STATUS_CHARS)))
        ImGui.endMainMenuBar()

        stagePanel.status = buildString {
            append("interface $currentId")
            val live = liveStatus()
            if (live.isNotEmpty()) {
                append("   ").append(live)
            }
            if (loadScriptMillis > 0) {
                append("   load scripts ${loadScriptMillis}ms")
            }
            if (validation.errorCount > 0) {
                append("   ${validation.errorCount} errors")
            }
        }
    }

    private fun menuCommand(id: String) {
        val command = palette.all.firstOrNull { it.id == id } ?: return
        val usable = command.enabled()
        if (ImGui.menuItem(command.label, command.shortcut, false, usable)) {
            palette.run(id)
        }
    }

    private fun panelToggle(label: String, toggle: () -> Unit) {
        if (ImGui.menuItem(label)) {
            toggle()
        }
    }


    private fun drawDialog() {
        drawSourceDialog()
        if (showDialog) {
            ImGui.openPopup(DIALOG)
            showDialog = false
        }
        if (!ImGui.beginPopupModal(DIALOG)) {
            return
        }
        ImGui.setNextItemWidth(120f)
        ImGui.inputInt("Interface id", dialogId)
        val id = dialogId.get()
        if (id < 0) {
            dialogId.set(0)
        }
        if (store.exists(id)) {
            ImGuiText.coloured(1f, 0.78f, 0.35f, "Interface $id already exists in the cache.")
        } else {
            ImGuiText.disabled("Free. The highest in the cache is ${store.lastInterfaceId()}.")
        }
        ImGui.separator()

        if (ImGui.radioButton("Empty", dialogSource == SOURCE_EMPTY)) {
            dialogSource = SOURCE_EMPTY
        }
        ImGui.sameLine()
        if (ImGui.radioButton("Copy the open interface", dialogSource == SOURCE_COPY)) {
            dialogSource = SOURCE_COPY
        }
        ImGui.sameLine()
        if (ImGui.radioButton("Import JSON", dialogSource == SOURCE_IMPORT)) {
            dialogSource = SOURCE_IMPORT
        }
        if (dialogSource == SOURCE_IMPORT) {
            ImGui.setNextItemWidth(-1f)
            ImGui.inputTextWithHint("##path", "path to a sidecar", dialogPath)
        }
        if (dialogSource == SOURCE_EMPTY) {
            ImGuiText.disabled("Add components from the tree's right click menu, or the Add menu.")
        }

        ImGui.separator()
        if (ImGui.button("Create", DIALOG_BUTTON, 0f)) {
            createFromDialog(id)
            ImGui.closeCurrentPopup()
        }
        ImGui.sameLine()
        if (ImGui.button("Cancel", DIALOG_BUTTON, 0f)) {
            ImGui.closeCurrentPopup()
        }
        ImGui.endPopup()
    }

    private fun drawSourceDialog() {
        if (showSourceDialog) {
            ImGui.openPopup(SOURCE_DIALOG)
            showSourceDialog = false
        }
        if (!ImGui.beginPopupModal(SOURCE_DIALOG)) {
            return
        }
        ImGuiText.wrapped(
            "Components come from <tree>/interfaces/<id>/*.json and clientscripts from " +
                "<tree>/clientscripts; graphics, fonts and models stay with the packed cache. " +
                "Nothing is written to the tree.",
        )
        ImGui.setNextItemWidth(-1f)
        ImGui.inputTextWithHint("##sourcepath", "path to a cache source tree", sourcePath)
        val candidate = File(sourcePath.get().trim())
        if (InterfaceSourceTree.rootOf(candidate) == null) {
            ImGuiText.coloured(1f, 0.78f, 0.35f, "No cache.json there; that is not a cache source tree.")
        } else {
            ImGuiText.disabled("Ready.")
        }
        ImGui.separator()
        if (ImGui.button("Use", DIALOG_BUTTON, 0f)) {
            useSource(candidate)
            ImGui.closeCurrentPopup()
        }
        ImGui.sameLine()
        if (ImGui.button("Cancel", DIALOG_BUTTON, 0f)) {
            ImGui.closeCurrentPopup()
        }
        ImGui.endPopup()
    }

    private fun createFromDialog(id: Int) {
        val document = when (dialogSource) {
            SOURCE_COPY -> context.document?.copyAs(id) ?: InterfaceDocument.empty(id)
            SOURCE_IMPORT -> try {
                store.importJson(File(dialogPath.get().trim()))
            } catch (e: Exception) {
                status = "import failed: ${e.message}"
                return
            }
            else -> InterfaceDocument.empty(id)
        }
        attach(document)
    }


    @Suppress("LongMethod")
    private fun registerCommands() {
        palette.register(
            "file.new", "New interface", "File", GLFW_KEY_N, GLFW_MOD_CONTROL,
            "Empty, a copy of the open one, or an imported sidecar.",
        ) {
            dialogId.set(store.lastInterfaceId() + 1)
            showDialog = true
        }
        palette.register(
            "file.save", "Save JSON sidecar", "File", GLFW_KEY_S, GLFW_MOD_CONTROL,
            "The working save. Writes ${InterfaceStore.DEFAULT_DIRECTORY}/interface-<id>.json and " +
                "backs up whatever the cache currently holds.",
            enabled = { context.open },
        ) { saveSidecar() }
        palette.register(
            "file.import", "Import JSON...", "File", 0, 0,
            "Load a sidecar written by an earlier save.",
        ) {
            dialogSource = SOURCE_IMPORT
            dialogId.set(currentId)
            dialogPath.set(store.jsonFile(currentId).path)
            showDialog = true
        }
        palette.register(
            "file.cache", "Save to cache", "File", 0, 0,
            "Writes index 3 directly. Only available with a writable cache.",
            enabled = { store.writable && context.open },
        ) { saveToCache() }

        palette.register(
            "edit.undo", "Undo", "Edit", GLFW_KEY_Z, GLFW_MOD_CONTROL, "",
            enabled = { context.document?.canUndo == true },
        ) {
            context.undo()
            status = "undo"
        }
        palette.register(
            "edit.redo", "Redo", "Edit", GLFW_KEY_Y, GLFW_MOD_CONTROL, "",
            enabled = { context.document?.canRedo == true },
        ) {
            context.redo()
            status = "redo"
        }
        palette.register(
            "edit.duplicate", "Duplicate", "Edit", GLFW_KEY_D, GLFW_MOD_CONTROL,
            "Deep copies the selection, repointing hook arguments at the copies.",
            enabled = { context.primary >= 0 },
        ) {
            val targets = context.targets()
            context.structural { it.duplicateComponents(targets) }
        }
        palette.register(
            "edit.delete", "Delete", "Edit", GLFW_KEY_DELETE, 0,
            "Deletes the selection and everything under it.",
            enabled = { context.primary >= 0 },
        ) {
            val targets = context.targets()
            context.structural { it.deleteComponents(targets) }
        }
        palette.register(
            "edit.copy", "Copy subtree", "Edit", GLFW_KEY_C, GLFW_MOD_CONTROL, "",
            enabled = { context.primary >= 0 },
        ) {
            val document = context.document ?: return@register
            context.clipboard =
                ComponentTemplates.extract(document, context.primary, "clipboard", "Pasted subtree")
        }
        palette.register(
            "edit.paste", "Paste into selection", "Edit", GLFW_KEY_V, GLFW_MOD_CONTROL, "",
            enabled = { context.clipboard != null },
        ) {
            val template = context.clipboard ?: return@register
            val parent = context.primary
            context.structural { it.addTemplate(template, parent) }
        }
        palette.register(
            "edit.selectAll", "Select all", "Edit", GLFW_KEY_A, GLFW_MOD_CONTROL, "",
            enabled = { context.open },
        ) {
            context.setSelection((0 until context.componentCount).toList())
        }
        palette.register("edit.deselect", "Deselect", "Edit", GLFW_KEY_ESCAPE, 0, "") {
            if (interaction.menuOpen) {
                interaction.closeMenu()
            } else {
                context.clearSelection()
            }
        }

        palette.register(
            "view.resetLayout", "Reset panel layout", "View", 0, 0,
            "Puts every panel back where it started.",
        ) { buildDefaultLayout() }
        palette.register("view.resetView", "Reset stage zoom", "View", 0, 0, "") { stagePanel.resetView() }
        palette.register("view.library", "Focus the interface list", "View", GLFW_KEY_L, GLFW_MOD_CONTROL, "") {
            library.visible = true
            ImGui.setWindowFocus("Interfaces")
        }

        palette.register(
            "run.mode", "Toggle Design / Play", "Run", GLFW_KEY_F5, 0,
            "Design selects and moves; Play runs the interface the way the client would.",
        ) { toggleMode() }
        palette.register(
            "run.load", "Run load scripts", "Run", GLFW_KEY_F6, 0,
            "Re-runs every component's onLoad, which is how an interface builds itself.",
            enabled = { context.open },
        ) { scriptsDirty = true }
        palette.register(
            "run.reset", "Reset the interface", "Run", 0, 0,
            "Drops every runtime binding and re-seeds from the cache.",
            enabled = { context.open },
        ) {
            cs2Host.clearHooks()
            cs2Host.log.clear()
            interaction.reset()
            context.document?.rebuild()
            scriptsDirty = true
            status = "interface reset"
        }
        palette.register(
            "run.source", "Reload from cache-source", "Run", GLFW_KEY_F7, 0,
            "Re-reads the interface's component JSON and re-compiles every edited script, without packing.",
            enabled = { source != null },
        ) {
            val components = reloadFromSource()
            val events = reload.reloadAll()
            status = if (events.isEmpty()) {
                components
            } else {
                "$components; ${events.size} scripts recompiled, ${events.count { !it.ok }} failed"
            }
        }
        palette.register("run.clearTrace", "Clear the event trace", "Run", 0, 0, "") {
            interaction.clearTrace()
        }

        palette.register(
            "help.palette", "Command palette", "Help", GLFW_KEY_P, GLFW_MOD_CONTROL, "",
        ) { palette.show() }
        palette.register("help.shortcuts", "Shortcut list", "Help", GLFW_KEY_F1, 0, "") {
            palette.show()
        }
    }

    private fun toggleMode() {
        mode = if (mode == EditorMode.PLAY) EditorMode.DESIGN else EditorMode.PLAY
        interaction.liveMode = mode == EditorMode.PLAY
        interaction.reset()
        gizmo.cancel()
        window.setCursorShape(GLFW_ARROW_CURSOR)
        status = if (mode == EditorMode.PLAY) "play mode: hooks fire" else "design mode: click to select"
    }


    private fun buildDefaultLayout() {
        val root = imGui.dockspace()
        if (root == 0) {
            return
        }
        val viewport = ImGui.getMainViewport()
        ImGuiInternal.dockBuilderRemoveNode(root)
        ImGuiInternal.dockBuilderAddNode(
            root,
            InternalDockNodeFlags.DockSpace or ImGuiDockNodeFlags.PassthruCentralNode,
        )
        ImGuiInternal.dockBuilderSetNodeSize(root, viewport.workSizeX, viewport.workSizeY)

        val left = ImInt()
        val body = ImInt()
        ImGuiInternal.dockBuilderSplitNode(root, ImGuiDir.Left, 0.19f, left, body)
        val leftLower = ImInt()
        val leftUpper = ImInt()
        ImGuiInternal.dockBuilderSplitNode(left.get(), ImGuiDir.Down, 0.58f, leftLower, leftUpper)
        val right = ImInt()
        val centre = ImInt()
        ImGuiInternal.dockBuilderSplitNode(body.get(), ImGuiDir.Right, 0.27f, right, centre)
        val rightLower = ImInt()
        val rightUpper = ImInt()
        ImGuiInternal.dockBuilderSplitNode(right.get(), ImGuiDir.Down, 0.42f, rightLower, rightUpper)
        val bottom = ImInt()
        val stage = ImInt()
        ImGuiInternal.dockBuilderSplitNode(centre.get(), ImGuiDir.Down, 0.30f, bottom, stage)

        dockNodes.clear()
        dockNodes["Interfaces"] = leftUpper.get()
        dockNodes["Tree"] = leftLower.get()
        dockNodes["Stage"] = stage.get()
        dockNodes["Interaction"] = bottom.get()
        dockNodes["CS2 Hot Reload"] = bottom.get()
        dockNodes["State"] = bottom.get()
        dockNodes["Inspector"] = rightUpper.get()
        dockNodes["CS2 Hooks"] = rightLower.get()
        dockNodes["Validation"] = rightLower.get()
        for ((title, node) in dockNodes) {
            ImGuiInternal.dockBuilderDockWindow(title, node)
        }
        ImGuiInternal.dockBuilderFinish(root)
        dockFrames = DOCK_FRAMES
    }

    private fun dock(title: String) {
        if (dockFrames <= 0) {
            return
        }
        dockNodes[title]?.let { ImGui.setNextWindowDockID(it) }
    }


    override fun onCleanup() {
        sourceWatch.close()
        reload.close()
        modelRenderer.cleanup()
        icons.cleanup()
        fonts.cleanup()
        graphics.cleanup()
        canvas.cleanup()
        stageTarget.cleanup()
        imGui.cleanup()
    }

    companion object {
        private const val LAYOUT_FILE = "darkan-tools-layout.ini"
        private const val BACKGROUND = 0.09f
        private const val TEMPLATE_SCAN = 4096
        private const val NO_CHAR = '\u0000'

        private const val STATUS_WIDTH = 560f
        private const val STATUS_CHARS = 72
        private const val DIALOG = "New interface"
        private const val SOURCE_DIALOG = "Live source"

        private const val SOURCE_DEBOUNCE = 150L

        private const val SCRIPT_CLOSURE_LIMIT = 512
        private const val DIALOG_BUTTON = 110f
        private const val DOCK_FRAMES = 4

        private const val SOURCE_EMPTY = 0
        private const val SOURCE_COPY = 1
        private const val SOURCE_IMPORT = 2

        private const val HOVER_OUTLINE = 0x9028c8ffL.toInt()
        private const val SECONDARY_OUTLINE = 0xa0ffb040L.toInt()
        private const val PLAY_OUTLINE = 0x4028c8ffL.toInt()
    }
}
