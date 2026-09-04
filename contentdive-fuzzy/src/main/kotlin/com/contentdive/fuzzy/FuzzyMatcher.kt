package com.contentdive.fuzzy

import java.text.Normalizer
import java.util.Locale
import java.util.PriorityQueue
import kotlin.math.abs

/**
 * Match quality for query tokens found in a candidate text.
 *
 * Quality describes the least exact accepted token match: [EXACT] means every matched query token
 * equals a candidate token, [PREFIX] means at least one token matched by prefix and none required
 * typo correction, and [FUZZY] means at least one token required typo correction.
 */
public enum class FuzzyMatchQuality {
    /** Every matched query token equals a normalized candidate token. */
    EXACT,

    /** At least one query token is a prefix of a candidate token; none required typo correction. */
    PREFIX,

    /** At least one query token was accepted through typo-tolerant matching. */
    FUZZY,
}

/**
 * Summary of an accepted comparison.
 *
 * @property score deterministic relevance in the inclusive `0.0..1.0` range. More query-token
 * coverage and stronger match qualities produce a higher value.
 * @property quality the least exact token quality needed for the accepted comparison.
 * @throws IllegalArgumentException if [score] is not finite or falls outside `0.0..1.0`.
 */
public data class FuzzyMatch(
    public val score: Double,
    public val quality: FuzzyMatchQuality,
) {
    init {
        require(score.isFinite() && score in 0.0..1.0) {
            "FuzzyMatch score must be finite and between 0 and 1"
        }
    }
}

/**
 * Entry point for synchronous fuzzy matching over caller-owned text.
 *
 * This matcher does not index, persist, page, or load candidates. Use [compile] when comparing one
 * query with several candidate strings so normalization and query token preparation happen once.
 * All operations are thread-safe.
 */
public object FuzzyMatcher {
    /**
     * Normalizes and prepares [query] for reuse across candidate comparisons.
     *
     * Blank queries compile successfully but never match a candidate.
     */
    public fun compile(query: String): CompiledFuzzyQuery =
        CompiledFuzzyQuery(normalize(query))

    /**
     * Compares [query] with [candidateText] in one operation.
     *
     * This is equivalent to `compile(query).match(candidateText)`. Prefer [compile] for a collection.
     *
     * @return the accepted score and quality, or `null` when no query token matches.
     */
    public fun match(query: String, candidateText: String): FuzzyMatch? =
        compile(query).match(candidateText)
}

/**
 * Immutable normalized query that can evaluate many candidate strings efficiently.
 *
 * Instances are created by [FuzzyMatcher.compile] and may be reused concurrently. Candidate text
 * is tokenized per comparison; callers searching large datasets should prefilter them or use
 * ContentDive's indexed search instead.
 */
public class CompiledFuzzyQuery internal constructor(
    normalizedQueryTokens: List<String>,
) {
    private val queryTokens: List<CompiledToken> = normalizedQueryTokens.map(::compileToken)

    /**
     * Compares the compiled query tokens with normalized tokens from [candidateText].
     *
     * Comparisons are case-insensitive and fold diacritics. Exact matches outrank prefixes, prefixes
     * outrank typos, and fuzzy distance is calculated per token rather than against the full text.
     * A candidate may match only part of a multi-token query; greater coverage receives a higher
     * [FuzzyMatch.score].
     *
     * @return match metadata, or `null` for blank queries, blank candidates, or unrelated text.
     */
    public fun match(candidateText: String): FuzzyMatch? {
        if (queryTokens.isEmpty()) return null
        val candidateTokens = normalize(candidateText)
        if (candidateTokens.isEmpty()) return null

        val tokenMatches = queryTokens.mapNotNull { queryToken ->
            candidateTokens.asSequence()
                .mapNotNull { candidateToken -> matchToken(queryToken, candidateToken) }
                .minWithOrNull(tokenMatchComparator)
        }
        if (tokenMatches.isEmpty()) return null

        return FuzzyMatch(
            score = tokenMatches.sumOf(TokenMatch::score) / queryTokens.size,
            quality = tokenMatches.maxBy(TokenMatch::qualityRank).quality,
        )
    }

    /**
     * Returns accepted [candidates] ordered by descending match score.
     *
     * [textSelector] is invoked once per inspected candidate. Equal scores preserve source order.
     * When [limit] is non-null, only a bounded top-result collection is retained during the scan;
     * `0` returns an empty list without evaluating candidates.
     *
     * @throws IllegalArgumentException if [limit] is negative.
     * @throws RuntimeException any exception thrown by [textSelector] is propagated unchanged.
     */
    public fun <T> rank(
        candidates: Iterable<T>,
        textSelector: (T) -> String,
        limit: Int? = null,
    ): List<T> {
        require(limit == null || limit >= 0) { "Fuzzy rank limit must not be negative" }
        if (limit == 0 || queryTokens.isEmpty()) return emptyList()

        if (limit == null) {
            return candidates.mapIndexedNotNull { index, candidate ->
                match(textSelector(candidate))?.let { IndexedMatch(index, candidate, it) }
            }.sortedWith(bestFirstComparator()).map { it.candidate }
        }

        val bestFirst = bestFirstComparator<T>()
        val retained = PriorityQueue<IndexedMatch<T>>(
            minOf(limit, TOP_RESULTS_INITIAL_CAPACITY),
            bestFirst.reversed(),
        )
        candidates.forEachIndexed { index, candidate ->
            val match = match(textSelector(candidate)) ?: return@forEachIndexed
            val accepted = IndexedMatch(index, candidate, match)
            if (retained.size < limit) {
                retained += accepted
            } else if (bestFirst.compare(accepted, retained.peek()) < 0) {
                retained.poll()
                retained += accepted
            }
        }
        return retained.sortedWith(bestFirst).map { it.candidate }
    }
}

