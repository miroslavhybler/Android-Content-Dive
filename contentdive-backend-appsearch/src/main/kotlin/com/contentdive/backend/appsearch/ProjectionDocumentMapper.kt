package com.contentdive.backend.appsearch

import androidx.appsearch.app.GenericDocument
import com.contentdive.api.AnchorRef
import com.contentdive.api.DestinationRef
import com.contentdive.api.SearchFragment
import com.contentdive.api.SearchFragmentId
import com.contentdive.api.SearchFragmentKind
import com.contentdive.api.SearchItem
import com.contentdive.api.SearchItemId
import com.contentdive.api.SearchScope
import com.contentdive.spi.ExperimentalContentDiveSpi
import com.contentdive.spi.PreparedProjection
import com.contentdive.spi.PreparedTextChunk
import com.contentdive.spi.PreparedToken

@OptIn(ExperimentalContentDiveSpi::class)
internal object ProjectionDocumentMapper {
    fun toDocument(projection: PreparedProjection): GenericDocument {
        validate(projection)
        val scope = projection.item.scope.value
        val fragmentsById = projection.fragments.associateBy { it.id }
        val searchableTokens = projection.chunks
            .flatMap { chunk -> chunk.tokens.map(PreparedToken::value) }
            .distinct()
            .sorted()
        val encodedTrigrams = searchableTokens.asSequence()
            .flatMap { characterTrigrams(it).asSequence() }
            .map(::encodeTrigram)
            .distinct()
            .sorted()
            .toList()
        return GenericDocument.Builder<GenericDocument.Builder<*>>(
            scope,
            projection.item.id.value,
            AppSearchSchemaModel.PROJECTION_TYPE,
        )
            .setPropertyString(AppSearchSchemaModel.TITLE, projection.item.title)
            .apply {
                projection.item.subtitle?.let { subtitle ->
                    setPropertyString(AppSearchSchemaModel.SUBTITLE, subtitle)
                }
            }
            .setPropertyDocument(
                AppSearchSchemaModel.DESTINATION,
                referenceDocument(scope, "destination", projection.item.destination),
            )
            .setPropertyDocument(
                AppSearchSchemaModel.FRAGMENTS,
                *projection.fragments.map { fragmentDocument(scope, it) }.toTypedArray(),
            )
            .setPropertyDocument(
                AppSearchSchemaModel.CHUNKS,
                *projection.chunks.map { chunk ->
                    chunkDocument(
                        scope = scope,
                        chunk = chunk,
                        sourceWeight = checkNotNull(fragmentsById[chunk.sourceFragmentId]).weight,
                    )
                }.toTypedArray(),
            )
            .setPropertyString(
                AppSearchSchemaModel.SEARCHABLE_TOKENS,
                *searchableTokens.toTypedArray(),
            )
            .setPropertyString(
                AppSearchSchemaModel.FUZZY_TRIGRAMS,
                *encodedTrigrams.toTypedArray(),
            )
            .build()
    }

    fun fromDocument(document: GenericDocument): PreparedProjection {
        require(document.schemaType == AppSearchSchemaModel.PROJECTION_TYPE) {
            "Unexpected AppSearch schema '${document.schemaType}'"
        }
        val itemId = SearchItemId(document.id)
        val scope = SearchScope(document.namespace)
        val item = SearchItem(
            id = itemId,
            scope = scope,
            title = document.requiredString(AppSearchSchemaModel.TITLE),
            destination = destinationFromDocument(
                document.requiredDocument(AppSearchSchemaModel.DESTINATION),
            ),
            subtitle = document.getPropertyString(AppSearchSchemaModel.SUBTITLE),
        )
        val fragments = document.getPropertyDocumentArray(AppSearchSchemaModel.FRAGMENTS)
            .orEmpty()
            .map { fragmentDocument -> fragmentFromDocument(fragmentDocument, itemId, scope) }
        val chunks = document.getPropertyDocumentArray(AppSearchSchemaModel.CHUNKS)
            .orEmpty()
            .map(::chunkFromDocument)
        return PreparedProjection(item, fragments, chunks)
    }

    fun fragmentCount(document: GenericDocument): Int =
        document.getPropertyDocumentArray(AppSearchSchemaModel.FRAGMENTS)?.size ?: 0

    fun searchableTokens(document: GenericDocument): Set<String> =
        document.getPropertyStringArray(AppSearchSchemaModel.SEARCHABLE_TOKENS)
            .orEmpty()
            .toCollection(linkedSetOf())

