package com.contentdive.spi.testing

import com.contentdive.api.AnchorRef
import com.contentdive.api.DestinationRef
import com.contentdive.api.SearchFragment
import com.contentdive.api.SearchFragmentId
import com.contentdive.api.SearchItem
import com.contentdive.api.SearchItemId
import com.contentdive.api.SearchScope
import com.contentdive.spi.BackendCandidate
import com.contentdive.spi.BackendCandidateRequest
import com.contentdive.spi.BackendTermCandidate
import com.contentdive.spi.ExperimentalContentDiveSpi
import com.contentdive.spi.FuzzyTermRequest
import com.contentdive.spi.PreparedProjection
import com.contentdive.spi.PreparedTextChunk
import com.contentdive.spi.PreparedToken
import com.contentdive.spi.SearchBackend
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Backend-neutral behavior every ContentDive backend must preserve. */
@OptIn(ExperimentalContentDiveSpi::class)
public abstract class SearchBackendContract {
    protected abstract fun createBackend(): SearchBackend

    @Test
    public fun exactPrefixAndFuzzyRetrievalUseTheSameProjection(): Unit = withBackend { backend ->
        backend.replace(preparedProjection("E1", "events", "body" to "parking available"))

        assertEquals(listOf("E1"), backend.find("parking", includePrefixes = false).itemIds())
        assertEquals(listOf("E1"), backend.find("park", includePrefixes = true).itemIds())
        assertEquals("parking", backend.fuzzy("parkng").first().indexedTerm)
    }

    @Test
    public fun replacementRemovesEveryOldChunkAndFuzzyTerm(): Unit = withBackend { backend ->
        backend.replace(
            preparedChunkedProjection(
                id = "E1",
                scope = "events",
                fragmentId = "body",
                chunkTexts = arrayOf("old alpha", "old beta", "old parking"),
            ),
        )

        backend.replace(preparedProjection("E1", "events", "new" to "bicycle storage"))

        assertTrue(backend.find("old").isEmpty())
        assertTrue(backend.find("parking").isEmpty())
        assertTrue(backend.fuzzy("parkng").none { it.indexedTerm == "parking" })
        assertEquals(listOf("new"), backend.find("bicycle").map { it.sourceFragment.id.value })
    }

    @Test
    public fun removingAnItemRemovesAllSearchableContent(): Unit = withBackend { backend ->
        backend.replace(preparedProjection("E1", "events", "body" to "parking available"))

        val result = backend.remove(SearchScope("events"), SearchItemId("E1"))

        assertEquals(1, result.affectedItems)
        assertTrue(backend.find("parking").isEmpty())
        assertTrue(backend.fuzzy("parkng").none { it.indexedTerm == "parking" })
    }

    @Test
    public fun clearingOneScopeDoesNotAffectAnother(): Unit = withBackend { backend ->
        backend.replace(preparedProjection("E1", "scope-a", "body" to "parking alpha"))
        backend.replace(preparedProjection("E2", "scope-b", "body" to "parking beta"))

        val result = backend.clear(SearchScope("scope-a"))

        assertEquals(1, result.affectedItems)
        assertEquals(
            listOf("E2"),
            backend.find("parking", scopes = setOf(SearchScope("scope-b"))).itemIds(),
        )
        assertTrue(
            backend.find("parking", scopes = setOf(SearchScope("scope-a"))).isEmpty(),
        )
    }

    @Test
    public fun duplicateReplacementProducesNoDuplicateCandidates(): Unit = withBackend { backend ->
        val projection = preparedProjection("E1", "events", "body" to "parking available")

        repeat(5) { backend.replace(projection) }

        assertEquals(1, backend.find("parking").size)
    }

    @Test
    public fun longContentChunksRemainIndividuallySearchable(): Unit = withBackend { backend ->
        backend.replace(
            preparedChunkedProjection(
                id = "E1",
                scope = "events",
                fragmentId = "description",
                chunkTexts = arrayOf(
                    "introductory material",
                    "more contextual material",
                    "parking is available after 18 00",
                ),
            ),
        )

        val candidate = backend.find("parking").single()

        assertEquals(2, candidate.chunk.ordinal)
        assertEquals("parking is available after 18 00", candidate.chunk.originalText)
        assertEquals("description", candidate.sourceFragment.id.value)
    }

