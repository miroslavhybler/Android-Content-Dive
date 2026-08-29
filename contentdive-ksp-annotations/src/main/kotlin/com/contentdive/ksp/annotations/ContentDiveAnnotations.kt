package com.contentdive.ksp.annotations

/**
 * Marks a straightforward domain class for compile-time `SearchProjector<T>` generation.
 *
 * The class must expose exactly one [ContentDiveId] and [ContentDiveTitle]. Generated projectors
 * accept application-owned scope and destination providers; they use no reflection and generate no
 * anchors. Lists, nested traversal, and structured blocks require a manual projector.
 *
 * @property type stable non-blank document type used by [ContentDiveGeneratedIds.itemId]. It must
 * begin with a letter and contain only letters, digits, `.`, `_`, or `-`.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
@MustBeDocumented
public annotation class ContentDiveDocument(public val type: String)

/**
 * Marks the single stable, non-null `String` source identifier of a generated document.
 *
 * The value is combined with [ContentDiveDocument.type] through
 * [ContentDiveGeneratedIds.itemId].
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
@MustBeDocumented
public annotation class ContentDiveId

/**
 * Marks the single required non-null display title.
 *
 * The generated projector also creates the first searchable fragment from this property using the
 * title ranking role.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
@MustBeDocumented
public annotation class ContentDiveTitle

/**
 * Marks optional secondary result text.
 *
 * Subtitle is display metadata only. Apply [ContentDiveText] as well when the same property should
 * be searchable.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
@MustBeDocumented
public annotation class ContentDiveSubtitle

/**
 * Marks a `String`, nullable `String`, Compose `AnnotatedString`, or nullable `AnnotatedString`
 * property as one searchable fragment.
 *
 * Null and blank optional values are skipped. Annotated text is reduced to visible text through the
 * `contentdive-compose` adapter. Lists and block-specific anchors remain manual-projector concerns.
 *
 * @property field stable generated fragment field name. It must begin with a letter and contain
 * only letters, digits, `.`, `_`, or `-`, and must not duplicate another generated field.
 * @property weight fixed semantic role and weight assigned to the generated fragment.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
@MustBeDocumented
public annotation class ContentDiveText(
    public val field: String,
    public val weight: ContentDiveTextWeight = ContentDiveTextWeight.BODY,
)

/** Fixed v1 fragment roles available to generated searchable properties. */
public enum class ContentDiveTextWeight {
    /** Ordinary body content. */
    BODY,

    /** Prominent section or heading text. */
    HEADING,

    /** Title-strength text; use sparingly for a field that behaves like another title. */
    TITLE,
}
