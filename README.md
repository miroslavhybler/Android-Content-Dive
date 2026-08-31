# ContentDive

## What ContentDive is

ContentDive provides on-device search for content owned by an Android application. The application
decides what to index and how a result maps back to its domain model.

Each result preserves a `DestinationRef` describing the entity to open and may preserve an
`AnchorRef` describing a paragraph or block inside it. Search supports exact tokens, token prefixes,
and common-character typo correction through fuzzy matching. Results are grouped by domain item, so
internal long-text chunks never appear as duplicate application results.

ContentDive is a derived index. The application's repository remains the source of truth.

## Supported inputs

ContentDive accepts visible text in two forms:

- Kotlin `String` through the core API.
- Compose `AnnotatedString` through `contentdive-compose`; only `AnnotatedString.text` is indexed.

Styles, links, and annotations are not retained. HTML, Markdown, and every other input format are
unsupported; extract their visible text in application code before creating a search fragment.

## Core flow

```text
Domain model
→ SearchProjector
→ SearchProjection
→ ContentDive index
→ SearchResult
→ DestinationRef
→ application navigation
```

A projector is the application boundary: it converts authoritative domain data into a complete,
replaceable search snapshot. A result returns enough metadata to navigate, after which the
destination screen reloads current data from its repository.

## Module overview

Every library module is published separately with the same group and version. Application code
normally uses the first eight modules; the engine and SPI artifacts are published so backend
dependencies remain resolvable, but are not part of the recommended application setup.

| Module | Audience | Purpose |
| --- | --- | --- |
| [`contentdive-api`](contentdive-api/) | Applications | Platform-neutral models and indexing/search contracts. Usually received transitively from a backend. |
| [`contentdive-backend-memory`](contentdive-backend-memory/) | Applications | Complete factory for an isolated temporary in-memory index. |
| [`contentdive-backend-appsearch`](contentdive-backend-appsearch/) | Android applications | Complete factory for a persistent app-private AppSearch LocalStorage index. |
| [`contentdive-compose`](contentdive-compose/) | Compose applications | Converts `AnnotatedString` visible text into ordinary `SearchFragment` values. |
| [`contentdive-navigation3`](contentdive-navigation3/) | Navigation 3 applications | Resolves opaque destinations and anchors into application-owned `NavKey` plans. |
| [`contentdive-serialization-kotlinx`](contentdive-serialization-kotlinx/) | Applications | Optional kotlinx.serialization JSON codecs for destination and anchor payloads. |
| [`contentdive-ksp-annotations`](contentdive-ksp-annotations/) | Applications using generation | Annotations and canonical IDs for simple generated projectors. |
| [`contentdive-ksp-processor`](contentdive-ksp-processor/) | Build-time only | Isolating KSP projector generator; place it on `ksp`, never the runtime classpath. |
| [`contentdive-engine`](contentdive-engine/) | Backend implementers | Engine implementation and an experimental raw-backend entry point. Normal applications do not depend on it directly. |
| [`contentdive-spi`](contentdive-spi/) | Backend implementers | Experimental prepared-chunk, candidate, mutation, and backend contracts guarded by `@ExperimentalContentDiveSpi`. |

The first eight modules expose normal consumer-facing or build-time APIs. Direct engine/SPI usage is
only for implementing or testing a backend and may change between alpha releases.

## Installation

Development artifacts are published to Maven Local under
`com.gihub.miroslavhybler:<artifact>:DEV`. Add Maven Local before remote repositories:

```kotlin
dependencyResolutionManagement {
    repositories {
        mavenLocal()
        google()
        mavenCentral()
    }
}
```

Choose exactly one backend for normal application setup.

Memory backend:

```kotlin
implementation(
    "com.gihub.miroslavhybler:contentdive-backend-memory:DEV",
)
```

Persistent AppSearch backend:

```kotlin
implementation(
    "com.gihub.miroslavhybler:contentdive-backend-appsearch:DEV",
)
```

Add only the integrations the application uses:

```kotlin
implementation("com.gihub.miroslavhybler:contentdive-compose:DEV")
implementation("com.gihub.miroslavhybler:contentdive-navigation3:DEV")
implementation("com.gihub.miroslavhybler:contentdive-serialization-kotlinx:DEV")
```

For generated projectors, apply KSP and keep the processor off runtime configurations:

```kotlin
plugins {
    id("com.google.devtools.ksp") version "<ksp-version>"
}

dependencies {
    implementation(
        "com.gihub.miroslavhybler:contentdive-ksp-annotations:DEV",
    )
    ksp(
        "com.gihub.miroslavhybler:contentdive-ksp-processor:DEV",
    )
}
```

`contentdive-compose` is also required when an annotated model contains `AnnotatedString`.

## Minimal working example

This example uses a short manual projector and the memory backend. Indexing and searching are
suspending operations, so call `runSearchDemo` from an application coroutine.

```kotlin
data class Event(val id: String, val title: String, val description: String)

val eventScope = SearchScope("events")

val eventProjector = SearchProjector<Event> { event ->
    val itemId = SearchItemId("event:${event.id}")
    SearchProjection(
        item = SearchItem(
            id = itemId,
            scope = eventScope,
            title = event.title,
            destination = DestinationRef("event", 1, event.id),
        ),
        fragments = listOf(
            SearchFragment(
                id = SearchFragmentId("title"),
                itemId = itemId,
                scope = eventScope,
                text = event.title,
                kind = SearchFragmentKind.TITLE,
            ),
            SearchFragment(
                id = SearchFragmentId("description"),
                itemId = itemId,
                scope = eventScope,
                text = event.description,
            ),
        ),
    )
}

suspend fun runSearchDemo(openEvent: (eventId: String) -> Unit) {
    val contentDive = createMemoryContentDive()
    try {
        val event = Event("E42", "Kotlin Meetup", "Parking is available after 18:00.")
        contentDive.replace(eventProjector.project(event))

        val match = contentDive.search(SearchQuery("parkng")).matches.single()
        check(match.destination.type == "event" && match.destination.version == 1)
        openEvent(match.destination.payload)
    } finally {
        contentDive.close()
    }
}
```

