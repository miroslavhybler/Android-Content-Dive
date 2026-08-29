package com.contentdive.engine

import com.contentdive.api.AnchorRef
import com.contentdive.api.ContentDiveConfiguration
import com.contentdive.api.ContentDiveException
import com.contentdive.api.ContentDiveLifecycleException
import com.contentdive.api.DestinationRef
import com.contentdive.api.SearchFragment
import com.contentdive.api.SearchFragmentId
import com.contentdive.api.SearchFragmentKind
import com.contentdive.api.SearchItem
import com.contentdive.api.SearchItemId
import com.contentdive.api.SearchBatchItemId
import com.contentdive.api.SearchProjection
import com.contentdive.api.SearchQuery
import com.contentdive.api.SearchScope
import com.contentdive.spi.BackendCandidate
import com.contentdive.spi.BackendCandidateRequest
import com.contentdive.spi.BackendCapabilities
import com.contentdive.spi.BackendBatchFailure
import com.contentdive.spi.BackendBatchWriteResult
import com.contentdive.spi.BackendTermCandidate
import com.contentdive.spi.BackendWriteResult
import com.contentdive.spi.ExperimentalContentDiveSpi
import com.contentdive.spi.FuzzyTermRequest
import com.contentdive.spi.PreparedProjection
import com.contentdive.spi.SearchBackend
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalContentDiveSpi::class)
internal class EngineSearchTest {
    @Test
    fun `empty query returns no results`() = runSuspend {
        val dive = ContentDiveEngine.create(TestSearchBackend())

        assertTrue(dive.search(SearchQuery("   ")).matches.isEmpty())
    }

    @Test
    fun `search is case insensitive`() = runSuspend {
        val dive = createDive(projection("E1", title = "Kotlin Meetup"))

        assertEquals("E1", dive.search(SearchQuery("kOtLiN")).matches.single().item.id.value)
    }

    @Test
    fun `diacritic folded query finds accented content`() = runSuspend {
        val dive = createDive(projection("E1", title = "Příjmy z reklamy"))

        assertEquals("E1", dive.search(SearchQuery("prijmy")).matches.single().item.id.value)
    }

    @Test
    fun `missing extra replacement and transposition typos find parking`() = runSuspend {
        val dive = createDive(projection("E1", title = "Meetup", body = "Parking available"))

        listOf("parkng", "parrking", "parkong", "pakring").forEach { typo ->
            assertEquals(
                "E1",
                dive.search(SearchQuery(typo)).matches.single().item.id.value,
                "Expected '$typo' to find parking",
            )
        }
    }

    @Test
    fun `revnue finds revenue`() = runSuspend {
        val dive = createDive(projection("E1", title = "Annual report", body = "Revenue increased"))

        assertEquals("E1", dive.search(SearchQuery("revnue")).matches.single().item.id.value)
    }

    @Test
    fun `configuration controls default maximum and fuzzy behavior without ranking knobs`() = runSuspend {
        val backend = TestSearchBackend()
        val dive = ContentDiveEngine.create(
            backend,
            ContentDiveConfiguration(
                defaultResultLimit = 2,
                maximumResultLimit = 3,
                fuzzySearchEnabled = false,
            ),
        )
        dive.replaceAll(
            listOf(
                projection("E3", title = "Parking three"),
                projection("E1", title = "Parking one"),
                projection("E2", title = "Parking two"),
            ),
        )

        assertEquals(2, dive.search(SearchQuery("parking")).matches.size)
        assertEquals(3, dive.search(SearchQuery("parking", limit = 3)).matches.size)
        assertTrue(dive.search(SearchQuery("parkng")).matches.isEmpty())
        assertFailsWith<IllegalArgumentException> {
            runSuspend { dive.search(SearchQuery("parking", limit = 4)) }
        }
        Unit
    }

