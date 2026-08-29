package com.contentdive.example

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.contentdive.api.SearchQuery
import com.contentdive.backend.appsearch.createAppSearchContentDive
import com.contentdive.example.integration.EventContentDiveIntegration
import com.contentdive.example.integration.EventDetailKey
import com.contentdive.example.mockdata.MockEvents
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun parkngFindsTheSameEventAfterBackendRestartWithoutReindexing() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val database = "contentdive-example-reopen-${UUID.randomUUID()}"
        val integration = EventContentDiveIntegration()
        val event = MockEvents.all.single { it.id == "E42" }
        val firstContentDive = createAppSearchContentDive(context, database)
        val firstMatch = runSuspendTest {
            val batch = firstContentDive.replaceAll(integration.project(MockEvents.all))
            assertEquals(MockEvents.all.size, batch.successfulItems.size)
            assertTrue(batch.isSuccess)
            firstContentDive.search(
                SearchQuery("parkng", scopes = setOf(integration.eventScope)),
            ).matches.first { it.item.id.value == "event:E42" }
        }
        firstContentDive.close()

        val reopenedContentDive = createAppSearchContentDive(context, database)
        try {
            val reopenedMatch = runSuspendTest {
                reopenedContentDive.search(
                    SearchQuery("parkng", scopes = setOf(integration.eventScope)),
                ).matches.first { it.item.id.value == "event:E42" }
            }

            assertEquals("event:E42", reopenedMatch.item.id.value)
            assertEquals("description:17", reopenedMatch.bestFragment.id.value)
            assertTrue(reopenedMatch.snippet.contains("Parking is available"))
            assertTrue(reopenedMatch.snippet.contains("after 18:00"))
            assertEquals(firstMatch.snippet, reopenedMatch.snippet)
            assertEquals(firstMatch.destination, reopenedMatch.destination)
            assertEquals(firstMatch.anchor, reopenedMatch.anchor)
            assertEquals(
                EventDetailKey(eventId = "E42", paragraphIndex = 17),
                integration.navigationPlanFor(reopenedMatch).keys.single(),
            )
        } finally {
            reopenedContentDive.close()
        }
    }
}

private fun <T> runSuspendTest(block: suspend () -> T): T {
    val outcome = AtomicReference<Result<T>>()
    val completed = CountDownLatch(1)
    block.startCoroutine(
        object : Continuation<T> {
            override val context = EmptyCoroutineContext

            override fun resumeWith(result: Result<T>) {
                outcome.set(result)
                completed.countDown()
            }
        },
    )
    check(completed.await(30, TimeUnit.SECONDS)) { "ContentDive operation timed out" }
    return checkNotNull(outcome.get()).getOrThrow()
}
