package org.darkan.core.cache

import org.darkan.core.EnvVars
import org.darkan.core.Logger.logInfo
import org.darkan.core.city.City
import org.darkan.core.map.WorldWindow
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.MapDefinition
import world.gregs.voidps.cache.source.ArchiveMetadata
import world.gregs.voidps.cache.source.SourceTree
import world.gregs.voidps.cache.source.codec.MapGenerator
import world.gregs.voidps.cache.source.codec.WorldGenerator
import world.gregs.voidps.cache.store.Compression
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The world map of the whole sector, cut into blocks a client fetches as it looks at them.
 *
 * The map of the original client is one file per area (`worldmapdata/main/4.dat`, see [WorldMapImage]), which a client
 * downloads and builds whole; at the size of a sector that is about 500 MB. Here every block of [BLOCK_SQUARES] by
 * [BLOCK_SQUARES] map squares is an archive of its own in index 23 (`FIRST_BLOCK_ARCHIVE + blockX * BLOCK_STRIDE + blockY`,
 * file 0: the square records of [WorldMapImage], without the two leading table counts), and archive [MANIFEST_ARCHIVE]
 * describes them (file 0) and holds a coarse picture of the sector for the overview (file 1). Blocks with no terrain have
 * no archive. Clients that do not know the stream keep reading the one-file map.
 */
object WorldMapStream {

    const val MANIFEST_ARCHIVE = 100
    const val FIRST_BLOCK_ARCHIVE = 1000
    const val BLOCK_STRIDE = 64
    const val BLOCK_SQUARES = 4
    const val OVERVIEW_CELL_TILES = 16
    const val VERSION = 1

    private const val SQUARE_TILES = MapDefinition.SIZE
    const val SECTOR_SQUARES = (WorldWindow.WINDOW_TILES + SQUARE_TILES - 1) / SQUARE_TILES
    private const val BLOCKS = (SECTOR_SQUARES + BLOCK_SQUARES - 1) / BLOCK_SQUARES
    private const val CELLS_PER_SQUARE = SQUARE_TILES / OVERVIEW_CELL_TILES
    private const val OVERVIEW_CELLS = SECTOR_SQUARES * CELLS_PER_SQUARE
    private const val CELL_BYTES = 2
    private const val SAMPLE_OFFSET = OVERVIEW_CELL_TILES / 2
    private const val MAP_NAME = "main"
    private const val DIRECTORY = "worldmapdata"
    private const val MANIFEST_FILE = 0
    private const val OVERVIEW_FILE = 1
    private const val BLOCK_FILE = 0
    private const val ARCHIVE_VERSION = 1
    private val MAGIC = byteArrayOf('O'.code.toByte(), 'A'.code.toByte(), 'W'.code.toByte(), 'M'.code.toByte())

    private const val RECORD_HEADER = 3
    private const val EXTENDED = 1
    private const val OVERLAY_FLAG = 2
    private const val FLOOR_SHIFT = 2
    private const val FLOOR_MASK = 0x3f
    private const val FLOOR_EXPLICIT = 63
    private const val HAS_OVERLAY = 0x8
    private const val HAS_LOCS = 0x10
    private const val BIG_SMART_BYTE = 0x80
    private const val BYTE = 0xff

    fun blockArchive(blockX: Int, blockY: Int): Int = FIRST_BLOCK_ARCHIVE + blockX * BLOCK_STRIDE + blockY

    fun write(tree: File, generator: MapGenerator, cities: List<City>) {
        val visited = WorldMapImage.visitedSquares()
        val full = WorldMapImage.fullRebuild()
        val overview = ByteArray(OVERVIEW_CELLS * OVERVIEW_CELLS * CELL_BYTES)
        val present = ConcurrentHashMap.newKeySet<Int>()
        val rebuilt = AtomicInteger()
        val changed = AtomicInteger()
        val bytes = AtomicLong()
        val pool = Executors.newFixedThreadPool(maxOf(1, EnvVars.cachePackThreads)) { work -> Thread(work, "world-map-stream").apply { isDaemon = true } }
        try {
            (0 until BLOCKS * BLOCKS).map { block ->
                pool.submit {
                    val blockX = block / BLOCKS
                    val blockY = block % BLOCKS
                    val out = ByteArrayOutputStream()
                    for (regionX in blockX * BLOCK_SQUARES until minOf((blockX + 1) * BLOCK_SQUARES, SECTOR_SQUARES)) {
                        for (regionY in blockY * BLOCK_SQUARES until minOf((blockY + 1) * BLOCK_SQUARES, SECTOR_SQUARES)) {
                            if (generator is WorldGenerator && !generator.inArea(regionX, regionY) && !WorldMapImage.hasSaved(tree, regionX, regionY)) continue
                            val (data, fresh) = WorldMapImage.square(tree, generator, cities, regionX, regionY, visited, full)
                            if (fresh) rebuilt.incrementAndGet()
                            sample(data, regionX, regionY, overview)
                            out.write(data)
                        }
                    }
                    if (out.size() > 0) {
                        val archive = blockArchive(blockX, blockY)
                        present += archive
                        bytes.addAndGet(out.size().toLong())
                        if (replace(File(tree, "$DIRECTORY/$archive/$BLOCK_FILE.dat"), out.toByteArray())) changed.incrementAndGet()
                    }
                }
            }.forEach { it.get() }
        } finally {
            pool.shutdown()
        }
        replace(File(tree, "$DIRECTORY/$MANIFEST_ARCHIVE/$MANIFEST_FILE.dat"), manifest())
        replace(File(tree, "$DIRECTORY/$MANIFEST_ARCHIVE/$OVERVIEW_FILE.dat"), overviewFile(overview))
        index(tree, present)
        logInfo("World map stream: ${present.size} blocks (${changed.get()} changed, ${bytes.get()} bytes), ${rebuilt.get()} squares rebuilt, for ${SECTOR_SQUARES * SQUARE_TILES} x ${SECTOR_SQUARES * SQUARE_TILES} tiles")
    }

