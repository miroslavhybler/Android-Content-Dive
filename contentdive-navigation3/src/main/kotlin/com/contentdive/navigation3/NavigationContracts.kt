package com.contentdive.navigation3

import androidx.navigation3.runtime.NavKey
import com.contentdive.api.AnchorRef
import com.contentdive.api.DestinationRef

/**
 * Application-provided conversion from ContentDive references to Navigation 3 keys.
 *
 * ContentDive does not know an application's routes. A resolver should validate and decode the
 * opaque [DestinationRef], optionally decode [AnchorRef], and return keys that open the entity and
 * focus the matched block. The destination screen should reload current authoritative data from its
 * repository rather than treating indexed result text as the entity source of truth.
 *
 * @param K application-owned Navigation 3 key type.
 */
public fun interface DestinationResolver<K : NavKey> {
    /**
     * Resolves one destination and optional matching-fragment anchor into a navigation plan.
     *
     * Implementations may throw an application-specific validation or decoding exception for an
     * unknown type, unsupported version, or malformed payload.
     */
    public fun resolve(destination: DestinationRef, anchor: AnchorRef?): NavigationPlan<K>
}

/**
 * One or more application-owned keys to append to a Navigation 3 back stack in list order.
 *
 * A plan may contain a parent/detail chain, but must never be empty.
 *
 * @property keys non-empty ordered Navigation 3 keys supplied by the application.
 * @throws IllegalArgumentException if [keys] is empty.
 */
public data class NavigationPlan<K : NavKey>(
    public val keys: List<K>,
) {
    init {
        require(keys.isNotEmpty()) { "NavigationPlan must contain at least one NavKey" }
    }
}