    @Test
    fun `backend failures are wrapped with their original cause and cancellation is propagated`() {
        val storageFailure = IllegalStateException("storage unavailable")
        val failingDive = ContentDiveEngine.create(ThrowingSearchBackend(storageFailure))

        val wrapped = assertFailsWith<ContentDiveException> {
            runSuspend { failingDive.search(SearchQuery("parking")) }
        }
        assertSame(storageFailure, wrapped.cause)

        val cancellation = CancellationException("cancelled")
        val cancellingDive = ContentDiveEngine.create(ThrowingSearchBackend(cancellation))
        val propagated = assertFailsWith<CancellationException> {
            runSuspend { cancellingDive.search(SearchQuery("parking")) }
        }
        assertSame(cancellation, propagated)

        val closeFailure = IllegalStateException("close unavailable")
        val closeDive = ContentDiveEngine.create(CloseThrowingSearchBackend(closeFailure))
        val wrappedClose = assertFailsWith<ContentDiveException> { closeDive.close() }
        assertSame(closeFailure, wrappedClose.cause)
        closeDive.close()
    }

    @Test
    fun `fuzzy matching remains compatible with diacritic folding`() = runSuspend {
        val dive = createDive(projection("E1", title = "Report", body = "Příjmy z reklamy"))

        assertEquals("E1", dive.search(SearchQuery("prjmy")).matches.single().item.id.value)
    }

    @Test
    fun `exact match outranks a fuzzy match`() = runSuspend {
        val dive = createDive(
            projection("fuzzy", title = "Meetup", body = "Parkng instructions"),
            projection("exact", title = "Meetup", body = "Parking instructions"),
        )

        val ids = dive.search(SearchQuery("parking")).matches.map { it.item.id.value }

        assertEquals(listOf("exact", "fuzzy"), ids)
    }

    @Test
    fun `prefix match outranks a fuzzy match`() = runSuspend {
        val dive = createDive(
            projection("fuzzy", title = "Meetup", body = "Bark instructions"),
            projection("prefix", title = "Meetup", body = "Parking instructions"),
        )

        val ids = dive.search(SearchQuery("park")).matches.map { it.item.id.value }

        assertEquals(listOf("prefix", "fuzzy"), ids)
    }

    @Test
    fun `very short tokens do not enable fuzzy matching`() = runSuspend {
        val dive = createDive(projection("E1", title = "Tiny", body = "Ba"))

        assertTrue(dive.search(SearchQuery("pa")).matches.isEmpty())
    }

    @Test
    fun `unrelated terms are rejected by edit distance`() = runSuspend {
        val dive = createDive(
            projection("E1", title = "Civic briefing", body = "Parliamentary parties"),
        )

        assertTrue(dive.search(SearchQuery("parking")).matches.isEmpty())
    }

    @Test
    fun `fuzzy matching works in a multi-token query`() = runSuspend {
        val dive = createDive(
            projection("partial", title = "Meetup", body = "Parking only"),
            projection("complete", title = "Meetup", body = "Kotlin parking session"),
        )

        val ids = dive.search(SearchQuery("kotlin parkng")).matches.map { it.item.id.value }

        assertEquals(listOf("complete", "partial"), ids)
    }

    @Test
    fun `multiple fuzzy fragments produce one item result`() = runSuspend {
        val base = projection("E1", title = "Meetup", body = "Parking near the entrance")
        val secondBody = base.fragments.last().copy(
            id = SearchFragmentId("body-2"),
            text = "Parking behind the venue",
        )
        val dive = createDive(base.copy(fragments = base.fragments + secondBody))

        val result = dive.search(SearchQuery("parkng"))

        assertEquals(1, result.matches.size)
        assertEquals(2, result.matches.single().matchedFragments.size)
    }

    @Test
    fun `fuzzy search respects scope isolation`() = runSuspend {
        val dive = createDive(
            projection("A", title = "Meetup", body = "Parking", scope = "scope-a"),
            projection("B", title = "Meetup", body = "Parking", scope = "scope-b"),
        )

        val matches = dive.search(
            SearchQuery("parkng", scopes = setOf(SearchScope("scope-a"))),
        ).matches

        assertEquals(listOf("A"), matches.map { it.item.id.value })
    }

