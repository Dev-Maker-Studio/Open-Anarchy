package world.gregs.voidps.cache.source.codec

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import world.gregs.voidps.cache.definition.DefinitionRecord
import world.gregs.voidps.cache.definition.KeyedValue
import world.gregs.voidps.cache.definition.data.InterfaceComponentHook
import world.gregs.voidps.cache.definition.data.InterfaceComponentSetting
import world.gregs.voidps.cache.definition.data.ShaderProgramDefine
import world.gregs.voidps.cache.definition.data.ShaderProgramMapping
import world.gregs.voidps.cache.definition.data.ShaderProgramVariable
import world.gregs.voidps.cache.definition.data.SynthEnvelope
import world.gregs.voidps.cache.definition.data.SynthFilter
import world.gregs.voidps.cache.definition.data.SynthFilterPair
import world.gregs.voidps.cache.definition.data.SynthInstrument
import world.gregs.voidps.cache.definition.data.SynthShape
import world.gregs.voidps.cache.definition.data.TextureOp
import world.gregs.voidps.cache.definition.data.TextureOpcode
import world.gregs.voidps.cache.definition.data.TextureShape
import world.gregs.voidps.cache.source.SourceJson
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.WildcardType
import java.util.concurrent.ConcurrentHashMap
import java.lang.Long as JavaLong
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Type as ReflectType

internal class DefinitionJson private constructor(private val properties: List<Property>) {

