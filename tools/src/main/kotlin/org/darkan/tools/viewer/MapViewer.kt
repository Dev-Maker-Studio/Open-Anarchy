package org.darkan.tools.viewer

import imgui.ImGui
import imgui.flag.ImGuiCol
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
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL13.GL_MULTISAMPLE
import org.lwjgl.opengl.GL13.GL_TEXTURE0
import org.lwjgl.opengl.GL13.glActiveTexture
import world.gregs.voidps.cache.Cache

/**
 * Map/terrain viewer with full scene rendering support.
 *
 * Features:
 * - Single-region terrain rendering with actual underlay/overlay colors
 * - XTEA-encrypted object loading
 * - Multi-plane object rendering (renders ALL planes 0-3 simultaneously)
 * - Roof toggling (hide shapes 12-21 to see inside buildings)
 * - Plane selection (0-3) for terrain and bridge shifting
 * - Quick navigation to known locations
 * - Integrated map editor panel with inspection tools
 *
 * Controls:
 * - WASD: Move camera
 * - Space/Shift: Up/Down
 * - Right-click drag: Look around
 * - Scroll: Zoom
 * - Ctrl: Fast movement
 * - Arrow Keys: Navigate regions
 * - PgUp/PgDn: Change plane
 * - 1-4: Select plane directly
 * - F1: Toggle debug panel
 * - F3: Wireframe mode
 * - F5: Toggle map editor panel
 * - T: Toggle terrain
 * - R: Toggle roofs (hide to see inside buildings)
 * - G: Toggle grid
 */
