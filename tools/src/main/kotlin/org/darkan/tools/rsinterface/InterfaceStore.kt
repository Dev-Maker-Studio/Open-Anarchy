package org.darkan.tools.rsinterface

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.ReadOnlyCache
import world.gregs.voidps.cache.definition.data.InterfaceComponentDefinition
import world.gregs.voidps.cache.definition.data.InterfaceComponentHook
import world.gregs.voidps.cache.definition.data.InterfaceComponentSetting
import world.gregs.voidps.cache.definition.decoder.InterfaceDecoder
import world.gregs.voidps.cache.definition.encoder.InterfaceEncoder
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.lang.reflect.Field
import java.nio.BufferOverflowException

class CacheNotWritableException(message: String) : IllegalStateException(message)

data class SaveOptions(
    val overwrite: Boolean = true,
    val backup: Boolean = true,
    val exportJson: Boolean = true,
    val padStaleFiles: Boolean = true,
    val verify: Boolean = true
)

data class FieldDifference(
    val componentIndex: Int,
    val field: String,
    val written: String,
    val decoded: String,
    val derived: Boolean = false
) {
    override fun toString(): String = "[$componentIndex] $field: wrote $written, read back $decoded"
}

data class SaveResult(
    val interfaceId: Int,
    val files: Int,
    val bytes: Int,
    val staleFiles: List<Int>,
    val backupFile: File?,
    val jsonFile: File?,
    val differences: List<FieldDifference>,
    val committed: Boolean,
    val warnings: List<String>
)

