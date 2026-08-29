package com.contentdive.backend.memory

import com.contentdive.api.SearchFragment
import com.contentdive.api.SearchBatchItemId
import com.contentdive.api.SearchItem
import com.contentdive.api.SearchItemId
import com.contentdive.api.SearchScope
import com.contentdive.spi.BackendCandidate
import com.contentdive.spi.BackendCandidateRequest
import com.contentdive.spi.BackendBatchWriteResult
import com.contentdive.spi.BackendCapabilities
import com.contentdive.spi.BackendTermCandidate
import com.contentdive.spi.BackendWriteResult
import com.contentdive.spi.ExperimentalContentDiveSpi
import com.contentdive.spi.FuzzyTermRequest
import com.contentdive.spi.PreparedProjection
import com.contentdive.spi.PreparedTextChunk
import com.contentdive.spi.SearchBackend

/**
 * Creates an isolated, lock-protected raw backend for engine or backend-contract development.
 *
 * This is an experimental implementation contract. Applications should use
 * [createMemoryContentDive], which does not require an SPI dependency or opt-in. Closing the raw
 * backend is idempotent and discards every indexed projection.
 */
@ExperimentalContentDiveSpi
public fun createMemorySearchBackend(): SearchBackend = MemorySearchBackend()

@OptIn(ExperimentalContentDiveSpi::class)
internal class MemorySearchBackend : SearchBackend {
    private val lock = Any()
    private val itemsByScopeAndId = mutableMapOf<ItemKey, SearchItem>()
    private val fragmentsByScopeAndId = mutableMapOf<FragmentKey, SearchFragment>()
    private val fragmentIdsByItem = mutableMapOf<ItemKey, MutableSet<FragmentKey>>()
    private val chunksByScopeAndId = mutableMapOf<ChunkKey, PreparedTextChunk>()
    private val chunkIdsByItem = mutableMapOf<ItemKey, MutableSet<ChunkKey>>()
    private val postingsByToken = mutableMapOf<String, MutableSet<ChunkKey>>()
    private val tokensByChunk = mutableMapOf<ChunkKey, Set<String>>()
    private val termUsageCount = mutableMapOf<String, Int>()
    private val termUsageByItem = mutableMapOf<String, MutableMap<ItemKey, Int>>()
    private val termsByTrigram = mutableMapOf<String, MutableSet<String>>()
    private var closed = false

    override val capabilities: BackendCapabilities = BackendCapabilities(
        supportsPrefixCandidates = true,
        supportsFuzzyCandidates = true,
    )

    override suspend fun replaceAll(
        projections: List<PreparedProjection>,
    ): BackendBatchWriteResult = synchronized(lock) {
        checkOpen()
        projections.forEach(::validatePreparedProjection)
        requireUniqueItems(projections.map { ItemKey(it.item.scope, it.item.id) })
        val sorted = projections.sortedWith(
            compareBy<PreparedProjection>({ it.item.scope.value }, { it.item.id.value }),
        )
        sorted.forEach(::replaceItemState)
        BackendBatchWriteResult(
            successfulItems = sorted.map { SearchBatchItemId(it.item.scope, it.item.id) },
            failedItems = emptyList(),
            affectedItems = sorted.size,
            affectedFragments = sorted.sumOf { it.fragments.size },
        )
    }

    override suspend fun removeAll(
        scope: SearchScope,
        itemIds: List<SearchItemId>,
    ): BackendBatchWriteResult = synchronized(lock) {
        checkOpen()
        require(scope.value.isNotBlank()) { "SearchScope must not be blank" }
        itemIds.forEach { require(it.value.isNotBlank()) { "SearchItemId must not be blank" } }
        val keys = itemIds.map { ItemKey(scope, it) }
        requireUniqueItems(keys)
        var fragmentCount = 0
        var itemCount = 0
        keys.sortedWith(itemKeyComparator).forEach { itemKey ->
            removeItemState(itemKey)?.let { removed ->
                itemCount += 1
                fragmentCount += removed.fragments
            }
        }
        BackendBatchWriteResult(
            successfulItems = keys.sortedWith(itemKeyComparator).map {
                SearchBatchItemId(it.scope, it.itemId)
            },
            failedItems = emptyList(),
            affectedItems = itemCount,
            affectedFragments = fragmentCount,
        )
    }

    override suspend fun clear(scope: SearchScope): BackendWriteResult = synchronized(lock) {
        checkOpen()
        val keys = itemsByScopeAndId.keys.filter { it.scope == scope }
        var fragmentCount = 0
        keys.forEach { itemKey ->
            fragmentCount += removeItemState(itemKey)?.fragments ?: 0
        }
        BackendWriteResult(
            affectedItems = keys.size,
            affectedFragments = fragmentCount,
        )
    }

