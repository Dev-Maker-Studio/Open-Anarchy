package org.darkan.tools.cache.texture

import org.lwjgl.opengl.EXTTextureFilterAnisotropic
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL13.GL_TEXTURE0
import org.lwjgl.opengl.GL13.glActiveTexture
import org.lwjgl.opengl.GL30.glGenerateMipmap
import org.lwjgl.system.MemoryUtil
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.MaterialDefinition
import world.gregs.voidps.cache.definition.decoder.MaterialDecoder

object TextureLoader {
    private val textureCache = mutableMapOf<Int, Int>()
    private val graphicDataCache = ConcurrentHashMap<Int, GraphicData>()
    private val textureDataCache = ConcurrentHashMap<Int, ByteArray>()
    private var defaultTextureId: Int = 0
    private var cacheRef: Cache? = null

    private var materialDefinitions: Array<MaterialDefinition> = emptyArray()

    private const val TEXTURE_SIZE_NORMAL = 128
    private const val TEXTURE_SIZE_SMALL = 64

    private val deadlineLocal: ThreadLocal<Long> = ThreadLocal.withInitial { 0L }

    private fun beginGeneration(): Long {
        val deadline = System.nanoTime() + TextureRaster.MAX_GENERATION_NANOS
        deadlineLocal.set(deadline)
        return deadline
    }

    fun init(cache: Cache) {
        initHeadless(cache)
        defaultTextureId = createCheckerTexture()
    }

    fun initHeadless(cache: Cache) {
        cacheRef = cache
        TextureRaster.calculateTable()

        materialDefinitions = MaterialDecoder().load(cache)
    }

    private fun getMaterial(materialId: Int): MaterialDefinition? {
        if (materialId < 0 || materialId >= materialDefinitions.size) return null
        return materialDefinitions[materialId]
    }

    private fun isMaterialOpaque(material: MaterialDefinition?): Boolean {
        if (material == null) return true
        val effectId = material.effectId.toInt()
        val effectCombiner = material.effectCombiner
        return effectCombiner != 2 && effectId != 1 && effectId != 7
    }

    fun isOpaque(textureId: Int): Boolean {
        if (textureId < 0) return true
        val material = getMaterial(textureId) ?: return true
        val effectId = material.effectId.toInt()
        val effectCombiner = material.effectCombiner

        if (effectCombiner != 2 && effectId != 1 && effectId != 7) return true

        if (effectCombiner == 0) {
            return material.alpha.toInt() >= 0
        }

        return false
    }

    fun shouldSkipMaterial(materialId: Int): Boolean {
        if (materialId < 0) return false
        return getMaterial(materialId)?.skipTriangles ?: false
    }

    private fun getTextureSize(material: MaterialDefinition?): Int {
        if (material == null) return TEXTURE_SIZE_NORMAL
        return if (material.isSmall) TEXTURE_SIZE_SMALL else TEXTURE_SIZE_NORMAL
    }

    fun getTextureForMaterial(cache: Cache, textureId: Int): Int {
        if (textureId < 0) return defaultTextureId

        return textureCache.getOrPut(textureId) {
            val textureType = loadTextureType(cache, textureId)

            if (textureType != null) {
                val material = getMaterial(textureId)
                val textureSize = getTextureSize(material)
                val opaque = isMaterialOpaque(material)
                val deadline = beginGeneration()

                val pixels = if (opaque) {
                    textureType.getPixelsRgb(
                        textureSize,
                        textureSize,
                        graphicLoader = { graphicId ->
                            loadGraphicData(cache, graphicId)
                        },
                        textureLoader = { materialId, width, height ->
                            generateTexturePixels(cache, materialId, width, height)
                        },
                        brightness = 0.7,
                        deadlineNanos = deadline
                    )
                } else {
                    textureType.getPixelsArgb(
                        textureSize,
                        textureSize,
                        graphicLoader = { graphicId ->
                            loadGraphicData(cache, graphicId)
                        },
                        textureLoader = { materialId, width, height ->
                            generateTexturePixels(cache, materialId, width, height)
                        },
                        brightness = 0.7,
                        deadlineNanos = deadline
                    )
                }

                if (material != null && material.effectCombiner != 2) {
                    val effectCombiner = material.effectCombiner
                    if (effectCombiner == 0) {
                        val signedAlpha = material.alpha.toInt()
                        val constAlpha = if (signedAlpha >= 0) 255 else (signedAlpha and 0xFF)
                        for (i in pixels.indices) {
                            val px = pixels[i]
                            pixels[i] = (constAlpha shl 24) or (px and 0x00FFFFFF)
                        }
                    } else {
                        for (i in pixels.indices) {
                            val px = pixels[i]
                            val rgb = px and 0x00FFFFFF
                            pixels[i] = if (rgb != 0) (0xFF shl 24) or rgb else 0
                        }
                    }
                }

                return@getOrPut createTextureFromArgb(pixels, textureSize, textureSize)
            }

            val directGraphic = loadGraphicAsTexture(cache, textureId)
            if (directGraphic != null) {
                return@getOrPut directGraphic
            }

            defaultTextureId
        }
    }

