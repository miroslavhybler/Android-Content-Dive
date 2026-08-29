package com.contentdive.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import com.contentdive.api.AnchorRef
import com.contentdive.api.DestinationRef
import com.contentdive.api.SearchFragment
import com.contentdive.api.SearchFragmentId
import com.contentdive.api.SearchFragmentKind
import com.contentdive.api.SearchItem
import com.contentdive.api.SearchItemId
import com.contentdive.api.SearchProjection
import com.contentdive.api.SearchQuery
import com.contentdive.api.SearchScope
import com.contentdive.backend.memory.createMemoryContentDive
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

internal class AnnotatedStringSearchFragmentTest {
    @Test
    fun `plain and equivalent annotated text produce identical results`() {
        val text = "Parking is available behind the venue."
        val plain = fragment(text)
        val annotated = AnnotatedString(text).toSearchFragment(
            id = FRAGMENT_ID,
            itemId = ITEM_ID,
            scope = SCOPE,
            kind = SearchFragmentKind.BODY,
            weight = 1.5,
            anchor = ANCHOR,
        )

        assertEquals(search(plain, "parking"), search(annotated, "parking"))
    }

    @Test
    fun `styles links tags and annotations do not change searchable text`() {
        val annotated = buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Parking") }
            append(" is ")
            withStyle(
                SpanStyle(
                    fontStyle = FontStyle.Italic,
                    color = Color.Red,
                    textDecoration = TextDecoration.Underline,
                ),
            ) {
                append("available")
            }
            append(" behind the ")
            withLink(LinkAnnotation.Url("https://example.test/venue")) { append("venue") }
            append('.')
            addStyle(ParagraphStyle(textAlign = TextAlign.Center, lineHeight = 20.sp), 0, length)
            addStringAnnotation("block", "arrival-information", 0, length)
        }

        val fragment = annotated.toSearchFragment(FRAGMENT_ID, ITEM_ID, SCOPE)

        assertEquals("Parking is available behind the venue.", fragment.text)
        assertEquals(annotated.text, fragment.text)
        assertEquals(String::class, fragment.text::class)
        assertEquals(listOf("event:E42"), search(fragment, "parking").matches.itemIds())
        assertEquals(listOf("event:E42"), search(fragment, "venue").matches.itemIds())
    }

    @Test
    fun `annotated semantic blocks preserve their individual anchors`() {
        val blocks = listOf(
            AnnotatedString("Arrival information"),
            AnnotatedString("Doors open at 18:00."),
            AnnotatedString("Parking is available behind the venue."),
        )
        val fragments = blocks.mapIndexed { index, block ->
            block.toSearchFragment(
                id = SearchFragmentId("description:$index"),
                itemId = ITEM_ID,
                scope = SCOPE,
                kind = if (index == 0) SearchFragmentKind.HEADING else SearchFragmentKind.BODY,
                anchor = AnchorRef("paragraph", 1, "{\"index\":$index}"),
            )
        }

        val match = search(fragments, "parking").matches.single()

        assertEquals("description:2", match.bestFragment.id.value)
        assertEquals("{\"index\":2}", match.anchor?.payload)
        assertEquals("Parking is available behind the venue.", match.snippet)
    }

    @Test
    fun `long annotated text uses existing chunking and local snippets`() {
        val text = (0 until 200).joinToString(" ") { "term$it" } +
            " Parking is available behind the venue."
        val annotated = buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text) }
        }
        val fragment = annotated.toSearchFragment(
            id = FRAGMENT_ID,
            itemId = ITEM_ID,
            scope = SCOPE,
            anchor = ANCHOR,
        )

        val match = search(fragment, "parking").matches.single()

        assertTrue(match.snippet.startsWith("… "))
        assertTrue(match.snippet.contains("Parking is available behind the venue."))
        assertEquals(ANCHOR, match.anchor)
    }

    @Test
    fun `empty annotated text is validated exactly like empty string`() {
        val annotated = AnnotatedString("").toSearchFragment(FRAGMENT_ID, ITEM_ID, SCOPE)
        val plain = fragment("")

        val annotatedError = replaceFailure(annotated)
        val plainError = replaceFailure(plain)

        assertEquals(plainError.message, annotatedError.message)
        assertEquals("Fragment 'description:0' text must not be blank", annotatedError.message)
    }

    @Test
    fun `replacement removes old annotated content`() = runSuspend {
        val contentDive = createMemoryContentDive()
        try {
            contentDive.replace(
                projection(
                    AnnotatedString("Parking is available.")
                        .toSearchFragment(FRAGMENT_ID, ITEM_ID, SCOPE),
                ),
            )
            assertEquals(1, contentDive.search(SearchQuery("parking")).matches.size)

            contentDive.replace(
                projection(
                    AnnotatedString("Bicycle storage is available.")
                        .toSearchFragment(FRAGMENT_ID, ITEM_ID, SCOPE),
                ),
            )

            assertTrue(contentDive.search(SearchQuery("parking")).matches.isEmpty())
            assertEquals(1, contentDive.search(SearchQuery("bicycle")).matches.size)
        } finally {
            contentDive.close()
        }
    }

    private fun fragment(text: String): SearchFragment = SearchFragment(
        id = FRAGMENT_ID,
        itemId = ITEM_ID,
        scope = SCOPE,
        text = text,
        weight = 1.5,
        anchor = ANCHOR,
    )

    private fun search(fragment: SearchFragment, query: String) = search(listOf(fragment), query)

    private fun search(fragments: List<SearchFragment>, query: String) = runSuspend {
        val contentDive = createMemoryContentDive()
        try {
            contentDive.replace(projection(fragments))
            contentDive.search(SearchQuery(query))
        } finally {
            contentDive.close()
        }
    }

    private fun replaceFailure(fragment: SearchFragment): IllegalArgumentException {
        val contentDive = createMemoryContentDive()
        return try {
            assertFailsWith { runSuspend { contentDive.replace(projection(fragment)) } }
        } finally {
            contentDive.close()
        }
    }

    private fun projection(fragment: SearchFragment): SearchProjection = projection(listOf(fragment))

    private fun projection(fragments: List<SearchFragment>): SearchProjection = SearchProjection(
        item = SearchItem(
            id = ITEM_ID,
            scope = SCOPE,
            title = "Arrival information",
            destination = DestinationRef("event", 1, "{\"eventId\":\"E42\"}"),
        ),
        fragments = fragments,
    )

    private companion object {
        val ITEM_ID = SearchItemId("event:E42")
        val FRAGMENT_ID = SearchFragmentId("description:0")
        val SCOPE = SearchScope("events")
        val ANCHOR = AnchorRef("paragraph", 1, "{\"index\":0}")
    }
}

private fun List<com.contentdive.api.SearchMatch>.itemIds(): List<String> =
    map { it.item.id.value }

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
