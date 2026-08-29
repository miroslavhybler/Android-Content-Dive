package com.contentdive.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.contentdive.api.ContentDive
import com.contentdive.api.SearchFragment
import com.contentdive.api.SearchMatch
import com.contentdive.api.SearchQuery
import com.contentdive.backend.appsearch.createAppSearchContentDive
import com.contentdive.example.integration.EventContentDiveIntegration
import com.contentdive.example.integration.EventDetailKey
import com.contentdive.example.mockdata.EventRepository
import com.contentdive.example.mockdata.MockEventRepository
import com.contentdive.example.ui.EventDetailScreen
import com.contentdive.example.ui.EventDetailUiModel
import com.contentdive.example.ui.EventSearchScreen
import com.contentdive.example.ui.SearchResultUiModel
import com.contentdive.example.ui.theme.ContentDiveTheme
import kotlinx.serialization.Serializable

class MainActivity : ComponentActivity() {
    private val repository: EventRepository = MockEventRepository()
    private val eventIntegration = EventContentDiveIntegration()
    private val contentDiveLazy: Lazy<ContentDive> = lazy {
        createAppSearchContentDive(applicationContext, CONTENTDIVE_DATABASE)
    }
    private val contentDive: ContentDive by contentDiveLazy

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ContentDiveTheme {
                ContentDiveExampleApp(
                    repository = repository,
                    integration = eventIntegration,
                    contentDive = contentDive,
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (contentDiveLazy.isInitialized()) contentDive.close()
    }

    private companion object {
        const val CONTENTDIVE_DATABASE = "contentdive-example"
    }
}

@Composable
private fun ContentDiveExampleApp(
    repository: EventRepository,
    integration: EventContentDiveIntegration,
    contentDive: ContentDive,
) {
    val backStack = rememberNavBackStack(SearchHomeKey)
    var query by remember { mutableStateOf("") }
    var indexed by remember { mutableStateOf(false) }
    var matches by remember { mutableStateOf(emptyList<SearchMatch>()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(contentDive, repository) {
        runCatching {
            val projections = integration.project(repository.all())
            val result = contentDive.replaceAll(projections)
            check(result.isSuccess) {
                result.failedItems.joinToString(
                    prefix = "Could not index ${result.failedItems.size} events: ",
                ) { failure ->
                    "${failure.item.itemId.value} (${failure.message})"
                }
            }
        }.onSuccess {
            indexed = true
        }.onFailure { error ->
            errorMessage = error.message ?: "Could not prepare the event index."
        }
    }

    LaunchedEffect(indexed, query) {
        if (!indexed) return@LaunchedEffect
        runCatching {
            contentDive.search(
                SearchQuery(
                    text = query,
                    scopes = setOf(integration.eventScope),
                ),
            ).matches
        }.onSuccess { searchMatches ->
            matches = searchMatches
            errorMessage = null
        }.onFailure { error ->
            matches = emptyList()
            errorMessage = error.message ?: "Search failed."
        }
    }

    val matchesById = matches.associateBy { it.item.id.value }
    val resultUiModels = matches.map { match ->
        SearchResultUiModel(
            id = match.item.id.value,
            title = match.item.title,
            snippet = match.snippet,
            matchedSection = match.bestFragment.sectionLabel(),
        )
    }

    NavDisplay(
        backStack = backStack,
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
        onBack = { backStack.removeLastOrNull() },
        entryProvider = entryProvider {
            entry<SearchHomeKey> {
                EventSearchScreen(
                    query = query,
                    results = resultUiModels,
                    isLoading = !indexed && errorMessage == null,
                    errorMessage = errorMessage,
                    onQueryChange = { query = it },
                    onResultClick = { itemId ->
                        matchesById[itemId]?.let { match ->
                            backStack.addAll(integration.navigationPlanFor(match).keys)
                        }
                    },
                )
            }
            entry<EventDetailKey> { key ->
                val event = repository.event(key.eventId)
                val uiModel = event?.let {
                    EventDetailUiModel(
                        id = it.id,
                        title = it.title,
                        location = it.location,
                        paragraphs = integration.descriptionParagraphs(it),
                    )
                }
                EventDetailScreen(
                    event = uiModel,
                    selectedParagraphIndex = key.paragraphIndex,
                    onBack = { backStack.removeLastOrNull() },
                )
            }
        },
    )
}

private fun SearchFragment.sectionLabel(): String =
    id.value.substringAfter("description:", missingDelimiterValue = "")
        .toIntOrNull()
        ?.let { "Description paragraph $it" }
        ?: id.value.replaceFirstChar(Char::uppercase)

@Serializable
private data object SearchHomeKey : NavKey
