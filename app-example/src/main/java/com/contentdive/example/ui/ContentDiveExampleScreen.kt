package com.contentdive.example.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.contentdive.example.ui.theme.ContentDiveTheme

/** Plain display models: this package has no ContentDive or app-domain dependency. */
internal data class SearchResultUiModel(
    val id: String,
    val title: String,
    val snippet: String,
    val matchedSection: String,
)

internal data class EventDetailUiModel(
    val id: String,
    val title: String,
    val location: String,
    val paragraphs: List<String>,
)

@Composable
internal fun EventSearchScreen(
    query: String,
    results: List<SearchResultUiModel>,
    isLoading: Boolean,
    errorMessage: String?,
    onQueryChange: (String) -> Unit,
    onResultClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text("Search events", style = MaterialTheme.typography.headlineMedium)
        }
        item {
            Text(
                "These results come from ContentDive's on-device index. Try “parkng”, “arrival”, or “studio”.",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Query") },
                singleLine = true,
            )
        }
        when {
            errorMessage != null -> item {
                Text(errorMessage, color = MaterialTheme.colorScheme.error)
            }
            isLoading -> item { Text("Preparing the event index…") }
            query.isBlank() -> item { Text("Enter a word or phrase to search.") }
            results.isEmpty() -> item { Text("No matching events.") }
            else -> items(results, key = SearchResultUiModel::id) { result ->
                SearchResultCard(result, onResultClick)
            }
        }
    }
}

@Composable
private fun SearchResultCard(
    result: SearchResultUiModel,
    onResultClick: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(result.title, style = MaterialTheme.typography.titleLarge)
            Text(
                result.matchedSection,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(result.snippet, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { onResultClick(result.id) }) {
                Text("Open event")
            }
        }
    }
}

@Composable
internal fun EventDetailScreen(
    event: EventDetailUiModel?,
    selectedParagraphIndex: Int?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(event?.id, selectedParagraphIndex) {
        if (
            event != null &&
            selectedParagraphIndex != null &&
            selectedParagraphIndex in event.paragraphs.indices
        ) {
            // Two header items appear before the paragraph list.
            listState.animateScrollToItem(selectedParagraphIndex + 2)
        }
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Button(onClick = onBack) { Text("Back to search") }
        }
        if (event == null) {
            item { Text("This event is no longer available.") }
        } else {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(event.title, style = MaterialTheme.typography.headlineMedium)
                    Text(
                        event.location,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            items(event.paragraphs.size) { index ->
                val selected = index == selectedParagraphIndex
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Column(
                        modifier = Modifier.padding(if (selected) 16.dp else 4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (selected) {
                            Text(
                                text = "Matched paragraph $index",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                        Text(
                            text = event.paragraphs[index],
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun EventSearchScreenPreview() {
    ContentDiveTheme(dynamicColor = false) {
        EventSearchScreen(
            query = "parkng",
            results = listOf(
                SearchResultUiModel(
                    id = "event:E42",
                    title = "Kotlin Meetup",
                    snippet = "Parking is available in the underground garage.",
                    matchedSection = "Description paragraph 17",
                ),
            ),
            isLoading = false,
            errorMessage = null,
            onQueryChange = {},
            onResultClick = {},
        )
    }
}
