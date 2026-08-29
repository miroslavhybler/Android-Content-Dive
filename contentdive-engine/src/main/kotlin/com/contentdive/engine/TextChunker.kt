package com.contentdive.engine

import com.contentdive.api.SearchFragment
import com.contentdive.spi.ExperimentalContentDiveSpi
import com.contentdive.spi.PreparedTextChunk
import com.contentdive.spi.PreparedToken
import java.text.BreakIterator
import java.util.Locale

/** Deterministic, engine-owned chunking policy. It is deliberately not part of the public API. */
@OptIn(ExperimentalContentDiveSpi::class)
internal object TextChunker {
    private const val MAX_TOKENS_PER_CHUNK = 80
    private const val OVERLAP_TOKENS = 8

    fun prepare(fragment: SearchFragment): List<PreparedTextChunk> {
        val source = fragment.text
        val sourceTokens = LexicalNormalizer.normalize(source).tokens
        require(sourceTokens.isNotEmpty()) {
            "Fragment '${fragment.id.value}' must contain searchable letters or numbers"
        }
        if (sourceTokens.size <= MAX_TOKENS_PER_CHUNK) {
            return listOf(createChunk(fragment, TextRange(0, source.length), 0))
        }

        val preferredPieces = splitOversized(
            source = source,
            range = TextRange(0, source.length),
            level = SplitLevel.PARAGRAPH,
        )
        val baseRanges = packPieces(source, preferredPieces)
        return baseRanges.mapIndexed { ordinal, baseRange ->
            val previousTokens = sourceTokens
                .asSequence()
                .filter { it.end <= baseRange.start }
                .toList()
                .takeLast(OVERLAP_TOKENS)
            val nextTokens = sourceTokens
                .asSequence()
                .filter { it.start >= baseRange.end }
                .take(OVERLAP_TOKENS)
                .toList()
            val expandedRange = TextRange(
                start = previousTokens.firstOrNull()?.start ?: baseRange.start,
                end = nextTokens.lastOrNull()?.end ?: baseRange.end,
            )
            createChunk(fragment, expandedRange, ordinal)
        }
    }

    private fun splitOversized(
        source: String,
        range: TextRange,
        level: SplitLevel,
    ): List<TextRange> {
        if (tokenCount(source, range) <= MAX_TOKENS_PER_CHUNK) return listOf(range)
        if (level == SplitLevel.TOKEN_WINDOW) return tokenWindows(source, range)

        val split = when (level) {
            SplitLevel.PARAGRAPH -> paragraphRanges(source, range)
            SplitLevel.SENTENCE -> sentenceRanges(source, range)
            SplitLevel.TOKEN_WINDOW -> error("Handled above")
        }
        val nextLevel = level.next()
        if (split.size <= 1) return splitOversized(source, range, nextLevel)
        return split.flatMap { piece -> splitOversized(source, piece, nextLevel) }
    }

    private fun paragraphRanges(source: String, range: TextRange): List<TextRange> {
        val local = source.substring(range.start, range.end)
        val separators = PARAGRAPH_SEPARATOR.findAll(local)
        val ranges = mutableListOf<TextRange>()
        var localStart = 0
        separators.forEach { separator ->
            trimRange(source, TextRange(range.start + localStart, range.start + separator.range.first))
                ?.let(ranges::add)
            localStart = separator.range.last + 1
        }
        trimRange(source, TextRange(range.start + localStart, range.end))?.let(ranges::add)
        return ranges.ifEmpty { listOf(range) }
    }

    private fun sentenceRanges(source: String, range: TextRange): List<TextRange> {
        val local = source.substring(range.start, range.end)
        val iterator = BreakIterator.getSentenceInstance(Locale.ROOT).apply { setText(local) }
        val ranges = mutableListOf<TextRange>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            trimRange(source, TextRange(range.start + start, range.start + end))?.let(ranges::add)
            start = end
            end = iterator.next()
        }
        return ranges.ifEmpty { listOf(range) }
    }

    private fun tokenWindows(source: String, range: TextRange): List<TextRange> {
        val tokens = LexicalNormalizer.normalize(source.substring(range.start, range.end)).tokens
        return tokens.chunked(MAX_TOKENS_PER_CHUNK).map { window ->
            TextRange(
                start = range.start + window.first().start,
                end = range.start + window.last().end,
            )
        }
    }

    private fun packPieces(source: String, pieces: List<TextRange>): List<TextRange> {
        val packed = mutableListOf<TextRange>()
        var current: TextRange? = null
        var currentTokenCount = 0
        pieces.forEach { piece ->
            val pieceTokenCount = tokenCount(source, piece)
            val open = current
            if (open == null) {
                current = piece
                currentTokenCount = pieceTokenCount
            } else if (currentTokenCount + pieceTokenCount <= MAX_TOKENS_PER_CHUNK) {
                current = TextRange(open.start, piece.end)
                currentTokenCount += pieceTokenCount
            } else {
                packed += open
                current = piece
                currentTokenCount = pieceTokenCount
            }
        }
        current?.let(packed::add)
        return packed
    }

    private fun createChunk(
        fragment: SearchFragment,
        range: TextRange,
        ordinal: Int,
    ): PreparedTextChunk {
        val originalText = fragment.text.substring(range.start, range.end)
        val normalized = LexicalNormalizer.normalize(originalText)
        return PreparedTextChunk(
            id = "${fragment.id.value}:chunk:$ordinal:${range.start}-${range.end}",
            sourceFragmentId = fragment.id,
            originalText = originalText,
            normalizedText = normalized.value,
            tokens = normalized.tokens.map { token ->
                PreparedToken(token.value, token.start, token.end)
            },
            sourceStart = range.start,
            sourceEnd = range.end,
            ordinal = ordinal,
            anchor = fragment.anchor,
        )
    }

    private fun tokenCount(source: String, range: TextRange): Int =
        LexicalNormalizer.normalize(source.substring(range.start, range.end)).tokens.size

    private fun trimRange(source: String, range: TextRange): TextRange? {
        var start = range.start
        var end = range.end
        while (start < end && source[start].isWhitespace()) start++
        while (end > start && source[end - 1].isWhitespace()) end--
        return if (start == end) null else TextRange(start, end)
    }

    private data class TextRange(val start: Int, val end: Int)

    private enum class SplitLevel {
        PARAGRAPH,
        SENTENCE,
        TOKEN_WINDOW,
        ;

        fun next(): SplitLevel = entries[ordinal + 1]
    }

    private val PARAGRAPH_SEPARATOR = Regex("(?:\\r?\\n)[\\t \\r]*(?:\\r?\\n)+")
}
