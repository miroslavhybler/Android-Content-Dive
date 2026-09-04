package com.contentdive.example.integration

import com.contentdive.example.mockdata.Event
import com.contentdive.example.mockdata.EventDescription
import com.contentdive.fuzzy.FuzzyMatcher

/** Standalone scan over repository-owned events; no ContentDive projection or destination is used. */
internal fun rankLocalEvents(
    events: List<Event>,
    query: String,
    limit: Int = 20,
): List<Event> = FuzzyMatcher.compile(query).rank(
    candidates = events,
    textSelector = { event ->
        when (val description = event.description) {
            is EventDescription.Plain -> description.text
            is EventDescription.Styled -> description.blocks.joinToString("\n") { block ->
                block.text.text
            }
        }
    },
    limit = limit,
)
