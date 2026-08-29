package com.contentdive.backend.memory

import com.contentdive.api.ContentDive
import com.contentdive.api.ContentDiveConfiguration
import com.contentdive.engine.ContentDiveEngine
import com.contentdive.spi.ExperimentalContentDiveSpi

/**
 * Creates a complete, thread-safe [ContentDive] backed by an isolated in-memory index.
 *
 * The index starts empty, is not shared with other instances, and is discarded by
 * [ContentDive.close]. This backend is intended for tests, demos, previews, and temporary indexes.
 * Standard callers do not need the engine or experimental SPI modules.
 *
 * @param configuration immutable query defaults and limits for this instance.
 * @return an open ContentDive instance that owns its memory backend.
 * @throws IllegalArgumentException if [configuration] was constructed with invalid limits.
 */
@OptIn(ExperimentalContentDiveSpi::class)
public fun createMemoryContentDive(
    configuration: ContentDiveConfiguration = ContentDiveConfiguration(),
): ContentDive = ContentDiveEngine.create(
    backend = MemorySearchBackend(),
    configuration = configuration,
)
