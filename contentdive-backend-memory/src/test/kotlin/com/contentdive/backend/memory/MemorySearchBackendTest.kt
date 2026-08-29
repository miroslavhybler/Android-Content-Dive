package com.contentdive.backend.memory

import com.contentdive.spi.BackendCandidateRequest
import com.contentdive.spi.ExperimentalContentDiveSpi
import com.contentdive.spi.SearchBackend
import com.contentdive.spi.testing.SearchBackendContract
import com.contentdive.spi.testing.preparedProjection
import com.contentdive.spi.testing.runSuspendTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse

@OptIn(ExperimentalContentDiveSpi::class)
internal class MemorySearchBackendTest : SearchBackendContract() {
    override fun createBackend(): SearchBackend = createMemorySearchBackend()

    @Test
    fun `search and replacement never expose a mixed projection`() {
        val backend = createMemorySearchBackend()
        val oldProjection = versionedProjection("old")
        val newProjection = versionedProjection("new")
        runSuspendTest { backend.replace(oldProjection) }
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(5)

        try {
            val writer = pool.submit {
                start.await()
                repeat(500) { iteration ->
                    runSuspendTest {
                        backend.replace(if (iteration % 2 == 0) newProjection else oldProjection)
                    }
                }
            }
            val readers = List(4) {
                pool.submit {
                    start.await()
                    repeat(500) {
                        val versions = runSuspendTest {
                            backend.candidates(
                                BackendCandidateRequest(
                                    tokens = listOf("old", "new"),
                                    includePrefixes = false,
                                    limit = Int.MAX_VALUE,
                                ),
                            )
                        }.map { it.chunk.tokens.first().value }.toSet()
                        assertFalse(versions.containsAll(setOf("old", "new")))
                    }
                }
            }

            start.countDown()
            writer.get(20, TimeUnit.SECONDS)
            readers.forEach { it.get(20, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
            backend.close()
        }
    }

    private fun versionedProjection(version: String) = preparedProjection(
        id = "E1",
        scope = "events",
        fragments = Array(10) { index -> "fragment-$index" to "$version value-$index" },
    )
}