private data class CompiledToken(
    val value: String,
    val codePoints: IntArray,
    val trigrams: Set<String>,
)

private data class TokenMatch(
    val score: Double,
    val quality: FuzzyMatchQuality,
) {
    val qualityRank: Int = when (quality) {
        FuzzyMatchQuality.EXACT -> 0
        FuzzyMatchQuality.PREFIX -> 1
        FuzzyMatchQuality.FUZZY -> 2
    }
}

private data class IndexedMatch<T>(
    val sourceIndex: Int,
    val candidate: T,
    val match: FuzzyMatch,
)

private fun <T> bestFirstComparator(): Comparator<IndexedMatch<T>> =
    compareByDescending<IndexedMatch<T>> { it.match.score }
        .thenBy(IndexedMatch<T>::sourceIndex)

private val tokenMatchComparator: Comparator<TokenMatch> =
    compareBy<TokenMatch>(TokenMatch::qualityRank)
        .thenByDescending(TokenMatch::score)

private fun matchToken(query: CompiledToken, candidate: String): TokenMatch? {
    if (candidate == query.value) return TokenMatch(EXACT_SCORE, FuzzyMatchQuality.EXACT)
    if (candidate.startsWith(query.value)) {
        return TokenMatch(PREFIX_SCORE, FuzzyMatchQuality.PREFIX)
    }
    if (query.codePoints.size < MIN_FUZZY_TOKEN_LENGTH) return null

    val candidateCodePoints = codePoints(candidate)
    val maximumDistance = maximumDistance(maxOf(query.codePoints.size, candidateCodePoints.size))
    if (abs(query.codePoints.size - candidateCodePoints.size) > maximumDistance) return null
    if (query.trigrams.intersect(characterTrigrams(candidateCodePoints)).isEmpty()) return null

    val distance = damerauLevenshtein(query.codePoints, candidateCodePoints)
    val similarity = 1.0 - distance.toDouble() / maxOf(
        query.codePoints.size,
        candidateCodePoints.size,
    )
    if (distance == 0 || distance > maximumDistance || similarity < MIN_SIMILARITY) return null
    return TokenMatch(similarity * FUZZY_SCORE_MULTIPLIER, FuzzyMatchQuality.FUZZY)
}

private fun compileToken(value: String): CompiledToken {
    val codePoints = codePoints(value)
    return CompiledToken(value, codePoints, characterTrigrams(codePoints))
}

private fun normalize(source: String): List<String> = buildList {
    var token = StringBuilder()

    fun emitToken() {
        if (token.isEmpty()) return
        normalizeToken(token.toString()).takeIf(String::isNotEmpty)?.let(::add)
        token = StringBuilder()
    }

    var offset = 0
    while (offset < source.length) {
        val codePoint = source.codePointAt(offset)
        offset += Character.charCount(codePoint)
        when {
            Character.isLetterOrDigit(codePoint) -> token.appendCodePoint(codePoint)
            isCombiningMark(codePoint) && token.isNotEmpty() -> token.appendCodePoint(codePoint)
            else -> emitToken()
        }
    }
    emitToken()
}.distinct()

private fun normalizeToken(source: String): String {
    val decomposed = Normalizer.normalize(source.lowercase(Locale.ROOT), Normalizer.Form.NFKD)
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

private fun characterTrigrams(codePoints: IntArray): Set<String> {
    val characters = buildList {
        add("^")
        codePoints.forEach { add(String(Character.toChars(it))) }
        add("$")
    }
    return characters.windowed(3).mapTo(linkedSetOf()) { it.joinToString("") }
}

private fun maximumDistance(longestTokenLength: Int): Int = when (longestTokenLength) {
    in 0..4 -> 1
    in 5..8 -> 2
    else -> 3
}

/** Optimal-string-alignment Damerau-Levenshtein distance with adjacent transpositions. */
private fun damerauLevenshtein(source: IntArray, target: IntArray): Int {
    if (source.isEmpty()) return target.size
    if (target.isEmpty()) return source.size
    val distances = Array(source.size + 1) { IntArray(target.size + 1) }
    for (sourceIndex in 0..source.size) distances[sourceIndex][0] = sourceIndex
    for (targetIndex in 0..target.size) distances[0][targetIndex] = targetIndex

    for (sourceIndex in 1..source.size) {
        for (targetIndex in 1..target.size) {
            val substitutionCost = if (source[sourceIndex - 1] == target[targetIndex - 1]) 0 else 1
            var distance = minOf(
                distances[sourceIndex - 1][targetIndex] + 1,
                distances[sourceIndex][targetIndex - 1] + 1,
                distances[sourceIndex - 1][targetIndex - 1] + substitutionCost,
            )
            if (
                sourceIndex > 1 &&
                targetIndex > 1 &&
                source[sourceIndex - 1] == target[targetIndex - 2] &&
                source[sourceIndex - 2] == target[targetIndex - 1]
            ) {
                distance = minOf(distance, distances[sourceIndex - 2][targetIndex - 2] + 1)
            }
            distances[sourceIndex][targetIndex] = distance
        }
    }
    return distances[source.size][target.size]
}

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

private const val MIN_FUZZY_TOKEN_LENGTH = 4
private const val MIN_SIMILARITY = 0.65
private const val EXACT_SCORE = 1.0
private const val PREFIX_SCORE = 0.8
private const val FUZZY_SCORE_MULTIPLIER = 0.75
private const val TOP_RESULTS_INITIAL_CAPACITY = 64
