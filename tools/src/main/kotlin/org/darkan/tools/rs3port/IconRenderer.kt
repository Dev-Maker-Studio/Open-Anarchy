package org.darkan.tools.rs3port

import org.darkan.tools.cache.model.ModelLoader
import org.darkan.tools.cache.texture.TextureLoader
import org.darkan.tools.engine.render.ShaderProgram
import org.joml.Matrix4f
import org.joml.Vector3f
import org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MAJOR
import org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MINOR
import org.lwjgl.glfw.GLFW.GLFW_FALSE
import org.lwjgl.glfw.GLFW.GLFW_OPENGL_CORE_PROFILE
import org.lwjgl.glfw.GLFW.GLFW_OPENGL_FORWARD_COMPAT
import org.lwjgl.glfw.GLFW.GLFW_OPENGL_PROFILE
import org.lwjgl.glfw.GLFW.GLFW_TRUE
import org.lwjgl.glfw.GLFW.GLFW_VISIBLE
import org.lwjgl.glfw.GLFW.glfwCreateWindow
import org.lwjgl.glfw.GLFW.glfwDestroyWindow
import org.lwjgl.glfw.GLFW.glfwInit
import org.lwjgl.glfw.GLFW.glfwMakeContextCurrent
import org.lwjgl.glfw.GLFW.glfwTerminate
import org.lwjgl.glfw.GLFW.glfwWindowHint
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11.GL_BACK
import org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT
import org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT
import org.lwjgl.opengl.GL11.GL_DEPTH_TEST
import org.lwjgl.opengl.GL11.GL_LEQUAL
import org.lwjgl.opengl.GL11.GL_RGBA
import org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE
import org.lwjgl.opengl.GL11.glClear
import org.lwjgl.opengl.GL11.glClearColor
import org.lwjgl.opengl.GL11.glDepthFunc
import org.lwjgl.opengl.GL11.glEnable
import org.lwjgl.opengl.GL11.glReadBuffer
import org.lwjgl.opengl.GL11.glReadPixels
import org.lwjgl.opengl.GL11.glViewport
import org.lwjgl.system.MemoryUtil
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.definition.data.ItemDefinition
import world.gregs.voidps.cache.definition.decoder.ItemDecoder
import world.gregs.voidps.cache.source.codec.ModelGltf
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.atan

class IconRenderer(private val cache: Cache, private val glbDirectory: Path?) {

    private val decoder = ItemDecoder()
    private val definitions: Array<ItemDefinition> = decoder.create(decoder.size(cache) + 1)
    private var handle: Long = 0
    private lateinit var colourShader: ShaderProgram
    private lateinit var texturedShader: ShaderProgram

