package com.contentdive.backend.appsearch

import android.content.Context
import androidx.appsearch.app.AppSearchSchema
import androidx.appsearch.app.GenericDocument
import androidx.appsearch.app.PutDocumentsRequest
import androidx.appsearch.app.SetSchemaRequest
import androidx.appsearch.localstorage.LocalStorage
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.contentdive.api.AnchorRef
import com.contentdive.api.DestinationRef
import com.contentdive.spi.ExperimentalContentDiveSpi
import com.contentdive.spi.SearchBackend
import com.contentdive.spi.testing.SearchBackendContract
import com.contentdive.spi.testing.find
import com.contentdive.spi.testing.fuzzy
import com.contentdive.spi.testing.preparedProjection
import com.contentdive.spi.testing.runSuspendTest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalContentDiveSpi::class)
internal class AppSearchBackendInstrumentedTest : SearchBackendContract() {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    override fun createBackend(): SearchBackend = createAppSearchBackend(
        context,
        "contentdive-contract-${UUID.randomUUID()}",
    )

    @Test
    fun closeAndReopenPreservesProjectionChunksDestinationAndAnchor() {
        val database = "contentdive-reopen-${UUID.randomUUID()}"
        val destination = DestinationRef("event", 7, "{\"eventId\":\"E42\"}")
        val anchor = AnchorRef(
            "event-description-paragraph",
            3,
            "{\"eventId\":\"E42\",\"paragraphIndex\":17}",
        )
        val first = createAppSearchBackend(context, database)
        runSuspendTest {
            val projection = preparedProjection(
                id = "E42",
                scope = "events",
                fragments = arrayOf("description:17" to "parking is available after 18 00"),
                destination = destination,
                anchor = anchor,
            )
            val result = first.replaceAll(
                listOf(
                    preparedProjection(
                        id = "E43",
                        scope = "events",
                        fragments = arrayOf("description" to "bicycle storage is available"),
                    ),
                    projection.copy(item = projection.item.copy(subtitle = "Riverside Hall")),
                ),
            )
            assertEquals(listOf("E42", "E43"), result.successfulItems.map { it.itemId.value })
        }
        first.close()

        val reopened = createAppSearchBackend(context, database)
        try {
            runSuspendTest {
                val candidate = reopened.find("parking").single()
                assertEquals("description:17", candidate.sourceFragment.id.value)
                assertEquals(destination, candidate.item.destination)
                assertEquals("Riverside Hall", candidate.item.subtitle)
                assertEquals(anchor, candidate.sourceFragment.anchor)
                assertEquals(anchor, candidate.chunk.anchor)
                assertEquals("parking", reopened.fuzzy("parkng").first().indexedTerm)
                assertEquals("E43", reopened.find("bicycle").single().item.id.value)
            }
        } finally {
            reopened.close()
        }
    }

    @Test
    fun incompatibleInternalSchemaProducesCleanEmptyIndex() {
        val database = "contentdive-schema-${UUID.randomUUID()}"
        val legacySession = LocalStorage.createSearchSessionAsync(
            LocalStorage.SearchContext.Builder(context, database).build(),
        ).get(30, TimeUnit.SECONDS)
        val incompatibleSchema = AppSearchSchema.Builder("ContentDiveProjectionV1")
            .addProperty(
                AppSearchSchema.StringPropertyConfig.Builder("legacyText")
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_REQUIRED)
                    .setTokenizerType(AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_PLAIN)
                    .setIndexingType(AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_PREFIXES)
                    .build(),
            )
            .build()
        legacySession.setSchemaAsync(
            SetSchemaRequest.Builder()
                .addSchemas(incompatibleSchema)
                .setForceOverride(true)
                .build(),
        ).get(30, TimeUnit.SECONDS)
        val legacyDocument = GenericDocument.Builder<GenericDocument.Builder<*>>(
            "events",
            "E42",
            "ContentDiveProjectionV1",
        )
            .setPropertyString("legacyText", "parking")
            .build()
        val putResult = legacySession.putAsync(
            PutDocumentsRequest.Builder().addGenericDocuments(legacyDocument).build(),
        ).get(30, TimeUnit.SECONDS)
        assertTrue(putResult.isSuccess)
        legacySession.requestFlushAsync().get(30, TimeUnit.SECONDS)
        legacySession.close()

        val backend = createAppSearchBackend(context, database)
        try {
            runSuspendTest {
                assertTrue(backend.find("parking").isEmpty())
                assertTrue(backend.fuzzy("parkng").isEmpty())
            }
        } finally {
            backend.close()
        }
    }
}