    @Test
    public fun destinationAndAnchorSurviveBackendRoundTrip(): Unit = withBackend { backend ->
        val destination = DestinationRef("event", 7, "{\"eventId\":\"E42\"}")
        val anchor = AnchorRef(
            "event-description-paragraph",
            3,
            "{\"eventId\":\"E42\",\"paragraphIndex\":17}",
        )
        backend.replace(
            preparedProjection(
                id = "E42",
                scope = "events",
                fragments = arrayOf("description:17" to "parking is available"),
                destination = destination,
                anchor = anchor,
            ),
        )

        val candidate = backend.find("parking").single()

        assertEquals(destination, candidate.item.destination)
        assertEquals(anchor, candidate.sourceFragment.anchor)
        assertEquals(anchor, candidate.chunk.anchor)
    }

    @Test
    public fun candidateLimitsAndOrderingAreDeterministic(): Unit = withBackend { backend ->
        listOf("E3", "E1", "E2").forEach { id ->
            backend.replace(preparedProjection(id, "events", "body" to "parking $id"))
        }

        val first = backend.find("parking", limit = 2)
        val second = backend.find("parking", limit = 2)
        val firstFuzzy = backend.fuzzy("parkng", limit = 1)
        val secondFuzzy = backend.fuzzy("parkng", limit = 1)

        assertEquals(listOf("E1", "E2"), first.itemIds())
        assertEquals(first, second)
        assertEquals(1, firstFuzzy.size)
        assertEquals(firstFuzzy, secondFuzzy)
    }

    @Test
    public fun batchIndexingProducesTheSameCandidatesAsIndividualIndexing() {
        val projections = listOf(
            preparedProjection("E3", "events", "body" to "parking gamma"),
            preparedProjection("E1", "events", "body" to "parking alpha"),
            preparedProjection("E2", "events", "body" to "parking beta"),
        )
        val individual = createBackend()
        val batch = createBackend()
        try {
            runSuspendTest {
                projections.forEach { individual.replace(it) }
                val result = batch.replaceAll(projections.reversed())

                assertEquals(listOf("E1", "E2", "E3"), result.successfulItems.map { it.itemId.value })
                assertTrue(result.failedItems.isEmpty())
                assertEquals(individual.find("parking"), batch.find("parking"))
            }
        } finally {
            individual.close()
            batch.close()
        }
    }

    @Test
    public fun batchReplacementRemovesAllOldPostingsAndFuzzyTerms(): Unit = withBackend { backend ->
        backend.replaceAll(
            listOf(
                preparedProjection("E1", "events", "old" to "parking alpha"),
                preparedProjection("E2", "events", "old" to "parking beta"),
            ),
        )

        backend.replaceAll(
            listOf(
                preparedProjection("E2", "events", "new" to "bicycle beta"),
                preparedProjection("E1", "events", "new" to "bicycle alpha"),
            ),
        )

        assertTrue(backend.find("parking").isEmpty())
        assertTrue(backend.fuzzy("parkng").none { it.indexedTerm == "parking" })
        assertEquals(listOf("E1", "E2"), backend.find("bicycle").itemIds())
    }

    @Test
    public fun batchRemovalDeletesPostingsAndFuzzyTermsWithoutCrossingScopes(): Unit =
        withBackend { backend ->
            backend.replaceAll(
                listOf(
                    preparedProjection("E2", "scope-a", "body" to "parking beta"),
                    preparedProjection("E1", "scope-b", "body" to "parking retained"),
                    preparedProjection("E1", "scope-a", "body" to "parking alpha"),
                ),
            )

            val result = backend.removeAll(
                SearchScope("scope-a"),
                listOf(SearchItemId("E2"), SearchItemId("E1")),
            )

            assertEquals(listOf("E1", "E2"), result.successfulItems.map { it.itemId.value })
            assertTrue(backend.find("parking", scopes = setOf(SearchScope("scope-a"))).isEmpty())
            assertEquals(
                listOf("E1"),
                backend.find("parking", scopes = setOf(SearchScope("scope-b"))).itemIds(),
            )
            assertEquals("parking", backend.fuzzy("parkng").first().indexedTerm)
        }

    @Test
    public fun duplicateScopedItemsRejectTheBatchBeforeMutation(): Unit = withBackend { backend ->
        val projection = preparedProjection("E1", "events", "body" to "parking")

        assertFailsWith<IllegalArgumentException> {
            runSuspendTest { backend.replaceAll(listOf(projection, projection)) }
        }

        assertTrue(backend.find("parking").isEmpty())
    }

    @Test
    public fun backendCloseIsIdempotentAndLaterOperationsFailPredictably() {
        val backend = createBackend()
        backend.close()
        backend.close()

        assertFailsWith<IllegalStateException> {
            runSuspendTest { backend.replaceAll(emptyList()) }
        }
        assertFailsWith<IllegalStateException> {
            runSuspendTest { backend.removeAll(SearchScope("events"), emptyList()) }
        }
        assertFailsWith<IllegalStateException> {
            runSuspendTest {
                backend.candidates(BackendCandidateRequest(listOf("parking"), limit = 1))
            }
        }
    }

