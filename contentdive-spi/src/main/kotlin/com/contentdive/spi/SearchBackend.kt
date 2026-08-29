package com.contentdive.spi

import com.contentdive.api.SearchItemId
import com.contentdive.api.SearchScope

/**
 * Experimental storage, item-atomic mutation, and candidate-narrowing boundary used by the engine.
 *
 * Implementations must be safe for concurrent search and indexing, keep scopes isolated, and make
 * [close] idempotent. They index complete [PreparedProjection] snapshots but never own final token
 * matching, fuzzy edit distance, ranking, grouping, or snippets. Cancellation must propagate and
 * must not leave an individual item partially replaced.
 *
 * Operations after [close] must fail consistently. Backend/storage failures are thrown with their
 * original cause so the engine can wrap them as `ContentDiveException`; only individual valid-item
 * failures belong in [BackendBatchWriteResult].
 */
@ExperimentalContentDiveSpi
public interface SearchBackend : AutoCloseable {
    /** Candidate-narrowing features supported by this backend instance. */
    public val capabilities: BackendCapabilities

    /**
     * Replaces complete prepared projections in one backend batch.
     *
     * Replacement is atomic for each scoped item and removes all of that item's previous fragments,
     * chunks, postings, and fuzzy terms. The whole batch need not be globally transactional.
     * Requests are already validated, prepared, and deterministically ordered by the engine.
     */
    public suspend fun replaceAll(projections: List<PreparedProjection>): BackendBatchWriteResult

    /**
     * Atomically replaces one item through the same implementation path as [replaceAll].
     *
     * This default adapter throws if the backend reports an individual failure.
     */
    public suspend fun replace(projection: PreparedProjection): BackendWriteResult {
        val result = replaceAll(listOf(projection))
        check(result.failedItems.isEmpty()) {
            result.failedItems.joinToString(
                prefix = "Backend could not replace projection: ",
                transform = BackendBatchFailure::message,
            )
        }
        return BackendWriteResult(
            affectedItems = result.affectedItems,
            affectedFragments = result.affectedFragments,
        )
    }

    /**
     * Removes multiple complete item snapshots from one [scope].
     *
     * Missing IDs are successful no-ops. Each removal is atomic and must remove all postings and
     * fuzzy-term usage for that item; other scopes are unaffected.
     */
    public suspend fun removeAll(
        scope: SearchScope,
        itemIds: List<SearchItemId>,
    ): BackendBatchWriteResult

    /**
     * Removes one item through the same implementation path as [removeAll].
     *
     * This default adapter throws if the backend reports an individual failure.
     */
    public suspend fun remove(scope: SearchScope, itemId: SearchItemId): BackendWriteResult {
        val result = removeAll(scope, listOf(itemId))
        check(result.failedItems.isEmpty()) {
            result.failedItems.joinToString(
                prefix = "Backend could not remove projection: ",
                transform = BackendBatchFailure::message,
            )
        }
        return BackendWriteResult(
            affectedItems = result.affectedItems,
            affectedFragments = result.affectedFragments,
        )
    }

    /**
     * Removes all items, logical fragments, chunks, postings, and fuzzy terms in [scope].
     *
     * Clearing an empty scope is a successful no-op and must not affect any other scope.
     */
    public suspend fun clear(scope: SearchScope): BackendWriteResult

    /**
     * Retrieves deterministic candidate chunks narrowed by tokens and scope.
     *
     * The engine remains responsible for final acceptance, grouping, snippets, and ranking.
     */
    public suspend fun candidates(request: BackendCandidateRequest): List<BackendCandidate>

    /**
     * Retrieves plausible normalized indexed terms for a trigram request.
     *
     * The result must honor the request limit; the engine performs final Damerau–Levenshtein
     * filtering and relevance penalties.
     */
    public suspend fun fuzzyTerms(request: FuzzyTermRequest): List<BackendTermCandidate>

    /**
     * Releases backend-owned resources.
     *
     * Closing is idempotent. Persistent implementations retain indexed data; temporary
     * implementations may discard it. Subsequent operations must fail consistently.
     */
    public override fun close()
}