    fun getTexturePixelsForMaterial(cache: Cache, textureId: Int): Triple<IntArray, Int, Int>? {
        if (textureId < 0) return null
        if (textureId in generatingSet.get()) return null

        materialPixelCache[textureId]?.let {
            TexturePixelStats.recordHit()
            return it
        }

        val textureType = loadTextureType(cache, textureId) ?: return null

        val material = getMaterial(textureId)
        val textureSize = getTextureSize(material)
        val opaque = isMaterialOpaque(material)

        val statsStart = System.nanoTime()
        generatingSet.get().add(textureId)
        val deadline = beginGeneration()
        val pixels = try {
            if (opaque) {
                textureType.getPixelsRgb(
                    textureSize,
                    textureSize,
                    graphicLoader = { graphicId -> loadGraphicData(cache, graphicId) },
                    textureLoader = { materialId, width, height ->
                        generateTexturePixels(cache, materialId, width, height)
                    },
                    brightness = 0.7,
                    deadlineNanos = deadline
                )
            } else {
                textureType.getPixelsArgb(
                    textureSize,
                    textureSize,
                    graphicLoader = { graphicId -> loadGraphicData(cache, graphicId) },
                    textureLoader = { materialId, width, height ->
                        generateTexturePixels(cache, materialId, width, height)
                    },
                    brightness = 0.7,
                    deadlineNanos = deadline
                )
            }
        } finally {
            generatingSet.get().remove(textureId)
        }

        if (material != null && material.effectCombiner != 2) {
            val effectCombiner = material.effectCombiner
            if (effectCombiner == 0) {
                val signedAlpha = material.alpha.toInt()
                val constAlpha = if (signedAlpha >= 0) 255 else (signedAlpha and 0xFF)
                for (i in pixels.indices) {
                    val px = pixels[i]
                    pixels[i] = (constAlpha shl 24) or (px and 0x00FFFFFF)
                }
            } else {
                for (i in pixels.indices) {
                    val px = pixels[i]
                    val rgb = px and 0x00FFFFFF
                    pixels[i] = if (rgb != 0) (0xFF shl 24) or rgb else 0
                }
            }
        }

        TexturePixelStats.record(textureId, System.nanoTime() - statsStart, pixels.size)
        val result = Triple(pixels, textureSize, textureSize)
        retainPixels(textureId, result)
        return result
    }

    private val materialPixelCache = ConcurrentHashMap<Int, Triple<IntArray, Int, Int>>()

    private val materialPixelCacheBytes = AtomicLong(0)

    private val materialPixelCacheEnabled: Boolean =
        System.getProperty("darkan.texPixelCache", "true").toBoolean()

    private val materialPixelCacheBudgetBytes: Long =
        (System.getProperty("darkan.texPixelCacheMiB")?.toLongOrNull() ?: 64L) * 1024L * 1024L

    private fun retainPixels(textureId: Int, result: Triple<IntArray, Int, Int>) {
        if (!materialPixelCacheEnabled) return
        val bytes = result.first.size.toLong() * Int.SIZE_BYTES
        if (materialPixelCacheBytes.get() + bytes > materialPixelCacheBudgetBytes) return
        if (materialPixelCache.putIfAbsent(textureId, result) == null) {
            materialPixelCacheBytes.addAndGet(bytes)
        }
    }

