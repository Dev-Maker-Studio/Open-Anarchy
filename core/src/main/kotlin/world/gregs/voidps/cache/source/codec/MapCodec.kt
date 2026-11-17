package world.gregs.voidps.cache.source.codec

import it.unimi.dsi.fastutil.ints.Int2IntMap
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.MapSquare
import world.gregs.voidps.cache.definition.data.MapDefinition
import world.gregs.voidps.cache.definition.data.MapObject
import world.gregs.voidps.cache.definition.decoder.MapNpcDecoder
import world.gregs.voidps.cache.definition.decoder.MapObjectDecoder
import world.gregs.voidps.cache.definition.decoder.MapTileDecoder
import world.gregs.voidps.cache.definition.encoder.MapNpcEncoder.writeSpawns
import world.gregs.voidps.cache.definition.encoder.MapObjectEncoder
import world.gregs.voidps.cache.definition.encoder.MapTileEncoder
import world.gregs.voidps.cache.source.ArchiveMetadata
import world.gregs.voidps.cache.source.IndexMetadata
import world.gregs.voidps.cache.source.SourceFiles
import world.gregs.voidps.cache.source.SourceTree
import java.io.IOException
import java.nio.BufferOverflowException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

object MapCodec : SourceCodec {

    override val id: String
        get() = "map-json"

    @Volatile
    var generator: MapGenerator? = null

    @Volatile
    var reserved: Set<Int> = emptySet()

    override fun fileIdsFromMetadata(archive: Int): Boolean = true

    override fun exact(archive: Int): Boolean = false

    override fun unpack(directory: Path, archive: SourceArchive): UnpackedArchive {
        val square = square(archive.metadata.nameHash, archive.archive)
        check(archive.files.size == 1) {
            "Map archive ${archive.archive} (${square.name}) has ${archive.files.size} files; " +
                "$id writes one file per archive."
        }
        val data = archive.files[0]
        val name = path(square)
        val text = write(square, data)
        val files = ArrayList<SourceFile>(2)
        files.add(SourceFile(name, text))
        if (!compile(square, text).contentEquals(data)) {
            files.add(SourceFile(SourceFiles.pristine(name), data))
            return UnpackedArchive(files, mapOf(archive.fileIds[0] to SourceFiles.sha256(text)))
        }
        return UnpackedArchive(files)
    }

    override fun pack(directory: Path, archive: Int, metadata: ArchiveMetadata): PackedArchive {
        val square = square(metadata.nameHash, archive)
        val ids = metadata.fileIds()
        check(ids.size == 1) { "Map archive $archive (${square.name}) is one file on disk but its metadata lists ${ids.size}." }
        outsideArea(square)?.let { return PackedArchive(it, ids) }
        val file = directory.resolve(path(square))
        if (!Files.isRegularFile(file)) {
            val worldGenerator = generator as? WorldGenerator
            if (square.shape == MapSquare.Shape.UNDERWATER_TERRAIN && worldGenerator != null) {
                val wet = worldGenerator.underwaterTerrain(square.regionX, square.regionY, surfaceOf(directory, square))
                return PackedArchive(if (wet == null) sharedDryBed(square, worldGenerator) else compile(square, wet), ids)
            }
            if (square.shape == MapSquare.Shape.UNDERWATER_LOCATIONS && worldGenerator != null) {
                return PackedArchive(sharedBedLocations(square, worldGenerator), ids)
            }
            val generated = generator?.let { generate(it, square, null) }
            if (generated != null) {
                return PackedArchive(compile(square, generated), ids)
            }
            throw IOException("Map archive $archive (${square.name}) has no JSON at ${file.toAbsolutePath()}.")
        }
        val text = Files.readAllBytes(file)
        val sidecar = SourceFiles.pristine(file)
        val hash = metadata.pristine[ids[0]]
        if (hash != null && Files.isRegularFile(sidecar)) {
            if (SourceFiles.sha256(text) == hash) {
                return PackedArchive(Files.readAllBytes(sidecar), ids)
            }
            Files.delete(sidecar)
        }
        return PackedArchive(compile(square, text), ids)
    }

    override fun archiveOf(path: String): Int? = null

    override fun archiveOf(directory: Path, path: String): Int? {
        val square = square(SourceFiles.guarded(path) ?: path) ?: return null
        val archive = lookup(directory).get(square.name.hashCode())
        return if (archive == ABSENT) null else archive
    }