    @Test
    fun `fuzzy result ordering remains deterministic`() = runSuspend {
        val dive = createDive(
            projection("B", title = "Meetup", body = "Parking"),
            projection("A", title = "Meetup", body = "Parking"),
        )

        val first = dive.search(SearchQuery("parkng")).matches.map { it.item.id.value }
        val second = dive.search(SearchQuery("parkng")).matches.map { it.item.id.value }

        assertEquals(listOf("A", "B"), first)
        assertEquals(first, second)
    }

    @Test
    fun `exact token outranks prefix token`() = runSuspend {
        val dive = createDive(
            projection("prefix", title = "Parking"),
            projection("exact", title = "Park"),
        )

        val ids = dive.search(SearchQuery("park")).matches.map { it.item.id.value }

        assertEquals(listOf("exact", "prefix"), ids)
    }

    @Test
    fun `title match outranks description match`() = runSuspend {
        val dive = createDive(
            projection("body", title = "Venue details", body = "Parking available"),
            projection("title", title = "Parking guide", body = "Arrival details"),
        )

        val ids = dive.search(SearchQuery("parking")).matches.map { it.item.id.value }

        assertEquals(listOf("title", "body"), ids)
    }

    @Test
    fun `matching every query token outranks a partial match`() = runSuspend {
        val dive = createDive(
            projection("partial", title = "Kotlin conference"),
            projection("complete", title = "Kotlin meetup conference"),
        )

        val ids = dive.search(SearchQuery("kotlin meetup")).matches.map { it.item.id.value }

        assertEquals(listOf("complete", "partial"), ids)
    }

    @Test
    fun `phrase and proximity outrank separated terms`() = runSuspend {
        val dive = createDive(
            projection("separated", title = "Kotlin architecture community meetup"),
            projection("phrase", title = "Kotlin meetup"),
        )

        val ids = dive.search(SearchQuery("kotlin meetup")).matches.map { it.item.id.value }

        assertEquals(listOf("phrase", "separated"), ids)
    }

    @Test
    fun `higher fragment weight breaks otherwise equal ranks`() = runSuspend {
        val low = projection("low", title = "Meetup", body = "Parking").let { projection ->
            projection.copy(
                fragments = projection.fragments.map { fragment ->
                    if (fragment.id.value == "body") fragment.copy(weight = 1.0) else fragment
                },
            )
        }
        val high = projection("high", title = "Meetup", body = "Parking").let { projection ->
            projection.copy(
                fragments = projection.fragments.map { fragment ->
                    if (fragment.id.value == "body") fragment.copy(weight = 4.0) else fragment
                },
            )
        }
        val dive = createDive(low, high)

        val ids = dive.search(SearchQuery("parking")).matches.map { it.item.id.value }

        assertEquals(listOf("high", "low"), ids)
    }

    @Test
    fun `result limit is applied after deterministic ranking`() = runSuspend {
        val dive = createDive(
            projection("C", title = "Parking"),
            projection("B", title = "Parking"),
            projection("A", title = "Parking"),
        )

        val ids = dive.search(SearchQuery("parking", limit = 2)).matches.map { it.item.id.value }

        assertEquals(listOf("A", "B"), ids)
    }

    @Test
    fun `multiple matching fragments produce one item result`() = runSuspend {
        val dive = createDive(
            projection("E1", title = "Parking guide", body = "Parking behind the venue"),
        )

        val result = dive.search(SearchQuery("parking"))

        assertEquals(1, result.matches.size)
        assertEquals(2, result.matches.single().matchedFragments.size)
    }

    @Test
    fun `best fragment provides snippet and anchor`() = runSuspend {
        val anchor = AnchorRef("paragraph", 1, "{\"index\":7}")
        val dive = createDive(
            projection(
                id = "E1",
                title = "Meetup",
                body = "Parking is available behind the venue after 18:00.",
                bodyAnchor = anchor,
            ),
        )

        val match = dive.search(SearchQuery("parking")).matches.single()

        assertEquals("body", match.bestFragment.id.value)
        assertEquals(anchor, match.anchor)
        assertEquals("Parking is available behind the venue after 18:00.", match.snippet)
    }