    fun getTextureSizeForMaterial(textureId: Int): Int {
        if (textureId < 0) return TEXTURE_SIZE_NORMAL
        return getTextureSize(getMaterial(textureId))
    }

    private val pixelCache = ConcurrentHashMap<Int, IntArray>()
    private val generatingSet: ThreadLocal<MutableSet<Int>> = ThreadLocal.withInitial { mutableSetOf() }

    private fun generateTexturePixels(cache: Cache, textureId: Int, @Suppress("UNUSED_PARAMETER") width: Int, @Suppress("UNUSED_PARAMETER") height: Int): IntArray? {
        pixelCache[textureId]?.let { return it }

        val inProgress = generatingSet.get()
        if (textureId in inProgress) return null

        val textureType = loadTextureType(cache, textureId) ?: return null

        val nestedSize = getTextureSize(getMaterial(textureId))

        inProgress.add(textureId)
        val pixels = try {
            textureType.getPixelsRgb(
                nestedSize,
                nestedSize,
                graphicLoader = { graphicId ->
                    loadGraphicData(cache, graphicId)
                },
                textureLoader = { materialId, w, h ->
                    generateTexturePixels(cache, materialId, w, h)
                },
                brightness = 1.0,
                deadlineNanos = deadlineLocal.get()
            )
        } finally {
            inProgress.remove(textureId)
        }

        return pixelCache.putIfAbsent(textureId, pixels) ?: pixels
    }

    private fun loadTextureType(cache: Cache, textureId: Int): TextureType? {
        val data = textureDataCache[textureId] ?: run {
            val loaded = cache.data(Index.TEXTURES, textureId) ?: return null
            if (loaded.isEmpty()) return null
            textureDataCache.putIfAbsent(textureId, loaded) ?: loaded
        }
        return try {
            TextureType(data)
        } catch (e: Exception) {
            null
        }
    }

    private fun loadGraphicData(cache: Cache, graphicId: Int): GraphicData? {
        graphicDataCache[graphicId]?.let { return it }
        val data = cache.data(Index.GRAPHICS, graphicId) ?: return null
        val graphic = decodeGraphicToData(data, 0) ?: return null
        return graphicDataCache.putIfAbsent(graphicId, graphic) ?: graphic
    }

    private fun loadGraphicAsTexture(cache: Cache, graphicId: Int): Int? {
        val graphicData = loadGraphicData(cache, graphicId) ?: return null
        return createTextureFromArgb(graphicData.pixels, graphicData.width, graphicData.height)
    }

