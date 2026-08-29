package com.contentdive.api

/**
 * Immutable application-level defaults shared by the standard ContentDive factories.
 *
 * Ranking, chunking, and fuzzy-matching thresholds intentionally are not configurable. A query
 * may provide its own [SearchQuery.limit], but it cannot exceed [maximumResultLimit].
 *
 * @property defaultResultLimit result count used when [SearchQuery.limit] is absent.
 * @property maximumResultLimit upper bound accepted from either this configuration or a query.
 * @property fuzzySearchEnabled whether typo-tolerant term expansion is enabled by default. Exact
 * and prefix matching remain enabled when this is `false`.
 * @throws IllegalArgumentException if either limit is not positive or the default exceeds the
 * maximum.
 */
public data class ContentDiveConfiguration constructor(
    public val defaultResultLimit: Int = DEFAULT_RESULT_LIMIT,
    public val maximumResultLimit: Int = DEFAULT_MAXIMUM_RESULT_LIMIT,
    public val fuzzySearchEnabled: Boolean = true,
) {
    init {
        require(value = defaultResultLimit > 0) { "Default result limit must be positive" }
        require(value = maximumResultLimit > 0) { "Maximum result limit must be positive" }
        require(value = defaultResultLimit <= maximumResultLimit) {
            "Default result limit must not exceed maximum result limit"
        }
    }

    /** Stable default values used by the no-argument configuration. */
    public companion object {
        /** Default number of matches returned when a query does not specify a limit. */
        public const val DEFAULT_RESULT_LIMIT: Int = 20

        /** Default safety ceiling for a query-specific result limit. */
        public const val DEFAULT_MAXIMUM_RESULT_LIMIT: Int = 100
    }
}
