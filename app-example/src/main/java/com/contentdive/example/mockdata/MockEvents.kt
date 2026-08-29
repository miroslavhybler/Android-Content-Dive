package com.contentdive.example.mockdata

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle

/** Static application data. It deliberately contains no search-specific structure. */
internal object MockEvents {
    private val featured: List<Event> = listOf(
        Event(
            id = "E42",
            title = "Kotlin Meetup",
            location = "Riverside Technology Hall",
            description = """
                Welcome to an evening for Android and Kotlin developers from across the city.

                Tonight's talks cover structured concurrency, API design, and practical Compose architecture.

                Bring a laptop if you would like to join the short coding exercise after the first talk.

                Doors open at 17:30 and the opening talk starts promptly at 18:00.

                Coffee, tea, and light snacks will be served in the foyer throughout the evening.

                The venue entrance is beside the river-facing courtyard and is fully accessible.

                The first session demonstrates dependable coroutine cancellation in production applications.

                The second session explores stable library contracts and careful compatibility evolution.

                A short intermission separates the talks and gives everyone time to meet the speakers.

                The hands-on workshop uses a small Compose application with deliberately simple screens.

                Accessibility reviewers will be available to discuss semantics, focus order, and contrast.

                Talk recordings and example projects will be shared with registered attendees afterward.

                Questions can be submitted during each session or saved for the closing discussion.

                Community organizers will have a desk in the foyer for future topic suggestions.

                A staffed coat room is available beside the main reception desk until the event closes.

                Tram lines two and nine stop on the next block, and the riverside cycle path remains open.

                The formal program finishes at 21:00, followed by optional conversation at a nearby café.

                Parking is available in the underground garage after 18:00; use the east ramp for entry.
            """.trimIndent(),
            revision = 3,
        ),
        Event(
            id = "E73",
            title = "Navigation planning workshop",
            location = "Studio B",
            description = "Turn semantic destinations into application-owned navigation keys.",
            revision = 1,
        ),
        Event(
            id = "E91",
            title = "Serialization compatibility check",
            location = "Online",
            description = "Verify that versioned destination payloads remain readable.",
            revision = 2,
        ),
        Event(
            id = "E104",
            title = "Compose accessibility clinic",
            location = "Design lab",
            description = "Review semantics, focus order, contrast, and scalable typography in real interfaces.",
            revision = 1,
        ),
        Event(
            id = "E118",
            title = "Coroutines testing roundtable",
            location = "Community room",
            description = "Compare deterministic techniques for testing dispatchers, flows, and cancellation.",
            revision = 4,
        ),
        Event(
            id = "E133",
            title = "Venue arrival briefing",
            location = "Old Town Arts Hall",
            description = EventDescription.Styled(
                blocks = listOf(
                    StyledEventBlock(
                        text = buildAnnotatedString {
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                append("Arrival information")
                            }
                        },
                        isHeading = true,
                    ),
                    StyledEventBlock(
                        text = buildAnnotatedString {
                            append("Doors ")
                            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                                append("open at 18:00")
                            }
                            append('.')
                        },
                    ),
                    StyledEventBlock(
                        text = buildAnnotatedString {
                            withStyle(SpanStyle(color = Color(0xff2e7d32))) {
                                append("Parking is available behind the ")
                            }
                            withLink(LinkAnnotation.Url("https://example.test/venue")) {
                                append("venue")
                            }
                            append('.')
                        },
                    ),
                ),
            ),
            revision = 1,
        ),
    )

    /** Simulates a realistically sized API response without introducing search-aware mock data. */
    val all: List<Event> = featured + List(GENERATED_EVENT_COUNT) { index ->
        val number = index + 1
        Event(
            id = "G${number.toString().padStart(3, '0')}",
            title = "Generated community event $number",
            location = "API venue ${(index % 25) + 1}",
            description = """
                This generated event represents record $number from a larger API response.

                Its schedule, capacity marker capacity$number, and arrival notes are searchable immediately after batch indexing.
            """.trimIndent(),
            revision = 1,
        )
    }

    private const val GENERATED_EVENT_COUNT = 500
}