    private fun decodeGraphicToData(data: ByteArray, subIndex: Int): GraphicData? {
        if (data.size < 10) return null

        val buffer = BufferReader(data)

        buffer.position(data.size - 2)
        val graphicCount = buffer.readShort() and 0xFFFF
        if (graphicCount == 0 || subIndex >= graphicCount) return null

        buffer.position(data.size - 7 - graphicCount * 8)
        val biggestWidth = buffer.readShort()
        val biggestHeight = buffer.readShort()
        val paletteSize = buffer.readUnsignedByte() + 1

        val minX = IntArray(graphicCount)
        val minY = IntArray(graphicCount)
        val widths = IntArray(graphicCount)
        val heights = IntArray(graphicCount)

        for (i in 0 until graphicCount) minX[i] = buffer.readShort()
        for (i in 0 until graphicCount) minY[i] = buffer.readShort()
        for (i in 0 until graphicCount) widths[i] = buffer.readShort()
        for (i in 0 until graphicCount) heights[i] = buffer.readShort()

        val innerWidth = widths[subIndex]
        val innerHeight = heights[subIndex]
        val offsetX = minX[subIndex]
        val offsetY = minY[subIndex]
        if (innerWidth <= 0 || innerHeight <= 0) return null

        val fullWidth = if (biggestWidth > 0) biggestWidth else innerWidth
        val fullHeight = if (biggestHeight > 0) biggestHeight else innerHeight

        buffer.position(data.size - 7 - graphicCount * 8 - (paletteSize - 1) * 3)
        val palette = IntArray(paletteSize)
        for (i in 1 until paletteSize) {
            palette[i] = buffer.readUnsignedMedium()
            if (palette[i] == 0) palette[i] = 1
        }

        buffer.position(0)
        var raster: ByteArray? = null
        var alpha: ByteArray? = null

        for (i in 0 until graphicCount) {
            val area = widths[i] * heights[i]
            val flags = buffer.readUnsignedByte()
            val hasAlpha = (flags and 0x2) != 0
            val columnMajor = (flags and 0x1) != 0

            val graphicRaster = ByteArray(area)
            val graphicAlpha = if (hasAlpha) ByteArray(area) else null

            if (!columnMajor) {
                for (p in 0 until area) {
                    graphicRaster[p] = buffer.readByte().toByte()
                }
                if (hasAlpha) {
                    for (p in 0 until area) {
                        graphicAlpha!![p] = buffer.readByte().toByte()
                    }
                }
            } else {
                for (x in 0 until widths[i]) {
                    for (y in 0 until heights[i]) {
                        graphicRaster[x + y * widths[i]] = buffer.readByte().toByte()
                    }
                }
                if (hasAlpha) {
                    for (x in 0 until widths[i]) {
                        for (y in 0 until heights[i]) {
                            graphicAlpha!![x + y * widths[i]] = buffer.readByte().toByte()
                        }
                    }
                }
            }

            if (i == subIndex) {
                raster = graphicRaster
                alpha = graphicAlpha
            }
        }

        if (raster == null) return null

        val pixels = IntArray(fullWidth * fullHeight)
        for (y in 0 until innerHeight) {
            val srcRowStart = y * innerWidth
            val destRowStart = (y + offsetY) * fullWidth + offsetX
            for (x in 0 until innerWidth) {
                val srcIdx = srcRowStart + x
                val destIdx = destRowStart + x
                if (destIdx < pixels.size) {
                    val paletteIdx = raster[srcIdx].toInt() and 0xFF
                    val rgb = palette[paletteIdx]
                    val a = if (alpha != null) {
                        alpha[srcIdx].toInt() and 0xFF
                    } else {
                        if (paletteIdx == 0) 0 else 255
                    }
                    pixels[destIdx] = (a shl 24) or rgb
                }
            }
        }

        return GraphicData(pixels, fullWidth, fullHeight)
    }

    private fun createTextureFromArgb(pixels: IntArray, width: Int, height: Int): Int {
        val textureId = glGenTextures()
        glBindTexture(GL_TEXTURE_2D, textureId)

        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)

        if (GL.getCapabilities().GL_EXT_texture_filter_anisotropic) {
            val maxAnisotropy = glGetFloat(EXTTextureFilterAnisotropic.GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT)
            glTexParameterf(GL_TEXTURE_2D, EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT, maxAnisotropy)
        }

