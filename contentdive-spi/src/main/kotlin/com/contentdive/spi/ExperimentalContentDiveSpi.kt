package com.contentdive.spi

/**
 * Marks contracts intended for ContentDive engine and backend implementers.
 *
 * These contracts are public only because implementations cross published module boundaries. They
 * are not application APIs, may change between alpha releases, and require an explicit opt-in at
 * error level. Standard backend factories intentionally hide them from normal compile classpaths.
 */
@RequiresOptIn(
    message = "This API is for ContentDive backend implementations and may change between releases.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.TYPEALIAS,
)
public annotation class ExperimentalContentDiveSpi