    override fun files(directory: Path, archive: Int): List<Path> {
        val hash = hashes(directory).get(archive)
        if (hash == ABSENT) {
            return emptyList()
        }
        val square = MapSquare.of(hash) ?: return emptyList()
        val file = directory.resolve(path(square))
        if (!Files.isRegularFile(file)) {
            return emptyList()
        }
        val sidecar = SourceFiles.pristine(file)
        return if (Files.isRegularFile(sidecar)) listOf(file, sidecar) else listOf(file)
    }

    private val outside = java.util.concurrent.ConcurrentHashMap<MapSquare.Shape, ByteArray>()

    private fun outsideArea(square: MapSquare.Square): ByteArray? {
        val generator = generator as? WorldGenerator ?: return null
        if (generator.inArea(square.regionX, square.regionY)) return null
        val surface = square.shape == MapSquare.Shape.TERRAIN || square.shape == MapSquare.Shape.LOCATIONS
        if (surface && square.region in reserved) return null
        return when (square.shape) {
            MapSquare.Shape.TERRAIN -> outside.computeIfAbsent(square.shape) { compile(square, generator.voidTerrain(square.regionX, square.regionY)) }
            MapSquare.Shape.LOCATIONS -> outside.computeIfAbsent(square.shape) { compile(square, generator.emptyLocations(square.regionX, square.regionY)) }
            MapSquare.Shape.UNDERWATER_TERRAIN -> sharedDryBed(square, generator)
            MapSquare.Shape.UNDERWATER_LOCATIONS -> sharedBedLocations(square, generator)
            else -> null
        }
    }

    private fun sharedBedLocations(square: MapSquare.Square, generator: WorldGenerator): ByteArray =
        outside.computeIfAbsent(square.shape) { compile(square, generator.underwaterLocations(square.regionX, square.regionY)) }

    private fun sharedDryBed(square: MapSquare.Square, generator: WorldGenerator): ByteArray =
        outside.computeIfAbsent(square.shape) { compile(square, generator.dryUnderwaterTerrain(square.regionX, square.regionY)) }

    fun generate(generator: MapGenerator, square: MapSquare.Square): ByteArray? = generate(generator, square, null)

    private fun surfaceOf(directory: Path, square: MapSquare.Square): ByteArray? {
        if (square.shape != MapSquare.Shape.UNDERWATER_TERRAIN) {
            return null
        }
        val file = directory.resolve(path(MapSquare.Square(MapSquare.Shape.TERRAIN, square.regionX, square.regionY)))
        return if (Files.isRegularFile(file)) Files.readAllBytes(file) else null
    }

    private fun generate(generator: MapGenerator, square: MapSquare.Square, surface: ByteArray?): ByteArray? = when (square.shape) {
        MapSquare.Shape.TERRAIN -> generator.terrain(square.regionX, square.regionY)
        MapSquare.Shape.LOCATIONS -> generator.locations(square.regionX, square.regionY)
        MapSquare.Shape.UNDERWATER_TERRAIN -> (generator as? WorldGenerator)?.let {
            it.underwaterTerrain(square.regionX, square.regionY, surface) ?: it.dryUnderwaterTerrain(square.regionX, square.regionY)
        }
        MapSquare.Shape.UNDERWATER_LOCATIONS -> (generator as? WorldGenerator)?.underwaterLocations(square.regionX, square.regionY)
        else -> null
    }

    fun path(square: MapSquare.Square): String = "${square.directory}/${square.shape.file}$EXTENSION"

    private fun square(nameHash: Int, archive: Int): MapSquare.Square {
        check(nameHash != 0) { "Map archive $archive has no name hash, so nothing says which map square it is." }
        return MapSquare.of(nameHash)
            ?: error("Map archive $archive's name hash $nameHash is no map square's; index 5 holds nothing else.")
    }

    private fun square(path: String): MapSquare.Square? {
        if (!path.endsWith(EXTENSION)) {
            return null
        }
        val separator = path.indexOf('/')
        if (separator <= 0 || path.indexOf('/', separator + 1) >= 0) {
            return null
        }
        val shape = MapSquare.Shape.ofFile(path.substring(separator + 1, path.length - EXTENSION.length))
            ?: return null
        val region = path.substring(0, separator)
        val coordinates = region.indexOf('_')
        if (coordinates <= 0) {
            return null
        }
        val regionX = region.substring(0, coordinates).toIntOrNull() ?: return null
        val regionY = region.substring(coordinates + 1).toIntOrNull() ?: return null
        if (regionX !in 0..0xff || regionY !in 0..0xff) {
            return null
        }
        return MapSquare.Square(shape, regionX, regionY)
    }

