package com.contentdive.engine

/** Backend narrowing remains an engine/SPI concern; fuzzy acceptance belongs to contentdive-fuzzy. */
internal fun characterTrigrams(token: String): Set<String> {
    val characters = buildList {
        add("^")
        codePoints(token).forEach { add(String(Character.toChars(it))) }
        add("$")
    }
    return characters.windowed(3).mapTo(linkedSetOf()) { it.joinToString("") }
}

/** Avoids backend fuzzy expansion for tokens the shared matcher deliberately protects. */
internal fun supportsFuzzyCandidateLookup(token: String): Boolean =
    token.codePointCount(0, token.length) >= MIN_FUZZY_TOKEN_LENGTH

private fun codePoints(value: String): IntArray {
    val result = IntArray(value.codePointCount(0, value.length))
    var sourceOffset = 0
    var resultOffset = 0
    while (sourceOffset < value.length) {
        val codePoint = value.codePointAt(sourceOffset)
        result[resultOffset++] = codePoint
        sourceOffset += Character.charCount(codePoint)
    }
    return result
}

internal const val FUZZY_CANDIDATE_LIMIT: Int = 32
private const val MIN_FUZZY_TOKEN_LENGTH = 4