    override suspend fun candidates(
        request: BackendCandidateRequest,
    ): List<BackendCandidate> = synchronized(lock) {
        checkOpen()
        val matchingKeys = linkedSetOf<ChunkKey>()
        request.tokens.forEach { queryToken ->
            postingsByToken[queryToken]?.let(matchingKeys::addAll)
            if (request.includePrefixes) {
                postingsByToken.forEach { (indexedToken, chunkKeys) ->
                    if (indexedToken != queryToken && indexedToken.startsWith(queryToken)) {
                        matchingKeys.addAll(chunkKeys)
                    }
                }
            }
        }

        matchingKeys.asSequence()
            .filter { request.scopes.isEmpty() || it.scope in request.scopes }
            .sortedWith(chunkKeyComparator)
            .mapNotNull { chunkKey ->
                val item = itemsByScopeAndId[ItemKey(chunkKey.scope, chunkKey.itemId)]
                    ?: return@mapNotNull null
                val chunk = chunksByScopeAndId[chunkKey] ?: return@mapNotNull null
                val fragment = fragmentsByScopeAndId[
                    FragmentKey(chunkKey.scope, chunkKey.itemId, chunk.sourceFragmentId.value)
                ] ?: return@mapNotNull null
                BackendCandidate(item, fragment, chunk)
            }
            .take(request.limit)
            .toList()
    }