    @Test
    fun `destination survives indexing and searching unchanged`() = runSuspend {
        val destination = DestinationRef("event", 7, "{\"eventId\":\"E42\"}")
        val backend = TestSearchBackend()
        val dive = ContentDiveEngine.create(backend)
        dive.replace(projection("E42", title = "Parking", destination = destination))

        val match = dive.search(SearchQuery("parking")).matches.single()

        assertSame(destination, match.destination)
    }

    @Test
    fun `scope filter cannot return another scope`() = runSuspend {
        val dive = createDive(
            projection("A", title = "Parking", scope = "scope-a"),
            projection("B", title = "Parking", scope = "scope-b"),
        )

        val matches = dive.search(
            SearchQuery("parking", scopes = setOf(SearchScope("scope-a"))),
        ).matches

        assertEquals(listOf("A"), matches.map { it.item.id.value })
    }

    @Test
    fun `result ordering has item id tie breaker`() = runSuspend {
        val dive = createDive(
            projection("B", title = "Parking"),
            projection("A", title = "Parking"),
        )

        val first = dive.search(SearchQuery("parking")).matches.map { it.item.id.value }
        val second = dive.search(SearchQuery("parking")).matches.map { it.item.id.value }

        assertEquals(listOf("A", "B"), first)
        assertEquals(first, second)
    }

    @Test
    fun `word near the end of a long description has a local original-text snippet`() = runSuspend {
        val body = longTokenText(210) +
            " The final arrival note says Parking is available after 18:00."
        val dive = createDive(projection("E42", title = "Kotlin Meetup", body = body))

        val match = dive.search(SearchQuery("parking")).matches.single()

        assertTrue(match.snippet.startsWith("… "))
        assertTrue(match.snippet.contains("Parking is available after 18:00."))
        assertTrue("term0" !in match.snippet)
    }

    @Test
    fun `phrase spanning a base chunk boundary remains searchable through overlap`() = runSuspend {
        val dive = createDive(
            projection("E1", title = "Boundary", body = longTokenText(170)),
        )

        val match = dive.search(SearchQuery("term79 term80")).matches.single()

        assertEquals("E1", match.item.id.value)
        assertTrue(match.snippet.contains("term79 term80"))
    }

    @Test
    fun `multiple matching internal chunks still create one logical result`() = runSuspend {
        val body = (0 until 190).joinToString(" ") { index ->
            if (index == 20 || index == 160) "parking" else "term$index"
        }
        val dive = createDive(projection("E1", title = "Meetup", body = body))

        val result = dive.search(SearchQuery("parking"))

        assertEquals(1, result.matches.size)
        assertEquals(listOf("body"), result.matches.single().matchedFragments.map { it.id.value })
    }

    @Test
    fun `best internal chunk determines the local snippet`() = runSuspend {
        val body = (0 until 190).joinToString(" ") { index ->
            when (index) {
                10 -> "parking"
                160 -> "parking available"
                else -> "term$index"
            }
        }
        val dive = createDive(projection("E1", title = "Meetup", body = body))

        val match = dive.search(SearchQuery("parking available")).matches.single()

        assertTrue(match.snippet.contains("parking available"))
        assertTrue("term0" !in match.snippet)
        assertTrue(match.snippet.startsWith("… "))
        assertTrue(match.snippet.endsWith(" …"))
    }

    @Test
    fun `logical fragment destination and anchor survive internal chunking`() = runSuspend {
        val anchor = AnchorRef("block", 1, "{\"blockId\":\"b17\"}")
        val destination = DestinationRef("event", 1, "{\"eventId\":\"E42\"}")
        val projection = projection(
            id = "E42",
            title = "Kotlin Meetup",
            body = longTokenText(180) + " Parking after eighteen.",
            destination = destination,
            bodyAnchor = anchor,
        )
        val originalBody = projection.fragments.single { it.id.value == "body" }
        val dive = createDive(projection)

        val match = dive.search(SearchQuery("parking")).matches.single()

        assertSame(originalBody, match.bestFragment)
        assertSame(destination, match.destination)
        assertSame(anchor, match.anchor)
    }

