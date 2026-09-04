package com.contentdive.example.integration

import com.contentdive.example.mockdata.MockEvents
import org.junit.Assert.assertEquals
import org.junit.Test

internal class LocalEventFuzzySearchTest {
    @Test
    fun `repository events can be ranked without ContentDive projections`() {
        val matches = rankLocalEvents(MockEvents.all, query = "parkng", limit = 2)

        assertEquals("E42", matches.first().id)
        assertEquals(2, matches.size)
    }
}
