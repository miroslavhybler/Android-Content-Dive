package com.contentdive.backend.memory

import com.contentdive.api.ContentDiveConfiguration
import com.contentdive.api.ContentDiveLifecycleException
import com.contentdive.api.DestinationRef
import com.contentdive.api.SearchFragment
import com.contentdive.api.SearchFragmentId
import com.contentdive.api.SearchItem
import com.contentdive.api.SearchItemId
import com.contentdive.api.SearchProjection
import com.contentdive.api.SearchQuery
import com.contentdive.api.SearchScope
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Compiles and exercises the complete memory setup without importing the engine or SPI. */
internal class MemoryContentDiveFactoryTest {
    @Test
    fun `standard factory applies public configuration and owns lifecycle`() = runSuspend {
        val contentDive = createMemoryContentDive(
            ContentDiveConfiguration(
                defaultResultLimit = 1,
                maximumResultLimit = 2,
                fuzzySearchEnabled = false,
            ),
        )
        try {
            contentDive.replaceAll(listOf(projection("E2"), projection("E1")))

            assertEquals(1, contentDive.search(SearchQuery("parking")).matches.size)
            assertEquals(2, contentDive.search(SearchQuery("parking", limit = 2)).matches.size)
            assertTrue(contentDive.search(SearchQuery("parkng")).matches.isEmpty())
            assertFailsWith<IllegalArgumentException> {
                runSuspend { contentDive.search(SearchQuery("parking", limit = 3)) }
            }
        } finally {
            contentDive.close()
        }

        contentDive.close()
        assertFailsWith<ContentDiveLifecycleException> {
            runSuspend { contentDive.search(SearchQuery("parking")) }
        }
        Unit
    }

    private fun projection(id: String): SearchProjection {
        val itemId = SearchItemId(id)
        val scope = SearchScope("events")
        return SearchProjection(
            item = SearchItem(
                id = itemId,
                scope = scope,
                title = "Parking $id",
                destination = DestinationRef("event", 1, id),
            ),
            fragments = listOf(
                SearchFragment(
                    id = SearchFragmentId("title"),
                    itemId = itemId,
                    scope = scope,
                    text = "Parking $id",
                ),
            ),
        )
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
