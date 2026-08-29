package com.contentdive.engine

import com.contentdive.spi.BackendTermCandidate
import com.contentdive.spi.ExperimentalContentDiveSpi

internal data class FuzzyTermMatch(
    val queryToken: String,
    val indexedTerm: String,
    val editDistance: Int,
    val similarity: Double,
    val trigramOverlap: Int,
)

/** Engine-owned fuzzy filtering and distance calculation. */
@OptIn(ExperimentalContentDiveSpi::class)
internal object FuzzyMatcher {
    const val CANDIDATE_LIMIT: Int = 32
    private const val MIN_TOKEN_LENGTH = 4
    private const val MIN_SIMILARITY = 0.65

    fun isEnabled(queryToken: String): Boolean = codePoints(queryToken).size >= MIN_TOKEN_LENGTH

    fun characterTrigrams(token: String): Set<String> {
        val characters = buildList {
            add("^")
            codePoints(token).forEach { add(String(Character.toChars(it))) }
            add("$")
        }
        return characters.windowed(3).mapTo(linkedSetOf()) { it.joinToString("") }
    }

    fun filter(
        queryToken: String,
        candidates: List<BackendTermCandidate>,
    ): List<FuzzyTermMatch> {
        if (!isEnabled(queryToken)) return emptyList()
        val queryCodePoints = codePoints(queryToken)
        return candidates.asSequence()
            .filter { candidate ->
                candidate.indexedTerm != queryToken &&
                    !candidate.indexedTerm.startsWith(queryToken)
            }
            .mapNotNull { candidate ->
                val indexedCodePoints = codePoints(candidate.indexedTerm)
                val maximumDistance = maximumDistance(maxOf(queryCodePoints.size, indexedCodePoints.size))
                if (kotlin.math.abs(queryCodePoints.size - indexedCodePoints.size) > maximumDistance) {
                    return@mapNotNull null
                }
                val distance = damerauLevenshtein(queryCodePoints, indexedCodePoints)
                val similarity = 1.0 - distance.toDouble() / maxOf(
                    queryCodePoints.size,
                    indexedCodePoints.size,
                )
                if (distance == 0 || distance > maximumDistance || similarity < MIN_SIMILARITY) {
                    return@mapNotNull null
                }
                FuzzyTermMatch(
                    queryToken = queryToken,
                    indexedTerm = candidate.indexedTerm,
                    editDistance = distance,
                    similarity = similarity,
                    trigramOverlap = candidate.trigramOverlap,
                )
            }
            .sortedWith(
                compareBy<FuzzyTermMatch>(FuzzyTermMatch::editDistance)
                    .thenByDescending(FuzzyTermMatch::similarity)
                    .thenByDescending(FuzzyTermMatch::trigramOverlap)
                    .thenBy(FuzzyTermMatch::indexedTerm),
            )
            .toList()
    }

    private fun maximumDistance(longestTokenLength: Int): Int = when (longestTokenLength) {
        in 0..4 -> 1
        in 5..8 -> 2
        else -> 3
    }

    /** Optimal-string-alignment Damerau–Levenshtein distance with adjacent transpositions. */
    private fun damerauLevenshtein(source: IntArray, target: IntArray): Int {
        if (source.isEmpty()) return target.size
        if (target.isEmpty()) return source.size
        val distances = Array(source.size + 1) { IntArray(target.size + 1) }
        for (sourceIndex in 0..source.size) distances[sourceIndex][0] = sourceIndex
        for (targetIndex in 0..target.size) distances[0][targetIndex] = targetIndex

        for (sourceIndex in 1..source.size) {
            for (targetIndex in 1..target.size) {
                val substitutionCost = if (
                    source[sourceIndex - 1] == target[targetIndex - 1]
                ) {
                    0
                } else {
                    1
                }
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
}