    fun write(definition: Any): ByteArray {
        val entries = ArrayList<Pair<String, Node>>(properties.size)
        for (property in properties) {
            val value = property.field.get(definition)
            if (value == null) {
                continue
            }
            entries.add(property.name to property.codec.node(value))
        }
        val out = StringBuilder(properties.size * 24)
        ObjectNode(entries).expand(out, 0)
        out.append('\n')
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    fun read(bytes: ByteArray, definition: Any) {
        val root = json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject
        for (property in properties) {
            val element = root[property.name] ?: continue
            if (element is JsonNull) {
                continue
            }
            property.field.set(definition, property.codec.value(element))
        }
    }

    private class Property(val name: String, val field: Field, val codec: ValueCodec)

    companion object {

        private const val WIDTH = 100

        private const val INDENT = "  "

        private val json = Json { ignoreUnknownKeys = true }

        private val cache = ConcurrentHashMap<Class<*>, DefinitionJson>()

        private val DERIVED = setOf(
            "world.gregs.voidps.cache.definition.data.ClanVarDefinition.type",
            "world.gregs.voidps.cache.definition.data.FontDefinition.kerningAdjustments",
            "world.gregs.voidps.cache.definition.data.WorldMapDefinition.minX",
            "world.gregs.voidps.cache.definition.data.WorldMapDefinition.minY",
            "world.gregs.voidps.cache.definition.data.WorldMapDefinition.maxX",
            "world.gregs.voidps.cache.definition.data.WorldMapDefinition.maxY",
            "world.gregs.voidps.cache.definition.data.WorldMapDefinition.anInt9547"
        )

        private val NESTED = setOf(
            InterfaceComponentSetting::class.java,
            InterfaceComponentHook::class.java,
            KeyedValue::class.java,
            TextureOp::class.java,
            TextureOpcode::class.java,
            TextureShape::class.java,
            SynthInstrument::class.java,
            SynthEnvelope::class.java,
            SynthShape::class.java,
            SynthFilter::class.java,
            SynthFilterPair::class.java,
            ShaderProgramVariable::class.java,
            ShaderProgramMapping::class.java,
            ShaderProgramDefine::class.java
        )

        fun of(type: Class<*>): DefinitionJson {
            cache[type]?.let { return it }
            val built = build(type)
            return cache.putIfAbsent(type, built) ?: built
        }

        private fun build(type: Class<*>): DefinitionJson {
            val properties = ArrayList<Property>()
            for (field in type.declaredFields) {
                if (Modifier.isStatic(field.modifiers) || field.isSynthetic) {
                    continue
                }
                if (DERIVED.contains("${type.name}.${field.name}")) {
                    continue
                }
                field.isAccessible = true
                properties.add(Property(field.name, field, codec(type, field)))
            }
            check(properties.isNotEmpty()) { "${type.name} has no fields to write." }
            return DefinitionJson(properties)
        }

        private fun codec(type: Class<*>, field: Field): ValueCodec {
            simple(field.type)?.let { return it }
            if (Map::class.java.isAssignableFrom(field.type) || List::class.java.isAssignableFrom(field.type)) {
                return element(type, field, field.genericType)
            }
            throw IllegalArgumentException(
                "${type.name}.${field.name} is a ${field.type.name}, which ${DefinitionJson::class.simpleName} " +
                    "has no spelling for; give it one rather than leaving the field out of the tree."
            )
        }

        private fun element(owner: Class<*>, field: Field, argument: ReflectType): ValueCodec {
            val type = erase(argument) ?: throw IllegalArgumentException(
                "${owner.name}.${field.name} is parameterised with $argument, which is not a class."
            )
            simple(type)?.let { return it }
            if (List::class.java.isAssignableFrom(type)) {
                return ListCodec(element(owner, field, arguments(owner, field, argument, 1)[0]))
            }
            if (Map::class.java.isAssignableFrom(type)) {
                val arguments = arguments(owner, field, argument, 2)
                return MapCodec(element(owner, field, arguments[0]), element(owner, field, arguments[1]))
            }
            throw IllegalArgumentException(
                "${owner.name}.${field.name} holds ${type.name}, which ${DefinitionJson::class.simpleName} " +
                    "has no spelling for."
            )
        }

        private fun simple(type: Class<*>): ValueCodec? = when (type) {
            Int::class.javaPrimitiveType, Integer::class.java -> IntCodec
            Long::class.javaPrimitiveType, JavaLong::class.java -> LongCodec
            Short::class.javaPrimitiveType, Short::class.javaObjectType -> ShortCodec
            Byte::class.javaPrimitiveType, Byte::class.javaObjectType -> ByteCodec
            Char::class.javaPrimitiveType, Char::class.javaObjectType -> CharCodec
            Boolean::class.javaPrimitiveType, Boolean::class.javaObjectType -> BooleanCodec
            String::class.java -> StringCodec
            Any::class.java -> AnyCodec
            IntArray::class.java -> ArrayCodec.INTS
            ShortArray::class.java -> ArrayCodec.SHORTS
            ByteArray::class.java -> ArrayCodec.BYTES
            BooleanArray::class.java -> ArrayCodec.BOOLEANS
            CharArray::class.java -> ArrayCodec.CHARS
            Array<String>::class.java -> ObjectArrayCodec(String::class.java, StringCodec)
            Array<IntArray>::class.java -> ObjectArrayCodec(IntArray::class.java, ArrayCodec.INTS)
            Array<ByteArray>::class.java -> ObjectArrayCodec(ByteArray::class.java, HexCodec)
            Array<Any>::class.java -> ObjectArrayCodec(Any::class.java, AnyCodec)
            else -> nested(type) ?: type.componentType?.let { component ->
                nested(component)?.let { ObjectArrayCodec(component, it) }
            }
        }

        private fun nested(type: Class<*>): NestedCodec? = when {
            NESTED.contains(type) -> NestedCodec(type, nulls = true)
            DefinitionRecord::class.java.isAssignableFrom(type) -> NestedCodec(type)
            else -> null
        }

        private fun erase(type: ReflectType): Class<*>? = when (type) {
            is Class<*> -> type
            is WildcardType -> type.upperBounds.firstOrNull()?.let { erase(it) }
            is ParameterizedType -> erase(type.rawType)
            else -> null
        }

        private fun arguments(
            owner: Class<*>,
            field: Field,
            type: ReflectType,
            count: Int
        ): Array<out ReflectType> {
            val generic = parameterized(type)
                ?: throw IllegalArgumentException("${owner.name}.${field.name} holds a raw $type.")
            val arguments = generic.actualTypeArguments
            require(arguments.size == count) {
                "${owner.name}.${field.name} holds a $type with ${arguments.size} type arguments, not $count."
            }
            return arguments
        }

        private fun parameterized(type: ReflectType): ParameterizedType? = when (type) {
            is ParameterizedType -> type
            is WildcardType -> type.upperBounds.firstOrNull()?.let { parameterized(it) }
            else -> null
        }


        private interface ValueCodec {
            fun node(value: Any): Node
            fun value(element: JsonElement): Any
        }

        private object IntCodec : ValueCodec {
            override fun node(value: Any) = NumberNode(value.toString())
            override fun value(element: JsonElement) = element.jsonPrimitive.content.toInt()
        }

        private object LongCodec : ValueCodec {
            override fun node(value: Any) = NumberNode(value.toString())
            override fun value(element: JsonElement) = element.jsonPrimitive.content.toLong()
        }

        private object ShortCodec : ValueCodec {
            override fun node(value: Any) = NumberNode(value.toString())
            override fun value(element: JsonElement) = element.jsonPrimitive.content.toShort()
        }

        private object ByteCodec : ValueCodec {
            override fun node(value: Any) = NumberNode(value.toString())
            override fun value(element: JsonElement) = element.jsonPrimitive.content.toByte()
        }

        private object BooleanCodec : ValueCodec {
            override fun node(value: Any) = BooleanNode(value as Boolean)
            override fun value(element: JsonElement) = element.jsonPrimitive.boolean
        }

        private object StringCodec : ValueCodec {
            override fun node(value: Any) = StringNode(value as String)
            override fun value(element: JsonElement) = element.jsonPrimitive.content
        }

        private object CharCodec : ValueCodec {
            override fun node(value: Any): Node {
                val character = value as Char
                return StringNode(if (character.code == 0) "" else character.toString())
            }

            override fun value(element: JsonElement): Any {
                val content = element.jsonPrimitive.content
                return if (content.isEmpty()) 0.toChar() else content[0]
            }
        }

        private object AnyCodec : ValueCodec {
            override fun node(value: Any): Node = when (value) {
                is String -> StringNode(value)
                is Int -> NumberNode(value.toString())
                is Long -> NumberNode(value.toString())
                else -> throw IllegalArgumentException("A ${value::class.simpleName} is not an int or a string.")
            }

            override fun value(element: JsonElement): Any {
                val primitive = element.jsonPrimitive
                return if (primitive.isString) primitive.content else primitive.content.toInt()
            }
        }

        private object HexCodec : ValueCodec {
            private val digits = "0123456789abcdef".toCharArray()

            override fun node(value: Any): Node {
                val bytes = value as ByteArray
                val out = CharArray(bytes.size * 2)
                for (index in bytes.indices) {
                    val byte = bytes[index].toInt() and 0xff
                    out[index * 2] = digits[byte shr 4]
                    out[index * 2 + 1] = digits[byte and 0xf]
                }
                return StringNode(String(out))
            }

            override fun value(element: JsonElement): Any {
                val text = element.jsonPrimitive.content
                require(text.length % 2 == 0) { "'$text' is not a whole number of bytes." }
                return ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            }
        }

        private class ArrayCodec(
            private val length: (Any) -> Int,
            private val element: (Any, Int) -> Node,
            private val build: (List<JsonElement>) -> Any
        ) : ValueCodec {

            override fun node(value: Any) = ArrayNode(List(length(value)) { element(value, it) })

            override fun value(source: JsonElement) = build(source.jsonArray)

            companion object {
                val INTS = ArrayCodec(
                    { (it as IntArray).size },
                    { array, index -> NumberNode((array as IntArray)[index].toString()) },
                    { items -> IntArray(items.size) { items[it].jsonPrimitive.content.toInt() } }
                )
                val SHORTS = ArrayCodec(
                    { (it as ShortArray).size },
                    { array, index -> NumberNode((array as ShortArray)[index].toString()) },
                    { items -> ShortArray(items.size) { items[it].jsonPrimitive.content.toShort() } }
                )
                val BYTES = ArrayCodec(
                    { (it as ByteArray).size },
                    { array, index -> NumberNode((array as ByteArray)[index].toString()) },
                    { items -> ByteArray(items.size) { items[it].jsonPrimitive.content.toByte() } }
                )
                val BOOLEANS = ArrayCodec(
                    { (it as BooleanArray).size },
                    { array, index -> BooleanNode((array as BooleanArray)[index]) },
                    { items -> BooleanArray(items.size) { items[it].jsonPrimitive.boolean } }
                )

                val CHARS = ArrayCodec(
                    { (it as CharArray).size },
                    { array, index -> CharCodec.node((array as CharArray)[index]) },
                    { items -> CharArray(items.size) { CharCodec.value(items[it]) as Char } }
                )
            }
        }

        private class ObjectArrayCodec(
            private val component: Class<*>,
            private val element: ValueCodec
        ) : ValueCodec {

            override fun node(value: Any): Node {
                val array = value as Array<*>
                return ArrayNode(array.map { if (it == null) NullNode else element.node(it) })
            }

            override fun value(source: JsonElement): Any {
                val items = source.jsonArray
                val array = ReflectArray.newInstance(component, items.size)
                for (index in items.indices) {
                    val item = items[index]
                    ReflectArray.set(array, index, if (item is JsonNull) null else element.value(item))
                }
                return array
            }
        }

        private class MapCodec(private val key: ValueCodec, private val value: ValueCodec) : ValueCodec {

            override fun node(value: Any): Node {
                val map = value as Map<*, *>
                val entries = ArrayList<Pair<String, Node>>(map.size)
                for ((entryKey, entryValue) in map) {
                    val name = key.node(entryKey!!)
                    entries.add((if (name is StringNode) name.text else (name as NumberNode).text) to this.value.node(entryValue!!))
                }
                return ObjectNode(entries)
            }

            override fun value(element: JsonElement): Any {
                val map = LinkedHashMap<Any, Any>()
                for ((entryKey, entryValue) in element.jsonObject) {
                    map[key.value(JsonPrimitive(entryKey))] = value.value(entryValue)
                }
                return map
            }
        }

        private class ListCodec(private val element: ValueCodec) : ValueCodec {
            override fun node(value: Any) = ArrayNode((value as List<*>).map { element.node(it!!) })
            override fun value(source: JsonElement): Any = source.jsonArray.map { element.value(it) }
        }

        private class NestedCodec(
            private val type: Class<*>,
            private val nulls: Boolean = false
        ) : ValueCodec {

            private val json = of(type)

            private val constructor = type.declaredConstructors
                .firstOrNull { it.parameterCount == json.properties.size }
                ?.also { it.isAccessible = true }
                ?: throw IllegalArgumentException("${type.name} has no constructor taking all of its fields.")

            override fun node(value: Any): Node {
                val entries = ArrayList<Pair<String, Node>>(json.properties.size)
                for (property in json.properties) {
                    val field = property.field.get(value)
                    if (field == null) {
                        if (nulls) {
                            entries.add(property.name to NullNode)
                        }
                        continue
                    }
                    entries.add(property.name to property.codec.node(field))
                }
                return ObjectNode(entries)
            }

            override fun value(element: JsonElement): Any {
                val root = element.jsonObject
                val arguments = arrayOfNulls<Any>(json.properties.size)
                for (index in json.properties.indices) {
                    val property = json.properties[index]
                    val field = root[property.name]
                    arguments[index] = if (field == null || field is JsonNull) null else property.codec.value(field)
                }
                return constructor.newInstance(*arguments)
            }
        }


        private sealed class Node {
            abstract fun compact(out: StringBuilder)

            open val breakable: Boolean
                get() = false

            open fun expand(out: StringBuilder, indent: Int) = compact(out)

            fun write(out: StringBuilder, indent: Int, column: Int) {
                val line = StringBuilder()
                compact(line)
                if (!breakable || column + line.length <= WIDTH) {
                    out.append(line)
                } else {
                    expand(out, indent)
                }
            }
        }

        private class NumberNode(val text: String) : Node() {
            override fun compact(out: StringBuilder) {
                out.append(text)
            }
        }

        private class BooleanNode(private val value: Boolean) : Node() {
            override fun compact(out: StringBuilder) {
                out.append(value)
            }
        }

        private class StringNode(val text: String) : Node() {
            override fun compact(out: StringBuilder) {
                out.append(SourceJson.quote(text))
            }
        }

        private object NullNode : Node() {
            override fun compact(out: StringBuilder) {
                out.append("null")
            }
        }

        private class ArrayNode(private val items: List<Node>) : Node() {

            override fun compact(out: StringBuilder) {
                out.append('[')
                for (index in items.indices) {
                    if (index != 0) {
                        out.append(", ")
                    }
                    items[index].compact(out)
                }
                out.append(']')
            }

            override val breakable: Boolean
                get() = items.isNotEmpty()

            override fun expand(out: StringBuilder, indent: Int) {
                out.append("[\n")
                val inner = indent + INDENT.length
                for (index in items.indices) {
                    pad(out, inner)
                    items[index].write(out, inner, inner)
                    if (index != items.size - 1) {
                        out.append(',')
                    }
                    out.append('\n')
                }
                pad(out, indent)
                out.append(']')
            }
        }

        private class ObjectNode(private val entries: List<Pair<String, Node>>) : Node() {

            override fun compact(out: StringBuilder) {
                out.append('{')
                for (index in entries.indices) {
                    if (index != 0) {
                        out.append(", ")
                    }
                    out.append(SourceJson.quote(entries[index].first)).append(": ")
                    entries[index].second.compact(out)
                }
                out.append('}')
            }

            override val breakable: Boolean
                get() = entries.isNotEmpty()

            override fun expand(out: StringBuilder, indent: Int) {
                out.append("{\n")
                val inner = indent + INDENT.length
                for (index in entries.indices) {
                    pad(out, inner)
                    val key = SourceJson.quote(entries[index].first) + ": "
                    out.append(key)
                    entries[index].second.write(out, inner, inner + key.length)
                    if (index != entries.size - 1) {
                        out.append(',')
                    }
                    out.append('\n')
                }
                pad(out, indent)
                out.append('}')
            }
        }

        private fun pad(out: StringBuilder, columns: Int) {
            for (column in 0 until columns) {
                out.append(' ')
            }
        }
    }
}
