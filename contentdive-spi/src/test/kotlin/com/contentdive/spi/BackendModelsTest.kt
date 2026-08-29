package com.contentdive.spi

import com.contentdive.api.SearchBatchItemId
import com.contentdive.api.SearchItemId
import com.contentdive.api.SearchScope
import kotlin.test.Test
import kotlin.test.assertFailsWith

@OptIn(ExperimentalContentDiveSpi::class)
internal class BackendModelsTest {
    @Test
    fun `candidate request requires a positive limit`() {
        assertFailsWith<IllegalArgumentException> {
            BackendCandidateRequest(tokens = listOf("query"), limit = 0)
        }
    }

    @Test
    fun `fuzzy term request requires trigrams and a positive limit`() {
        assertFailsWith<IllegalArgumentException> {
            FuzzyTermRequest("parking", emptySet(), candidateLimit = 10)
        }
        assertFailsWith<IllegalArgumentException> {
            FuzzyTermRequest("parking", setOf("par"), candidateLimit = 0)
        }
    }

    @Test
    fun `batch write result keeps affected counts consistent with successes`() {
        val item = SearchBatchItemId(SearchScope("events"), SearchItemId("E1"))

        assertFailsWith<IllegalArgumentException> {
            BackendBatchWriteResult(
                successfulItems = listOf(item),
                failedItems = emptyList(),
                affectedItems = 2,
                affectedFragments = 1,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            BackendBatchWriteResult(
                successfulItems = listOf(item),
                failedItems = listOf(BackendBatchFailure(item, "failed")),
                affectedItems = 0,
                affectedFragments = 0,
            )
        }
    }
}
