package com.contentdive.engine

import com.contentdive.api.ContentDive
import com.contentdive.api.ContentDiveConfiguration
import com.contentdive.api.ContentDiveException
import com.contentdive.api.ContentDiveLifecycleException
import com.contentdive.api.SearchBatchFailure
import com.contentdive.api.SearchBatchItemId
import com.contentdive.api.SearchBatchResult
import com.contentdive.api.SearchFragmentKind
import com.contentdive.api.SearchItem
import com.contentdive.api.SearchItemId
import com.contentdive.api.SearchMatch
import com.contentdive.api.SearchProjection
import com.contentdive.api.SearchQuery
import com.contentdive.api.SearchResult
import com.contentdive.api.SearchScope
import com.contentdive.spi.BackendCandidate
import com.contentdive.spi.BackendCandidateRequest
import com.contentdive.spi.BackendBatchWriteResult
import com.contentdive.spi.BackendCapabilities
import com.contentdive.spi.ExperimentalContentDiveSpi
import com.contentdive.spi.FuzzyTermRequest
import com.contentdive.spi.PreparedProjection
import com.contentdive.spi.PreparedToken
import com.contentdive.spi.SearchBackend
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.cancellation.CancellationException

@OptIn(ExperimentalContentDiveSpi::class)
internal class DefaultContentDive(
    private val backend: SearchBackend,
    private val configuration: ContentDiveConfiguration,
) : ContentDive {
    private val lifecycleLock = ReentrantLock()
    private var activeOperations = 0
    private var closed = false
    private var backendClosed = false

    override suspend fun replace(projection: SearchProjection) {
        withOperation {
            replaceAllOpen(listOf(projection)).requireSuccess("replace")
        }
    }

    override suspend fun replaceAll(
        projections: Collection<SearchProjection>,
    ): SearchBatchResult = withOperation {
        replaceAllOpen(projections)
    }

    override suspend fun remove(scope: SearchScope, itemId: SearchItemId) {
        withOperation {
            removeAllOpen(scope, listOf(itemId)).requireSuccess("remove")
        }
    }

    override suspend fun removeAll(
        scope: SearchScope,
        itemIds: Collection<SearchItemId>,
    ): SearchBatchResult = withOperation {
        removeAllOpen(scope, itemIds)
    }

    override suspend fun clear(scope: SearchScope) {
        withOperation {
            require(scope.value.isNotBlank()) { "SearchScope must not be blank" }
            backendOperation("clear scope") { backend.clear(scope) }
        }
    }

    override suspend fun search(query: SearchQuery): SearchResult = withOperation {
        searchOpen(query)
    }

    override fun close() {
        val closeBackend = lifecycleLock.withLock {
            if (closed) return
            closed = true
            if (activeOperations == 0) {
                backendClosed = true
                true
            } else {
                false
            }
        }
        if (closeBackend) closeBackend()
    }

    private suspend fun replaceAllOpen(
        projections: Collection<SearchProjection>,
    ): SearchBatchResult {
        val prepared = prepareBatch(projections)
        val expectedItems = prepared.map { it.item.batchId() }
        return backendOperation("replace items") {
            backend.replaceAll(prepared).toPublicResult(expectedItems)
        }
    }

    private suspend fun removeAllOpen(
        scope: SearchScope,
        itemIds: Collection<SearchItemId>,
    ): SearchBatchResult {
        require(scope.value.isNotBlank()) { "SearchScope must not be blank" }
        val snapshot = itemIds.toList()
        snapshot.forEach { itemId ->
            require(itemId.value.isNotBlank()) { "SearchItemId must not be blank" }
        }
        val duplicate = snapshot.groupingBy(SearchItemId::value)
            .eachCount()
            .entries
            .firstOrNull { it.value > 1 }
        require(duplicate == null) {
            "Duplicate SearchItemId '${duplicate?.key}' in scope '${scope.value}'"
        }
        val sortedIds = snapshot.sortedBy(SearchItemId::value)
        val expectedItems = sortedIds.map { SearchBatchItemId(scope, it) }
        return backendOperation("remove items") {
            backend.removeAll(scope, sortedIds).toPublicResult(expectedItems)
        }
    }

    private fun prepareBatch(
        projections: Collection<SearchProjection>,
    ): List<PreparedProjection> {
        val snapshot = projections.toList()
        snapshot.forEach(::validateProjection)
        val duplicate = snapshot
            .groupingBy { it.item.batchId() }
            .eachCount()
            .entries
            .firstOrNull { it.value > 1 }
        require(duplicate == null) {
            val item = duplicate?.key
            "Duplicate SearchItemId '${item?.itemId?.value}' in scope '${item?.scope?.value}'"
        }
        return snapshot
            .sortedWith(
                compareBy<SearchProjection>({ it.item.scope.value }, { it.item.id.value }),
            )
            .map { projection ->
                PreparedProjection(
                    item = projection.item,
                    fragments = projection.fragments.toList(),
                    chunks = projection.fragments.flatMap(TextChunker::prepare),
                )
            }
    }

    private fun BackendBatchWriteResult.toPublicResult(
        expectedItems: List<SearchBatchItemId>,
    ): SearchBatchResult {
        val expected = expectedItems.toSet()
        val successful = successfulItems.toSet()
        val failuresByItem = failedItems.associateBy { it.item }
        check(successful.size == successfulItems.size) {
            "Backend returned duplicate successful batch items"
        }
        check(failuresByItem.size == failedItems.size) {
            "Backend returned duplicate failed batch items"
        }
        check(successful.intersect(failuresByItem.keys).isEmpty()) {
            "Backend returned both success and failure for a batch item"
        }
        check(successful + failuresByItem.keys == expected) {
            "Backend batch outcomes did not match the requested items"
        }
        return SearchBatchResult(
            successfulItems = successfulItems.sortedWith(batchItemComparator),
            failedItems = failedItems
                .map { SearchBatchFailure(it.item, it.message) }
                .sortedWith(compareBy(batchFailureItemComparator) { it.item }),
        )
    }

    private suspend fun searchOpen(query: SearchQuery): SearchResult {
        val resultLimit = query.limit ?: configuration.defaultResultLimit
        require(resultLimit <= configuration.maximumResultLimit) {
            "SearchQuery limit $resultLimit exceeds configured maximum " +
                configuration.maximumResultLimit
        }
        val normalizedQuery = LexicalNormalizer.normalize(query.text)
        if (normalizedQuery.tokens.isEmpty()) return SearchResult(emptyList())

        val queryTokens = normalizedQuery.tokens.map(NormalizedToken::value).distinct()
        val capabilities = backendCapabilities()
        val lexicalCandidates = backendOperation("retrieve search candidates") {
            backend.candidates(
                BackendCandidateRequest(
                    tokens = queryTokens,
                    scopes = query.scopes,
                    includePrefixes = capabilities.supportsPrefixCandidates,
                    limit = Int.MAX_VALUE,
                ),
            )
        }
        val fuzzyTermsByQuery = resolveFuzzyTerms(queryTokens)
        val fuzzyIndexedTerms = fuzzyTermsByQuery.values
            .flatten()
            .map(FuzzyTermMatch::indexedTerm)
            .distinct()
        val fuzzyCandidates = if (fuzzyIndexedTerms.isEmpty()) {
            emptyList()
        } else {
            backendOperation("retrieve fuzzy search candidates") {
                backend.candidates(
                    BackendCandidateRequest(
                        tokens = fuzzyIndexedTerms,
                        scopes = query.scopes,
                        includePrefixes = false,
                        limit = Int.MAX_VALUE,
                    ),
                )
            }
        }
        val candidates = (lexicalCandidates + fuzzyCandidates).distinctBy { candidate ->
            CandidateKey(
                scope = candidate.item.scope.value,
                itemId = candidate.item.id.value,
                chunkId = candidate.chunk.id,
            )
        }
        return SearchResult(
            matches = rankAndGroup(candidates, queryTokens, fuzzyTermsByQuery).take(resultLimit),
        )
    }

    private suspend fun <T> withOperation(block: suspend () -> T): T {
        lifecycleLock.withLock {
            if (closed) throw ContentDiveLifecycleException()
            activeOperations += 1
        }
        var operationFailure: Throwable? = null
        return try {
            block()
        } catch (error: Throwable) {
            operationFailure = error
            throw error
        } finally {
            val closeBackend = lifecycleLock.withLock {
                activeOperations -= 1
                if (closed && activeOperations == 0 && !backendClosed) {
                    backendClosed = true
                    true
                } else {
                    false
                }
            }
            if (closeBackend) {
                try {
                    closeBackend()
                } catch (closeFailure: Throwable) {
                    operationFailure?.addSuppressed(closeFailure) ?: throw closeFailure
                }
            }
        }
    }

    private suspend fun <T> backendOperation(
        operation: String,
        block: suspend () -> T,
    ): T = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: ContentDiveException) {
        throw error
    } catch (error: Exception) {
        throw ContentDiveException("ContentDive could not $operation", error)
    }

    private fun backendCapabilities(): BackendCapabilities = try {
        backend.capabilities
    } catch (error: ContentDiveException) {
        throw error
    } catch (error: Exception) {
        throw ContentDiveException("ContentDive could not inspect backend capabilities", error)
    }

    private fun closeBackend() {
        try {
            backend.close()
        } catch (error: ContentDiveException) {
            throw error
        } catch (error: Exception) {
            throw ContentDiveException("ContentDive could not close its backend", error)
        }
    }

    private suspend fun resolveFuzzyTerms(
        queryTokens: List<String>,
    ): Map<String, List<FuzzyTermMatch>> {
        if (!configuration.fuzzySearchEnabled || !backendCapabilities().supportsFuzzyCandidates) {
            return emptyMap()
        }
        return buildMap {
            queryTokens.forEach { queryToken ->
                if (!FuzzyMatcher.isEnabled(queryToken)) return@forEach
                val terms = backendOperation("retrieve fuzzy terms") {
                    backend.fuzzyTerms(
                        FuzzyTermRequest(
                            normalizedQueryToken = queryToken,
                            trigrams = FuzzyMatcher.characterTrigrams(queryToken),
                            candidateLimit = FuzzyMatcher.CANDIDATE_LIMIT,
                        ),
                    )
                }
                FuzzyMatcher.filter(queryToken, terms)
                    .take(FuzzyMatcher.CANDIDATE_LIMIT)
                    .takeIf { it.isNotEmpty() }
                    ?.let { put(queryToken, it) }
            }
        }
    }

    private fun validateProjection(projection: SearchProjection) {
        val item = projection.item
        require(item.id.value.isNotBlank()) { "SearchItemId must not be blank" }
        require(item.scope.value.isNotBlank()) { "SearchScope must not be blank" }
        require(item.title.isNotBlank()) { "SearchItem title must not be blank" }
        require(item.destination.type.isNotBlank()) { "Destination type must not be blank" }
        require(item.destination.version > 0) { "Destination version must be positive" }
        require(item.destination.payload.isNotBlank()) { "Destination payload must not be blank" }
        require(projection.fragments.isNotEmpty()) {
            "SearchProjection must contain at least one fragment"
        }

        val fragmentIds = HashSet<String>()
        projection.fragments.forEach { fragment ->
            require(fragment.id.value.isNotBlank()) { "SearchFragmentId must not be blank" }
            require(fragmentIds.add(fragment.id.value)) {
                "Duplicate SearchFragmentId '${fragment.id.value}'"
            }
            require(fragment.itemId == item.id) {
                "Fragment '${fragment.id.value}' belongs to another item"
            }
            require(fragment.scope == item.scope) {
                "Fragment '${fragment.id.value}' belongs to another scope"
            }
            require(fragment.text.isNotBlank()) {
                "Fragment '${fragment.id.value}' text must not be blank"
            }
            require(fragment.weight.isFinite() && fragment.weight > 0.0) {
                "Fragment '${fragment.id.value}' weight must be finite and positive"
            }
            fragment.anchor?.let { anchor ->
                require(anchor.type.isNotBlank()) { "Anchor type must not be blank" }
                require(anchor.version > 0) { "Anchor version must be positive" }
                require(anchor.payload.isNotBlank()) { "Anchor payload must not be blank" }
            }
        }
    }

    private fun rankAndGroup(
        candidates: List<BackendCandidate>,
        queryTokens: List<String>,
        fuzzyTermsByQuery: Map<String, List<FuzzyTermMatch>>,
    ): List<SearchMatch> {
        val ranked = candidates.mapNotNull { candidate ->
            rank(candidate, queryTokens, fuzzyTermsByQuery)
        }
        return ranked
            .groupBy { ItemKey(it.candidate.item) }
            .map { (_, itemCandidates) -> createMatch(itemCandidates, queryTokens) }
            .sortedWith(
                compareByDescending<SearchMatch>(SearchMatch::score)
                    .thenBy { it.item.id.value }
                    .thenBy { it.item.scope.value },
            )
    }

    private fun rank(
        candidate: BackendCandidate,
        queryTokens: List<String>,
        fuzzyTermsByQuery: Map<String, List<FuzzyTermMatch>>,
    ): RankedChunk? {
        val chunkTokens = candidate.chunk.tokens
        val tokenValues = chunkTokens.map(PreparedToken::value)
        val queryMatches = queryTokens.mapNotNull { queryToken ->
            classifyQueryToken(queryToken, tokenValues, fuzzyTermsByQuery[queryToken].orEmpty())
        }
        if (queryMatches.isEmpty()) return null
        val matchesByQuery = queryMatches.associateBy(QueryTokenMatch::queryToken)
        val exactTokenCount = queryMatches.count { it.kind == MatchKind.EXACT }
        val prefixTokenCount = queryMatches.count { it.kind == MatchKind.PREFIX }
        val fuzzyTokenCount = queryMatches.count { it.kind == MatchKind.FUZZY }
        val fuzzyEditDistance = queryMatches.sumOf(QueryTokenMatch::editDistance)
        val matchedTokens = matchesByQuery.keys
        val phrase = containsPhrase(tokenValues, queryTokens, matchesByQuery)
        val matchWindow = checkNotNull(minimumMatchWindow(chunkTokens, matchesByQuery))
        val fragment = candidate.sourceFragment
        val score = matchedTokens.size * MATCHED_TOKEN_SCORE +
            exactTokenCount * EXACT_TOKEN_SCORE +
            prefixTokenCount * PREFIX_TOKEN_SCORE +
            fuzzyTokenCount * FUZZY_TOKEN_SCORE -
            fuzzyEditDistance * FUZZY_EDIT_DISTANCE_PENALTY +
            kindScore(fragment.kind) +
            (if (phrase) PHRASE_SCORE else 0.0) +
            proximityScore(matchWindow.tokenCount) +
            boundedWeightScore(fragment.weight)

        return RankedChunk(
            candidate = candidate,
            matchedTokens = matchedTokens,
            exactTokenCount = exactTokenCount,
            prefixTokenCount = prefixTokenCount,
            fuzzyTokenCount = fuzzyTokenCount,
            fuzzyEditDistance = fuzzyEditDistance,
            phrase = phrase,
            matchWindow = matchWindow,
            fragmentScore = score,
        )
    }

    private fun createMatch(
        candidates: List<RankedChunk>,
        queryTokens: List<String>,
    ): SearchMatch {
        val rankedChunks = candidates.sortedWith(rankedChunkComparator)
        val best = rankedChunks.first()
        val itemMatchedTokens = rankedChunks.flatMapTo(linkedSetOf()) { it.matchedTokens }
        val allTokensMatched = itemMatchedTokens.containsAll(queryTokens)
        val groupedScore = best.fragmentScore +
            itemMatchedTokens.size * ITEM_COVERAGE_SCORE +
            (if (allTokensMatched) ALL_TOKENS_SCORE else 0.0)
        return SearchMatch(
            item = best.candidate.item,
            bestFragment = best.candidate.sourceFragment,
            matchedFragments = rankedChunks
                .map { it.candidate.sourceFragment }
                .distinctBy { it.id },
            score = groupedScore,
            snippet = createSnippet(best),
        )
    }

    private fun classifyQueryToken(
        queryToken: String,
        indexedTokens: List<String>,
        fuzzyTerms: List<FuzzyTermMatch>,
    ): QueryTokenMatch? {
        if (indexedTokens.any { it == queryToken }) {
            return QueryTokenMatch(queryToken, MatchKind.EXACT, setOf(queryToken), editDistance = 0)
        }
        if (indexedTokens.any { it.startsWith(queryToken) }) {
            return QueryTokenMatch(queryToken, MatchKind.PREFIX, emptySet(), editDistance = 0)
        }
        val presentFuzzyTerms = fuzzyTerms.filter { it.indexedTerm in indexedTokens }
        val bestDistance = presentFuzzyTerms.minOfOrNull(FuzzyTermMatch::editDistance) ?: return null
        return QueryTokenMatch(
            queryToken = queryToken,
            kind = MatchKind.FUZZY,
            indexedTerms = presentFuzzyTerms.asSequence()
                .filter { it.editDistance == bestDistance }
                .map(FuzzyTermMatch::indexedTerm)
                .toCollection(linkedSetOf()),
            editDistance = bestDistance,
        )
    }

    private fun containsPhrase(
        chunkTokens: List<String>,
        queryTokens: List<String>,
        matchesByQuery: Map<String, QueryTokenMatch>,
    ): Boolean {
        if (queryTokens.size < 2 || queryTokens.size > chunkTokens.size) return false
        if (!matchesByQuery.keys.containsAll(queryTokens)) return false
        return chunkTokens.windowed(queryTokens.size).any { window ->
            window.zip(queryTokens).all { (indexedToken, queryToken) ->
                checkNotNull(matchesByQuery[queryToken]).matches(indexedToken)
            }
        }
    }

    private fun minimumMatchWindow(
        chunkTokens: List<PreparedToken>,
        matchesByQuery: Map<String, QueryTokenMatch>,
    ): MatchWindow? {
        var best: MatchWindow? = null
        chunkTokens.indices.forEach { start ->
            val found = HashSet<String>()
            for (end in start until chunkTokens.size) {
                val token = chunkTokens[end].value
                matchesByQuery.values.forEach { match ->
                    if (match.matches(token)) found += match.queryToken
                }
                if (found.containsAll(matchesByQuery.keys)) {
                    val candidate = MatchWindow(start, end)
                    if (best == null || candidate.tokenCount < checkNotNull(best).tokenCount) {
                        best = candidate
                    }
                    break
                }
            }
        }
        return best
    }

    private fun kindScore(kind: SearchFragmentKind): Double = when (kind) {
        SearchFragmentKind.TITLE -> TITLE_SCORE
        SearchFragmentKind.HEADING -> HEADING_SCORE
        SearchFragmentKind.BODY -> 0.0
    }

    private fun proximityScore(span: Int): Double =
        (PROXIMITY_SCORE - span).coerceAtLeast(0.0)

    private fun boundedWeightScore(weight: Double): Double =
        weight / (1.0 + weight) * WEIGHT_SCORE

    private fun createSnippet(best: RankedChunk): String {
        val source = best.candidate.sourceFragment.text
        val chunk = best.candidate.chunk
        val firstToken = chunk.tokens[best.matchWindow.startToken]
        val lastToken = chunk.tokens[best.matchWindow.endToken]
        val matchStart = chunk.sourceStart + firstToken.start
        val matchEnd = chunk.sourceStart + lastToken.end
        val matchLength = matchEnd - matchStart
        val availableContext = (MAX_SNIPPET_LENGTH - matchLength).coerceAtLeast(0)
        val contextBefore = availableContext / 2
        val contextAfter = availableContext - contextBefore
        var snippetStart = (matchStart - contextBefore).coerceAtLeast(0)
        var snippetEnd = (matchEnd + contextAfter).coerceAtMost(source.length)

        if (snippetStart > 0) {
            while (snippetStart < matchStart && !source[snippetStart].isWhitespace()) snippetStart++
            while (snippetStart < matchStart && source[snippetStart].isWhitespace()) snippetStart++
        }
        if (snippetEnd < source.length) {
            while (snippetEnd > matchEnd && !source[snippetEnd - 1].isWhitespace()) snippetEnd--
            while (snippetEnd > matchEnd && source[snippetEnd - 1].isWhitespace()) snippetEnd--
        }

        return buildString {
            if (snippetStart > 0) append("… ")
            append(source.substring(snippetStart, snippetEnd))
            if (snippetEnd < source.length) append(" …")
        }
    }

    private data class ItemKey(
        val scope: String,
        val id: String,
    ) {
        constructor(item: SearchItem) : this(item.scope.value, item.id.value)
    }

    private data class CandidateKey(
        val scope: String,
        val itemId: String,
        val chunkId: String,
    )

    private data class QueryTokenMatch(
        val queryToken: String,
        val kind: MatchKind,
        val indexedTerms: Set<String>,
        val editDistance: Int,
    ) {
        fun matches(indexedToken: String): Boolean = when (kind) {
            MatchKind.EXACT -> indexedToken == queryToken
            MatchKind.PREFIX -> indexedToken.startsWith(queryToken)
            MatchKind.FUZZY -> indexedToken in indexedTerms
        }
    }

    private enum class MatchKind {
        EXACT,
        PREFIX,
        FUZZY,
    }

    private data class RankedChunk(
        val candidate: BackendCandidate,
        val matchedTokens: Set<String>,
        val exactTokenCount: Int,
        val prefixTokenCount: Int,
        val fuzzyTokenCount: Int,
        val fuzzyEditDistance: Int,
        val phrase: Boolean,
        val matchWindow: MatchWindow,
        val fragmentScore: Double,
    )

    private data class MatchWindow(
        val startToken: Int,
        val endToken: Int,
    ) {
        val tokenCount: Int = endToken - startToken + 1
    }

    private companion object {
        const val MATCHED_TOKEN_SCORE = 1_000_000.0
        const val EXACT_TOKEN_SCORE = 200_000.0
        const val PREFIX_TOKEN_SCORE = 100_000.0
        const val FUZZY_TOKEN_SCORE = 10_000.0
        const val FUZZY_EDIT_DISTANCE_PENALTY = 1_000.0
        const val TITLE_SCORE = 10_000.0
        const val HEADING_SCORE = 5_000.0
        const val PHRASE_SCORE = 2_000.0
        const val PROXIMITY_SCORE = 1_000.0
        const val WEIGHT_SCORE = 100.0
        const val ITEM_COVERAGE_SCORE = 100_000_000.0
        const val ALL_TOKENS_SCORE = 1_000_000_000_000.0
        const val MAX_SNIPPET_LENGTH = 240

        val batchItemComparator = compareBy<SearchBatchItemId>(
            { it.scope.value },
            { it.itemId.value },
        )
        val batchFailureItemComparator = compareBy<SearchBatchItemId>(
            { it.scope.value },
            { it.itemId.value },
        )

        val rankedChunkComparator =
            compareByDescending<RankedChunk> { it.matchedTokens.size }
                .thenByDescending(RankedChunk::exactTokenCount)
                .thenByDescending(RankedChunk::prefixTokenCount)
                .thenByDescending(RankedChunk::fuzzyTokenCount)
                .thenBy(RankedChunk::fuzzyEditDistance)
                .thenByDescending { kindRank(it.candidate.sourceFragment.kind) }
                .thenByDescending(RankedChunk::phrase)
                .thenBy { it.matchWindow.tokenCount }
                .thenByDescending { it.candidate.sourceFragment.weight }
                .thenBy { it.candidate.sourceFragment.id.value }
                .thenBy { it.candidate.chunk.ordinal }
                .thenBy { it.candidate.chunk.sourceStart }
                .thenBy { it.candidate.chunk.id }

        fun kindRank(kind: SearchFragmentKind): Int = when (kind) {
            SearchFragmentKind.TITLE -> 3
            SearchFragmentKind.HEADING -> 2
            SearchFragmentKind.BODY -> 1
        }
    }
}

private fun SearchItem.batchId(): SearchBatchItemId = SearchBatchItemId(scope, id)

private fun SearchBatchResult.requireSuccess(operation: String) {
    check(isSuccess) {
        failedItems.joinToString(
            prefix = "ContentDive could not $operation item: ",
        ) { failure ->
            "${failure.item.scope.value}/${failure.item.itemId.value}: ${failure.message}"
        }
    }
}