    @Test
    fun `short logical fragments create exactly one prepared chunk each`() = runSuspend {
        val backend = TestSearchBackend()
        val dive = ContentDiveEngine.create(backend)
        val projection = projection("E1", title = "Tiny title", body = "Small searchable body")

        dive.replace(projection)

        val prepared = backend.storedProjection(projection.item.scope, projection.item.id)
        assertEquals(2, prepared.chunks.size)
        assertTrue(
            prepared.chunks.groupingBy { it.sourceFragmentId }.eachCount().values.all { it == 1 },
        )
    }

    @Test
    fun `prepared chunk IDs and ranges are deterministic`() = runSuspend {
        val backend = TestSearchBackend()
        val dive = ContentDiveEngine.create(backend)
        val projection = projection("E1", title = "Meetup", body = longTokenText(190))
        dive.replace(projection)
        val first = backend.storedProjection(projection.item.scope, projection.item.id)
            .chunks
            .map { Triple(it.id, it.sourceStart, it.sourceEnd) }

        dive.replace(projection)
        val second = backend.storedProjection(projection.item.scope, projection.item.id)
            .chunks
            .map { Triple(it.id, it.sourceStart, it.sourceEnd) }

        assertEquals(first, second)
        assertTrue(first.size > 1)
    }

    @Test
    fun `invalid projection metadata is rejected by the engine`() = runSuspend {
        val dive = ContentDiveEngine.create(TestSearchBackend())
        val valid = projection("E1", title = "Parking")

        assertFailsWith<IllegalArgumentException> {
            runSuspend { dive.replace(valid.copy(item = valid.item.copy(id = SearchItemId("")))) }
        }
        assertFailsWith<IllegalArgumentException> {
            runSuspend {
                dive.replace(valid.copy(fragments = valid.fragments + valid.fragments.first()))
            }
        }
        assertFailsWith<IllegalArgumentException> {
            runSuspend {
                dive.replace(
                    valid.copy(
                        fragments = valid.fragments.map {
                            it.copy(itemId = SearchItemId("another-item"))
                        },
                    ),
                )
            }
        }
        assertFailsWith<IllegalArgumentException> {
            runSuspend {
                dive.replace(
                    valid.copy(
                        fragments = valid.fragments.map { it.copy(scope = SearchScope("other")) },
                    ),
                )
            }
        }
        Unit
    }

    @Test
    fun `batch indexing matches individual indexing and returns deterministic outcomes`() = runSuspend {
        val projections = listOf(
            projection("E3", title = "Workshop", body = "Parking gamma"),
            projection("E1", title = "Meetup", body = "Parking alpha"),
            projection("E2", title = "Conference", body = "Parking beta"),
        )
        val individual = createDive(*projections.toTypedArray())
        val batch = ContentDiveEngine.create(TestSearchBackend())

        val result = batch.replaceAll(projections.reversed())

        assertTrue(result.isSuccess)
        assertEquals(listOf("E1", "E2", "E3"), result.successfulItems.map { it.itemId.value })
        assertEquals(
            individual.search(SearchQuery("parking")).matches,
            batch.search(SearchQuery("parking")).matches,
        )
    }

    @Test
    fun `duplicate scoped IDs and invalid items reject the complete batch before mutation`() = runSuspend {
        val backend = TestSearchBackend()
        val dive = ContentDiveEngine.create(backend)
        val valid = projection("E1", title = "Parking")

        assertFailsWith<IllegalArgumentException> {
            runSuspend { dive.replaceAll(listOf(valid, valid.copy())) }
        }
        assertFailsWith<IllegalArgumentException> {
            runSuspend {
                dive.replaceAll(
                    listOf(
                        valid,
                        projection("E2", title = "Invalid").copy(fragments = emptyList()),
                    ),
                )
            }
        }

        assertEquals(0, backend.replaceAllCalls)
        assertTrue(dive.search(SearchQuery("parking")).matches.isEmpty())
    }

