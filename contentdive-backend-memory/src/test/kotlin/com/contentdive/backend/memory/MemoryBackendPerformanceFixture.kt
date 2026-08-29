package com.contentdive.backend.memory

import com.contentdive.api.SearchItemId
import com.contentdive.api.SearchScope
import com.contentdive.spi.BackendCandidateRequest
import com.contentdive.spi.ExperimentalContentDiveSpi
import com.contentdive.spi.FuzzyTermRequest
import com.contentdive.spi.testing.preparedProjection
import com.contentdive.spi.testing.runSuspendTest
import kotlin.system.measureTimeMillis
import kotlin.test.Test

@OptIn(ExperimentalContentDiveSpi::class)
internal class MemoryBackendPerformanceFixture {
    @Test
    fun `record individual versus batch indexing baseline`() {
        if (System.getenv("CONTENTDIVE_RUN_MEMORY_BENCHMARKS") != "true") return

        val individualBackend = createMemorySearchBackend()
        val batchBackend = createMemorySearchBackend()
        val projections = List(1_000) { itemIndex ->
            preparedProjection(
                id = "item-$itemIndex",
                scope = "benchmark",
                fragments = Array(10) { fragmentIndex ->
                    "fragment-$fragmentIndex" to
                        "benchmark exact$itemIndex prefixable shared fragment $fragmentIndex"
                },
            )
        }

        val individualIndexing = measureTimeMillis {
            runSuspendTest { projections.forEach { individualBackend.replace(it) } }
        }
        val batchIndexing = measureTimeMillis {
            runSuspendTest { batchBackend.replaceAll(projections) }
        }
        val exactSearch = measureTimeMillis {
            runSuspendTest {
                batchBackend.candidates(
                    BackendCandidateRequest(
                        tokens = listOf("exact500"),
                        limit = Int.MAX_VALUE,
                    ),
                )
            }
        }
        val prefixSearch = measureTimeMillis {
            runSuspendTest {
                batchBackend.candidates(
                    BackendCandidateRequest(
                        tokens = listOf("prefix"),
                        includePrefixes = true,
                        limit = Int.MAX_VALUE,
                    ),
                )
            }
        }
        val fuzzyTermLookup = measureTimeMillis {
            runSuspendTest {
                batchBackend.fuzzyTerms(
                    FuzzyTermRequest(
                        normalizedQueryToken = "exat500",
                        trigrams = performanceTrigrams("exat500"),
                        candidateLimit = 32,
                    ),
                )
            }
        }
        val replacement = measureTimeMillis {
            runSuspendTest { batchBackend.replace(projections[500]) }
        }
        val clear = measureTimeMillis {
            runSuspendTest { batchBackend.clear(SearchScope("benchmark")) }
        }

        println(
            "ContentDive memory baseline: " +
                "individualIndex=$individualIndexing ms, batchIndex=$batchIndexing ms, " +
                "exact=$exactSearch ms, prefix=$prefixSearch ms, " +
                "fuzzyTerms=$fuzzyTermLookup ms, " +
                "replace=$replacement ms, clear=$clear ms, " +
                "items=${projections.size}, fragments=${projections.size * 10}, " +
                "chunks=${projections.size * 10}, " +
                "fixture=${SearchItemId("item-500").value}",
        )
        individualBackend.close()
        batchBackend.close()
    }
}

private fun performanceTrigrams(term: String): Set<String> {
    val characters = listOf("^") + term.map(Char::toString) + "$"
    return characters.windowed(3).mapTo(linkedSetOf()) { it.joinToString("") }
}