    override suspend fun fuzzyTerms(
        request: FuzzyTermRequest,
    ): List<BackendTermCandidate> = synchronized(lock) {
        checkOpen()
        val overlapByTerm = mutableMapOf<String, Int>()
        request.trigrams.forEach { trigram ->
            termsByTrigram[trigram].orEmpty().forEach { term ->
                if (term != request.normalizedQueryToken) {
                    overlapByTerm[term] = (overlapByTerm[term] ?: 0) + 1
                }
            }
        }
        overlapByTerm.asSequence()
            .map { (term, overlap) ->
                BackendTermCandidate(
                    indexedTerm = term,
                    trigramOverlap = overlap,
                    documentFrequency = termUsageByItem[term]?.size,
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
        synchronized(lock) {
            if (closed) return
            itemsByScopeAndId.clear()
            fragmentsByScopeAndId.clear()
            fragmentIdsByItem.clear()
            chunksByScopeAndId.clear()
            chunkIdsByItem.clear()
            postingsByToken.clear()
            tokensByChunk.clear()
            termUsageCount.clear()
            termUsageByItem.clear()
            termsByTrigram.clear()
            closed = true
        }
    }

    private fun validatePreparedProjection(projection: PreparedProjection) {
        require(projection.fragments.isNotEmpty()) { "PreparedProjection fragments must not be empty" }
        require(projection.chunks.isNotEmpty()) { "PreparedProjection chunks must not be empty" }
        val fragmentsById = projection.fragments.associateBy { it.id }
        require(fragmentsById.size == projection.fragments.size) {
            "PreparedProjection fragment IDs must be unique"
        }
        projection.fragments.forEach { fragment ->
            require(fragment.itemId == projection.item.id) {
                "Prepared fragment belongs to another item"
            }
            require(fragment.scope == projection.item.scope) {
                "Prepared fragment belongs to another scope"
            }
        }

        require(projection.chunks.map { it.id }.toSet().size == projection.chunks.size) {
            "Prepared chunk IDs must be unique"
        }
        projection.chunks.forEach { chunk ->
            val source = requireNotNull(fragmentsById[chunk.sourceFragmentId]) {
                "Prepared chunk '${chunk.id}' has no source fragment"
            }
            require(chunk.ordinal >= 0) { "Prepared chunk ordinal must not be negative" }
            require(chunk.sourceStart >= 0 && chunk.sourceEnd > chunk.sourceStart) {
                "Prepared chunk source range must be non-empty"
            }
            require(chunk.sourceEnd <= source.text.length) {
                "Prepared chunk source range exceeds its fragment"
            }
            require(chunk.originalText == source.text.substring(chunk.sourceStart, chunk.sourceEnd)) {
                "Prepared chunk original text does not match its source range"
            }
            require(chunk.anchor == source.anchor) { "Prepared chunk did not inherit its source anchor" }
            require(chunk.normalizedText.isNotBlank()) { "Normalized chunk text must not be blank" }
            require(chunk.tokens.isNotEmpty()) { "Prepared chunk tokens must not be empty" }
            chunk.tokens.forEach { token ->
                require(token.value.isNotBlank()) { "Prepared token must not be blank" }
                require(token.start >= 0 && token.end > token.start) {
                    "Prepared token range must be non-empty"
                }
                require(token.end <= chunk.originalText.length) {
                    "Prepared token range exceeds its chunk"
                }
            }
        }
        require(projection.chunks.map { it.sourceFragmentId }.toSet() == fragmentsById.keys) {
            "Every prepared fragment must have at least one chunk"
        }
    }

    private fun replaceItemState(projection: PreparedProjection) {
        val itemKey = ItemKey(projection.item.scope, projection.item.id)
        removeItemState(itemKey)

        itemsByScopeAndId[itemKey] = projection.item
        fragmentIdsByItem[itemKey] = projection.fragments.mapTo(linkedSetOf()) { fragment ->
            FragmentKey(
                scope = projection.item.scope,
                itemId = projection.item.id,
                fragmentId = fragment.id.value,
            ).also { key -> fragmentsByScopeAndId[key] = fragment }
        }
        chunkIdsByItem[itemKey] = projection.chunks.mapTo(linkedSetOf()) { source ->
            val prepared = source.copy(tokens = source.tokens.map { it.copy() })
            ChunkKey(
                scope = projection.item.scope,
                itemId = projection.item.id,
                chunkId = prepared.id,
            ).also { chunkKey ->
                chunksByScopeAndId[chunkKey] = prepared
                val tokens = prepared.tokens.mapTo(linkedSetOf()) { it.value }
                tokensByChunk[chunkKey] = tokens
                tokens.forEach { token ->
                    postingsByToken.getOrPut(token, ::linkedSetOf).add(chunkKey)
                    addTermUsage(token, itemKey)
                }
            }
        }
    }

    private fun requireUniqueItems(itemKeys: List<ItemKey>) {
        val duplicate = itemKeys.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }
        require(duplicate == null) {
            "Duplicate SearchItemId '${duplicate?.key?.itemId?.value}' in scope " +
                "'${duplicate?.key?.scope?.value}'"
        }
    }

    private fun removeItemState(itemKey: ItemKey): RemovalCounts? {
        if (itemsByScopeAndId.remove(itemKey) == null) return null
        val fragmentKeys = fragmentIdsByItem.remove(itemKey).orEmpty()
        fragmentKeys.forEach(fragmentsByScopeAndId::remove)
        val chunkKeys = chunkIdsByItem.remove(itemKey).orEmpty()
        chunkKeys.forEach { chunkKey ->
            chunksByScopeAndId.remove(chunkKey)
            tokensByChunk.remove(chunkKey).orEmpty().forEach { token ->
                postingsByToken[token]?.let { postings ->
                    postings.remove(chunkKey)
                    if (postings.isEmpty()) postingsByToken.remove(token)
                }
                removeTermUsage(token, itemKey)
            }
        }
        return RemovalCounts(fragmentKeys.size, chunkKeys.size)
    }

    private fun checkOpen() {
        check(!closed) { "MemorySearchBackend is closed" }
    }

    private fun addTermUsage(term: String, itemKey: ItemKey) {
        val previous = termUsageCount[term] ?: 0
        termUsageCount[term] = previous + 1
        val itemCounts = termUsageByItem.getOrPut(term, ::mutableMapOf)
        itemCounts[itemKey] = (itemCounts[itemKey] ?: 0) + 1
        if (previous == 0) {
            characterTrigrams(term).forEach { trigram ->
                termsByTrigram.getOrPut(trigram, ::linkedSetOf).add(term)
            }
        }
    }

    private fun removeTermUsage(term: String, itemKey: ItemKey) {
        val previous = checkNotNull(termUsageCount[term]) {
            "Missing usage count for indexed term '$term'"
        }
        val itemCounts = checkNotNull(termUsageByItem[term]) {
            "Missing item usage for indexed term '$term'"
        }
        val previousItemCount = checkNotNull(itemCounts[itemKey]) {
            "Missing item usage for indexed term '$term'"
        }
        if (previousItemCount > 1) {
            itemCounts[itemKey] = previousItemCount - 1
        } else {
            itemCounts.remove(itemKey)
        }
        if (previous > 1) {
            termUsageCount[term] = previous - 1
            return
        }
        termUsageCount.remove(term)
        termUsageByItem.remove(term)
        characterTrigrams(term).forEach { trigram ->
            termsByTrigram[trigram]?.let { terms ->
                terms.remove(term)
                if (terms.isEmpty()) termsByTrigram.remove(trigram)
            }
        }
    }

    private fun characterTrigrams(term: String): Set<String> {
        val characters = buildList {
            add("^")
            var offset = 0
            while (offset < term.length) {
                val next = offset + Character.charCount(term.codePointAt(offset))
                add(term.substring(offset, next))
                offset = next
            }
            add("$")
        }
        return characters.windowed(3).mapTo(linkedSetOf()) { it.joinToString("") }
    }

    private data class ItemKey(
        val scope: SearchScope,
        val itemId: SearchItemId,
    )

    private data class FragmentKey(
        val scope: SearchScope,
        val itemId: SearchItemId,
        val fragmentId: String,
    )

    private data class ChunkKey(
        val scope: SearchScope,
        val itemId: SearchItemId,
        val chunkId: String,
    )

    private data class RemovalCounts(
        val fragments: Int,
        val chunks: Int,
    )

    private companion object {
        val itemKeyComparator = compareBy<ItemKey>(
            { it.scope.value },
            { it.itemId.value },
        )
        val chunkKeyComparator = compareBy<ChunkKey>(
            { it.scope.value },
            { it.itemId.value },
            ChunkKey::chunkId,
        )
    }
}