    private fun write(square: MapSquare.Square, data: ByteArray): ByteArray = when (square.shape) {
        MapSquare.Shape.TERRAIN, MapSquare.Shape.UNDERWATER_TERRAIN ->
            MapJson.writeTerrain(MapTileDecoder.decodeArchive(data, square.region, square.shape.levels), square)
        MapSquare.Shape.LOCATIONS, MapSquare.Shape.UNDERWATER_LOCATIONS ->
            MapJson.writeLocs(locations(data, square), square)
        MapSquare.Shape.NPCS -> MapJson.writeNpcs(MapNpcDecoder.decode(data, square.region), square)
    }

    private fun compile(square: MapSquare.Square, text: ByteArray): ByteArray {
        var capacity = capacity(square)
        while (true) {
            val writer = BufferWriter(capacity)
            try {
                when (square.shape) {
                    MapSquare.Shape.TERRAIN, MapSquare.Shape.UNDERWATER_TERRAIN ->
                        with(tileEncoder) { writer.encode(MapJson.readTerrain(text, square)) }
                    MapSquare.Shape.LOCATIONS, MapSquare.Shape.UNDERWATER_LOCATIONS ->
                        with(locationEncoder) { writer.encode(MapJson.readLocs(text, square)) }
                    MapSquare.Shape.NPCS -> writer.writeSpawns(MapJson.readNpcs(text, square))
                }
                return writer.toArray()
            } catch (e: BufferOverflowException) {
                capacity *= 2
                check(capacity <= LIMIT) { "${square.name} does not encode into $LIMIT bytes." }
            }
        }
    }

    private fun locations(data: ByteArray, square: MapSquare.Square): MapDefinition {
        val definition = MapDefinition(square.region, levels = 0)
        val decoder = object : MapObjectDecoder() {
            override fun add(
                objectId: Int,
                localX: Int,
                localY: Int,
                level: Int,
                shape: Int,
                rotation: Int,
                regionTileX: Int,
                regionTileY: Int
            ) {
                definition.objects.add(MapObject(objectId, localX, localY, level, shape, rotation))
            }
        }
        val end = decoder.decode(data, ByteArray(0), square.regionX, square.regionY)
        if (end < data.size) {
            definition.trailing = data.copyOfRange(end, data.size)
        }
        return definition
    }

    private fun capacity(square: MapSquare.Square): Int = when (square.shape) {
        MapSquare.Shape.TERRAIN -> 128 * 1024
        MapSquare.Shape.UNDERWATER_TERRAIN -> 32 * 1024
        else -> 64 * 1024
    }

    private fun lookup(directory: Path): Int2IntMap = cached(directory).names

    private fun hashes(directory: Path): Int2IntMap = cached(directory).archives

    private fun cached(directory: Path): Lookup {
        val file = directory.resolve(SourceTree.INDEX_FILE)
        val size: Long
        val modified: Long
        try {
            size = Files.size(file)
            modified = Files.getLastModifiedTime(file).toMillis()
        } catch (e: IOException) {
            return Lookup.EMPTY
        }
        val existing = lookups[directory]
        if (existing != null && existing.size == size && existing.modified == modified) {
            return existing
        }
        val built = read(file, size, modified) ?: return Lookup.EMPTY
        lookups[directory] = built
        return built
    }

    private fun read(file: Path, size: Long, modified: Long): Lookup? {
        val metadata = try {
            IndexMetadata.read(Files.readString(file))
        } catch (e: Exception) {
            return null
        }
        val names = Int2IntOpenHashMap(metadata.archives.size)
        names.defaultReturnValue(ABSENT)
        val archives = Int2IntOpenHashMap(metadata.archives.size)
        archives.defaultReturnValue(ABSENT)
        for ((archive, entry) in metadata.archives) {
            if (entry.nameHash == 0) {
                continue
            }
            names.put(entry.nameHash, archive)
            archives.put(archive, entry.nameHash)
        }
        return Lookup(size, modified, names, archives)
    }

    private class Lookup(
        val size: Long,
        val modified: Long,
        val names: Int2IntMap,
        val archives: Int2IntMap
    ) {
        companion object {
            val EMPTY = Lookup(-1, -1, empty(), empty())

            private fun empty(): Int2IntMap {
                val map = Int2IntOpenHashMap(0)
                map.defaultReturnValue(ABSENT)
                return map
            }
        }
    }

    private val lookups = ConcurrentHashMap<Path, Lookup>()

    private val tileEncoder = MapTileEncoder()

    private val locationEncoder = MapObjectEncoder()

    private const val ABSENT = -1

    private const val EXTENSION = ".json"

    private const val LIMIT = 8 * 1024 * 1024
}
