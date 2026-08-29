package com.contentdive.api

/**
 * Signals a backend, storage, initialization, retrieval, or resource-release failure.
 *
 * The original exception is preserved as [cause]. Cancellation is never wrapped in this type, and
 * per-item batch failures are represented by [SearchBatchFailure] instead.
 */
public class ContentDiveException(
    message: String,
    cause: Throwable,
) : RuntimeException(message, cause)

/**
 * Indicates that indexing or searching was attempted after [ContentDive.close].
 *
 * Repeated calls to `close()` itself remain safe and do not throw this exception.
 */
public class ContentDiveLifecycleException(
    message: String = "ContentDive is closed",
) : IllegalStateException(message)
