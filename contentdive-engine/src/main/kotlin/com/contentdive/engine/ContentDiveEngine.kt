package com.contentdive.engine

import com.contentdive.api.ContentDive
import com.contentdive.api.ContentDiveConfiguration
import com.contentdive.spi.ExperimentalContentDiveSpi
import com.contentdive.spi.SearchBackend

/**
 * Experimental engine entry point for backend implementers and contract tests.
 *
 * Normal applications should use a complete factory such as `createMemoryContentDive` or
 * `createAppSearchContentDive` so the SPI is absent from their compile classpath.
 */
public object ContentDiveEngine {
    /**
     * Creates a [ContentDive] instance that owns [backend] and applies [configuration].
     *
     * The returned instance closes the backend, propagates cancellation, rejects operations after
     * close, and wraps backend failures while retaining their causes.
     */
    @ExperimentalContentDiveSpi
    public fun create(
        backend: SearchBackend,
        configuration: ContentDiveConfiguration = ContentDiveConfiguration(),
    ): ContentDive = DefaultContentDive(backend, configuration)
}