        val buffer = MemoryUtil.memAlloc(width * height * 4)
        try {
            for (y in (height - 1) downTo 0) {
                for (x in 0 until width) {
                    val pixel = pixels[y * width + x]
                    buffer.put(((pixel shr 16) and 0xFF).toByte())
                    buffer.put(((pixel shr 8) and 0xFF).toByte())
                    buffer.put((pixel and 0xFF).toByte())
                    buffer.put(((pixel shr 24) and 0xFF).toByte())
                }
            }
            buffer.flip()

            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, buffer)
            glGenerateMipmap(GL_TEXTURE_2D)
        } finally {
            MemoryUtil.memFree(buffer)
        }

        glBindTexture(GL_TEXTURE_2D, 0)
        return textureId
    }

    private fun createCheckerTexture(): Int {
        val size = 64
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val checker = ((x / 8) + (y / 8)) % 2 == 0
                val gray = if (checker) 200 else 100
                pixels[y * size + x] = (255 shl 24) or (gray shl 16) or (gray shl 8) or gray
            }
        }
        return createTextureFromArgb(pixels, size, size)
    }

    fun bindTexture(textureId: Int, unit: Int = 0) {
        glActiveTexture(GL_TEXTURE0 + unit)
        glBindTexture(GL_TEXTURE_2D, textureId)
    }

    fun unbindTexture() {
        glBindTexture(GL_TEXTURE_2D, 0)
    }

    fun decodeGraphicToDataPublic(data: ByteArray, subIndex: Int): GraphicData? {
        return decodeGraphicToData(data, subIndex)
    }

    fun dumpTextureImage(cache: Cache, materialId: Int, outputPath: String) {
        val textureType = loadTextureType(cache, materialId)
        if (textureType == null) {
            println("dumpTextureImage: Could not load texture type for material $materialId")
            return
        }
        val material = getMaterial(materialId)
        val textureSize = getTextureSize(material)
        val opaque = isMaterialOpaque(material)

        val pixels = if (opaque) {
            textureType.getPixelsRgb(
                textureSize, textureSize,
                graphicLoader = { graphicId -> loadGraphicData(cache, graphicId) },
                textureLoader = { matId, w, h -> generateTexturePixels(cache, matId, w, h) },
                brightness = 0.7
            )
        } else {
            textureType.getPixelsArgb(
                textureSize, textureSize,
                graphicLoader = { graphicId -> loadGraphicData(cache, graphicId) },
                textureLoader = { matId, w, h -> generateTexturePixels(cache, matId, w, h) },
                brightness = 0.7
            )
        }

        if (material != null && material.effectCombiner != 2) {
            val effectCombiner = material.effectCombiner
            if (effectCombiner == 0) {
                val signedAlpha = material.alpha.toInt()
                val constAlpha = if (signedAlpha >= 0) 255 else (signedAlpha and 0xFF)
                for (i in pixels.indices) {
                    val px = pixels[i]
                    pixels[i] = (constAlpha shl 24) or (px and 0x00FFFFFF)
                }
            } else {
                for (i in pixels.indices) {
                    val px = pixels[i]
                    val rgb = px and 0x00FFFFFF
                    pixels[i] = if (rgb != 0) (0xFF shl 24) or rgb else 0
                }
            }
        }

        val base = outputPath.substringBeforeLast('.', outputPath)
        val colorPath = "$base.png"
        val alphaPath = "${base}_alpha.png"

        val colorImage = BufferedImage(textureSize, textureSize, BufferedImage.TYPE_INT_RGB)
        val alphaImage = BufferedImage(textureSize, textureSize, BufferedImage.TYPE_INT_RGB)
        val colorRow = IntArray(textureSize)
        val alphaRow = IntArray(textureSize)
        for (y in 0 until textureSize) {
            val rowStart = y * textureSize
            for (x in 0 until textureSize) {
                val pixel = pixels[rowStart + x]
                colorRow[x] = pixel and 0x00FFFFFF
                val a = (pixel shr 24) and 0xFF
                alphaRow[x] = (a shl 16) or (a shl 8) or a
            }
            colorImage.setRGB(0, y, textureSize, 1, colorRow, 0, textureSize)
            alphaImage.setRGB(0, y, textureSize, 1, alphaRow, 0, textureSize)
        }
        ImageIO.write(colorImage, "png", File(colorPath))
        ImageIO.write(alphaImage, "png", File(alphaPath))

        var nonBlack = 0
        var transparent = 0
        for (pixel in pixels) {
            val rgb = pixel and 0x00FFFFFF
            val a = (pixel shr 24) and 0xFF
            if (rgb != 0) nonBlack++
            if (a == 0) transparent++
        }
        println("dumpTextureImage($materialId): ${textureSize}x${textureSize}, opaque=$opaque, effectCombiner=${material?.effectCombiner}, nonBlack=$nonBlack/${pixels.size}, transparent=$transparent/${pixels.size}")
        println("  Saved colour to $colorPath")
        println("  Saved alpha to $alphaPath")
    }

    fun clearCpuCaches() {
        graphicDataCache.clear()
        textureDataCache.clear()
        pixelCache.clear()
        materialPixelCache.clear()
        materialPixelCacheBytes.set(0)
        generatingSet.get().clear()
    }

    fun clearCache() {
        textureCache.values.forEach { glDeleteTextures(it) }
        textureCache.clear()
        clearCpuCaches()
        if (defaultTextureId != 0) {
            glDeleteTextures(defaultTextureId)
            defaultTextureId = 0
        }
    }

}