    @Test
    fun `one failed batch item does not hide successful items`() = runSuspend {
        val backend = TestSearchBackend(failingItemId = SearchItemId("E2"))
        val dive = ContentDiveEngine.create(backend)

        val result = dive.replaceAll(
            listOf(
                projection("E3", title = "Parking three"),
                projection("E2", title = "Parking two"),
                projection("E1", title = "Parking one"),
            ),
        )

        assertFalse(result.isSuccess)
        assertEquals(listOf("E1", "E3"), result.successfulItems.map { it.itemId.value })
        assertEquals(listOf("E2"), result.failedItems.map { it.item.itemId.value })
        assertEquals(
            listOf("E1", "E3"),
            dive.search(SearchQuery("parking")).matches.map { it.item.id.value }.sorted(),
        )
    }

    @Test
    fun `batch removal validates duplicates and reports per-item outcomes`() = runSuspend {
        val scope = SearchScope("events")
        val backend = TestSearchBackend(failingItemId = SearchItemId("E2"))
        val dive = ContentDiveEngine.create(backend)
        dive.replaceAll(
            listOf(
                projection("E1", title = "Parking one"),
                projection("E3", title = "Parking three"),
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            runSuspend { dive.removeAll(scope, listOf(SearchItemId("E1"), SearchItemId("E1"))) }
        }
        val result = dive.removeAll(
            scope,
            listOf(SearchItemId("E3"), SearchItemId("E2"), SearchItemId("E1")),
        )

        assertEquals(listOf("E1", "E3"), result.successfulItems.map { it.itemId.value })
        assertEquals(listOf("E2"), result.failedItems.map { it.item.itemId.value })
        assertTrue(dive.search(SearchQuery("parking")).matches.isEmpty())
    }

    @Test
    fun `single item operations use batch paths`() = runSuspend {
        val backend = TestSearchBackend()
        val dive = ContentDiveEngine.create(backend)

        dive.replace(projection("E1", title = "Parking"))
        dive.remove(SearchScope("events"), SearchItemId("E1"))

        assertEquals(1, backend.replaceAllCalls)
        assertEquals(1, backend.removeAllCalls)
    }

    @Test
    fun `close is idempotent closes the backend and rejects every later operation`() = runSuspend {
        val backend = TestSearchBackend()
        val dive = ContentDiveEngine.create(backend)
        dive.close()
        dive.close()

        assertEquals(1, backend.closeCalls)
        assertFailsWith<ContentDiveLifecycleException> {
            runSuspend { dive.search(SearchQuery("parking")) }
        }
        assertFailsWith<ContentDiveLifecycleException> { runSuspend { dive.replaceAll(emptyList()) } }
        assertFailsWith<ContentDiveLifecycleException> {
            runSuspend { dive.removeAll(SearchScope("events"), emptyList()) }
        }
        assertFailsWith<ContentDiveLifecycleException> {
            runSuspend { dive.clear(SearchScope("events")) }
        }
        Unit
    }

    @Test
    fun `close defers backend release until an in-flight operation finishes`() {
        val backend = PausingSearchBackend()
        val dive = ContentDiveEngine.create(backend)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val operation = executor.submit {
                runSuspending { dive.replace(projection("E1", title = "Parking")) }
            }
            assertTrue(backend.started.await(5, TimeUnit.SECONDS))

            dive.close()

            assertEquals(0, backend.closeCalls)
            assertFailsWith<ContentDiveLifecycleException> {
                runSuspend { dive.search(SearchQuery("parking")) }
            }
            backend.release()
            operation.get(5, TimeUnit.SECONDS)
            assertEquals(1, backend.closeCalls)
        } finally {
            executor.shutdownNow()
        }
    }

    private suspend fun createDive(vararg projections: SearchProjection) =
        ContentDiveEngine.create(TestSearchBackend()).also { dive ->
            projections.forEach { dive.replace(it) }
        }

