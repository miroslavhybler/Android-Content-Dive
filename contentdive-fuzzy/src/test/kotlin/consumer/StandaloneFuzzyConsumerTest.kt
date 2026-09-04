package consumer

import com.contentdive.fuzzy.FuzzyMatcher
import kotlin.test.Test
import kotlin.test.assertEquals

internal class StandaloneFuzzyConsumerTest {
    private data class Event(
        val id: String,
        val description: String,
    )

    @Test
    fun `consumer using only fuzzy artifact ranks existing domain objects`() {
        val events = listOf(
            Event("E7", "Doors open at 18:00."),
            Event("E42", "Parking is available behind the venue."),
        )

        val ranked = FuzzyMatcher.compile("parkng").rank(events, Event::description)

        assertEquals(listOf("E42"), ranked.map(Event::id))
    }
}
