package org.darkan.tools.viewer

import imgui.ImGui
import imgui.flag.ImGuiChildFlags
import imgui.flag.ImGuiCol
import imgui.flag.ImGuiCond
import imgui.flag.ImGuiHoveredFlags
import imgui.flag.ImGuiTreeNodeFlags
import imgui.flag.ImGuiWindowFlags
import imgui.type.ImBoolean
import imgui.type.ImInt
import imgui.type.ImString
import kotlin.math.sqrt
import kotlin.math.tan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.darkan.core.map.RegionLoader
import org.darkan.core.model.ObjectShape
import world.gregs.voidps.cache.definition.data.MapObject
import world.gregs.voidps.cache.definition.data.ObjectDefinition
import org.darkan.tools.cache.map.NormalMerger
import org.darkan.tools.cache.map.ObjectInstance
import org.darkan.tools.cache.map.ObjectRenderer
import org.darkan.tools.cache.map.TerrainAligner
import org.darkan.tools.cache.map.environment.BackgroundMode
import org.darkan.tools.cache.map.environment.EnvironmentConfigs
import org.darkan.tools.cache.map.environment.MapEnvironment
import org.darkan.tools.cache.map.environment.EnvironmentEdit
import org.darkan.tools.cache.map.environment.EnvironmentWritePlan
import org.darkan.tools.cache.map.environment.MapEnvironmentLoader
import org.darkan.tools.cache.map.environment.MapEnvironmentWriter
import org.darkan.tools.cache.model.ModelLoader
import org.darkan.tools.cache.terrain.TerrainEditManager
import org.darkan.tools.cache.terrain.EditResult
import org.darkan.tools.cache.terrain.TerrainLoader
import org.darkan.tools.cache.texture.TextureLoader
import org.darkan.tools.cache.texture.TexturePixelStats
import org.darkan.tools.engine.Engine
import org.darkan.tools.engine.async.ParallelChunks
import org.darkan.tools.engine.input.TilePicker
import org.darkan.tools.engine.region.AsyncRegionData
import org.darkan.tools.engine.region.AsyncRegionManager
import org.darkan.tools.engine.region.BatchDraw
import org.darkan.tools.engine.region.MergedTerrainRange
import org.darkan.tools.engine.region.RegionLoadProfiler
import org.darkan.tools.engine.region.RegionManager
import org.darkan.tools.engine.render.BatchKey
import org.darkan.tools.engine.render.EnvironmentSettings
import org.darkan.tools.engine.render.GpuTimerPool
import org.darkan.tools.engine.render.IndirectRenderer
import org.darkan.tools.engine.render.InstanceManager
import org.darkan.tools.engine.render.Mesh
import org.darkan.tools.engine.render.MeshData
import org.darkan.tools.engine.render.RenderMetrics
import org.darkan.tools.engine.render.TexturePixelProvider
import org.darkan.tools.engine.render.ShaderProgram
import org.darkan.tools.engine.render.TextureAtlas
import org.darkan.tools.engine.render.UniformBuffers
import org.darkan.tools.engine.render.sky.SkyBackgroundMode
import org.darkan.tools.engine.render.sky.SkyRenderer
import org.darkan.tools.engine.render.sky.SkySettings
import org.darkan.tools.engine.render.sky.SkyStateRestore
import org.darkan.tools.engine.spatial.Frustum
import org.darkan.tools.engine.window.WindowConfig
import org.darkan.tools.ui.DebugPanel
import org.darkan.tools.ui.ImGuiLayer
import org.darkan.tools.ui.ImGuiText
import org.darkan.tools.ui.LoadingScreen
import org.darkan.tools.util.CoordinateUtil
import org.joml.Matrix4f
import org.joml.Vector3f
import org.joml.Vector4f
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL13.GL_MULTISAMPLE
import org.lwjgl.opengl.GL13.GL_TEXTURE0
import org.lwjgl.opengl.GL13.glActiveTexture
import world.gregs.voidps.cache.Cache

private const val UNSAVED_EDITS_POPUP = "Unsaved edits##unsaved_edits"