    private fun projection(
        id: String,
        title: String,
        body: String = "Details",
        scope: String = "events",
        destination: DestinationRef = DestinationRef("event", 1, "{\"eventId\":\"$id\"}"),
        bodyAnchor: AnchorRef? = null,
    ): SearchProjection {
        val itemId = SearchItemId(id)
        val itemScope = SearchScope(scope)
        return SearchProjection(
            item = SearchItem(itemId, itemScope, title, destination),
            fragments = listOf(
                SearchFragment(
                    id = SearchFragmentId("title"),
                    itemId = itemId,
                    scope = itemScope,
                    text = title,
                    kind = SearchFragmentKind.TITLE,
                ),
                SearchFragment(
                    id = SearchFragmentId("body"),
                    itemId = itemId,
                    scope = itemScope,
                    text = body,
                    kind = SearchFragmentKind.BODY,
                    anchor = bodyAnchor,
                ),
            ),
        )
    }

    private fun longTokenText(count: Int): String =
        (0 until count).joinToString(" ") { index -> "term$index" }
}

@OptIn(ExperimentalContentDiveSpi::class)
private class TestSearchBackend(
    private val failingItemId: SearchItemId? = null,
) : SearchBackend {
    private val projections = linkedMapOf<Pair<SearchScope, SearchItemId>, PreparedProjection>()
    var closeCalls: Int = 0
        private set
    var replaceAllCalls: Int = 0
        private set
    var removeAllCalls: Int = 0
        private set

    fun storedProjection(scope: SearchScope, itemId: SearchItemId): PreparedProjection =
        checkNotNull(projections[scope to itemId])

    override val capabilities = BackendCapabilities(
        supportsPrefixCandidates = true,
        supportsFuzzyCandidates = true,
    )

    override suspend fun replaceAll(
        projections: List<PreparedProjection>,
    ): BackendBatchWriteResult {
        replaceAllCalls += 1
        val successful = mutableListOf<SearchBatchItemId>()
        val failures = mutableListOf<BackendBatchFailure>()
        var affectedFragments = 0
        projections.forEach { projection ->
            val item = SearchBatchItemId(projection.item.scope, projection.item.id)
            if (projection.item.id == failingItemId) {
                failures += BackendBatchFailure(item, "Deliberate test failure")
            } else {
                this.projections[projection.item.scope to projection.item.id] = projection
                successful += item
                affectedFragments += projection.fragments.size
            }
        }
        return BackendBatchWriteResult(
            successfulItems = successful,
            failedItems = failures,
            affectedItems = successful.size,
            affectedFragments = affectedFragments,
        )
    }

    override suspend fun removeAll(
        scope: SearchScope,
        itemIds: List<SearchItemId>,
    ): BackendBatchWriteResult {
        removeAllCalls += 1
        val successful = mutableListOf<SearchBatchItemId>()
        val failures = mutableListOf<BackendBatchFailure>()
        var affectedFragments = 0
        itemIds.forEach { itemId ->
            val item = SearchBatchItemId(scope, itemId)
            if (itemId == failingItemId) {
                failures += BackendBatchFailure(item, "Deliberate test failure")
            } else {
                affectedFragments += projections.remove(scope to itemId)?.fragments?.size ?: 0
                successful += item
            }
        }
        return BackendBatchWriteResult(
            successfulItems = successful,
            failedItems = failures,
            affectedItems = successful.size,
            affectedFragments = affectedFragments,
        )
    }

    override suspend fun clear(scope: SearchScope): BackendWriteResult {
        val keys = projections.keys.filter { it.first == scope }
        val fragments = keys.sumOf { projections[it]?.fragments?.size ?: 0 }
        keys.forEach(projections::remove)
        return BackendWriteResult(keys.size, fragments)
    }

    override suspend fun candidates(request: BackendCandidateRequest): List<BackendCandidate> =
        projections.values.asSequence()
            .filter { request.scopes.isEmpty() || it.item.scope in request.scopes }
            .flatMap { projection ->
                val fragmentsById = projection.fragments.associateBy { it.id }
                projection.chunks.asSequence().map { chunk ->
                    BackendCandidate(
                        item = projection.item,
                        sourceFragment = checkNotNull(fragmentsById[chunk.sourceFragmentId]),
                        chunk = chunk,
                    )
                }
            }
            .filter { candidate ->
                request.tokens.any { query ->
                    candidate.chunk.tokens.any { indexed ->
                        indexed.value == query ||
                            request.includePrefixes && indexed.value.startsWith(query)
                    }
                }
            }
            .take(request.limit)
            .toList()

    override suspend fun fuzzyTerms(request: FuzzyTermRequest): List<BackendTermCandidate> {
        val frequencies = projections.values
            .flatMap(PreparedProjection::chunks)
            .flatMap { chunk -> chunk.tokens.map { it.value }.distinct() }
            .groupingBy { it }
            .eachCount()
        return frequencies.asSequence()
            .filter { (term) -> term != request.normalizedQueryToken }
            .map { (term, frequency) ->
                val overlap = FuzzyMatcher.characterTrigrams(term)
                    .intersect(request.trigrams)
                    .size
                Triple(term, frequency, overlap)
            }
            .filter { (_, _, overlap) -> overlap > 0 }
            .map { (term, frequency, overlap) ->
                BackendTermCandidate(
                    indexedTerm = term,
                    trigramOverlap = overlap,
                    documentFrequency = frequency,
                )
            }
            .sortedWith(
                compareByDescending<BackendTermCandidate>(BackendTermCandidate::trigramOverlap)
                    .thenByDescending { it.documentFrequency ?: 0 }
                    .thenBy(BackendTermCandidate::indexedTerm),
            )
            .take(request.candidateLimit)
            .toList()
    }

    override fun close() {
        closeCalls += 1
    }
}