class InterfaceStore(
    private val cache: Cache,
    val directory: File = File(DEFAULT_DIRECTORY),
    val backupDirectory: File = File(directory, "backups")
) {

    private val decoder = InterfaceDecoder()
    private val encoder = InterfaceEncoder()
    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    val writable: Boolean = cache !is ReadOnlyCache

    val readOnlyReason: String? = if (writable) {
        null
    } else {
        "${cache.javaClass.simpleName} extends ReadOnlyCache: write() throws " +
            "UnsupportedOperationException and update() returns false, so nothing can be " +
            "committed to cache index ${Index.INTERFACES}"
    }


    fun exists(id: Int): Boolean = id >= 0 && cache.files(Index.INTERFACES, id).isNotEmpty()

    fun lastInterfaceId(): Int = cache.lastArchiveId(Index.INTERFACES)

    fun open(id: Int): InterfaceDocument = InterfaceDocument.decode(cache, id)

    fun newInterface(id: Int, overwrite: Boolean = false): InterfaceDocument {
        require(id >= 0) { "Interface id must not be negative" }
        check(overwrite || !exists(id)) {
            "Interface $id already exists with ${cache.files(Index.INTERFACES, id).size} components; " +
                "pass overwrite = true to start from scratch anyway"
        }
        return InterfaceDocument.empty(id)
    }


    fun encode(definition: InterfaceComponentDefinition): ByteArray {
        check(definition.id != -1) {
            "Component id is -1; InterfaceEncoder writes nothing for an unassigned id, which " +
                "would silently produce an empty cache file"
        }
        var capacity = estimateSize(definition)
        while (true) {
            try {
                val writer = BufferWriter(capacity)
                encoder.run { writer.encode(definition) }
                return writer.toArray()
            } catch (_: BufferOverflowException) {
                check(capacity < MAX_COMPONENT_BYTES) {
                    "Component ${definition.id} did not fit in $MAX_COMPONENT_BYTES bytes"
                }
                capacity *= 2
            }
        }
    }

    fun encode(document: InterfaceDocument): Map<Int, ByteArray> {
        val files = LinkedHashMap<Int, ByteArray>(document.componentCount)
        document.components.forEachIndexed { index, definition ->
            files[index] = encode(definition)
        }
        return files
    }


    fun verifyRoundTrip(definition: InterfaceComponentDefinition, componentIndex: Int): List<FieldDifference> {
        val bytes = try {
            encode(definition)
        } catch (e: Exception) {
            return listOf(FieldDifference(componentIndex, "<encode>", "", e.toString()))
        }
        val fresh = InterfaceComponentDefinition(id = definition.id)
        try {
            decoder.run { fresh.read(BufferReader(bytes)) }
        } catch (e: Exception) {
            return listOf(
                FieldDifference(componentIndex, "<decode>", "${bytes.size} bytes", e.toString())
            )
        }
        val differences = ArrayList<FieldDifference>()
        for (field in ComponentFields.fields) {
            if (field.name in ComponentFields.transientFields) {
                continue
            }
            val written = ComponentFields.get(definition, field)
            val decoded = ComponentFields.get(fresh, field)
            if (!ComponentFields.same(written, decoded)) {
                differences.add(
                    FieldDifference(
                        componentIndex,
                        field.name,
                        ComponentFields.describe(written),
                        ComponentFields.describe(decoded),
                        field.name in ComponentFields.derivedFields
                    )
                )
            }
        }
        return differences
    }

    fun verifyRoundTrip(document: InterfaceDocument): List<FieldDifference> {
        val differences = ArrayList<FieldDifference>()
        document.components.forEachIndexed { index, definition ->
            differences.addAll(verifyRoundTrip(definition, index))
        }
        return differences
    }


    fun save(document: InterfaceDocument, options: SaveOptions = SaveOptions()): SaveResult {
        check(options.overwrite || !exists(document.id)) {
            "Interface ${document.id} already exists; pass SaveOptions(overwrite = true) to replace it"
        }
        val warnings = ArrayList<String>()
        val files = encode(document)
        val differences = if (options.verify) verifyRoundTrip(document) else emptyList()
        val lossy = differences.filter { !it.derived }
        if (lossy.isNotEmpty()) {
            warnings.add(
                "${lossy.size} field(s) across ${lossy.map { it.componentIndex }.distinct().size} " +
                    "component(s) do not survive an encode/decode round-trip"
            )
        }
        val existing = cache.files(Index.INTERFACES, document.id)
        val stale = existing.filter { it >= document.componentCount }.sorted()
        val backupFile = if (options.backup && existing.isNotEmpty()) backup(document.id) else null
        val jsonFile = if (options.exportJson) exportJson(document) else null
        if (stale.isNotEmpty()) {
            warnings.add(
                "${stale.size} file(s) past the last component (${stale.joinToString()}) cannot be " +
                    "removed - the cache API only writes files, it never deletes them" +
                    if (options.padStaleFiles) "; overwriting them with inert placeholders" else ""
            )
        }
        if (!writable) {
            throw CacheNotWritableException(
                "Cannot write interface ${document.id}: $readOnlyReason. " +
                    (jsonFile?.let { "The JSON sidecar was written to $it. " } ?: "") +
                    (backupFile?.let { "A backup of the current archive is at $it." } ?: "")
            )
        }
        var bytes = 0
        for ((file, data) in files) {
            cache.write(Index.INTERFACES, document.id, file, data)
            bytes += data.size
        }
        if (options.padStaleFiles) {
            for (file in stale) {
                val placeholder = encode(inertPlaceholder(document.id, file))
                cache.write(Index.INTERFACES, document.id, file, placeholder)
                bytes += placeholder.size
            }
        }
        val committed = cache.update()
        if (!committed) {
            warnings.add("Cache.update() returned false; the index was not committed")
        } else {
            document.markSaved()
        }
        return SaveResult(
            interfaceId = document.id,
            files = files.size,
            bytes = bytes,
            staleFiles = stale,
            backupFile = backupFile,
            jsonFile = jsonFile,
            differences = differences,
            committed = committed,
            warnings = warnings
        )
    }

    fun saveAs(document: InterfaceDocument, newId: Int, options: SaveOptions = SaveOptions()): SaveResult =
        save(document.copyAs(newId), options)

    fun saveSidecar(document: InterfaceDocument): File {
        if (exists(document.id)) {
            backup(document.id)
        }
        return exportJson(document)
    }

    fun inertPlaceholder(interfaceId: Int, index: Int): InterfaceComponentDefinition {
        val placeholder = ComponentDefinitions.create(interfaceId, index, ComponentType.CONTAINER)
        placeholder.hidden = true
        placeholder.parent = ComponentDefinitions.ROOT
        placeholder.name = "deleted"
        return placeholder
    }


    fun backupFile(id: Int): File = File(backupDirectory, "interface-$id-${System.currentTimeMillis()}.bin")

    fun backup(id: Int, file: File = backupFile(id)): File? {
        val fileIds = cache.files(Index.INTERFACES, id)
        if (fileIds.isEmpty()) {
            return null
        }
        file.parentFile?.mkdirs()
        DataOutputStream(file.outputStream().buffered()).use { out ->
            out.writeInt(BACKUP_MAGIC)
            out.writeInt(BACKUP_VERSION)
            out.writeInt(id)
            out.writeInt(fileIds.size)
            for (fileId in fileIds) {
                val data = cache.data(Index.INTERFACES, id, fileId) ?: ByteArray(0)
                out.writeInt(fileId)
                out.writeInt(data.size)
                out.write(data)
            }
        }
        return file
    }

    fun readBackup(file: File): Map<Int, ByteArray> {
        DataInputStream(file.inputStream().buffered()).use { input ->
            check(input.readInt() == BACKUP_MAGIC) { "$file is not an interface backup" }
            val version = input.readInt()
            check(version == BACKUP_VERSION) { "Unsupported backup version $version in $file" }
            input.readInt()
            val count = input.readInt()
            val files = LinkedHashMap<Int, ByteArray>(count)
            repeat(count) {
                val fileId = input.readInt()
                val data = ByteArray(input.readInt())
                input.readFully(data)
                files[fileId] = data
            }
            return files
        }
    }


    fun jsonFile(id: Int): File = File(directory, "interface-$id.json")

    fun exportJson(document: InterfaceDocument, file: File = jsonFile(document.id)): File {
        file.parentFile?.mkdirs()
        file.writeText(toJson(document))
        return file
    }

    fun importJson(file: File): InterfaceDocument = fromJson(file.readText())

    fun toJson(document: InterfaceDocument): String {
        val defaults = InterfaceComponentDefinition()
        val components = document.components.mapIndexed { index, definition ->
            val fields = LinkedHashMap<String, JsonElement>()
            fields["index"] = JsonPrimitive(index)
            for (field in ComponentFields.fields) {
                if (field.name in ComponentFields.transientFields || field.name == "id") {
                    continue
                }
                val value = ComponentFields.get(definition, field)
                if (field.name != "type" && field.name != "parent" &&
                    ComponentFields.same(value, ComponentFields.get(defaults, field))
                ) {
                    continue
                }
                fields[field.name] = encodeField(field, value)
            }
            JsonObject(fields)
        }
        val root = JsonObject(
            linkedMapOf(
                "format" to JsonPrimitive(JSON_FORMAT),
                "interfaceId" to JsonPrimitive(document.id),
                "components" to JsonArray(components)
            )
        )
        return json.encodeToString(JsonObject.serializer(), root)
    }

    fun fromJson(text: String): InterfaceDocument {
        val root = json.parseToJsonElement(text).jsonObject
        val format = root["format"]?.jsonPrimitive?.int ?: JSON_FORMAT
        check(format <= JSON_FORMAT) { "Sidecar format $format is newer than $JSON_FORMAT" }
        val id = root["interfaceId"]?.jsonPrimitive?.int
            ?: error("Sidecar has no interfaceId")
        val array = root["components"]?.jsonArray ?: JsonArray(emptyList())
        val components = array.mapIndexed { index, element ->
            val definition = InterfaceComponentDefinition(id = ComponentDefinitions.pack(id, index))
            for ((name, value) in element.jsonObject) {
                if (name == "index" || name == "id") {
                    continue
                }
                val field = ComponentFields.field(name) ?: continue
                if (name in ComponentFields.transientFields) {
                    continue
                }
                ComponentFields.set(definition, field, decodeField(field, value))
            }
            definition
        }
        return InterfaceDocument(id, components)
    }

    private fun encodeField(field: Field, value: Any?): JsonElement = when {
        value == null -> JsonNull
        field.type == Int::class.javaPrimitiveType -> JsonPrimitive(value as Int)
        field.type == Byte::class.javaPrimitiveType -> JsonPrimitive((value as Byte).toInt())
        field.type == Boolean::class.javaPrimitiveType -> JsonPrimitive(value as Boolean)
        field.type == String::class.java -> JsonPrimitive(value as String)
        field.type == IntArray::class.java -> JsonArray((value as IntArray).map { JsonPrimitive(it) })
        field.type == ByteArray::class.java -> JsonArray((value as ByteArray).map { JsonPrimitive(it.toInt()) })
        field.type == Array<String>::class.java ->
            JsonArray((value as Array<*>).map { JsonPrimitive(it as String) })
        field.type == InterfaceComponentSetting::class.java -> {
            val setting = value as InterfaceComponentSetting
            JsonObject(
                linkedMapOf(
                    "setting" to JsonPrimitive(setting.setting),
                    "data" to JsonPrimitive(setting.anInt7413)
                )
            )
        }
        field.type == InterfaceComponentHook::class.java -> {
            val hook = value as InterfaceComponentHook
            JsonObject(
                linkedMapOf(
                    "script" to JsonPrimitive(hook.script),
                    "args" to JsonArray(
                        hook.args.map {
                            when (it) {
                                is Int -> JsonPrimitive(it)
                                is String -> JsonPrimitive(it)
                                else -> throw IllegalArgumentException(
                                    "Hook argument of unsupported type ${it.javaClass.name}"
                                )
                            }
                        }
                    )
                )
            )
        }
        else -> throw IllegalArgumentException(
            "No JSON mapping for ${field.name} of type ${field.type.name}"
        )
    }

    private fun decodeField(field: Field, element: JsonElement): Any? = when {
        element is JsonNull -> null
        field.type == Int::class.javaPrimitiveType -> element.jsonPrimitive.int
        field.type == Byte::class.javaPrimitiveType -> element.jsonPrimitive.int.toByte()
        field.type == Boolean::class.javaPrimitiveType -> element.jsonPrimitive.boolean
        field.type == String::class.java -> element.jsonPrimitive.content
        field.type == IntArray::class.java -> {
            val array = element.jsonArray
            IntArray(array.size) { array[it].jsonPrimitive.int }
        }
        field.type == ByteArray::class.java -> {
            val array = element.jsonArray
            ByteArray(array.size) { array[it].jsonPrimitive.int.toByte() }
        }
        field.type == Array<String>::class.java ->
            element.jsonArray.map { it.jsonPrimitive.content }.toTypedArray()
        field.type == InterfaceComponentSetting::class.java -> {
            val object_ = element.jsonObject
            InterfaceComponentSetting(
                object_["setting"]?.jsonPrimitive?.int ?: 0,
                object_["data"]?.jsonPrimitive?.int ?: -1
            )
        }
        field.type == InterfaceComponentHook::class.java -> {
            val object_ = element.jsonObject
            val args = object_["args"]?.jsonArray ?: JsonArray(emptyList())
            InterfaceComponentHook(
                object_["script"]?.jsonPrimitive?.int ?: -1,
                Array(args.size) { position ->
                    val primitive = args[position].jsonPrimitive
                    if (primitive.isString) primitive.content else primitive.int
                }
            )
        }
        else -> throw IllegalArgumentException(
            "No JSON mapping for ${field.name} of type ${field.type.name}"
        )
    }

    private fun estimateSize(definition: InterfaceComponentDefinition): Int {
        var size = BASE_COMPONENT_BYTES
        size += (definition.unknown?.length ?: 0) + definition.text.length +
            definition.name.length + definition.useOption.length +
            (definition.optionOverride?.length ?: 0) + STRING_SLACK * 5
        definition.options?.forEach { size += it.length + STRING_SLACK }
        definition.mouseIcon?.let { size += it.size * 2 + STRING_SLACK }
        for (hook in definition.hooks().values) {
            size += HOOK_BYTES
            for (arg in hook.args) {
                size += if (arg is String) arg.length + STRING_SLACK else 5
            }
        }
        size += ((definition.varps?.size ?: 0) + (definition.mouseLeaveArrayParams?.size ?: 0) +
            (definition.statTransmitFilter?.size ?: 0) + (definition.keyPressArray?.size ?: 0) +
            (definition.mouseWheelArray?.size ?: 0)) * 4
        return size
    }

    companion object {
        const val JSON_FORMAT = 1

        const val DEFAULT_DIRECTORY = "data/interfaces"

        private const val BACKUP_MAGIC = 0x4946424b
        private const val BACKUP_VERSION = 1

        private const val BASE_COMPONENT_BYTES = 256
        private const val STRING_SLACK = 8
        private const val HOOK_BYTES = 16
        private const val MAX_COMPONENT_BYTES = 1 shl 20
    }
}