class MapViewer(
    private val cache: Cache
) : Engine(WindowConfig(title = "Darkan Map Viewer", width = 1280, height = 720)) {

    private lateinit var uniformBuffers: UniformBuffers
    private lateinit var uboColorShader: ShaderProgram
    private lateinit var uboTexturedShader: ShaderProgram
    private lateinit var terrainAlphaShader: ShaderProgram

    private var terrainAtlasShader: ShaderProgram? = null
    private var terrainAtlasAlphaShader: ShaderProgram? = null

    private var terrainAtlas: TextureAtlas? = null
    private val SENTINEL_TEXTURE_ID = Int.MAX_VALUE

    private var objectAtlas: TextureAtlas? = null

    private lateinit var asyncRegionManager: AsyncRegionManager

    private lateinit var instancedColorShader: ShaderProgram
    private lateinit var instancedTexturedShader: ShaderProgram

    private var useIndirectRendering = System.getProperty("darkan.indirect", "true").toBoolean()
    private var indirectColorShader: ShaderProgram? = null
    private var indirectTexturedShader: ShaderProgram? = null
    private var indirectRenderer: IndirectRenderer? = null

    private val useGpuCulling = System.getProperty("darkan.gpuCull", "true").toBoolean()
    private val useStaticInstances = System.getProperty("darkan.staticInstances", "true").toBoolean()
    private val useCpuBatchCulling = System.getProperty("darkan.batchCull", "true").toBoolean()

    private val mergeContextTerrain = System.getProperty("darkan.mergeContextTerrain", "true").toBoolean()

    private val frustum = Frustum()
    private val frustumViewProjection = Matrix4f()
    private val frustumPlanes = FloatArray(24)

    private val readyContextRegions = mutableListOf<AsyncRegionData>()

    private val visibleContextRegions = mutableListOf<AsyncRegionData>()

    private var objectSceneDirty = true

    private var cullShaderSource: String? = null
    private var cullResetShaderSource: String? = null

    private val gpuTimers = GpuTimerPool(RenderMetrics.GPU_PASS_NAMES)

    private lateinit var staticColorShader: ShaderProgram
    private lateinit var staticTexturedShader: ShaderProgram

    private lateinit var imGui: ImGuiLayer
    private lateinit var debugPanel: DebugPanel
    private lateinit var loadingScreen: LoadingScreen

    private var currentRegionX = regionProperty()?.first ?: 52
    private var currentRegionY = regionProperty()?.second ?: 50
    private var currentPlane = 0

    private val regionXInput = ImInt(50)
    private val regionYInput = ImInt(50)
    private var showTerrain = System.getProperty("darkan.terrain", "true").toBoolean()
    private val showObjects = System.getProperty("darkan.objects", "true").toBoolean()
    private var showRoofs = true
    private val renderDistanceInput = intArrayOf(1)

    private val environment = EnvironmentSettings()

    private var sky: SkyRenderer? = null

    private val clearColor = Vector3f()

    private val knownRegions = listOf(
        "Lumbridge" to Pair(50, 50),
        "Varrock" to Pair(51, 51),
        "Falador" to Pair(46, 52),
        "Ardougne" to Pair(42, 51),
        "Camelot" to Pair(44, 55),
        "Al Kharid" to Pair(51, 49),
        "Draynor" to Pair(48, 50),
        "Edgeville" to Pair(48, 54),
        "Barbarian Village" to Pair(48, 53),
        "Wilderness" to Pair(48, 55)
    )

    private val editManagers = LinkedHashMap<Pair<Int, Int>, TerrainEditManager>()

    private val centerEditManager: TerrainEditManager?
        get() = editManagers[Pair(currentRegionX, currentRegionY)]

    private val terrainTextureBinding: (Int) -> Unit = { texId ->
        TextureLoader.bindTexture(TextureLoader.getTextureForMaterial(cache, texId))
    }

    private val tilePicker = TilePicker()
    private var tileHighlightMesh: Mesh? = null

    private var hoveredEditRegion: Pair<Int, Int>? = null
    private var editRejectNotice: String? = null
    private var editRejectNoticeExpiry = 0L
    private val EDIT_REJECT_NOTICE_NANOS = 5_000_000_000L

    private var showEditorPanel = true
    private var editorPanelFirstFrame = true
    private var currentEditorTool = EditorTool.SELECT

    private val editorObjectSearchQuery = ImString(256)
    private val editorOverlayUnderlayId = ImInt(0)
    private var editorPaintMode = 0
    private val editorBrushSize = intArrayOf(1)
    private val editorHeightBrushRadius = intArrayOf(1)
    private val editorHeightStrength = intArrayOf(5)
    private var editorHeightMode = 0

    private val editorOverlayShape = intArrayOf(0)
    private val editorOverlayRotation = intArrayOf(0)

    private val editedTilesThisDrag = mutableSetOf<EditTile>()

    private var objectSearchResults: List<ObjectDefinition> = emptyList()
    private var selectedObjectId = -1
    private val editorObjectShape = intArrayOf(10)
    private val editorObjectRotation = intArrayOf(0)

    private data class MovingObject(
        val instance: ObjectInstance,
        val originRegionX: Int,
        val originRegionY: Int,
        val originalX: Int,
        val originalY: Int
    )
    private var movingObject: MovingObject? = null
    private var moveOriginMesh: Mesh? = null

    private val placedObjects = mutableListOf<ObjectInstance>()

    private val editorModelMeshData = mutableMapOf<Int, List<MeshData>>()

    private val terrainEditedRegions = LinkedHashSet<Pair<Int, Int>>()
    private var movedObjectCount = 0

    private var movingObjectMoved = false

    private var pendingRegionChange: (() -> Unit)? = null
    private var pendingRegionChangeLabel = ""
    private var unsavedEditsPopupOpen = false

    private val envSunColourInput = FloatArray(3)
    private val envFogColourInput = FloatArray(3)
    private val envSunPositionInput = FloatArray(3)
    private val envSunAmbientInput = FloatArray(1)
    private val envSunLightInput = FloatArray(1)
    private val envSunBacklightInput = FloatArray(1)
    private val envFogDepthInput = intArrayOf(0)
    private val envViewRangeInput = FloatArray(1)
    private val envSkyboxInput = ImInt(-1)

    private var envWritePlan: EnvironmentWritePlan? = null
    private var envWritePlanRegion: Int = -1
    private val envWriteConfirm = ImBoolean(false)
    private var envWriteLog: List<String> = emptyList()
    private var envWriteError: String? = null

    private val editProbeScript: List<String> = System.getProperty("darkan.editProbe")
        ?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
    private var editProbeAim: Pair<Float, Float>? = null
    private var editProbeStep = 0
    private var editProbeSettle = 0

    private var editProbeAnchor: EditTile? = null

    private var brushPreviewMesh: Mesh? = null
    private var lastBrushCenter: EditTile? = null
    private lateinit var brushPreviewShader: ShaderProgram

    override fun onInit() {
        TextureLoader.init(cache)

        imGui = ImGuiLayer(window)
        imGui.init()
        debugPanel = DebugPanel()

        uniformBuffers = UniformBuffers()
        uniformBuffers.init()

        loadUBOShaders()

        sky = SkyRenderer(cache).also {
            it.init()
            it.stateRestore = SkyStateRestore.ASSUME_SCENE_DEFAULTS
        }

        TerrainLoader.bakeSunLighting = !environment.dynamicSunLighting

        asyncRegionManager = AsyncRegionManager(cache)
        asyncRegionManager.initialize()

        buildTerrainAtlas()

        objectAtlas = null

        asyncRegionManager.objectAtlas = objectAtlas
        asyncRegionManager.terrainAtlas = terrainAtlas

        loadingScreen = LoadingScreen()

        val brushVertSrc = loadShaderSource("/shaders/brush_preview.vert")
        val brushFragSrc = loadShaderSource("/shaders/brush_preview.frag")
        brushPreviewShader = ShaderProgram.createWithUniforms(
            brushVertSrc, brushFragSrc,
            "model", "view", "projection"
        )

        scene.camera.setPosition(0f, 50f, 50f)
        scene.camera.pitch = -45f
        scene.camera.yaw = -90f
        scene.camera.moveSpeed = 20f
        scene.camera.nearPlane = 1.0f
        scene.camera.farPlane = 4000f

        glEnable(GL_DEPTH_TEST)
        glEnable(GL_MULTISAMPLE)

        if (System.getProperty("darkan.hideUi", "false").toBoolean()) {
            debugPanel.visible = false
            showEditorPanel = false
        }

        if (System.getProperty("darkan.wireframe", "false").toBoolean()) {
            renderSystem.setWireframe(true)
        }

        RenderMetrics.gpuTimingActive = gpuTimers.init()

        System.getProperty("darkan.renderDistance")?.toIntOrNull()?.let {
            renderDistanceInput[0] = it.coerceIn(0, 5)
        }

        isInitialLoading = true
        loadRegionAsync(currentRegionX, currentRegionY, currentPlane)
    }

    private fun buildTerrainAtlas() {
        if (!System.getProperty("darkan.terrainAtlas", "true").toBoolean()) {
            println("[MapViewer] Terrain atlas disabled by -Ddarkan.terrainAtlas=false")
            TerrainLoader.atlasLookup = null
            return
        }

        val textureIds = TerrainLoader.getUniqueTextureIds()
        if (textureIds.isEmpty()) {
            println("[MapViewer] No terrain textures found in definitions, atlas disabled")
            return
        }

        println("[MapViewer] Building terrain atlas with ${textureIds.size} textures...")
        System.out.flush()
        val startTime = System.nanoTime()
        val atlas = TextureAtlas(atlasSize = 4096, textureSize = 128, border = 8)

        val sortedIds = textureIds.sorted()

        val generated = arrayOfNulls<Triple<IntArray, Int, Int>>(sortedIds.size)
        val errors = arrayOfNulls<String>(sortedIds.size)
        val genStart = System.nanoTime()
        val genStartCpu = RegionLoadProfiler.processCpuTimeNanos()
        val genStatsBefore = TexturePixelStats.snapshot()
        ParallelChunks.forEachIndexBlocking(sortedIds.size, ParallelChunks.TEXTURE_WORKER_COUNT) { i ->
            try {
                generated[i] = TextureLoader.getTexturePixelsForMaterial(cache, sortedIds[i])
            } catch (e: Throwable) {
                errors[i] = e.message ?: e.javaClass.simpleName
            }
        }
        val genMs = (System.nanoTime() - genStart) / 1e6
        println(TexturePixelStats.snapshot().since(genStatsBefore).describe("terrainAtlas"))
        val genCpuMs = if (genStartCpu >= 0) {
            (RegionLoadProfiler.processCpuTimeNanos() - genStartCpu) / 1e6
        } else {
            -1.0
        }

        var loaded = 0
        var failed = 0
        for ((index, texId) in sortedIds.withIndex()) {
            val error = errors[index]
            if (error != null) {
                println("[MapViewer]   Texture $texId FAILED: $error")
                failed++
                continue
            }
            val result = generated[index]
            if (result == null) {
                failed++
                continue
            }
            val (pixels, width, height) = result
            atlas.addTexture(texId, pixels, width, height)
            loaded++
        }
        val elapsed = (System.nanoTime() - startTime) / 1_000_000
        println("[MapViewer]   Texture generation completed in ${elapsed}ms: $loaded loaded, $failed failed")
        println(
            "[MapViewer]   texgen %.0f ms wall, %.0f ms CPU -> %.2f cores busy (workers=%d), pack %.0f ms"
                .format(genMs, genCpuMs, if (genMs > 0) genCpuMs / genMs else 0.0,
                    ParallelChunks.TEXTURE_WORKER_COUNT, elapsed - genMs)
        )

        val whitePixel = intArrayOf(0xFFFFFFFF.toInt())
        atlas.addTexture(SENTINEL_TEXTURE_ID, whitePixel, 1, 1)

        atlas.upload()

        val stats = atlas.getStats()
        println("[MapViewer] Terrain atlas built: ${stats.textureCount} textures on ${stats.pageCount} page(s), " +
                "$loaded loaded, $failed failed, utilization ${(stats.utilization * 100).toInt()}%")

        val sentinelRegion = atlas.getRegion(SENTINEL_TEXTURE_ID)
        if (sentinelRegion == null || sentinelRegion.atlasPageIndex != 0) {
            System.err.println("[MapViewer] Terrain atlas overflowed past its first page before the " +
                    "white sentinel could be packed (${stats.pageCount} pages, ${stats.textureCount} " +
                    "textures); disabling the atlas terrain path")
            atlas.cleanup()
            terrainAtlas = null
            TerrainLoader.atlasLookup = null
            return
        }

        val offPage = sortedIds.count { id ->
            val region = atlas.getRegion(id)
            region != null && region.atlasPageIndex != 0
        }
        if (offPage > 0) {
            System.err.println("[MapViewer] Terrain atlas overflowed: $offPage of ${sortedIds.size} " +
                    "textures landed beyond page 0 and will render with their own texture binds " +
                    "instead of being merged")
        }

        terrainAtlas = atlas

        TerrainLoader.atlasLookup = { textureId ->
            val region = atlas.getRegion(textureId)
            when {
                region == null -> sentinelRegion.getTransformVec4()
                region.atlasPageIndex != 0 -> null
                else -> region.getTransformVec4()
            }
        }
        TerrainLoader.sentinelAtlasTransform = sentinelRegion.getTransformVec4()
    }

    private fun loadUBOShaders() {
        val terrainColorVert = loadShaderSource("/shaders/terrain_color.vert")
        val terrainColorFrag = loadShaderSource("/shaders/terrain_color.frag")

        uboColorShader = ShaderProgram.createWithUniforms(
            terrainColorVert, terrainColorFrag,
            "model"
        )
        uniformBuffers.bindToShader(uboColorShader)

        val terrainTexturedVert = loadShaderSource("/shaders/terrain_textured.vert")
        val terrainTexturedFrag = loadShaderSource("/shaders/terrain_textured.frag")

        uboTexturedShader = ShaderProgram.createWithUniforms(
            terrainTexturedVert, terrainTexturedFrag,
            "model", "textureSampler"
        )
        uniformBuffers.bindToShader(uboTexturedShader)

        val terrainAlphaVert = loadShaderSource("/shaders/terrain_alpha.vert")
        val terrainAlphaFrag = loadShaderSource("/shaders/terrain_alpha.frag")

        terrainAlphaShader = ShaderProgram.createWithUniforms(
            terrainAlphaVert, terrainAlphaFrag,
            "model", "textureSampler"
        )
        uniformBuffers.bindToShader(terrainAlphaShader)

        val instancedColorVert = loadShaderSource("/shaders/ubo_instanced.vert")
        val instancedColorFrag = loadShaderSource("/shaders/ubo_color.frag")
        instancedColorShader = ShaderProgram.create(instancedColorVert, instancedColorFrag)
        uniformBuffers.bindToShader(instancedColorShader)

        val instancedTexturedVert = loadShaderSource("/shaders/ubo_instanced_textured.vert")
        val instancedTexturedFrag = loadShaderSource("/shaders/ubo_textured.frag")
        instancedTexturedShader = ShaderProgram.createWithUniforms(
            instancedTexturedVert, instancedTexturedFrag,
            "textureSampler"
        )
        uniformBuffers.bindToShader(instancedTexturedShader)

        if (useIndirectRendering && !IndirectRenderer.isSupported()) {
            println("[MapViewer] GL context lacks 4.3 + gl_BaseInstance; using legacy instanced path")
            useIndirectRendering = false
        }
        if (useIndirectRendering) {
            try {
                val indColorVert = loadShaderSource("/shaders/indirect_color.vert")
                val indColorFrag = loadShaderSource("/shaders/indirect_color.frag")
                indirectColorShader = ShaderProgram.create(indColorVert, indColorFrag)
                uniformBuffers.bindToShader(indirectColorShader!!)

                val indTexturedVert = loadShaderSource("/shaders/indirect_textured.vert")
                val indTexturedFrag = loadShaderSource("/shaders/indirect_textured.frag")
                indirectTexturedShader = ShaderProgram.createWithUniforms(
                    indTexturedVert, indTexturedFrag,
                    *IndirectRenderer.textureArrayUniformNames()
                )
                uniformBuffers.bindToShader(indirectTexturedShader!!)

                if (useGpuCulling) {
                    cullShaderSource = loadShaderSource("/shaders/cull_instances.comp")
                    cullResetShaderSource = loadShaderSource("/shaders/cull_reset.comp")
                }

                println("[MapViewer] Indirect draw shaders loaded successfully")
            } catch (e: Exception) {
                System.err.println("[MapViewer] Failed to load indirect draw shaders, falling back to instanced rendering: ${e.message}")
                indirectColorShader?.cleanup()
                indirectColorShader = null
                indirectTexturedShader?.cleanup()
                indirectTexturedShader = null
                useIndirectRendering = false
            }
        } else {
            println("[MapViewer] Indirect rendering disabled, using legacy instanced path")
        }

        val staticColorVert = loadShaderSource("/shaders/ubo_color.vert")
        val staticColorFrag = loadShaderSource("/shaders/ubo_color.frag")
        staticColorShader = ShaderProgram.createWithUniforms(
            staticColorVert, staticColorFrag,
            "model"
        )
        uniformBuffers.bindToShader(staticColorShader)

        val staticTexturedVert = loadShaderSource("/shaders/ubo_textured.vert")
        val staticTexturedFrag = loadShaderSource("/shaders/ubo_textured.frag")
        staticTexturedShader = ShaderProgram.createWithUniforms(
            staticTexturedVert, staticTexturedFrag,
            "model", "textureSampler"
        )
        uniformBuffers.bindToShader(staticTexturedShader)

        val atlasVert = loadShaderSource("/shaders/terrain_atlas.vert")
        val atlasFrag = loadShaderSource("/shaders/terrain_atlas.frag")
        terrainAtlasShader = ShaderProgram.createWithUniforms(
            atlasVert, atlasFrag,
            "model", "atlasTexture"
        )
        uniformBuffers.bindToShader(terrainAtlasShader!!)

        val atlasAlphaVert = loadShaderSource("/shaders/terrain_atlas_alpha.vert")
        val atlasAlphaFrag = loadShaderSource("/shaders/terrain_atlas_alpha.frag")
        terrainAtlasAlphaShader = ShaderProgram.createWithUniforms(
            atlasAlphaVert, atlasAlphaFrag,
            "model", "atlasTexture"
        )
        uniformBuffers.bindToShader(terrainAtlasAlphaShader!!)
    }

    override fun onInput() {
        if (!imGui.wantCaptureKeyboard()) {
            val cam = scene.camera

            val speedMult = if (input.keyboard.isKeyDown(GLFW_KEY_LEFT_CONTROL)) 4f else 1f
            cam.moveSpeed = 20f * speedMult

            if (input.keyboard.isKeyDown(GLFW_KEY_W)) cam.moveForward(deltaTime)
            if (input.keyboard.isKeyDown(GLFW_KEY_S)) cam.moveBackward(deltaTime)
            if (input.keyboard.isKeyDown(GLFW_KEY_A)) cam.moveLeft(deltaTime)
            if (input.keyboard.isKeyDown(GLFW_KEY_D)) cam.moveRight(deltaTime)
            if (input.keyboard.isKeyDown(GLFW_KEY_SPACE)) cam.moveUp(deltaTime)
            if (input.keyboard.isKeyDown(GLFW_KEY_LEFT_SHIFT)) cam.moveDown(deltaTime)

            if (input.keyboard.isKeyPressed(GLFW_KEY_F1)) debugPanel.toggleVisibility()
            if (input.keyboard.isKeyPressed(GLFW_KEY_F3)) renderSystem.toggleWireframe()
            if (input.keyboard.isKeyPressed(GLFW_KEY_T)) showTerrain = !showTerrain
            if (input.keyboard.isKeyPressed(GLFW_KEY_R)) setShowRoofs(!showRoofs)

            if (input.keyboard.isKeyPressed(GLFW_KEY_1)) setPlane(0)
            if (input.keyboard.isKeyPressed(GLFW_KEY_2)) setPlane(1)
            if (input.keyboard.isKeyPressed(GLFW_KEY_3)) setPlane(2)
            if (input.keyboard.isKeyPressed(GLFW_KEY_4)) setPlane(3)

            if (input.keyboard.isKeyPressed(GLFW_KEY_LEFT)) navigateRegion(-1, 0)
            if (input.keyboard.isKeyPressed(GLFW_KEY_RIGHT)) navigateRegion(1, 0)
            if (input.keyboard.isKeyPressed(GLFW_KEY_UP)) navigateRegion(0, 1)
            if (input.keyboard.isKeyPressed(GLFW_KEY_DOWN)) navigateRegion(0, -1)

            if (input.keyboard.isKeyPressed(GLFW_KEY_PAGE_UP)) {
                if (currentPlane < 3) setPlane(currentPlane + 1)
            }
            if (input.keyboard.isKeyPressed(GLFW_KEY_PAGE_DOWN)) {
                if (currentPlane > 0) setPlane(currentPlane - 1)
            }

            if (input.keyboard.isKeyPressed(GLFW_KEY_F5)) {
                showEditorPanel = !showEditorPanel
                if (showEditorPanel) {
                    editorPanelFirstFrame = true
                }
            }

            if (input.keyboard.isKeyPressed(GLFW_KEY_ESCAPE) && movingObject != null) {
                val moving = movingObject!!
                val tileSize = 512f * CoordinateUtil.DEFAULT_SCALE
                val origWorldX = moving.originRegionX * 64 + moving.originalX
                val origWorldY = moving.originRegionY * 64 + moving.originalY
                val def = moving.instance.definition
                val mo = moving.instance.mapObject
                val sizeX: Int
                val sizeY: Int
                if (mo.rotation == 1 || mo.rotation == 3) {
                    sizeX = def.sizeY
                    sizeY = def.sizeX
                } else {
                    sizeX = def.sizeX
                    sizeY = def.sizeY
                }
                val basePosX = origWorldX * tileSize
                val basePosZ = -origWorldY * tileSize
                val posOffsetX = sizeX * tileSize * 0.5f
                val posOffsetZ = -sizeY * tileSize * 0.5f
                moving.instance.worldPosition.x = basePosX + posOffsetX
                moving.instance.worldPosition.z = basePosZ + posOffsetZ
                objectSceneDirty = true
                recalcObjectHeight(moving.instance, force = true)
                movingObjectMoved = false
                movingObject = null
                moveOriginMesh?.cleanup()
                moveOriginMesh = null
            }
        }

        if (!imGui.wantCaptureMouse()) {
            val cam = scene.camera

            if (input.mouse.isButtonDown(GLFW_MOUSE_BUTTON_RIGHT)) {
                cam.rotate(input.mouse.deltaX.toFloat(), input.mouse.deltaY.toFloat())
            }

            if (input.mouse.scrollY != 0.0) {
                cam.zoom(input.mouse.scrollY.toFloat())
            }
        }

        if (!imGui.wantCaptureMouse()) {
            when (currentEditorTool) {
                EditorTool.SELECT -> {
                    if (input.mouse.isButtonPressed(GLFW_MOUSE_BUTTON_LEFT)) {
                        handleTilePick()
                    }
                }
                EditorTool.TERRAIN_PAINT -> {
                    if (input.mouse.isButtonDown(GLFW_MOUSE_BUTTON_LEFT)) {
                        handleTerrainPaint()
                    }
                    if (input.mouse.isButtonReleased(GLFW_MOUSE_BUTTON_LEFT)) {
                        editedTilesThisDrag.clear()
                    }
                }
                EditorTool.HEIGHT_EDIT -> {
                    if (input.mouse.isButtonDown(GLFW_MOUSE_BUTTON_LEFT)) {
                        handleHeightEdit()
                    }
                }
                EditorTool.FLAG_EDIT -> {
                    if (input.mouse.isButtonPressed(GLFW_MOUSE_BUTTON_LEFT)) {
                        handleTilePick()
                    }
                }
                EditorTool.OBJECT_PLACE -> {
                    if (input.mouse.isButtonPressed(GLFW_MOUSE_BUTTON_LEFT)) {
                        handleObjectPlace()
                    }
                }
                EditorTool.OBJECT_MOVE -> {
                    if (input.mouse.isButtonPressed(GLFW_MOUSE_BUTTON_LEFT) && movingObject == null) {
                        handleObjectMoveStart()
                    } else if (input.mouse.isButtonDown(GLFW_MOUSE_BUTTON_LEFT) && movingObject != null) {
                        handleObjectMoveDrag()
                    }
                    if (input.mouse.isButtonReleased(GLFW_MOUSE_BUTTON_LEFT) && movingObject != null) {
                        handleObjectMoveEnd()
                    }
                }
            }
        }
    }

    private val pickTerrainProvider = TilePicker.TerrainProvider { rx, ry ->
        val loaded = (rx == currentRegionX && ry == currentRegionY) ||
                asyncRegionManager.getReadyRegion(rx, ry) != null
        val edited = if (loaded) editManagers[Pair(rx, ry)]?.tileSnapshot else null
        if (edited != null && edited.isNotEmpty()) {
            edited
        } else if (rx == currentRegionX && ry == currentRegionY) {
            asyncRegionManager.centerTerrainData?.tiles
        } else if (asyncRegionManager.getReadyRegion(rx, ry) != null) {
            TerrainLoader.loadRegion(cache, rx, ry)?.tiles
        } else {
            null
        }
    }

    private fun regionMapObjects(regionX: Int, regionY: Int): List<MapObject>? {
        if (regionX == currentRegionX && regionY == currentRegionY) {
            return asyncRegionManager.centerMapObjects
        }
        val region = asyncRegionManager.getReadyRegion(regionX, regionY) ?: return null
        return region.objectInstances.map { it.mapObject }
    }

    private fun handleTilePick() {
        val selectedTile = pickTileUnderCursor()

        if (pickProbeEnabled) {
            if (selectedTile == null) {
                println("[PICKCLICK] mouse=(%.0f, %.0f) MISS".format(input.mouse.x.toFloat(), input.mouse.y.toFloat()))
            } else {
                reportPickPoint(input.mouse.x.toFloat(), input.mouse.y.toFloat())
            }
            System.out.flush()
        }

        if (selectedTile == null) return

        tilePicker.updateTileDetails(
            pickTerrainProvider.tiles(selectedTile.regionX, selectedTile.regionY),
            cache,
            regionMapObjects(selectedTile.regionX, selectedTile.regionY),
            selectedTile.regionX * 64,
            selectedTile.regionY * 64
        )

        updateTileHighlight()
    }

    private fun pickTileUnderCursor(): TilePicker.SelectedTile? {
        val aim = editProbeAim
        tilePicker.screenToWorldRay(
            aim?.first ?: input.mouse.x.toFloat(),
            aim?.second ?: input.mouse.y.toFloat(),
            window.width,
            window.height,
            scene.camera,
            getAspectRatio()
        )
        return tilePicker.pickTile(pickTerrainProvider, currentPlane)
    }

    private data class EditTile(val regionX: Int, val regionY: Int, val localX: Int, val localY: Int)

    private fun getEditTileUnderCursor(): EditTile? {
        val picked = pickTileUnderCursor()
        if (picked == null) {
            hoveredEditRegion = null
            return null
        }
        hoveredEditRegion =
            if (picked.regionX == currentRegionX && picked.regionY == currentRegionY) null
            else Pair(picked.regionX, picked.regionY)
        return EditTile(picked.regionX, picked.regionY, picked.localX, picked.localY)
    }

    private fun editManagerFor(regionX: Int, regionY: Int): TerrainEditManager? {
        val key = Pair(regionX, regionY)
        editManagers[key]?.let { return it }

        val start = System.nanoTime()
        val manager = try {
            TerrainEditManager(regionX, regionY, currentPlane, cache).also { it.init() }
        } catch (e: Exception) {
            println("[MapViewer] TerrainEditManager init failed for ($regionX, $regionY): ${e.message}")
            noteEditRejected(uneditableRegionMessage(regionX, regionY))
            return null
        }
        editManagers[key] = manager

        if (regionX != currentRegionX || regionY != currentRegionY) {
            asyncRegionManager.invalidateMergedTerrain()
        }
        println(
            "[MapEditor] region ($regionX, $regionY) is now editable " +
                    "(edit manager built in ${"%.0f".format((System.nanoTime() - start) / 1e6)} ms, " +
                    "${editManagers.size} live)"
        )
        return manager
    }

    private fun noteEditRejected(message: String) {
        editRejectNotice = message
        editRejectNoticeExpiry = System.nanoTime() + EDIT_REJECT_NOTICE_NANOS
    }

    private fun uneditableRegionMessage(regionX: Int, regionY: Int): String =
        "Region ($regionX, $regionY) could not be opened for editing - its terrain failed to load, " +
                "so there is nothing to write the edit into."

    private fun noteMoveUnavailable(regionX: Int, regionY: Int) = noteEditRejected(
        "Nothing grabbable at that tile. Region ($regionX, $regionY) draws its scenery as merged " +
                "static batches, so only its terrain and objects you placed yourself can be moved - " +
                "load region $regionX / $regionY as the centre to move its own objects."
    )

    private fun tileHeightUnits(regionX: Int, regionY: Int, plane: Int, x: Int, y: Int): Int {
        val rx = regionX + Math.floorDiv(x, 64)
        val ry = regionY + Math.floorDiv(y, 64)
        val lx = Math.floorMod(x, 64)
        val ly = Math.floorMod(y, 64)
        editManagers[Pair(rx, ry)]?.let { return it.mutableData.tiles[plane][lx][ly].height }
        pickTerrainProvider.tiles(rx, ry)?.let { return it[plane][lx][ly].height }
        val cx = x.coerceIn(0, 63)
        val cy = y.coerceIn(0, 63)
        editManagers[Pair(regionX, regionY)]?.let { return it.mutableData.tiles[plane][cx][cy].height }
        val tiles = pickTerrainProvider.tiles(regionX, regionY) ?: return 0
        return tiles[plane][cx][cy].height
    }

    private fun tileHeightWorld(regionX: Int, regionY: Int, plane: Int, x: Int, y: Int): Float =
        tileHeightUnits(regionX, regionY, plane, x, y) * TerrainLoader.getHeightScale()

    private fun applyTileFlags(regionX: Int, regionY: Int, x: Int, y: Int, newFlags: Int) {
        val em = editManagerFor(regionX, regionY)
        if (em == null) {
            noteEditRejected(uneditableRegionMessage(regionX, regionY))
            return
        }
        em.mutableData.setFlags(currentPlane, x, y, newFlags)
        terrainEditedRegions.add(Pair(regionX, regionY))
        tilePicker.updateTileDetails(
            em.mutableData.toTileArray(),
            cache,
            regionMapObjects(regionX, regionY),
            regionX * 64,
            regionY * 64
        )
    }

    private fun tileFlags(regionX: Int, regionY: Int, plane: Int, x: Int, y: Int): Int? {
        editManagers[Pair(regionX, regionY)]?.let { return it.mutableData.tiles[plane][x][y].flags }
        val tiles = pickTerrainProvider.tiles(regionX, regionY) ?: return null
        return tiles[plane][x][y].flags
    }

    private fun handleTerrainPaint() {
        val center = getEditTileUnderCursor() ?: return
        val em = editManagerFor(center.regionX, center.regionY) ?: return
        val radius = editorBrushSize[0] - 1
        val id = editorOverlayUnderlayId.get()

        for (dx in -radius..radius) {
            for (dy in -radius..radius) {
                val tx = center.localX + dx
                val ty = center.localY + dy
                if (tx < 0 || tx > 63 || ty < 0 || ty > 63) continue
                val key = EditTile(center.regionX, center.regionY, tx, ty)
                if (key in editedTilesThisDrag) continue
                editedTilesThisDrag.add(key)
                terrainEditedRegions.add(Pair(center.regionX, center.regionY))

                if (editorPaintMode == 0) {
                    em.mutableData.setOverlayId(
                        currentPlane, tx, ty,
                        id,
                        editorOverlayShape[0],
                        editorOverlayRotation[0]
                    )
                } else {
                    em.mutableData.setUnderlayId(currentPlane, tx, ty, id)
                }
            }
        }
    }

    private fun handleHeightEdit() {
        val center = getEditTileUnderCursor() ?: return
        val em = editManagerFor(center.regionX, center.regionY) ?: return
        val editedRegion = Pair(center.regionX, center.regionY)
        val radius = editorHeightBrushRadius[0]
        val strength = editorHeightStrength[0]

        when (editorHeightMode) {
            0, 1 -> {
                val sign = if (editorHeightMode == 0) 1 else -1
                for (dx in -radius..radius) {
                    for (dy in -radius..radius) {
                        val tx = center.localX + dx
                        val ty = center.localY + dy
                        if (tx < 0 || tx > 63 || ty < 0 || ty > 63) continue

                        val dist = sqrt((dx * dx + dy * dy).toFloat())
                        val falloff = (1f - dist / (radius + 1)).coerceIn(0f, 1f)
                        val delta = (strength * falloff * sign).toInt()

                        val current = em.mutableData.tiles[currentPlane][tx][ty].height
                        em.mutableData.setHeight(currentPlane, tx, ty, current + delta)
                        terrainEditedRegions.add(editedRegion)
                    }
                }
            }
            2 -> {
                var sum = 0L
                var count = 0
                for (dx in -radius..radius) {
                    for (dy in -radius..radius) {
                        val tx = center.localX + dx
                        val ty = center.localY + dy
                        if (tx < 0 || tx > 63 || ty < 0 || ty > 63) continue
                        sum += em.mutableData.tiles[currentPlane][tx][ty].height
                        count++
                    }
                }
                if (count == 0) return
                val avg = (sum / count).toInt()

                for (dx in -radius..radius) {
                    for (dy in -radius..radius) {
                        val tx = center.localX + dx
                        val ty = center.localY + dy
                        if (tx < 0 || tx > 63 || ty < 0 || ty > 63) continue
                        val current = em.mutableData.tiles[currentPlane][tx][ty].height
                        val newHeight = current + (avg - current) / 2
                        em.mutableData.setHeight(currentPlane, tx, ty, newHeight)
                        terrainEditedRegions.add(editedRegion)
                    }
                }
            }
        }
    }

    private fun updateObjectHeights() {
        if (editManagers.isEmpty()) return
        for (obj in asyncRegionManager.centerObjects) recalcObjectHeight(obj)
        for (obj in placedObjects) recalcObjectHeight(obj)
    }

    private fun recalcObjectHeight(obj: ObjectInstance, force: Boolean = false) {
        val mo = obj.mapObject
        val regionX = mo.x / 64
        val regionY = mo.y / 64
        if (!force && Pair(regionX, regionY) !in editManagers) return

        val def = obj.definition
        val localTileX = mo.x - regionX * 64
        val localTileY = mo.y - regionY * 64

        val sizeX: Int
        val sizeY: Int
        if (mo.rotation == 1 || mo.rotation == 3) {
            sizeX = def.sizeY
            sizeY = def.sizeX
        } else {
            sizeX = def.sizeX
            sizeY = def.sizeY
        }

        val xA: Int
        val xB: Int
        if (localTileX + sizeX <= 64) {
            xA = localTileX + (sizeX shr 1)
            xB = localTileX + ((sizeX + 1) shr 1)
        } else {
            xA = localTileX
            xB = localTileX + 1
        }

        val yA: Int
        val yB: Int
        if (localTileY + sizeY <= 64) {
            yA = localTileY + (sizeY shr 1)
            yB = localTileY + ((sizeY + 1) shr 1)
        } else {
            yA = localTileY
            yB = localTileY + 1
        }

        val x0 = xA
        val x1 = xB
        val y0 = yA
        val y1 = yB

        val plane = mo.level
        val h00 = tileHeightWorld(regionX, regionY, plane, x0, y0)
        val h10 = tileHeightWorld(regionX, regionY, plane, x1, y0)
        val h01 = tileHeightWorld(regionX, regionY, plane, x0, y1)
        val h11 = tileHeightWorld(regionX, regionY, plane, x1, y1)
        val newHeight = (h00 + h10 + h01 + h11) / 4f

        obj.worldPosition.y = newHeight
        objectSceneDirty = true

        if (obj.groundContourType == 3) {
            obj.terrainAlignment = TerrainAligner.calculateObjectTilt(
                localTileX = localTileX,
                localTileY = localTileY,
                sizeX = sizeX,
                sizeY = sizeY,
                groundContourType = obj.groundContourType,
                groundContourModifier = obj.groundContourModifier,
                averageHeight = newHeight,
                heightSampler = { tx, ty ->
                    tileHeightWorld(regionX, regionY, plane, tx.coerceIn(0, 63), ty.coerceIn(0, 63))
                }
            )
        }
    }

    private fun searchObjectDefinitions(query: String, limit: Int = 50): List<ObjectDefinition> {
        val defs = Cache.objects
        val results = mutableListOf<ObjectDefinition>()

        val idMatch = query.toIntOrNull()
        if (idMatch != null && idMatch in defs.indices) {
            val def = defs[idMatch]
            if (def.name != "null") results.add(def)
        }

        val lowerQuery = query.lowercase()
        for (def in defs) {
            if (results.size >= limit) break
            if (def.name == "null" || def.id == -1) continue
            if (def.name.lowercase().contains(lowerQuery) && def !in results) {
                results.add(def)
            }
        }

        return results
    }

    private fun computeObjectWorldPosition(
        regionX: Int,
        regionY: Int,
        localX: Int,
        localY: Int,
        def: ObjectDefinition,
        rotation: Int
    ): Triple<Vector3f, Int, Int> {
        val tileSize = 512f * CoordinateUtil.DEFAULT_SCALE

        val sizeX: Int
        val sizeY: Int
        if (rotation == 1 || rotation == 3) {
            sizeX = def.sizeY
            sizeY = def.sizeX
        } else {
            sizeX = def.sizeX
            sizeY = def.sizeY
        }

        val xA: Int
        val xB: Int
        if (localX + sizeX <= 64) {
            xA = localX + (sizeX shr 1)
            xB = localX + ((sizeX + 1) shr 1)
        } else {
            xA = localX
            xB = localX + 1
        }
        val yA: Int
        val yB: Int
        if (localY + sizeY <= 64) {
            yA = localY + (sizeY shr 1)
            yB = localY + ((sizeY + 1) shr 1)
        } else {
            yA = localY
            yB = localY + 1
        }
        val x0 = xA
        val x1 = xB
        val y0 = yA
        val y1 = yB

        val h00 = tileHeightWorld(regionX, regionY, currentPlane, x0, y0)
        val h10 = tileHeightWorld(regionX, regionY, currentPlane, x1, y0)
        val h01 = tileHeightWorld(regionX, regionY, currentPlane, x0, y1)
        val h11 = tileHeightWorld(regionX, regionY, currentPlane, x1, y1)
        val terrainHeight = (h00 + h10 + h01 + h11) / 4f

        val worldTileX = regionX * 64 + localX
        val worldTileY = regionY * 64 + localY
        val basePosX = worldTileX * tileSize
        val basePosZ = -worldTileY * tileSize
        val posOffsetX = sizeX * tileSize * 0.5f
        val posOffsetZ = -sizeY * tileSize * 0.5f

        return Triple(
            Vector3f(basePosX + posOffsetX, terrainHeight, basePosZ + posOffsetZ),
            sizeX,
            sizeY
        )
    }

    private fun ensureModelRegistered(def: ObjectDefinition, shape: Int): Int {
        val im = asyncRegionManager.instanceManager ?: return -1
        val modelIds = ObjectRenderer.getModelIds(def, shape)
        if (modelIds.isEmpty()) return -1

        val modifications = ObjectRenderer.getModifications(def)
        val combinedId = ModelLoader.getCombinedModelIdWithModifications(modelIds, modifications)

        val meshDataList = editorModelMeshData[combinedId]
            ?: asyncRegionManager.centerModelMeshData[combinedId]
            ?: if (modifications.hasModifications()) {
                ModelLoader.createCombinedMeshDataListWithModifications(cache, modelIds, modifications)
            } else {
                ModelLoader.createCombinedMeshDataList(cache, modelIds)
            }
        if (meshDataList.isNullOrEmpty()) return -1

        editorModelMeshData[combinedId] = meshDataList

        if (!im.isModelRegistered(combinedId)) im.registerModel(combinedId, meshDataList)

        return combinedId
    }

    private fun ingestUnregisteredModels(ir: IndirectRenderer, entries: List<InstanceEntry>) {
        val centerMeshData = asyncRegionManager.centerModelMeshData
        val unavailable = asyncRegionManager.unavailableModels
        val seen = HashSet<Int>()
        var ingested = 0
        var noGeometry = 0
        for (entry in entries) {
            val modelId = entry.modelId
            if (!seen.add(modelId)) continue
            if (ir.isModelRegistered(modelId) || modelId in unavailable) continue
            val meshes = editorModelMeshData[modelId] ?: centerMeshData[modelId]
            if (meshes.isNullOrEmpty()) {
                noGeometry++
                continue
            }
            if (ir.registerModelLate(modelId, meshes)) ingested++ else noGeometry++
        }
        if (ingested > 0) {
            println("[MapViewer] ingested $ingested model(s) into the indirect renderer after its build")
        }
        if (noGeometry > 0) {
            System.err.println(
                "[MapViewer] $noGeometry submitted model(s) have no mesh data to ingest; " +
                        "their instances will be dropped"
            )
        }
    }

    private fun handleObjectPlace() {
        if (selectedObjectId < 0) return
        val tile = getEditTileUnderCursor() ?: return

        val def = ObjectRenderer.getDefinition(selectedObjectId) ?: return
        val shape = editorObjectShape[0]
        val rotation = editorObjectRotation[0]

        val combinedId = ensureModelRegistered(def, shape)
        if (combinedId == -1) return

        val (worldPos, sizeX, sizeY) =
            computeObjectWorldPosition(tile.regionX, tile.regionY, tile.localX, tile.localY, def, rotation)

        val worldX = tile.regionX * 64 + tile.localX
        val worldY = tile.regionY * 64 + tile.localY
        val modelIds = ObjectRenderer.getModelIds(def, shape)
        val modifications = ObjectRenderer.getModifications(def)

        val mapObject = MapObject(selectedObjectId, worldX, worldY, currentPlane, shape, rotation)

        val scaleX = def.modelSizeX / 128f
        val scaleY = def.modelSizeZ / 128f
        val scaleZ = def.modelSizeY / 128f
        val defOffsetX = def.offsetX * CoordinateUtil.DEFAULT_SCALE
        val defOffsetY = def.offsetZ * CoordinateUtil.DEFAULT_SCALE
        val defOffsetZ = def.offsetY * CoordinateUtil.DEFAULT_SCALE

        val instance = ObjectInstance(
            mapObject = mapObject,
            definition = def,
            modelIds = modelIds,
            worldPosition = worldPos,
            rotation = ObjectRenderer.rotationToRadians(rotation),
            scale = Vector3f(scaleX, scaleY, scaleZ),
            offset = Vector3f(defOffsetX, -defOffsetY, -defOffsetZ),
            mirrored = def.mirrored,
            modifications = modifications,
            cachedCombinedModelId = combinedId
        )

        placedObjects.add(instance)
        objectSceneDirty = true

        println("[MapEditor] Placed object '${def.name}' (id=$selectedObjectId) at ($worldX, $worldY) plane=$currentPlane shape=$shape rot=$rotation")
    }

    private fun handleObjectMoveStart() {
        val tile = getEditTileUnderCursor() ?: return

        val worldX = tile.regionX * 64 + tile.localX
        val worldY = tile.regionY * 64 + tile.localY

        val searched = listOf(asyncRegionManager.centerObjects, placedObjects)
        for (instances in searched) {
            for (instance in instances) {
                val obj = instance.mapObject
                val def = instance.definition
                if (obj.level != currentPlane) continue

                val sizeX: Int
                val sizeY: Int
                if (obj.rotation == 1 || obj.rotation == 3) {
                    sizeX = def.sizeY
                    sizeY = def.sizeX
                } else {
                    sizeX = def.sizeX
                    sizeY = def.sizeY
                }

                if (worldX in obj.x until (obj.x + sizeX) &&
                    worldY in obj.y until (obj.y + sizeY)) {
                    val originRegionX = obj.x / 64
                    val originRegionY = obj.y / 64
                    val origLocalX = obj.x - originRegionX * 64
                    val origLocalY = obj.y - originRegionY * 64
                    movingObject = MovingObject(instance, originRegionX, originRegionY, origLocalX, origLocalY)

                    moveOriginMesh?.cleanup()
                    moveOriginMesh =
                        buildTileOutlineMesh(originRegionX, originRegionY, origLocalX, origLocalY, sizeX, sizeY)

                    println("[MapEditor] Grabbed object '${def.name}' (id=${obj.id}) at (${obj.x}, ${obj.y}) for moving")
                    return
                }
            }
        }

        if (tile.regionX != currentRegionX || tile.regionY != currentRegionY) {
            noteMoveUnavailable(tile.regionX, tile.regionY)
        }
    }

    private fun handleObjectMoveDrag() {
        val moving = movingObject ?: return
        val tile = getEditTileUnderCursor() ?: return

        val def = moving.instance.definition
        val rotation = moving.instance.mapObject.rotation

        val (newPos, _, _) =
            computeObjectWorldPosition(tile.regionX, tile.regionY, tile.localX, tile.localY, def, rotation)
        val wp = moving.instance.worldPosition
        if (wp.x != newPos.x || wp.y != newPos.y || wp.z != newPos.z) {
            wp.set(newPos)
            movingObjectMoved = true
            objectSceneDirty = true
        }
    }

    private fun handleObjectMoveEnd() {
        if (movingObjectMoved) movedObjectCount++
        movingObjectMoved = false
        movingObject = null
        moveOriginMesh?.cleanup()
        moveOriginMesh = null
    }

    private fun buildTileOutlineMesh(
        regionX: Int,
        regionY: Int,
        localX: Int,
        localY: Int,
        sizeX: Int,
        sizeY: Int
    ): Mesh {
        val tileSize = 512f * CoordinateUtil.DEFAULT_SCALE
        val worldOriginX = regionX * 64 * tileSize
        val worldOriginZ = -regionY * 64 * tileSize
        val yOffset = 0.2f

        val h = tileHeightWorld(regionX, regionY, currentPlane, localX.coerceIn(0, 63), localY.coerceIn(0, 63))

        val x0 = worldOriginX + localX * tileSize
        val x1 = worldOriginX + (localX + sizeX) * tileSize
        val z0 = worldOriginZ + (-localY * tileSize)
        val z1 = worldOriginZ + (-(localY + sizeY) * tileSize)

        val positions = floatArrayOf(
            x0, h + yOffset, z0,
            x1, h + yOffset, z0,
            x1, h + yOffset, z0,
            x1, h + yOffset, z1,
            x1, h + yOffset, z1,
            x0, h + yOffset, z1,
            x0, h + yOffset, z1,
            x0, h + yOffset, z0
        )

        val colors = FloatArray(8 * 3) { i ->
            when (i % 3) {
                0 -> 1f
                1 -> 0.5f
                2 -> 0f
                else -> 1f
            }
        }

        val indices = IntArray(8) { it }

        return Mesh(
            positions = positions,
            colors = colors,
            textureCoords = null,
            normals = null,
            indices = indices,
            colorComponents = 3
        )
    }

    private fun updateTileHighlight() {
        val tile = tilePicker.selectedTile ?: return
        val terrainTiles = pickTerrainProvider.tiles(tile.regionX, tile.regionY) ?: return
        if (tile.plane >= terrainTiles.size) return

        tileHighlightMesh?.cleanup()

        val tileSize = 512f * CoordinateUtil.DEFAULT_SCALE
        val heightScale = TerrainLoader.getHeightScale()

        val x = tile.localX
        val y = tile.localY
        val nextX = (x + 1).coerceAtMost(63)
        val nextY = (y + 1).coerceAtMost(63)

        val h00 = terrainTiles[tile.plane][x][y].height * heightScale
        val h10 = terrainTiles[tile.plane][nextX][y].height * heightScale
        val h01 = terrainTiles[tile.plane][x][nextY].height * heightScale
        val h11 = terrainTiles[tile.plane][nextX][nextY].height * heightScale

        val worldOriginX = tile.regionX * 64 * tileSize
        val worldOriginZ = -tile.regionY * 64 * tileSize
        val yOffset = 0.1f
        val x0 = worldOriginX + x * tileSize
        val x1 = worldOriginX + (x + 1) * tileSize
        val z0 = worldOriginZ + (-y * tileSize)
        val z1 = worldOriginZ + (-(y + 1) * tileSize)

        val positions = floatArrayOf(
            x0, h00 + yOffset, z0,
            x1, h10 + yOffset, z0,
            x1, h10 + yOffset, z0,
            x1, h11 + yOffset, z1,
            x1, h11 + yOffset, z1,
            x0, h01 + yOffset, z1,
            x0, h01 + yOffset, z1,
            x0, h00 + yOffset, z0
        )

        val colors = FloatArray(8 * 3) { i ->
            when (i % 3) {
                0 -> 1f
                1 -> 1f
                2 -> 0f
                else -> 1f
            }
        }

        val indices = IntArray(8) { it }

        tileHighlightMesh = Mesh(
            positions = positions,
            colors = colors,
            textureCoords = null,
            normals = null,
            indices = indices,
            colorComponents = 3
        )
    }

    private fun updateBrushPreview() {
        val isBrushTool = currentEditorTool == EditorTool.TERRAIN_PAINT || currentEditorTool == EditorTool.HEIGHT_EDIT
        if (!isBrushTool || imGui.wantCaptureMouse()) {
            hoveredEditRegion = null
            if (brushPreviewMesh != null) {
                brushPreviewMesh?.cleanup()
                brushPreviewMesh = null
                lastBrushCenter = null
            }
            return
        }

        val center = getEditTileUnderCursor()
        if (center == null) {
            if (brushPreviewMesh != null) {
                brushPreviewMesh?.cleanup()
                brushPreviewMesh = null
                lastBrushCenter = null
            }
            return
        }

        if (center == lastBrushCenter) return
        lastBrushCenter = center

        brushPreviewMesh?.cleanup()

        val radius = when (currentEditorTool) {
            EditorTool.TERRAIN_PAINT -> editorBrushSize[0] - 1
            EditorTool.HEIGHT_EDIT -> editorHeightBrushRadius[0]
            else -> 0
        }

        val brushColor = when (currentEditorTool) {
            EditorTool.TERRAIN_PAINT -> floatArrayOf(0.2f, 0.8f, 0.2f, 0.25f)
            EditorTool.HEIGHT_EDIT -> when (editorHeightMode) {
                0 -> floatArrayOf(0.2f, 0.4f, 0.9f, 0.25f)
                1 -> floatArrayOf(0.9f, 0.2f, 0.2f, 0.25f)
                2 -> floatArrayOf(0.9f, 0.9f, 0.2f, 0.25f)
                else -> floatArrayOf(0.5f, 0.5f, 0.5f, 0.25f)
            }
            else -> floatArrayOf(0.5f, 0.5f, 0.5f, 0.25f)
        }

        val tileSize = 512f * CoordinateUtil.DEFAULT_SCALE
        val regionX = center.regionX
        val regionY = center.regionY
        val worldOriginX = regionX * 64 * tileSize
        val worldOriginZ = -regionY * 64 * tileSize
        val yOffset = 0.15f

        val positions = mutableListOf<Float>()
        val colors = mutableListOf<Float>()
        val indices = mutableListOf<Int>()
        var vertexIndex = 0

        for (dx in -radius..radius) {
            for (dy in -radius..radius) {
                val tx = center.localX + dx
                val ty = center.localY + dy
                if (tx < 0 || tx > 63 || ty < 0 || ty > 63) continue
                val nextX = (tx + 1).coerceAtMost(63)
                val nextY = (ty + 1).coerceAtMost(63)

                val h00 = tileHeightWorld(regionX, regionY, currentPlane, tx, ty)
                val h10 = tileHeightWorld(regionX, regionY, currentPlane, nextX, ty)
                val h01 = tileHeightWorld(regionX, regionY, currentPlane, tx, nextY)
                val h11 = tileHeightWorld(regionX, regionY, currentPlane, nextX, nextY)

                val x0 = worldOriginX + tx * tileSize
                val x1 = worldOriginX + (tx + 1) * tileSize
                val z0 = worldOriginZ + (-ty * tileSize)
                val z1 = worldOriginZ + (-(ty + 1) * tileSize)

                positions.add(x0); positions.add(h00 + yOffset); positions.add(z0)
                positions.add(x1); positions.add(h10 + yOffset); positions.add(z0)
                positions.add(x0); positions.add(h01 + yOffset); positions.add(z1)
                positions.add(x1); positions.add(h11 + yOffset); positions.add(z1)

                for (v in 0 until 4) {
                    colors.add(brushColor[0])
                    colors.add(brushColor[1])
                    colors.add(brushColor[2])
                    colors.add(brushColor[3])
                }

                indices.add(vertexIndex)
                indices.add(vertexIndex + 1)
                indices.add(vertexIndex + 2)
                indices.add(vertexIndex + 1)
                indices.add(vertexIndex + 3)
                indices.add(vertexIndex + 2)

                vertexIndex += 4
            }
        }

        if (positions.isNotEmpty()) {
            brushPreviewMesh = Mesh(
                positions = positions.toFloatArray(),
                colors = colors.toFloatArray(),
                textureCoords = null,
                normals = null,
                indices = indices.toIntArray(),
                colorComponents = 4
            )
        }
    }

    private fun renderBrushPreview() {
        val mesh = brushPreviewMesh ?: return

        brushPreviewShader.bind()
        brushPreviewShader.setUniform("model", Matrix4f().identity())
        brushPreviewShader.setUniform("view", scene.camera.getViewMatrix())
        brushPreviewShader.setUniform("projection", scene.camera.getProjectionMatrix(getAspectRatio()))

        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glDepthMask(false)

        mesh.render()

        glDepthMask(true)
        glDisable(GL_BLEND)
    }

    private var lastMergedTerrainRebuildNanos = 0L

    private val mergedTerrainRebuildIntervalNanos = 3_000_000_000L

    override fun onDrainGpuUploads(budgetNanos: Long) {
        val drainStart = RenderMetrics.startTiming()
        asyncRegionManager.drainGpuUploads(budgetNanos)
        val currentProgress = asyncRegionManager.getLoadProgress()
        RenderMetrics.gpuUploadDrainTimeMs = RenderMetrics.endTiming(drainStart)

        if (isInitialLoading && currentProgress.isComplete) {
            isInitialLoading = false
        }

        if (terrainAtlas != null && asyncRegionManager.mergedTerrainNeedsRebuild()) {
            val now = System.nanoTime()
            if (currentProgress.isComplete || now - lastMergedTerrainRebuildNanos >= mergedTerrainRebuildIntervalNanos) {
                asyncRegionManager.buildMergedAtlasTerrain()
                lastMergedTerrainRebuildNanos = now
            }
        }
    }

    override fun onRenderLoadingScreen() {
        RenderMetrics.resetFrame()
        glClearColor(clearColor.x, clearColor.y, clearColor.z, 1.0f)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)

        val renderStart = RenderMetrics.startTiming()
        renderScene()
        RenderMetrics.renderTimeMs = RenderMetrics.endTiming(renderStart)

        imGui.beginFrame()
        loadingScreen.render(asyncRegionManager.getLoadProgress(), window.width, window.height)
        debugPanel.fps = fps
        debugPanel.deltaTime = deltaTime
        debugPanel.wireframeMode = renderSystem.wireframeMode
        debugPanel.render(scene.camera, window.width, window.height) {
            renderSystem.toggleWireframe()
        }
        imGui.endFrame()
    }

    override fun onUpdate(deltaTime: Float) {
        var anyChanges = false
        var anyHeightsChanged = false
        for (manager in editManagers.values) {
            val result = manager.processEdits()
            if (result.hasChanges) anyChanges = true
            if (result.heightsChanged) anyHeightsChanged = true
        }

        if (anyChanges && asyncRegionManager.mergedOpaqueTerrain != null) {
            asyncRegionManager.invalidateMergedTerrain()
        }

        if (anyHeightsChanged) {
            updateObjectHeights()
        }

        updateBrushPreview()

        runEditProbe()
    }

    private fun runEditProbe() {
        if (editProbeStep >= editProbeScript.size) return
        if (isInitialLoading) return
        if (editProbeSettle < EDIT_PROBE_SETTLE_FRAMES) {
            editProbeSettle++
            return
        }

        val step = editProbeScript[editProbeStep++]
        val before = editProbeFingerprints()

        val at = step.substringAfter('@', "")
        val head = step.substringBefore('@')
        val point = at.split(',').mapNotNull { it.trim().toFloatOrNull() }
        val aim = if (point.size == 2) Pair(point[0], point[1]) else null

        editProbeAim = aim
        try {
            when {
                head == "scan" -> {
                    editProbeScan(point.getOrNull(0)?.toInt() ?: 24, point.getOrNull(1)?.toInt() ?: 14)
                    return
                }
                head == "summary" -> {
                    println("[EDITPROBE] summary: unsaved=${hasUnsavedEdits()} | ${unsavedEditSummary()}")
                    return
                }
                head == "navigate" -> {
                    jumpToRegion(currentRegionX + 1, currentRegionY)
                    println(
                        "[EDITPROBE] navigate: modalOpen=${pendingRegionChange != null} " +
                                "label='$pendingRegionChangeLabel' region=($currentRegionX, $currentRegionY)"
                    )
                    return
                }
                head == "discard" -> {
                    println("[EDITPROBE] discard: before=${unsavedEditSummary()}")
                    discardUnsavedEdits()
                    println(
                        "[EDITPROBE] discard: after unsaved=${hasUnsavedEdits()} " +
                                "managers=${editManagers.keys} regions=$terrainEditedRegions"
                    )
                    return
                }
                aim == null -> {
                    println("[EDITPROBE] '$step' has no @px,py")
                    return
                }
                head == "mark" -> {
                    editProbeAnchor = getEditTileUnderCursor()
                    println("[EDITPROBE] mark: anchor=$editProbeAnchor ${editProbeAnchorReport()}")
                    return
                }
                head.startsWith("height") -> {
                    currentEditorTool = EditorTool.HEIGHT_EDIT
                    editorHeightMode = when (head.substringAfter(':', "").trim()) {
                        "lower" -> 1
                        "smooth" -> 2
                        else -> 0
                    }
                    handleHeightEdit()
                }
                head.startsWith("paint") -> {
                    currentEditorTool = EditorTool.TERRAIN_PAINT
                    head.substringAfter(':', "").trim().toIntOrNull()?.let { editorOverlayUnderlayId.set(it) }
                    editedTilesThisDrag.clear()
                    handleTerrainPaint()
                }
                head == "flags" -> {
                    currentEditorTool = EditorTool.FLAG_EDIT
                    val tile = pickTileUnderCursor()
                    if (tile != null) {
                        val current = tileFlags(tile.regionX, tile.regionY, currentPlane, tile.localX, tile.localY) ?: 0
                        applyTileFlags(tile.regionX, tile.regionY, tile.localX, tile.localY, current or 0x1)
                    }
                }
                head.startsWith("place") -> {
                    currentEditorTool = EditorTool.OBJECT_PLACE
                    selectedObjectId = head.substringAfter(':', "").trim().toIntOrNull() ?: selectedObjectId
                    handleObjectPlace()
                }
                else -> println("[EDITPROBE] unknown step '$step'")
            }

            val aimed = pickTileUnderCursor()
            val aimedRegion = aimed?.let { Pair(it.regionX, it.regionY) }
            val changed = editProbeFingerprints().filter { (key, value) -> before[key] != value }.keys
            val height = aimed?.let {
                "h=%.2f".format(tileHeightWorld(it.regionX, it.regionY, currentPlane, it.localX, it.localY))
            } ?: ""
            val verdict = when {
                aimedRegion == null -> "NO TILE UNDER AIM"
                changed.isEmpty() -> "NO DATA CHANGE (object placement or no-op)"
                changed == setOf(aimedRegion) -> "OK"
                else -> "WRONG REGION EDITED"
            }
            println(
                "[EDITPROBE] $step -> aimed=${aimedRegion ?: "miss"} " +
                        "local=(${aimed?.localX}, ${aimed?.localY}) $height " +
                        "${editProbeAnchorReport()} changed=$changed " +
                        "placed=${placedObjects.size} : $verdict"
            )
        } finally {
            editProbeAim = null
        }
    }

    private fun editProbeAnchorReport(): String {
        val a = editProbeAnchor ?: return ""
        val units = tileHeightUnits(a.regionX, a.regionY, currentPlane, a.localX, a.localY)
        return "anchor=%d/%.4f".format(units, units * TerrainLoader.getHeightScale())
    }

    private fun editProbeFingerprints(): Map<Pair<Int, Int>, Int> =
        editManagers.mapValues { (_, manager) ->
            var hash = 1
            val tiles = manager.mutableData.tiles[currentPlane]
            for (x in 0 until 64) {
                for (y in 0 until 64) {
                    val t = tiles[x][y]
                    hash = hash * 31 + t.height
                    hash = hash * 31 + t.overlayId
                    hash = hash * 31 + t.underlayId
                    hash = hash * 31 + t.flags
                }
            }
            hash
        }

    private fun editProbeScan(cols: Int, rows: Int) {
        val counts = mutableMapOf<String, Int>()
        var misses = 0
        for (i in 0 until cols) {
            for (j in 0 until rows) {
                val px = window.width * (i + 0.5f) / cols
                val py = window.height * (j + 0.5f) / rows
                editProbeAim = Pair(px, py)
                val tile = pickTileUnderCursor()
                if (tile == null) {
                    misses++
                } else {
                    val key = "(${tile.regionX}, ${tile.regionY})"
                    counts[key] = (counts[key] ?: 0) + 1
                }
            }
        }
        editProbeAim = null
        val total = cols * rows
        val hits = total - misses
        val centre = counts["($currentRegionX, $currentRegionY)"] ?: 0
        println(
            "[EDITPROBE] scan ${cols}x$rows: $hits/$total points over terrain; " +
                    "centre region ($currentRegionX, $currentRegionY)=$centre, " +
                    "other regions=${hits - centre}; breakdown=$counts"
        )
    }

    private var frameNumber = 0

    override fun onRender() {
        val logFrame = frameNumber < 5
        if (logFrame) {
            println("[RENDER] Frame $frameNumber: onRender() start")
            System.out.flush()
        }

        RenderMetrics.resetFrame()

        gpuTimers.beginFrame()

        glClearColor(clearColor.x, clearColor.y, clearColor.z, 1.0f)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)

        if (gpuSyncProbe) {
            val waitStart = System.nanoTime()
            glFinish()
            gpuSyncWaitMs = (System.nanoTime() - waitStart) / 1_000_000.0
            RenderMetrics.gpuSyncWaitMs = gpuSyncWaitMs
        }

        val renderStart = RenderMetrics.startTiming()
        renderScene()
        RenderMetrics.renderTimeMs = RenderMetrics.endTiming(renderStart)

        if (logFrame) {
            println("[RENDER] Frame $frameNumber: renderScene() done in ${RenderMetrics.renderTimeMs}ms")
            System.out.flush()
        }

        gpuTimers.begin(RenderMetrics.GPU_PASS_UI)
        renderUI()
        gpuTimers.end()

        gpuTimers.endFrame()
        for (pass in RenderMetrics.GPU_PASS_NAMES.indices) {
            RenderMetrics.gpuPassMs[pass] = gpuTimers.passMillis[pass]
        }

        if (logFrame) {
            println("[RENDER] Frame $frameNumber: onRender() complete")
            System.out.flush()
        }
        frameNumber++

        if (metricsEveryFrames > 0 && frameNumber % metricsEveryFrames == 0) {
            print(RenderMetrics.summary(fps))
            System.out.flush()
        }

        if (sweepAngles.isNotEmpty()) stepAngleSweep()
        if (pickProbeEnabled) stepPickProbe()
    }


    private val pickProbePoints: List<FloatArray> = parsePickProbePoints()

    private val pickProbeSweepStep: Int = System.getProperty("darkan.pickProbeSweep")?.toIntOrNull() ?: 0

    private val pickProbeAfterSeconds: Float = System.getProperty("darkan.pickProbeAfter", "30").toFloat()

    private val pickProbeShot: String? = System.getProperty("darkan.pickProbeShot")

    private val pickProbeEnabled: Boolean = pickProbePoints.isNotEmpty() || pickProbeSweepStep > 0

    private var pickProbeElapsedSeconds = 0f
    private var pickProbeDone = false

    private val probeViewProjection = Matrix4f()
    private val probeClipPoint = Vector4f()

    private fun parsePickProbePoints(): List<FloatArray> {
        val spec = System.getProperty("darkan.pickProbe") ?: return emptyList()
        val points = ArrayList<FloatArray>()
        for (entry in spec.split(';')) {
            val parts = entry.trim().split(',')
            if (parts.size != 2) continue
            val x = parts[0].trim().toFloatOrNull() ?: continue
            val y = parts[1].trim().toFloatOrNull() ?: continue
            points.add(floatArrayOf(x, y))
        }
        return points
    }

    private fun stepPickProbe() {
        if (pickProbeDone) return
        pickProbeElapsedSeconds += deltaTime
        if (pickProbeElapsedSeconds < pickProbeAfterSeconds) return
        pickProbeDone = true

        val camera = scene.camera
        println(
            "[PICKPROBE] window=${window.width}x${window.height} region=($currentRegionX, $currentRegionY) " +
                    "plane=$currentPlane camera=(%.1f, %.1f, %.1f) yaw=%.1f pitch=%.1f".format(
                        camera.position.x, camera.position.y, camera.position.z, camera.yaw, camera.pitch
                    )
        )

        if (pickProbeSweepStep > 0) reportPickCoverage()
        for (point in pickProbePoints) reportPickPoint(point[0], point[1])

        val shot = pickProbeShot
        if (shot != null) {
            updateTileHighlight()
            captureBackBuffer(shot)
        }
        System.out.flush()
    }

    private fun reportPickCoverage() {
        var total = 0
        var centre = 0
        var foreign = 0
        val byRegion = HashMap<Pair<Int, Int>, Int>()
        var y = pickProbeSweepStep
        while (y < window.height) {
            var x = pickProbeSweepStep
            while (x < window.width) {
                total++
                val hit = probePick(x.toFloat(), y.toFloat())
                if (hit != null) {
                    val key = Pair(hit.regionX, hit.regionY)
                    byRegion[key] = (byRegion[key] ?: 0) + 1
                    if (hit.regionX == currentRegionX && hit.regionY == currentRegionY) centre++ else foreign++
                }
                x += pickProbeSweepStep
            }
            y += pickProbeSweepStep
        }
        val hits = centre + foreign
        println(
            "[PICKPROBE] coverage step=$pickProbeSweepStep sampled=$total answered=$hits " +
                    "unanswered=${total - hits} centre=$centre foreign=$foreign"
        )
        for ((key, count) in byRegion.entries.sortedByDescending { it.value }) {
            println("[PICKPROBE]   region (${key.first}, ${key.second}): $count")
        }
    }

    private fun reportPickPoint(screenX: Float, screenY: Float) {
        val hit = probePick(screenX, screenY)
        if (hit == null) {
            println("[PICKPROBE] screen=(%.0f, %.0f) MISS".format(screenX, screenY))
            return
        }
        val centre = tileCentreWorld(hit)
        val projected = if (centre != null) projectToScreen(centre) else null
        val error = if (projected != null) {
            val dx = projected[0] - screenX
            val dy = projected[1] - screenY
            sqrt(dx * dx + dy * dy)
        } else Float.NaN
        println(
            "[PICKPROBE] screen=(%.0f, %.0f) -> region=(%d, %d) local=(%d, %d) world=(%.1f, %.1f, %.1f) reproj=(%.1f, %.1f) err=%.1fpx".format(
                screenX, screenY, hit.regionX, hit.regionY, hit.localX, hit.localY,
                hit.worldX, hit.worldY, hit.worldZ,
                projected?.get(0) ?: Float.NaN, projected?.get(1) ?: Float.NaN, error
            )
        )
    }

    private fun probePick(screenX: Float, screenY: Float): TilePicker.SelectedTile? {
        tilePicker.screenToWorldRay(
            screenX, screenY, window.width, window.height, scene.camera, getAspectRatio()
        )
        return tilePicker.pickTile(pickTerrainProvider, currentPlane)
    }

    private fun tileCentreWorld(tile: TilePicker.SelectedTile): Vector3f? {
        val tiles = pickTerrainProvider.tiles(tile.regionX, tile.regionY) ?: return null
        if (tile.plane >= tiles.size) return null
        val nextX = (tile.localX + 1).coerceAtMost(63)
        val nextY = (tile.localY + 1).coerceAtMost(63)
        val sum = tiles[tile.plane][tile.localX][tile.localY].height +
                tiles[tile.plane][nextX][tile.localY].height +
                tiles[tile.plane][tile.localX][nextY].height +
                tiles[tile.plane][nextX][nextY].height
        val tileSize = 512f * CoordinateUtil.DEFAULT_SCALE
        val globalX = tile.regionX * 64 + tile.localX
        val globalY = tile.regionY * 64 + tile.localY
        return Vector3f(
            (globalX + 0.5f) * tileSize,
            sum / 4f * TerrainLoader.getHeightScale(),
            -(globalY + 0.5f) * tileSize
        )
    }

    private fun projectToScreen(world: Vector3f): FloatArray? {
        val camera = scene.camera
        camera.getProjectionMatrix(getAspectRatio()).mul(camera.getViewMatrix(), probeViewProjection)
        probeClipPoint.set(world.x, world.y, world.z, 1f)
        probeViewProjection.transform(probeClipPoint)
        if (probeClipPoint.w <= 0f) return null
        val ndcX = probeClipPoint.x / probeClipPoint.w
        val ndcY = probeClipPoint.y / probeClipPoint.w
        return floatArrayOf(
            (ndcX + 1f) * 0.5f * window.width,
            (1f - ndcY) * 0.5f * window.height
        )
    }


    private class SweepAngle(val yaw: Float, val pitch: Float) {
        val label: String get() = "yaw%+.0f_pitch%+.0f".format(yaw, pitch)
    }

    private val sweepAngles: List<SweepAngle> = parseSweepAngles()

    private val sweepAfterSeconds: Float = System.getProperty("darkan.sweepAfter", "45").toFloat()

    private val sweepWarmupFrames: Int = System.getProperty("darkan.sweepWarmup", "24").toIntOrNull() ?: 24

    private val sweepSampleFrames: Int = System.getProperty("darkan.sweepFrames", "60").toIntOrNull() ?: 60

    private val sweepShotDirectory: String? = System.getProperty("darkan.sweepShots")

    private var sweepElapsedSeconds = 0f
    private var sweepIndex = -1
    private var sweepFramesAtAngle = 0
    private val sweepPassSamples = ArrayList<DoubleArray>()
    private val sweepBatchTriangles = ArrayList<Int>()
    private val sweepBatchDraws = ArrayList<Int>()
    private val sweepBatchRanges = ArrayList<Int>()
    private val sweepRangesConsidered = ArrayList<Int>()
    private val sweepProbeVisibleTriangles = ArrayList<Long>()
    private val sweepProbeTotalTriangles = ArrayList<Long>()
    private val sweepProbeVisibleObjects = ArrayList<Int>()
    private val sweepProbeTotalObjects = ArrayList<Int>()
    private val sweepProbeCpuMs = ArrayList<Double>()
    private val sweepCullCpuMs = ArrayList<Double>()
    private val sweepGpuWaitMs = ArrayList<Double>()
    private val sweepRenderCpuMs = ArrayList<Double>()
    private val sweepSubmitCpuMs = ArrayList<Double>()
    private val sweepPixelTriangles = LongArray(RenderMetrics.PROBE_PIXEL_EDGES.size)
    private val sweepPixelObjects = IntArray(RenderMetrics.PROBE_PIXEL_EDGES.size)
    private val sweepGridDraws = IntArray(8)
    private val sweepGridTriangles = LongArray(8)
    private val sweepGridBatches = IntArray(8)
    private val sweepDistanceTriangles = LongArray(RenderMetrics.PROBE_DISTANCE_EDGES_TILES.size)
    private val sweepSizeTriangles = LongArray(RenderMetrics.PROBE_SIZE_EDGES.size)
    private val sweepSizeObjects = IntArray(RenderMetrics.PROBE_SIZE_EDGES.size)

    private fun parseSweepAngles(): List<SweepAngle> {
        val spec = System.getProperty("darkan.sweep") ?: return emptyList()
        val angles = ArrayList<SweepAngle>()
        for (entry in spec.split(',')) {
            val parts = entry.trim().split(':')
            if (parts.size != 2) continue
            val yaw = parts[0].trim().toFloatOrNull() ?: continue
            val pitch = parts[1].trim().toFloatOrNull() ?: continue
            angles.add(SweepAngle(yaw, pitch))
        }
        return angles
    }

    private fun stepAngleSweep() {
        sweepElapsedSeconds += deltaTime
        if (sweepIndex < 0) {
            if (sweepElapsedSeconds < sweepAfterSeconds) return
            beginSweepAngle(0)
            return
        }

        sweepFramesAtAngle++
        if (sweepFramesAtAngle <= sweepWarmupFrames) return

        sweepPassSamples.add(RenderMetrics.gpuPassMs.copyOf())
        sweepBatchTriangles.add(RenderMetrics.staticBatchTriangles)
        sweepBatchDraws.add(RenderMetrics.staticBatchDrawCalls)
        sweepBatchRanges.add(RenderMetrics.staticBatchRanges)
        sweepRangesConsidered.add(RenderMetrics.batchRangesConsidered)
        sweepProbeVisibleTriangles.add(RenderMetrics.probeTrianglesVisible)
        sweepProbeTotalTriangles.add(RenderMetrics.probeTrianglesTotal)
        sweepProbeVisibleObjects.add(RenderMetrics.probeObjectsVisible)
        sweepProbeTotalObjects.add(RenderMetrics.probeObjectsTotal)
        sweepProbeCpuMs.add(RenderMetrics.probeCpuMs)
        sweepCullCpuMs.add(RenderMetrics.cullCpuTimeMs)
        sweepGpuWaitMs.add(RenderMetrics.gpuSyncWaitMs)
        sweepRenderCpuMs.add(RenderMetrics.renderTimeMs)
        sweepSubmitCpuMs.add(RenderMetrics.cpuPhaseMs[RenderMetrics.CPU_PHASE_STATIC_BATCH])
        for (i in sweepDistanceTriangles.indices) {
            sweepDistanceTriangles[i] = RenderMetrics.probeTrianglesByDistance[i]
        }
        for (i in sweepSizeTriangles.indices) {
            sweepSizeTriangles[i] = RenderMetrics.probeTrianglesBySize[i]
            sweepSizeObjects[i] = RenderMetrics.probeObjectsBySize[i]
        }
        for (i in sweepPixelTriangles.indices) {
            sweepPixelTriangles[i] = RenderMetrics.probeTrianglesByPixels[i]
            sweepPixelObjects[i] = RenderMetrics.probeObjectsByPixels[i]
        }
        for (i in probeGridDraws.indices) {
            sweepGridDraws[i] = probeGridDraws[i]
            sweepGridTriangles[i] = probeGridTriangles[i]
            sweepGridBatches[i] = probeGridBatches[i]
        }

        if (sweepFramesAtAngle < sweepWarmupFrames + sweepSampleFrames) return

        val shotDirectory = sweepShotDirectory
        if (shotDirectory != null) {
            captureBackBuffer("$shotDirectory/${sweepAngles[sweepIndex].label}.png")
        }
        reportSweepAngle()

        if (sweepIndex + 1 < sweepAngles.size) {
            beginSweepAngle(sweepIndex + 1)
        } else {
            println("[SWEEP] done")
            System.out.flush()
            window.requestClose()
        }
    }

    private fun beginSweepAngle(index: Int) {
        sweepIndex = index
        sweepFramesAtAngle = 0
        sweepPassSamples.clear()
        sweepBatchTriangles.clear()
        sweepBatchDraws.clear()
        sweepBatchRanges.clear()
        sweepRangesConsidered.clear()
        sweepProbeVisibleTriangles.clear()
        sweepProbeTotalTriangles.clear()
        sweepProbeVisibleObjects.clear()
        sweepProbeTotalObjects.clear()
        sweepProbeCpuMs.clear()
        sweepCullCpuMs.clear()
        sweepGpuWaitMs.clear()
        sweepRenderCpuMs.clear()
        sweepSubmitCpuMs.clear()
        val angle = sweepAngles[index]
        scene.camera.yaw = angle.yaw
        scene.camera.pitch = angle.pitch
        scene.camera.rotate(0f, 0f)
    }

    private fun reportSweepAngle() {
        val angle = sweepAngles[sweepIndex]
        val passes = StringBuilder()
        for (pass in RenderMetrics.GPU_PASS_NAMES.indices) {
            val samples = DoubleArray(sweepPassSamples.size) { sweepPassSamples[it][pass] }
            passes.append("%s=%.3f ".format(RenderMetrics.GPU_PASS_NAMES[pass], medianOf(samples)))
        }
        val batchTriangles = medianOfInts(sweepBatchTriangles)
        val probeVisible = medianOfLongs(sweepProbeVisibleTriangles)
        val probeTotal = medianOfLongs(sweepProbeTotalTriangles)
        println(
            "[SWEEP] angle=${angle.label} frames=${sweepPassSamples.size} " +
                "gpu: $passes"
        )
        println(
            "[SWEEP] angle=${angle.label} staticBatch: draws=${medianOfInts(sweepBatchDraws)} " +
                "ranges=${medianOfInts(sweepBatchRanges)}/${medianOfInts(sweepRangesConsidered)} " +
                "triangles=$batchTriangles " +
                "cullCpu=%.3fms staticBatchCpu=%.3fms cpuRender=%.3fms gpuWait=%.3fms".format(
                    medianOfDoubles(sweepCullCpuMs), medianOfDoubles(sweepSubmitCpuMs),
                    medianOfDoubles(sweepRenderCpuMs), medianOfDoubles(sweepGpuWaitMs)
                )
        )
        println(
            "[SWEEP] angle=${angle.label} probe: objects=${medianOfInts(sweepProbeVisibleObjects)}" +
                "/${medianOfInts(sweepProbeTotalObjects)} " +
                "triangles=$probeVisible/$probeTotal " +
                "visibleOfSubmitted=%.1f%% ".format(
                    if (batchTriangles > 0) 100.0 * probeVisible / batchTriangles else 0.0
                ) +
                "probeCpu=%.3fms".format(medianOfDoubles(sweepProbeCpuMs))
        )
        val distance = StringBuilder()
        for (i in sweepDistanceTriangles.indices) {
            val edge = RenderMetrics.PROBE_DISTANCE_EDGES_TILES[i]
            val name = if (edge == Int.MAX_VALUE) "inf" else edge.toString()
            distance.append("<${name}t=${sweepDistanceTriangles[i]} ")
        }
        println("[SWEEP] angle=${angle.label} visibleTrianglesByDistance: $distance")
        val size = StringBuilder()
        for (i in sweepSizeTriangles.indices) {
            val edge = RenderMetrics.PROBE_SIZE_EDGES[i]
            val name = if (edge == Int.MAX_VALUE) "inf" else edge.toString()
            size.append("<=${name}tri: objs=${sweepSizeObjects[i]} tris=${sweepSizeTriangles[i]}  ")
        }
        println("[SWEEP] angle=${angle.label} visibleBySize: $size")
        val pixels = StringBuilder()
        for (i in sweepPixelTriangles.indices) {
            val edge = RenderMetrics.PROBE_PIXEL_EDGES[i]
            val name = if (edge == Int.MAX_VALUE) "inf" else edge.toString()
            pixels.append("<${name}px: objs=${sweepPixelObjects[i]} tris=${sweepPixelTriangles[i]}  ")
        }
        println("[SWEEP] angle=${angle.label} visibleByScreenSize: $pixels")
        val gridSizes = readyContextRegions.firstOrNull { it.batchSimulations.isNotEmpty() }
            ?.batchSimulations?.map { it.gridTiles } ?: emptyList()
        for (i in gridSizes.indices) {
            println(
                "[SWEEP] angle=${angle.label} grid${gridSizes[i]}t: batches=${sweepGridBatches[i]} " +
                    "draws=${sweepGridDraws[i]} triangles=${sweepGridTriangles[i]}"
            )
        }
        System.out.flush()
    }

    private fun medianOf(values: DoubleArray): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sortedArray()
        return sorted[sorted.size / 2]
    }

    private fun medianOfDoubles(values: List<Double>): Double =
        if (values.isEmpty()) 0.0 else values.sorted()[values.size / 2]

    private fun medianOfInts(values: List<Int>): Int =
        if (values.isEmpty()) 0 else values.sorted()[values.size / 2]

    private fun medianOfLongs(values: List<Long>): Long =
        if (values.isEmpty()) 0L else values.sorted()[values.size / 2]

    private val metricsEveryFrames = System.getProperty("darkan.metricsEvery", "0").toIntOrNull() ?: 0

    private val gpuSyncProbe: Boolean = System.getProperty("darkan.gpuSync", "false").toBoolean()

    private val cullProbe: Boolean = System.getProperty("darkan.cullProbe", "false").toBoolean()

    private val rangeFuseGapIndices: Int =
        System.getProperty("darkan.rangeFuseGap")?.toIntOrNull() ?: DEFAULT_RANGE_FUSE_GAP

    private val TILE_WORLD_SIZE = 512f * CoordinateUtil.DEFAULT_SCALE

    private var gpuSyncWaitMs = 0.0

    private fun renderScene() {
        val logFrame = frameNumber < 5

        val uniformStart = RenderMetrics.startTiming()
        val viewMatrix = scene.camera.getViewMatrix()
        val projectionMatrix = scene.camera.getProjectionMatrix(getAspectRatio())
        uniformBuffers.updateCamera(viewMatrix, projectionMatrix, scene.camera.position)
        environment.nearClipDistance = scene.camera.nearPlane
        uniformBuffers.updateEnvironment(environment)
        RenderMetrics.endPhase(RenderMetrics.CPU_PHASE_UNIFORMS, uniformStart)

        val cullStart = RenderMetrics.startTiming()
        frustumViewProjection.set(projectionMatrix).mul(viewMatrix)
        frustum.update(frustumViewProjection)
        frustum.writePlanes(frustumPlanes)
        updateVisibleContextRegions()
        updateMergedTerrainVisibility()
        RenderMetrics.cullCpuTimeMs = RenderMetrics.endTiming(cullStart)
        RenderMetrics.endPhase(RenderMetrics.CPU_PHASE_CULL, cullStart)

        val objectSceneStart = RenderMetrics.startTiming()
        updateObjectScene()
        val ir = indirectRenderer
        RenderMetrics.cullActive = ir != null && ir.cullingActive && ir.staticSceneBuilt
        if (RenderMetrics.cullActive) {
            gpuTimers.begin(RenderMetrics.GPU_PASS_CULL)
            ir!!.cullFrame(frustumPlanes)
            gpuTimers.end()
        }
        RenderMetrics.endPhase(RenderMetrics.CPU_PHASE_OBJECT_SCENE, objectSceneStart)

        if (renderSystem.wireframeMode) {
            glPolygonMode(GL_FRONT_AND_BACK, GL_LINE)
        }

        val atlas = terrainAtlas
        val opaqueAtlasMesh = if (showTerrain) asyncRegionManager.mergedOpaqueTerrain else null
        val alphaAtlasMesh = if (showTerrain) asyncRegionManager.mergedAlphaTerrain else null
        val terrainRegions = if (showTerrain) visibleContextRegions else emptyList()
        val atlasShader = terrainAtlasShader
        val atlasAlphaShader = terrainAtlasAlphaShader

        if (atlas != null && atlasShader != null && atlasAlphaShader != null && (opaqueAtlasMesh != null || alphaAtlasMesh != null)) {
            var mergedStart = RenderMetrics.startTiming()
            renderSystem.disableCullFace()
            val identityModel = Matrix4f().identity()
            val mergedRegions =
                if (mergeContextTerrain) asyncRegionManager.mergedTerrainRegions else emptySet()

            gpuTimers.begin(RenderMetrics.GPU_PASS_TERRAIN_OPAQUE)

            atlas.bindPage(0, 0)

            if (opaqueAtlasMesh != null) {
                val rangeCount = if (mergeContextTerrain) gatherMergedTerrainRanges(alpha = false) else 0
                if (!mergeContextTerrain) {
                    atlasShader.bind()
                    atlasShader.setUniform("model", identityModel)
                    atlasShader.setUniform("atlasTexture", 0)
                    opaqueAtlasMesh.render()
                    RenderMetrics.terrainOpaqueDrawCalls++
                    RenderMetrics.trianglesSubmitted += opaqueAtlasMesh.vertexCount / 3
                } else if (rangeCount > 0) {
                    atlasShader.bind()
                    atlasShader.setUniform("model", identityModel)
                    atlasShader.setUniform("atlasTexture", 0)
                    opaqueAtlasMesh.renderRanges(terrainRangeFirst, terrainRangeCounts, rangeCount)
                    RenderMetrics.terrainOpaqueDrawCalls++
                    for (i in 0 until rangeCount) {
                        RenderMetrics.trianglesSubmitted += terrainRangeCounts[i] / 3
                    }
                }
            }

            glActiveTexture(GL_TEXTURE0)
            glBindTexture(GL_TEXTURE_2D, 0)
            RenderMetrics.endPhase(RenderMetrics.CPU_PHASE_TERRAIN_MERGED, mergedStart)

            val regionTerrainStart = RenderMetrics.startTiming()

            for (ctxRegion in terrainRegions) {
                val terrainMeshes = ctxRegion.terrainMeshes ?: continue
                val merged = ctxRegion.key in mergedRegions
                val regionModelMatrix = asyncRegionManager.getRegionModelMatrix(ctxRegion.key)

                if (!(merged && terrainMeshes.colorInAtlas)) {
                    terrainMeshes.colorMesh?.let { mesh ->
                        uboColorShader.bind()
                        uboColorShader.setUniform("model", regionModelMatrix)
                        mesh.render()
                        RenderMetrics.terrainOpaqueDrawCalls++
                        RenderMetrics.trianglesSubmitted += mesh.vertexCount / 3
                    }
                }

                var texturedBound = false
                for (batch in terrainMeshes.texturedBatches) {
                    if (merged && batch.inAtlas) continue
                    if (!texturedBound) {
                        uboTexturedShader.bind()
                        uboTexturedShader.setUniform("textureSampler", 0)
                        uboTexturedShader.setUniform("model", regionModelMatrix)
                        texturedBound = true
                    }
                    terrainTextureBinding(batch.textureId)
                    batch.mesh.render()
                    RenderMetrics.terrainOpaqueDrawCalls++
                    if (merged) RenderMetrics.terrainAtlasOverflowDrawCalls++
                    RenderMetrics.trianglesSubmitted += batch.mesh.vertexCount / 3
                }
                if (texturedBound) TextureLoader.unbindTexture()
            }

            gpuTimers.end()
            RenderMetrics.endPhase(RenderMetrics.CPU_PHASE_TERRAIN_REGIONS, regionTerrainStart)

            mergedStart = RenderMetrics.startTiming()

            val hasContextAlpha = terrainRegions.any { region ->
                val batches = region.terrainMeshes?.alphaBatches ?: return@any false
                if (region.key in mergedRegions) batches.any { !it.inAtlas } else batches.isNotEmpty()
            }
            if (alphaAtlasMesh != null || hasContextAlpha) {
                gpuTimers.begin(RenderMetrics.GPU_PASS_TERRAIN_ALPHA)
                glDepthMask(false)
                glEnable(GL_BLEND)
                glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)

                if (alphaAtlasMesh != null) {
                    val rangeCount = if (mergeContextTerrain) gatherMergedTerrainRanges(alpha = true) else 0
                    if (!mergeContextTerrain) {
                        atlas.bindPage(0, 0)
                        atlasAlphaShader.bind()
                        atlasAlphaShader.setUniform("model", identityModel)
                        atlasAlphaShader.setUniform("atlasTexture", 0)
                        alphaAtlasMesh.render()
                        RenderMetrics.terrainAlphaDrawCalls++
                        RenderMetrics.trianglesSubmitted += alphaAtlasMesh.vertexCount / 3
                        glActiveTexture(GL_TEXTURE0)
                        glBindTexture(GL_TEXTURE_2D, 0)
                    } else if (rangeCount > 0) {
                        atlas.bindPage(0, 0)
                        atlasAlphaShader.bind()
                        atlasAlphaShader.setUniform("model", identityModel)
                        atlasAlphaShader.setUniform("atlasTexture", 0)
                        alphaAtlasMesh.renderRanges(terrainRangeFirst, terrainRangeCounts, rangeCount)
                        RenderMetrics.terrainAlphaDrawCalls++
                        for (i in 0 until rangeCount) {
                            RenderMetrics.trianglesSubmitted += terrainRangeCounts[i] / 3
                        }
                        glActiveTexture(GL_TEXTURE0)
                        glBindTexture(GL_TEXTURE_2D, 0)
                    }
                }

                RenderMetrics.endPhase(RenderMetrics.CPU_PHASE_TERRAIN_MERGED, mergedStart)
                val alphaRegionStart = RenderMetrics.startTiming()
                if (hasContextAlpha) {
                    terrainAlphaShader.bind()
                    terrainAlphaShader.setUniform("textureSampler", 0)
                    var anyDrawn = false
                    for (ctxRegion in terrainRegions) {
                        val terrainMeshes = ctxRegion.terrainMeshes ?: continue
                        if (terrainMeshes.alphaBatches.isEmpty()) continue
                        val merged = ctxRegion.key in mergedRegions
                        var modelSet = false
                        for (batch in terrainMeshes.alphaBatches) {
                            if (merged && batch.inAtlas) continue
                            if (!modelSet) {
                                terrainAlphaShader.setUniform("model", asyncRegionManager.getRegionModelMatrix(ctxRegion.key))
                                modelSet = true
                            }
                            terrainTextureBinding(batch.textureId)
                            batch.mesh.render()
                            RenderMetrics.terrainAlphaDrawCalls++
                            if (merged) RenderMetrics.terrainAtlasOverflowDrawCalls++
                            RenderMetrics.trianglesSubmitted += batch.mesh.vertexCount / 3
                            anyDrawn = true
                        }
                    }
                    if (anyDrawn) TextureLoader.unbindTexture()
                }

                glDisable(GL_BLEND)
                glDepthMask(true)
                gpuTimers.end()
                RenderMetrics.endPhase(RenderMetrics.CPU_PHASE_TERRAIN_REGIONS, alphaRegionStart)
            }

            renderSystem.enableCullFace()
        } else {
            val em = if (showTerrain) centerEditManager else null
            val editedRegions = if (showTerrain) editManagers else emptyMap()
            if (showTerrain) {
                renderSystem.disableCullFace()
                gpuTimers.begin(RenderMetrics.GPU_PASS_TERRAIN_OPAQUE)

                uboColorShader.bind()
                if (em != null) {
                    val centerColor = em.renderColor(uboColorShader, asyncRegionManager.centerModelMatrix)
                    RenderMetrics.terrainOpaqueDrawCalls += centerColor.drawCalls
                    RenderMetrics.trianglesSubmitted += centerColor.triangles
                }
                for (ctxRegion in terrainRegions) {
                    val regionModelMatrix = asyncRegionManager.getRegionModelMatrix(ctxRegion.key)
                    val regionEm = editedRegions[Pair(ctxRegion.key.regionX, ctxRegion.key.regionY)]
                    if (regionEm != null) {
                        val stats = regionEm.renderColor(uboColorShader, regionModelMatrix)
                        RenderMetrics.terrainOpaqueDrawCalls += stats.drawCalls
                        RenderMetrics.trianglesSubmitted += stats.triangles
                        continue
                    }
                    val terrainMeshes = ctxRegion.terrainMeshes ?: continue
                    uboColorShader.setUniform("model", regionModelMatrix)
                    terrainMeshes.colorMesh?.let { mesh ->
                        mesh.render()
                        RenderMetrics.terrainOpaqueDrawCalls++
                        RenderMetrics.trianglesSubmitted += mesh.vertexCount / 3
                    }
                }

                uboTexturedShader.bind()
                uboTexturedShader.setUniform("textureSampler", 0)
                if (em != null) {
                    val centerTextured =
                        em.renderTextured(uboTexturedShader, asyncRegionManager.centerModelMatrix, terrainTextureBinding)
                    RenderMetrics.terrainOpaqueDrawCalls += centerTextured.drawCalls
                    RenderMetrics.trianglesSubmitted += centerTextured.triangles
                }
                for (ctxRegion in terrainRegions) {
                    val regionModelMatrix = asyncRegionManager.getRegionModelMatrix(ctxRegion.key)
                    val regionEm = editedRegions[Pair(ctxRegion.key.regionX, ctxRegion.key.regionY)]
                    if (regionEm != null) {
                        val stats = regionEm.renderTextured(uboTexturedShader, regionModelMatrix, terrainTextureBinding)
                        RenderMetrics.terrainOpaqueDrawCalls += stats.drawCalls
                        RenderMetrics.trianglesSubmitted += stats.triangles
                        continue
                    }
                    val terrainMeshes = ctxRegion.terrainMeshes ?: continue
                    uboTexturedShader.setUniform("model", regionModelMatrix)
                    for (batch in terrainMeshes.texturedBatches) {
                        terrainTextureBinding(batch.textureId)
                        batch.mesh.render()
                        RenderMetrics.terrainOpaqueDrawCalls++
                        RenderMetrics.trianglesSubmitted += batch.mesh.vertexCount / 3
                    }
                }
                TextureLoader.unbindTexture()
                gpuTimers.end()

                gpuTimers.begin(RenderMetrics.GPU_PASS_TERRAIN_ALPHA)
                glDepthMask(false)
                glEnable(GL_BLEND)
                glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)

                terrainAlphaShader.bind()
                terrainAlphaShader.setUniform("textureSampler", 0)
                if (em != null) {
                    val centerAlpha =
                        em.renderAlpha(terrainAlphaShader, asyncRegionManager.centerModelMatrix, terrainTextureBinding)
                    RenderMetrics.terrainAlphaDrawCalls += centerAlpha.drawCalls
                    RenderMetrics.trianglesSubmitted += centerAlpha.triangles
                }
                for (ctxRegion in terrainRegions) {
                    val regionModelMatrix = asyncRegionManager.getRegionModelMatrix(ctxRegion.key)
                    val regionEm = editedRegions[Pair(ctxRegion.key.regionX, ctxRegion.key.regionY)]
                    if (regionEm != null) {
                        val stats = regionEm.renderAlpha(terrainAlphaShader, regionModelMatrix, terrainTextureBinding)
                        RenderMetrics.terrainAlphaDrawCalls += stats.drawCalls
                        RenderMetrics.trianglesSubmitted += stats.triangles
                        continue
                    }
                    val terrainMeshes = ctxRegion.terrainMeshes ?: continue
                    terrainAlphaShader.setUniform("model", regionModelMatrix)
                    for (batch in terrainMeshes.alphaBatches) {
                        terrainTextureBinding(batch.textureId)
                        batch.mesh.render()
                        RenderMetrics.terrainAlphaDrawCalls++
                        RenderMetrics.trianglesSubmitted += batch.mesh.vertexCount / 3
                    }
                }

                TextureLoader.unbindTexture()
                glDisable(GL_BLEND)
                glDepthMask(true)
                gpuTimers.end()

                renderSystem.enableCullFace()
            }
        }

        val overlayStart = RenderMetrics.startTiming()
        renderTileHighlight()

        renderBrushPreview()
        RenderMetrics.endPhase(RenderMetrics.CPU_PHASE_OVERLAYS, overlayStart)

        if (logFrame) {
            println("[RENDER] Frame $frameNumber:   terrain done, starting renderObjects()")
            System.out.flush()
        }

        val objectsStart = RenderMetrics.startTiming()
        renderObjects()
        RenderMetrics.endPhase(RenderMetrics.CPU_PHASE_OBJECTS, objectsStart)
        RenderMetrics.cpuPhaseMs[RenderMetrics.CPU_PHASE_OBJECTS] -=
            RenderMetrics.cpuPhaseMs[RenderMetrics.CPU_PHASE_STATIC_BATCH]

        if (logFrame) {
            println("[RENDER] Frame $frameNumber:   renderObjects() done")
            System.out.flush()
        }

        val skyRenderer = sky
        if (skyRenderer != null && !renderSystem.wireframeMode) {
            val skyStart = RenderMetrics.startTiming()
            gpuTimers.begin(RenderMetrics.GPU_PASS_SKY)
            skyRenderer.render(scene.camera, window.width, window.height)
            gpuTimers.end()
            RenderMetrics.skyTimeMs = RenderMetrics.endTiming(skyStart)
            RenderMetrics.endPhase(RenderMetrics.CPU_PHASE_SKY, skyStart)
            RenderMetrics.skyDrawCalls = 1
        } else {
            RenderMetrics.skyTimeMs = 0.0
            RenderMetrics.skyDrawCalls = 0
        }

        if (renderSystem.wireframeMode) {
            glPolygonMode(GL_FRONT_AND_BACK, GL_FILL)
        }
    }

    private fun renderTileHighlight() {
        val highlightMesh = tileHighlightMesh
        val originMesh = moveOriginMesh

        if (highlightMesh == null && originMesh == null) return

        simpleShader.bind()
        simpleShader.setUniform("model", Matrix4f().identity())
        simpleShader.setUniform("view", scene.camera.getViewMatrix())
        simpleShader.setUniform("projection", scene.camera.getProjectionMatrix(getAspectRatio()))

        glDisable(GL_DEPTH_TEST)

        glLineWidth(3f)

        highlightMesh?.renderLines()

        originMesh?.renderLines()

        glLineWidth(1f)
        glEnable(GL_DEPTH_TEST)
    }

    private data class InstanceEntry(val modelId: Int, val matrixData: FloatArray)

    private val PARALLEL_CHUNK_SIZE = 512

    private val DROPPED_MODEL_REPORT_LIMIT = 16

    private fun computeInstanceEntries(): List<InstanceEntry> {
        val allObjects = asyncRegionManager.centerObjects + placedObjects
        if (allObjects.size <= PARALLEL_CHUNK_SIZE) {
            val tempMatrix = Matrix4f()
            return allObjects.map { obj -> computeInstanceEntry(obj, tempMatrix) }
        }
        return runBlocking(Dispatchers.Default) {
            allObjects.chunked(PARALLEL_CHUNK_SIZE).map { chunk ->
                async {
                    val tempMatrix = Matrix4f()
                    chunk.map { obj -> computeInstanceEntry(obj, tempMatrix) }
                }
            }.awaitAll().flatten()
        }
    }

    private fun updateObjectScene() {
        val ir = indirectRenderer ?: return
        if (!useStaticInstances || !ir.isReady()) return

        ir.resetFrameCounters()

        if (objectSceneDirty) {
            val start = RenderMetrics.startTiming()
            val entries = computeInstanceEntries()
            ingestUnregisteredModels(ir, entries)
            ir.beginStaticScene()
            for (entry in entries) ir.addStaticInstance(entry.modelId, entry.matrixData)
            ir.buildStaticScene()
            objectSceneDirty = false
            RenderMetrics.transformTimeMs = RenderMetrics.endTiming(start)
            println(
                "[MapViewer] object scene rebuilt in ${"%.1f".format(RenderMetrics.transformTimeMs)} ms: " +
                        "${ir.getTotalInstances()} instances, ${ir.staticItemCount} draw items"
            )
            reportDroppedInstances(ir, entries.size)
        }

        RenderMetrics.droppedInstances = ir.droppedInstanceCount
        RenderMetrics.droppedModelIds = ir.droppedModelIdCount
        RenderMetrics.instanceCount = ir.getTotalInstances()
        RenderMetrics.cullItemsTotal = ir.staticItemCount
        RenderMetrics.cullVisibleItemsStale = ir.visibleInstancesStale
        RenderMetrics.cullVisibleTrianglesStale = ir.visibleTrianglesStale
        RenderMetrics.cullStatsAgeFrames = ir.statsAgeFrames
    }

    private fun reportDroppedInstances(ir: IndirectRenderer, submitted: Int) {
        val dropped = ir.droppedInstanceCount
        if (dropped == 0) return

        val meshData = asyncRegionManager.centerModelMeshData
        val unavailable = asyncRegionManager.unavailableModels
        val droppedIds = ir.droppedModelIdList()
        val unregistered = droppedIds.filter { it !in unavailable }

        RenderMetrics.droppedModelsUnavailable = droppedIds.size - unregistered.size

        System.err.println(
            "[MapViewer] $dropped of $submitted object instances were dropped across " +
                    "${droppedIds.size} model(s): ${RenderMetrics.droppedModelsUnavailable} carry no " +
                    "geometry in the cache (data gap, not a renderer fault), " +
                    "${unregistered.size} unregistered"
        )
        for (modelId in droppedIds.take(DROPPED_MODEL_REPORT_LIMIT)) {
            val meshes = meshData[modelId]
            val where = when {
                modelId in unavailable -> "the cache holds no geometry under this id — " +
                        "run 'tools faces $modelId' for the reason; nothing can draw it"
                meshes == null -> "absent from centerModelMeshData: never registered"
                meshes.isEmpty() -> "registered with no meshes"
                else -> "has ${meshes.size} mesh(es) but reached the renderer unregistered"
            }
            System.err.println("[MapViewer]   model $modelId: $where")
        }
    }

    private fun renderObjects() {
        if (!showObjects) return
        val im = asyncRegionManager.instanceManager ?: return
        val logFrame = frameNumber < 5

        val ir = indirectRenderer
        val useIndirect = ir != null && ir.isReady()
        val staticMode = useIndirect && ir.staticSceneBuilt

        if (!staticMode) {
            val transformStart = RenderMetrics.startTiming()
            val entries = computeInstanceEntries()
            RenderMetrics.transformTimeMs = RenderMetrics.endTiming(transformStart)

            if (logFrame) {
                println("[RENDER] Frame $frameNumber:     transforms done in ${RenderMetrics.transformTimeMs}ms for ${entries.size} objects")
                System.out.flush()
            }

            val uploadStart = RenderMetrics.startTiming()
            var addedCount = 0
            if (useIndirect) {
                ingestUnregisteredModels(ir, entries)
                ir.prepareFrame()
                for (entry in entries) {
                    if (ir.addInstanceRaw(entry.modelId, entry.matrixData)) addedCount++
                }
                ir.uploadAll()
                RenderMetrics.droppedInstances = ir.droppedInstanceCount
                RenderMetrics.droppedModelIds = ir.droppedModelIdCount
                if (ir.droppedInstanceCount > 0 && frameNumber < 5) {
                    reportDroppedInstances(ir, entries.size)
                }
            } else {
                im.prepareFrame()
                for (entry in entries) {
                    if (im.addInstanceRaw(entry.modelId, entry.matrixData)) addedCount++
                }
                im.uploadAll()
            }
            RenderMetrics.uploadTimeMs = RenderMetrics.endTiming(uploadStart)
            RenderMetrics.instanceCount = addedCount

            if (logFrame) {
                println("[RENDER] Frame $frameNumber:     upload done in ${RenderMetrics.uploadTimeMs}ms, $addedCount instances, indirect=$useIndirect")
                System.out.flush()
            }
        }

        renderSystem.enableModelCullFace()

        renderSystem.enablePolygonOffset(-1.0f, -1.0f)

        if (useIndirect) {
            val indColorShader = indirectColorShader!!
            val indTexShader = indirectTexturedShader!!

            gpuTimers.begin(RenderMetrics.GPU_PASS_OBJECTS)

            ir.cullBarrier()

            ir.renderUntextured(indColorShader)

            renderSystem.enableBlending()

            indTexShader.bind()
            ir.renderTextured(indTexShader)

            renderSystem.disableBlending()
            gpuTimers.end()

            if (!staticMode) ir.endFrame()

            RenderMetrics.indirectDrawCalls = ir.getDrawCallCount()
            RenderMetrics.indirectCommands = ir.getCommandCount()
            RenderMetrics.trianglesSubmitted += ir.getTotalTrianglesSubmitted()
        } else {
            gpuTimers.begin(RenderMetrics.GPU_PASS_OBJECTS)

            im.renderUntextured(instancedColorShader)

            renderSystem.enableBlending()

            instancedTexturedShader.bind()
            instancedTexturedShader.setUniform("textureSampler", 0)

            if (im.objectAtlas != null) {
                im.renderTexturedWithAtlas(instancedTexturedShader) { materialId ->
                    val glTexId = TextureLoader.getTextureForMaterial(cache, materialId)
                    TextureLoader.bindTexture(glTexId)
                }
            } else {
                im.renderTextured(instancedTexturedShader) { materialId ->
                    val glTexId = TextureLoader.getTextureForMaterial(cache, materialId)
                    TextureLoader.bindTexture(glTexId)
                }
            }

            TextureLoader.unbindTexture()
            renderSystem.disableBlending()
            gpuTimers.end()

            val instanceStats = im.getStats()
            RenderMetrics.instancedDrawCalls = instanceStats.drawCalls
            RenderMetrics.trianglesSubmitted += countInstancedTriangles(im)
        }

        if (logFrame) {
            println("[RENDER] Frame $frameNumber:     instanced rendering done")
            System.out.flush()
        }

        val staticBatchStart = RenderMetrics.startTiming()
        gpuTimers.begin(RenderMetrics.GPU_PASS_STATIC_BATCH)
        renderStaticBatchedObjects()
        gpuTimers.end()
        RenderMetrics.endPhase(RenderMetrics.CPU_PHASE_STATIC_BATCH, staticBatchStart)

        if (cullProbe) runCullProbe()

        renderSystem.disablePolygonOffset()

        if (logFrame) {
            println("[RENDER] Frame $frameNumber:     static batched objects done")
            System.out.flush()
        }

        renderSystem.resetFrontFace()
        renderSystem.enableCullFace()
    }

    private val visibleBatchDraws = ArrayList<BatchDraw>(2048)

    private val staticBatchModel = Matrix4f().identity()

    private fun renderStaticBatchedObjects() {
        val atlas = objectAtlas
        var totalMeshes = 0
        var considered = 0
        var culled = 0

        val cullStart = RenderMetrics.startTiming()

        val visible = visibleBatchDraws
        visible.clear()
        val cullBatches = useCpuBatchCulling
        var regionsConsidered = 0
        var regionsCulled = 0
        for (ctxRegion in readyContextRegions) {
            val batches = ctxRegion.batches
            val draws = batches.draws
            if (draws.isEmpty()) continue
            totalMeshes += draws.size
            regionsConsidered++

            if (cullBatches) {
                val regionBounds = batches.union
                if (regionBounds != null && !frustum.isAABBVisible(regionBounds)) {
                    considered += draws.size
                    culled += draws.size
                    regionsCulled++
                    continue
                }
            }

            val boundsData = batches.bounds
            val cullable = batches.cullable
            for (i in draws.indices) {
                considered++
                if (cullBatches && cullable[i] && !frustum.isAABBVisible(boundsData, i * 6)) {
                    culled++
                    continue
                }
                visible.add(draws[i])
            }
        }

        RenderMetrics.staticBatchMeshCount = totalMeshes
        RenderMetrics.batchesConsidered = considered
        RenderMetrics.batchesCulled = culled
        RenderMetrics.batchRegionsConsidered = regionsConsidered
        RenderMetrics.batchRegionsCulled = regionsCulled
        RenderMetrics.cullCpuTimeMs += RenderMetrics.endTiming(cullStart)

        if (visible.isEmpty()) return

        val identityModel = staticBatchModel
        var colorShaderBound = false
        var texturedShaderBound = false
        var blending = false
        var boundAtlasPage = -1
        var boundTextureId = -1

        for (i in visible.indices) {
            val draw = visible[i]
            if (draw.key == BatchKey.UNTEXTURED) {
                if (blending) {
                    renderSystem.disableBlending()
                    blending = false
                }
                if (!colorShaderBound) {
                    staticColorShader.bind()
                    staticColorShader.setUniform("model", identityModel)
                    colorShaderBound = true
                    texturedShaderBound = false
                }
                submitBatch(draw)
            } else {
                if (!blending) {
                    renderSystem.enableBlending()
                    blending = true
                }
                if (!texturedShaderBound) {
                    staticTexturedShader.bind()
                    staticTexturedShader.setUniform("model", identityModel)
                    staticTexturedShader.setUniform("textureSampler", 0)
                    texturedShaderBound = true
                    colorShaderBound = false
                }
                if (draw.key.atlasPageIndex >= 0 && atlas != null) {
                    if (draw.key.atlasPageIndex != boundAtlasPage) {
                        atlas.bindPage(draw.key.atlasPageIndex, 0)
                        boundAtlasPage = draw.key.atlasPageIndex
                        boundTextureId = -1
                    }
                } else if (draw.key.textureId >= 0) {
                    val glTextureId = TextureLoader.getTextureForMaterial(cache, draw.key.textureId)
                    if (glTextureId != boundTextureId) {
                        TextureLoader.bindTexture(glTextureId)
                        boundTextureId = glTextureId
                        boundAtlasPage = -1
                    }
                }
                submitBatch(draw)
            }
        }

        if (boundAtlasPage >= 0 || boundTextureId >= 0) TextureLoader.unbindTexture()
        if (blending) renderSystem.disableBlending()
    }

    private fun submitBatch(draw: BatchDraw) {
        val mesh = draw.mesh
        if (!useCpuBatchCulling || !draw.rangesCullable) {
            mesh.render()
            RenderMetrics.staticBatchDrawCalls++
            RenderMetrics.staticBatchTriangles += mesh.vertexCount / 3
            RenderMetrics.trianglesSubmitted += mesh.vertexCount / 3
            return
        }

        val bounds = draw.rangeBounds
        val first = draw.rangeFirst
        val counts = draw.rangeCount
        ensureRangeCapacity(first.size)
        val outFirst = rangeSubmitFirst
        val outCount = rangeSubmitCount
        var kept = 0
        var indices = 0
        for (i in first.indices) {
            RenderMetrics.batchRangesConsidered++
            if (!frustum.isAABBVisible(bounds, i * 6)) continue
            val previousEnd = if (kept > 0) outFirst[kept - 1] + outCount[kept - 1] else -1
            val gap = if (kept > 0) first[i] - previousEnd else Int.MAX_VALUE
            if (kept > 0 && gap <= rangeFuseGapIndices) {
                outCount[kept - 1] = first[i] + counts[i] - outFirst[kept - 1]
                indices += counts[i] + gap
            } else {
                outFirst[kept] = first[i]
                outCount[kept] = counts[i]
                kept++
                indices += counts[i]
            }
        }

        RenderMetrics.batchRangesCulled += first.size - kept
        if (kept == 0) return

        if (kept == 1) {
            if (outFirst[0] == 0 && outCount[0] == mesh.vertexCount) mesh.render()
            else mesh.renderRange(outFirst[0], outCount[0])
        } else {
            mesh.renderRanges(outFirst, outCount, kept)
        }
        RenderMetrics.staticBatchDrawCalls++
        RenderMetrics.staticBatchRanges += kept
        RenderMetrics.staticBatchTriangles += indices / 3
        RenderMetrics.trianglesSubmitted += indices / 3
    }

    private var rangeSubmitFirst = IntArray(64)
    private var rangeSubmitCount = IntArray(64)

    private fun ensureRangeCapacity(size: Int) {
        if (rangeSubmitFirst.size >= size) return
        rangeSubmitFirst = IntArray(size)
        rangeSubmitCount = IntArray(size)
    }

    private fun runCullProbe() {
        val probeStart = RenderMetrics.startTiming()
        val camera = scene.camera.position
        val distanceEdges = RenderMetrics.PROBE_DISTANCE_EDGES_TILES
        val sizeEdges = RenderMetrics.PROBE_SIZE_EDGES
        val pixelEdges = RenderMetrics.PROBE_PIXEL_EDGES
        val pixelsPerRadian = window.height /
            tan(Math.toRadians(scene.camera.fov.toDouble() * 0.5).toFloat())

        var objectsTotal = 0
        var objectsVisible = 0
        var trianglesTotal = 0L
        var trianglesVisible = 0L

        for (ctxRegion in readyContextRegions) {
            val geometry = ctxRegion.objectGeometry ?: continue
            val bounds = geometry.bounds
            val triangles = geometry.triangles
            for (i in triangles.indices) {
                val triangleCount = triangles[i]
                if (triangleCount == 0) continue
                objectsTotal++
                trianglesTotal += triangleCount

                val offset = i * 6
                if (!frustum.isAABBVisible(bounds, offset)) continue
                objectsVisible++
                trianglesVisible += triangleCount

                val dx = (bounds[offset] + bounds[offset + 3]) * 0.5f - camera.x
                val dy = (bounds[offset + 1] + bounds[offset + 4]) * 0.5f - camera.y
                val dz = (bounds[offset + 2] + bounds[offset + 5]) * 0.5f - camera.z
                val distance = sqrt(dx * dx + dy * dy + dz * dz)

                val radius = 0.5f * sqrt(
                    (bounds[offset + 3] - bounds[offset]) * (bounds[offset + 3] - bounds[offset]) +
                        (bounds[offset + 4] - bounds[offset + 1]) * (bounds[offset + 4] - bounds[offset + 1]) +
                        (bounds[offset + 5] - bounds[offset + 2]) * (bounds[offset + 5] - bounds[offset + 2])
                )
                val pixels = (radius * pixelsPerRadian / distance.coerceAtLeast(1f)).toInt()
                var pixelBucket = 0
                while (pixelBucket < pixelEdges.size - 1 && pixels >= pixelEdges[pixelBucket]) pixelBucket++
                RenderMetrics.probeTrianglesByPixels[pixelBucket] += triangleCount
                RenderMetrics.probeObjectsByPixels[pixelBucket]++

                val tiles = (distance / TILE_WORLD_SIZE).toInt()
                var bucket = 0
                while (bucket < distanceEdges.size - 1 && tiles >= distanceEdges[bucket]) bucket++
                RenderMetrics.probeTrianglesByDistance[bucket] += triangleCount
                RenderMetrics.probeObjectsByDistance[bucket]++

                var sizeBucket = 0
                while (sizeBucket < sizeEdges.size - 1 && triangleCount > sizeEdges[sizeBucket]) sizeBucket++
                RenderMetrics.probeObjectsBySize[sizeBucket]++
                RenderMetrics.probeTrianglesBySize[sizeBucket] += triangleCount
            }
        }

        RenderMetrics.probeObjectsTotal = objectsTotal
        RenderMetrics.probeObjectsVisible = objectsVisible
        RenderMetrics.probeTrianglesTotal = trianglesTotal
        RenderMetrics.probeTrianglesVisible = trianglesVisible

        probeGridDraws.fill(0)
        probeGridTriangles.fill(0L)
        probeGridBatches.fill(0)
        for (ctxRegion in readyContextRegions) {
            val simulations = ctxRegion.batchSimulations
            for (s in simulations.indices) {
                val simulation = simulations[s]
                val bounds = simulation.bounds
                val triangles = simulation.triangles
                probeGridBatches[s] += triangles.size
                for (i in triangles.indices) {
                    if (!frustum.isAABBVisible(bounds, i * 6)) continue
                    probeGridDraws[s]++
                    probeGridTriangles[s] += triangles[i]
                }
            }
        }

        RenderMetrics.probeCpuMs = RenderMetrics.endTiming(probeStart)
    }

    private val probeGridDraws = IntArray(8)
    private val probeGridTriangles = LongArray(8)
    private val probeGridBatches = IntArray(8)

    private fun updateVisibleContextRegions() {
        readyContextRegions.clear()
        visibleContextRegions.clear()
        readyContextRegions.addAll(asyncRegionManager.getReadyContextRegions())
        RenderMetrics.contextRegionsConsidered = readyContextRegions.size

        if (!useCpuBatchCulling) {
            visibleContextRegions.addAll(readyContextRegions)
            RenderMetrics.contextRegionsCulled = 0
            return
        }

        for (region in readyContextRegions) {
            if (frustum.isAABBVisible(asyncRegionManager.getRegionBounds(region.key))) {
                visibleContextRegions.add(region)
            }
        }
        RenderMetrics.contextRegionsCulled = readyContextRegions.size - visibleContextRegions.size
    }

    private var terrainRangeSnapshot: List<MergedTerrainRange> = emptyList()
    private var terrainRangeVisible = BooleanArray(0)
    private var terrainRangeFirst = IntArray(0)
    private var terrainRangeCounts = IntArray(0)

    private fun updateMergedTerrainVisibility() {
        val ranges = asyncRegionManager.mergedTerrainRanges
        terrainRangeSnapshot = ranges
        if (terrainRangeVisible.size < ranges.size) {
            terrainRangeVisible = BooleanArray(ranges.size)
            terrainRangeFirst = IntArray(ranges.size)
            terrainRangeCounts = IntArray(ranges.size)
        }
        var visible = 0
        for (i in ranges.indices) {
            val onScreen = !useCpuBatchCulling || frustum.isAABBVisible(ranges[i].bounds)
            terrainRangeVisible[i] = onScreen
            if (onScreen) visible++
        }
        RenderMetrics.terrainMergedRegionsTotal = ranges.size
        RenderMetrics.terrainMergedRegionsDrawn = visible
    }

    private fun gatherMergedTerrainRanges(alpha: Boolean): Int {
        val ranges = terrainRangeSnapshot
        var count = 0
        for (i in ranges.indices) {
            if (!terrainRangeVisible[i]) continue
            val range = ranges[i]
            val indexCount = if (alpha) range.alphaIndexCount else range.opaqueIndexCount
            if (indexCount == 0) continue
            terrainRangeFirst[count] = if (alpha) range.alphaFirstIndex else range.opaqueFirstIndex
            terrainRangeCounts[count] = indexCount
            count++
        }
        return count
    }

    private fun countInstancedTriangles(im: InstanceManager): Int {
        return im.getTotalTrianglesSubmitted()
    }

    private fun computeInstanceEntry(
        obj: ObjectInstance,
        tempMatrix: Matrix4f
    ): InstanceEntry {
        val combinedModelId = asyncRegionManager.getModelIdForInstance(obj)

        tempMatrix.identity()
            .translate(obj.worldPosition)

        if (obj.terrainAlignment.needsAlignment()) {
            tempMatrix.translate(0f, obj.terrainAlignment.heightAdjustment, 0f)
        }

        tempMatrix
            .translate(obj.offset)
            .scale(obj.scale)

        if (obj.terrainAlignment.needsAlignment()) {
            tempMatrix.rotateZ(obj.terrainAlignment.tiltZ)
            tempMatrix.rotateX(obj.terrainAlignment.tiltX)
        }

        tempMatrix.rotateY(obj.rotation)

        if (obj.mirrored) {
            tempMatrix.scale(1f, 1f, -1f)
        }

        val matrixData = FloatArray(16)
        tempMatrix.get(matrixData)
        return InstanceEntry(combinedModelId, matrixData)
    }


    private fun renderUI() {
        imGui.beginFrame()

        debugPanel.fps = fps
        debugPanel.deltaTime = deltaTime
        debugPanel.wireframeMode = renderSystem.wireframeMode
        debugPanel.render(scene.camera, window.width, window.height) {
            renderSystem.toggleWireframe()
        }

        if (showEditorPanel) {
            renderMapEditorPanel()
        }

        renderUnsavedEditsModal()

        imGui.endFrame()
    }

    private fun renderMapEditorPanel() {
        val maxHeight = window.height * 0.8f
        ImGui.setNextWindowSizeConstraints(320f, 200f, 400f, maxHeight)

        if (editorPanelFirstFrame) {
            ImGui.setNextWindowPos(window.width - 340f, 10f)
            ImGui.setNextWindowSize(340f, 600f)
            editorPanelFirstFrame = false
        }

        val flags = ImGuiWindowFlags.None

        if (ImGui.begin("Map Editor (F5)", flags)) {
            clampWindowToScreen()

            renderUnsavedEditsBanner()

            renderEditScopeNotice()

            renderNavigationSection()

            ImGui.separator()

            renderViewTogglesSection()

            ImGui.separator()

            renderToolPalette()

            ImGui.spacing()

            renderCurrentToolContent()

            ImGui.separator()

            renderEnvironmentSection()

            ImGui.separator()

            renderRenderingSettingsSection()

            ImGui.separator()

            ImGuiText.wrapped("WASD move, Space/Shift up/down, Right-click look, Arrows navigate, Ctrl fast, R roofs")
        }
        ImGui.end()
    }

    private fun renderEditScopeNotice() {
        val notice = editRejectNotice
        if (notice != null) {
            if (System.nanoTime() > editRejectNoticeExpiry) {
                editRejectNotice = null
            } else {
                ImGuiText.colouredWrapped(1f, 0.7f, 0.3f, 1f, notice)
                ImGui.separator()
                return
            }
        }

        val hovered = hoveredEditRegion ?: return
        val live = if (Pair(hovered.first, hovered.second) in editManagers) " (open for editing)" else ""
        ImGuiText.disabledWrapped(
            "Brush is over region (${hovered.first}, ${hovered.second})$live, not the centre region " +
                    "($currentRegionX, $currentRegionY). Edits land in the region under the cursor."
        )
        ImGui.separator()
    }

    private fun renderUnsavedEditsBanner() {
        if (!hasUnsavedEdits()) return

        ImGuiText.coloured(1f, 0.45f, 0.2f, 1f, "UNSAVED EDITS - CANNOT BE SAVED")
        ImGuiText.colouredWrapped(0.9f, 0.9f, 0.9f, 1f, unsavedEditSummary())
        ImGuiText.disabledWrapped(
            "The editor has no cache write-back for terrain or objects yet. " +
                    "Changing region, changing plane, reloading or closing the viewer discards them."
        )
        ImGui.separator()
    }

    private fun renderUnsavedEditsModal() {
        val pending = pendingRegionChange ?: return

        if (!unsavedEditsPopupOpen) {
            ImGui.openPopup(UNSAVED_EDITS_POPUP)
            unsavedEditsPopupOpen = true
        }
        ImGui.setNextWindowPos(
            window.width * 0.5f, window.height * 0.5f,
            ImGuiCond.Appearing, 0.5f, 0.5f
        )

        if (!ImGui.beginPopupModal(UNSAVED_EDITS_POPUP, ImGuiWindowFlags.AlwaysAutoResize)) {
            cancelPendingRegionChange()
            return
        }

        ImGui.pushTextWrapPos(ImGui.getCursorPosX() + 420f)

        ImGuiText.coloured(1f, 0.45f, 0.2f, 1f, "These edits will be lost")
        ImGui.spacing()
        ImGui.text(pendingRegionChangeLabel + " would discard:")
        ImGuiText.coloured(0.9f, 0.9f, 0.9f, 1f, "  " + unsavedEditSummary())
        ImGui.spacing()
        ImGuiText.wrapped(
            "There is no way to save them. The map editor can modify terrain and objects " +
                    "in memory but has no cache write-back path, so this work cannot be " +
                    "written anywhere and discarding it is permanent."
        )

        ImGui.popTextWrapPos()
        ImGui.spacing()
        ImGui.separator()
        ImGui.spacing()

        if (ImGui.button("Discard edits and continue", 220f, 0f)) {
            discardUnsavedEdits()
            pendingRegionChange = null
            pendingRegionChangeLabel = ""
            unsavedEditsPopupOpen = false
            ImGui.closeCurrentPopup()
            pending()
        }
        ImGui.sameLine()
        if (ImGui.button("Keep editing", 140f, 0f)) {
            cancelPendingRegionChange()
            ImGui.closeCurrentPopup()
        }

        ImGui.endPopup()
    }

    private fun cancelPendingRegionChange() {
        pendingRegionChange = null
        pendingRegionChangeLabel = ""
        unsavedEditsPopupOpen = false
        regionXInput.set(currentRegionX)
        regionYInput.set(currentRegionY)
    }

    private fun renderNavigationSection() {
        ImGui.text("Region: (%d, %d)".format(currentRegionX, currentRegionY))
        ImGui.sameLine()
        ImGui.text("  Plane:")
        for (plane in 0..3) {
            ImGui.sameLine()
            if (ImGui.radioButton("$plane##plane", currentPlane == plane)) {
                setPlane(plane)
            }
        }

        ImGui.setNextItemWidth(80f)
        ImGui.inputInt("X##region", regionXInput)
        ImGui.sameLine()
        ImGui.setNextItemWidth(80f)
        ImGui.inputInt("Y##region", regionYInput)
        ImGui.sameLine()
        if (ImGui.button("Go", 40f, 0f)) {
            jumpToRegion(regionXInput.get().coerceIn(0, 255), regionYInput.get().coerceIn(0, 255))
        }

        if (ImGui.collapsingHeader("Quick Locations")) {
            var col = 0
            for ((name, coords) in knownRegions) {
                if (col > 0) ImGui.sameLine()
                if (ImGui.button(name, 130f, 0f)) {
                    regionXInput.set(coords.first)
                    regionYInput.set(coords.second)
                    jumpToRegion(coords.first, coords.second)
                }
                col = (col + 1) % 2
            }
        }
    }

    private fun renderViewTogglesSection() {
        if (ImGui.checkbox("Terrain (T)", showTerrain)) {
            showTerrain = !showTerrain
        }

        ImGui.sameLine()

        val roofsEnabled = ImBoolean(showRoofs)
        if (ImGui.checkbox("Roofs (R)", roofsEnabled)) {
            setShowRoofs(roofsEnabled.get())
        }
        if (ImGui.isItemHovered()) {
            ImGuiText.tooltip("Toggle visibility of roof shapes (12-21)\nHide roofs to see inside buildings")
        }

        val groundDecEnabled = ImBoolean(ObjectRenderer.groundDecorationEnabled)
        if (ImGui.checkbox("Ground Decorations", groundDecEnabled)) {
            val enabled = groundDecEnabled.get()
            requestSettingChange("Toggle ground decorations") {
                ObjectRenderer.groundDecorationEnabled = enabled
            }
        }
        if (ImGui.isItemHovered()) {
            ImGuiText.tooltip("Toggle ground decoration rendering\nWhen off, only interactive/blocking ground decorations render")
        }

        ImGui.spacing()
        ImGui.setNextItemWidth(120f)
        if (ImGui.sliderInt("Render Distance", renderDistanceInput, 0, RegionManager.MAX_RENDER_DISTANCE)) {
        }
        ImGui.sameLine()
        if (ImGui.button("Apply##renderDist")) {
            asyncRegionManager.setRenderDistanceAsync(renderDistanceInput[0])
        }
        if (ImGui.isItemHovered()) {
            val dist = renderDistanceInput[0]
            val gridSize = dist * 2 + 1
            val totalRegions = gridSize * gridSize
            ImGuiText.tooltip("Render distance: $dist ($gridSize x $gridSize = $totalRegions regions)\n0 = center only, 1 = 3x3, 2 = 5x5, etc.")
        }
    }

    private fun renderToolPalette() {
        ImGui.text("Tool")

        val buttonWidth = (ImGui.getContentRegionAvailX() - ImGui.getStyle().itemSpacingX * 2f) / 3f

        for (tool in EditorTool.entries) {
            if (tool.ordinal % 3 != 0) ImGui.sameLine()

            val isSelected = currentEditorTool == tool
            if (isSelected) {
                ImGui.pushStyleColor(ImGuiCol.Button, 0.26f, 0.59f, 0.98f, 1f)
                ImGui.pushStyleColor(ImGuiCol.ButtonHovered, 0.26f, 0.59f, 0.98f, 0.8f)
            }

            val label = if (tool.wip) "${tool.label}*" else tool.label
            if (ImGui.button(label, buttonWidth, 0f)) {
                currentEditorTool = tool
            }
            if (tool.wip && ImGui.isItemHovered()) {
                ImGuiText.tooltip("${tool.label} (WIP - not yet functional)")
            }

            if (isSelected) {
                ImGui.popStyleColor(2)
            }
        }
    }

    private fun renderCurrentToolContent() {
        when (currentEditorTool) {
            EditorTool.SELECT -> renderEditorSelectTool()
            EditorTool.OBJECT_PLACE -> renderEditorObjectPlaceTool()
            EditorTool.OBJECT_MOVE -> renderEditorObjectMoveTool()
            EditorTool.TERRAIN_PAINT -> renderEditorTerrainPaintTool()
            EditorTool.HEIGHT_EDIT -> renderEditorHeightEditTool()
            EditorTool.FLAG_EDIT -> renderEditorFlagEditTool()
        }
    }

    private fun renderEnvironmentSection() {
        if (!ImGui.collapsingHeader("Environment")) return

        ImGuiText.disabled(
            if (environment.fromCache) {
                "Decoded from m${currentRegionX}_$currentRegionY"
            } else {
                "No environment block - client defaults"
            }
        )

        ImGui.text("Sun")

        val dynamic = ImBoolean(environment.dynamicSunLighting)
        if (ImGui.checkbox("Dynamic sun lighting", dynamic)) {
            val enabled = dynamic.get()
            requestTerrainSettingChange("Toggle dynamic sun lighting") {
                environment.dynamicSunLighting = enabled
                TerrainLoader.bakeSunLighting = !enabled
            }
        }
        if (ImGui.isItemHovered()) {
            ImGuiText.tooltip(
                "On: meshes carry unlit colour and the shader applies the environment sun, " +
                    "so the controls below take effect immediately.\n" +
                    "Off: the legacy path bakes a fixed light direction into terrain vertex " +
                    "colours at build time and the sun controls do nothing."
            )
        }

        envSunColourInput[0] = EnvironmentSettings.red(environment.sunColour)
        envSunColourInput[1] = EnvironmentSettings.green(environment.sunColour)
        envSunColourInput[2] = EnvironmentSettings.blue(environment.sunColour)
        if (ImGui.colorEdit3("Sun colour", envSunColourInput)) {
            environment.sunColour = EnvironmentSettings.pack(
                envSunColourInput[0], envSunColourInput[1], envSunColourInput[2]
            )
        }

        envSunPositionInput[0] = environment.sunPositionX
        envSunPositionInput[1] = environment.sunPositionY
        envSunPositionInput[2] = environment.sunPositionZ
        ImGui.setNextItemWidth(200f)
        if (ImGui.dragFloat3("Sun position", envSunPositionInput, 1f, -512f, 512f)) {
            environment.sunPositionX = envSunPositionInput[0]
            environment.sunPositionY = envSunPositionInput[1]
            environment.sunPositionZ = envSunPositionInput[2]
        }
        if (ImGui.isItemHovered()) {
            ImGuiText.tooltip("RS scene space, exactly as the cache stores it: Y is negative-up.")
        }

        envSunAmbientInput[0] = environment.sunAmbient
        ImGui.setNextItemWidth(150f)
        if (ImGui.sliderFloat("Ambient", envSunAmbientInput, 0f, 4f)) {
            environment.sunAmbient = envSunAmbientInput[0]
        }

        envSunLightInput[0] = environment.sunLight
        ImGui.setNextItemWidth(150f)
        if (ImGui.sliderFloat("Key light", envSunLightInput, 0f, 4f)) {
            environment.sunLight = envSunLightInput[0]
        }

        envSunBacklightInput[0] = environment.sunBacklight
        ImGui.setNextItemWidth(150f)
        if (ImGui.sliderFloat("Backlight", envSunBacklightInput, 0f, 4f)) {
            environment.sunBacklight = envSunBacklightInput[0]
        }
        if (ImGui.isItemHovered()) {
            ImGuiText.tooltip("Negative diffuse: darkens surfaces facing away from the sun.")
        }

        val direction = environment.sunDirection(Vector3f())
        ImGuiText.disabled(
            "dir (%.2f, %.2f, %.2f)  ambient %.3f".format(
                direction.x, direction.y, direction.z, environment.sunBrightness
            )
        )

        ImGui.spacing()

        ImGui.text("Fog")

        val fog = ImBoolean(environment.fogEnabled)
        if (ImGui.checkbox("Fog enabled", fog)) {
            environment.fogEnabled = fog.get()
            applyEnvironmentToRenderers()
        }
        if (ImGui.isItemHovered()) {
            ImGuiText.tooltip(
                "Off by default in the editor so the map stays readable at distance. The cache " +
                    "fog colour, depth and view range below are still the decoded values and " +
                    "still apply the moment this is ticked."
            )
        }

        envFogColourInput[0] = EnvironmentSettings.red(environment.fogColour)
        envFogColourInput[1] = EnvironmentSettings.green(environment.fogColour)
        envFogColourInput[2] = EnvironmentSettings.blue(environment.fogColour)
        if (ImGui.colorEdit3("Fog colour", envFogColourInput)) {
            environment.fogColour = EnvironmentSettings.pack(
                envFogColourInput[0], envFogColourInput[1], envFogColourInput[2]
            )
            applyEnvironmentToRenderers()
        }

        envFogDepthInput[0] = environment.fogDepth
        ImGui.setNextItemWidth(150f)
        if (ImGui.sliderInt("Fog depth", envFogDepthInput, 0, 4095)) {
            environment.fogDepth = envFogDepthInput[0]
        }

        envViewRangeInput[0] = environment.fogDistanceMultiplier
        ImGui.setNextItemWidth(150f)
        if (ImGui.sliderFloat("View range", envViewRangeInput, 1f, 24f)) {
            environment.fogDistanceMultiplier = envViewRangeInput[0]
        }
        if (ImGui.isItemHovered()) {
            ImGuiText.tooltip(
                "The client's FOG_DISTANCE_MULTIPLIER. Fog always reaches full opacity at the " +
                    "view range, so this decides how much of the scene it eats. 1 is Jagex stock " +
                    "(~34 tiles, which fogs out everything but the foreground from up here); " +
                    "10 matches the reference client in ../darkan-bot."
            )
        }

        val tiles = 512f * EnvironmentSettings.RS_TO_ENGINE
        ImGuiText.disabled(
            "density %d rs  |  fog %.0f to %.0f units (%.0f - %.0f tiles)".format(
                (environment.fogDepth + 256) shl 2,
                environment.fogStartUnits, environment.fogEndUnits,
                environment.fogStartUnits / tiles, environment.fogEndUnits / tiles
            )
        )

        ImGui.spacing()

        ImGui.text("Skybox")
        envSkyboxInput.set(environment.skyboxId)
        ImGui.setNextItemWidth(100f)
        if (ImGui.inputInt("Skybox id", envSkyboxInput)) {
            environment.skyboxId = envSkyboxInput.get().coerceAtLeast(-1)
            applyEnvironmentToRenderers()
        }
        if (ImGui.isItemHovered()) {
            ImGuiText.tooltip("-1 draws a flat fill in the fog colour, which is what every region in this cache does.")
        }
        ImGuiText.disabled(
            if (environment.skyboxId < 0) {
                "flat fog-colour background"
            } else {
                val type = runCatching { EnvironmentConfigs.skybox(cache, environment.skyboxId) }.getOrNull()
                "material ${type?.materialId ?: -1}, mode ${type?.backgroundMode ?: "?"}"
            }
        )

        ImGui.spacing()
        if (ImGui.button("Reset to cache", 140f, 0f)) {
            loadEnvironment(currentRegionX, currentRegionY)
        }

        ImGui.spacing()
        ImGui.separator()
        renderEnvironmentSaveSection()
    }

    private fun renderEnvironmentSaveSection() {
        ImGui.text("Save to cache")

        val regionKey = (currentRegionX shl 8) or currentRegionY
        if (envWritePlanRegion != regionKey) {
            envWritePlan = null
            envWriteLog = emptyList()
            envWriteError = null
            envWriteConfirm.set(false)
            envWritePlanRegion = regionKey
        }

        ImGuiText.disabledWrapped(
            "Target: m${currentRegionX}_$currentRegionY in ${MapEnvironmentWriter.cacheDirectory().absolutePath}"
        )

        if (ImGui.button("Preview changes (dry run)", 220f, 0f)) {
            envWriteLog = emptyList()
            envWriteError = null
            envWriteConfirm.set(false)
            envWritePlan = try {
                MapEnvironmentWriter.plan(cache, currentRegionX, currentRegionY, currentEnvironmentEdit())
            } catch (exception: Exception) {
                envWriteError = "Planning failed: ${exception.message}"
                null
            }
        }
        if (ImGui.isItemHovered()) {
            ImGuiText.tooltip(
                "Decodes the region's effects block, merges the edits above into it and reports " +
                    "the byte-level difference. Writes nothing."
            )
        }

        val plan = envWritePlan
        if (plan != null) {
            ImGuiText.disabledWrapped(plan.summary())
            if (plan.blocked != null) {
                ImGuiText.coloured(1f, 0.4f, 0.4f, 1f, "Blocked - cannot save this region.")
            } else if (plan.changes.isEmpty()) {
                ImGuiText.disabled("No field changes.")
            } else {
                for (change in plan.changes) ImGuiText.disabled("  $change")
            }
        }

        val saveable = plan != null && plan.blocked == null && plan.hasChanges
        if (saveable) {
            ImGui.checkbox("Overwrite m${currentRegionX}_$currentRegionY in the cache", envWriteConfirm)
        }
        val disabled = !saveable || !envWriteConfirm.get()
        if (disabled) ImGui.beginDisabled()
        if (ImGui.button("Save environment to cache", 220f, 0f) && !disabled) {
            try {
                val result = MapEnvironmentWriter.apply(plan!!)
                envWriteLog = result.log
                envWriteError = null
                envWritePlan = null
                envWriteConfirm.set(false)
            } catch (exception: Exception) {
                envWriteError = "Save failed: ${exception.message}"
                System.err.println("[MapViewer] environment save failed: ${exception.message}")
            }
        }
        if (disabled) ImGui.endDisabled()
        if (ImGui.isItemHovered()) {
            ImGuiText.tooltip(
                "Backs the archive up to ${MapEnvironmentWriter.backupDirectory().absolutePath} " +
                    "before the first write of the session, then rewrites the effects block and " +
                    "the index 255 CRC. The tile records are copied through untouched."
            )
        }

        envWriteError?.let { ImGuiText.coloured(1f, 0.4f, 0.4f, 1f, it) }
        for (line in envWriteLog) ImGuiText.disabledWrapped("  $line")
    }

    private fun currentEnvironmentEdit() = EnvironmentEdit(
        sunColour = environment.sunColour,
        sunPositionX = environment.sunPositionX,
        sunPositionY = environment.sunPositionY,
        sunPositionZ = environment.sunPositionZ,
        sunAmbient = environment.sunAmbient,
        sunLight = environment.sunLight,
        sunBacklight = environment.sunBacklight,
        fogColour = environment.fogColour,
        fogDepth = environment.fogDepth,
        skyboxId = environment.skyboxId,
        skyboxRotation = environment.skyboxRotation,
    )

    private fun renderRenderingSettingsSection() {
        if (!ImGui.collapsingHeader("Rendering Settings")) return

        val blendingEnabled = ImBoolean(TerrainLoader.enableBlending)
        if (ImGui.checkbox("Terrain Blending", blendingEnabled)) {
            val enabled = blendingEnabled.get()
            requestTerrainSettingChange("Toggle terrain blending") {
                TerrainLoader.enableBlending = enabled
            }
        }

        val overlaysEnabled = ImBoolean(TerrainLoader.enableOverlays)
        if (ImGui.checkbox("Show Overlays", overlaysEnabled)) {
            val enabled = overlaysEnabled.get()
            requestTerrainSettingChange("Toggle overlays") {
                TerrainLoader.enableOverlays = enabled
            }
        }

        val colorNoiseEnabled = ImBoolean(TerrainLoader.enableColorNoise)
        if (ImGui.checkbox("Color Noise", colorNoiseEnabled)) {
            val enabled = colorNoiseEnabled.get()
            requestTerrainSettingChange("Toggle colour noise") {
                TerrainLoader.enableColorNoise = enabled
            }
        }
        if (ImGui.isItemHovered()) {
            ImGuiText.tooltip("Adds subtle per-vertex brightness variation\nlike the RS client (+-1.5% noise)")
        }

        val lightingEnabled = ImBoolean(TerrainLoader.enableLighting)
        if (ImGui.checkbox("Terrain Lighting", lightingEnabled)) {
            val enabled = lightingEnabled.get()
            requestTerrainSettingChange("Toggle terrain lighting") {
                TerrainLoader.enableLighting = enabled
            }
        }
        if (ImGui.isItemHovered()) {
            ImGuiText.tooltip("Applies RS-style per-vertex lighting based on\nterrain normals and sun direction")
        }

        if (TerrainLoader.enableLighting && ImGui.collapsingHeader("Lighting Parameters")) {
            ImGui.indent()
            if (environment.dynamicSunLighting) {
                ImGuiText.disabledWrapped(
                    "Dynamic sun lighting is on, so these do nothing - edit the sun in the " +
                        "Environment section instead."
                )
                ImGui.spacing()
            }

            ImGui.text("Light Direction")
            val lightDirX = floatArrayOf(TerrainLoader.LIGHT_DIRECTION_X)
            val lightDirY = floatArrayOf(TerrainLoader.LIGHT_DIRECTION_Y)
            val lightDirZ = floatArrayOf(TerrainLoader.LIGHT_DIRECTION_Z)

            var lightingChange: (() -> Unit)? = null
            ImGui.setNextItemWidth(100f)
            if (ImGui.sliderFloat("Dir X", lightDirX, -1f, 1f)) {
                val v = lightDirX[0]
                lightingChange = { TerrainLoader.LIGHT_DIRECTION_X = v }
            }
            ImGui.setNextItemWidth(100f)
            if (ImGui.sliderFloat("Dir Y", lightDirY, -1f, 1f)) {
                val v = lightDirY[0]
                lightingChange = { TerrainLoader.LIGHT_DIRECTION_Y = v }
            }
            ImGui.setNextItemWidth(100f)
            if (ImGui.sliderFloat("Dir Z", lightDirZ, -1f, 1f)) {
                val v = lightDirZ[0]
                lightingChange = { TerrainLoader.LIGHT_DIRECTION_Z = v }
            }

            ImGui.spacing()

            ImGui.text("RS Lighting Formula:")
            ImGuiText.wrapped("factor = sunBrightness + dot * (dot>0 ? bright : dark)")

            val sunBrightness = floatArrayOf(TerrainLoader.SUN_BRIGHTNESS)
            val brightFactor = floatArrayOf(TerrainLoader.BRIGHT_FACTOR)
            val darkFactor = floatArrayOf(TerrainLoader.DARK_FACTOR)

            ImGui.setNextItemWidth(100f)
            if (ImGui.sliderFloat("Sun Brightness", sunBrightness, 0.5f, 1.5f)) {
                val v = sunBrightness[0]
                lightingChange = { TerrainLoader.SUN_BRIGHTNESS = v }
            }
            if (ImGui.isItemHovered()) {
                ImGuiText.tooltip("Base ambient brightness (default: 1.2)")
            }

            ImGui.setNextItemWidth(100f)
            if (ImGui.sliderFloat("Bright Factor", brightFactor, 0.2f, 1.5f)) {
                val v = brightFactor[0]
                lightingChange = { TerrainLoader.BRIGHT_FACTOR = v }
            }
            if (ImGui.isItemHovered()) {
                ImGuiText.tooltip("Factor for lit surfaces (default: 0.865)")
            }

            ImGui.setNextItemWidth(100f)
            if (ImGui.sliderFloat("Dark Factor", darkFactor, 0.5f, 2.0f)) {
                val v = darkFactor[0]
                lightingChange = { TerrainLoader.DARK_FACTOR = v }
            }
            if (ImGui.isItemHovered()) {
                ImGuiText.tooltip("Factor for shadowed surfaces (default: 0.814)")
            }

            if (ImGui.button("Reset to Defaults")) {
                lightingChange = {
                    TerrainLoader.LIGHT_DIRECTION_X = 0.163f
                    TerrainLoader.LIGHT_DIRECTION_Y = 0.070f
                    TerrainLoader.LIGHT_DIRECTION_Z = 1.00f
                    TerrainLoader.SUN_BRIGHTNESS = 1.2f
                    TerrainLoader.BRIGHT_FACTOR = 0.865f
                    TerrainLoader.DARK_FACTOR = 0.814f
                }
            }

            lightingChange?.let { change ->
                requestTerrainSettingChange("Change lighting parameters", change)
            }

            ImGui.unindent()
        }

        ImGui.spacing()

        val terrainAlignEnabled = ImBoolean(TerrainAligner.enabled)
        if (ImGui.checkbox("Terrain Alignment", terrainAlignEnabled)) {
            val enabled = terrainAlignEnabled.get()
            requestSettingChange("Toggle terrain alignment") {
                TerrainAligner.enabled = enabled
            }
        }
        if (ImGui.isItemHovered()) {
            ImGuiText.tooltip("Tilt objects to match terrain slopes\n(RS groundContourType=3 / hillChange)")
        }

        if (TerrainAligner.enabled && ImGui.collapsingHeader("Tilt Debug")) {
            ImGui.indent()

            ImGui.text("Stats: ${TerrainAligner.getDebugStats()}")

            val negateXTilt = ImBoolean(TerrainAligner.negateXTilt)
            if (ImGui.checkbox("Negate X Tilt", negateXTilt)) {
                val enabled = negateXTilt.get()
                requestSettingChange("Toggle X tilt negation") {
                    TerrainAligner.negateXTilt = enabled
                }
            }
            if (ImGui.isItemHovered()) {
                ImGuiText.tooltip("Negate X-axis tilt (pitch) for coordinate system fix")
            }

            val negateZTilt = ImBoolean(TerrainAligner.negateZTilt)
            if (ImGui.checkbox("Negate Z Tilt", negateZTilt)) {
                val enabled = negateZTilt.get()
                requestSettingChange("Toggle Z tilt negation") {
                    TerrainAligner.negateZTilt = enabled
                }
            }
            if (ImGui.isItemHovered()) {
                ImGuiText.tooltip("Negate Z-axis tilt (roll) for coordinate system fix")
            }

            if (ImGui.button("Enable Debug Logging")) {
                requestSettingChange("Enable tilt debug logging") {
                    TerrainAligner.enableDebug(true)
                }
            }

            ImGui.unindent()
        }

    }


    private fun renderEditorSelectTool() {
        val details = tilePicker.selectedTileDetails
        val tile = tilePicker.selectedTile

        if (tile == null) {
            ImGuiText.coloured(0.7f, 0.7f, 0.7f, 1f, "Left-click on terrain to select a tile")
            return
        }

        if (tile.regionX != currentRegionX || tile.regionY != currentRegionY) {
            ImGuiText.colouredWrapped(
                1f, 0.7f, 0.3f, 1f,
                "Selected tile is in region (${tile.regionX}, ${tile.regionY}), not the centre region " +
                        "($currentRegionX, $currentRegionY). Edits apply to the region under the cursor."
            )
        }

        if (ImGui.collapsingHeader("Object Inspector")) {
            if (details == null || details.objects.isEmpty()) {
                ImGuiText.coloured(0.7f, 0.7f, 0.7f, 1f, "No objects on selected tile")
            } else {
                for ((index, tileObj) in details.objects.withIndex()) {
                    val obj = tileObj.mapObject
                    val def = tileObj.definition

                    val objName = def.name.ifEmpty { "<unnamed>" }
                    if (ImGuiText.treeNode("editor_obj_$index", "#%d: %s".format(obj.id, objName))) {

                        val shapeName = ObjectShape.entries.find { it.id == obj.shape }?.name ?: "Unknown"
                        ImGui.text("Shape:    %s (%d)".format(shapeName, obj.shape))
                        ImGui.text("Rotation: %d".format(obj.rotation))

                        ImGui.text("Models:   %s".format(
                            if (tileObj.modelIds.isEmpty()) "(none)"
                            else tileObj.modelIds.joinToString(", ")
                        ))

                        val interactiveLabel = when (def.interactive) {
                            -1 -> "Default (-1)"
                            0 -> "No (0)"
                            1 -> "Yes (1)"
                            else -> "${def.interactive}"
                        }
                        ImGui.text("Interactive: %s".format(interactiveLabel))

                        ImGui.spacing()
                        ImGui.text("Definition Properties")
                        ImGui.indent()
                        ImGui.text("Size:     %dx%d".format(def.sizeX, def.sizeY))
                        ImGui.text("Solid:    %d".format(def.solid))

                        val blocksProjectile = (def.block and ObjectDefinition.PROJECTILE) != 0
                        ImGui.text("Blocks Projectile: %s".format(if (blocksProjectile) "Yes" else "No"))
                        ImGui.unindent()

                        ImGui.spacing()

                        ImGui.beginDisabled()
                        ImGui.button("Edit##edit_$index")
                        ImGui.sameLine()
                        ImGui.button("Delete##delete_$index")
                        ImGui.endDisabled()
                        if (ImGui.isItemHovered(ImGuiHoveredFlags.AllowWhenDisabled)) {
                            ImGuiText.tooltip("Not yet implemented")
                        }

                        ImGui.treePop()
                    }
                }
            }
        }

        if (ImGui.collapsingHeader("Tile Inspector")) {
            if (details == null || details.tileData == null) {
                ImGuiText.coloured(0.7f, 0.7f, 0.7f, 1f, "No tile data available")
            } else {
                val tileData = details.tileData

                ImGui.text("Position: (%d, %d) Plane %d".format(tile.localX, tile.localY, tile.plane))
                ImGui.text("Height:   %d".format(tileData.height))

                ImGui.spacing()
                ImGui.text("Overlay")
                ImGui.indent()
                ImGui.text("ID:       %d".format(tileData.overlayId))
                ImGui.text("Shape:    %d".format(tileData.overlayShape))
                ImGui.text("Rotation: %d".format(tileData.overlayRotation))
                ImGui.unindent()

                ImGui.spacing()
                ImGui.text("Underlay")
                ImGui.indent()
                ImGui.text("ID:       %d".format(tileData.underlayId))
                ImGui.unindent()

                ImGui.spacing()
                ImGui.text("Flags (0x%02X)".format(tileData.flags))
                ImGui.indent()

                ImGui.beginDisabled()
                val blocksWalk = ImBoolean(tileData.flags and 0x1 != 0)
                ImGui.checkbox("Blocks Walk (0x1)", blocksWalk)
                val bridge = ImBoolean(tileData.flags and 0x2 != 0)
                ImGui.checkbox("Bridge (0x2)", bridge)
                val invisible = ImBoolean(tileData.flags and 0x4 != 0)
                ImGui.checkbox("Invisible (0x4)", invisible)
                val forcePlane0 = ImBoolean(tileData.flags and 0x8 != 0)
                ImGui.checkbox("Force Plane 0 (0x8)", forcePlane0)
                ImGui.endDisabled()

                ImGui.unindent()
            }
        }

        if (ImGui.collapsingHeader("Tile Details (Debug)")) {
            if (details == null || details.tileData == null) {
                ImGuiText.coloured(0.7f, 0.7f, 0.7f, 1f, "No tile data available")
            } else {
                val tileData = details.tileData

                ImGui.text("Tile Coordinates")
                ImGui.separator()
                ImGui.text("  Region: (%d, %d)".format(tile.regionX, tile.regionY))
                ImGui.text("  Local:  (%d, %d)".format(tile.localX, tile.localY))
                ImGui.text("  Plane:  %d".format(tile.plane))
                ImGui.text("  World:  (%.2f, %.2f, %.2f)".format(tile.worldX, tile.worldY, tile.worldZ))

                ImGui.spacing()
                ImGui.text("Overlay Definition")
                ImGui.separator()
                ImGui.text("  ID:       %d".format(tileData.overlayId))
                ImGui.text("  Shape:    %d".format(tileData.overlayShape))
                ImGui.text("  Rotation: %d".format(tileData.overlayRotation))

                val overlayDef = details.overlayDef
                if (overlayDef != null) {
                    ImGui.text("  Texture:  %d".format(overlayDef.texture))
                    ImGui.text("  Scale:    %d".format(overlayDef.scale))
                    ImGui.text("  Colour:   0x%04X (%d)".format(overlayDef.colour, overlayDef.colour))
                    ImGui.text("  BlendCol: 0x%04X (%d)".format(overlayDef.blendColour, overlayDef.blendColour))
                    ImGui.text("  Underlay Override: %s".format(overlayDef.underlayOverrides))

                    val uvs = details.cornerUVsOverlay
                    if (uvs != null) {
                        ImGui.text("  UV Scale: %.4f (1/%d)".format(1f / overlayDef.scale.toFloat(), overlayDef.scale))
                        ImGui.text("  UV SW: (%.3f, %.3f)".format(uvs[0][0], uvs[0][1]))
                        ImGui.text("  UV SE: (%.3f, %.3f)".format(uvs[1][0], uvs[1][1]))
                        ImGui.text("  UV NW: (%.3f, %.3f)".format(uvs[2][0], uvs[2][1]))
                        ImGui.text("  UV NE: (%.3f, %.3f)".format(uvs[3][0], uvs[3][1]))
                    }
                } else if (tileData.overlayId > 0) {
                    ImGuiText.coloured(1f, 0.5f, 0.5f, 1f, "  (Definition not found)")
                }

                ImGui.spacing()
                ImGui.text("Underlay Definition")
                ImGui.separator()
                ImGui.text("  ID:       %d".format(tileData.underlayId))

                val underlayDef = details.underlayDef
                if (underlayDef != null) {
                    ImGui.text("  Texture:  %d".format(underlayDef.texture))
                    ImGui.text("  Scale:    %d".format(underlayDef.scale))
                    ImGui.text("  Colour:   0x%06X".format(underlayDef.colour))
                    ImGui.text("  Hue:      %d".format(underlayDef.hue))
                    ImGui.text("  Sat:      %d".format(underlayDef.saturation))
                    ImGui.text("  Light:    %d".format(underlayDef.lightness))
                    ImGui.text("  Chroma:   %d".format(underlayDef.chroma))

                    val uvs = details.cornerUVsUnderlay
                    if (uvs != null) {
                        ImGui.text("  UV Scale: %.4f (1/%d)".format(1f / underlayDef.scale.toFloat(), underlayDef.scale))
                        ImGui.text("  UV SW: (%.3f, %.3f)".format(uvs[0][0], uvs[0][1]))
                        ImGui.text("  UV SE: (%.3f, %.3f)".format(uvs[1][0], uvs[1][1]))
                        ImGui.text("  UV NW: (%.3f, %.3f)".format(uvs[2][0], uvs[2][1]))
                        ImGui.text("  UV NE: (%.3f, %.3f)".format(uvs[3][0], uvs[3][1]))
                    }
                } else if (tileData.underlayId > 0) {
                    ImGuiText.coloured(1f, 0.5f, 0.5f, 1f, "  (Definition not found)")
                }

                ImGui.spacing()
                ImGui.text("Corner Heights (scaled)")
                ImGui.separator()
                ImGui.text("  SW: %.2f  SE: %.2f".format(details.cornerHeights[0], details.cornerHeights[1]))
                ImGui.text("  NW: %.2f  NE: %.2f".format(details.cornerHeights[2], details.cornerHeights[3]))

                ImGui.spacing()
                ImGui.text("Objects on Tile (%d)".format(details.objects.size))
                ImGui.separator()
                if (details.objects.isEmpty()) {
                    ImGuiText.coloured(0.7f, 0.7f, 0.7f, 1f, "  (No objects)")
                } else {
                    for ((index, tileObj) in details.objects.withIndex()) {
                        val obj = tileObj.mapObject
                        val def = tileObj.definition
                        if (ImGuiText.treeNode("debug_obj_$index", "Object %d: %s".format(obj.id, def.name.ifEmpty { "<unnamed>" }))) {
                            ImGui.text("  Shape: %d, Rotation: %d".format(obj.shape, obj.rotation))
                            ImGui.text("  World Pos: (%d, %d)".format(obj.x, obj.y))
                            ImGui.text("  Size: %dx%d".format(def.sizeX, def.sizeY))

                            ImGui.text("  Model IDs: %s".format(
                                if (tileObj.modelIds.isEmpty()) "(none)"
                                else tileObj.modelIds.joinToString(", ")
                            ))

                            ImGui.text("  Texture IDs: %s".format(
                                if (tileObj.textureIds.isEmpty()) "(none)"
                                else tileObj.textureIds.sorted().joinToString(", ")
                            ))

                            if (def.originalTextureColours != null && def.modifiedTextureColours != null) {
                                ImGuiText.coloured(0.5f, 1f, 0.5f, 1f, "  Has Retexturing")
                                for (i in def.originalTextureColours!!.indices) {
                                    if (i < def.modifiedTextureColours!!.size) {
                                        val src = def.originalTextureColours!![i].toInt() and 0xffff
                                        val dst = def.modifiedTextureColours!![i].toInt() and 0xffff
                                        ImGui.text("    Tex %d -> %d".format(src, dst))
                                    }
                                }
                            }
                            if (def.originalColours != null && def.modifiedColours != null) {
                                ImGuiText.coloured(0.5f, 0.5f, 1f, 1f, "  Has Recoloring")
                            }

                            ImGui.treePop()
                        }
                    }
                }

                ImGui.spacing()
                ImGui.separator()
                if (ImGui.button("Clear Selection")) {
                    tilePicker.clearSelection()
                    tileHighlightMesh?.cleanup()
                    tileHighlightMesh = null
                }
            }
        }
    }

    private fun renderEditorObjectPlaceTool() {
        if (selectedObjectId >= 0) {
            val selDef = ObjectRenderer.getDefinition(selectedObjectId)
            if (selDef != null) {
                ImGuiText.coloured(0.3f, 0.9f, 0.3f, 1f, "Selected: %s (ID: %d)".format(selDef.name, selDef.id))
                ImGuiText.coloured(0.3f, 0.9f, 0.3f, 1f, "Left-click terrain to place")
            }
            ImGui.spacing()
        } else {
            ImGuiText.coloured(0.7f, 0.7f, 0.7f, 1f, "Search and select an object below to place it")
            ImGui.spacing()
        }

        if (ImGui.collapsingHeader("Object Browser", ImGuiTreeNodeFlags.DefaultOpen)) {
            ImGui.setNextItemWidth(-1f)
            if (ImGui.inputText("##obj_search", editorObjectSearchQuery)) {
                val query = editorObjectSearchQuery.get().trim()
                objectSearchResults = if (query.length >= 2) {
                    searchObjectDefinitions(query)
                } else {
                    emptyList()
                }
            }

            ImGui.spacing()

            val listHeight = ImGui.getContentRegionAvailY().coerceAtLeast(100f).coerceAtMost(300f)
            if (ImGui.beginChild("obj_search_results", 0f, listHeight, ImGuiChildFlags.Borders)) {
                val query = editorObjectSearchQuery.get().trim()
                if (query.length < 2) {
                    ImGuiText.coloured(0.5f, 0.5f, 0.5f, 1f, "Type 2+ characters to search...")
                } else if (objectSearchResults.isEmpty()) {
                    ImGuiText.coloured(0.5f, 0.5f, 0.5f, 1f, "No results for \"%s\"".format(query))
                } else {
                    for (def in objectSearchResults) {
                        val isSelected = selectedObjectId == def.id
                        val label = "%d: %s".format(def.id, def.name)
                        if (ImGui.selectable(label, isSelected)) {
                            selectedObjectId = def.id
                        }
                        if (ImGui.isItemHovered()) {
                            ImGuiText.tooltip("ID: %d\nSize: %dx%d".format(def.id, def.sizeX, def.sizeY))
                        }
                    }
                }
            }
            ImGui.endChild()

            if (selectedObjectId >= 0) {
                ImGui.spacing()
                ImGui.separator()
                ImGui.spacing()
                ImGui.setNextItemWidth(120f)
                ImGui.sliderInt("Shape##obj", editorObjectShape, 0, 22)
                ImGui.setNextItemWidth(120f)
                ImGui.sliderInt("Rotation##obj", editorObjectRotation, 0, 3)

                if (placedObjects.isNotEmpty()) {
                    ImGui.spacing()
                    ImGuiText.coloured(0.7f, 0.7f, 0.7f, 1f, "Placed objects: %d".format(placedObjects.size))
                }
            }
        }
    }

    private fun renderEditorObjectMoveTool() {
        val moving = movingObject
        if (moving != null) {
            val def = moving.instance.definition
            ImGuiText.coloured(0.3f, 0.9f, 0.3f, 1f, "Dragging: %s (ID: %d)".format(def.name, def.id))
            ImGuiText.coloured(0.7f, 0.7f, 0.7f, 1f, "Release mouse to drop. Escape to cancel.")
            ImGui.spacing()
            ImGui.text("Original: (%d, %d)".format(
                moving.originRegionX * 64 + moving.originalX,
                moving.originRegionY * 64 + moving.originalY
            ))
            ImGui.spacing()
            if (ImGui.button("Cancel Move")) {
                val tileSize = 512f * CoordinateUtil.DEFAULT_SCALE
                val origWorldX = moving.originRegionX * 64 + moving.originalX
                val origWorldY = moving.originRegionY * 64 + moving.originalY
                val sizeX: Int
                val sizeY: Int
                val mo = moving.instance.mapObject
                if (mo.rotation == 1 || mo.rotation == 3) {
                    sizeX = def.sizeY
                    sizeY = def.sizeX
                } else {
                    sizeX = def.sizeX
                    sizeY = def.sizeY
                }
                val basePosX = origWorldX * tileSize
                val basePosZ = -origWorldY * tileSize
                moving.instance.worldPosition.x = basePosX + sizeX * tileSize * 0.5f
                moving.instance.worldPosition.z = basePosZ + -sizeY * tileSize * 0.5f
                objectSceneDirty = true
                recalcObjectHeight(moving.instance, force = true)
                movingObjectMoved = false
                movingObject = null
                moveOriginMesh?.cleanup()
                moveOriginMesh = null
            }
        } else {
            ImGuiText.coloured(0.7f, 0.7f, 0.7f, 1f, "Click an object to grab it")
            ImGuiText.coloured(0.7f, 0.7f, 0.7f, 1f, "Drag to reposition, release to place")
        }
    }

    private fun renderEditorTerrainPaintTool() {
        if (ImGui.collapsingHeader("Terrain Painter", ImGuiTreeNodeFlags.DefaultOpen)) {
            ImGuiText.coloured(0.7f, 0.7f, 0.7f, 1f, "Click or drag to paint terrain")
            ImGui.spacing()

            ImGui.text("Paint Mode")
            if (ImGui.radioButton("Overlay", editorPaintMode == 0)) {
                editorPaintMode = 0
            }
            ImGui.sameLine()
            if (ImGui.radioButton("Underlay", editorPaintMode == 1)) {
                editorPaintMode = 1
            }

            ImGui.spacing()

            val idLabel = if (editorPaintMode == 0) "Overlay ID" else "Underlay ID"
            ImGui.setNextItemWidth(120f)
            ImGui.inputInt(idLabel, editorOverlayUnderlayId)

            if (editorPaintMode == 0) {
                ImGui.setNextItemWidth(120f)
                ImGui.sliderInt("Shape", editorOverlayShape, 0, 11)
                ImGui.setNextItemWidth(120f)
                ImGui.sliderInt("Rotation", editorOverlayRotation, 0, 3)
            }

            ImGui.spacing()

            ImGui.setNextItemWidth(120f)
            ImGui.sliderInt("Brush Size", editorBrushSize, 1, 5)

            ImGui.spacing()
            ImGuiText.coloured(0.3f, 0.9f, 0.3f, 1f, "Left-click or drag to paint")
        }
    }

    private fun renderEditorHeightEditTool() {
        if (ImGui.collapsingHeader("Height Editor", ImGuiTreeNodeFlags.DefaultOpen)) {
            ImGuiText.coloured(0.7f, 0.7f, 0.7f, 1f, "Click and drag to edit height")
            ImGui.spacing()

            ImGui.setNextItemWidth(120f)
            ImGui.sliderInt("Brush Radius", editorHeightBrushRadius, 1, 3)

            ImGui.setNextItemWidth(120f)
            ImGui.sliderInt("Strength", editorHeightStrength, 1, 10)

            ImGui.spacing()

            ImGui.text("Mode")
            if (ImGui.radioButton("Raise", editorHeightMode == 0)) {
                editorHeightMode = 0
            }
            ImGui.sameLine()
            if (ImGui.radioButton("Lower", editorHeightMode == 1)) {
                editorHeightMode = 1
            }
            ImGui.sameLine()
            if (ImGui.radioButton("Smooth", editorHeightMode == 2)) {
                editorHeightMode = 2
            }

            ImGui.spacing()
            ImGuiText.coloured(0.3f, 0.9f, 0.3f, 1f, "Left-click and drag to edit height")
        }
    }

    private fun renderEditorFlagEditTool() {
        if (ImGui.collapsingHeader("Flag Editor", ImGuiTreeNodeFlags.DefaultOpen)) {
            ImGuiText.coloured(0.7f, 0.7f, 0.7f, 1f, "Click tile to select, toggle flags below")
            ImGui.spacing()

            val tile = tilePicker.selectedTile
            val flags = if (tile == null) null else tileFlags(tile.regionX, tile.regionY, currentPlane, tile.localX, tile.localY)

            if (tile == null || flags == null) {
                ImGuiText.coloured(0.5f, 0.5f, 0.5f, 1f, "Select a tile first (left-click)")
            } else {
                val x = tile.localX
                val y = tile.localY
                val currentFlags = flags

                if (tile.regionX != currentRegionX || tile.regionY != currentRegionY) {
                    ImGuiText.coloured(1f, 0.8f, 0.4f, 1f, "Region (%d, %d)".format(tile.regionX, tile.regionY))
                }
                ImGui.text("Tile (%d, %d) - Flags: 0x%02X".format(x, y, currentFlags))
                ImGui.spacing()

                var newFlags = currentFlags

                val blocksWalk = ImBoolean(currentFlags and 0x1 != 0)
                if (ImGui.checkbox("Blocks Walk (0x1)##flag_edit", blocksWalk)) {
                    newFlags = if (blocksWalk.get()) newFlags or 0x1 else newFlags and 0x1.inv()
                }
                val bridge = ImBoolean(currentFlags and 0x2 != 0)
                if (ImGui.checkbox("Bridge (0x2)##flag_edit", bridge)) {
                    newFlags = if (bridge.get()) newFlags or 0x2 else newFlags and 0x2.inv()
                }
                val invisible = ImBoolean(currentFlags and 0x4 != 0)
                if (ImGui.checkbox("Invisible (0x4)##flag_edit", invisible)) {
                    newFlags = if (invisible.get()) newFlags or 0x4 else newFlags and 0x4.inv()
                }
                val forcePlane0 = ImBoolean(currentFlags and 0x8 != 0)
                if (ImGui.checkbox("Force Plane 0 (0x8)##flag_edit", forcePlane0)) {
                    newFlags = if (forcePlane0.get()) newFlags or 0x8 else newFlags and 0x8.inv()
                }

                if (newFlags != currentFlags) {
                    applyTileFlags(tile.regionX, tile.regionY, x, y, newFlags)
                }
            }
        }
    }

    private fun hasUnsavedEdits(): Boolean =
        terrainEditedRegions.isNotEmpty() || placedObjects.isNotEmpty() || movedObjectCount > 0

    private fun unsavedEditSummary(): String {
        val parts = mutableListOf<String>()
        if (terrainEditedRegions.isNotEmpty()) {
            val regions = terrainEditedRegions.joinToString(", ") { "(${it.first}, ${it.second})" }
            val noun = if (terrainEditedRegions.size == 1) "region" else "regions"
            parts.add("terrain edits (heights / paint / flags) in ${terrainEditedRegions.size} $noun: $regions")
        }
        if (placedObjects.isNotEmpty()) {
            val byRegion = placedObjects
                .groupingBy { Pair(it.mapObject.x / 64, it.mapObject.y / 64) }
                .eachCount()
            val where = byRegion.entries.joinToString(", ") { (region, count) ->
                "$count in (${region.first}, ${region.second})"
            }
            parts.add("${placedObjects.size} placed object(s) [$where]")
        }
        if (movedObjectCount > 0) parts.add("$movedObjectCount moved object(s)")
        return parts.joinToString(", ")
    }

    private fun requestRegionChange(label: String, action: () -> Unit) {
        if (pendingRegionChange != null) return

        if (!hasUnsavedEdits()) {
            action()
            return
        }
        pendingRegionChangeLabel = label
        pendingRegionChange = action
    }

    private fun discardUnsavedEdits() {
        cleanupEditManagers()
        editManagerFor(currentRegionX, currentRegionY)
        placedObjects.clear()
        editorModelMeshData.clear()
        movingObject = null
        moveOriginMesh?.cleanup()
        moveOriginMesh = null
        movingObjectMoved = false
        movedObjectCount = 0
        objectSceneDirty = true
    }

    private fun cleanupEditManagers() {
        for (manager in editManagers.values) manager.cleanup()
        editManagers.clear()
        terrainEditedRegions.clear()
        editedTilesThisDrag.clear()
        lastBrushCenter = null
    }

    private fun requestSettingChange(label: String, apply: () -> Unit) =
        requestRegionChange(label) {
            apply()
            reloadRegionNow()
        }

    private fun requestTerrainSettingChange(label: String, apply: () -> Unit) =
        requestRegionChange(label) {
            apply()
            reloadTerrainNow()
        }

    private fun setShowRoofs(enabled: Boolean) = requestSettingChange("Toggle roofs") {
        showRoofs = enabled
        asyncRegionManager.showRoofs = enabled
    }

    private fun setPlane(plane: Int) = requestRegionChange("Switch to plane $plane") {
        currentPlane = plane.coerceIn(0, 3)
        loadRegion(currentRegionX, currentRegionY, currentPlane)
    }

    private fun navigateRegion(dx: Int, dy: Int) {
        val newX = (currentRegionX + dx).coerceIn(0, 255)
        val newY = (currentRegionY + dy).coerceIn(0, 255)
        jumpToRegion(newX, newY)
    }

    private fun jumpToRegion(regionX: Int, regionY: Int) {
        val targetX = regionX.coerceIn(0, 255)
        val targetY = regionY.coerceIn(0, 255)
        requestRegionChange("Load region ($targetX, $targetY)") {
            currentRegionX = targetX
            currentRegionY = targetY
            regionXInput.set(currentRegionX)
            regionYInput.set(currentRegionY)

            loadRegion(currentRegionX, currentRegionY, currentPlane)
        }
    }

    private fun reloadRegionNow() = loadRegion(currentRegionX, currentRegionY, currentPlane)

    private fun reloadTerrainNow() {
        TerrainLoader.clearCache()
        reloadRegionNow()
    }


    private fun loadRegion(regionX: Int, regionY: Int, plane: Int) = loadRegionAsync(regionX, regionY, plane)

    private fun loadRegionAsync(regionX: Int, regionY: Int, plane: Int) {
        loadEnvironment(regionX, regionY)

        tilePicker.clearSelection()
        tileHighlightMesh?.cleanup()
        tileHighlightMesh = null

        val regionLoadStart = RenderMetrics.startTiming()

        cleanupEditManagers()
        try {
            editManagers[Pair(regionX, regionY)] =
                TerrainEditManager(regionX, regionY, plane, cache).also { it.init() }
        } catch (e: Exception) {
            println("[MapViewer] TerrainEditManager init failed for ($regionX, $regionY): ${e.message}")
        }

        asyncRegionManager.renderDistance = renderDistanceInput[0]
        asyncRegionManager.showRoofs = showRoofs
        asyncRegionManager.navigateToRegion(regionX, regionY, plane)

        val im = asyncRegionManager.instanceManager
        indirectRenderer?.cleanup()
        indirectRenderer = null
        val centerMeshData = asyncRegionManager.centerModelMeshData
        if (useIndirectRendering && im != null && indirectColorShader != null && indirectTexturedShader != null) {
            val buildStart = System.nanoTime()
            val ir = IndirectRenderer(
                texturePixelProvider = TexturePixelProvider { textureId ->
                    TextureLoader.getTexturePixelsForMaterial(cache, textureId)
                }
            )
            try {
                ir.init()
                for ((modelId, meshDataList) in centerMeshData) {
                    if (meshDataList.isNotEmpty()) ir.registerModel(modelId, meshDataList)
                }
                ir.buildSharedGeometry()
                if (!ir.isReady()) throw IllegalStateException("no geometry registered")

                val cullSrc = cullShaderSource
                val resetSrc = cullResetShaderSource
                if (useGpuCulling && useStaticInstances && cullSrc != null && resetSrc != null) {
                    ir.initCulling(cullSrc, resetSrc)
                }

                indirectRenderer = ir
                objectSceneDirty = true
                println(
                    "[MapViewer] IndirectRenderer ready in ${"%.1f".format((System.nanoTime() - buildStart) / 1e6)} ms: " +
                            "${ir.getRegisteredModelCount()} models, ${ir.getMeshEntryCount()} mesh entries, " +
                            "${ir.getTextureLayerCount()} texture layers in ${ir.getTextureArrayCount()} array texture(s)"
                )
            } catch (e: Exception) {
                System.err.println("[MapViewer] IndirectRenderer build failed, falling back to instanced: $e")
                ir.cleanup()
                indirectRenderer = null
            }
        }

        RenderMetrics.terrainLoadTimeMs = RenderMetrics.endTiming(regionLoadStart)

        updateAtlasStats()

        val tileSize = 512f * CoordinateUtil.DEFAULT_SCALE
        val worldCenterX = (regionX * 64 + 32) * tileSize
        val worldCenterZ = -(regionY * 64 + 32) * tileSize
        scene.camera.setPosition(worldCenterX - 200f, 50f, worldCenterZ + 80f)
        scene.camera.pitch = -35f
        scene.camera.yaw = 0f
        scene.camera.rotate(0f, 0f)
        scene.camera.nearPlane = 1.0f
        scene.camera.farPlane = 4000f

        applyCameraOverride(worldCenterX, worldCenterZ)
    }

    private fun applyCameraOverride(worldCenterX: Float, worldCenterZ: Float) {
        val parts = System.getProperty("darkan.camera")?.split(',') ?: return
        if (parts.size < 5) {
            System.err.println("[MapViewer] -Ddarkan.camera needs dx,y,dz,yaw,pitch")
            return
        }
        val values = parts.map { it.trim().toFloatOrNull() }
        if (values.any { it == null }) {
            System.err.println("[MapViewer] -Ddarkan.camera has a non-numeric component")
            return
        }
        scene.camera.setPosition(worldCenterX + values[0]!!, values[1]!!, worldCenterZ + values[2]!!)
        scene.camera.yaw = values[3]!!
        scene.camera.pitch = values[4]!!
        scene.camera.rotate(0f, 0f)
        println("[MapViewer] camera override: pos=(${scene.camera.position.x}, ${scene.camera.position.y}, ${scene.camera.position.z}) yaw=${scene.camera.yaw} pitch=${scene.camera.pitch}")
    }

    private fun loadEnvironment(regionX: Int, regionY: Int) {
        val decoded = try {
            MapEnvironmentLoader.load(cache, regionX, regionY)?.environment(4, 4)
        } catch (e: Exception) {
            System.err.println("[MapViewer] Environment decode failed for ($regionX, $regionY): ${e.message}")
            null
        }
        environment.applyFrom(decoded ?: MapEnvironment.DEFAULT, decoded != null)
        applyEnvironmentToRenderers()
    }

    private fun applyEnvironmentToRenderers() {
        var materialId = -1
        var backgroundMode = SkyBackgroundMode.SINGLE_COLOR
        if (environment.skyboxId >= 0) {
            try {
                val type = EnvironmentConfigs.skybox(cache, environment.skyboxId)
                materialId = type.materialId
                backgroundMode = if (type.backgroundMode == BackgroundMode.COLOUR_TRANSITION) {
                    SkyBackgroundMode.COLOR_TRANSITION
                } else {
                    SkyBackgroundMode.SINGLE_COLOR
                }
            } catch (e: Exception) {
                System.err.println("[MapViewer] Skybox ${environment.skyboxId} lookup failed: ${e.message}")
            }
        }

        sky?.settings = SkySettings(
            materialId = materialId,
            backgroundMode = backgroundMode,
            rotation = environment.skyboxRotation,
            backgroundColor = environment.backgroundColour
        )

        clearColor.set(
            EnvironmentSettings.red(environment.backgroundColour),
            EnvironmentSettings.green(environment.backgroundColour),
            EnvironmentSettings.blue(environment.backgroundColour)
        )
    }

    private fun updateAtlasStats() {
        val tAtlas = terrainAtlas
        if (tAtlas != null) {
            val stats = tAtlas.getStats()
            RenderMetrics.terrainAtlasTextures = stats.textureCount
            RenderMetrics.terrainAtlasPages = stats.pageCount
            RenderMetrics.terrainAtlasUtilization = stats.utilization * 100f
        } else {
            RenderMetrics.terrainAtlasTextures = 0
            RenderMetrics.terrainAtlasPages = 0
            RenderMetrics.terrainAtlasUtilization = 0f
        }

        val oAtlas = objectAtlas
        if (oAtlas != null && oAtlas.getTextureCount() > 0) {
            val stats = oAtlas.getStats()
            RenderMetrics.objectAtlasTextures = stats.textureCount
            RenderMetrics.objectAtlasPages = stats.pageCount
            RenderMetrics.objectAtlasUtilization = stats.utilization * 100f
        } else {
            RenderMetrics.objectAtlasTextures = 0
            RenderMetrics.objectAtlasPages = 0
            RenderMetrics.objectAtlasUtilization = 0f
        }
    }

    private fun cleanupRegion() {
        cleanupEditManagers()
        indirectRenderer?.cleanup()
        indirectRenderer = null
        tilePicker.clearSelection()
        tileHighlightMesh?.cleanup()
        tileHighlightMesh = null
        placedObjects.clear()
        editorModelMeshData.clear()
        objectSceneDirty = true
        movingObject = null
        moveOriginMesh?.cleanup()
        moveOriginMesh = null
        brushPreviewMesh?.cleanup()
        brushPreviewMesh = null
        lastBrushCenter = null
    }










    private fun clampWindowToScreen() {
        val pos = ImGui.getWindowPos()
        val size = ImGui.getWindowSize()
        val screenWidth = window.width.toFloat()
        val screenHeight = window.height.toFloat()

        var newX = pos.x
        var newY = pos.y

        if (newX + size.x < 50f) newX = 50f - size.x
        if (newX > screenWidth - 50f) newX = screenWidth - 50f

        if (newY < 0f) newY = 0f
        if (newY > screenHeight - 50f) newY = screenHeight - 50f

        if (newX != pos.x || newY != pos.y) {
            ImGui.setWindowPos(newX, newY)
        }
    }

    override fun onCapture() {
        val merge = NormalMerger.stats()
        println(
            "[CAPTURE-STATS] terrain=${RenderMetrics.terrainOpaqueDrawCalls}+${RenderMetrics.terrainAlphaDrawCalls} " +
                "objectsIndirect=${RenderMetrics.indirectDrawCalls} " +
                "indirectCommands=${RenderMetrics.indirectCommands} " +
                "staticBatch=${RenderMetrics.staticBatchDrawCalls} " +
                "instanced=${RenderMetrics.instancedDrawCalls} " +
                "sky=${RenderMetrics.skyDrawCalls} " +
                "batchMeshes=${RenderMetrics.staticBatchMeshCount} " +
                "triangles=${RenderMetrics.trianglesSubmitted}"
        )
        val objects = asyncRegionManager.centerObjects
        println(
            "[CAPTURE-STATS] droppedInstances=${RenderMetrics.droppedInstances} " +
                "droppedModels=${RenderMetrics.droppedModelIds} " +
                "unregistered=${RenderMetrics.droppedModelIds - RenderMetrics.droppedModelsUnavailable} " +
                "centerObjects=${objects.size} mirrored=${objects.count { it.mirrored }}"
        )
        println(
            "[CAPTURE-STATS] normalMerge enabled=${NormalMerger.enabled} " +
                "eligible=${merge.eligibleInstances} merged=${merge.mergedInstances} " +
                "signatures=${merge.distinctSignatures} " +
                "cost=%.1fms".format(merge.totalMillis)
        )
        val cam = scene.camera
        println(
            "[CAPTURE-STATS] camera pos=(%.1f, %.1f, %.1f) yaw=%.1f pitch=%.1f front=(%.3f, %.3f, %.3f) fov=%.1f"
                .format(
                    cam.position.x, cam.position.y, cam.position.z, cam.yaw, cam.pitch,
                    cam.front.x, cam.front.y, cam.front.z, cam.fov
                )
        )
        System.out.flush()
    }

    override fun onCleanup() {
        if (hasUnsavedEdits()) {
            System.err.println(
                "[MapEditor] Discarding unsaved edits on exit: ${unsavedEditSummary()}. " +
                        "The map editor has no cache write-back path, so these could not be saved."
            )
        }
        gpuTimers.cleanup()
        cleanupRegion()
        asyncRegionManager.destroy()
        uniformBuffers.cleanup()
        uboColorShader.cleanup()
        uboTexturedShader.cleanup()
        terrainAlphaShader.cleanup()
        instancedColorShader.cleanup()
        instancedTexturedShader.cleanup()
        indirectColorShader?.cleanup()
        indirectTexturedShader?.cleanup()
        staticColorShader.cleanup()
        staticTexturedShader.cleanup()
        terrainAtlasShader?.cleanup()
        terrainAtlasAlphaShader?.cleanup()
        brushPreviewShader.cleanup()
        sky?.cleanup()
        sky = null
        brushPreviewMesh?.cleanup()
        brushPreviewMesh = null
        moveOriginMesh?.cleanup()
        moveOriginMesh = null
        terrainAtlas?.cleanup()
        terrainAtlas = null
        objectAtlas?.cleanup()
        objectAtlas = null
        TerrainLoader.atlasLookup = null
        TerrainLoader.clearCache()
        RegionLoader.clearCache()
        imGui.cleanup()
    }

    private enum class EditorTool(val label: String, val wip: Boolean = true) {
        SELECT("Select", wip = false),
        OBJECT_PLACE("Place Object", wip = false),
        OBJECT_MOVE("Move Object", wip = false),
        TERRAIN_PAINT("Paint Terrain", wip = false),
        HEIGHT_EDIT("Edit Height", wip = false),
        FLAG_EDIT("Edit Flags", wip = false)
    }
}

private const val DEFAULT_RANGE_FUSE_GAP = 12000

private const val EDIT_PROBE_SETTLE_FRAMES = 90

private fun regionProperty(): Pair<Int, Int>? {
    val parts = System.getProperty("darkan.region")?.split(',') ?: return null
    if (parts.size < 2) return null
    val x = parts[0].trim().toIntOrNull() ?: return null
    val y = parts[1].trim().toIntOrNull() ?: return null
    return x to y
}
