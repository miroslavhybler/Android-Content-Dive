package com.contentdive.example.integration

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.contentdive.api.DestinationRef
import com.contentdive.api.SearchFragment
import com.contentdive.api.SearchFragmentKind
import com.contentdive.api.SearchItem
import com.contentdive.api.SearchProjection
import com.contentdive.api.SearchQuery
import com.contentdive.api.SearchScope
import com.contentdive.backend.memory.createMemoryContentDive
import com.contentdive.ksp.annotations.ContentDiveGeneratedIds
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class GeneratedProjectorIntegrationTest {
    @Test
    fun `generated projector equals handwritten projection and searches long content`() = runGeneratedTest {
        val description = (0 until 220).joinToString(" ") { "archive$it" } +
            " The observatory entrance opens after sunset."
        val event = SimpleEvent(
            id = "S:7",
            title = "Night sky briefing",
            location = "Hilltop observatory",
            description = description,
        )
        val scope = SearchScope("simple-events")
        val destination = DestinationRef("simple-event", 1, "{\"id\":\"S:7\"}")
        var scopeInvocations = 0
        var destinationInvocations = 0
        val generated = SimpleEventContentDiveProjector(
            scopeProvider = {
                scopeInvocations += 1
                scope
            },
            destinationProvider = {
                destinationInvocations += 1
                destination
            },
        ).project(event)
        val expected = handwrittenProjection(event, scope, destination)

        assertEquals(expected, generated)
        assertEquals(1, scopeInvocations)
        assertEquals(1, destinationInvocations)
        assertEquals("simple-event:S%3A7", generated.item.id.value)
        assertEquals("Hilltop observatory", generated.item.subtitle)
        assertEquals(listOf("title", "description"), generated.fragments.map { it.id.value })
        assertEquals(description, generated.fragments.last().text)

        val contentDive = createMemoryContentDive()
        try {
            contentDive.replace(generated)

            val match = contentDive.search(SearchQuery("observatory entrance")).matches.single()

            assertEquals(destination, match.destination)
            assertEquals("description", match.bestFragment.id.value)
            assertTrue(match.snippet.contains("observatory entrance opens after sunset"))
        } finally {
            contentDive.close()
        }
    }

    @Test
    fun `KSP processor is absent from the application runtime classpath`() {
        val runtimeEntries = checkNotNull(System.getProperty("java.class.path"))
            .split(checkNotNull(System.getProperty("path.separator")))

        assertFalse(runtimeEntries.any { "contentdive-ksp-processor" in it })
    }

    @Test
    fun `generated annotated nullable fragment skips null and blank values`() {
        val base = SimpleEvent(
            id = "A1",
            title = "Arrival",
            location = "Main hall",
            description = "Doors open at 18:00.",
        )
        val projector = SimpleEventContentDiveProjector(
            scopeProvider = { SearchScope("simple-events") },
            destinationProvider = { DestinationRef("simple-event", 1, it.id) },
        )

        assertEquals(listOf("title", "description"), projector.project(base).fragments.map { it.id.value })
        assertEquals(
            listOf("title", "description"),
            projector.project(base.copy(note = AnnotatedString("   "))).fragments.map { it.id.value },
        )

        val styledNote = buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Parking") }
            append(" is behind the venue.")
        }
        val noteFragment = projector.project(base.copy(note = styledNote)).fragments.last()

        assertEquals("note", noteFragment.id.value)
        assertEquals("Parking is behind the venue.", noteFragment.text)
        assertEquals(SearchFragmentKind.HEADING, noteFragment.kind)
        assertEquals(1.25, noteFragment.weight, 0.0)
    }

    private fun handwrittenProjection(
        event: SimpleEvent,
        scope: SearchScope,
        destination: DestinationRef,
    ): SearchProjection {
        val itemId = ContentDiveGeneratedIds.itemId("simple-event", event.id)
        return SearchProjection(
            item = SearchItem(
                id = itemId,
                scope = scope,
                title = event.title,
                destination = destination,
                subtitle = event.location,
            ),
            fragments = listOf(
                SearchFragment(
                    id = ContentDiveGeneratedIds.fragmentId("title"),
                    itemId = itemId,
                    scope = scope,
                    text = event.title,
                    kind = SearchFragmentKind.TITLE,
                    weight = 2.0,
                ),
                SearchFragment(
                    id = ContentDiveGeneratedIds.fragmentId("description"),
                    itemId = itemId,
                    scope = scope,
                    text = event.description,
                    kind = SearchFragmentKind.BODY,
                    weight = 1.0,
                ),
            ),
        )
    }
}

private fun <T> runGeneratedTest(block: suspend () -> T): T {
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
