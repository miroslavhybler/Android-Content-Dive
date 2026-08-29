package com.contentdive.backend.appsearch

import android.content.Context
import androidx.appsearch.app.AppSearchBatchResult
import androidx.appsearch.app.AppSearchResult
import androidx.appsearch.app.AppSearchSession
import androidx.appsearch.app.GenericDocument
import androidx.appsearch.app.GetByDocumentIdRequest
import androidx.appsearch.app.PutDocumentsRequest
import androidx.appsearch.app.RemoveByDocumentIdRequest
import androidx.appsearch.app.SearchSpec
import androidx.appsearch.localstorage.LocalStorage
import com.contentdive.api.SearchItemId
import com.contentdive.api.SearchBatchItemId
import com.contentdive.api.SearchScope
import com.contentdive.spi.BackendCandidate
import com.contentdive.spi.BackendCandidateRequest
import com.contentdive.spi.BackendBatchFailure
import com.contentdive.spi.BackendBatchWriteResult
import com.contentdive.spi.BackendCapabilities
import com.contentdive.spi.BackendTermCandidate
import com.contentdive.spi.BackendWriteResult
import com.contentdive.spi.ExperimentalContentDiveSpi
import com.contentdive.spi.FuzzyTermRequest
import com.contentdive.spi.PreparedProjection
import com.contentdive.spi.SearchBackend
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CancellationException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@OptIn(ExperimentalContentDiveSpi::class)
internal class AppSearchBackend(
    private val applicationContext: Context,
    private val databaseName: String,
) : SearchBackend {
    private val lifecycleLock = ReentrantLock()
    private var initialization: CompletableFuture<AppSearchSession>? = null
    private var activeOperations = 0
    private var closed = false
    private var sessionCloseScheduled = false

    override val capabilities: BackendCapabilities = BackendCapabilities(
        supportsPrefixCandidates = true,
        supportsFuzzyCandidates = true,
    )

    override suspend fun replaceAll(
        projections: List<PreparedProjection>,
    ): BackendBatchWriteResult = withSession { appSearch ->
        requireUniqueItems(projections.map { SearchBatchItemId(it.item.scope, it.item.id) })
        val sorted = projections.sortedWith(projectionComparator)
        val successful = mutableListOf<SearchBatchItemId>()
        val failures = mutableListOf<BackendBatchFailure>()
        var affectedFragments = 0
        val mapped = sorted.mapNotNull { projection ->
            try {
                MappedProjection(projection, ProjectionDocumentMapper.toDocument(projection))
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                failures += BackendBatchFailure(
                    projection.item.batchId(),
                    error.batchMessage("AppSearch could not map projection"),
                )
                null
            }
        }

        mapped.groupBy { it.projection.item.scope }.forEach { (_, scopedProjections) ->
            val projectionsById = scopedProjections.associateBy { it.projection.item.id.value }
            val documents = scopedProjections.map(MappedProjection::document)
            val outcome = appSearch.putAsync(
                PutDocumentsRequest.Builder()
                    .addGenericDocuments(*documents.toTypedArray())
                    .build(),
            ).await()

            projectionsById.forEach { (itemId, mappedProjection) ->
                val projection = mappedProjection.projection
                val item = projection.item.batchId()
                when {
                    outcome.successes.containsKey(itemId) -> {
                        successful += item
                        affectedFragments += projection.fragments.size
                    }
                    outcome.failures.containsKey(itemId) -> failures += BackendBatchFailure(
                        item = item,
                        message = checkNotNull(outcome.failures[itemId]).batchMessage(),
                    )
                    else -> failures += BackendBatchFailure(
                        item = item,
                        message = "AppSearch returned no replacement outcome",
                    )
                }
            }
        }
        if (successful.isNotEmpty()) appSearch.requestFlushAsync().await()
        BackendBatchWriteResult(
            successfulItems = successful.sortedWith(batchItemComparator),
            failedItems = failures.sortedWith(batchFailureComparator),
            affectedItems = successful.size,
            affectedFragments = affectedFragments,
        )
    }

    override suspend fun removeAll(
        scope: SearchScope,
        itemIds: List<SearchItemId>,
    ): BackendBatchWriteResult = withSession { appSearch ->
        require(scope.value.isNotBlank()) { "SearchScope must not be blank" }
        itemIds.forEach { require(it.value.isNotBlank()) { "SearchItemId must not be blank" } }
        val requested = itemIds.sortedBy(SearchItemId::value)
        requireUniqueItems(requested.map { SearchBatchItemId(scope, it) })
        if (requested.isEmpty()) {
            return@withSession BackendBatchWriteResult(
                successfulItems = emptyList(),
                failedItems = emptyList(),
                affectedItems = 0,
                affectedFragments = 0,
            )
        }

        val getOutcome = appSearch.getByDocumentIdAsync(
            GetByDocumentIdRequest.Builder(scope.value)
                .addIds(*requested.map { it.value }.toTypedArray())
                .build(),
        ).await()
        val successful = mutableListOf<SearchBatchItemId>()
        val failures = mutableListOf<BackendBatchFailure>()
        val existing = linkedMapOf<String, GenericDocument>()
        requested.forEach { itemId ->
            val item = SearchBatchItemId(scope, itemId)
            val document = getOutcome.successes[itemId.value]
            val getFailure = getOutcome.failures[itemId.value]
            when {
                document != null -> existing[itemId.value] = document
                getFailure == null || getFailure.resultCode == AppSearchResult.RESULT_NOT_FOUND -> {
                    successful += item
                }
                else -> failures += BackendBatchFailure(item, getFailure.batchMessage())
            }
        }
        if (existing.isEmpty()) {
            return@withSession BackendBatchWriteResult(
                successfulItems = successful.sortedWith(batchItemComparator),
                failedItems = failures.sortedWith(batchFailureComparator),
                affectedItems = 0,
                affectedFragments = 0,
            )
        }

        val removeOutcome = appSearch.removeAsync(
            RemoveByDocumentIdRequest.Builder(scope.value)
                .addIds(*existing.keys.toTypedArray())
                .build(),
        ).await()
        var affectedFragments = 0
        existing.forEach { (itemId, document) ->
            val item = SearchBatchItemId(scope, SearchItemId(itemId))
            when {
                removeOutcome.successes.containsKey(itemId) -> {
                    successful += item
                    affectedFragments += ProjectionDocumentMapper.fragmentCount(document)
                }
                removeOutcome.failures.containsKey(itemId) -> failures += BackendBatchFailure(
                    item,
                    checkNotNull(removeOutcome.failures[itemId]).batchMessage(),
                )
                else -> failures += BackendBatchFailure(
                    item,
                    "AppSearch returned no removal outcome",
                )
            }
        }
        if (removeOutcome.successes.isNotEmpty()) appSearch.requestFlushAsync().await()
        BackendBatchWriteResult(
            successfulItems = successful.sortedWith(batchItemComparator),
            failedItems = failures.sortedWith(batchFailureComparator),
            affectedItems = removeOutcome.successes.size,
            affectedFragments = affectedFragments,
        )
    }

    override suspend fun clear(scope: SearchScope): BackendWriteResult = withSession { appSearch ->
        val documents = searchDocuments(
            appSearch = appSearch,
            query = "",
            property = null,
            termMatch = SearchSpec.TERM_MATCH_EXACT_ONLY,
            scopes = setOf(scope),
        )
        if (documents.isEmpty()) {
            return@withSession BackendWriteResult(affectedItems = 0, affectedFragments = 0)
        }
        val ids = documents.map(GenericDocument::getId)
        val fragmentCount = documents.sumOf(ProjectionDocumentMapper::fragmentCount)
        appSearch.removeAsync(
            RemoveByDocumentIdRequest.Builder(scope.value)
                .addIds(*ids.toTypedArray())
                .build(),
        ).await().requireSuccess("clear scope")
        appSearch.requestFlushAsync().await()
        BackendWriteResult(
            affectedItems = documents.size,
            affectedFragments = fragmentCount,
        )
    }

    override suspend fun candidates(
        request: BackendCandidateRequest,
    ): List<BackendCandidate> = withSession { appSearch ->
        val documents = linkedMapOf<DocumentKey, GenericDocument>()
        request.tokens.distinct().forEach { token ->
            searchDocuments(
                appSearch = appSearch,
                query = token,
                property = AppSearchSchemaModel.SEARCHABLE_TOKENS,
                termMatch = if (request.includePrefixes) {
                    SearchSpec.TERM_MATCH_PREFIX
                } else {
                    SearchSpec.TERM_MATCH_EXACT_ONLY
                },
                scopes = request.scopes,
            ).forEach { document ->
                documents[DocumentKey(document.namespace, document.id)] = document
            }
        }

        documents.values.asSequence()
            .map(ProjectionDocumentMapper::fromDocument)
            .flatMap { projection ->
                val fragmentsById = projection.fragments.associateBy { it.id }
                projection.chunks.asSequence()
                    .filter { chunk ->
                        chunk.tokens.any { indexedToken ->
                            request.tokens.any { queryToken ->
                                indexedToken.value == queryToken ||
                                    request.includePrefixes && indexedToken.value.startsWith(queryToken)
                            }
                        }
                    }
                    .map { chunk ->
                        BackendCandidate(
                            item = projection.item,
                            sourceFragment = checkNotNull(fragmentsById[chunk.sourceFragmentId]) {
                                "Persisted chunk '${chunk.id}' has no source fragment"
                            },
                            chunk = chunk,
                        )
                    }
            }
            .sortedWith(candidateComparator)
            .take(request.limit)
            .toList()
    }

    override suspend fun fuzzyTerms(
        request: FuzzyTermRequest,
    ): List<BackendTermCandidate> = withSession { appSearch ->
        val documents = linkedMapOf<DocumentKey, GenericDocument>()
        request.trigrams.forEach { trigram ->
            searchDocuments(
                appSearch = appSearch,
                query = encodeTrigram(trigram),
                property = AppSearchSchemaModel.FUZZY_TRIGRAMS,
                termMatch = SearchSpec.TERM_MATCH_EXACT_ONLY,
                scopes = emptySet(),
            ).forEach { document ->
                documents[DocumentKey(document.namespace, document.id)] = document
            }
        }

        val overlapByTerm = mutableMapOf<String, Int>()
        val itemKeysByTerm = mutableMapOf<String, MutableSet<DocumentKey>>()
        documents.forEach { (documentKey, document) ->
            ProjectionDocumentMapper.searchableTokens(document).forEach { term ->
                if (term == request.normalizedQueryToken) return@forEach
                val overlap = characterTrigrams(term).count(request.trigrams::contains)
                if (overlap > 0) {
                    overlapByTerm[term] = overlap
                    itemKeysByTerm.getOrPut(term, ::linkedSetOf).add(documentKey)
                }
            }
        }
        overlapByTerm.asSequence()
            .map { (term, overlap) ->
                BackendTermCandidate(
                    indexedTerm = term,
                    trigramOverlap = overlap,
                    documentFrequency = itemKeysByTerm[term]?.size,
                )
            }
            .sortedWith(
                compareByDescending<BackendTermCandidate>(BackendTermCandidate::trigramOverlap)
                    .thenByDescending { it.documentFrequency ?: 0 }
                    .thenBy(BackendTermCandidate::indexedTerm),
            )
            .take(request.candidateLimit)
            .toList()
    }

    override fun close() {
        val pending = lifecycleLock.withLock {
            if (closed) return
            closed = true
            if (activeOperations == 0) {
                sessionCloseScheduled = true
                initialization
            } else {
                null
            }
        }
        pending.closeWhenReady()
    }

    private suspend fun sessionForOperation(): AppSearchSession {
        val pending = lifecycleLock.withLock {
            initialization ?: initialize().also { initialization = it }
        }
        return pending.await()
    }

    private suspend fun <T> withSession(block: suspend (AppSearchSession) -> T): T {
        lifecycleLock.withLock {
            check(!closed) { "AppSearchBackend is closed" }
            activeOperations += 1
        }
        return try {
            block(sessionForOperation())
        } finally {
            val pending = lifecycleLock.withLock {
                activeOperations -= 1
                if (closed && activeOperations == 0 && !sessionCloseScheduled) {
                    sessionCloseScheduled = true
                    initialization
                } else {
                    null
                }
            }
            pending.closeWhenReady()
        }
    }

    private fun initialize(): CompletableFuture<AppSearchSession> {
        val completion = CompletableFuture<AppSearchSession>()
        val sessionFuture = LocalStorage.createSearchSessionAsync(
            LocalStorage.SearchContext.Builder(applicationContext, databaseName).build(),
        )
        sessionFuture.addListener(
            {
                val appSearch = runCatching { sessionFuture.get() }
                    .getOrElse { error ->
                        completion.completeExceptionally(error.unwrapFutureFailure())
                        return@addListener
                    }
                val schemaFuture = appSearch.setSchemaAsync(AppSearchSchemaModel.setSchemaRequest)
                schemaFuture.addListener(
                    {
                        runCatching { schemaFuture.get() }
                            .onSuccess {
                                completion.complete(appSearch)
                            }
                            .onFailure { error ->
                                appSearch.close()
                                completion.completeExceptionally(error.unwrapFutureFailure())
                            }
                    },
                    DirectExecutor,
                )
            },
            DirectExecutor,
        )
        return completion
    }

    private suspend fun searchDocuments(
        appSearch: AppSearchSession,
        query: String,
        property: String?,
        termMatch: Int,
        scopes: Set<SearchScope>,
    ): List<GenericDocument> {
        val specification = SearchSpec.Builder()
            .setTermMatch(termMatch)
            .setResultCountPerPage(RESULTS_PER_PAGE)
            .addFilterSchemas(AppSearchSchemaModel.PROJECTION_TYPE)
            .apply {
                if (property != null) {
                    addFilterProperties(AppSearchSchemaModel.PROJECTION_TYPE, listOf(property))
                }
                if (scopes.isNotEmpty()) {
                    addFilterNamespaces(*scopes.map { it.value }.toTypedArray())
                }
            }
            .build()
        val results = appSearch.search(query, specification)
        return try {
            buildList {
                while (true) {
                    val page = results.nextPageAsync.await()
                    if (page.isEmpty()) break
                    page.forEach { add(it.genericDocument) }
                }
            }
        } finally {
            results.close()
        }
    }

    private data class DocumentKey(val scope: String, val itemId: String)

    private data class MappedProjection(
        val projection: PreparedProjection,
        val document: GenericDocument,
    )

    private fun requireUniqueItems(items: List<SearchBatchItemId>) {
        val duplicate = items.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }
        require(duplicate == null) {
            "Duplicate SearchItemId '${duplicate?.key?.itemId?.value}' in scope " +
                "'${duplicate?.key?.scope?.value}'"
        }
    }

    private companion object {
        const val RESULTS_PER_PAGE = 100

        val candidateComparator = compareBy<BackendCandidate>(
            { it.item.scope.value },
            { it.item.id.value },
            { it.chunk.id },
        )
        val projectionComparator = compareBy<PreparedProjection>(
            { it.item.scope.value },
            { it.item.id.value },
        )
        val batchItemComparator = compareBy<SearchBatchItemId>(
            { it.scope.value },
            { it.itemId.value },
        )
        val batchFailureComparator = compareBy<BackendBatchFailure>(
            { it.item.scope.value },
            { it.item.itemId.value },
        )
    }
}

