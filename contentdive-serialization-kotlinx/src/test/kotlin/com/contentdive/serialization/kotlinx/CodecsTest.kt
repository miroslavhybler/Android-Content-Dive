package com.contentdive.serialization.kotlinx

import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

internal class CodecsTest {
    @Test
    fun `destination payload round trips`() {
        val codec = KotlinxDestinationCodec(
            type = "event",
            version = 1,
            serializer = EventDestination.serializer(),
        )

        val reference = codec.encode(EventDestination("E42"))

        assertEquals("{\"eventId\":\"E42\"}", reference.payload)
        assertEquals(EventDestination("E42"), codec.decode(reference))
    }

    @Test
    fun `codec rejects another schema version`() {
        val codec = KotlinxDestinationCodec(
            type = "event",
            version = 1,
            serializer = EventDestination.serializer(),
        )

        assertFailsWith<IllegalArgumentException> {
            codec.decode(com.contentdive.api.DestinationRef("event", 2, "{}"))
        }
    }

    @Serializable
    private data class EventDestination(val eventId: String)
}