    private fun manifest(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(MAGIC)
        out.write(VERSION)
        out.write(BLOCK_SQUARES)
        out.write(BLOCKS)
        out.write(BLOCKS)
        out.write(BLOCK_STRIDE)
        out.write(OVERVIEW_CELL_TILES)
        short(out, FIRST_BLOCK_ARCHIVE)
        short(out, SECTOR_SQUARES * SQUARE_TILES)
        short(out, SECTOR_SQUARES * SQUARE_TILES)
        out.write(MAP_NAME.toByteArray(Charsets.ISO_8859_1))
        out.write(0)
        return out.toByteArray()
    }

    private fun overviewFile(cells: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(cells.size + 4)
        short(out, OVERVIEW_CELLS)
        short(out, OVERVIEW_CELLS)
        out.write(cells)
        return out.toByteArray()
    }

    private fun short(out: ByteArrayOutputStream, value: Int) {
        out.write(value shr 8)
        out.write(value)
    }

    private fun sample(record: ByteArray, regionX: Int, regionY: Int, overview: ByteArray) {
        var at = RECORD_HEADER
        for (localX in 0 until SQUARE_TILES) {
            for (localY in 0 until SQUARE_TILES) {
                val flags = record[at++].toInt() and BYTE
                var underlay = 0
                var overlay = 0
                if (flags and EXTENDED == 0) {
                    val floor = flags shr FLOOR_SHIFT and FLOOR_MASK
                    if (floor == FLOOR_EXPLICIT) {
                        if (flags and OVERLAY_FLAG != 0) {
                            overlay = record[at++].toInt() and BYTE
                            underlay = record[at++].toInt() and BYTE
                        } else {
                            underlay = record[at++].toInt() and BYTE
                        }
                    }
                } else {
                    underlay = record[at++].toInt() and BYTE
                    if (flags and HAS_OVERLAY != 0) {
                        overlay = record[at++].toInt() and BYTE
                        at++
                    }
                    if (flags and HAS_LOCS != 0) {
                        val count = record[at++].toInt() and BYTE
                        repeat(count) {
                            at += if (record[at].toInt() and BIG_SMART_BYTE != 0) 4 else 2
                            at++
                        }
                    }
                }
                if (localX % OVERVIEW_CELL_TILES == SAMPLE_OFFSET && localY % OVERVIEW_CELL_TILES == SAMPLE_OFFSET) {
                    val cellX = regionX * CELLS_PER_SQUARE + localX / OVERVIEW_CELL_TILES
                    val cellY = regionY * CELLS_PER_SQUARE + localY / OVERVIEW_CELL_TILES
                    val cell = (cellX * OVERVIEW_CELLS + cellY) * CELL_BYTES
                    overview[cell] = underlay.toByte()
                    overview[cell + 1] = overlay.toByte()
                }
            }
        }
    }

    private fun index(tree: File, blocks: Set<Int>) {
        val source = SourceTree.open(tree)
        val metadata = source.metadata(Index.WORLD_MAP)
        val before = metadata.write()
        val gone = metadata.archives.keys.filter { it >= FIRST_BLOCK_ARCHIVE && it !in blocks }
        for (archive in gone) {
            metadata.archives.remove(archive)
            File(tree, "$DIRECTORY/$archive").deleteRecursively()
        }
        for (archive in blocks) {
            if (archive !in metadata.archives) metadata.archives[archive] = ArchiveMetadata(version = ARCHIVE_VERSION, compression = Compression.GZIP, file = BLOCK_FILE)
        }
        if (MANIFEST_ARCHIVE !in metadata.archives) {
            metadata.archives[MANIFEST_ARCHIVE] = ArchiveMetadata(version = ARCHIVE_VERSION, compression = Compression.GZIP).apply { fileIds(intArrayOf(MANIFEST_FILE, OVERVIEW_FILE)) }
        }
        if (metadata.write() != before) source.writeMetadata(metadata)
    }

    private fun replace(file: File, data: ByteArray): Boolean {
        if (file.isFile && file.length() == data.size.toLong() && file.readBytes().contentEquals(data)) return false
        file.parentFile.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeBytes(data)
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return true
    }
}