@OptIn(ExperimentalContentDiveSpi::class)
private class ThrowingSearchBackend(
    private val failure: RuntimeException,
) : SearchBackend by TestSearchBackend() {
    override val capabilities: BackendCapabilities
        get() = BackendCapabilities(supportsPrefixCandidates = true, supportsFuzzyCandidates = true)

    override suspend fun candidates(request: BackendCandidateRequest): List<BackendCandidate> {
        throw failure
    }
}

@OptIn(ExperimentalContentDiveSpi::class)
private class CloseThrowingSearchBackend(
    private val failure: RuntimeException,
) : SearchBackend by TestSearchBackend() {
    override fun close() {
        throw failure
    }
}

@OptIn(ExperimentalContentDiveSpi::class)
private class PausingSearchBackend : SearchBackend by TestSearchBackend() {
    val started = CountDownLatch(1)
    private val closeCount = AtomicInteger()
    private val continuationLock = Any()
    private var continuation: Continuation<Unit>? = null

    val closeCalls: Int
        get() = closeCount.get()

    override suspend fun replaceAll(
        projections: List<PreparedProjection>,
    ): BackendBatchWriteResult {
        suspendCoroutine<Unit> { pending ->
            synchronized(continuationLock) {
                continuation = pending
            }
            started.countDown()
        }
        return BackendBatchWriteResult(
            successfulItems = projections.map { SearchBatchItemId(it.item.scope, it.item.id) },
            failedItems = emptyList(),
            affectedItems = projections.size,
            affectedFragments = projections.sumOf { it.fragments.size },
        )
    }

    fun release() {
        val pending = synchronized(continuationLock) {
            checkNotNull(continuation).also { continuation = null }
        }
        pending.resume(Unit)
    }

    override fun close() {
        closeCount.incrementAndGet()
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

private fun <T> runSuspending(block: suspend () -> T): T {
    val completed = CountDownLatch(1)
    var outcome: Result<T>? = null
    block.startCoroutine(
        object : Continuation<T> {
            override val context = EmptyCoroutineContext

            override fun resumeWith(result: Result<T>) {
                outcome = result
                completed.countDown()
            }
        },
    )
    check(completed.await(10, TimeUnit.SECONDS)) { "Suspending test operation timed out" }
    return checkNotNull(outcome).getOrThrow()
}
