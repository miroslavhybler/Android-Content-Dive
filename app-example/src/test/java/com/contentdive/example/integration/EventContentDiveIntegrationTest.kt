package com.contentdive.example.integration

import com.contentdive.api.SearchQuery
import com.contentdive.api.SearchFragmentKind
import com.contentdive.backend.memory.createMemoryContentDive
import com.contentdive.example.mockdata.Event
import com.contentdive.example.mockdata.MockEvents
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class EventContentDiveIntegrationTest {
    private val integration = EventContentDiveIntegration()
    private val meetup = MockEvents.all.single { it.id == "E42" }
    private val styledArrival = MockEvents.all.single { it.id == "E133" }

    @Test
    fun `handwritten projector creates application-owned paragraph anchors`() {
        val projection = integration.project(meetup)
        val parkingFragment = projection.fragments.single { "parking" in it.text.lowercase() }

        assertEquals("event:E42", projection.item.id.value)
        assertEquals("events", projection.item.scope.value)
        assertEquals("event", projection.item.destination.type)
        assertEquals("description:17", parkingFragment.id.value)
        assertEquals("event-description-paragraph", parkingFragment.anchor?.type)
        assertNotNull(parkingFragment.anchor)
    }

    @Test
    fun `parkng fuzzy search resolves E42 and paragraph seventeen through the complete stack`() = runSuspend {
        val contentDive = createMemoryContentDive()
        try {
            contentDive.replace(integration.project(meetup))

            val result = contentDive.search(
                SearchQuery("parkng", scopes = setOf(integration.eventScope)),
            )

            val match = result.matches.single()
            assertEquals("event:E42", match.item.id.value)
            assertEquals("description:17", match.bestFragment.id.value)
            assertTrue(match.snippet.contains("Parking"))
            assertEquals("event", match.destination.type)
            assertTrue(match.destination.payload.contains("\"eventId\":\"E42\""))
            assertEquals("event-description-paragraph", match.anchor?.type)
            assertTrue(match.anchor?.payload?.contains("\"paragraphIndex\":17") == true)
            assertEquals(
                EventDetailKey(eventId = "E42", paragraphIndex = 17),
                integration.navigationPlanFor(match).keys.single(),
            )
        } finally {
            contentDive.close()
        }
    }

    @Test
    fun `oversized semantic block is internally chunked without losing its block anchor`() = runSuspend {
        val event = Event(
            id = "LONG",
            title = "Long-form event notes",
            location = "Archive",
            description = (0 until 200).joinToString(" ") { "term$it" } +
                " Parking is available after 18:00.",
            revision = 5,
        )
        val contentDive = createMemoryContentDive()
        try {
            contentDive.replace(integration.project(event))

            val match = contentDive.search(SearchQuery("parking")).matches.single()

            assertEquals("description:0", match.bestFragment.id.value)
            assertTrue(match.snippet.startsWith("… "))
            assertTrue(match.snippet.contains("Parking is available after 18:00."))
            assertEquals(
                EventDetailKey(eventId = "LONG", paragraphIndex = 0),
                integration.navigationPlanFor(match).keys.single(),
            )
        } finally {
            contentDive.close()
        }
    }

    @Test
    fun `styled annotated paragraphs become plain searchable blocks with their own anchors`() = runSuspend {
        val projection = integration.project(styledArrival)
        val heading = projection.fragments.single { it.id.value == "description:0" }
        val parking = projection.fragments.single { it.id.value == "description:2" }

        assertEquals(SearchFragmentKind.HEADING, heading.kind)
        assertEquals("Arrival information", heading.text)
        assertEquals("Parking is available behind the venue.", parking.text)
        assertEquals(String::class, parking.text::class)
        assertTrue(parking.anchor?.payload?.contains("\"paragraphIndex\":2") == true)
        assertEquals(
            listOf(
                "Arrival information",
                "Doors open at 18:00.",
                "Parking is available behind the venue.",
            ),
            integration.descriptionParagraphs(styledArrival),
        )

        val contentDive = createMemoryContentDive()
        try {
            val batch = contentDive.replaceAll(integration.project(MockEvents.all))
            assertEquals(MockEvents.all.size, batch.successfulItems.size)
            assertTrue(batch.isSuccess)

            val match = contentDive.search(SearchQuery("parking")).matches.single {
                it.item.id.value == "event:E133"
            }

            assertEquals("event:E133", match.item.id.value)
            assertEquals("description:2", match.bestFragment.id.value)
            assertEquals("Parking is available behind the venue.", match.snippet)
            assertEquals("event-description-paragraph", match.anchor?.type)
            assertEquals(
                EventDetailKey(eventId = "E133", paragraphIndex = 2),
                integration.navigationPlanFor(match).keys.single(),
            )
        } finally {
            contentDive.close()
        }
    }

    @Test
    fun `hundreds of API events are projected and searchable through one batch`() = runSuspend {
        val contentDive = createMemoryContentDive()
        try {
            val projections = integration.project(MockEvents.all)

            val result = contentDive.replaceAll(projections)
            val generated = contentDive.search(SearchQuery("capacity237")).matches.first()
            val fuzzyAnchored = contentDive.search(SearchQuery("parkng")).matches.first {
                it.item.id.value == "event:E42"
            }

            assertTrue(MockEvents.all.size >= 500)
            assertEquals(MockEvents.all.size, result.successfulItems.size)
            assertTrue(result.isSuccess)
            assertEquals("event:G237", generated.item.id.value)
            assertEquals("event", generated.destination.type)
            assertEquals("description:1", generated.bestFragment.id.value)
            assertEquals("event:E42", fuzzyAnchored.item.id.value)
            assertEquals("description:17", fuzzyAnchored.bestFragment.id.value)
            assertEquals("event-description-paragraph", fuzzyAnchored.anchor?.type)
        } finally {
            contentDive.close()
        }
    }
}

private fun <T> runSuspend(block: suspend () -> T): T {
    var outcome: Result<T>? = null
    block.startCoroutine(
        object : Continuation<T> {
            override val context = EmptyCoroutineContext

            override fun resumeWith(result: Result<T>) {
                outcome = result
            }
        },
    )
    return checkNotNull(outcome).getOrThrow()
}
