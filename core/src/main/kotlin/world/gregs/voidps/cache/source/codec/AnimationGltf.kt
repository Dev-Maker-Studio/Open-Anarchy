package world.gregs.voidps.cache.source.codec

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import world.gregs.voidps.cache.definition.data.AnimationBaseDefinition
import world.gregs.voidps.cache.definition.data.AnimationFrameDefinition
import world.gregs.voidps.cache.definition.decoder.AnimationFrameDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object AnimationGltf {

    const val ORIGIN = 0

    const val TRANSLATE = 1

    const val ROTATE = 2

    const val SCALE = 3

    const val ALPHA = 5

    private const val ROTATE_ALIAS = 6

    private const val BILLBOARD_SCALE = 10

    private val COMPONENTS = intArrayOf(0x1, 0x2, 0x4)

    private const val ABSENT = 0

    private const val ABSENT_SCALE = 128

    const val UNDECLARED = -1

    fun write(set: AnimationFrameSet, base: AnimationBaseDefinition?): ByteArray {
        val groups = groups(set, base)
        val types = IntArray(groups) { if (base != null && it < base.types.size) base.types[it] else UNDECLARED }
        val channels = channels(set, types)
        val binary = binary(channels)
        return Gltf.assemble(asset(set, base, types, channels, binary.size), binary)
    }

    fun read(bytes: ByteArray, archive: Int): AnimationFrameSet {
        val root = Gltf.root(Gltf.chunks(bytes).first)
        val scene = root["scenes"]?.jsonArray?.getOrNull(root["scene"]?.jsonPrimitive?.int ?: 0)?.jsonObject
            ?: throw IllegalArgumentException("Frame set $archive has no glTF scene.")
        val rs = scene["extras"]?.jsonObject?.get("rs")?.jsonObject
            ?: throw IllegalArgumentException(
                "Frame set $archive carries no rs extras on its scene; it is not a cache frame set."
            )
        val baseId = rs["base"]?.jsonPrimitive?.int
            ?: throw IllegalArgumentException("Frame set $archive names no base.")
        val layout = rs["layout"]?.jsonObject ?: JsonObject(emptyMap())
        val frames = ArrayList<AnimationFrameDefinition>()
        for (element in rs["frames"]?.jsonArray ?: emptyList()) {
            frames.add(frame(element.jsonObject, archive, baseId))
        }
        return AnimationFrameSet(
            archive = archive,
            baseId = baseId,
            frames = frames,
            chunks = layout["chunks"]?.jsonPrimitive?.int ?: if (frames.size == 1) 1 else CHUNKS,
            header = layout["header"]?.jsonPrimitive?.int ?: HEADER
        )
    }

    fun json(bytes: ByteArray): String = Gltf.text(bytes)

    private fun frame(frame: JsonObject, archive: Int, baseId: Int): AnimationFrameDefinition {
        val file = frame["file"]?.jsonPrimitive?.int
            ?: throw IllegalArgumentException("A frame of set $archive has no file id.")
        val moved = Gltf.ints(frame["transforms"]) ?: IntArray(0)
        val bytes = Gltf.ints(frame["flags"]) ?: IntArray(0)
        require(moved.size == bytes.size) {
            "Frame set $archive frame $file lists ${moved.size} transforms and ${bytes.size} flag bytes."
        }
        val count = frame["count"]?.jsonPrimitive?.int ?: (moved.maxOrNull()?.plus(1) ?: 0)
        require(count <= AnimationFrameDefinition.WRAPPED_COUNT) {
            "Frame set $archive frame $file declares $count transforms; the count byte holds at most " +
                "${AnimationFrameDefinition.WRAPPED_COUNT}."
        }
        val flags = IntArray(count)
        for (index in moved.indices) {
            val group = moved[index]
            require(group in 0 until count) {
                "Frame set $archive frame $file moves group $group, which is outside its $count transforms."
            }
            require(flags[group] == 0) { "Frame set $archive frame $file lists group $group twice." }
            flags[group] = bytes[index]
        }
        val values = Gltf.ints(frame["values"]) ?: IntArray(0)
        val expected = components(flags)
        require(values.size == expected) {
            "Frame set $archive frame $file has ${values.size} values for the $expected its flags ask for."
        }
        return AnimationFrameDefinition(
            id = (archive shl AnimationFrameDecoder.BITS) or file,
            baseId = frame["base"]?.jsonPrimitive?.int ?: baseId,
            header = frame["header"]?.jsonPrimitive?.int ?: 1,
            flags = flags,
            values = values,
            wideValues = Gltf.ints(frame["wide"])
        )
    }


    private fun groups(set: AnimationFrameSet, base: AnimationBaseDefinition?): Int {
        var groups = base?.types?.size ?: 0
        for (frame in set.frames) {
            if (frame.flags.size > groups) {
                groups = frame.flags.size
            }
        }
        return groups
    }

    private class Channel(val group: Int, val path: String, val arity: Int, val times: IntArray, val values: IntArray)

    private fun channels(set: AnimationFrameSet, types: IntArray): List<Channel> {
        val paths = arrayOfNulls<String>(types.size)
        val counts = IntArray(types.size)
        for (group in types.indices) {
            paths[group] = path(types[group])
        }
        for (frame in set.frames) {
            val limit = minOf(frame.flags.size, types.size)
            for (group in 0 until limit) {
                if (frame.flags[group] != 0 && paths[group] != null) {
                    counts[group]++
                }
            }
        }
        val times = arrayOfNulls<IntArray>(types.size)
        val values = arrayOfNulls<IntArray>(types.size)
        val arity = IntArray(types.size)
        val absent = IntArray(types.size)
        val keyframes = IntArray(types.size)
        for (group in types.indices) {
            if (paths[group] == null || counts[group] == 0) {
                continue
            }
            arity[group] = if (paths[group] == ROTATION) 4 else 3
            absent[group] = if (types[group] == SCALE || types[group] == BILLBOARD_SCALE) ABSENT_SCALE else ABSENT
            times[group] = IntArray(counts[group])
            values[group] = IntArray(counts[group] * arity[group])
        }
        for (frame in set.frames) {
            val time = frame.id and MASK
            var offset = 0
            for (group in frame.flags.indices) {
                val flag = frame.flags[group]
                if (flag == 0) {
                    continue
                }
                val moments = if (group < times.size) times[group] else null
                if (moments == null) {
                    for (bit in COMPONENTS) {
                        if (flag and bit != 0) {
                            offset++
                        }
                    }
                    continue
                }
                val output = values[group]!!
                val keyframe = keyframes[group]++
                moments[keyframe] = time
                val components = arity[group]
                for (part in COMPONENTS.indices) {
                    output[keyframe * components + part] = if (flag and COMPONENTS[part] != 0) {
                        frame.values[offset++]
                    } else {
                        absent[group]
                    }
                }
            }
        }
        val channels = ArrayList<Channel>()
        for (group in types.indices) {
            val moments = times[group] ?: continue
            channels.add(Channel(group, paths[group]!!, arity[group], moments, values[group]!!))
        }
        return channels
    }

    private fun components(flags: IntArray): Int {
        var components = 0
        for (flag in flags) {
            for (bit in COMPONENTS) {
                if (flag and bit != 0) {
                    components++
                }
            }
        }
        return components
    }

    private fun path(type: Int): String? = when (type) {
        TRANSLATE -> TRANSLATION
        ROTATE, ROTATE_ALIAS -> ROTATION
        SCALE -> SCALING
        else -> null
    }

    private fun binary(channels: List<Channel>): ByteArray {
        val inputs = inputs(channels)
        var length = 0
        for (times in inputs) {
            length += times.size * 4
        }
        for (channel in channels) {
            length += channel.values.size * 4
        }
        val binary = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
        for (times in inputs) {
            for (time in times) {
                binary.putFloat(time.toFloat())
            }
        }
        for (channel in channels) {
            for (value in channel.values) {
                binary.putFloat(value.toFloat())
            }
        }
        return binary.array()
    }

    private fun inputs(channels: List<Channel>): List<IntArray> {
        val inputs = ArrayList<IntArray>()
        val seen = HashMap<Times, Int>()
        for (channel in channels) {
            val key = Times(channel.times)
            if (seen.putIfAbsent(key, inputs.size) == null) {
                inputs.add(channel.times)
            }
        }
        return inputs
    }

    private fun sampled(channels: List<Channel>, inputs: List<IntArray>): IntArray {
        val seen = HashMap<Times, Int>(inputs.size)
        for (index in inputs.indices) {
            seen[Times(inputs[index])] = index
        }
        return IntArray(channels.size) { seen.getValue(Times(channels[it].times)) }
    }

    private class Times(val values: IntArray) {
        override fun equals(other: Any?): Boolean = other is Times && values.contentEquals(other.values)

        override fun hashCode(): Int = values.contentHashCode()
    }

    private fun asset(
        set: AnimationFrameSet,
        base: AnimationBaseDefinition?,
        types: IntArray,
        channels: List<Channel>,
        binaryLength: Int
    ): String {
        val out = StringBuilder(1024 + set.frames.size * 64)
        out.append("{\"asset\":{\"generator\":\"").append(Gltf.GENERATOR).append("\",\"version\":\"2.0\"}")
        out.append(",\"scene\":0,\"scenes\":[{")
        if (types.isNotEmpty()) {
            out.append("\"nodes\":[")
            for (group in types.indices) {
                if (group != 0) {
                    out.append(',')
                }
                out.append(group)
            }
            out.append("],")
        }
        out.append("\"extras\":{\"rs\":")
        extras(out, set, types.size)
        out.append("}}]")
        nodes(out, base, types)
        if (channels.isEmpty()) {
            out.append('}')
            return out.toString()
        }
        val inputs = inputs(channels)
        val samplers = sampled(channels, inputs)
        animation(out, set.archive, channels, samplers, inputs.size)
        accessors(out, channels, inputs)
        views(out, channels, inputs)
        out.append(",\"buffers\":[{\"byteLength\":").append(binaryLength).append("}]")
        out.append('}')
        return out.toString()
    }

    private fun nodes(out: StringBuilder, base: AnimationBaseDefinition?, types: IntArray) {
        if (types.isEmpty()) {
            return
        }
        out.append(",\"nodes\":[")
        for (group in types.indices) {
            if (group != 0) {
                out.append(',')
            }
            out.append("{\"name\":\"").append(group).append('"')
            out.append(",\"extras\":{\"rs\":{\"group\":").append(group)
            out.append(",\"type\":").append(types[group])
            val labels = base?.labels?.getOrNull(group)
            if (labels != null && labels.isNotEmpty()) {
                Gltf.scalars(out, "labels", labels.size) { labels[it] }
            }
            out.append("}}}")
        }
        out.append(']')
    }

    private fun animation(
        out: StringBuilder,
        archive: Int,
        channels: List<Channel>,
        samplers: IntArray,
        inputs: Int
    ) {
        out.append(",\"animations\":[{\"name\":\"frameset_").append(archive).append('"')
        out.append(",\"channels\":[")
        for (index in channels.indices) {
            if (index != 0) {
                out.append(',')
            }
            out.append("{\"sampler\":").append(index)
                .append(",\"target\":{\"node\":").append(channels[index].group)
                .append(",\"path\":\"").append(channels[index].path).append("\"}}")
        }
        out.append("],\"samplers\":[")
        for (index in channels.indices) {
            if (index != 0) {
                out.append(',')
            }
            out.append("{\"input\":").append(samplers[index])
                .append(",\"interpolation\":\"LINEAR\",\"output\":").append(inputs + index).append('}')
        }
        out.append("]}]")
    }

    private fun accessors(out: StringBuilder, channels: List<Channel>, inputs: List<IntArray>) {
        out.append(",\"accessors\":[")
        var offset = 0
        for (index in inputs.indices) {
            if (index != 0) {
                out.append(',')
            }
            val times = inputs[index]
            out.append("{\"bufferView\":").append(index)
                .append(",\"componentType\":").append(Gltf.FLOAT)
                .append(",\"count\":").append(times.size)
                .append(",\"max\":[").append(bound(times, least = false)).append(']')
                .append(",\"min\":[").append(bound(times, least = true)).append(']')
                .append(",\"type\":\"SCALAR\"}")
            offset++
        }
        for (index in channels.indices) {
            out.append(",{\"bufferView\":").append(offset + index)
                .append(",\"componentType\":").append(Gltf.FLOAT)
                .append(",\"count\":").append(channels[index].times.size)
                .append(",\"type\":\"").append(if (channels[index].arity == 4) "VEC4" else "VEC3")
                .append("\"}")
        }
        out.append(']')
    }

    private fun views(out: StringBuilder, channels: List<Channel>, inputs: List<IntArray>) {
        out.append(",\"bufferViews\":[")
        var start = 0
        for (index in inputs.indices) {
            if (index != 0) {
                out.append(',')
            }
            val length = inputs[index].size * 4
            view(out, length, start)
            start += length
        }
        for (channel in channels) {
            val length = channel.values.size * 4
            out.append(',')
            view(out, length, start)
            start += length
        }
        out.append(']')
    }

    private fun bound(times: IntArray, least: Boolean): Int {
        var bound = times[0]
        for (index in 1 until times.size) {
            val time = times[index]
            if (if (least) time < bound else time > bound) {
                bound = time
            }
        }
        return bound
    }

    private fun view(out: StringBuilder, length: Int, offset: Int) {
        out.append("{\"buffer\":0,\"byteLength\":").append(length).append(",\"byteOffset\":").append(offset).append('}')
    }

    private fun extras(out: StringBuilder, set: AnimationFrameSet, groups: Int) {
        out.append("{\"archive\":").append(set.archive)
        out.append(",\"base\":").append(set.baseId)
        out.append(",\"groups\":").append(groups)
        out.append(",\"layout\":{\"chunks\":").append(set.chunks)
            .append(",\"header\":").append(set.header).append('}')
        out.append(",\"frames\":[")
        for (index in set.frames.indices) {
            if (index != 0) {
                out.append(',')
            }
            frame(out, set, set.frames[index])
        }
        out.append("]}")
    }

    private fun frame(out: StringBuilder, set: AnimationFrameSet, frame: AnimationFrameDefinition) {
        out.append("{\"file\":").append(frame.id and MASK)
        if (frame.baseId != set.baseId) {
            out.append(",\"base\":").append(frame.baseId)
        }
        out.append(",\"header\":").append(frame.header)
        out.append(",\"count\":").append(frame.flags.size)
        var moved = 0
        for (flag in frame.flags) {
            if (flag != 0) {
                moved++
            }
        }
        out.append(",\"transforms\":[")
        var written = 0
        for (group in frame.flags.indices) {
            if (frame.flags[group] == 0) {
                continue
            }
            if (written++ != 0) {
                out.append(',')
            }
            out.append(group)
        }
        out.append("],\"flags\":[")
        written = 0
        for (flag in frame.flags) {
            if (flag == 0) {
                continue
            }
            if (written++ != 0) {
                out.append(',')
            }
            out.append(flag)
        }
        out.append(']')
        check(written == moved) { "Frame ${frame.id} wrote $written of $moved flag bytes." }
        Gltf.scalars(out, "values", frame.values.size) { frame.values[it] }
        frame.wideValues?.let { wide -> Gltf.scalars(out, "wide", wide.size) { wide[it] } }
        out.append('}')
    }

    private const val TRANSLATION = "translation"
    private const val ROTATION = "rotation"
    private const val SCALING = "scale"

    const val HEADER = 4

    const val CHUNKS = 3

    private const val MASK = (1 shl AnimationFrameDecoder.BITS) - 1
}

internal class AnimationFrameSet(
    val archive: Int,
    val baseId: Int,
    val frames: List<AnimationFrameDefinition>,
    val chunks: Int,
    val header: Int
) {

    fun fileIds(): IntArray = IntArray(frames.size) { frames[it].id and MASK }

    private companion object {
        private const val MASK = (1 shl AnimationFrameDecoder.BITS) - 1
    }
}
