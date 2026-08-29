package com.contentdive.spi

import com.contentdive.api.AnchorRef
import com.contentdive.api.SearchFragment
import com.contentdive.api.SearchFragmentId
import com.contentdive.api.SearchItem
import com.contentdive.api.SearchBatchItemId
import com.contentdive.api.SearchScope

/**
 * Experimental candidate-retrieval features advertised by a [SearchBackend].
 *
 * The engine uses these flags only to decide which narrowing requests it may issue; final matching
 * and ranking remain engine responsibilities.
 *
 * @property supportsPrefixCandidates whether [SearchBackend.candidates] can return indexed tokens
 * beginning with a request token.
 * @property supportsFuzzyCandidates whether [SearchBackend.fuzzyTerms] is implemented.
 */
@ExperimentalContentDiveSpi
public data class BackendCapabilities(
    public val supportsPrefixCandidates: Boolean = false,
    public val supportsFuzzyCandidates: Boolean = false,
)

/**
 * Experimental request for plausible indexed terms sharing character trigrams with one normalized
 * query token.
 *
 * The backend must only narrow candidates and honor [candidateLimit]; it must not perform final
 * edit-distance acceptance or ranking.
 *
 * @property normalizedQueryToken engine-normalized, non-blank token.
 * @property trigrams non-empty normalized character trigrams for that token.
 * @property candidateLimit positive maximum number of term candidates to return.
 * @throws IllegalArgumentException if a required value is blank or empty, or the limit is not
 * positive.
 */
@ExperimentalContentDiveSpi
public data class FuzzyTermRequest(
    public val normalizedQueryToken: String,
    public val trigrams: Set<String>,
    public val candidateLimit: Int,
) {
    init {
        require(normalizedQueryToken.isNotBlank()) { "Fuzzy query token must not be blank" }
        require(trigrams.isNotEmpty()) { "Fuzzy query trigrams must not be empty" }
        require(trigrams.none(String::isBlank)) { "Fuzzy query trigrams must not be blank" }
        require(candidateLimit > 0) { "Fuzzy candidate limit must be positive" }
    }
}

/**
 * Experimental backend-proposed indexed term for engine-owned fuzzy filtering.
 *
 * @property indexedTerm non-blank normalized term present in the backend index.
 * @property trigramOverlap positive number of request trigrams shared with [indexedTerm].
 * @property documentFrequency optional positive number of scoped items using the term, suitable
 * only for deterministic candidate ordering rather than final result scoring.
 * @throws IllegalArgumentException if an invariant is violated.
 */
@ExperimentalContentDiveSpi
public data class BackendTermCandidate(
    public val indexedTerm: String,
    public val trigramOverlap: Int,
    public val documentFrequency: Int? = null,
) {
    init {
        require(indexedTerm.isNotBlank()) { "Indexed fuzzy term must not be blank" }
        require(trigramOverlap > 0) { "Trigram overlap must be positive" }
        require(documentFrequency == null || documentFrequency > 0) {
            "Document frequency must be positive when present"
        }
    }
}

/**
 * Experimental complete item snapshot after engine-owned normalization and chunking.
 *
 * A backend replaces this projection atomically for the [SearchItem.scope]/[SearchItem.id] pair.
 * [fragments] retain the logical application model while [chunks] are derived searchable storage
 * units; every fragment must own at least one chunk.
 *
 * @property item item metadata and destination snapshot returned with candidates.
 * @property fragments non-empty logical source fragments belonging to [item].
 * @property chunks non-empty engine-prepared chunks derived from [fragments].
 */
@ExperimentalContentDiveSpi
public data class PreparedProjection(
    public val item: SearchItem,
    public val fragments: List<SearchFragment>,
    public val chunks: List<PreparedTextChunk>,
)

/**
 * One normalized token and its UTF-16 character range inside a chunk's original text.
 * [start] is inclusive and [end] is exclusive.
 *
 * @property value non-blank engine-normalized token used for postings.
 * @property start inclusive UTF-16 offset in [PreparedTextChunk.originalText].
 * @property end exclusive UTF-16 offset greater than [start].
 */
@ExperimentalContentDiveSpi
public data class PreparedToken(
    public val value: String,
    public val start: Int,
    public val end: Int,
)

/**
 * One internal searchable portion of a logical [SearchFragment].
 * [sourceStart] is inclusive and [sourceEnd] is exclusive in the source fragment text.
 *
 * This experimental derived type must never escape as an application result. Neighboring chunks
 * may overlap so boundary-spanning phrases remain searchable.
 *
 * @property id deterministic internal ID derived from source fragment, ordinal, and source range.
 * @property sourceFragmentId logical fragment from which this chunk was prepared.
 * @property originalText exact non-normalized source slice used to construct snippets.
 * @property normalizedText normalized searchable text for backend narrowing.
 * @property tokens normalized tokens and their positions in [originalText].
 * @property sourceStart inclusive offset in the source fragment's original text.
 * @property sourceEnd exclusive offset in the source fragment's original text.
 * @property ordinal zero-based deterministic chunk order within the source fragment.
 * @property anchor anchor inherited unchanged from the logical source fragment.
 */
