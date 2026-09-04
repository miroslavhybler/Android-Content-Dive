package com.contentdive.fuzzy

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class FuzzyMatcherTest {
    @Test
    fun `missing character and transposition typos match per token`() {
        assertEquals(FuzzyMatchQuality.FUZZY, FuzzyMatcher.match("parkng", "parking")?.quality)
        assertEquals(FuzzyMatchQuality.FUZZY, FuzzyMatcher.match("pakring", "parking")?.quality)
    }

    @Test
    fun `exact outranks prefix and fuzzy`() {
        val ranked = FuzzyMatcher.compile("parking").rank(
            listOf("parkng", "parkinglot", "parking"),
            textSelector = { it },
        )

        assertEquals(listOf("parking", "parkinglot", "parkng"), ranked)
        assertEquals(FuzzyMatchQuality.EXACT, FuzzyMatcher.match("parking", "parking")?.quality)
        assertEquals(FuzzyMatchQuality.PREFIX, FuzzyMatcher.match("parking", "parkinglot")?.quality)
    }

    @Test
    fun `case and diacritics are folded`() {
        val plain = assertNotNull(FuzzyMatcher.match("prjmy", "prijmy z reklamy"))
        val folded = assertNotNull(FuzzyMatcher.match("PRJMY", "Příjmy z reklamy"))

        assertEquals(plain, folded)
    }

    @Test
    fun `multi-token queries reward complete candidates`() {
        val ranked = FuzzyMatcher.compile("kotlin parkng").rank(
            listOf("Parking only", "Kotlin parking session"),
            textSelector = { it },
        )

        assertEquals(listOf("Kotlin parking session", "Parking only"), ranked)
    }

    @Test
    fun `short typos and unrelated text are rejected`() {
        assertNull(FuzzyMatcher.match("pa", "ba"))
        assertNull(FuzzyMatcher.match("parking", "parliamentary parties"))
        assertNull(FuzzyMatcher.match(" ", "parking"))
    }

    @Test
    fun `compiled and convenience operations are equivalent`() {
        val compiled = FuzzyMatcher.compile("parkng").match("Parking is available")
        val convenience = FuzzyMatcher.match("parkng", "Parking is available")

        assertEquals(convenience, compiled)
        assertTrue(assertNotNull(compiled).score in 0.0..1.0)
    }

    @Test
    fun `ranking keeps source order for equal scores and respects limits`() {
        data class Candidate(val id: Int, val text: String)

        val candidates = listOf(
            Candidate(1, "Parking north"),
            Candidate(2, "Parking south"),
            Candidate(3, "Parking east"),
        )
        val compiled = FuzzyMatcher.compile("parking")

        assertEquals(listOf(1, 2), compiled.rank(candidates, Candidate::text, limit = 2).map { it.id })
        assertEquals(emptyList(), compiled.rank(candidates, Candidate::text, limit = 0))
        assertFailsWith<IllegalArgumentException> {
            compiled.rank(candidates, Candidate::text, limit = -1)
        }
    }

    @Test
    fun `compiled query is safe for concurrent reuse`() {
        val compiled = FuzzyMatcher.compile("kotlin parkng")
        val executor = Executors.newFixedThreadPool(8)
        try {
            val results = executor.invokeAll(
                List(200) {
                    Callable { compiled.match("Kotlin parking session") }
                },
            ).map { it.get() }

            assertEquals(1, results.distinct().size)
            assertEquals(FuzzyMatchQuality.FUZZY, assertNotNull(results.first()).quality)
        } finally {
            executor.shutdownNow()
        }
    }
}
