package com.contentdive.backend.appsearch

import android.content.Context
import com.contentdive.spi.ExperimentalContentDiveSpi
import com.contentdive.spi.SearchBackend

/**
 * Creates a raw persistent backend backed exclusively by app-private AppSearch LocalStorage.
 *
 * This experimental factory is for engine integration and shared backend-contract tests.
 * Applications should use [createAppSearchContentDive], which owns the session and standardizes
 * lifecycle and storage failures without exposing SPI types. The backend initializes lazily, uses
 * [Context.getApplicationContext], and retains indexed data after close.
 *
 * @param context Android context used to obtain the application context.
 * @param databaseName non-blank database name identifying the persistent derived index.
 * @throws IllegalArgumentException if [databaseName] is blank.
 */
@ExperimentalContentDiveSpi
public fun createAppSearchBackend(
    context: Context,
    databaseName: String,
): SearchBackend {
    require(databaseName.isNotBlank()) { "AppSearch database name must not be blank" }
    return AppSearchBackend(context.applicationContext, databaseName)
}
