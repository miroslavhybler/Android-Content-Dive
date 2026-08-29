package com.contentdive.api

/**
 * Display and navigation metadata for one application-owned entity exposed to search.
 *
 * This is a derived index snapshot, not authoritative entity data. Use [destination] to reload the
 * current entity from the application repository when the user opens a result.
 *
 * @property id identity used with [scope] for replacement, removal, grouping, and deterministic
 * ordering.
 * @property scope partition containing this item and all of its fragments.
 * @property title primary result label; the title is searchable only when supplied separately as a
 * [SearchFragment].
 * @property destination opaque application-owned reference describing what to open.
 * @property subtitle optional secondary display label; it is not searchable unless projected as a
 * fragment.
 */
public data class SearchItem(
    public val id: SearchItemId,
    public val scope: SearchScope,
    public val title: String,
    public val destination: DestinationRef,
    public val subtitle: String? = null,
)

/** Semantic role used by ContentDive's fixed v1 ranking policy. */
public enum class SearchFragmentKind {
    /** Primary entity title; receives the strongest role contribution to ranking. */
    TITLE,

    /** Section title or other prominent text; ranks above ordinary body text. */
    HEADING,

    /** Ordinary searchable content. */
    BODY,
}

/**
 * One logical, searchable piece of visible text belonging to a [SearchItem].
 *
 * A long fragment may become multiple internal chunks, but results remain grouped by this logical
 * fragment and its item. Use one fragment per semantic block when different blocks need different
 * [anchor] values.
 *
 * @property id identifier unique within the owning [SearchProjection].
 * @property itemId ID of the owning item; it must equal [SearchItem.id].
 * @property scope scope of the owning item; it must equal [SearchItem.scope].
 * @property text original visible text used for snippets. It must be non-blank when indexed.
 * @property kind semantic ranking role.
 * @property weight positive finite application emphasis applied within the fixed ranking policy.
 * @property anchor optional application-owned location inside the destination represented by this
 * fragment.
 */
public data class SearchFragment(
    public val id: SearchFragmentId,
    public val itemId: SearchItemId,
    public val scope: SearchScope,
    public val text: String,
    public val kind: SearchFragmentKind = SearchFragmentKind.BODY,
    public val weight: Double = 1.0,
    public val anchor: AnchorRef? = null,
)

/**
 * Complete derived index snapshot for one [item].
 *
 * Every fragment must reference the same item ID and scope, and fragment IDs must be unique. Passing
 * this projection to [ContentIndexer.replace] removes all previously indexed fragments for the same
 * scoped item before publishing this complete replacement atomically.
 *
 * @property item display, identity, and destination metadata for the result.
 * @property fragments non-empty logical text fields or semantic blocks belonging to [item].
 */
public data class SearchProjection(
    public val item: SearchItem,
    public val fragments: List<SearchFragment>,
)

/**
 * User query text plus optional scope filtering and result limit.
 *
 * @property text text normalized by the engine for exact, prefix, and optionally fuzzy matching.
 * Blank or punctuation-only normalized text returns no matches.
 * @property scopes scopes eligible for the search; an empty set searches every scope.
 * @property limit positive query-specific maximum, or `null` to use
 * [ContentDiveConfiguration.defaultResultLimit]. It must not exceed the configured maximum.
 * @throws IllegalArgumentException if [limit] is present and not positive.
 */
public data class SearchQuery(
    public val text: String,
    public val scopes: Set<SearchScope> = emptySet(),
    public val limit: Int? = null,
) {
    init {
        require(limit == null || limit > 0) { "SearchQuery limit must be positive when present" }
    }
}

/**
 * One ranked, item-grouped search match.
 *
 * Multiple matching internal chunks and logical fragments still produce one match per scoped item.
 *
 * @property item indexed display and destination snapshot for the matched entity.
 * @property bestFragment highest-ranked logical fragment, used for [snippet] and [anchor].
 * @property matchedFragments deterministic list of distinct logical fragments that contributed.
 * @property score engine-owned relative ranking value; applications should not persist or compare
 * it across ContentDive versions.
 * @property snippet bounded original-text context surrounding the best match, with ellipses when
 * source content was omitted.
 */
public data class SearchMatch(
    public val item: SearchItem,
    public val bestFragment: SearchFragment,
    public val matchedFragments: List<SearchFragment>,
    public val score: Double,
    public val snippet: String,
) {
    /** Application destination preserved from [item] for convenient result handling. */
    public val destination: DestinationRef
        get() = item.destination

    /** Optional location preserved from [bestFragment]. */
    public val anchor: AnchorRef?
        get() = bestFragment.anchor
}

/**
 * Deterministically ordered, item-grouped matches returned by [ContentSearcher.search].
 *
 * @property matches highest-ranked match first, already limited according to [SearchQuery.limit]
 * and [ContentDiveConfiguration].
 */
public data class SearchResult(
    public val matches: List<SearchMatch>,
)