class MapViewer(
    private val cache: Cache
) : Engine(WindowConfig(title = "Darkan Map Viewer", width = 1280, height = 720)) {

    // UBO-based shaders for efficient rendering
    private lateinit var uniformBuffers: UniformBuffers
    private lateinit var uboColorShader: ShaderProgram
    private lateinit var uboTexturedShader: ShaderProgram
    private lateinit var terrainAlphaShader: ShaderProgram

    // Atlas shaders for unified terrain rendering
    private var terrainAtlasShader: ShaderProgram? = null
    private var terrainAtlasAlphaShader: ShaderProgram? = null

    // Terrain texture atlas
    private var terrainAtlas: TextureAtlas? = null
    private val SENTINEL_TEXTURE_ID = Int.MAX_VALUE

    // Object texture atlas for collapsing texture-based draw call splits.
    // Built incrementally as models are registered with the InstanceManager.
    // Separate from the terrain atlas (different lifecycle and texture set).
    private var objectAtlas: TextureAtlas? = null

    // Async region manager: handles loading, object creation, displacement,
    // static batching, and dynamic render distance for all regions.
    // Center region loads synchronously; context regions load in the background.
    private lateinit var asyncRegionManager: AsyncRegionManager

    // Instanced shaders for object rendering (center region)
    private lateinit var instancedColorShader: ShaderProgram
    private lateinit var instancedTexturedShader: ShaderProgram

    // Multi-Draw Indirect shaders and renderer for center region objects (GL 4.3).
    // When available, replaces per-mesh instanced draws with 1-3 MDI calls.
    // Falls back to InstanceManager if initialization fails.
    //
    // Multi-Draw Indirect path for center-region objects. Enabled by default; set
    // -Ddarkan.indirect=false to force the legacy InstanceManager path. Also disabled
    // automatically when the GL context is too old (see IndirectRenderer.isSupported)
    // or when shader compilation / geometry build fails, in which case renderObjects()
    // falls through to the InstanceManager path unchanged.
    private var useIndirectRendering = System.getProperty("darkan.indirect", "true").toBoolean()
    private var indirectColorShader: ShaderProgram? = null
    private var indirectTexturedShader: ShaderProgram? = null
    private var indirectRenderer: IndirectRenderer? = null

    // ---- Culling -------------------------------------------------------
    //
    // Center-region objects are culled on the GPU: their transforms and world bounding
    // spheres are uploaded once per scene and a compute shader rebuilds the indirect draw
    // commands every frame, so the CPU never decides what is visible and never reads the
    // result back. Context regions and their static batches are culled on the CPU instead,
    // where the granularity is a whole merged mesh and a handful of AABB tests replaces
    // hundreds of draw calls.
    //
    // -Ddarkan.gpuCull=false draws everything, for A/B comparison against the culled image.
    // -Ddarkan.staticInstances=false goes back to streaming instance transforms every
    // frame (which cannot be culled), keeping the previous path exercisable.
    private val useGpuCulling = System.getProperty("darkan.gpuCull", "true").toBoolean()
    private val useStaticInstances = System.getProperty("darkan.staticInstances", "true").toBoolean()
    private val useCpuBatchCulling = System.getProperty("darkan.batchCull", "true").toBoolean()

    /**
     * Whether context regions draw their terrain out of the merged atlas buffers.
     *
     * `-Ddarkan.mergeContextTerrain=false` restores the previous arrangement, where the
     * merged meshes were submitted whole and every context region additionally drew its own
     * per-texture terrain batches on top. Kept switchable because it is the only way to A/B
     * the merged terrain against what it replaced from a single build.
     */
    private val mergeContextTerrain = System.getProperty("darkan.mergeContextTerrain", "true").toBoolean()

    private val frustum = Frustum()
    private val frustumViewProjection = Matrix4f()
    private val frustumPlanes = FloatArray(24)

    /** Every ready context region this frame. Refilled every frame. */
    private val readyContextRegions = mutableListOf<AsyncRegionData>()

    /** Context regions whose terrain survived the frustum test. Refilled every frame. */
    private val visibleContextRegions = mutableListOf<AsyncRegionData>()

    /** Set when the object set changes, so the static instance buffers are rebuilt. */
    private var objectSceneDirty = true

    /** Compute shader sources for the culling passes, loaded once. */
    private var cullShaderSource: String? = null
    private var cullResetShaderSource: String? = null

    /** Per-pass GPU timers. Results lag by a few frames and never stall the pipeline. */
    private val gpuTimers = GpuTimerPool(RenderMetrics.GPU_PASS_NAMES)

    // Non-instanced UBO shaders for static batched context objects
    private lateinit var staticColorShader: ShaderProgram
    private lateinit var staticTexturedShader: ShaderProgram

    // UI
    private lateinit var imGui: ImGuiLayer
    private lateinit var debugPanel: DebugPanel
    private lateinit var loadingScreen: LoadingScreen

    // Current state. -Ddarkan.region=<x>,<y> picks the region to open on, so a
    // specific area can be brought up without driving the UI (e.g. Canifis = 54,54).
    private var currentRegionX = regionProperty()?.first ?: 52
    private var currentRegionY = regionProperty()?.second ?: 50
    private var currentPlane = 0

    // UI state
    private val regionXInput = ImInt(50)
    private val regionYInput = ImInt(50)
    // Capture-harness isolation switches, same family as -Ddarkan.capture / -Ddarkan.hideUi:
    // `-Ddarkan.objects=false` renders terrain alone and `-Ddarkan.terrain=false` renders
    // objects alone, which is the only way to tell from a screenshot whether a given piece of
    // geometry came from the ground mesh or from a placed model.
    private var showTerrain = System.getProperty("darkan.terrain", "true").toBoolean()
    private val showObjects = System.getProperty("darkan.objects", "true").toBoolean()
    private var showRoofs = true  // Toggle roof visibility (shapes 12-21)
    private val renderDistanceInput = intArrayOf(1)  // Dynamic render distance (0-5)

    // Sun / fog / skybox for the region being viewed. Seeded from the map archive on every
    // region change, then live-editable from the Environment section of the editor panel.
    private val environment = EnvironmentSettings()

    // Skybox pass. Drawn after the opaque scene so covered pixels are depth-rejected;
    // with no skybox reference it degrades to a flat fill in the fog colour.
    private var sky: SkyRenderer? = null

    // Only ever seen for the single frame before the sky pass runs, so it tracks the fog
    // colour rather than sitting on a hardcoded blue.
    private val clearColor = Vector3f()

    // Known regions for quick navigation
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

    // Region rendering state -- terrain edit manager for the center region
    private var editManager: TerrainEditManager? = null

    // Pre-allocated texture binding callback to avoid per-frame lambda allocation
    private val terrainTextureBinding: (Int) -> Unit = { texId ->
        TextureLoader.bindTexture(TextureLoader.getTextureForMaterial(cache, texId))
    }

    // Tile picker for debugging UV issues
    private val tilePicker = TilePicker()
    private var tileHighlightMesh: Mesh? = null

    // Map editor panel state
    private var showEditorPanel = true
    private var editorPanelFirstFrame = true
    private var currentEditorTool = EditorTool.SELECT

    // Editor panel input state
    private val editorObjectSearchQuery = ImString(256)
    private val editorOverlayUnderlayId = ImInt(0)
    private var editorPaintMode = 0  // 0 = Overlay, 1 = Underlay
    private val editorBrushSize = intArrayOf(1)
    private val editorHeightBrushRadius = intArrayOf(1)
    private val editorHeightStrength = intArrayOf(5)
    private var editorHeightMode = 0  // 0 = Raise, 1 = Lower, 2 = Smooth

    // Overlay shape/rotation for terrain paint tool
    private val editorOverlayShape = intArrayOf(0)
    private val editorOverlayRotation = intArrayOf(0)

    // Drag tracking for continuous tools (prevents re-painting same tile)
    private val editedTilesThisDrag = mutableSetOf<Pair<Int, Int>>()

    // Object place tool state
    private var objectSearchResults: List<ObjectDefinition> = emptyList()
    private var selectedObjectId = -1
    private val editorObjectShape = intArrayOf(10)  // Default to CENTREPIECE_STRAIGHT
    private val editorObjectRotation = intArrayOf(0)

    // Object move tool state
    private data class MovingObject(
        val instance: ObjectInstance,
        val originalX: Int,
        val originalY: Int
    )
    private var movingObject: MovingObject? = null
    private var moveOriginMesh: Mesh? = null

    // Placed objects (user-placed via Object Place tool)
    private val placedObjects = mutableListOf<ObjectInstance>()

    // Environment panel scratch buffers. Refreshed from [environment] every frame so a
    // region change is picked up, and written back whenever a widget reports a change.
    private val envSunColourInput = FloatArray(3)
    private val envFogColourInput = FloatArray(3)
    private val envSunPositionInput = FloatArray(3)
    private val envSunAmbientInput = FloatArray(1)
    private val envSunLightInput = FloatArray(1)
    private val envSunBacklightInput = FloatArray(1)
    private val envFogDepthInput = intArrayOf(0)
    private val envViewRangeInput = FloatArray(1)
    private val envSkyboxInput = ImInt(-1)

    // Cache write-back state. A plan is only ever produced by pressing "Preview changes",
    // and only ever applied by pressing the save button with the confirmation ticked -
    // nothing here runs on a region change, on a panel edit or on shutdown.
    private var envWritePlan: EnvironmentWritePlan? = null
    private var envWritePlanRegion: Int = -1
    private val envWriteConfirm = ImBoolean(false)
    private var envWriteLog: List<String> = emptyList()
    private var envWriteError: String? = null

    // Brush preview state
    private var brushPreviewMesh: Mesh? = null
    private var lastBrushCenter: Pair<Int, Int>? = null
    private lateinit var brushPreviewShader: ShaderProgram

    override fun onInit() {
        // Initialize texture loader
        TextureLoader.init(cache)

        // Initialize ImGui
        imGui = ImGuiLayer(window)
        imGui.init()
        debugPanel = DebugPanel()

        // Initialize uniform buffers
        uniformBuffers = UniformBuffers()
        uniformBuffers.init()

        // Load UBO shaders
        loadUBOShaders()

        // Skybox pass. One attribute-less triangle; the material texture (when a region
        // references a skybox) is built lazily and cached inside the renderer.
        sky = SkyRenderer(cache).also {
            it.init()
            // renderScene() always leaves the scene's steady state behind before the sky
            // pass, so the renderer can skip its synchronous state readback.
            it.stateRestore = SkyStateRestore.ASSUME_SCENE_DEFAULTS
        }

        // With dynamic sun lighting on, terrain meshes must be built *unlit* so the shader
        // can light them. Has to be set before the first region build.
        TerrainLoader.bakeSunLighting = !environment.dynamicSunLighting

        // Initialize async region manager (loads definitions and XTEA keys)
        asyncRegionManager = AsyncRegionManager(cache)
        asyncRegionManager.initialize()

        // Build terrain texture atlas from all overlay/underlay texture definitions
        buildTerrainAtlas()

        // Object atlas disabled: the CPU-side UV remap (remapUVsToAtlas) cannot handle
        // model UVs outside [0,1] which rely on GL_REPEAT for tiling. This causes wrong
        // UV projections and spurious transparency when remapped UVs sample empty atlas space.
        // To re-enable, this needs shader-side fract()+remap like the terrain atlas does.
        objectAtlas = null

        // Wire async region manager to atlas references
        asyncRegionManager.objectAtlas = objectAtlas
        asyncRegionManager.terrainAtlas = terrainAtlas

        loadingScreen = LoadingScreen()

        // Load brush preview shader (RGBA vertex colors for semi-transparent overlay)
        val brushVertSrc = loadShaderSource("/shaders/brush_preview.vert")
        val brushFragSrc = loadShaderSource("/shaders/brush_preview.frag")
        brushPreviewShader = ShaderProgram.createWithUniforms(
            brushVertSrc, brushFragSrc,
            "model", "view", "projection"
        )

        // Setup the scene camera (will be repositioned when region loads)
        scene.camera.setPosition(0f, 50f, 50f)
        scene.camera.pitch = -45f
        scene.camera.yaw = -90f
        scene.camera.moveSpeed = 20f
        scene.camera.nearPlane = 1.0f
        scene.camera.farPlane = 4000f

        // Enable depth testing and multisampling
        glEnable(GL_DEPTH_TEST)
        glEnable(GL_MULTISAMPLE)

        // -Ddarkan.hideUi=true drops both panels. A capture taken for pixel-diffing has to
        // contain nothing but the 3D scene: the panels report per-run counters, so leaving
        // them in makes every comparison differ for reasons that have nothing to do with
        // what was drawn.
        if (System.getProperty("darkan.hideUi", "false").toBoolean()) {
            debugPanel.visible = false
            showEditorPanel = false
        }

        // `-Ddarkan.wireframe=true` starts in line mode. Missing geometry and geometry that
        // is drawn but shaded to a dark colour look identical in a filled frame; in wireframe
        // the first leaves a gap in the edge grid and the second does not, which decides
        // between them from a single capture instead of by argument.
        if (System.getProperty("darkan.wireframe", "false").toBoolean()) {
            renderSystem.setWireframe(true)
        }

        // GPU pass timing. Harvested several frames after the fact so the queries never
        // force a sync; without this every performance claim rests on CPU timings alone.
        RenderMetrics.gpuTimingActive = gpuTimers.init()

        // Opening render distance, so a measurement run does not have to drive the UI.
        System.getProperty("darkan.renderDistance")?.toIntOrNull()?.let {
            renderDistanceInput[0] = it.coerceIn(0, 5)
        }

        isInitialLoading = true
        loadRegionAsync(currentRegionX, currentRegionY, currentPlane)
    }

    /**
     * Build the terrain texture atlas containing all overlay/underlay textures.
     * Scans all overlay and underlay definitions for unique texture IDs, generates
     * each texture's pixels via the procedural texture system, and packs them into
     * a single atlas. Also adds a 1x1 white sentinel pixel for untextured (color-only)
     * tiles so they can share the same shader as textured tiles.
     *
     * After building, wires up [TerrainLoader.atlasLookup] and
     * [TerrainLoader.sentinelAtlasTransform] so that [TerrainLoader.computeTerrainMeshData]
     * produces per-vertex atlas UV transforms.
     */
    private fun buildTerrainAtlas() {
        // -Ddarkan.terrainAtlas=false keeps every terrain batch on its own texture bind,
        // the way the viewer drew terrain before the atlas existed. Slow, but it is the
        // reference image: no atlas sampling, no merging, every layer composited once.
        //
        // It is a usable reference because it is the same geometry: the centre region's
        // per-batch meshes come from TerrainEditManager and the merged atlas mesh from
        // TerrainLoader.computeTerrainMeshData, and both tessellate through
        // generateTileVertices with the same opaque/alpha split, so the two paths submit
        // the same triangle count. Measured against a same-flags control pair (5e-05 to
        // 1.2e-04 mean abs, 1280x720), atlas vs per-texture comes to 8e-04 (Sophanem
        // 52,50), 3e-03 (Canifis 54,54), 1e-02 (Lumbridge 50,50) and 0.11 (Lumbridge with
        // context regions) mean abs of 255 -- the residue of atlas sampling, not a
        // different picture. Draw counts differ by design, pixels should not.
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

        // Pixel generation is pure CPU: it decodes a material's operation DAG and rasterises
        // it into an IntArray without touching GL state. It ran here one texture at a time on
        // the GL thread, with the other fifteen cores idle, because TextureRaster kept its
        // scratch state in shared statics and generating two materials at once corrupted
        // both. That state now lives on the operation objects -- verified with 8 threads over
        // 5 rounds of the same materials, no failures and zero pixel mismatches against a
        // single-threaded baseline -- so the work can fan out.
        //
        // Packing stays serial and stays in sortedIds order. The atlas shelf allocator places
        // each texture relative to the ones already in the page, so packing order decides the
        // UV transform every terrain vertex is given. Generate in whatever order the workers
        // finish; insert in exactly the order this loop always used.
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

        // Add sentinel white pixel texture for color-only tiles.
        // A 1x1 pure white pixel means texColor.rgb = (1,1,1), so
        // finalColor = texColor * vertexColor = vertexColor (correct for untextured tiles).
        val whitePixel = intArrayOf(0xFFFFFFFF.toInt())
        atlas.addTexture(SENTINEL_TEXTURE_ID, whitePixel, 1, 1)

        // Upload to GPU
        atlas.upload()

        val stats = atlas.getStats()
        println("[MapViewer] Terrain atlas built: ${stats.textureCount} textures on ${stats.pageCount} page(s), " +
                "$loaded loaded, $failed failed, utilization ${(stats.utilization * 100).toInt()}%")

        // Overflow handling. The terrain shaders sample a single bound page, so only
        // textures that landed on page 0 can be reached through an atlas transform.
        //
        // The sentinel white pixel is what untextured tiles sample, so if the atlas
        // overflowed hard enough to push it off page 0 there is no atlas path at all and
        // the whole thing is abandoned in favour of the per-texture renderer. Individual
        // textures beyond page 0 are handled more gently: the lookup returns null for
        // them, which keeps their batches on the per-texture path and out of the merged
        // mesh. Either way the pixels stay right and the cost shows up as draw calls.
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

        // Wire up TerrainLoader atlas lookup so computeTerrainMeshData produces
        // per-vertex atlas transform arrays. A texture the atlas never managed to pack
        // falls back to the sentinel exactly as before -- that is a texture generation
        // failure, not an atlas capacity problem, and it has no pixels to show either way.
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
        // Load terrain color shader with RGB vertex colors
        val terrainColorVert = loadShaderSource("/shaders/terrain_color.vert")
        val terrainColorFrag = loadShaderSource("/shaders/terrain_color.frag")

        uboColorShader = ShaderProgram.createWithUniforms(
            terrainColorVert, terrainColorFrag,
            "model"
        )
        uniformBuffers.bindToShader(uboColorShader)

        // Load terrain textured shader with RGB vertex colors
        val terrainTexturedVert = loadShaderSource("/shaders/terrain_textured.vert")
        val terrainTexturedFrag = loadShaderSource("/shaders/terrain_textured.frag")

        uboTexturedShader = ShaderProgram.createWithUniforms(
            terrainTexturedVert, terrainTexturedFrag,
            "model", "textureSampler"
        )
        uniformBuffers.bindToShader(uboTexturedShader)

        // Load terrain alpha shader for multi-pass alpha blending of underlay textures
        val terrainAlphaVert = loadShaderSource("/shaders/terrain_alpha.vert")
        val terrainAlphaFrag = loadShaderSource("/shaders/terrain_alpha.frag")

        terrainAlphaShader = ShaderProgram.createWithUniforms(
            terrainAlphaVert, terrainAlphaFrag,
            "model", "textureSampler"
        )
        uniformBuffers.bindToShader(terrainAlphaShader)

        // Load instanced shaders (for center region objects - use standard RGB shaders)
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

        // Load Multi-Draw Indirect shaders (GL 4.3 SSBO-based).
        // These read instance matrices from an SSBO indexed by gl_BaseInstance + gl_InstanceID
        // instead of per-instance vertex attributes. Falls back gracefully if shaders fail.
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
                // One sampler per array texture; IndirectRenderer binds each to the matching
                // unit so a single draw can cover every material size at once.
                indirectTexturedShader = ShaderProgram.createWithUniforms(
                    indTexturedVert, indTexturedFrag,
                    *IndirectRenderer.textureArrayUniformNames()
                )
                uniformBuffers.bindToShader(indirectTexturedShader!!)

                // Compute sources for the culling passes. Kept as strings because the
                // IndirectRenderer is rebuilt on every region change and needs them again.
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

        // Load non-instanced UBO shaders for static batched context region objects.
        // These use the same UBO blocks as instanced shaders but take a model uniform
        // instead of per-instance matrix attributes.
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

        // Load atlas terrain shaders
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
        // Section 1: Keyboard input -- only gated by wantCaptureKeyboard
        // Camera movement, toggles, plane nav, and escape-to-cancel still work
        // even when the mouse is hovering over an ImGui panel.
        if (!imGui.wantCaptureKeyboard()) {
            val cam = scene.camera

            // Speed multiplier
            val speedMult = if (input.keyboard.isKeyDown(GLFW_KEY_LEFT_CONTROL)) 4f else 1f
            cam.moveSpeed = 20f * speedMult

            // Camera movement
            if (input.keyboard.isKeyDown(GLFW_KEY_W)) cam.moveForward(deltaTime)
            if (input.keyboard.isKeyDown(GLFW_KEY_S)) cam.moveBackward(deltaTime)
            if (input.keyboard.isKeyDown(GLFW_KEY_A)) cam.moveLeft(deltaTime)
            if (input.keyboard.isKeyDown(GLFW_KEY_D)) cam.moveRight(deltaTime)
            if (input.keyboard.isKeyDown(GLFW_KEY_SPACE)) cam.moveUp(deltaTime)
            if (input.keyboard.isKeyDown(GLFW_KEY_LEFT_SHIFT)) cam.moveDown(deltaTime)

            // Toggle keys
            if (input.keyboard.isKeyPressed(GLFW_KEY_F1)) debugPanel.toggleVisibility()
            if (input.keyboard.isKeyPressed(GLFW_KEY_F3)) renderSystem.toggleWireframe()
            if (input.keyboard.isKeyPressed(GLFW_KEY_T)) showTerrain = !showTerrain
            if (input.keyboard.isKeyPressed(GLFW_KEY_R)) {
                showRoofs = !showRoofs
                asyncRegionManager.showRoofs = showRoofs
                reloadRegion()
            }

            // Plane selection (1-4 keys)
            if (input.keyboard.isKeyPressed(GLFW_KEY_1)) setPlane(0)
            if (input.keyboard.isKeyPressed(GLFW_KEY_2)) setPlane(1)
            if (input.keyboard.isKeyPressed(GLFW_KEY_3)) setPlane(2)
            if (input.keyboard.isKeyPressed(GLFW_KEY_4)) setPlane(3)

            // Region navigation (arrow keys)
            if (input.keyboard.isKeyPressed(GLFW_KEY_LEFT)) navigateRegion(-1, 0)
            if (input.keyboard.isKeyPressed(GLFW_KEY_RIGHT)) navigateRegion(1, 0)
            if (input.keyboard.isKeyPressed(GLFW_KEY_UP)) navigateRegion(0, 1)
            if (input.keyboard.isKeyPressed(GLFW_KEY_DOWN)) navigateRegion(0, -1)

            // Plane navigation (PgUp/PgDn)
            if (input.keyboard.isKeyPressed(GLFW_KEY_PAGE_UP)) {
                if (currentPlane < 3) setPlane(currentPlane + 1)
            }
            if (input.keyboard.isKeyPressed(GLFW_KEY_PAGE_DOWN)) {
                if (currentPlane > 0) setPlane(currentPlane - 1)
            }

            // Toggle map editor panel (F5)
            if (input.keyboard.isKeyPressed(GLFW_KEY_F5)) {
                showEditorPanel = !showEditorPanel
                if (showEditorPanel) {
                    editorPanelFirstFrame = true
                }
            }

            // Escape to cancel object move
            if (input.keyboard.isKeyPressed(GLFW_KEY_ESCAPE) && movingObject != null) {
                val moving = movingObject!!
                // Restore original world position
                val tileSize = 512f * CoordinateUtil.DEFAULT_SCALE
                val origWorldX = currentRegionX * 64 + moving.originalX
                val origWorldY = currentRegionY * 64 + moving.originalY
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
                // Restore Y from terrain heights
                recalcObjectHeight(moving.instance)
                movingObject = null
                moveOriginMesh?.cleanup()
                moveOriginMesh = null
            }
        }

        // Section 2: Camera mouse input -- gated by wantCaptureMouse
        if (!imGui.wantCaptureMouse()) {
            val cam = scene.camera

            // Camera rotation
            if (input.mouse.isButtonDown(GLFW_MOUSE_BUTTON_RIGHT)) {
                cam.rotate(input.mouse.deltaX.toFloat(), input.mouse.deltaY.toFloat())
            }

            // Scroll zoom
            if (input.mouse.scrollY != 0.0) {
                cam.zoom(input.mouse.scrollY.toFloat())
            }
        }

        // Section 3: Tool left-click dispatch -- gated by wantCaptureMouse
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

    /**
     * Handle tile picking via left-click.
     */
    private fun handleTilePick() {
        val td = asyncRegionManager.centerTerrainData ?: return
        val cam = scene.camera
        val tileSize = 512f * CoordinateUtil.DEFAULT_SCALE

        // Set the world offset for the tile picker so it converts world-space
        // ray positions into local tile coordinates (0-63)
        tilePicker.worldOffsetX = currentRegionX * 64 * tileSize
        tilePicker.worldOffsetZ = -currentRegionY * 64 * tileSize

        // Convert mouse position to world ray
        tilePicker.screenToWorldRay(
            input.mouse.x.toFloat(),
            input.mouse.y.toFloat(),
            window.width,
            window.height,
            cam,
            getAspectRatio()
        )

        // Pick tile
        val selectedTile = tilePicker.pickTile(
            td.tiles,
            currentRegionX,
            currentRegionY,
            currentPlane
        )

        if (selectedTile != null) {
            // Update tile details with object lookup
            tilePicker.updateTileDetails(
                td.tiles,
                cache,
                asyncRegionManager.centerMapObjects,
                currentRegionX * 64,
                currentRegionY * 64
            )

            // Update highlight mesh
            updateTileHighlight(td.tiles)
        }
    }

    /**
     * Perform a raycast to find the tile coordinate under the cursor.
     * Unlike handleTilePick(), this does NOT update selection state or highlight mesh.
     */
    private fun getTileUnderCursor(): Pair<Int, Int>? {
        val td = asyncRegionManager.centerTerrainData ?: return null
        val cam = scene.camera
        val tileSize = 512f * CoordinateUtil.DEFAULT_SCALE

        tilePicker.worldOffsetX = currentRegionX * 64 * tileSize
        tilePicker.worldOffsetZ = -currentRegionY * 64 * tileSize

        tilePicker.screenToWorldRay(
            input.mouse.x.toFloat(),
            input.mouse.y.toFloat(),
            window.width,
            window.height,
            cam,
            getAspectRatio()
        )

        val picked = tilePicker.pickTile(
            td.tiles,
            currentRegionX,
            currentRegionY,
            currentPlane
        ) ?: return null

        return Pair(picked.localX, picked.localY)
    }

    /**
     * Handle terrain painting. Called continuously while mouse button is held.
     * Uses editorBrushSize radius, applies overlay or underlay based on editorPaintMode.
     */
    private fun handleTerrainPaint() {
        val em = editManager ?: return
        val center = getTileUnderCursor() ?: return
        val radius = editorBrushSize[0] - 1  // brush size 1 = single tile (radius 0)
        val id = editorOverlayUnderlayId.get()

        for (dx in -radius..radius) {
            for (dy in -radius..radius) {
                val tx = center.first + dx
                val ty = center.second + dy
                if (tx < 0 || tx > 63 || ty < 0 || ty > 63) continue
                val key = Pair(tx, ty)
                if (key in editedTilesThisDrag) continue
                editedTilesThisDrag.add(key)

                if (editorPaintMode == 0) {
                    // Overlay
                    em.mutableData.setOverlayId(
                        currentPlane, tx, ty,
                        id,
                        editorOverlayShape[0],
                        editorOverlayRotation[0]
                    )
                } else {
                    // Underlay
                    em.mutableData.setUnderlayId(currentPlane, tx, ty, id)
                }
            }
        }
    }

    /**
     * Handle height editing. Called continuously while mouse button is held.
     * Applies raise, lower, or smooth based on editorHeightMode.
     * No drag throttle -- continuous application for sculpting feel.
     */
    private fun handleHeightEdit() {
        val em = editManager ?: return
        val center = getTileUnderCursor() ?: return
        val radius = editorHeightBrushRadius[0]
        val strength = editorHeightStrength[0]

        when (editorHeightMode) {
            0, 1 -> {
                // Raise (0) or Lower (1)
                // RS heights are negative (higher terrain = more negative)
                val sign = if (editorHeightMode == 0) -1 else 1
                for (dx in -radius..radius) {
                    for (dy in -radius..radius) {
                        val tx = center.first + dx
                        val ty = center.second + dy
                        if (tx < 0 || tx > 63 || ty < 0 || ty > 63) continue

                        // Distance-based falloff
                        val dist = sqrt((dx * dx + dy * dy).toFloat())
                        val falloff = (1f - dist / (radius + 1)).coerceIn(0f, 1f)
                        val delta = (strength * falloff * sign).toInt()

                        val current = em.mutableData.tiles[currentPlane][tx][ty].height
                        em.mutableData.setHeight(currentPlane, tx, ty, current + delta)
                    }
                }
            }
            2 -> {
                // Smooth: average height in radius, move each tile halfway toward average
                var sum = 0L
                var count = 0
                for (dx in -radius..radius) {
                    for (dy in -radius..radius) {
                        val tx = center.first + dx
                        val ty = center.second + dy
                        if (tx < 0 || tx > 63 || ty < 0 || ty > 63) continue
                        sum += em.mutableData.tiles[currentPlane][tx][ty].height
                        count++
                    }
                }
                if (count == 0) return
                val avg = (sum / count).toInt()

                for (dx in -radius..radius) {
                    for (dy in -radius..radius) {
                        val tx = center.first + dx
                        val ty = center.second + dy
                        if (tx < 0 || tx > 63 || ty < 0 || ty > 63) continue
                        val current = em.mutableData.tiles[currentPlane][tx][ty].height
                        val newHeight = current + (avg - current) / 2
                        em.mutableData.setHeight(currentPlane, tx, ty, newHeight)
                    }
                }
            }
        }
    }

    /**
     * Recompute Y positions for all center objects using current mutable tile heights.
     * Called when terrain height edits occur so objects follow the terrain.
     *
     * The height sampling algorithm matches ObjectRenderer.createInstancesAllPlanes():
     * for objects fitting within the region, it samples the center tiles of the
     * bounding box rather than the full footprint corners.
     */
    private fun updateObjectHeights() {
        val em = editManager ?: return
        val heightScale = TerrainLoader.getHeightScale()
        val baseWorldX = currentRegionX * 64
        val baseWorldY = currentRegionY * 64

        for (obj in asyncRegionManager.centerObjects) {
            val mo = obj.mapObject
            val def = obj.definition

            // Convert to local tile coordinates
            val localTileX = mo.x - baseWorldX
            val localTileY = mo.y - baseWorldY
            if (localTileX < 0 || localTileX >= 64 || localTileY < 0 || localTileY >= 64) continue

            // Calculate object size (may be rotated)
            val sizeX: Int
            val sizeY: Int
            if (mo.rotation == 1 || mo.rotation == 3) {
                sizeX = def.sizeY
                sizeY = def.sizeX
            } else {
                sizeX = def.sizeX
                sizeY = def.sizeY
            }

            // Calculate center tile coordinates for height sampling (matching ObjectRenderer)
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

            // Clamp to valid tile range
            val x0 = xA.coerceIn(0, 63)
            val x1 = xB.coerceIn(0, 63)
            val y0 = yA.coerceIn(0, 63)
            val y1 = yB.coerceIn(0, 63)

            // Average height of 4 sample points (matching client algorithm)
            val plane = mo.level
            val h00 = em.mutableData.tiles[plane][x0][y0].height * heightScale
            val h10 = em.mutableData.tiles[plane][x1][y0].height * heightScale
            val h01 = em.mutableData.tiles[plane][x0][y1].height * heightScale
            val h11 = em.mutableData.tiles[plane][x1][y1].height * heightScale
            val newHeight = (h00 + h10 + h01 + h11) / 4f

            // Update Y position (worldPosition is a mutable var Vector3f)
            obj.worldPosition.y = newHeight
            objectSceneDirty = true

            // Recompute terrain alignment for type 3 objects (hillChange tilt)
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
                        val cx = tx.coerceIn(0, 63)
                        val cy = ty.coerceIn(0, 63)
                        em.mutableData.tiles[plane][cx][cy].height * heightScale
                    }
                )
            }
        }
    }

    /**
     * Search object definitions by name (case-insensitive substring) or exact ID.
     * Returns at most [limit] results.
     */
    private fun searchObjectDefinitions(query: String, limit: Int = 50): List<ObjectDefinition> {
        val defs = Cache.objects
        val results = mutableListOf<ObjectDefinition>()

        // Try exact ID match first
        val idMatch = query.toIntOrNull()
        if (idMatch != null && idMatch in defs.indices) {
            val def = defs[idMatch]
            if (def.name != "null") results.add(def)
        }

        // Name search
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

    /**
     * Compute world-space position for an object at a given local tile coordinate.
     * Follows the same algorithm as ObjectRenderer.createInstances() for consistent positioning.
     *
     * @return Triple of (worldPosition, sizeX, sizeY) where sizes account for rotation
     */
    private fun computeObjectWorldPosition(
        localX: Int,
        localY: Int,
        def: ObjectDefinition,
        rotation: Int
    ): Triple<Vector3f, Int, Int> {
        val tileSize = 512f * CoordinateUtil.DEFAULT_SCALE
        val heightScale = TerrainLoader.getHeightScale()
        val em = editManager

        val sizeX: Int
        val sizeY: Int
        if (rotation == 1 || rotation == 3) {
            sizeX = def.sizeY
            sizeY = def.sizeX
        } else {
            sizeX = def.sizeX
            sizeY = def.sizeY
        }

        // Height sampling from 4 center tiles (matching ObjectRenderer)
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
        val x0 = xA.coerceIn(0, 63)
        val x1 = xB.coerceIn(0, 63)
        val y0 = yA.coerceIn(0, 63)
        val y1 = yB.coerceIn(0, 63)

        val terrainHeight = if (em != null) {
            val h00 = em.mutableData.tiles[currentPlane][x0][y0].height * heightScale
            val h10 = em.mutableData.tiles[currentPlane][x1][y0].height * heightScale
            val h01 = em.mutableData.tiles[currentPlane][x0][y1].height * heightScale
            val h11 = em.mutableData.tiles[currentPlane][x1][y1].height * heightScale
            (h00 + h10 + h01 + h11) / 4f
        } else 0f

        val worldTileX = currentRegionX * 64 + localX
        val worldTileY = currentRegionY * 64 + localY
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

    /**
     * Recalculate the Y (height) position for a single ObjectInstance
     * using current mutable tile data.
     */
    private fun recalcObjectHeight(obj: ObjectInstance) {
        val em = editManager ?: return
        val heightScale = TerrainLoader.getHeightScale()
        val baseWorldX = currentRegionX * 64
        val baseWorldY = currentRegionY * 64
        val mo = obj.mapObject
        val def = obj.definition

        val localTileX = mo.x - baseWorldX
        val localTileY = mo.y - baseWorldY
        if (localTileX < 0 || localTileX >= 64 || localTileY < 0 || localTileY >= 64) return

        val sizeX: Int
        val sizeY: Int
        if (mo.rotation == 1 || mo.rotation == 3) {
            sizeX = def.sizeY
            sizeY = def.sizeX
        } else {
            sizeX = def.sizeX
            sizeY = def.sizeY
        }

        val xA = if (localTileX + sizeX <= 64) localTileX + (sizeX shr 1) else localTileX
        val xB = if (localTileX + sizeX <= 64) localTileX + ((sizeX + 1) shr 1) else localTileX + 1
        val yA = if (localTileY + sizeY <= 64) localTileY + (sizeY shr 1) else localTileY
        val yB = if (localTileY + sizeY <= 64) localTileY + ((sizeY + 1) shr 1) else localTileY + 1

        val x0 = xA.coerceIn(0, 63)
        val x1 = xB.coerceIn(0, 63)
        val y0 = yA.coerceIn(0, 63)
        val y1 = yB.coerceIn(0, 63)

        val plane = mo.level
        val h00 = em.mutableData.tiles[plane][x0][y0].height * heightScale
        val h10 = em.mutableData.tiles[plane][x1][y0].height * heightScale
        val h01 = em.mutableData.tiles[plane][x0][y1].height * heightScale
        val h11 = em.mutableData.tiles[plane][x1][y1].height * heightScale
        obj.worldPosition.y = (h00 + h10 + h01 + h11) / 4f
        objectSceneDirty = true
    }

    /**
     * Ensure the model for an object definition is registered with the InstanceManager.
     * Returns the combined model ID, or -1 if registration failed.
     */
    private fun ensureModelRegistered(def: ObjectDefinition, shape: Int): Int {
        val im = asyncRegionManager.instanceManager ?: return -1
        val modelIds = ObjectRenderer.getModelIds(def, shape)
        if (modelIds.isEmpty()) return -1

        val modifications = ObjectRenderer.getModifications(def)
        val combinedId = ModelLoader.getCombinedModelIdWithModifications(modelIds, modifications)

        if (!im.isModelRegistered(combinedId)) {
            val meshDataList = if (modifications.hasModifications()) {
                ModelLoader.createCombinedMeshDataListWithModifications(cache, modelIds, modifications)
            } else {
                ModelLoader.createCombinedMeshDataList(cache, modelIds)
            }
            if (meshDataList != null && meshDataList.isNotEmpty()) {
                im.registerModel(combinedId, meshDataList)
            } else {
                return -1
            }
        }

        return combinedId
    }

    /**
     * Handle object placement at the clicked tile.
     * Creates an ObjectInstance and adds it to the placedObjects list so it
     * renders in the scene alongside loaded map objects.
     */
    private fun handleObjectPlace() {
        if (selectedObjectId < 0) return
        val tile = getTileUnderCursor() ?: return

        val def = ObjectRenderer.getDefinition(selectedObjectId) ?: return
        val shape = editorObjectShape[0]
        val rotation = editorObjectRotation[0]

        // Ensure model is registered with InstanceManager
        val combinedId = ensureModelRegistered(def, shape)
        if (combinedId == -1) return

        val (worldPos, sizeX, sizeY) = computeObjectWorldPosition(tile.first, tile.second, def, rotation)

        val worldX = currentRegionX * 64 + tile.first
        val worldY = currentRegionY * 64 + tile.second
        val modelIds = ObjectRenderer.getModelIds(def, shape)
        val modifications = ObjectRenderer.getModifications(def)

        // Create a synthetic MapObject for the placed object
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

    /**
     * Start moving an object. Find the ObjectInstance at the clicked tile
     * from centerObjects, and store it along with its original local coordinates.
     */
    private fun handleObjectMoveStart() {
        val tile = getTileUnderCursor() ?: return
        val instances = asyncRegionManager.centerObjects

        val worldX = currentRegionX * 64 + tile.first
        val worldY = currentRegionY * 64 + tile.second

        // Find the ObjectInstance at this tile by matching through mapObject coordinates
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
                val origLocalX = obj.x - currentRegionX * 64
                val origLocalY = obj.y - currentRegionY * 64
                movingObject = MovingObject(instance, origLocalX, origLocalY)

                // Build a tile outline mesh at the original position for reference
                moveOriginMesh?.cleanup()
                moveOriginMesh = buildTileOutlineMesh(origLocalX, origLocalY, sizeX, sizeY)

                println("[MapEditor] Grabbed object '${def.name}' (id=${obj.id}) at (${obj.x}, ${obj.y}) for moving")
                return
            }
        }

        // Also check placedObjects
        for (instance in placedObjects) {
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
                val origLocalX = obj.x - currentRegionX * 64
                val origLocalY = obj.y - currentRegionY * 64
                movingObject = MovingObject(instance, origLocalX, origLocalY)
                moveOriginMesh?.cleanup()
                moveOriginMesh = buildTileOutlineMesh(origLocalX, origLocalY, sizeX, sizeY)
                println("[MapEditor] Grabbed placed object '${def.name}' (id=${obj.id}) for moving")
                return
            }
        }
    }

    /**
     * Drag the moving object to a new tile position.
     * Updates the object's worldPosition so it renders at the cursor location.
     */
    private fun handleObjectMoveDrag() {
        val moving = movingObject ?: return
        val tile = getTileUnderCursor() ?: return

        val def = moving.instance.definition
        val rotation = moving.instance.mapObject.rotation

        val (newPos, _, _) = computeObjectWorldPosition(tile.first, tile.second, def, rotation)
        moving.instance.worldPosition.set(newPos)
        objectSceneDirty = true
    }

    /**
     * Finalize the object move. Clears the drag state and origin marker.
     */
    private fun handleObjectMoveEnd() {
        movingObject = null
        moveOriginMesh?.cleanup()
        moveOriginMesh = null
    }

    /**
     * Build a bright wireframe outline mesh marking a tile footprint.
     * Used to show where a dragged object originated from.
     */
    private fun buildTileOutlineMesh(localX: Int, localY: Int, sizeX: Int, sizeY: Int): Mesh {
        val tileSize = 512f * CoordinateUtil.DEFAULT_SCALE
        val heightScale = TerrainLoader.getHeightScale()
        val em = editManager
        val worldOriginX = currentRegionX * 64 * tileSize
        val worldOriginZ = -currentRegionY * 64 * tileSize
        val yOffset = 0.2f

        // Get height at origin tile
        val h = if (em != null) {
            em.mutableData.tiles[currentPlane][localX.coerceIn(0, 63)][localY.coerceIn(0, 63)].height * heightScale
        } else 0f

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

        // Orange color for move origin marker
        val colors = FloatArray(8 * 3) { i ->
            when (i % 3) {
                0 -> 1f   // R
                1 -> 0.5f // G
                2 -> 0f   // B
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

    /**
     * Update the tile highlight mesh for the selected tile.
     */
    private fun updateTileHighlight(terrainTiles: Array<Array<Array<TerrainLoader.TileData>>>) {
        val tile = tilePicker.selectedTile ?: return

        // Clean up old mesh
        tileHighlightMesh?.cleanup()

        val tileSize = 512f * CoordinateUtil.DEFAULT_SCALE
        val heightScale = TerrainLoader.getHeightScale()

        // Get corner heights
        val x = tile.localX
        val y = tile.localY
        val nextX = (x + 1).coerceAtMost(63)
        val nextY = (y + 1).coerceAtMost(63)

        val h00 = terrainTiles[tile.plane][x][y].height * heightScale
        val h10 = terrainTiles[tile.plane][nextX][y].height * heightScale
        val h01 = terrainTiles[tile.plane][x][nextY].height * heightScale
        val h11 = terrainTiles[tile.plane][nextX][nextY].height * heightScale

        // Calculate WORLD positions for corners (with small Y offset to prevent z-fighting)
        // Offset by the center region's world origin so highlight aligns with world-space terrain
        val worldOriginX = currentRegionX * 64 * tileSize
        val worldOriginZ = -currentRegionY * 64 * tileSize
        val yOffset = 0.1f
        val x0 = worldOriginX + x * tileSize
        val x1 = worldOriginX + (x + 1) * tileSize
        val z0 = worldOriginZ + (-y * tileSize)
        val z1 = worldOriginZ + (-(y + 1) * tileSize)

        // Create line mesh for tile outline
        val positions = floatArrayOf(
            // Bottom edge (SW -> SE)
            x0, h00 + yOffset, z0,
            x1, h10 + yOffset, z0,
            // Right edge (SE -> NE)
            x1, h10 + yOffset, z0,
            x1, h11 + yOffset, z1,
            // Top edge (NE -> NW)
            x1, h11 + yOffset, z1,
            x0, h01 + yOffset, z1,
            // Left edge (NW -> SW)
            x0, h01 + yOffset, z1,
            x0, h00 + yOffset, z0
        )

        // Bright yellow color for highlight
        val colors = FloatArray(8 * 3) { i ->
            when (i % 3) {
                0 -> 1f  // R
                1 -> 1f  // G
                2 -> 0f  // B
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

    /**
     * Update the brush preview mesh for terrain paint / height edit tools.
     * Builds a semi-transparent filled quad mesh covering the brush area
     * conforming to the terrain surface. Only rebuilt when the center tile changes.
     */
    private fun updateBrushPreview() {
        // Only show brush preview for paint and height tools, and only when mouse is not over ImGui
        val isBrushTool = currentEditorTool == EditorTool.TERRAIN_PAINT || currentEditorTool == EditorTool.HEIGHT_EDIT
        if (!isBrushTool || imGui.wantCaptureMouse()) {
            if (brushPreviewMesh != null) {
                brushPreviewMesh?.cleanup()
                brushPreviewMesh = null
                lastBrushCenter = null
            }
            return
        }

        val center = getTileUnderCursor()
        if (center == null) {
            if (brushPreviewMesh != null) {
                brushPreviewMesh?.cleanup()
                brushPreviewMesh = null
                lastBrushCenter = null
            }
            return
        }

        // Skip rebuild if same tile
        if (center == lastBrushCenter) return
        lastBrushCenter = center

        brushPreviewMesh?.cleanup()

        val em = editManager ?: return

        val radius = when (currentEditorTool) {
            EditorTool.TERRAIN_PAINT -> editorBrushSize[0] - 1
            EditorTool.HEIGHT_EDIT -> editorHeightBrushRadius[0]
            else -> 0
        }

        // Determine brush color based on tool/mode
        val brushColor = when (currentEditorTool) {
            EditorTool.TERRAIN_PAINT -> floatArrayOf(0.2f, 0.8f, 0.2f, 0.25f)  // Green
            EditorTool.HEIGHT_EDIT -> when (editorHeightMode) {
                0 -> floatArrayOf(0.2f, 0.4f, 0.9f, 0.25f)     // Blue for raise
                1 -> floatArrayOf(0.9f, 0.2f, 0.2f, 0.25f)     // Red for lower
                2 -> floatArrayOf(0.9f, 0.9f, 0.2f, 0.25f)     // Yellow for smooth
                else -> floatArrayOf(0.5f, 0.5f, 0.5f, 0.25f)
            }
            else -> floatArrayOf(0.5f, 0.5f, 0.5f, 0.25f)
        }

        val tileSize = 512f * CoordinateUtil.DEFAULT_SCALE
        val heightScale = TerrainLoader.getHeightScale()
        val worldOriginX = currentRegionX * 64 * tileSize
        val worldOriginZ = -currentRegionY * 64 * tileSize
        val yOffset = 0.15f

        val positions = mutableListOf<Float>()
        val colors = mutableListOf<Float>()
        val indices = mutableListOf<Int>()
        var vertexIndex = 0

        for (dx in -radius..radius) {
            for (dy in -radius..radius) {
                val tx = center.first + dx
                val ty = center.second + dy
                if (tx < 0 || tx > 63 || ty < 0 || ty > 63) continue
                val nextX = (tx + 1).coerceAtMost(63)
                val nextY = (ty + 1).coerceAtMost(63)

                val h00 = em.mutableData.tiles[currentPlane][tx][ty].height * heightScale
                val h10 = em.mutableData.tiles[currentPlane][nextX][ty].height * heightScale
                val h01 = em.mutableData.tiles[currentPlane][tx][nextY].height * heightScale
                val h11 = em.mutableData.tiles[currentPlane][nextX][nextY].height * heightScale

                val x0 = worldOriginX + tx * tileSize
                val x1 = worldOriginX + (tx + 1) * tileSize
                val z0 = worldOriginZ + (-ty * tileSize)
                val z1 = worldOriginZ + (-(ty + 1) * tileSize)

                // 4 corner vertices per tile quad (2 triangles)
                // SW corner
                positions.add(x0); positions.add(h00 + yOffset); positions.add(z0)
                // SE corner
                positions.add(x1); positions.add(h10 + yOffset); positions.add(z0)
                // NW corner
                positions.add(x0); positions.add(h01 + yOffset); positions.add(z1)
                // NE corner
                positions.add(x1); positions.add(h11 + yOffset); positions.add(z1)

                // RGBA color for all 4 vertices
                for (v in 0 until 4) {
                    colors.add(brushColor[0])
                    colors.add(brushColor[1])
                    colors.add(brushColor[2])
                    colors.add(brushColor[3])
                }

                // Two triangles: SW-SE-NW and SE-NE-NW
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

    /**
     * Render the brush preview overlay.
     * Uses alpha blending with depth test on but depth write off so the preview
     * sits on top of terrain without occluding objects.
     */
    private fun renderBrushPreview() {
        val mesh = brushPreviewMesh ?: return

        brushPreviewShader.bind()
        brushPreviewShader.setUniform("model", Matrix4f().identity())
        brushPreviewShader.setUniform("view", scene.camera.getViewMatrix())
        brushPreviewShader.setUniform("projection", scene.camera.getProjectionMatrix(getAspectRatio()))

        // Enable blending, keep depth test, disable depth write
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glDepthMask(false)

        mesh.render()

        // Restore state
        glDepthMask(true)
        glDisable(GL_BLEND)
    }

    /**
     * Rebuilding the merged terrain re-concatenates and re-uploads every covered region's
     * geometry, which measures around 150 ms of GL-thread work at render distance 3. Doing
     * it as each region lands would spend most of a load wave inside that rebuild.
     *
     * A region that is not merged yet still draws through its own per-texture meshes, so
     * waiting costs nothing but draw calls on frames nobody is measuring. The rebuild that
     * matters is the one at the end of the wave, which is taken unconditionally; this
     * interval only governs the intermediate ones, and is deliberately long enough that a
     * normal wave takes one or two of them rather than a dozen.
     */
    private var lastMergedTerrainRebuildNanos = 0L

    private val mergedTerrainRebuildIntervalNanos = 3_000_000_000L

    override fun onDrainGpuUploads(budgetNanos: Long) {
        val drainStart = RenderMetrics.startTiming()
        asyncRegionManager.drainGpuUploads(budgetNanos)
        val currentProgress = asyncRegionManager.getLoadProgress()
        RenderMetrics.gpuUploadDrainTimeMs = RenderMetrics.endTiming(drainStart)

        // Check if initial loading is complete
        if (isInitialLoading && currentProgress.isComplete) {
            isInitialLoading = false
        }

        // Fold newly arrived regions into the merged terrain. Always rebuild immediately
        // once the wave has finished so the steady state is never left short of coverage;
        // rate-limit the intermediate rebuilds that happen while regions are still landing.
        if (terrainAtlas != null && asyncRegionManager.mergedTerrainNeedsRebuild()) {
            val now = System.nanoTime()
            if (currentProgress.isComplete || now - lastMergedTerrainRebuildNanos >= mergedTerrainRebuildIntervalNanos) {
                asyncRegionManager.buildMergedAtlasTerrain()
                lastMergedTerrainRebuildNanos = now
            }
        }
    }

    override fun onRenderLoadingScreen() {
        // Still render the scene if we have any data (center region loads synchronously)
        RenderMetrics.resetFrame()
        glClearColor(clearColor.x, clearColor.y, clearColor.z, 1.0f)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)

        // Render whatever is loaded so far
        val renderStart = RenderMetrics.startTiming()
        renderScene()
        RenderMetrics.renderTimeMs = RenderMetrics.endTiming(renderStart)

        // Render loading overlay on top
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
        // Process any pending terrain edits (dirty chunk rebuilds)
        val editResult = editManager?.processEdits() ?: EditResult.NONE

        // When terrain edits occur, the merged atlas terrain becomes stale.
        if (editResult.hasChanges && asyncRegionManager.mergedOpaqueTerrain != null) {
            asyncRegionManager.invalidateMergedTerrain()
        }

        // Reposition objects when terrain heights change
        if (editResult.heightsChanged) {
            updateObjectHeights()
        }

        // Update brush preview every frame for terrain paint / height edit tools
        updateBrushPreview()
    }

    // Frame counter for debug logging (only logs first few frames to avoid spam)
    private var frameNumber = 0

    override fun onRender() {
        val logFrame = frameNumber < 5
        if (logFrame) {
            println("[RENDER] Frame $frameNumber: onRender() start")
            System.out.flush()
        }

        // Reset per-frame performance counters
        RenderMetrics.resetFrame()

        // Reclaim the timer slot this frame will reuse, picking up the readings it took
        // GpuTimerPool.DEPTH frames ago. Nothing here waits on the GPU.
        gpuTimers.beginFrame()

        // Clear
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

        // Render UI
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

        // Periodic metrics dump for measurement runs, see RenderMetrics.summary.
        if (metricsEveryFrames > 0 && frameNumber % metricsEveryFrames == 0) {
            print(RenderMetrics.summary(fps))
            System.out.flush()
        }

        if (sweepAngles.isNotEmpty()) stepAngleSweep()
    }

    // ========================================================================
    // Scripted camera sweep
    // ========================================================================

    /**
     * `-Ddarkan.sweep=<yaw>:<pitch>[,...]`: visit a fixed list of camera angles in one run,
     * reporting a median over several frames at each.
     *
     * Every measurement this pass needs — how the visible fraction moves with viewing
     * angle, whether a change holds across angles, whether two builds draw the same
     * pixels — is a question about several viewpoints, and the honest way to answer it is
     * from one process. Restarting the viewer per angle pays the region load again, spreads
     * the samples across different driver states, and means the only thing a comparison can
     * be sure of is that something differed.
     *
     * Medians, not means: the culling compute pass occasionally spikes on a stats-readback
     * barrier, and one such frame drags a mean far enough to invent a result.
     */
    private class SweepAngle(val yaw: Float, val pitch: Float) {
        val label: String get() = "yaw%+.0f_pitch%+.0f".format(yaw, pitch)
    }

    private val sweepAngles: List<SweepAngle> = parseSweepAngles()

    /** Seconds to let regions finish loading before the first angle is sampled. */
    private val sweepAfterSeconds: Float = System.getProperty("darkan.sweepAfter", "45").toFloat()

    /** Frames discarded after each angle change, covering the GPU timer read-back lag. */
    private val sweepWarmupFrames: Int = System.getProperty("darkan.sweepWarmup", "24").toIntOrNull() ?: 24

    /** Frames sampled per angle once warmed up. */
    private val sweepSampleFrames: Int = System.getProperty("darkan.sweepFrames", "60").toIntOrNull() ?: 60

    /** `-Ddarkan.sweepShots=<dir>`: write one PNG per angle into this directory. */
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

    /** `-Ddarkan.metricsEvery=<frames>`; 0 disables the periodic metrics dump. */
    private val metricsEveryFrames = System.getProperty("darkan.metricsEvery", "0").toIntOrNull() ?: 0

    /**
     * `-Ddarkan.gpuSync=true` blocks the render thread on [glFinish] immediately after the
     * frame's clear, before any of the frame's work is submitted.
     *
     * Off by default because it serialises CPU and GPU and costs throughput. It exists
     * because the numbers in [RenderMetrics] are otherwise easy to misread: with V-Sync on,
     * the driver does not necessarily park the wait for the previous frame's present inside
     * `glfwSwapBuffers`. It parks it inside whichever GL call next needs the pipeline, which
     * in this viewer is the merged terrain multi-draw — the first substantial draw of the
     * frame. That makes `cpuRender` report several milliseconds of *waiting* as though it
     * were several milliseconds of CPU *work*, and the figure barely moves when the work
     * changes because it is really just the frame period minus everything else.
     *
     * Draining the pipeline up front moves the wait here, where it is labelled, and leaves
     * `cpuRender` and the phase breakdown measuring nothing but submission cost. Any
     * before/after comparison of CPU render cost should be made with this flag on.
     */
    private val gpuSyncProbe: Boolean = System.getProperty("darkan.gpuSync", "false").toBoolean()

    /** `-Ddarkan.cullProbe=true`; runs [runCullProbe] each frame. Measurement only. */
    private val cullProbe: Boolean = System.getProperty("darkan.cullProbe", "false").toBoolean()

    /**
     * `-Ddarkan.rangeFuseGap=<indices>`: how much culled geometry may be drawn anyway to
     * avoid splitting a batch into another draw command. See [submitBatch].
     */
    private val rangeFuseGapIndices: Int =
        System.getProperty("darkan.rangeFuseGap")?.toIntOrNull() ?: DEFAULT_RANGE_FUSE_GAP

    /** World units per map tile, so probe distances can be reported in tiles. */
    private val TILE_WORLD_SIZE = 512f * CoordinateUtil.DEFAULT_SCALE

    /** Milliseconds the [gpuSyncProbe] blocked for, i.e. the present/pipeline wait. */
    private var gpuSyncWaitMs = 0.0

    private fun renderScene() {
        val logFrame = frameNumber < 5

        // Update UBOs for lighting
        val uniformStart = RenderMetrics.startTiming()
        val viewMatrix = scene.camera.getViewMatrix()
        val projectionMatrix = scene.camera.getProjectionMatrix(getAspectRatio())
        uniformBuffers.updateCamera(viewMatrix, projectionMatrix, scene.camera.position)
        environment.nearClipDistance = scene.camera.nearPlane
        uniformBuffers.updateEnvironment(environment)
        RenderMetrics.endPhase(RenderMetrics.CPU_PHASE_UNIFORMS, uniformStart)

        // Frustum built from exactly the matrices the shaders are about to use, composed
        // the same way UniformBuffers composes its viewProjection. Anything else risks the
        // culler and the rasteriser disagreeing about where the screen edge is.
        val cullStart = RenderMetrics.startTiming()
        frustumViewProjection.set(projectionMatrix).mul(viewMatrix)
        frustum.update(frustumViewProjection)
        frustum.writePlanes(frustumPlanes)
        updateVisibleContextRegions()
        updateMergedTerrainVisibility()
        RenderMetrics.cullCpuTimeMs = RenderMetrics.endTiming(cullStart)
        RenderMetrics.endPhase(RenderMetrics.CPU_PHASE_CULL, cullStart)

        // Rebuild the static instance buffers if the object set changed, then let the GPU
        // decide what is visible. Both must happen before any object draw is issued.
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

        // Render terrain - use atlas path (2 draw calls) when available,
        // fall back to legacy multi-pass path otherwise.
        //
        // `showTerrain` had a key binding and a checkbox but nothing in this function ever
        // read it, so the toggle silently did nothing. Being able to hide the ground is what
        // lets a screenshot answer "is this the ground mesh or a placed model?" -- which is
        // exactly the question a terraced hillside raises. Starving the atlas branch of
        // geometry is only half of it: doing so also fails its guard and routes the frame
        // into the legacy branch, which draws the centre region from the edit manager, so
        // that branch reads `showTerrain` for itself too.
        val atlas = terrainAtlas
        val opaqueAtlasMesh = if (showTerrain) asyncRegionManager.mergedOpaqueTerrain else null
        val alphaAtlasMesh = if (showTerrain) asyncRegionManager.mergedAlphaTerrain else null
        val terrainRegions = if (showTerrain) visibleContextRegions else emptyList()
        val atlasShader = terrainAtlasShader
        val atlasAlphaShader = terrainAtlasAlphaShader

        if (atlas != null && atlasShader != null && atlasAlphaShader != null && (opaqueAtlasMesh != null || alphaAtlasMesh != null)) {
            // ===== Atlas rendering path: one draw call per pass for every merged region ====
            //
            // The merged meshes hold the centre region and every context region that has
            // finished loading, already offset into world space and already carrying atlas
            // UV transforms, as one contiguous index range per region. Frustum culling picks
            // the ranges and glMultiDrawElements submits them together, so the terrain draw
            // count does not move when render distance does.
            //
            // Anything the merge does not cover still renders the old way: regions that
            // arrived since the last rebuild, and individual batches whose texture the atlas
            // could not serve. Those are the only per-region terrain draws left.
            var mergedStart = RenderMetrics.startTiming()
            renderSystem.disableCullFace()
            val identityModel = Matrix4f().identity()
            val mergedRegions =
                if (mergeContextTerrain) asyncRegionManager.mergedTerrainRegions else emptySet()

            gpuTimers.begin(RenderMetrics.GPU_PASS_TERRAIN_OPAQUE)

            // Bind atlas texture to GL_TEXTURE0
            atlas.bindPage(0, 0)

            // Draw 1: Opaque terrain (color + textured, merged with atlas)
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

            // Unbind atlas texture
            glActiveTexture(GL_TEXTURE0)
            glBindTexture(GL_TEXTURE_2D, 0)
            RenderMetrics.endPhase(RenderMetrics.CPU_PHASE_TERRAIN_MERGED, mergedStart)

            val regionTerrainStart = RenderMetrics.startTiming()

            // Per-region terrain meshes for whatever the merge does not cover yet, opaque
            // half. Regions outside the frustum were already dropped by
            // updateVisibleContextRegions().
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

            // Draw 2: Alpha terrain, merged first then anything left over, under one blend
            // state. Depth func is already GL_LEQUAL globally, no need to switch.
            //
            // Merging does not disturb blend order: each region's alpha layers stay in their
            // original sequence inside its own index range, and two regions never overlap in
            // plan view, so the order between ranges cannot affect a pixel.
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
            // ===== Legacy rendering path: multi-pass per-texture draws =====
            //
            // The centre region comes from the edit manager here, not from the merged
            // atlas meshes, and only the edit manager knows whether it is drawing its
            // merged region meshes or its 64 per-chunk meshes. Each pass therefore
            // reports what it submitted and the counts are charged to the frame below;
            // counting a flat 1 for the centre made this path report `terrainAlpha=0`
            // and look like it was skipping the alpha pass when it was not.
            //
            // `showTerrain` is honoured here as well as in the atlas branch. Starving the
            // atlas branch of geometry is what routes an unwanted frame down here in the
            // first place, so leaving this side ungated meant `-Ddarkan.terrain=false`
            // drew the centre region's ground anyway.
            val em = if (showTerrain) editManager else null
            if (em != null) {
                renderSystem.disableCullFace()
                gpuTimers.begin(RenderMetrics.GPU_PASS_TERRAIN_OPAQUE)

                // Pass 1: Color mesh (untextured tiles) with RGB colors
                uboColorShader.bind()
                val centerColor = em.renderColor(uboColorShader, asyncRegionManager.centerModelMatrix)
                RenderMetrics.terrainOpaqueDrawCalls += centerColor.drawCalls
                RenderMetrics.trianglesSubmitted += centerColor.triangles
                for (ctxRegion in terrainRegions) {
                    val regionModelMatrix = asyncRegionManager.getRegionModelMatrix(ctxRegion.key)
                    val terrainMeshes = ctxRegion.terrainMeshes ?: continue
                    uboColorShader.setUniform("model", regionModelMatrix)
                    terrainMeshes.colorMesh?.let { mesh ->
                        mesh.render()
                        RenderMetrics.terrainOpaqueDrawCalls++
                        RenderMetrics.trianglesSubmitted += mesh.vertexCount / 3
                    }
                }

                // Pass 2: Textured terrain (single texture per triangle)
                uboTexturedShader.bind()
                uboTexturedShader.setUniform("textureSampler", 0)
                val centerTextured =
                    em.renderTextured(uboTexturedShader, asyncRegionManager.centerModelMatrix, terrainTextureBinding)
                RenderMetrics.terrainOpaqueDrawCalls += centerTextured.drawCalls
                RenderMetrics.trianglesSubmitted += centerTextured.triangles
                for (ctxRegion in terrainRegions) {
                    val regionModelMatrix = asyncRegionManager.getRegionModelMatrix(ctxRegion.key)
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

                // Pass 3: Alpha terrain (multi-pass alpha blending)
                // Depth func is already GL_LEQUAL globally, no need to switch.
                gpuTimers.begin(RenderMetrics.GPU_PASS_TERRAIN_ALPHA)
                glDepthMask(false)
                glEnable(GL_BLEND)
                glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)

                terrainAlphaShader.bind()
                terrainAlphaShader.setUniform("textureSampler", 0)
                val centerAlpha =
                    em.renderAlpha(terrainAlphaShader, asyncRegionManager.centerModelMatrix, terrainTextureBinding)
                RenderMetrics.terrainAlphaDrawCalls += centerAlpha.drawCalls
                RenderMetrics.trianglesSubmitted += centerAlpha.triangles
                for (ctxRegion in terrainRegions) {
                    val regionModelMatrix = asyncRegionManager.getRegionModelMatrix(ctxRegion.key)
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

        // Render tile highlight (if any selected tile)
        val overlayStart = RenderMetrics.startTiming()
        renderTileHighlight()

        // Render brush preview overlay (terrain paint / height edit tools)
        renderBrushPreview()
        RenderMetrics.endPhase(RenderMetrics.CPU_PHASE_OVERLAYS, overlayStart)

        if (logFrame) {
            println("[RENDER] Frame $frameNumber:   terrain done, starting renderObjects()")
            System.out.flush()
        }

        // Render objects
        val objectsStart = RenderMetrics.startTiming()
        renderObjects()
        RenderMetrics.endPhase(RenderMetrics.CPU_PHASE_OBJECTS, objectsStart)
        // renderObjects() runs the static batch pass inside itself; report the two side by
        // side rather than nested, so the phase columns sum to the frame.
        RenderMetrics.cpuPhaseMs[RenderMetrics.CPU_PHASE_OBJECTS] -=
            RenderMetrics.cpuPhaseMs[RenderMetrics.CPU_PHASE_STATIC_BATCH]

        if (logFrame) {
            println("[RENDER] Frame $frameNumber:   renderObjects() done")
            System.out.flush()
        }

        // Sky last: the full-screen triangle sits on the far plane with depth writes off,
        // so every pixel the scene already covered is rejected by the depth test. One draw
        // call regardless of what the region references.
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

    /**
     * Render the tile highlight outline for the selected tile,
     * and the move-origin marker if an object is being dragged.
     */
    private fun renderTileHighlight() {
        val highlightMesh = tileHighlightMesh
        val originMesh = moveOriginMesh

        if (highlightMesh == null && originMesh == null) return

        // Use simple shader for the highlight (no lighting)
        simpleShader.bind()
        simpleShader.setUniform("model", Matrix4f().identity())
        simpleShader.setUniform("view", scene.camera.getViewMatrix())
        simpleShader.setUniform("projection", scene.camera.getProjectionMatrix(getAspectRatio()))

        // Disable depth test to always show highlight, or enable with slight offset
        glDisable(GL_DEPTH_TEST)

        // Set line width (if supported)
        glLineWidth(3f)

        // Render tile selection highlight
        highlightMesh?.renderLines()

        // Render move origin marker (orange outline showing where the object was)
        originMesh?.renderLines()

        // Restore state
        glLineWidth(1f)
        glEnable(GL_DEPTH_TEST)
    }

    /**
     * Pre-computed instance entry from the parallel transform phase.
     * Contains the model ID and the raw 16-float column-major matrix data
     * ready for direct upload to the GPU via [InstanceManager.addInstanceRaw].
     */
    private data class InstanceEntry(val modelId: Int, val matrixData: FloatArray)

    /** Chunk size for parallel transform computation. */
    private val PARALLEL_CHUNK_SIZE = 512

    /** Missing models named individually by [reportDroppedInstances] before it stops. */
    private val DROPPED_MODEL_REPORT_LIMIT = 16

    /**
     * Compute the transform for every centre-region and user-placed object.
     *
     * Pure math, no GL calls, so the work fans out across [Dispatchers.Default]. Each
     * coroutine keeps its own [Matrix4f] to stay off the allocator on the hot path.
     */
    private fun computeInstanceEntries(): List<InstanceEntry> {
        val allObjects = asyncRegionManager.centerObjects + placedObjects
        if (allObjects.size <= PARALLEL_CHUNK_SIZE) {
            // Small object count: compute sequentially to avoid coroutine overhead
            val tempMatrix = Matrix4f()
            return allObjects.map { obj -> computeInstanceEntry(obj, tempMatrix) }
        }
        return runBlocking(Dispatchers.Default) {
            allObjects.chunked(PARALLEL_CHUNK_SIZE).map { chunk ->
                async {
                    val tempMatrix = Matrix4f()  // Thread-local: one per coroutine
                    chunk.map { obj -> computeInstanceEntry(obj, tempMatrix) }
                }
            }.awaitAll().flatten()
        }
    }

    /**
     * Rebuild the static object scene when the object set has changed.
     *
     * Map objects do not move: their transforms are the same every frame, and so are the
     * world bounds derived from them. Uploading both once per scene rather than once per
     * frame is what makes GPU culling worth having — the per-frame CPU cost of the object
     * pass drops to nothing, and the only thing left to do each frame is dispatch the
     * compute shader.
     *
     * [objectSceneDirty] is set by every path that can move, add or re-seat an object:
     * region load, terrain height edits, object placement and object dragging.
     */
    private fun updateObjectScene() {
        val ir = indirectRenderer ?: return
        if (!useStaticInstances || !ir.isReady()) return

        ir.resetFrameCounters()

        if (objectSceneDirty) {
            val start = RenderMetrics.startTiming()
            val entries = computeInstanceEntries()
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

    /**
     * Say, loudly, that objects were left out of the scene, and say what is known about
     * each missing model.
     *
     * The indirect renderer is fed from [AsyncRegionManager.centerModelMeshData]; the ids
     * it is asked to draw come from [AsyncRegionManager.getModelIdForInstance]. When those
     * two disagree the scene comes up with holes in it, so the report names which side of
     * that pairing is missing the model rather than only that it went missing.
     */
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
                // Not "missing from the cache": every model archive in rev727 is present and
                // decompresses. These decode to zero vertices and zero faces, so there is
                // nothing to draw and nothing for us to fix. See ModelUnavailableReason.
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
        // Static mode has already uploaded this frame's instance data (and had the GPU cull
        // it) in updateObjectScene(); only the streaming and legacy paths still have
        // per-frame transform and upload work to do here.
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
                // Multi-Draw Indirect path: stream instances into IndirectRenderer
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
                // Legacy instanced path: feed instances into InstanceManager
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

        // RS models are single-sided: the game client back-face culls them and never
        // turns culling off for the scene. Drawing them two-sided exposes interior and
        // underside faces the client discards -- e.g. the Canifis signpost's three
        // full-footprint horizontal quads show up as flat green bars through the model.
        renderSystem.enableModelCullFace()

        // Enable polygon offset to prevent z-fighting between objects and terrain.
        // Many scenery objects (ground decorations, walls, floor decals) have geometry
        // that sits exactly coplanar with terrain surfaces. The small depth bias nudges
        // object fragments slightly closer to the camera so they consistently win the
        // depth test against terrain fragments at the same depth.
        renderSystem.enablePolygonOffset(-1.0f, -1.0f)

        if (useIndirect) {
            // === Multi-Draw Indirect rendering: 1-2 draw calls for ALL center objects ===
            val indColorShader = indirectColorShader!!
            val indTexShader = indirectTexturedShader!!

            gpuTimers.begin(RenderMetrics.GPU_PASS_OBJECTS)

            // Only now make the culling results visible to the draws. Everything drawn
            // between the dispatch and here overlapped with the compute pass.
            ir.cullBarrier()

            // Render untextured objects (1 MDI call)
            ir.renderUntextured(indColorShader)

            // Render textured objects (1 MDI call) with proper GL state
            renderSystem.enableBlending()

            indTexShader.bind()
            ir.renderTextured(indTexShader)

            renderSystem.disableBlending()
            gpuTimers.end()

            // Fence the streaming ring only now that every draw reading it has been issued.
            // The static path owns immutable buffers and has no ring to fence.
            if (!staticMode) ir.endFrame()

            RenderMetrics.indirectDrawCalls = ir.getDrawCallCount()
            RenderMetrics.indirectCommands = ir.getCommandCount()
            RenderMetrics.trianglesSubmitted += ir.getTotalTrianglesSubmitted()
        } else {
            // === Legacy instanced rendering: per-mesh draw calls ===
            gpuTimers.begin(RenderMetrics.GPU_PASS_OBJECTS)

            // Render instanced center region objects (untextured)
            im.renderUntextured(instancedColorShader)

            // Render instanced center region objects (textured) with proper GL state.
            // Only use standard alpha blending (no alpha-to-coverage) to match the RS client.
            // Alpha-to-coverage + blending double-applies transparency, making semi-transparent
            // textures far too transparent (e.g., alpha=0.5 → ~25% effective opacity).
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

            // Capture instanced draw call count from InstanceManager
            val instanceStats = im.getStats()
            RenderMetrics.instancedDrawCalls = instanceStats.drawCalls
            RenderMetrics.trianglesSubmitted += countInstancedTriangles(im)
        }

        if (logFrame) {
            println("[RENDER] Frame $frameNumber:     instanced rendering done")
            System.out.flush()
        }

        // Render static batched context region objects
        val staticBatchStart = RenderMetrics.startTiming()
        gpuTimers.begin(RenderMetrics.GPU_PASS_STATIC_BATCH)
        renderStaticBatchedObjects()
        gpuTimers.end()
        RenderMetrics.endPhase(RenderMetrics.CPU_PHASE_STATIC_BATCH, staticBatchStart)

        if (cullProbe) runCullProbe()

        // Disable polygon offset after all object rendering is complete
        renderSystem.disablePolygonOffset()

        if (logFrame) {
            println("[RENDER] Frame $frameNumber:     static batched objects done")
            System.out.flush()
        }

        renderSystem.resetFrontFace()
        renderSystem.enableCullFace()
    }

    /**
     * The batches that survived this frame's cull, in the order they will be drawn.
     *
     * Kept as a field and cleared rather than rebuilt so the gather allocates nothing, and
     * so the submit loop walks a dense list of survivors instead of re-testing every batch
     * in the world a second time.
     */
    private val visibleBatchDraws = ArrayList<BatchDraw>(2048)

    /** Model matrix for static batches, which are already baked into world space. */
    private val staticBatchModel = Matrix4f().identity()

    /**
     * Render context region objects from static batched meshes.
     * Each async context region has its own set of batch meshes, keyed by [BatchKey].
     * Geometry is pre-transformed to world space, so renders with an identity model matrix.
     *
     * Batches are keyed by [BatchKey]:
     * - UNTEXTURED: vertex colors only, drawn with [staticColorShader]
     * - Atlas page (atlasPageIndex >= 0): drawn with [staticTexturedShader], atlas page bound
     * - Fallback texture (textureId >= 0, atlasPageIndex == -1): drawn with [staticTexturedShader],
     *   individual texture bound via TextureLoader
     *
     * Runs as gather-then-submit. The gather decides what is drawn, the submit loop only
     * draws, which keeps the frustum tests in one tight pass over the bounds and lets the
     * GL state be tracked across the whole pass instead of being re-established per region.
     *
     * ## This pass is primitive-bound, not draw-call bound
     * It is the most expensive GPU pass in the frame and it issues by far the most draw
     * calls, which makes collapsing those draws onto an indirect or multi-draw path look
     * like the obvious next optimisation. It is not: the draw calls are almost free and the
     * triangles are not.
     *
     * Measured by re-issuing every surviving batch's draw with an index count of 3 — same
     * draw calls, same VAO binds, same texture binds, no geometry — at render distance 3,
     * medians of six post-load samples:
     *
     * ```
     * region            draws   triangles   pass ms   issue ms   geometry ms   tri/s
     * Lumbridge 50,50    1596   7,323,989     1.857      0.119         1.738   4.2 G
     * Sophanem  52,50    1240   6,445,325     1.916      0.062         1.854   3.5 G
     * Canifis   54,54     363   2,865,784     0.642      0.026         0.616   4.7 G
     * ```
     *
     * Issuing is 3-6% of the pass, about 70 ns per draw. The other 94-97% is geometry
     * running at 3.5-4.7 G triangles/s, which is the primitive setup rate of the GPU this
     * was measured on — so it is a hardware ceiling, and no way of packaging the same
     * triangles into fewer commands moves it. Collapsing all 1596 Lumbridge draws into one
     * would save 0.119 ms, less than the run-to-run spread of the pass itself
     * (1.605-2.376 ms across six samples of an unchanged build).
     *
     * The cost is [RenderMetrics.staticBatchTriangles], and the reason it is so large is
     * granularity: a batch is one (region, texture) pair, so it spans a whole 64x64 region
     * and the frustum test either keeps all of its objects or none. The centre region's
     * per-object GPU cull, by contrast, takes 876,835 triangles down to 297,962 and costs
     * 0.097 ms. Anything that wants this pass cheaper has to submit fewer triangles —
     * finer culling granularity or distance LOD — not fewer commands.
     */
    private fun renderStaticBatchedObjects() {
        val atlas = objectAtlas
        var totalMeshes = 0
        var considered = 0
        var culled = 0

        val cullStart = RenderMetrics.startTiming()

        // Gather. Every ready region is considered, not just the terrain-visible ones: a
        // region's objects are not confined to its terrain footprint -- an overhanging roof
        // or a tall tree at the border reaches past it -- so the region's terrain box is not
        // a safe gate for its batches.
        //
        // What is safe is the union of the batches' own world-space bounds, which the
        // region maintains as it uploads them. One test against that rejects every batch the
        // region owns, so the per-batch tests are only paid for regions that are actually on
        // screen. At render distance 5 that turns ~5800 box tests into ~120 plus the couple
        // of thousand belonging to regions in view.
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
                // Null means the union could not be trusted (see RegionBatches.union), in
                // which case the region falls through to per-batch testing as before.
                val regionBounds = batches.union
                if (regionBounds != null && !frustum.isAABBVisible(regionBounds)) {
                    considered += draws.size
                    culled += draws.size
                    regionsCulled++
                    continue
                }
            }

            // Batch geometry is already in world space, so one AABB test per merged mesh
            // is the whole cost of culling it -- far cheaper than the per-object test the
            // centre region needs, and it removes a whole draw call when it hits.
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

        // Submit. The draw order is exactly the gather order, which is exactly the order the
        // old per-region loop used, so nothing about the resulting image depends on this
        // being a separate pass. What changes is that the shader binds, the blend toggle and
        // the texture binds are now tracked across the whole pass: the old loop re-bound
        // staticColorShader and re-uploaded its model uniform for every untextured batch,
        // and toggled blending and unbound the texture once per region.
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

    /**
     * Draw one batch, leaving out the spatial cells the frustum rejects.
     *
     * A batch spans a whole region, which is far coarser than anything on screen: at
     * render distance 3 roughly a third of the triangles it submits are off screen, and
     * the pass is primitive-bound, so those triangles are the cost. The merge records the
     * batch's geometry as contiguous per-cell index ranges *in buffer order*, so dropping
     * a cell is nothing more than leaving its range out of the multi-draw — the triangles
     * that remain are the same triangles, in the same order, with the same state.
     *
     * Falls back to the whole-mesh draw whenever the ranges cannot help: a single-range
     * batch, a batch whose range boxes are untrustworthy, or the common case of every
     * range being visible, where one `glDrawElements` beats a multi-draw of the same
     * contiguous span.
     */
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
            // Fuse this range into the previous one when the gap between them is small.
            //
            // A draw command costs the order of a microsecond of CPU; a triangle costs a
            // quarter of a nanosecond of GPU. Splitting a batch to skip a few hundred
            // triangles is therefore a straight loss, and only a gap worth thousands of
            // triangles pays for the command it saves. [rangeFuseGapIndices] is that
            // break-even point; zero fuses only touching ranges.
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

    // Scratch for [submitBatch]: the ranges of one batch that survived, grown to fit and
    // reused so submission allocates nothing.
    private var rangeSubmitFirst = IntArray(64)
    private var rangeSubmitCount = IntArray(64)

    private fun ensureRangeCapacity(size: Int) {
        if (rangeSubmitFirst.size >= size) return
        rangeSubmitFirst = IntArray(size)
        rangeSubmitCount = IntArray(size)
    }

    /**
     * `-Ddarkan.cullProbe=true`: price a per-object cull of the context regions without
     * building one.
     *
     * The static batch pass is primitive-bound (see [renderStaticBatchedObjects]), so the
     * only lever left is submitting fewer triangles, and the two candidate levers — finer
     * culling and distance LOD — cannot be told apart from a triangle total. This walks the
     * per-object boxes the batch merge flattened away and answers both questions from the
     * same pass: how many of the submitted triangles a per-object frustum test would keep,
     * and how those survivors are spread over distance and object size.
     *
     * Measurement only. Nothing here changes what is drawn, and the cost is reported
     * separately as [RenderMetrics.probeCpuMs] so it cannot be mistaken for frame time.
     */
    private fun runCullProbe() {
        val probeStart = RenderMetrics.startTiming()
        val camera = scene.camera.position
        val distanceEdges = RenderMetrics.PROBE_DISTANCE_EDGES_TILES
        val sizeEdges = RenderMetrics.PROBE_SIZE_EDGES
        val pixelEdges = RenderMetrics.PROBE_PIXEL_EDGES
        // Viewport height over the vertical field of view, i.e. pixels per unit of
        // (size / distance). Matches the projection the frame is about to be drawn with.
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

                // Distance to the box centre, in tiles, so the buckets read in the same
                // units as render distance rather than in world units.
                val dx = (bounds[offset] + bounds[offset + 3]) * 0.5f - camera.x
                val dy = (bounds[offset + 1] + bounds[offset + 4]) * 0.5f - camera.y
                val dz = (bounds[offset + 2] + bounds[offset + 5]) * 0.5f - camera.z
                val distance = sqrt(dx * dx + dy * dy + dz * dz)

                // Projected height of the box, in pixels: a sphere of radius r at distance
                // d covers 2r / (2 d tan(fov/2)) of the viewport height. Conservative, since
                // the box's half-diagonal bounds it in every orientation.
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

        // Price the intermediate granularities: batches keyed by (cell, material) rather
        // than (region, material). Same frustum, same test, so the draw and triangle
        // counts here are what that grouping would really submit this frame.
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

    /** Per grid size, in [AsyncRegionManager] simulation order: batches that survived. */
    private val probeGridDraws = IntArray(8)
    private val probeGridTriangles = LongArray(8)
    private val probeGridBatches = IntArray(8)

    /**
     * Recompute, once per frame, which context regions have terrain inside the frustum.
     *
     * The test is the region's 64x64 tile terrain box, which is exact horizontally, so
     * rejecting a region here drops all of its terrain draws. It is deliberately *not*
     * used to gate the region's static batches: object geometry reaches past the terrain
     * footprint, so batches are culled individually against their own bounds.
     *
     * Both lists are cached because several passes walk them, and the old code re-derived
     * the ready-region list from scratch -- allocating a fresh filtered list -- three
     * times per frame.
     */
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

    // Per-frame working set for the merged terrain multi-draws: which of the merged
    // regions the frustum kept, and the index ranges handed to GL. Grown to fit and
    // reused, so a frame allocates nothing for terrain submission.
    //
    // [terrainRangeSnapshot] is the list the three arrays were sized and filled for.
    // AsyncRegionManager replaces mergedTerrainRanges wholesale when it rebuilds, so
    // re-reading it in the gather step could hand back a longer list than the arrays
    // were sized for; pinning the list once per frame makes the indices provably in
    // range instead of relying on when the rebuild happens to run.
    private var terrainRangeSnapshot: List<MergedTerrainRange> = emptyList()
    private var terrainRangeVisible = BooleanArray(0)
    private var terrainRangeFirst = IntArray(0)
    private var terrainRangeCounts = IntArray(0)

    /**
     * Pin this frame's merged terrain ranges and frustum-test every region in them.
     *
     * Done once per frame rather than once per pass so the opaque and alpha halves of a
     * region can never disagree about whether it is on screen, which would show up as a
     * region's alpha layers blending against terrain that was not drawn.
     */
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

    /**
     * Pack the visible merged-terrain ranges of one pass into [terrainRangeFirst] and
     * [terrainRangeCounts].
     *
     * Walks [terrainRangeSnapshot], not the manager's live list, so it can only ever see
     * the ranges [updateMergedTerrainVisibility] sized the arrays for.
     *
     * @param alpha true for the alpha buffer's ranges, false for the opaque buffer's
     * @return the number of leading entries of the two arrays that are valid
     */
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

    /**
     * Count the total triangles rendered by the InstanceManager this frame.
     * Delegates to [InstanceManager.getTotalTrianglesSubmitted] which sums
     * (vertexCount/3 * instanceCount) for each active instanced mesh.
     */
    private fun countInstancedTriangles(im: InstanceManager): Int {
        return im.getTotalTrianglesSubmitted()
    }

    /**
     * Compute an [InstanceEntry] for a single object instance.
     * This is a pure math function with no GL calls, safe for use from any thread.
     *
     * @param obj The object instance to compute the transform for
     * @param tempMatrix Thread-local Matrix4f reused across calls to avoid allocation
     * @return An InstanceEntry with the model ID and pre-computed raw matrix data
     */
    private fun computeInstanceEntry(
        obj: ObjectInstance,
        tempMatrix: Matrix4f
    ): InstanceEntry {
        // For contourType 1 objects with displacement, use the displaced model ID
        // so the rendering path picks up the displaced geometry.
        // Fall back to the cached combined model ID for all other objects.
        val combinedModelId = asyncRegionManager.getModelIdForInstance(obj)

        // Build transform matrix matching client order:
        // RS client applies to model vertices: Rotate Y -> Terrain Tilt -> Scale -> Offset
        // Then model is placed at world position with height adjustment
        // Matrix operations are right-to-left, so we specify them in reverse order
        //
        // For terrain alignment (groundContourType=3), the client's hillChange applies:
        // 1. X-axis rotation (pitch tilt based on N/S slope)
        // 2. Z-axis rotation (roll tilt based on E/W slope)
        // 3. Y translation (height adjustment to sit on lowest corner)
        tempMatrix.identity()
            .translate(obj.worldPosition)        // 6. Move to world position

        // Apply terrain alignment (height adjustment + tilt rotations)
        if (obj.terrainAlignment.needsAlignment()) {
            // Height adjustment from terrain alignment (after world position)
            tempMatrix.translate(0f, obj.terrainAlignment.heightAdjustment, 0f)
        }

        tempMatrix
            .translate(obj.offset)               // 5. Apply definition offset
            .scale(obj.scale)                    // 4. Scale

        // Apply terrain tilt rotations (after Y rotation in application order)
        // RS applies: RotateY -> TiltX -> TiltZ (in that order on vertices)
        // Matrix equivalent (right-to-left): TiltZ * TiltX * RotateY
        if (obj.terrainAlignment.needsAlignment()) {
            tempMatrix.rotateZ(obj.terrainAlignment.tiltZ)  // 3. Roll tilt (last in app order)
            tempMatrix.rotateX(obj.terrainAlignment.tiltX)  // 2. Pitch tilt
        }

        tempMatrix.rotateY(obj.rotation)   // 1. Object Y rotation (first in app order)

        // Apply mirroring if needed (negates Z coordinates)
        // This is applied last in the matrix chain = first on vertices
        if (obj.mirrored) {
            tempMatrix.scale(1f, 1f, -1f)  // 0. Mirror across XY plane
        }

        val matrixData = FloatArray(16)
        tempMatrix.get(matrixData)
        return InstanceEntry(combinedModelId, matrixData)
    }

    // ========================================================================
    // UI Rendering
    // ========================================================================

    private fun renderUI() {
        imGui.beginFrame()

        // Debug panel
        debugPanel.fps = fps
        debugPanel.deltaTime = deltaTime
        debugPanel.wireframeMode = renderSystem.wireframeMode
        debugPanel.render(scene.camera, window.width, window.height) {
            renderSystem.toggleWireframe()
        }

        // Map editor panel (sole control surface)
        if (showEditorPanel) {
            renderMapEditorPanel()
        }

        imGui.endFrame()
    }

    /**
     * Render the unified Map Editor panel.
     * Contains navigation, view toggles, tool palette, tool content,
     * rendering settings, and controls help.
     */
    private fun renderMapEditorPanel() {
        // Size constraints: 320-400 width, 200 to 80% screen height
        val maxHeight = window.height * 0.8f
        ImGui.setNextWindowSizeConstraints(320f, 200f, 400f, maxHeight)

        // Set initial position on first frame (right side)
        if (editorPanelFirstFrame) {
            ImGui.setNextWindowPos(window.width - 340f, 10f)
            ImGui.setNextWindowSize(340f, 600f)
            editorPanelFirstFrame = false
        }

        val flags = ImGuiWindowFlags.None

        if (ImGui.begin("Map Editor (F5)", flags)) {
            clampWindowToScreen()

            // --- Navigation Section ---
            renderNavigationSection()

            ImGui.separator()

            // --- View Toggles Section ---
            renderViewTogglesSection()

            ImGui.separator()

            // --- Tool Palette ---
            renderToolPalette()

            ImGui.spacing()

            // --- Tool-specific content ---
            renderCurrentToolContent()

            ImGui.separator()

            // --- Environment (collapsible) ---
            renderEnvironmentSection()

            ImGui.separator()

            // --- Rendering Settings (collapsible) ---
            renderRenderingSettingsSection()

            ImGui.separator()

            // Controls help
            ImGui.textWrapped("WASD move, Space/Shift up/down, Right-click look, Arrows navigate, Ctrl fast, R roofs")
        }
        ImGui.end()
    }

    /**
     * Navigation section: region coordinates, plane radio buttons, go-to input, quick locations.
     */
    private fun renderNavigationSection() {
        // Current position
        ImGui.text("Region: (%d, %d)".format(currentRegionX, currentRegionY))
        ImGui.sameLine()
        ImGui.text("  Plane:")
        for (plane in 0..3) {
            ImGui.sameLine()
            if (ImGui.radioButton("$plane##plane", currentPlane == plane)) {
                setPlane(plane)
            }
        }

        // Go-to region input
        ImGui.setNextItemWidth(80f)
        ImGui.inputInt("X##region", regionXInput)
        ImGui.sameLine()
        ImGui.setNextItemWidth(80f)
        ImGui.inputInt("Y##region", regionYInput)
        ImGui.sameLine()
        if (ImGui.button("Go", 40f, 0f)) {
            jumpToRegion(regionXInput.get().coerceIn(0, 255), regionYInput.get().coerceIn(0, 255))
        }

        // Quick locations (collapsible)
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

    /**
     * View toggles section: terrain, roofs, ground decorations in a row.
     */
    private fun renderViewTogglesSection() {
        // Terrain toggle
        if (ImGui.checkbox("Terrain (T)", showTerrain)) {
            showTerrain = !showTerrain
        }

        ImGui.sameLine()

        // Roof visibility toggle
        val roofsEnabled = ImBoolean(showRoofs)
        if (ImGui.checkbox("Roofs (R)", roofsEnabled)) {
            showRoofs = roofsEnabled.get()
            reloadRegion()
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Toggle visibility of roof shapes (12-21)\nHide roofs to see inside buildings")
        }

        // Ground decoration toggle
        val groundDecEnabled = ImBoolean(ObjectRenderer.groundDecorationEnabled)
        if (ImGui.checkbox("Ground Decorations", groundDecEnabled)) {
            ObjectRenderer.groundDecorationEnabled = groundDecEnabled.get()
            reloadRegion()
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Toggle ground decoration rendering\nWhen off, only interactive/blocking ground decorations render")
        }

        // Render distance slider
        ImGui.spacing()
        ImGui.setNextItemWidth(120f)
        if (ImGui.sliderInt("Render Distance", renderDistanceInput, 0, RegionManager.MAX_RENDER_DISTANCE)) {
            // Will take effect on next loadRegion call
        }
        ImGui.sameLine()
        if (ImGui.button("Apply##renderDist")) {
            asyncRegionManager.setRenderDistanceAsync(renderDistanceInput[0])
        }
        if (ImGui.isItemHovered()) {
            val dist = renderDistanceInput[0]
            val gridSize = dist * 2 + 1
            val totalRegions = gridSize * gridSize
            ImGui.setTooltip("Render distance: $dist ($gridSize x $gridSize = $totalRegions regions)\n0 = center only, 1 = 3x3, 2 = 5x5, etc.")
        }
    }

    /**
     * Tool palette: buttons for each editor tool.
     */
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
                ImGui.setTooltip("${tool.label} (WIP - not yet functional)")
            }

            if (isSelected) {
                ImGui.popStyleColor(2)
            }
        }
    }

    /**
     * Dispatch to the current tool's content renderer.
     */
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

    /**
     * Environment section: the decoded sun/fog/skybox record for the current region,
     * live-editable.
     *
     * Every widget writes straight back into [environment], which the UBO picks up on the
     * next frame - that is the whole point of moving the sun out of the baked vertex
     * colours. Nothing here is written back to the cache.
     */
    private fun renderEnvironmentSection() {
        if (!ImGui.collapsingHeader("Environment")) return

        ImGuiText.disabled(
            if (environment.fromCache) {
                "Decoded from m${currentRegionX}_$currentRegionY"
            } else {
                "No environment block - client defaults"
            }
        )

        // --- Sun ---
        ImGui.text("Sun")

        val dynamic = ImBoolean(environment.dynamicSunLighting)
        if (ImGui.checkbox("Dynamic sun lighting", dynamic)) {
            environment.dynamicSunLighting = dynamic.get()
            // The terrain vertex colours are built either lit or unlit, so the meshes have
            // to be rebuilt. Object colours are unlit in both modes and need no rebuild.
            TerrainLoader.bakeSunLighting = !environment.dynamicSunLighting
            reloadTerrain()
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

        // --- Fog ---
        ImGui.text("Fog")

        val fog = ImBoolean(environment.fogEnabled)
        if (ImGui.checkbox("Fog enabled", fog)) {
            environment.fogEnabled = fog.get()
            // Fog also decides whether the flat background paints the cache fog colour or the
            // editor's neutral sky, and that is only pushed to the sky pass from here.
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

        // --- Skybox ---
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

    /**
     * Write-back controls for the Environment section.
     *
     * Saving mutates the user's real cache, so it is deliberately awkward: a preview has to
     * be taken first, the region it targets is spelled out, and a confirmation has to be
     * ticked before the button does anything. The preview is invalidated whenever the camera
     * moves into a different region, so the button can never end up pointed somewhere else.
     */
    private fun renderEnvironmentSaveSection() {
        ImGui.text("Save to cache")

        val regionKey = (currentRegionX shl 8) or currentRegionY
        if (envWritePlanRegion != regionKey) {
            // Region changed under the panel - drop the stale plan rather than let it be applied.
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
        // Latch the disabled state: the button's own handler clears the confirmation, and
        // re-evaluating the condition afterwards would skip endDisabled and unbalance the
        // style stack for the rest of the frame.
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

    /** The panel's current environment values, in the units the cache encoder expects. */
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

    /**
     * Rendering settings section: blending, overlays, noise, lighting parameters,
     * terrain alignment, and tilt debug.
     */
    private fun renderRenderingSettingsSection() {
        if (!ImGui.collapsingHeader("Rendering Settings")) return

        // Terrain blending toggle
        val blendingEnabled = ImBoolean(TerrainLoader.enableBlending)
        if (ImGui.checkbox("Terrain Blending", blendingEnabled)) {
            TerrainLoader.enableBlending = blendingEnabled.get()
            reloadTerrain()
        }

        // Overlay toggle (for debugging underlay-only terrain)
        val overlaysEnabled = ImBoolean(TerrainLoader.enableOverlays)
        if (ImGui.checkbox("Show Overlays", overlaysEnabled)) {
            TerrainLoader.enableOverlays = overlaysEnabled.get()
            reloadTerrain()
        }

        // Color noise toggle - adds subtle per-vertex brightness variation like RS client
        val colorNoiseEnabled = ImBoolean(TerrainLoader.enableColorNoise)
        if (ImGui.checkbox("Color Noise", colorNoiseEnabled)) {
            TerrainLoader.enableColorNoise = colorNoiseEnabled.get()
            reloadTerrain()
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Adds subtle per-vertex brightness variation\nlike the RS client (+-1.5%% noise)")
        }

        // Terrain lighting toggle
        val lightingEnabled = ImBoolean(TerrainLoader.enableLighting)
        if (ImGui.checkbox("Terrain Lighting", lightingEnabled)) {
            TerrainLoader.enableLighting = lightingEnabled.get()
            reloadTerrain()
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Applies RS-style per-vertex lighting based on\nterrain normals and sun direction")
        }

        // Lighting parameters section (collapsible). These drive the *baked* terrain path
        // only; with dynamic sun lighting on, the sun lives in the Environment section.
        if (TerrainLoader.enableLighting && ImGui.collapsingHeader("Lighting Parameters")) {
            ImGui.indent()
            if (environment.dynamicSunLighting) {
                ImGuiText.disabledWrapped(
                    "Dynamic sun lighting is on, so these do nothing - edit the sun in the " +
                        "Environment section instead."
                )
                ImGui.spacing()
            }

            // Sun direction sliders
            ImGui.text("Light Direction")
            val lightDirX = floatArrayOf(TerrainLoader.LIGHT_DIRECTION_X)
            val lightDirY = floatArrayOf(TerrainLoader.LIGHT_DIRECTION_Y)
            val lightDirZ = floatArrayOf(TerrainLoader.LIGHT_DIRECTION_Z)

            var lightingChanged = false
            ImGui.setNextItemWidth(100f)
            if (ImGui.sliderFloat("Dir X", lightDirX, -1f, 1f)) {
                TerrainLoader.LIGHT_DIRECTION_X = lightDirX[0]
                lightingChanged = true
            }
            ImGui.setNextItemWidth(100f)
            if (ImGui.sliderFloat("Dir Y", lightDirY, -1f, 1f)) {
                TerrainLoader.LIGHT_DIRECTION_Y = lightDirY[0]
                lightingChanged = true
            }
            ImGui.setNextItemWidth(100f)
            if (ImGui.sliderFloat("Dir Z", lightDirZ, -1f, 1f)) {
                TerrainLoader.LIGHT_DIRECTION_Z = lightDirZ[0]
                lightingChanged = true
            }

            ImGui.spacing()

            // RS lighting parameters
            ImGui.text("RS Lighting Formula:")
            ImGui.textWrapped("factor = sunBrightness + dot * (dot>0 ? bright : dark)")

            val sunBrightness = floatArrayOf(TerrainLoader.SUN_BRIGHTNESS)
            val brightFactor = floatArrayOf(TerrainLoader.BRIGHT_FACTOR)
            val darkFactor = floatArrayOf(TerrainLoader.DARK_FACTOR)

            ImGui.setNextItemWidth(100f)
            if (ImGui.sliderFloat("Sun Brightness", sunBrightness, 0.5f, 1.5f)) {
                TerrainLoader.SUN_BRIGHTNESS = sunBrightness[0]
                lightingChanged = true
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip("Base ambient brightness (default: 1.2)")
            }

            ImGui.setNextItemWidth(100f)
            if (ImGui.sliderFloat("Bright Factor", brightFactor, 0.2f, 1.5f)) {
                TerrainLoader.BRIGHT_FACTOR = brightFactor[0]
                lightingChanged = true
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip("Factor for lit surfaces (default: 0.865)")
            }

            ImGui.setNextItemWidth(100f)
            if (ImGui.sliderFloat("Dark Factor", darkFactor, 0.5f, 2.0f)) {
                TerrainLoader.DARK_FACTOR = darkFactor[0]
                lightingChanged = true
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip("Factor for shadowed surfaces (default: 0.814)")
            }

            // Reset to defaults button
            if (ImGui.button("Reset to Defaults")) {
                TerrainLoader.LIGHT_DIRECTION_X = 0.163f
                TerrainLoader.LIGHT_DIRECTION_Y = 0.070f
                TerrainLoader.LIGHT_DIRECTION_Z = 1.00f
                TerrainLoader.SUN_BRIGHTNESS = 1.2f
                TerrainLoader.BRIGHT_FACTOR = 0.865f
                TerrainLoader.DARK_FACTOR = 0.814f
                lightingChanged = true
            }

            // Reload terrain if parameters changed
            if (lightingChanged) {
                reloadTerrain()
            }

            ImGui.unindent()
        }

        ImGui.spacing()

        // Terrain alignment toggle (for objects with groundContourType=3)
        val terrainAlignEnabled = ImBoolean(TerrainAligner.enabled)
        if (ImGui.checkbox("Terrain Alignment", terrainAlignEnabled)) {
            TerrainAligner.enabled = terrainAlignEnabled.get()
            reloadRegion()
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Tilt objects to match terrain slopes\n(RS groundContourType=3 / hillChange)")
        }

        // Tilt negation toggles (for coordinate system debugging)
        if (TerrainAligner.enabled && ImGui.collapsingHeader("Tilt Debug")) {
            ImGui.indent()

            // Show debug stats
            ImGui.text("Stats: ${TerrainAligner.getDebugStats()}")

            val negateXTilt = ImBoolean(TerrainAligner.negateXTilt)
            if (ImGui.checkbox("Negate X Tilt", negateXTilt)) {
                TerrainAligner.negateXTilt = negateXTilt.get()
                reloadRegion()
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip("Negate X-axis tilt (pitch) for coordinate system fix")
            }

            val negateZTilt = ImBoolean(TerrainAligner.negateZTilt)
            if (ImGui.checkbox("Negate Z Tilt", negateZTilt)) {
                TerrainAligner.negateZTilt = negateZTilt.get()
                reloadRegion()
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip("Negate Z-axis tilt (roll) for coordinate system fix")
            }

            // Button to enable debug logging
            if (ImGui.button("Enable Debug Logging")) {
                TerrainAligner.enableDebug(true)
                reloadRegion()
            }

            ImGui.unindent()
        }

    }

    // ========================================================================
    // Editor Tool Content
    // ========================================================================

    /**
     * SELECT tool content: Object Inspector, Tile Inspector, and Tile Details (Debug).
     */
    private fun renderEditorSelectTool() {
        val details = tilePicker.selectedTileDetails
        val tile = tilePicker.selectedTile

        if (tile == null) {
            ImGui.textColored(0.7f, 0.7f, 0.7f, 1f, "Left-click on terrain to select a tile")
            return
        }

        // --- Object Inspector ---
        if (ImGui.collapsingHeader("Object Inspector")) {
            if (details == null || details.objects.isEmpty()) {
                ImGui.textColored(0.7f, 0.7f, 0.7f, 1f, "No objects on selected tile")
            } else {
                for ((index, tileObj) in details.objects.withIndex()) {
                    val obj = tileObj.mapObject
                    val def = tileObj.definition

                    val objName = def.name.ifEmpty { "<unnamed>" }
                    if (ImGui.treeNode("editor_obj_$index", "#%d: %s".format(obj.id, objName))) {

                        // Shape name lookup
                        val shapeName = ObjectShape.entries.find { it.id == obj.shape }?.name ?: "Unknown"
                        ImGui.text("Shape:    %s (%d)".format(shapeName, obj.shape))
                        ImGui.text("Rotation: %d".format(obj.rotation))

                        // Model IDs
                        ImGui.text("Models:   %s".format(
                            if (tileObj.modelIds.isEmpty()) "(none)"
                            else tileObj.modelIds.joinToString(", ")
                        ))

                        // Interactive flag
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

                        // Placeholder action buttons (disabled)
                        ImGui.beginDisabled()
                        ImGui.button("Edit##edit_$index")
                        ImGui.sameLine()
                        ImGui.button("Delete##delete_$index")
                        ImGui.endDisabled()
                        if (ImGui.isItemHovered(ImGuiHoveredFlags.AllowWhenDisabled)) {
                            ImGui.setTooltip("Not yet implemented")
                        }

                        ImGui.treePop()
                    }
                }
            }
        }

        // --- Tile Inspector ---
        if (ImGui.collapsingHeader("Tile Inspector")) {
            if (details == null || details.tileData == null) {
                ImGui.textColored(0.7f, 0.7f, 0.7f, 1f, "No tile data available")
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

                // Read-only flag checkboxes
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

        // --- Tile Details (Debug) ---
        // Detailed overlay/underlay definitions, UV coords, corner heights, and per-object info.
        if (ImGui.collapsingHeader("Tile Details (Debug)")) {
            if (details == null || details.tileData == null) {
                ImGui.textColored(0.7f, 0.7f, 0.7f, 1f, "No tile data available")
            } else {
                val tileData = details.tileData

                // Tile coordinates
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

                    // UV coordinates for overlay
                    val uvs = details.cornerUVsOverlay
                    if (uvs != null) {
                        ImGui.text("  UV Scale: %.4f (1/%d)".format(1f / overlayDef.scale.toFloat(), overlayDef.scale))
                        ImGui.text("  UV SW: (%.3f, %.3f)".format(uvs[0][0], uvs[0][1]))
                        ImGui.text("  UV SE: (%.3f, %.3f)".format(uvs[1][0], uvs[1][1]))
                        ImGui.text("  UV NW: (%.3f, %.3f)".format(uvs[2][0], uvs[2][1]))
                        ImGui.text("  UV NE: (%.3f, %.3f)".format(uvs[3][0], uvs[3][1]))
                    }
                } else if (tileData.overlayId > 0) {
                    ImGui.textColored(1f, 0.5f, 0.5f, 1f, "  (Definition not found)")
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

                    // UV coordinates for underlay
                    val uvs = details.cornerUVsUnderlay
                    if (uvs != null) {
                        ImGui.text("  UV Scale: %.4f (1/%d)".format(1f / underlayDef.scale.toFloat(), underlayDef.scale))
                        ImGui.text("  UV SW: (%.3f, %.3f)".format(uvs[0][0], uvs[0][1]))
                        ImGui.text("  UV SE: (%.3f, %.3f)".format(uvs[1][0], uvs[1][1]))
                        ImGui.text("  UV NW: (%.3f, %.3f)".format(uvs[2][0], uvs[2][1]))
                        ImGui.text("  UV NE: (%.3f, %.3f)".format(uvs[3][0], uvs[3][1]))
                    }
                } else if (tileData.underlayId > 0) {
                    ImGui.textColored(1f, 0.5f, 0.5f, 1f, "  (Definition not found)")
                }

                // Corner heights
                ImGui.spacing()
                ImGui.text("Corner Heights (scaled)")
                ImGui.separator()
                ImGui.text("  SW: %.2f  SE: %.2f".format(details.cornerHeights[0], details.cornerHeights[1]))
                ImGui.text("  NW: %.2f  NE: %.2f".format(details.cornerHeights[2], details.cornerHeights[3]))

                // Objects on tile (detailed view with model/texture/retexture info)
                ImGui.spacing()
                ImGui.text("Objects on Tile (%d)".format(details.objects.size))
                ImGui.separator()
                if (details.objects.isEmpty()) {
                    ImGui.textColored(0.7f, 0.7f, 0.7f, 1f, "  (No objects)")
                } else {
                    for ((index, tileObj) in details.objects.withIndex()) {
                        val obj = tileObj.mapObject
                        val def = tileObj.definition
                        if (ImGui.treeNode("debug_obj_$index", "Object %d: %s".format(obj.id, def.name.ifEmpty { "<unnamed>" }))) {
                            ImGui.text("  Shape: %d, Rotation: %d".format(obj.shape, obj.rotation))
                            ImGui.text("  World Pos: (%d, %d)".format(obj.x, obj.y))
                            ImGui.text("  Size: %dx%d".format(def.sizeX, def.sizeY))

                            // Model IDs
                            ImGui.text("  Model IDs: %s".format(
                                if (tileObj.modelIds.isEmpty()) "(none)"
                                else tileObj.modelIds.joinToString(", ")
                            ))

                            // Texture IDs
                            ImGui.text("  Texture IDs: %s".format(
                                if (tileObj.textureIds.isEmpty()) "(none)"
                                else tileObj.textureIds.sorted().joinToString(", ")
                            ))

                            // Object retexturing/recoloring if present
                            if (def.originalTextureColours != null && def.modifiedTextureColours != null) {
                                ImGui.textColored(0.5f, 1f, 0.5f, 1f, "  Has Retexturing")
                                for (i in def.originalTextureColours!!.indices) {
                                    if (i < def.modifiedTextureColours!!.size) {
                                        val src = def.originalTextureColours!![i].toInt() and 0xffff
                                        val dst = def.modifiedTextureColours!![i].toInt() and 0xffff
                                        ImGui.text("    Tex %d -> %d".format(src, dst))
                                    }
                                }
                            }
                            if (def.originalColours != null && def.modifiedColours != null) {
                                ImGui.textColored(0.5f, 0.5f, 1f, 1f, "  Has Recoloring")
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

    /**
     * OBJECT_PLACE tool content: Object browser for searching and placing objects.
     */
    private fun renderEditorObjectPlaceTool() {
        // Show selected object prominently at the top
        if (selectedObjectId >= 0) {
            val selDef = ObjectRenderer.getDefinition(selectedObjectId)
            if (selDef != null) {
                ImGui.textColored(0.3f, 0.9f, 0.3f, 1f, "Selected: %s (ID: %d)".format(selDef.name, selDef.id))
                ImGui.textColored(0.3f, 0.9f, 0.3f, 1f, "Left-click terrain to place")
            }
            ImGui.spacing()
        } else {
            ImGui.textColored(0.7f, 0.7f, 0.7f, 1f, "Search and select an object below to place it")
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

            // Scrollable results list
            val listHeight = ImGui.getContentRegionAvailY().coerceAtLeast(100f).coerceAtMost(300f)
            if (ImGui.beginChild("obj_search_results", 0f, listHeight, true)) {
                val query = editorObjectSearchQuery.get().trim()
                if (query.length < 2) {
                    ImGui.textColored(0.5f, 0.5f, 0.5f, 1f, "Type 2+ characters to search...")
                } else if (objectSearchResults.isEmpty()) {
                    ImGui.textColored(0.5f, 0.5f, 0.5f, 1f, "No results for \"%s\"".format(query))
                } else {
                    for (def in objectSearchResults) {
                        val isSelected = selectedObjectId == def.id
                        val label = "%d: %s".format(def.id, def.name)
                        if (ImGui.selectable(label, isSelected)) {
                            selectedObjectId = def.id
                        }
                        if (ImGui.isItemHovered()) {
                            ImGui.setTooltip("ID: %d\nSize: %dx%d".format(def.id, def.sizeX, def.sizeY))
                        }
                    }
                }
            }
            ImGui.endChild()

            // Shape and rotation controls
            if (selectedObjectId >= 0) {
                ImGui.spacing()
                ImGui.separator()
                ImGui.spacing()
                ImGui.setNextItemWidth(120f)
                ImGui.sliderInt("Shape##obj", editorObjectShape, 0, 22)
                ImGui.setNextItemWidth(120f)
                ImGui.sliderInt("Rotation##obj", editorObjectRotation, 0, 3)

                // Show count of placed objects
                if (placedObjects.isNotEmpty()) {
                    ImGui.spacing()
                    ImGui.textColored(0.7f, 0.7f, 0.7f, 1f, "Placed objects: %d".format(placedObjects.size))
                }
            }
        }
    }

    /**
     * OBJECT_MOVE tool content: click an object to grab it, drag to reposition, release to place.
     */
    private fun renderEditorObjectMoveTool() {
        val moving = movingObject
        if (moving != null) {
            val def = moving.instance.definition
            ImGui.textColored(0.3f, 0.9f, 0.3f, 1f, "Dragging: %s (ID: %d)".format(def.name, def.id))
            ImGui.textColored(0.7f, 0.7f, 0.7f, 1f, "Release mouse to drop. Escape to cancel.")
            ImGui.spacing()
            ImGui.text("Original: (%d, %d)".format(
                currentRegionX * 64 + moving.originalX,
                currentRegionY * 64 + moving.originalY
            ))
            ImGui.spacing()
            if (ImGui.button("Cancel Move")) {
                // Restore original position
                val tileSize = 512f * CoordinateUtil.DEFAULT_SCALE
                val origWorldX = currentRegionX * 64 + moving.originalX
                val origWorldY = currentRegionY * 64 + moving.originalY
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
                recalcObjectHeight(moving.instance)
                movingObject = null
                moveOriginMesh?.cleanup()
                moveOriginMesh = null
            }
        } else {
            ImGui.textColored(0.7f, 0.7f, 0.7f, 1f, "Click an object to grab it")
            ImGui.textColored(0.7f, 0.7f, 0.7f, 1f, "Drag to reposition, release to place")
        }
    }

    /**
     * TERRAIN_PAINT tool content: overlay/underlay painting controls.
     */
    private fun renderEditorTerrainPaintTool() {
        if (ImGui.collapsingHeader("Terrain Painter", ImGuiTreeNodeFlags.DefaultOpen)) {
            ImGui.textColored(0.7f, 0.7f, 0.7f, 1f, "Click or drag to paint terrain")
            ImGui.spacing()

            // Paint mode: Overlay / Underlay
            ImGui.text("Paint Mode")
            if (ImGui.radioButton("Overlay", editorPaintMode == 0)) {
                editorPaintMode = 0
            }
            ImGui.sameLine()
            if (ImGui.radioButton("Underlay", editorPaintMode == 1)) {
                editorPaintMode = 1
            }

            ImGui.spacing()

            // ID input
            val idLabel = if (editorPaintMode == 0) "Overlay ID" else "Underlay ID"
            ImGui.setNextItemWidth(120f)
            ImGui.inputInt(idLabel, editorOverlayUnderlayId)

            // Overlay-specific: shape and rotation
            if (editorPaintMode == 0) {
                ImGui.setNextItemWidth(120f)
                ImGui.sliderInt("Shape", editorOverlayShape, 0, 11)
                ImGui.setNextItemWidth(120f)
                ImGui.sliderInt("Rotation", editorOverlayRotation, 0, 3)
            }

            ImGui.spacing()

            // Brush size slider
            ImGui.setNextItemWidth(120f)
            ImGui.sliderInt("Brush Size", editorBrushSize, 1, 5)

            ImGui.spacing()
            ImGui.textColored(0.3f, 0.9f, 0.3f, 1f, "Left-click or drag to paint")
        }
    }

    /**
     * HEIGHT_EDIT tool content: height brush controls.
     */
    private fun renderEditorHeightEditTool() {
        if (ImGui.collapsingHeader("Height Editor", ImGuiTreeNodeFlags.DefaultOpen)) {
            ImGui.textColored(0.7f, 0.7f, 0.7f, 1f, "Click and drag to edit height")
            ImGui.spacing()

            // Brush radius slider
            ImGui.setNextItemWidth(120f)
            ImGui.sliderInt("Brush Radius", editorHeightBrushRadius, 1, 3)

            // Strength slider
            ImGui.setNextItemWidth(120f)
            ImGui.sliderInt("Strength", editorHeightStrength, 1, 10)

            ImGui.spacing()

            // Mode radio buttons
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
            ImGui.textColored(0.3f, 0.9f, 0.3f, 1f, "Left-click and drag to edit height")
        }
    }

    /**
     * FLAG_EDIT tool content: tile flag toggle checkboxes.
     */
    private fun renderEditorFlagEditTool() {
        if (ImGui.collapsingHeader("Flag Editor", ImGuiTreeNodeFlags.DefaultOpen)) {
            ImGui.textColored(0.7f, 0.7f, 0.7f, 1f, "Click tile to select, toggle flags below")
            ImGui.spacing()

            val tile = tilePicker.selectedTile
            val em = editManager

            if (tile == null) {
                ImGui.textColored(0.5f, 0.5f, 0.5f, 1f, "Select a tile first (left-click)")
            } else if (em == null) {
                ImGui.textColored(1f, 0.4f, 0.4f, 1f, "No edit manager available")
            } else {
                val x = tile.localX
                val y = tile.localY
                val currentFlags = em.mutableData.tiles[currentPlane][x][y].flags

                ImGui.text("Tile (%d, %d) - Flags: 0x%02X".format(x, y, currentFlags))
                ImGui.spacing()

                // Flag checkboxes -- each toggles a single bit
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

                // Apply changes if flags were modified
                if (newFlags != currentFlags) {
                    em.mutableData.setFlags(currentPlane, x, y, newFlags)
                    // Refresh tile details so the inspector shows updated data
                    tilePicker.updateTileDetails(
                        em.mutableData.toTileArray(),
                        cache,
                        asyncRegionManager.centerMapObjects,
                        currentRegionX * 64,
                        currentRegionY * 64
                    )
                }
            }
        }
    }

    // ========================================================================
    // Region Loading and Navigation
    // ========================================================================

    private fun setPlane(plane: Int) {
        currentPlane = plane.coerceIn(0, 3)
        loadRegion(currentRegionX, currentRegionY, currentPlane)
    }

    private fun navigateRegion(dx: Int, dy: Int) {
        val newX = (currentRegionX + dx).coerceIn(0, 255)
        val newY = (currentRegionY + dy).coerceIn(0, 255)
        jumpToRegion(newX, newY)
    }

    private fun jumpToRegion(regionX: Int, regionY: Int) {
        currentRegionX = regionX.coerceIn(0, 255)
        currentRegionY = regionY.coerceIn(0, 255)
        regionXInput.set(currentRegionX)
        regionYInput.set(currentRegionY)

        loadRegion(currentRegionX, currentRegionY, currentPlane)
    }

    /** Reload the current region (same coordinates and plane). */
    private fun reloadRegion() = loadRegion(currentRegionX, currentRegionY, currentPlane)

    /** Clear terrain cache and reload the current region. */
    private fun reloadTerrain() {
        TerrainLoader.clearCache()
        reloadRegion()
    }


    /** Redirect old loadRegion calls to the async version. */
    private fun loadRegion(regionX: Int, regionY: Int, plane: Int) = loadRegionAsync(regionX, regionY, plane)

    private fun loadRegionAsync(regionX: Int, regionY: Int, plane: Int) {
        // Environment first: the sun/fog/skybox of the region we are moving into, plus the
        // clear colour, are all read from the same map archive as the terrain.
        loadEnvironment(regionX, regionY)

        // Clear tile picker selection when loading new region
        tilePicker.clearSelection()
        tileHighlightMesh?.cleanup()
        tileHighlightMesh = null

        val regionLoadStart = RenderMetrics.startTiming()

        // Initialize chunked terrain via edit manager
        editManager?.cleanup()
        editManager = try {
            TerrainEditManager(regionX, regionY, plane, cache).also { it.init() }
        } catch (e: Exception) {
            println("[MapViewer] TerrainEditManager init failed for ($regionX, $regionY): ${e.message}")
            null
        }

        // Navigate to the new region (reuses existing context regions when possible)
        asyncRegionManager.renderDistance = renderDistanceInput[0]
        asyncRegionManager.showRoofs = showRoofs
        asyncRegionManager.navigateToRegion(regionX, regionY, plane)

        // Build the multi-draw-indirect renderer for the center region. Any failure here just
        // leaves indirectRenderer null, which sends renderObjects() down the InstanceManager path.
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
                // The region loader already decoded these meshes to register them with the
                // InstanceManager, so reuse them rather than paying for a second full decode
                // of every model in the region (~1.4s at Canifis).
                for ((modelId, meshDataList) in centerMeshData) {
                    if (meshDataList.isNotEmpty()) ir.registerModel(modelId, meshDataList)
                }
                ir.buildSharedGeometry()
                if (!ir.isReady()) throw IllegalStateException("no geometry registered")

                // Culling has to be set up before the first buildStaticScene(): with it on
                // the commands are left at zero instances for the compute pass to fill in,
                // with it off the full counts are baked in instead.
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

        // Record total region load time
        RenderMetrics.terrainLoadTimeMs = RenderMetrics.endTiming(regionLoadStart)

        updateAtlasStats()

        // Center camera on the world-space center of this region
        val tileSize = 512f * CoordinateUtil.DEFAULT_SCALE
        val worldCenterX = (regionX * 64 + 32) * tileSize
        val worldCenterZ = -(regionY * 64 + 32) * tileSize
        scene.camera.setPosition(worldCenterX, 50f, worldCenterZ + 80f)
        scene.camera.pitch = -35f
        scene.camera.yaw = 0f
        // Assigning yaw/pitch only stores the angles; front/right/up are derived and stay
        // at whatever they were until something rotates the camera. Without this the view
        // starts on the identity basis (front = -Z, horizontal) while the debug panel and
        // every log line report the -35 degree pitch that is not actually being applied.
        scene.camera.rotate(0f, 0f)
        scene.camera.nearPlane = 1.0f
        scene.camera.farPlane = 4000f

        applyCameraOverride(worldCenterX, worldCenterZ)
    }

    /**
     * Apply `-Ddarkan.camera=<dx>,<y>,<dz>,<yaw>,<pitch>`, placing the camera relative to
     * the region centre.
     *
     * A/B comparisons of culled against unculled frames are only meaningful from an
     * identical viewpoint, and pixel-diffing two captures needs that viewpoint to be
     * reproducible without touching the mouse.
     */
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
        scene.camera.rotate(0f, 0f)  // recompute the basis vectors from the new angles
        println("[MapViewer] camera override: pos=(${scene.camera.position.x}, ${scene.camera.position.y}, ${scene.camera.position.z}) yaw=${scene.camera.yaw} pitch=${scene.camera.pitch}")
    }

    /**
     * Seed [environment] from the centre region's map archive and push the result into the
     * sky renderer and the clear colour.
     *
     * There is one environment block per 64x64 region, so the centre chunk's copy is the
     * whole region's. Regions with no block fall back to the client's `Environment.reset()`
     * defaults rather than keeping the previous region's sun.
     */
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

    /**
     * Push the current [environment] into the sky renderer and the clear colour.
     * Called on region change and after every edit in the Environment panel, so edits show
     * up on the next frame without a reload.
     */
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

        // With no skybox the sky pass is a flat fill in the background colour, so the clear
        // colour matching it keeps the one pre-sky frame from flashing a different background.
        clearColor.set(
            EnvironmentSettings.red(environment.backgroundColour),
            EnvironmentSettings.green(environment.backgroundColour),
            EnvironmentSettings.blue(environment.backgroundColour)
        )
    }

    /**
     * Populate [RenderMetrics] atlas stats from current terrain and object atlases.
     */
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
        editManager?.cleanup()
        editManager = null
        indirectRenderer?.cleanup()
        indirectRenderer = null
        tilePicker.clearSelection()
        tileHighlightMesh?.cleanup()
        tileHighlightMesh = null
        placedObjects.clear()
        objectSceneDirty = true
        movingObject = null
        moveOriginMesh?.cleanup()
        moveOriginMesh = null
        brushPreviewMesh?.cleanup()
        brushPreviewMesh = null
        lastBrushCenter = null
    }










    /**
     * Clamp the current ImGui window position to stay within screen bounds.
     * Call this inside ImGui.begin() block.
     */
    private fun clampWindowToScreen() {
        val pos = ImGui.getWindowPos()
        val size = ImGui.getWindowSize()
        val screenWidth = window.width.toFloat()
        val screenHeight = window.height.toFloat()

        var newX = pos.x
        var newY = pos.y

        // Clamp X: keep at least 50px visible on screen
        if (newX + size.x < 50f) newX = 50f - size.x
        if (newX > screenWidth - 50f) newX = screenWidth - 50f

        // Clamp Y: keep title bar on screen (at least 20px from top)
        if (newY < 0f) newY = 0f
        if (newY > screenHeight - 50f) newY = screenHeight - 50f

        if (newX != pos.x || newY != pos.y) {
            ImGui.setWindowPos(newX, newY)
        }
    }

    /**
     * Dump the numbers a headless capture would otherwise hide, so a screenshot can be checked
     * against its draw-call and mesh-variant cost in the same log.
     */
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
        // Two captures are only comparable from the same viewpoint, and the yaw/pitch fields
        // are not by themselves the viewpoint: the basis vectors are recomputed on rotation,
        // so a frame can be drawn along a `front` that no longer matches the angles. Both are
        // recorded so a mismatch between them is visible in the log rather than as an
        // unexplained difference between two screenshots.
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
        gpuTimers.cleanup()
        cleanupRegion()
        asyncRegionManager.destroy()  // Cancel coroutine scope
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
        // Clear atlas lookup so TerrainLoader reverts to legacy mode
        TerrainLoader.atlasLookup = null
        TerrainLoader.clearCache()
        RegionLoader.clearCache()
        imGui.cleanup()
    }

    /**
     * Available editing tools for the Map Editor panel.
     * SELECT is the default mode (existing tile-pick behavior).
     * Other tools are placeholders for future editing functionality.
     */
    private enum class EditorTool(val label: String, val wip: Boolean = true) {
        SELECT("Select", wip = false),
        OBJECT_PLACE("Place Object", wip = false),
        OBJECT_MOVE("Move Object", wip = false),
        TERRAIN_PAINT("Paint Terrain", wip = false),
        HEIGHT_EDIT("Edit Height", wip = false),
        FLAG_EDIT("Edit Flags", wip = false)
    }
}

/**
 * Default for `-Ddarkan.rangeFuseGap`, in indices.
 *
 * Set from the two measured rates it balances: a static batch draw command costs about a
 * microsecond of CPU in this pass, and the GPU retires triangles at roughly 4 G/s, so a
 * command is worth about four thousand triangles — twelve thousand indices. Splitting a
 * batch to skip less than that spends more than it saves.
 */
private const val DEFAULT_RANGE_FUSE_GAP = 12000

/**
 * Region to open the map editor on, from -Ddarkan.region=<x>,<y>.
 * Lets a specific area be brought up for inspection without driving the UI.
 */
private fun regionProperty(): Pair<Int, Int>? {
    val parts = System.getProperty("darkan.region")?.split(',') ?: return null
    if (parts.size < 2) return null
    val x = parts[0].trim().toIntOrNull() ?: return null
    val y = parts[1].trim().toIntOrNull() ?: return null
    return x to y
}
