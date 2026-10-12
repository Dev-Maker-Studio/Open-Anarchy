package org.darkan.core.gameval

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.darkan.core.Logger.logWarn
import java.io.File
import java.util.concurrent.ConcurrentHashMap

open class GamevalCatalog(
    private val folder: File?,
    private val retryMillis: Long = DEFAULT_RETRY_MILLIS,
) {
    @Serializable
    private data class CatalogFile(val entries: Map<String, String> = emptyMap())

    enum class Source { FOLDER, MISSING }

    private class Loaded(
        val raw: Map<String, String>,
        val source: Source,
        val loadedAt: Long,
    ) {
        val ok: Boolean get() = source != Source.MISSING

        val all: Map<Int, String> by lazy {
            if (raw.isEmpty()) emptyMap() else HashMap<Int, String>(raw.size).also { out ->
                for ((key, name) in raw) out[key.toIntOrNull() ?: continue] = name
            }
        }

        fun named(type: String): Map<Int, String> = namedCache ?: buildNamed(type).also { namedCache = it }

        fun reverse(type: String): Map<String, Int> = reverseCache ?: buildReverse(type).also { reverseCache = it }

        @Volatile private var namedCache: Map<Int, String>? = null
        @Volatile private var reverseCache: Map<String, Int>? = null

        private fun buildNamed(type: String): Map<Int, String> {
            if (all.isEmpty()) return emptyMap()
            val out = HashMap<Int, String>(all.size)
            for ((id, name) in all) if (!isPlaceholder(type, id.toString(), name)) out[id] = name
            return out
        }

        private fun buildReverse(type: String): Map<String, Int> {
            val named = named(type)
            if (named.isEmpty()) return emptyMap()
            val out = HashMap<String, Int>(named.size)
            for ((id, name) in named) out[name] = id
            return out
        }
    }

    private val cache = ConcurrentHashMap<String, Loaded>()
    private val warned = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var componentReverse: Pair<Loaded, Map<String, Int>>? = null


    private fun loaded(type: String): Loaded {
        val now = System.currentTimeMillis()
        val current = cache[type]
        if (current != null && (current.ok || now - current.loadedAt < retryMillis)) return current
        return cache.compute(type) { t, existing ->
            if (existing != null && existing !== current && (existing.ok || now - existing.loadedAt < retryMillis)) existing
            else load(t, now)
        }!!
    }

    private fun load(type: String, now: Long): Loaded {
        val file = folder?.let { File(it, "$type.json") }
        if (file != null && file.isFile) {
            val parsed = parse(type, file.absolutePath) { file.readText() }
            if (parsed != null) return Loaded(parsed, Source.FOLDER, now).also { warned.remove(type) }
        }
        if (file == null || !file.isFile) {
            warnOnce(type, "no $type.json catalog in ${folder?.absolutePath ?: "(no folder)"} (GAMEVAL_PATH, normally <cache source tree>/gamevals); $type names will not resolve")
        }
        return Loaded(emptyMap(), Source.MISSING, now)
    }

    private inline fun parse(type: String, where: String, text: () -> String): Map<String, String>? = try {
        json.decodeFromString(CatalogFile.serializer(), text()).entries
    } catch (e: Exception) {
        warnOnce(type, "$where is present but unreadable (${e.message?.lineSequence()?.firstOrNull()}); $type names will not resolve until it is rewritten", e)
        null
    }

    private fun warnOnce(type: String, message: String, e: Throwable? = null) {
        if (warned.add(type)) logWarn("Gameval: $message", e)
    }


    fun name(type: String, id: Int): String? = loaded(type).named(type)[id]

    fun rawName(type: String, id: Int): String? = loaded(type).all[id]

    fun isPlaceholder(type: String, id: Int): Boolean {
        val raw = rawName(type, id) ?: return false
        return isPlaceholder(type, id.toString(), raw)
    }

    fun label(type: String, id: Int): String = name(type, id)?.let { "$it ($id)" } ?: id.toString()


    fun id(type: String, name: String): Int? = loaded(type).reverse(type)[name]

    fun requireId(type: String, name: String): Int = id(type, name) ?: error("Gameval: no $type named '$name'")


    fun has(type: String): Boolean = loaded(type).raw.isNotEmpty()

    fun source(type: String): Source = loaded(type).source

    fun entries(type: String): Map<Int, String> = loaded(type).named(type)

    fun allEntries(type: String): Map<Int, String> = loaded(type).all

    fun rawEntries(type: String): Map<String, String> = loaded(type).raw

    fun reload() {
        cache.clear()
        componentReverse = null
        warned.clear()
    }


    fun component(interfaceId: Int, componentId: Int): String? {
        val key = componentKey(interfaceId, componentId)
        val raw = loaded(COMPONENT).raw[key] ?: return null
        return if (isPlaceholder(COMPONENT, key, raw)) null else raw
    }

    fun component(packed: Int): String? = component(ifaceOf(packed), compOf(packed))

    fun componentLabel(interfaceId: Int, componentId: Int): String {
        val key = componentKey(interfaceId, componentId)
        return component(interfaceId, componentId)?.let { "$it ($key)" } ?: key
    }

    fun componentId(name: String): Int? = componentReverseMap()[name]

    fun requireComponentId(name: String): Int = componentId(name) ?: error("Gameval: no component named '$name'")

    private fun componentReverseMap(): Map<String, Int> {
        val current = loaded(COMPONENT)
        componentReverse?.let { (from, map) -> if (from === current) return map }
        val map = if (current.raw.isEmpty()) emptyMap() else HashMap<String, Int>(current.raw.size).also { out ->
            for ((key, name) in current.raw) {
                val colon = key.indexOf(':')
                if (colon < 0) continue
                val iface = key.substring(0, colon).toIntOrNull() ?: continue
                val comp = key.substring(colon + 1).toIntOrNull() ?: continue
                if (isPlaceholder(COMPONENT, key, name)) continue
                out[name] = pack(iface, comp)
            }
        }
        componentReverse = current to map
        return map
    }

    companion object {
        const val COMPONENT = "component"
        const val CLIENTSCRIPT = "clientscript"

        const val DEFAULT_RETRY_MILLIS = 30_000L

        private val json = Json { ignoreUnknownKeys = true }

        fun pack(interfaceId: Int, componentId: Int): Int = (interfaceId shl 16) or (componentId and 0xFFFF)

        fun ifaceOf(packed: Int): Int = packed ushr 16

        fun compOf(packed: Int): Int = packed and 0xFFFF

        fun componentKey(interfaceId: Int, componentId: Int): String = "$interfaceId:$componentId"

        fun placeholderPrefix(type: String): String = if (type == CLIENTSCRIPT) "cs2" else type

        fun placeholder(type: String, key: String): String = "${placeholderPrefix(type)}_${key.replace(':', '_')}"

        internal fun isPlaceholder(type: String, key: String, name: String): Boolean = name == placeholder(type, key)
    }
}
