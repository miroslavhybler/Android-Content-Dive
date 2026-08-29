package com.contentdive.engine

import java.text.Normalizer
import java.util.Locale

internal data class NormalizedText(
    val value: String,
    val tokens: List<NormalizedToken>,
)

internal data class NormalizedToken(
    val value: String,
    val start: Int,
    val end: Int,
)

internal object LexicalNormalizer {
    fun normalize(source: String): NormalizedText {
        val tokens = buildList {
            var offset = 0
            var tokenStart = -1
            var tokenEnd = -1

            fun emitToken() {
                if (tokenStart < 0) return
                val value = normalizeToken(source.substring(tokenStart, tokenEnd))
                if (value.isNotEmpty()) add(NormalizedToken(value, tokenStart, tokenEnd))
                tokenStart = -1
                tokenEnd = -1
            }

            while (offset < source.length) {
                val codePoint = source.codePointAt(offset)
                val nextOffset = offset + Character.charCount(codePoint)
                when {
                    Character.isLetterOrDigit(codePoint) -> {
                        if (tokenStart < 0) tokenStart = offset
                        tokenEnd = nextOffset
                    }
                    isCombiningMark(codePoint) && tokenStart >= 0 -> tokenEnd = nextOffset
                    else -> emitToken()
                }
                offset = nextOffset
            }
            emitToken()
        }
        return NormalizedText(
            value = tokens.joinToString(" ", transform = NormalizedToken::value),
            tokens = tokens,
        )
    }

    private fun normalizeToken(source: String): String {
        val decomposed = Normalizer.normalize(
            source.lowercase(Locale.ROOT),
            Normalizer.Form.NFKD,
        )
        return buildString(decomposed.length) {
            var offset = 0
            while (offset < decomposed.length) {
                val codePoint = decomposed.codePointAt(offset)
                offset += Character.charCount(codePoint)
                if (!isCombiningMark(codePoint) && Character.isLetterOrDigit(codePoint)) {
                    appendCodePoint(codePoint)
                }
            }
        }
    }

    private fun isCombiningMark(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
        Character.NON_SPACING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt(),
        -> true
        else -> false
    }
}
