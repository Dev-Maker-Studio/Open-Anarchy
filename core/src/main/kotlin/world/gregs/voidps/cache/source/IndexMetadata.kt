package world.gregs.voidps.cache.source

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import world.gregs.voidps.cache.store.BZip2Variant
import world.gregs.voidps.cache.store.Compression
import world.gregs.voidps.cache.store.ReferenceTable

class ArchiveMetadata(
    var version: Int = 0,
    var compression: Compression = Compression.GZIP,
    var gzipOs: Int = 0,
    var bzip2: BZip2Variant = BZip2Variant.LIBBZIP2,
    var nameHash: Int = 0,
    var file: Int = 0,
    var fileCount: Int = 1,
    var files: IntArray? = null,
    var fileNames: Map<Int, Int> = emptyMap(),
    var xtea: IntArray? = null,
    var pristine: Map<Int, String> = emptyMap(),
    var opaque: Boolean = false
) {

    fun fileIds(): IntArray {
        files?.let { return it }
        if (fileCount > 1) {
            return IntArray(fileCount) { it }
        }
        return intArrayOf(file)
    }

    fun fileIds(ids: IntArray) {
        require(ids.isNotEmpty()) { "An archive has at least one file." }
        val dense = ids.withIndex().all { (position, id) -> id == position }
        if (ids.size == 1) {
            file = ids[0]
            fileCount = 1
            files = null
        } else if (dense) {
            file = 0
            fileCount = ids.size
            files = null
        } else {
            file = 0
            fileCount = ids.size
            files = ids
        }
    }

    fun write(): String {
        val fields = ArrayList<String>(6)
        if (version != 0) {
            fields.add("\"version\": $version")
        }
        fields.add("\"compression\": ${SourceJson.quote(compression.name.lowercase())}")
        if (gzipOs != 0) {
            fields.add("\"gzipOs\": $gzipOs")
        }
        if (bzip2 != BZip2Variant.LIBBZIP2) {
            fields.add("\"bzip2\": ${SourceJson.quote(bzip2.id)}")
        }
        if (nameHash != 0) {
            fields.add("\"nameHash\": $nameHash")
        }
        if (file != 0) {
            fields.add("\"file\": $file")
        }
        if (fileCount != 1) {
            fields.add("\"fileCount\": $fileCount")
        }
        files?.let {
            fields.add("\"files\": ${SourceJson.array(it)}")
        }
        if (fileNames.isNotEmpty()) {
            fields.add("\"fileNames\": ${SourceJson.intMap(fileNames)}")
        }
        xtea?.let {
            fields.add("\"xtea\": ${SourceJson.array(it)}")
        }
        if (pristine.isNotEmpty()) {
            fields.add("\"pristine\": ${SourceJson.stringMap(pristine)}")
        }
        if (opaque) {
            fields.add("\"opaque\": true")
        }
        return fields.joinToString(", ", "{", "}")
    }

    companion object {

        fun read(json: JsonObject): ArchiveMetadata {
            val metadata = ArchiveMetadata()
            json["version"]?.let { metadata.version = it.jsonPrimitive.int }
            json["compression"]?.let { metadata.compression = compression(it.jsonPrimitive.content) }
            json["gzipOs"]?.let { metadata.gzipOs = it.jsonPrimitive.int }
            json["bzip2"]?.let { metadata.bzip2 = BZip2Variant.of(it.jsonPrimitive.content) }
            json["nameHash"]?.let { metadata.nameHash = it.jsonPrimitive.int }
            json["file"]?.let { metadata.file = it.jsonPrimitive.int }
            json["fileCount"]?.let { metadata.fileCount = it.jsonPrimitive.int }
            json["files"]?.let { element ->
                val ids = element.jsonArray.map { it.jsonPrimitive.int }.toIntArray()
                metadata.files = ids
                metadata.fileCount = ids.size
            }
            json["fileNames"]?.let { element ->
                metadata.fileNames = element.jsonObject.entries.associate { (key, value) ->
                    key.toInt() to value.jsonPrimitive.int
                }
            }
            json["xtea"]?.let { element ->
                metadata.xtea = element.jsonArray.map { it.jsonPrimitive.int }.toIntArray()
            }
            json["pristine"]?.let { element ->
                metadata.pristine = element.jsonObject.entries.associate { (key, value) ->
                    key.toInt() to value.jsonPrimitive.content
                }
            }
            json["opaque"]?.let { metadata.opaque = it.jsonPrimitive.boolean }
            return metadata
        }

        internal fun compression(name: String): Compression = when (name.lowercase()) {
            "none" -> Compression.NONE
            "bzip2" -> Compression.BZIP2
            "gzip" -> Compression.GZIP
            else -> throw IllegalArgumentException("Unknown container compression '$name'.")
        }
    }
}

