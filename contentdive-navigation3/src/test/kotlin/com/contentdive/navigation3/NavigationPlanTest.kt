package com.contentdive.navigation3

import androidx.navigation3.runtime.NavKey
import kotlin.test.Test
import kotlin.test.assertFailsWith

internal class NavigationPlanTest {
    @Test
    fun `plan requires at least one key`() {
        assertFailsWith<IllegalArgumentException> {
            NavigationPlan<TestKey>(emptyList())
        }
    }

    private data object TestKey : NavKey
}
