package com.contentdive.backend.appsearch

import android.content.Context
import com.contentdive.api.ContentDive
import com.contentdive.api.ContentDiveConfiguration
import com.contentdive.engine.ContentDiveEngine
import com.contentdive.spi.ExperimentalContentDiveSpi

/**
 * Creates a complete, thread-safe [ContentDive] using app-private AppSearch LocalStorage.
 *
 * Initialization is lazy and uses [Context.getApplicationContext], so the returned instance does
 * not retain an activity. Indexed projections persist under [databaseName] after
 * [ContentDive.close] and can be searched by a later instance without reindexing. ContentDive owns
 * the AppSearch session; callers should close ContentDive rather than manage storage directly.
 *
 * @param context Android context used to obtain the application context.
 * @param databaseName non-blank app-private AppSearch database name. Reusing it reopens the same
 * derived index.
 * @param configuration immutable query defaults and limits for this instance.
 * @return an open ContentDive instance whose storage session is created on first use.
 * @throws IllegalArgumentException if [databaseName] is blank.
 * @throws com.contentdive.api.ContentDiveException from the first operation if AppSearch
 * initialization or storage access fails; the original cause is retained.
 */
@OptIn(ExperimentalContentDiveSpi::class)
public fun createAppSearchContentDive(
    context: Context,
    databaseName: String,
    configuration: ContentDiveConfiguration = ContentDiveConfiguration(),
): ContentDive {
    require(databaseName.isNotBlank()) { "AppSearch database name must not be blank" }
    return ContentDiveEngine.create(
        backend = AppSearchBackend(context.applicationContext, databaseName),
        configuration = configuration,
    )
}
