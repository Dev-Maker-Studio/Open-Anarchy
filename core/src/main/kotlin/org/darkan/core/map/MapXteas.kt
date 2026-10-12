package org.darkan.core.map

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.darkan.core.Logger.logError
import org.darkan.core.Logger.logInfo
import java.io.File

object MapXteas {
    private var keys: Map<Int, IntArray> = emptyMap()
    private var loaded = false

    fun load(path: String = "./data/map/xteaKeys.json"): Boolean {
        val file = File(path)
        if (!file.exists()) {
            logError("XTEA keys file not found: $path")
            return false
        }

        return try {
            val json = Json { ignoreUnknownKeys = true }
            val jsonObject = json.parseToJsonElement(file.readText()) as JsonObject

            keys = jsonObject.entries.associate { (key, value) ->
                val regionId = key.toInt()
                val keysArray = value.jsonArray.map { it.jsonPrimitive.content.toInt() }.toIntArray()
                regionId to keysArray
            }

            loaded = true
            logInfo("Loaded ${keys.size} XTEA keys")
            true
        } catch (e: Exception) {
            logError("Failed to load XTEA keys: ${e.message}", e)
            false
        }
    }

    fun getKeys(regionId: Int): IntArray? {
        val keyArray = keys[regionId] ?: return null

        if (keyArray.all { it == 0 }) return null

        return keyArray
    }

    fun getKeys(regionX: Int, regionY: Int): IntArray? {
        return getKeys((regionX shl 8) or regionY)
    }

    fun isLoaded(): Boolean = loaded

    fun size(): Int = keys.size

    fun hasKeys(regionId: Int): Boolean {
        val keyArray = keys[regionId] ?: return false
        return !keyArray.all { it == 0 }
    }

    fun hasKeys(regionX: Int, regionY: Int): Boolean {
        return hasKeys((regionX shl 8) or regionY)
    }

    fun getEncryptedRegionIds(): Set<Int> {
        return keys.filterValues { keyArray -> !keyArray.all { it == 0 } }.keys
    }

    fun put(regionId: Int, regionKeys: IntArray) {
        keys = keys + (regionId to regionKeys.copyOf())
    }

    fun clear() {
        keys = emptyMap()
        loaded = false
    }
}
