package com.ninelivesaudio.app.ui.theme.unhinged.accent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ninelivesaudio.app.ui.theme.unhinged.surfaces.StoneSlabCard

/**
 * Example: Selectable List Item with Edge Highlight
 *
 * Shows proper usage of the impossible accent for selection indication.
 * The accent appears as a small 3dp edge highlight, never as a full background.
 */
@Composable
fun SelectableListItemExample(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    StoneSlabCard(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            // ✅ CORRECT: Small edge highlight for selection
            .selectionEdgeHighlight(isSelected = isSelected)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                // ✅ CORRECT: Using standard text colors, NOT impossible accent
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Example: Focusable Button with Archive Focus Indication
 *
 * Shows proper usage of impossible accent for keyboard focus.
 * The button itself uses gold (primary), but focus ring uses impossible accent.
 */
@Composable
fun FocusableButtonExample(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier
            // ✅ CORRECT: Impossible accent only for focus indication
            .archiveFocusIndication(),
        // ✅ CORRECT: Button uses gold (primary), not impossible accent
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary
        )
    ) {
        Text(text)
    }
}

/**
 * Example: Tab Indicator with Active State
 *
 * Shows how to combine gold and impossible accent for tab navigation.
 * The tab itself uses gold, but the active indicator can use impossible accent.
 */
@Composable
fun TabExample() {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Home", "Library", "Player")

    TabRow(
        selectedTabIndex = selectedTab,
        // ✅ CORRECT: Active indicator uses impossible accent in unhinged mode
        indicator = { tabPositions ->
            val indicatorColor = rememberActiveIndicatorColor(isActive = true)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentSize(Alignment.BottomStart)
                    .offset(x = tabPositions[selectedTab].left)
                    .width(tabPositions[selectedTab].width)
                    .height(3.dp)
                    .background(indicatorColor)
            )
        }
    ) {
        tabs.forEachIndexed { index, title ->
            Tab(
                selected = selectedTab == index,
                onClick = { selectedTab = index },
                text = { Text(title) }
            )
        }
    }
}

/**
 * Example: List with Selection State
 *
 * Complete example showing a list where items can be selected.
 * Demonstrates proper selection edge highlight usage.
 */
@Composable
fun SelectableListExample() {
    var selectedId by remember { mutableStateOf<String?>(null) }

    val items = remember {
        listOf(
            "The Archive Keeper's Guide" to "A. Librarian",
            "Songs of the Deep Shelves" to "M. Curator",
            "The Last Bookmark" to "S. Archivist"
        )
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(items) { (title, author) ->
            SelectableListItemExample(
                title = title,
                subtitle = author,
                isSelected = selectedId == title,
                onClick = {
                    selectedId = if (selectedId == title) null else title
                }
            )
        }
    }
}

/**
 * ❌ ANTI-PATTERN: Full Background with Impossible Accent
 *
 * DO NOT DO THIS - the impossible accent should never be a full background fill.
 */
@Composable
private fun BadExample_FullBackground() {
    // ❌ WRONG: Using impossible accent as a background
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            // .background(ImpossibleAccent) // DON'T DO THIS
    ) {
        Text("This would be wrong")
    }
}

/**
 * ❌ ANTI-PATTERN: Text with Impossible Accent Color
 *
 * DO NOT DO THIS - the impossible accent should never be used for text.
 */
@Composable
private fun BadExample_TextColor() {
    // ❌ WRONG: Using impossible accent as text color
    Text(
        text = "This would be wrong",
        // color = ImpossibleAccent // DON'T DO THIS
        color = MaterialTheme.colorScheme.onSurface // Use this instead
    )
}

/**
 * ✅ CORRECT: Combining Gold and Impossible Accent
 *
 * Shows how to use both accents properly:
 * - Gold for the primary action (button)
 * - Impossible accent for focus state
 */
@Composable
fun CorrectExample_CombinedAccents() {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Primary action: uses gold
        Button(
            onClick = { },
            modifier = Modifier.archiveFocusIndication(), // Focus: impossible accent
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary // Gold
            )
        ) {
            Text("Download Book")
        }

        // Selection indicator: uses impossible accent
        var isSelected by remember { mutableStateOf(false) }
        StoneSlabCard(
            onClick = { isSelected = !isSelected },
            modifier = Modifier
                .fillMaxWidth()
                .selectionEdgeHighlight(isSelected = isSelected) // Impossible accent
        ) {
            Text(
                "Selectable Item",
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurface // NOT impossible accent
            )
        }
    }
}
