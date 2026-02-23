package org.darkan.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

/**
 * Serializer for clan setting values which can be Int, Long, or String.
 */
object ClanSettingValueSerializer : KSerializer<Any> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ClanSettingValue") {
        element<JsonElement>("value")
    }

    override fun serialize(encoder: Encoder, value: Any) {
        val jsonEncoder = encoder as JsonEncoder
        val element = when (value) {
            is Int -> JsonPrimitive(value)
            is Long -> JsonPrimitive(value)
            is String -> JsonPrimitive(value)
            else -> throw IllegalArgumentException("Unsupported clan setting type: ${value::class}")
        }
        jsonEncoder.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): Any {
        val jsonDecoder = decoder as JsonDecoder
        val element = jsonDecoder.decodeJsonElement()
        return when {
            element is JsonPrimitive && element.isString -> element.content
            element is JsonPrimitive && element.longOrNull != null -> {
                val long = element.long
                if (long in Int.MIN_VALUE..Int.MAX_VALUE) long.toInt() else long
            }
            else -> throw IllegalArgumentException("Unsupported clan setting value: $element")
        }
    }
}

@Serializable
data class ClanMemberData(
    var rank: ClanRank = ClanRank.RECRUIT,
    val joinDate: Long = System.currentTimeMillis(),
)

@Serializable
class Clan(
    val name: String = "",
    var leaderUsername: String = "",
    val members: MutableMap<String, ClanMemberData> = mutableMapOf(),
    val bannedUsers: MutableSet<String> = mutableSetOf(),
    val settings: MutableMap<String, @Serializable(with = ClanSettingValueSerializer::class) Any> = mutableMapOf(),
    var ccChatRank: ClanRank = ClanRank.NONE,
    var ccKickRank: ClanRank = ClanRank.ADMIN,
) {
    companion object {
        const val MAX_MEMBERS = 100
        const val MIN_FILLER_SLOTS = 5
    }
}