    fun open() {
        check(glfwInit()) { "GLFW would not start; run this under a display or Xvfb." }
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3)
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE)
        handle = glfwCreateWindow(SURFACE, SURFACE, "Darkan Icons", 0, 0)
        check(handle != 0L) { "could not create an offscreen GL window." }
        glfwMakeContextCurrent(handle)
        GL.createCapabilities()
        glEnable(GL_DEPTH_TEST)
        glDepthFunc(GL_LEQUAL)
        colourShader = ShaderProgram.createWithUniforms(
            source("/shaders/color.vert"), source("/shaders/color.frag"),
            "model", "view", "projection", "ambientLight", "lightDirection", "lightColor"
        )
        texturedShader = ShaderProgram.createWithUniforms(
            source("/shaders/textured.vert"), source("/shaders/textured.frag"),
            "model", "view", "projection", "ambientLight", "lightDirection", "lightColor",
            "textureSampler", "useTexture"
        )
        TextureLoader.init(cache)
    }

    fun close() {
        colourShader.cleanup()
        texturedShader.cleanup()
        glfwDestroyWindow(handle)
        glfwTerminate()
    }

    fun definition(obj: Int): ItemDefinition? {
        if (obj < 0 || obj >= definitions.size) {
            return null
        }
        decoder.load(definitions, cache, obj)
        return definitions[obj]
    }

    fun render(obj: Int): BufferedImage? {
        val definition = definition(obj) ?: return null
        return render(
            definition.modelId, definition.graphicScale, definition.graphicPitch,
            definition.graphicCameraRoll, definition.graphicCameraYaw,
            definition.graphicTranslateX, definition.graphicTranslateY
        )
    }

    fun render(modelId: Int, scale: Int, pitch: Int, roll: Int, yaw: Int, translateX: Int, translateY: Int): BufferedImage? {
        val model = load(modelId) ?: return null
        glViewport(0, 0, WIDTH, HEIGHT)
        glClearColor(0f, 0f, 0f, 0f)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)

        val distance = (scale * 4).coerceAtLeast(1) * RS_UNIT
        val view = Matrix4f().lookAt(0f, 0f, distance, 0f, 0f, 0f, 0f, 1f, 0f)
        val projection = Matrix4f()
            .perspective(FIELD_OF_VIEW, WIDTH.toFloat() / HEIGHT.toFloat(), NEAR_PLANE, distance * 4f + FAR_PADDING)
        val centre = centre(modelId)
        val transform = Matrix4f()
            .translate(translateX * RS_UNIT, -translateY * RS_UNIT, 0f)
            .rotateX(turns(pitch))
            .rotateY(turns(yaw))
            .rotateZ(turns(roll))
            .translate(-centre.x, -centre.y, -centre.z)

        model.colorBatch?.let { batch ->
            colourShader.bind()
            uniforms(colourShader, view, projection, transform)
            batch.render()
            colourShader.unbind()
        }
        if (model.textureBatches.isNotEmpty()) {
            texturedShader.bind()
            uniforms(texturedShader, view, projection, transform)
            texturedShader.setUniform("textureSampler", 0)
            texturedShader.setUniform("useTexture", true)
            for (batch in model.textureBatches) {
                TextureLoader.bindTexture(TextureLoader.getTextureForMaterial(cache, batch.textureId), 0)
                batch.mesh.render()
            }
            TextureLoader.unbindTexture()
            texturedShader.unbind()
        }
        model.cleanup()
        return readBack()
    }

    private fun uniforms(shader: ShaderProgram, view: Matrix4f, projection: Matrix4f, transform: Matrix4f) {
        shader.setUniform("view", view)
        shader.setUniform("projection", projection)
        shader.setUniform("model", transform)
        shader.setUniform("ambientLight", AMBIENT)
        shader.setUniform("lightDirection", LIGHT_DIRECTION)
        shader.setUniform("lightColor", LIGHT_COLOUR)
    }

    private fun load(modelId: Int) = definition(modelId, glbDirectory)?.let {
        ModelLoader.createTexturedModel(it, cache = cache)
    }

    private fun definition(modelId: Int, directory: Path?) =
        directory?.resolve("$modelId.glb")?.takeIf { Files.exists(it) }
            ?.let { ModelGltf.read(Files.readAllBytes(it), modelId) }
            ?: ModelLoader.loadDefinition(cache, modelId)

    private fun centre(modelId: Int): Vector3f {
        val model = definition(modelId, glbDirectory) ?: return Vector3f()
        if (model.vertexCount == 0) {
            return Vector3f()
        }
        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var minY = Int.MAX_VALUE
        var maxY = Int.MIN_VALUE
        var minZ = Int.MAX_VALUE
        var maxZ = Int.MIN_VALUE
        for (vertex in 0 until model.vertexCount) {
            minX = minOf(minX, model.vertexX[vertex])
            maxX = maxOf(maxX, model.vertexX[vertex])
            minY = minOf(minY, model.vertexY[vertex])
            maxY = maxOf(maxY, model.vertexY[vertex])
            minZ = minOf(minZ, model.vertexZ[vertex])
            maxZ = maxOf(maxZ, model.vertexZ[vertex])
        }
        return Vector3f(
            (minX + maxX) * 0.5f * RS_UNIT,
            -(minY + maxY) * 0.5f * RS_UNIT,
            -(minZ + maxZ) * 0.5f * RS_UNIT
        )
    }

    private fun readBack(): BufferedImage {
        val pixels = MemoryUtil.memAlloc(WIDTH * HEIGHT * 4)
        try {
            glReadBuffer(GL_BACK)
            glReadPixels(0, 0, WIDTH, HEIGHT, GL_RGBA, GL_UNSIGNED_BYTE, pixels)
            val image = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until HEIGHT) {
                val row = (HEIGHT - 1 - y) * WIDTH * 4
                for (x in 0 until WIDTH) {
                    val offset = row + x * 4
                    val red = pixels.get(offset).toInt() and 0xff
                    val green = pixels.get(offset + 1).toInt() and 0xff
                    val blue = pixels.get(offset + 2).toInt() and 0xff
                    val alpha = pixels.get(offset + 3).toInt() and 0xff
                    image.setRGB(x, y, (alpha shl 24) or (red shl 16) or (green shl 8) or blue)
                }
            }
            return image
        } finally {
            MemoryUtil.memFree(pixels)
        }
    }

    private fun source(resource: String): String =
        IconRenderer::class.java.getResourceAsStream(resource)?.bufferedReader()?.readText()
            ?: throw IllegalStateException("shader $resource is missing.")

    companion object {

        const val WIDTH = 36

        const val HEIGHT = 32

        private const val SURFACE = 256

        private const val RS_UNIT = 1f / 128f

        private const val TURN_UNITS = 2048f

        private const val NEAR_PLANE = 0.05f

        private const val FAR_PADDING = 64f

        /**
         * RS projects with a focal length of 512 pixels whatever the viewport is, so the vertical
         * field of view follows the icon's own height rather than a 512 pixel reference frame.
         */
        private val FIELD_OF_VIEW = (2.0 * atan(HEIGHT / 2.0 / 512.0)).toFloat()

        private val AMBIENT = Vector3f(0.55f, 0.55f, 0.55f)

        private val LIGHT_DIRECTION = Vector3f(-0.4f, -0.7f, -0.6f).normalize()

        private val LIGHT_COLOUR = Vector3f(1f, 1f, 1f)

        private fun turns(angle: Int): Float = (angle / TURN_UNITS) * 2f * PI.toFloat()
    }
}

