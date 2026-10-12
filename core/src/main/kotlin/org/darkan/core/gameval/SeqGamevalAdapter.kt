package org.darkan.core.gameval

import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import org.darkan.core.Logger.logWarn
import java.util.concurrent.ConcurrentHashMap

class SeqGamevalAdapter(private val catalog: GamevalCatalog) : TypeAdapter<Int>() {

    constructor() : this(Gameval)

    override fun write(out: JsonWriter, value: Int?) {
        if (value == null) {
            out.nullValue()
            return
        }
        val name = catalog.name(Gameval.SEQ, value)
        if (name != null) out.value(name) else out.value(value.toLong())
    }

    override fun read(`in`: JsonReader): Int {
        if (`in`.peek() != JsonToken.STRING) {
            return `in`.nextInt()
        }
        val text = `in`.nextString()
        return text.toIntOrNull() ?: catalog.id(Gameval.SEQ, text) ?: unknown(text)
    }

    private fun unknown(name: String): Int {
        if (catalog.has(Gameval.SEQ) && WARNED.add(name)) {
            logWarn("no seq gameval named '$name' - reading it as -1")
        }
        return -1
    }

    private companion object {
        val WARNED = ConcurrentHashMap.newKeySet<String>()
    }
}