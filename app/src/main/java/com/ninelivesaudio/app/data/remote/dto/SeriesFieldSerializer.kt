package com.ninelivesaudio.app.data.remote.dto

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

/**
 * Handles both single object and array formats for the series field.
 * Library items endpoint returns a single object; expanded item returns an array.
 * Matches the C# SeriesConverter logic.
 */
typealias ApiSeriesField = @Serializable(with = SeriesFieldSerializer::class) List<ApiSeries>?

object SeriesFieldSerializer : KSerializer<List<ApiSeries>?> {

    private val json = Json { ignoreUnknownKeys = true }

    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("SeriesField")

    override fun deserialize(decoder: Decoder): List<ApiSeries>? {
        val jsonDecoder = decoder as? JsonDecoder ?: return null
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonNull -> null
            is JsonArray -> json.decodeFromJsonElement(
                ListSerializer(ApiSeries.serializer()),
                element
            )
            is JsonObject -> {
                val single = json.decodeFromJsonElement(ApiSeries.serializer(), element)
                listOf(single)
            }
            else -> null
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: List<ApiSeries>?) {
        val jsonEncoder = encoder as? JsonEncoder ?: return
        if (value == null) {
            jsonEncoder.encodeNull()
        } else {
            jsonEncoder.encodeJsonElement(
                json.encodeToJsonElement(ListSerializer(ApiSeries.serializer()), value)
            )
        }
    }
}