class IndexMetadata(
    val index: Int,
    var format: Int = ReferenceTable.MAX_FORMAT,
    var revision: Int = 0,
    var named: Boolean = false,
    var whirlpool: Boolean = false,
    var compression: Compression = Compression.GZIP,
    var bzip2: BZip2Variant = BZip2Variant.LIBBZIP2,
    val archives: MutableMap<Int, ArchiveMetadata> = HashMap()
) {

    fun archiveIds(): IntArray = archives.keys.sorted().toIntArray()

    fun archive(id: Int): ArchiveMetadata? = archives[id]

    fun flags(): List<String> {
        val flags = ArrayList<String>(2)
        if (named) {
            flags.add(NAMES)
        }
        if (whirlpool) {
            flags.add(WHIRLPOOL)
        }
        return flags
    }

    fun write(): String {
        val out = StringBuilder(archives.size * 48 + 128)
        out.append("{\n")
        out.append("  \"index\": ").append(index).append(",\n")
        out.append("  \"format\": ").append(format).append(",\n")
        out.append("  \"revision\": ").append(revision).append(",\n")
        out.append("  \"flags\": ").append(SourceJson.strings(flags())).append(",\n")
        out.append("  \"compression\": ").append(SourceJson.quote(compression.name.lowercase())).append(",\n")
        if (bzip2 != BZip2Variant.LIBBZIP2) {
            out.append("  \"bzip2\": ").append(SourceJson.quote(bzip2.id)).append(",\n")
        }
        out.append("  \"archives\": {")
        val ids = archiveIds()
        for ((position, id) in ids.withIndex()) {
            out.append("\n    ").append(SourceJson.quote(id.toString())).append(": ")
            out.append(archives.getValue(id).write())
            if (position != ids.size - 1) {
                out.append(',')
            }
        }
        if (ids.isNotEmpty()) {
            out.append("\n  ")
        }
        out.append("}\n}\n")
        return out.toString()
    }

    companion object {
        const val NAMES = "names"
        const val WHIRLPOOL = "whirlpool"

        private val json = Json { ignoreUnknownKeys = true }

        fun read(text: String, fallbackIndex: Int = -1): IndexMetadata {
            val root = json.parseToJsonElement(text).jsonObject
            val index = root["index"]?.jsonPrimitive?.int ?: fallbackIndex
            require(index >= 0) { "index.json has no index and none was supplied." }
            val flags = root["flags"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            val metadata = IndexMetadata(
                index = index,
                format = root["format"]?.jsonPrimitive?.int ?: ReferenceTable.MAX_FORMAT,
                revision = root["revision"]?.jsonPrimitive?.int ?: 0,
                named = flags.contains(NAMES),
                whirlpool = flags.contains(WHIRLPOOL)
            )
            root["compression"]?.let { metadata.compression = ArchiveMetadata.compression(it.jsonPrimitive.content) }
            root["bzip2"]?.let { metadata.bzip2 = BZip2Variant.of(it.jsonPrimitive.content) }
            val archives = root["archives"]?.jsonObject ?: return metadata
            for ((key, value) in archives) {
                metadata.archives[key.toInt()] = ArchiveMetadata.read(value.jsonObject)
            }
            return metadata
        }
    }
}