private const val ZOOM = 4

private const val COLUMNS = 6

fun runIcon(cache: Cache, args: List<String>) {
    if (args.size < 2) {
        println("icon <objId>[,<objId>...] <out.png> [--sheet] [--wiki <dir>] [--glb <dir>]")
        return
    }
    val objs = args[0].split(',').flatMap { part ->
        if (part.contains('-')) {
            val bounds = part.split('-')
            (bounds[0].toInt()..bounds[1].toInt()).toList()
        } else {
            listOf(part.toInt())
        }
    }
    val out = File(args[1])
    val sheet = args.contains("--sheet")
    val wiki = args.indexOf("--wiki").takeIf { it >= 0 }?.let { File(args[it + 1]) }
    val glb = args.indexOf("--glb").takeIf { it >= 0 }?.let { Path.of(args[it + 1]) }
    val renderer = IconRenderer(cache, glb)
    renderer.open()
    try {
        val cells = objs.mapNotNull { obj ->
            val definition = renderer.definition(obj)
            val image = renderer.render(obj) ?: return@mapNotNull null
            Cell("$obj ${definition?.name ?: ""}".trim(), image, wiki?.let { fetch(it, definition?.name) })
        }
        if (!sheet && cells.size == 1) {
            out.parentFile?.mkdirs()
            ImageIO.write(cells[0].image, "png", out)
        } else {
            out.parentFile?.mkdirs()
            ImageIO.write(contactSheet(cells), "png", out)
        }
        println("rendered ${cells.size} of ${objs.size} objs to $out")
    } finally {
        renderer.close()
    }
}

private class Cell(val label: String, val image: BufferedImage, val reference: BufferedImage?)

private fun fetch(directory: File, name: String?): BufferedImage? {
    if (name == null || name == "null") {
        return null
    }
    directory.mkdirs()
    val file = File(directory, name.replace(' ', '_') + ".png")
    if (!file.exists()) {
        val url = "https://runescape.wiki/w/Special:FilePath/" + name.replace(' ', '_') + ".png"
        ProcessBuilder("curl", "-L", "-s", "-f", "-o", file.absolutePath, url)
            .redirectErrorStream(true).start().waitFor()
    }
    return if (file.exists() && file.length() > 0) runCatching { ImageIO.read(file) }.getOrNull() else null
}

private fun contactSheet(cells: List<Cell>): BufferedImage {
    val tile = IconRenderer.WIDTH * ZOOM
    val height = IconRenderer.HEIGHT * ZOOM
    val cellWidth = tile * 2 + 12
    val cellHeight = height + 20
    val rows = (cells.size + COLUMNS - 1) / COLUMNS
    val sheet = BufferedImage(cellWidth * COLUMNS, cellHeight * rows, BufferedImage.TYPE_INT_RGB)
    val graphics = sheet.createGraphics()
    graphics.color = Color(32, 32, 36)
    graphics.fillRect(0, 0, sheet.width, sheet.height)
    graphics.font = Font(Font.MONOSPACED, Font.PLAIN, 11)
    for ((index, cell) in cells.withIndex()) {
        val x = (index % COLUMNS) * cellWidth
        val y = (index / COLUMNS) * cellHeight
        graphics.color = Color(200, 200, 205)
        graphics.drawString(cell.label.take(44), x + 4, y + 13)
        graphics.drawImage(cell.image, x + 4, y + 18, tile, height, null)
        cell.reference?.let { graphics.drawImage(it, x + tile + 8, y + 18, tile, height, null) }
    }
    graphics.dispose()
    return sheet
}