For persistent Android usage, only initialization changes:

```kotlin
val contentDive = createAppSearchContentDive(
    context = applicationContext,
    databaseName = "contentdive-events",
)
```

The projector, projection, indexing, search, destination handling, and lifecycle stay the same.

## Manual versus KSP projectors

Use KSP for flat models with one ID, one title, an optional subtitle, and searchable `String` or
`AnnotatedString` properties:

```kotlin
@ContentDiveDocument(type = "event")
data class SimpleEvent(
    @ContentDiveId val id: String,
    @ContentDiveTitle val title: String,
    @ContentDiveSubtitle val location: String?,
    @ContentDiveText(field = "description") val description: String,
)

val projector = SimpleEventContentDiveProjector(
    scopeProvider = { SearchScope("events") },
    destinationProvider = { event -> DestinationRef("event", 1, event.id) },
)
```

Generated and manual projectors both produce ordinary `SearchProjection` values. Use a manual
projector when content contains semantic block lists, each paragraph needs its own anchor, extraction
requires application-specific logic, or the model shape is outside the bounded KSP feature set.

The complete generated and handwritten paths are both exercised in
[`app-example`](app-example/src/main/java/com/contentdive/example/integration/).

## Backend choice

Use `createMemoryContentDive()` for tests, demos, previews, and temporary indexes. Each instance
starts empty, is thread-safe, and discards all indexed content when closed.

Use `createAppSearchContentDive(context, databaseName)` for persistent Android application usage.
It uses app-private AppSearch `LocalStorage`, initializes lazily with the application context, and
preserves indexed data after close/reopen. The index remains derived data: an incompatible internal
schema may clear it and require the normal repository reindexing flow.

Both factories return the same `ContentDive` API and accept `ContentDiveConfiguration` for the
default result limit, maximum result limit, and whether fuzzy expansion is enabled. Ranking,
chunking, trigram, and edit-distance details are intentionally internal.

## Destinations and anchors

A `DestinationRef` identifies the application entity to open. Its type, version, and payload belong
to the application; ContentDive stores and returns them without interpreting the payload.

An optional `AnchorRef` identifies a location inside that entity, such as paragraph 17 or block
`b17`. For structured text, create one logical `SearchFragment` per semantic block and attach that
block's anchor. ContentDive may split an oversized fragment internally, but the destination and
anchor survive and internal chunks remain hidden.

When a result is selected:

```text
SearchMatch.destination → decode entity ID → load current entity from repository
SearchMatch.anchor      → decode optional block ID → focus or scroll after loading
```

The indexed title, subtitle, fragment, and snippet are search snapshots. The destination screen
must reload current authoritative data rather than rendering the index as its source of truth.

## Current limitations

- No HTML or Markdown parsing.
- No semantic or vector search, synonyms, or stemming.
- No built-in search UI or result-highlighting component.
- No automatic repository synchronization or background indexing.
- No automatic navigation registration or destination serialization.
- No whole-scope transactional replacement or non-destructive AppSearch schema migration.

## Architecture and behavior notes

ContentDive normalizes application fragments, splits long text into overlapping internal chunks,
and lets the selected backend narrow candidate chunks. The engine still owns final exact, prefix,
and Damerau–Levenshtein fuzzy acceptance, ranking, grouping, deterministic ordering, and local
original-text snippets.

`replace` and `replaceAll` treat each `SearchProjection` as a complete item snapshot. Replacing one
scoped item atomically removes every previous fragment, internal chunk, posting, and fuzzy term for
that item. A batch is validated and prepared before backend mutation and is atomic per item, but it
does not promise one transaction across every item. `SearchBatchResult` reports individual valid-
item failures without hiding successes.

`ContentDive` is thread-safe and owns its backend. Cancellation propagates normally. Closing is
idempotent; new operations fail with `ContentDiveLifecycleException`, and backend/storage failures
surface as `ContentDiveException` with their original cause. Backend failures are never converted to
empty search results.

The example application keeps [`mockdata`](app-example/src/main/java/com/contentdive/example/mockdata/),
[`integration`](app-example/src/main/java/com/contentdive/example/integration/), and
[`ui`](app-example/src/main/java/com/contentdive/example/ui/) separate. It indexes more than 500
events in one batch, searches fuzzy long-description content, preserves paragraph anchors, resolves
Navigation 3 keys, and verifies persistent AppSearch close/reopen behavior.

## Verification and API documentation

Run the normal repository verification with:

```shell
./gradlew clean build apiCheck publishPublicModulesToMavenLocal
```

Generate aggregated public API documentation from KDoc with:

```shell
./gradlew :dokkaGenerate
```

The HTML output is written to `build/dokka/html`. Documentation generation reports undocumented
public declarations and fails on warnings. Committed API dumps cover every published module, and
the root checks keep implementation models and integration-specific types out of unrelated public
surfaces.

With a device or emulator connected, run the persistent backend and example flows with:

```shell
./gradlew :contentdive-backend-appsearch:connectedDebugAndroidTest \
  :app-example:connectedDebugAndroidTest
```

## License

ContentDive is available under the [Apache License 2.0](LICENSE).