    private fun fragmentDocument(scope: String, fragment: SearchFragment): GenericDocument =
        GenericDocument.Builder<GenericDocument.Builder<*>>(
            scope,
            "fragment:${fragment.id.value}",
            AppSearchSchemaModel.FRAGMENT_TYPE,
        )
            .setPropertyString(AppSearchSchemaModel.FRAGMENT_ID, fragment.id.value)
            .setPropertyString(AppSearchSchemaModel.FRAGMENT_TEXT, fragment.text)
            .setPropertyString(AppSearchSchemaModel.FRAGMENT_KIND, fragment.kind.name)
            .setPropertyDouble(AppSearchSchemaModel.FRAGMENT_WEIGHT, fragment.weight)
            .apply {
                fragment.anchor?.let { anchor ->
                    setPropertyDocument(
                        AppSearchSchemaModel.ANCHOR,
                        referenceDocument(scope, "fragment-anchor:${fragment.id.value}", anchor),
                    )
                }
            }
            .build()

    private fun chunkDocument(
        scope: String,
        chunk: PreparedTextChunk,
        sourceWeight: Double,
    ): GenericDocument = GenericDocument.Builder<GenericDocument.Builder<*>>(
        scope,
        "chunk:${chunk.id}",
        AppSearchSchemaModel.CHUNK_TYPE,
    )
        .setPropertyString(AppSearchSchemaModel.CHUNK_ID, chunk.id)
        .setPropertyString(AppSearchSchemaModel.SOURCE_FRAGMENT_ID, chunk.sourceFragmentId.value)
        .setPropertyString(AppSearchSchemaModel.ORIGINAL_TEXT, chunk.originalText)
        .setPropertyString(AppSearchSchemaModel.NORMALIZED_TEXT, chunk.normalizedText)
        .setPropertyString(
            AppSearchSchemaModel.TOKEN_VALUES,
            *chunk.tokens.map { it.value }.toTypedArray(),
        )
        .setPropertyLong(
            AppSearchSchemaModel.TOKEN_STARTS,
            *chunk.tokens.map { it.start.toLong() }.toLongArray(),
        )
        .setPropertyLong(
            AppSearchSchemaModel.TOKEN_ENDS,
            *chunk.tokens.map { it.end.toLong() }.toLongArray(),
        )
        .setPropertyLong(AppSearchSchemaModel.SOURCE_START, chunk.sourceStart.toLong())
        .setPropertyLong(AppSearchSchemaModel.SOURCE_END, chunk.sourceEnd.toLong())
        .setPropertyLong(AppSearchSchemaModel.ORDINAL, chunk.ordinal.toLong())
        .setPropertyDouble(AppSearchSchemaModel.SOURCE_FRAGMENT_WEIGHT, sourceWeight)
        .apply {
            chunk.anchor?.let { anchor ->
                setPropertyDocument(
                    AppSearchSchemaModel.ANCHOR,
                    referenceDocument(scope, "chunk-anchor:${chunk.id}", anchor),
                )
            }
        }
        .build()

    private fun referenceDocument(
        scope: String,
        id: String,
        reference: DestinationRef,
    ): GenericDocument = GenericDocument.Builder<GenericDocument.Builder<*>>(
        scope,
        id,
        AppSearchSchemaModel.REFERENCE_TYPE,
    )
        .setPropertyString(AppSearchSchemaModel.REFERENCE_TYPE_NAME, reference.type)
        .setPropertyLong(AppSearchSchemaModel.REFERENCE_VERSION, reference.version.toLong())
        .setPropertyString(AppSearchSchemaModel.REFERENCE_PAYLOAD, reference.payload)
        .build()

    private fun referenceDocument(
        scope: String,
        id: String,
        reference: AnchorRef,
    ): GenericDocument = GenericDocument.Builder<GenericDocument.Builder<*>>(
        scope,
        id,
        AppSearchSchemaModel.REFERENCE_TYPE,
    )
        .setPropertyString(AppSearchSchemaModel.REFERENCE_TYPE_NAME, reference.type)
        .setPropertyLong(AppSearchSchemaModel.REFERENCE_VERSION, reference.version.toLong())
        .setPropertyString(AppSearchSchemaModel.REFERENCE_PAYLOAD, reference.payload)
        .build()

    private fun fragmentFromDocument(
        document: GenericDocument,
        itemId: SearchItemId,
        scope: SearchScope,
    ): SearchFragment = SearchFragment(
        id = SearchFragmentId(document.requiredString(AppSearchSchemaModel.FRAGMENT_ID)),
        itemId = itemId,
        scope = scope,
        text = document.requiredString(AppSearchSchemaModel.FRAGMENT_TEXT),
        kind = SearchFragmentKind.valueOf(
            document.requiredString(AppSearchSchemaModel.FRAGMENT_KIND),
        ),
        weight = document.getPropertyDouble(AppSearchSchemaModel.FRAGMENT_WEIGHT),
        anchor = document.getPropertyDocument(AppSearchSchemaModel.ANCHOR)?.let(::anchorFromDocument),
    )

