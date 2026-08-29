package com.contentdive.example.integration

import androidx.navigation3.runtime.NavKey
import com.contentdive.api.SearchFragment
import com.contentdive.api.SearchFragmentId
import com.contentdive.api.SearchFragmentKind
import com.contentdive.api.SearchItem
import com.contentdive.api.SearchItemId
import com.contentdive.api.SearchMatch
import com.contentdive.api.SearchProjection
import com.contentdive.api.SearchProjector
import com.contentdive.api.SearchScope
import com.contentdive.compose.toSearchFragment
import com.contentdive.example.mockdata.Event
import com.contentdive.example.mockdata.EventDescription
import com.contentdive.navigation3.DestinationResolver
import com.contentdive.navigation3.NavigationPlan
import com.contentdive.serialization.kotlinx.KotlinxAnchorCodec
import com.contentdive.serialization.kotlinx.KotlinxDestinationCodec
import kotlinx.serialization.Serializable

/**
 * The app's complete ContentDive boundary. Domain-to-index mapping, reference schemas, and
 * navigation resolution live here; neither the mock repository nor the Compose UI knows them.
 */
internal class EventContentDiveIntegration {
    private val destinationCodec = KotlinxDestinationCodec(
        type = EVENT_DESTINATION_TYPE,
        version = SCHEMA_VERSION,
        serializer = EventDestination.serializer(),
    )
    private val anchorCodec = KotlinxAnchorCodec(
        type = EVENT_PARAGRAPH_ANCHOR_TYPE,
        version = SCHEMA_VERSION,
        serializer = EventParagraphAnchor.serializer(),
    )
    private val projector: SearchProjector<Event> = EventSearchProjector(
        destinationCodec = destinationCodec,
        anchorCodec = anchorCodec,
    )
    private val resolver = DestinationResolver<EventDetailKey> { destination, anchor ->
        val eventDestination = destinationCodec.decode(destination)
        val paragraph = anchor?.let(anchorCodec::decode)
        require(paragraph == null || paragraph.eventId == eventDestination.eventId) {
            "Event anchor does not belong to destination ${eventDestination.eventId}"
        }
        NavigationPlan(
            listOf(
                EventDetailKey(
                    eventId = eventDestination.eventId,
                    paragraphIndex = paragraph?.paragraphIndex,
                ),
            ),
        )
    }

    val eventScope: SearchScope = EVENT_SCOPE

    fun project(event: Event): SearchProjection = projector.project(event)

    fun project(events: List<Event>): List<SearchProjection> = events.map(projector::project)

    fun descriptionParagraphs(event: Event): List<String> = when (val description = event.description) {
        is EventDescription.Plain -> splitDescription(description.text)
        is EventDescription.Styled -> description.blocks.map { it.text.text }
    }

    fun navigationPlanFor(match: SearchMatch): NavigationPlan<EventDetailKey> =
        resolver.resolve(match.destination, match.anchor)

    private companion object {
        val EVENT_SCOPE = SearchScope("events")
        const val EVENT_DESTINATION_TYPE = "event"
        const val EVENT_PARAGRAPH_ANCHOR_TYPE = "event-description-paragraph"
        const val SCHEMA_VERSION = 1
    }
}

/** Handwritten Event mapping, including the app-specific paragraph chunking policy. */
private class EventSearchProjector(
    private val destinationCodec: KotlinxDestinationCodec<EventDestination>,
    private val anchorCodec: KotlinxAnchorCodec<EventParagraphAnchor>,
) : SearchProjector<Event> {
    override fun project(value: Event): SearchProjection {
        val itemId = SearchItemId("event:${value.id}")
        val item = SearchItem(
            id = itemId,
            scope = EVENT_SCOPE,
            title = value.title,
            destination = destinationCodec.encode(EventDestination(value.id)),
        )
        val fixedFragments = listOf(
            SearchFragment(
                id = SearchFragmentId("title"),
                itemId = itemId,
                scope = EVENT_SCOPE,
                text = value.title,
                kind = SearchFragmentKind.TITLE,
                weight = 2.0,
            ),
            SearchFragment(
                id = SearchFragmentId("location"),
                itemId = itemId,
                scope = EVENT_SCOPE,
                text = value.location,
                kind = SearchFragmentKind.HEADING,
                weight = 1.25,
            ),
        )
        val descriptionFragments = when (val description = value.description) {
            is EventDescription.Plain -> splitDescription(description.text).mapIndexed { index, text ->
                SearchFragment(
                    id = SearchFragmentId("description:$index"),
                    itemId = itemId,
                    scope = EVENT_SCOPE,
                    text = text,
                    kind = SearchFragmentKind.BODY,
                    anchor = paragraphAnchor(value, index),
                )
            }
            is EventDescription.Styled -> description.blocks.mapIndexed { index, block ->
                block.text.toSearchFragment(
                    id = SearchFragmentId("description:$index"),
                    itemId = itemId,
                    scope = EVENT_SCOPE,
                    kind = if (block.isHeading) {
                        SearchFragmentKind.HEADING
                    } else {
                        SearchFragmentKind.BODY
                    },
                    anchor = paragraphAnchor(value, index),
                )
            }
        }
        return SearchProjection(item, fixedFragments + descriptionFragments)
    }

    private fun paragraphAnchor(event: Event, paragraphIndex: Int) = anchorCodec.encode(
        EventParagraphAnchor(
            eventId = event.id,
            paragraphIndex = paragraphIndex,
            revision = event.revision,
        ),
    )

    private companion object {
        val EVENT_SCOPE = SearchScope("events")
    }
}

private fun splitDescription(description: String): List<String> = description
    .trim()
    .split(Regex("\\n\\s*\\n"))
    .map(String::trim)
    .filter(String::isNotEmpty)

@Serializable
private data class EventDestination(val eventId: String)

@Serializable
private data class EventParagraphAnchor(
    val eventId: String,
    val paragraphIndex: Int,
    val revision: Int,
)

/** Application-owned key produced from ContentDive's opaque references. */
@Serializable
internal data class EventDetailKey(
    val eventId: String,
    val paragraphIndex: Int?,
) : NavKey
