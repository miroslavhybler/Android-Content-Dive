package com.contentdive.api

/**
 * Identifies one [SearchItem] within a [SearchScope].
 *
 * The same value may be reused in another scope; replacement and removal use the pair of scope and
 * item ID as the storage identity.
 *
 * @property value stable, non-blank application identifier.
 * @throws IllegalArgumentException if [value] is blank.
 */
@JvmInline
public value class SearchItemId(public val value: String) {
    init {
        require(value.isNotBlank()) { "SearchItemId must not be blank" }
    }
}

/**
 * Identifies one [SearchFragment] within a [SearchProjection].
 *
 * Fragment IDs must be unique within an item projection and should remain stable across equivalent
 * projections so diagnostics and deterministic ordering remain understandable.
 *
 * @property value non-blank fragment identifier.
 * @throws IllegalArgumentException if [value] is blank.
 */
@JvmInline
public value class SearchFragmentId(public val value: String) {
    init {
        require(value.isNotBlank()) { "SearchFragmentId must not be blank" }
    }
}

/**
 * Application-defined partition that isolates indexing, removal, clearing, and scoped search.
 *
 * Typical scopes separate content categories, accounts, or tenants. An empty scope set in
 * [SearchQuery] searches across all indexed scopes.
 *
 * @property value stable, non-blank scope identifier.
 * @throws IllegalArgumentException if [value] is blank.
 */
@JvmInline
public value class SearchScope(public val value: String) {
    init {
        require(value.isNotBlank()) { "SearchScope must not be blank" }
    }
}