    private fun chunkFromDocument(document: GenericDocument): PreparedTextChunk {
        val values = document.getPropertyStringArray(AppSearchSchemaModel.TOKEN_VALUES).orEmpty()
        val starts = document.getPropertyLongArray(AppSearchSchemaModel.TOKEN_STARTS) ?: LongArray(0)
        val ends = document.getPropertyLongArray(AppSearchSchemaModel.TOKEN_ENDS) ?: LongArray(0)
        require(values.size == starts.size && values.size == ends.size) {
            "Persisted token values and ranges have different sizes"
        }
        return PreparedTextChunk(
            id = document.requiredString(AppSearchSchemaModel.CHUNK_ID),
            sourceFragmentId = SearchFragmentId(
                document.requiredString(AppSearchSchemaModel.SOURCE_FRAGMENT_ID),
            ),
            originalText = document.requiredString(AppSearchSchemaModel.ORIGINAL_TEXT),
            normalizedText = document.requiredString(AppSearchSchemaModel.NORMALIZED_TEXT),
            tokens = values.indices.map { index ->
                PreparedToken(values[index], starts[index].toIntExact(), ends[index].toIntExact())
            },
            sourceStart = document.getPropertyLong(AppSearchSchemaModel.SOURCE_START).toIntExact(),
            sourceEnd = document.getPropertyLong(AppSearchSchemaModel.SOURCE_END).toIntExact(),
            ordinal = document.getPropertyLong(AppSearchSchemaModel.ORDINAL).toIntExact(),
            anchor = document.getPropertyDocument(AppSearchSchemaModel.ANCHOR)?.let(::anchorFromDocument),
        )
    }

    private fun destinationFromDocument(document: GenericDocument): DestinationRef = DestinationRef(
        type = document.requiredString(AppSearchSchemaModel.REFERENCE_TYPE_NAME),
        version = document.getPropertyLong(AppSearchSchemaModel.REFERENCE_VERSION).toIntExact(),
        payload = document.requiredString(AppSearchSchemaModel.REFERENCE_PAYLOAD),
    )

    private fun anchorFromDocument(document: GenericDocument): AnchorRef = AnchorRef(
        type = document.requiredString(AppSearchSchemaModel.REFERENCE_TYPE_NAME),
        version = document.getPropertyLong(AppSearchSchemaModel.REFERENCE_VERSION).toIntExact(),
        payload = document.requiredString(AppSearchSchemaModel.REFERENCE_PAYLOAD),
    )

    private fun validate(projection: PreparedProjection) {
        require(projection.fragments.isNotEmpty()) { "PreparedProjection fragments must not be empty" }
        require(projection.chunks.isNotEmpty()) { "PreparedProjection chunks must not be empty" }
        val fragmentsById = projection.fragments.associateBy { it.id }
        require(fragmentsById.size == projection.fragments.size) {
            "PreparedProjection fragment IDs must be unique"
        }
        require(projection.chunks.map { it.id }.toSet().size == projection.chunks.size) {
            "Prepared chunk IDs must be unique"
        }
        projection.fragments.forEach { fragment ->
            require(fragment.itemId == projection.item.id && fragment.scope == projection.item.scope) {
                "Prepared fragment belongs to another item or scope"
            }
        }
        projection.chunks.forEach { chunk ->
            val source = requireNotNull(fragmentsById[chunk.sourceFragmentId]) {
                "Prepared chunk '${chunk.id}' has no source fragment"
            }
            require(chunk.ordinal >= 0) { "Prepared chunk ordinal must not be negative" }
            require(chunk.sourceStart >= 0 && chunk.sourceEnd > chunk.sourceStart) {
                "Prepared chunk source range must be non-empty"
            }
            require(chunk.sourceEnd <= source.text.length) {
                "Prepared chunk source range exceeds its fragment"
            }
            require(chunk.originalText == source.text.substring(chunk.sourceStart, chunk.sourceEnd)) {
                "Prepared chunk original text does not match its source range"
            }
            require(chunk.anchor == source.anchor) { "Prepared chunk did not inherit its source anchor" }
            require(chunk.tokens.isNotEmpty()) { "Prepared chunk tokens must not be empty" }
        }
        require(projection.chunks.map { it.sourceFragmentId }.toSet() == fragmentsById.keys) {
            "Every prepared fragment must have at least one chunk"
        }
    }

    private fun characterTrigrams(term: String): Set<String> {
        val characters = buildList {
            add("^")
            var offset = 0
            while (offset < term.length) {
                val next = offset + Character.charCount(term.codePointAt(offset))
                add(term.substring(offset, next))
                offset = next
            }
            add("$")
        }
        return characters.windowed(3).mapTo(linkedSetOf()) { it.joinToString("") }
    }
}

private fun GenericDocument.requiredString(property: String): String =
    requireNotNull(getPropertyString(property)) { "Missing persisted property '$property'" }

private fun GenericDocument.requiredDocument(property: String): GenericDocument =
    requireNotNull(getPropertyDocument(property)) { "Missing persisted property '$property'" }

private fun Long.toIntExact(): Int {
    require(this in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
        "Persisted integer value is outside Int range"
    }
    return toInt()
}
