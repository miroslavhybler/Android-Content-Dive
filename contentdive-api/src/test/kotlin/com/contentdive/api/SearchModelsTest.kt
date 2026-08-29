package com.contentdive.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SearchModelsTest {
    @Test
    fun `projection preserves fragment ownership metadata`() {
        val item = SearchItem(
            id = SearchItemId("E42"),
            scope = SearchScope("events"),
            title = "Launch",
            destination = DestinationRef("event", 1, "{\"eventId\":\"E42\"}"),
            subtitle = "Riverside Hall",
        )
        val fragment = SearchFragment(
            id = SearchFragmentId("title"),
            itemId = item.id,
            scope = item.scope,
            text = "Launch",
            kind = SearchFragmentKind.TITLE,
        )

        val projection = SearchProjection(item, listOf(fragment))

        assertEquals(item.id, projection.fragments.single().itemId)
        assertEquals("Riverside Hall", projection.item.subtitle)
    }

    @Test
    fun `query defers its default limit to engine configuration`() {
        assertNull(SearchQuery("launch").limit)
        assertEquals(20, ContentDiveConfiguration().defaultResultLimit)
        assertEquals(100, ContentDiveConfiguration().maximumResultLimit)
    }

    @Test
    fun `configuration validates its small public surface`() {
        assertFailsWith<IllegalArgumentException> {
            ContentDiveConfiguration(defaultResultLimit = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            ContentDiveConfiguration(defaultResultLimit = 20, maximumResultLimit = 10)
        }
        assertFailsWith<IllegalArgumentException> {
            SearchQuery("launch", limit = 0)
        }
        assertFailsWith<IllegalArgumentException> { SearchItemId(" ") }
        assertFailsWith<IllegalArgumentException> { SearchFragmentId("") }
        assertFailsWith<IllegalArgumentException> { SearchScope("\t") }
    }

    @Test
    fun `batch result exposes valid per-item outcomes`() {
        val successful = SearchBatchItemId(SearchScope("events"), SearchItemId("E1"))
        val failed = SearchBatchItemId(SearchScope("events"), SearchItemId("E2"))
        val result = SearchBatchResult(
            successfulItems = listOf(successful),
            failedItems = listOf(SearchBatchFailure(failed, "storage unavailable")),
        )

        assertFalse(result.isSuccess)
        assertEquals(successful, result.successfulItems.single())
        assertEquals(failed, result.failedItems.single().item)
        assertTrue(SearchBatchResult(emptyList(), emptyList()).isSuccess)
        assertFailsWith<IllegalArgumentException> {
            SearchBatchResult(listOf(successful), listOf(SearchBatchFailure(successful, "failed")))
        }
    }
}
