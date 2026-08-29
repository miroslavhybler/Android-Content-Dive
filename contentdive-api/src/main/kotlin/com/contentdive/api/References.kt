package com.contentdive.api

/**
 * Serialization-independent reference describing what an application should open.
 *
 * ContentDive treats [payload] as opaque and never parses it in the core API. A search result is a
 * derived index snapshot: the destination screen should decode this reference, then reload current
 * authoritative data from the application's repository rather than rendering indexed text as the
 * source of truth.
 *
 * @property type application-owned schema name used to select a resolver.
 * @property version positive application-owned schema version stored with the payload.
 * @property payload opaque serialized identity or navigation data.
 */
public data class DestinationRef(
    public val type: String,
    public val version: Int,
    public val payload: String,
)

/**
 * Optional serialization-independent reference describing where to focus inside a destination.
 *
 * An anchor usually identifies a semantic paragraph, block, tab, or section within the entity
 * described by [DestinationRef]. ContentDive preserves it from the best matching [SearchFragment]
 * but treats [payload] as opaque.
 *
 * @property type application-owned anchor schema name used to select a decoder.
 * @property version positive application-owned schema version stored with the payload.
 * @property payload opaque serialized block or focus identity.
 */
public data class AnchorRef(
    public val type: String,
    public val version: Int,
    public val payload: String,
)
