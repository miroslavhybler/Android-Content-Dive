package com.contentdive.backend.appsearch

import androidx.appsearch.app.AppSearchSchema
import androidx.appsearch.app.SetSchemaRequest

internal object AppSearchSchemaModel {
    const val PROJECTION_TYPE = "ContentDiveProjectionV2"
    const val FRAGMENT_TYPE = "ContentDiveFragmentV2"
    const val CHUNK_TYPE = "ContentDiveChunkV2"
    const val REFERENCE_TYPE = "ContentDiveReferenceV2"

    const val TITLE = "title"
    const val SUBTITLE = "subtitle"
    const val DESTINATION = "destination"
    const val FRAGMENTS = "fragments"
    const val CHUNKS = "chunks"
    const val SEARCHABLE_TOKENS = "searchableTokens"
    const val FUZZY_TRIGRAMS = "fuzzyTrigrams"

    const val REFERENCE_TYPE_NAME = "type"
    const val REFERENCE_VERSION = "version"
    const val REFERENCE_PAYLOAD = "payload"

    const val FRAGMENT_ID = "fragmentId"
    const val FRAGMENT_TEXT = "text"
    const val FRAGMENT_KIND = "kind"
    const val FRAGMENT_WEIGHT = "weight"
    const val ANCHOR = "anchor"

    const val CHUNK_ID = "chunkId"
    const val SOURCE_FRAGMENT_ID = "sourceFragmentId"
    const val ORIGINAL_TEXT = "originalText"
    const val NORMALIZED_TEXT = "normalizedText"
    const val TOKEN_VALUES = "tokenValues"
    const val TOKEN_STARTS = "tokenStarts"
    const val TOKEN_ENDS = "tokenEnds"
    const val SOURCE_START = "sourceStart"
    const val SOURCE_END = "sourceEnd"
    const val ORDINAL = "ordinal"
    const val SOURCE_FRAGMENT_WEIGHT = "sourceFragmentWeight"

    val setSchemaRequest: SetSchemaRequest = SetSchemaRequest.Builder()
        .addSchemas(referenceSchema(), fragmentSchema(), chunkSchema(), projectionSchema())
        .setForceOverride(true)
        .build()

    private fun referenceSchema(): AppSearchSchema = AppSearchSchema.Builder(REFERENCE_TYPE)
        .addProperty(requiredString(REFERENCE_TYPE_NAME))
        .addProperty(requiredLong(REFERENCE_VERSION))
        .addProperty(requiredString(REFERENCE_PAYLOAD))
        .build()

    private fun fragmentSchema(): AppSearchSchema = AppSearchSchema.Builder(FRAGMENT_TYPE)
        .addProperty(requiredString(FRAGMENT_ID))
        .addProperty(requiredString(FRAGMENT_TEXT))
        .addProperty(requiredString(FRAGMENT_KIND))
        .addProperty(requiredDouble(FRAGMENT_WEIGHT))
        .addProperty(optionalDocument(ANCHOR, REFERENCE_TYPE))
        .build()

    private fun chunkSchema(): AppSearchSchema = AppSearchSchema.Builder(CHUNK_TYPE)
        .addProperty(requiredString(CHUNK_ID))
        .addProperty(requiredString(SOURCE_FRAGMENT_ID))
        .addProperty(requiredString(ORIGINAL_TEXT))
        .addProperty(requiredString(NORMALIZED_TEXT))
        .addProperty(repeatedString(TOKEN_VALUES))
        .addProperty(repeatedLong(TOKEN_STARTS))
        .addProperty(repeatedLong(TOKEN_ENDS))
        .addProperty(requiredLong(SOURCE_START))
        .addProperty(requiredLong(SOURCE_END))
        .addProperty(requiredLong(ORDINAL))
        .addProperty(requiredDouble(SOURCE_FRAGMENT_WEIGHT))
        .addProperty(optionalDocument(ANCHOR, REFERENCE_TYPE))
        .build()

    private fun projectionSchema(): AppSearchSchema = AppSearchSchema.Builder(PROJECTION_TYPE)
        .addProperty(requiredString(TITLE))
        .addProperty(optionalString(SUBTITLE))
        .addProperty(requiredDocument(DESTINATION, REFERENCE_TYPE))
        .addProperty(repeatedDocument(FRAGMENTS, FRAGMENT_TYPE))
        .addProperty(repeatedDocument(CHUNKS, CHUNK_TYPE))
        .addProperty(indexedRepeatedString(SEARCHABLE_TOKENS, prefixes = true))
        .addProperty(indexedRepeatedString(FUZZY_TRIGRAMS, prefixes = false))
        .build()

    private fun requiredString(name: String) = AppSearchSchema.StringPropertyConfig.Builder(name)
        .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_REQUIRED)
        .build()

    private fun optionalString(name: String) = AppSearchSchema.StringPropertyConfig.Builder(name)
        .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
        .build()

    private fun repeatedString(name: String) = AppSearchSchema.StringPropertyConfig.Builder(name)
        .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_REPEATED)
        .build()

    private fun indexedRepeatedString(
        name: String,
        prefixes: Boolean,
    ) = AppSearchSchema.StringPropertyConfig.Builder(name)
        .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_REPEATED)
        .setTokenizerType(AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_PLAIN)
        .setIndexingType(
            if (prefixes) {
                AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_PREFIXES
            } else {
                AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_EXACT_TERMS
            },
        )
        .build()

    private fun requiredLong(name: String) = AppSearchSchema.LongPropertyConfig.Builder(name)
        .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_REQUIRED)
        .build()

    private fun repeatedLong(name: String) = AppSearchSchema.LongPropertyConfig.Builder(name)
        .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_REPEATED)
        .build()

    private fun requiredDouble(name: String) = AppSearchSchema.DoublePropertyConfig.Builder(name)
        .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_REQUIRED)
        .build()

    private fun requiredDocument(name: String, type: String) =
        AppSearchSchema.DocumentPropertyConfig.Builder(name, type)
            .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_REQUIRED)
            .setShouldIndexNestedProperties(false)
            .build()

    private fun optionalDocument(name: String, type: String) =
        AppSearchSchema.DocumentPropertyConfig.Builder(name, type)
            .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
            .setShouldIndexNestedProperties(false)
            .build()

    private fun repeatedDocument(name: String, type: String) =
        AppSearchSchema.DocumentPropertyConfig.Builder(name, type)
            .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_REPEATED)
            .setShouldIndexNestedProperties(false)
            .build()
}
