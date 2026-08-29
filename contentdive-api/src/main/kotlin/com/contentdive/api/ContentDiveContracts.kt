package com.contentdive.api

/**
 * Converts an application-owned value into the complete derived snapshot ContentDive indexes.
 *
 * A projector does not store data and should produce the same [SearchProjection] for equivalent
 * input. Search results are navigation metadata, not a replacement for reloading authoritative
 * domain data from the application's repository.
 *
 * @param T application or domain type accepted by this projector.
 */
public fun interface SearchProjector<T> {
    /**
     * Creates one complete searchable snapshot of [value].
     *
     * The returned [SearchProjection.item] and every [SearchProjection.fragments] entry must use
     * the same [SearchItem.id] and [SearchItem.scope].
     */
    public fun project(value: T): SearchProjection
}

/**
 * Mutates ContentDive's derived index.
 *
 * Implementations returned by the standard backend factories are safe to call concurrently with
 * [ContentSearcher.search]. Cancellation is propagated and never reported as an empty result or
 * batch failure. Invalid application data fails before backend mutation whenever validation can
 * be completed up front.
 */
public interface ContentIndexer {
    /**
     * Atomically replaces the complete indexed representation of one item.
     *
     * Any fragments and internal chunks stored for the same [SearchItem.scope] and [SearchItem.id]
     * are removed as part of that item-level replacement. An item in another scope is unaffected.
     *
     * @throws IllegalArgumentException if the projection or its identifiers are invalid.
     * @throws ContentDiveLifecycleException if the owning [ContentDive] is closed.
     * @throws ContentDiveException if preparation reaches a backend or storage failure.
     */
    public suspend fun replace(projection: SearchProjection)

    /**
     * Prepares and replaces a collection of complete item projections as one backend batch.
     *
     * Each item replacement is atomic, but the complete batch is not guaranteed to be globally
     * transactional. The complete collection is validated and prepared before backend mutation;
     * duplicate item IDs in the same scope reject the batch without changing the index. A valid
     * item-level failure is returned in [SearchBatchResult.failedItems] and does not hide successes.
     *
     * @return deterministic per-item outcomes, independent of input order.
     * @throws IllegalArgumentException if any projection is invalid or a scoped ID is duplicated.
     * @throws ContentDiveLifecycleException if the owning [ContentDive] is closed.
     * @throws ContentDiveException for a batch-level backend or storage failure.
     */
    public suspend fun replaceAll(projections: Collection<SearchProjection>): SearchBatchResult

    /**
     * Removes one item and all of its fragments and internal chunks from [scope].
     *
     * Removing a missing item succeeds as a no-op; the same ID in another scope is unaffected.
     *
     * @throws ContentDiveLifecycleException if the owning [ContentDive] is closed.
     * @throws ContentDiveException if the backend cannot complete the removal.
     */
    public suspend fun remove(scope: SearchScope, itemId: SearchItemId)

    /**
     * Removes multiple item snapshots from one [scope] using one backend batch.
     *
     * Duplicate IDs reject the request before mutation. Each item removal is atomic, while the
     * complete batch is not promised to be globally transactional. Missing IDs are successful
     * no-ops and do not affect [SearchBatchResult.isSuccess].
     *
     * @return deterministic per-item outcomes in the requested scope.
     * @throws IllegalArgumentException if an ID is duplicated.
     * @throws ContentDiveLifecycleException if the owning [ContentDive] is closed.
     * @throws ContentDiveException for a batch-level backend or storage failure.
     */
    public suspend fun removeAll(
        scope: SearchScope,
        itemIds: Collection<SearchItemId>,
    ): SearchBatchResult

    /**
     * Removes every indexed item, fragment, and internal chunk in [scope].
     *
     * Other scopes are isolated. Clearing an empty scope succeeds as a no-op.
     *
     * @throws ContentDiveLifecycleException if the owning [ContentDive] is closed.
     * @throws ContentDiveException if the backend cannot clear the scope.
     */
    public suspend fun clear(scope: SearchScope)
}

/**
 * Reads derived ContentDive snapshots without changing application-owned data.
 *
 * Implementations returned by standard factories may be searched concurrently with indexing.
 */
public fun interface ContentSearcher {
    /**
     * Finds, ranks, and groups matching items for [query].
     *
     * Exact matches rank above prefixes, which rank above fuzzy matches. Empty normalized query
     * text returns an empty [SearchResult]. Cancellation propagates normally.
     *
     * @throws IllegalArgumentException if the query limit exceeds the configured maximum.
     * @throws ContentDiveLifecycleException if the owning [ContentDive] is closed.
     * @throws ContentDiveException if candidate retrieval or storage access fails.
     */
    public suspend fun search(query: SearchQuery): SearchResult
}

/**
 * Thread-safe application entry point combining [ContentIndexer] and [ContentSearcher].
 *
 * A `ContentDive` owns its backend. Call [close] when the application component that created it is
 * finished; persistent backends retain indexed data, while temporary backends may discard it.
 */
public interface ContentDive : ContentIndexer, ContentSearcher, AutoCloseable {
    /**
     * Closes the engine and its backend.
     *
     * Closing is idempotent. New operations fail with [ContentDiveLifecycleException]; an operation
     * already in flight may finish its current item-level atomic mutation before resources close.
     *
     * @throws ContentDiveException if releasing backend resources fails on the first close.
     */
    public override fun close()
}

/**
 * Unambiguously identifies one item outcome in a batch that may contain multiple scopes.
 *
 * @property scope partition in which the mutation was requested.
 * @property itemId application-defined item identifier within that scope.
 */
public data class SearchBatchItemId(
    public val scope: SearchScope,
    public val itemId: SearchItemId,
)

/**
 * Describes one otherwise valid item that could not be changed by a batch operation.
 *
 * This is not used for cancellation or a batch-level storage failure; those are thrown.
 *
 * @property item scoped item whose individual mutation failed.
 * @property message non-blank backend-provided diagnostic safe for logging.
 * @throws IllegalArgumentException if [message] is blank.
 */
public data class SearchBatchFailure(
    public val item: SearchBatchItemId,
    public val message: String,
) {
    init {
        require(message.isNotBlank()) { "Batch failure message must not be blank" }
    }
}

/**
 * Deterministic per-item outcomes from [ContentIndexer.replaceAll] or
 * [ContentIndexer.removeAll].
 *
 * An item appears exactly once across [successfulItems] and [failedItems]. Successful replacement
 * is atomic per item; this type does not imply a transaction spanning the complete batch.
 *
 * @property successfulItems scoped items that completed, including successful removal no-ops.
 * @property failedItems valid scoped items whose individual backend mutation failed.
 * @throws IllegalArgumentException if either list contains duplicates or the lists overlap.
 */
public data class SearchBatchResult(
    public val successfulItems: List<SearchBatchItemId>,
    public val failedItems: List<SearchBatchFailure>,
) {
    init {
        require(successfulItems.toSet().size == successfulItems.size) {
            "Successful batch items must be unique"
        }
        require(failedItems.map { it.item }.toSet().size == failedItems.size) {
            "Failed batch items must be unique"
        }
        require(successfulItems.toSet().intersect(failedItems.map { it.item }.toSet()).isEmpty()) {
            "A batch item must not be both successful and failed"
        }
    }

    /** Whether the batch contains no individual item failures. */
    public val isSuccess: Boolean
        get() = failedItems.isEmpty()
}
