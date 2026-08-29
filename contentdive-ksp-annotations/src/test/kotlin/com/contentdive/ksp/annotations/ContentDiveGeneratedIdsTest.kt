package com.contentdive.ksp.annotations

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

internal class ContentDiveGeneratedIdsTest {
    @Test
    fun `item IDs encode source separators without ambiguity`() {
        assertEquals("event:E42", ContentDiveGeneratedIds.itemId("event", "E42").value)
        assertEquals("event:A%3AB", ContentDiveGeneratedIds.itemId("event", "A:B").value)
        assertEquals("event:A%253AB", ContentDiveGeneratedIds.itemId("event", "A%3AB").value)
    }

    @Test
    fun `fragment IDs are deterministic validated field names`() {
        assertEquals("long-description", ContentDiveGeneratedIds.fragmentId("long-description").value)
        assertFailsWith<IllegalArgumentException> {
            ContentDiveGeneratedIds.fragmentId("ambiguous:field")
        }
    }
}