    private fun withBackend(block: suspend (SearchBackend) -> Unit) {
        val backend = createBackend()
        try {
            runSuspendTest { block(backend) }
        } finally {
            backend.close()
        }
    }
}

@OptIn(ExperimentalContentDiveSpi::class)
public suspend fun SearchBackend.find(
    token: String,
    includePrefixes: Boolean = true,
    scopes: Set<SearchScope> = emptySet(),
    limit: Int = Int.MAX_VALUE,
): List<BackendCandidate> = candidates(
    BackendCandidateRequest(
        tokens = listOf(token),
        scopes = scopes,
        includePrefixes = includePrefixes,
        limit = limit,
    ),
)

@OptIn(ExperimentalContentDiveSpi::class)
public suspend fun SearchBackend.fuzzy(
    token: String,
    limit: Int = Int.MAX_VALUE,
): List<BackendTermCandidate> = fuzzyTerms(
    FuzzyTermRequest(
        normalizedQueryToken = token,
        trigrams = characterTrigrams(token),
        candidateLimit = limit,
    ),
)

@OptIn(ExperimentalContentDiveSpi::class)
public fun preparedProjection(
    id: String,
    scope: String,
    vararg fragments: Pair<String, String>,
    destination: DestinationRef = DestinationRef("event", 1, "{\"eventId\":\"$id\"}"),
    anchor: AnchorRef? = null,
): PreparedProjection {
    val itemId = SearchItemId(id)
    val itemScope = SearchScope(scope)
    return PreparedProjection(
        item = SearchItem(itemId, itemScope, id, destination),
        fragments = fragments.map { (fragmentId, text) ->
            SearchFragment(
                id = SearchFragmentId(fragmentId),
                itemId = itemId,
                scope = itemScope,
                text = text,
                anchor = anchor,
            )
        },
        chunks = fragments.map { (fragmentId, text) ->
            preparedChunk(fragmentId, text, sourceStart = 0, ordinal = 0, anchor = anchor)
        },
    )
}

@OptIn(ExperimentalContentDiveSpi::class)
public fun preparedChunkedProjection(
    id: String,
    scope: String,
    fragmentId: String,
    vararg chunkTexts: String,
    anchor: AnchorRef? = null,
): PreparedProjection {
    val itemId = SearchItemId(id)
    val itemScope = SearchScope(scope)
    val fullText = chunkTexts.joinToString(" ")
    val fragment = SearchFragment(
        id = SearchFragmentId(fragmentId),
        itemId = itemId,
        scope = itemScope,
        text = fullText,
        anchor = anchor,
    )
    var start = 0
    val chunks = chunkTexts.mapIndexed { ordinal, text ->
        preparedChunk(fragmentId, text, start, ordinal, anchor).also {
            start += text.length + 1
        }
    }
    return PreparedProjection(
        item = SearchItem(
            id = itemId,
            scope = itemScope,
            title = id,
            destination = DestinationRef("event", 1, "{\"eventId\":\"$id\"}"),
        ),
        fragments = listOf(fragment),
        chunks = chunks,
    )
}

public fun <T> runSuspendTest(block: suspend () -> T): T {
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
    check(completed.await(30, TimeUnit.SECONDS)) { "Suspending backend operation timed out" }
    return assertNotNull(outcome.get()).getOrThrow()
}

@OptIn(ExperimentalContentDiveSpi::class)
private fun preparedChunk(
    fragmentId: String,
    text: String,
    sourceStart: Int,
    ordinal: Int,
    anchor: AnchorRef?,
): PreparedTextChunk {
    val tokens = TOKEN.findAll(text).map { match ->
        PreparedToken(
            value = match.value.lowercase(),
            start = match.range.first,
            end = match.range.last + 1,
        )
    }.toList()
    return PreparedTextChunk(
        id = "$fragmentId:chunk:$ordinal:$sourceStart-${sourceStart + text.length}",
        sourceFragmentId = SearchFragmentId(fragmentId),
        originalText = text,
        normalizedText = tokens.joinToString(" ") { it.value },
        tokens = tokens,
        sourceStart = sourceStart,
        sourceEnd = sourceStart + text.length,
        ordinal = ordinal,
        anchor = anchor,
    )
}

@OptIn(ExperimentalContentDiveSpi::class)
private fun List<BackendCandidate>.itemIds(): List<String> = map { it.item.id.value }

private fun characterTrigrams(term: String): Set<String> {
    val characters = listOf("^") + term.map(Char::toString) + "$"
    return characters.windowed(3).mapTo(linkedSetOf()) { it.joinToString("") }
}

private val TOKEN = Regex("[A-Za-z0-9]+")
