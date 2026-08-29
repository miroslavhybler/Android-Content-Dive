package com.contentdive.ksp.annotations

import com.contentdive.api.SearchFragmentId
import com.contentdive.api.SearchItemId

/**
 * Canonical identifier helpers shared by generated and equivalent manual projectors.
 *
 * Using these helpers prevents ambiguous delimiter-based IDs and keeps generated projections
 * deterministic across builds.
 */
public object ContentDiveGeneratedIds {
    /**
     * Builds an item ID as `document-type:percent-encoded-source-id` using UTF-8 bytes.
     *
     * @throws IllegalArgumentException if [documentType] is not a valid generated name or
     * [sourceId] is blank.
     */
    public fun itemId(documentType: String, sourceId: String): SearchItemId {
        requireValidName(documentType, "Document type")
        require(sourceId.isNotBlank()) { "ContentDive source ID must not be blank" }
        return SearchItemId("$documentType:${encodeComponent(sourceId)}")
    }

    /**
     * Builds a stable fragment ID from a generated field name.
     *
     * @throws IllegalArgumentException if [field] does not begin with a letter or contains
     * characters other than letters, digits, `.`, `_`, or `-`.
     */
    public fun fragmentId(field: String): SearchFragmentId {
        requireValidName(field, "Fragment field")
        return SearchFragmentId(field)
    }

    private fun requireValidName(value: String, label: String) {
        require(NAME_PATTERN.matches(value)) {
            "$label '$value' must match ${NAME_PATTERN.pattern}"
        }
    }

    private fun encodeComponent(value: String): String = buildString {
        value.toByteArray(Charsets.UTF_8).forEach { byte ->
            val code = byte.toInt() and 0xff
            if (
                code in 'a'.code..'z'.code ||
                code in 'A'.code..'Z'.code ||
                code in '0'.code..'9'.code ||
                code == '-'.code || code == '_'.code || code == '.'.code || code == '~'.code
            ) {
                append(code.toChar())
            } else {
                append('%')
                append(HEX[code ushr 4])
                append(HEX[code and 0x0f])
            }
        }
    }

    private const val HEX: String = "0123456789ABCDEF"
    private val NAME_PATTERN: Regex = Regex("[A-Za-z][A-Za-z0-9._-]*")
}