private fun CompletableFuture<AppSearchSession>?.closeWhenReady() {
    this?.whenComplete { appSearch, _ -> appSearch?.close() }
}

private fun com.contentdive.api.SearchItem.batchId(): SearchBatchItemId =
    SearchBatchItemId(scope, id)

private fun AppSearchResult<*>.batchMessage(): String =
    errorMessage?.takeIf { it.isNotBlank() } ?: "AppSearch error $resultCode"

private fun Throwable.batchMessage(prefix: String): String =
    message?.takeIf(String::isNotBlank)?.let { "$prefix: $it" } ?: prefix

private fun AppSearchBatchResult<String, Void>.requireSuccess(operation: String) {
    check(isSuccess) { "AppSearch could not $operation: $failures" }
}

private fun characterTrigrams(term: String): Set<String> {
    val characters = buildList {
        add("^")
        var offset = 0
        while (offset < term.length) {
            val next = offset + Character.charCount(term.codePointAt(offset))
            add(term.substring(offset, next))
            offset = next
        }
        add("$")
    }
    return characters.windowed(3).mapTo(linkedSetOf()) { it.joinToString("") }
}

internal fun encodeTrigram(trigram: String): String = buildString {
    append('g')
    trigram.toByteArray(Charsets.UTF_8).forEach { byte ->
        append(HEX_DIGITS[(byte.toInt() ushr 4) and 0x0f])
        append(HEX_DIGITS[byte.toInt() and 0x0f])
    }
}

private const val HEX_DIGITS = "0123456789abcdef"
