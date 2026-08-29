package com.contentdive.example.mockdata

import androidx.compose.ui.text.AnnotatedString

/** Application domain model. It deliberately has no ContentDive dependency. */
internal data class Event(
    val id: String,
    val title: String,
    val location: String,
    val description: EventDescription,
    val revision: Int,
) {
    constructor(
        id: String,
        title: String,
        location: String,
        description: String,
        revision: Int,
    ) : this(
        id = id,
        title = title,
        location = location,
        description = EventDescription.Plain(description),
        revision = revision,
    )
}

internal sealed interface EventDescription {
    data class Plain(val text: String) : EventDescription

    data class Styled(val blocks: List<StyledEventBlock>) : EventDescription {
        init {
            require(blocks.isNotEmpty()) { "Styled event description must contain a block" }
        }
    }
}

internal data class StyledEventBlock(
    val text: AnnotatedString,
    val isHeading: Boolean = false,
)