@ExperimentalContentDiveSpi
public data class PreparedTextChunk(
    public val id: String,
    public val sourceFragmentId: SearchFragmentId,
    public val originalText: String,
    public val normalizedText: String,
    public val tokens: List<PreparedToken>,
    public val sourceStart: Int,
    public val sourceEnd: Int,
    public val ordinal: Int,
    public val anchor: AnchorRef?,
)

/**
 * Experimental engine-prepared request for exact and optional prefix candidates.
 *
 * Backends narrow possible chunks and return deterministic results up to [limit]. They do not
 * determine final matches, snippets, grouping, or ranking.
 *
 * @property tokens non-empty distinct normalized query or fuzzy-expanded terms.
 * @property scopes eligible partitions; an empty set means all scopes.
 * @property includePrefixes whether indexed terms beginning with each request token are eligible.
 * @property limit positive backend candidate ceiling.
 * @throws IllegalArgumentException if tokens are empty/blank or [limit] is not positive.
 */
@ExperimentalContentDiveSpi
public data class BackendCandidateRequest(
    public val tokens: List<String>,
    public val scopes: Set<SearchScope> = emptySet(),
    public val includePrefixes: Boolean = true,
    public val limit: Int,
) {
    init {
        require(tokens.isNotEmpty()) { "BackendCandidateRequest tokens must not be empty" }
        require(tokens.none(String::isBlank)) { "BackendCandidateRequest tokens must not be blank" }
        require(limit > 0) { "BackendCandidateRequest limit must be positive" }
    }
}

/**
 * Experimental candidate chunk returned to the engine for final matching, ranking, and grouping.
 *
 * @property item stored item snapshot that owns the candidate.
 * @property sourceFragment logical fragment that produced [chunk].
 * @property chunk internal prepared chunk selected by backend narrowing.
 */
@ExperimentalContentDiveSpi
public data class BackendCandidate(
    public val item: SearchItem,
    public val sourceFragment: SearchFragment,
    public val chunk: PreparedTextChunk,
)

/**
 * Experimental mutation counts returned by a single-item or scope-clear backend operation.
 *
 * @property affectedItems number of existing items changed or removed; a missing removal is zero.
 * @property affectedFragments number of logical fragments changed or removed.
 * @throws IllegalArgumentException if either count is negative.
 */
@ExperimentalContentDiveSpi
public data class BackendWriteResult(
    public val affectedItems: Int,
    public val affectedFragments: Int,
) {
    init {
        require(affectedItems >= 0) { "affectedItems must not be negative" }
        require(affectedFragments >= 0) { "affectedFragments must not be negative" }
    }
}

/**
 * Experimental per-item failure from an otherwise valid backend batch.
 *
 * Cancellation and batch-level storage failures must be thrown rather than represented here.
 *
 * @property item scoped item whose individual atomic mutation failed.
 * @property message non-blank diagnostic for propagation to the public batch result.
 * @throws IllegalArgumentException if [message] is blank.
 */
@ExperimentalContentDiveSpi
public data class BackendBatchFailure(
    public val item: SearchBatchItemId,
    public val message: String,
) {
    init {
        require(message.isNotBlank()) { "Backend batch failure message must not be blank" }
    }
}

/**
 * Experimental deterministic per-item outcomes from a backend batch mutation.
 *
 * Each requested item must appear exactly once across [successfulItems] and [failedItems]. The
 * backend guarantees atomic replacement/removal per item but need not provide one transaction for
 * the whole batch.
 *
 * @property successfulItems requested items that completed, including removal no-ops.
 * @property failedItems otherwise valid items whose individual mutation failed.
 * @property affectedItems number of existing items actually changed or removed.
 * @property affectedFragments number of logical fragments actually changed or removed.
 * @throws IllegalArgumentException if counts are negative/inconsistent, outcomes overlap, or an
 * outcome list contains duplicates.
 */
@ExperimentalContentDiveSpi
public data class BackendBatchWriteResult(
    public val successfulItems: List<SearchBatchItemId>,
    public val failedItems: List<BackendBatchFailure>,
    public val affectedItems: Int,
    public val affectedFragments: Int,
) {
    init {
        require(affectedItems >= 0) { "affectedItems must not be negative" }
        require(affectedFragments >= 0) { "affectedFragments must not be negative" }
        require(affectedItems <= successfulItems.size) {
            "affectedItems must not exceed successful items"
        }
        require(successfulItems.toSet().size == successfulItems.size) {
            "Successful backend batch items must be unique"
        }
        require(failedItems.map { it.item }.toSet().size == failedItems.size) {
            "Failed backend batch items must be unique"
        }
        require(successfulItems.toSet().intersect(failedItems.map { it.item }.toSet()).isEmpty()) {
            "A backend batch item must not be both successful and failed"
        }
    }
}
