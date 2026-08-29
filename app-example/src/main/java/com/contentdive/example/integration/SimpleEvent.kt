package com.contentdive.example.integration

import androidx.compose.ui.text.AnnotatedString
import com.contentdive.ksp.annotations.ContentDiveDocument
import com.contentdive.ksp.annotations.ContentDiveId
import com.contentdive.ksp.annotations.ContentDiveSubtitle
import com.contentdive.ksp.annotations.ContentDiveText
import com.contentdive.ksp.annotations.ContentDiveTextWeight
import com.contentdive.ksp.annotations.ContentDiveTitle

/** A deliberately flat fixture proving the generated-projector path in the example application. */
@ContentDiveDocument(type = "simple-event")
internal data class SimpleEvent(
    @ContentDiveId val id: String,
    @ContentDiveTitle val title: String,
    @ContentDiveSubtitle val location: String,
    @ContentDiveText(field = "description", weight = ContentDiveTextWeight.BODY)
    val description: String,
    @ContentDiveText(field = "note", weight = ContentDiveTextWeight.HEADING)
    val note: AnnotatedString? = null,
)
