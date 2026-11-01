package com.ninelivesaudio.app.ui.coversearch

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.ninelivesaudio.app.service.cover.CoverResult
import com.ninelivesaudio.app.service.cover.CoverSourceId
import com.ninelivesaudio.app.ui.components.BookCoverImage
import com.ninelivesaudio.app.ui.theme.NineLivesTheme
import com.ninelivesaudio.app.ui.coversearch.CoverSearchUiState.Phase

private val TileShape = RoundedCornerShape(10.dp)

/** Find a cover: search Open Library or pick from your photos, preview, and set. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoverSearchScreen(
    onDone: () -> Unit,
    onBack: () -> Unit,
    viewModel: CoverSearchViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.choosePhoto(uri)
    }

    LaunchedEffect(uiState.done) {
        if (uiState.done) onDone()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = CoverCopy.FIND_A_COVER,
                        color = NineLivesTheme.colors.archiveTextPrimary,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = NineLivesTheme.colors.goldFilament,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = NineLivesTheme.colors.archiveVoidDeep),
            )
        },
        containerColor = NineLivesTheme.colors.archiveVoidDeep,
    ) { innerPadding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(104.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(NineLivesTheme.colors.archiveVoidDeep),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 100.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SearchHeader(
                    uiState = uiState,
                    onQueryChange = viewModel::onQueryChange,
                    onSearch = {
                        focus.clearFocus()
                        viewModel.search()
                    },
                    onPickPhoto = {
                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                )
            }
            items(uiState.results, key = { it.candidate.key }) { result ->
                CoverTile(result = result, onClick = { viewModel.choose(result) })
            }
        }
    }

    uiState.preview?.let { preview ->
        PreviewDialog(
            preview = preview,
            saving = uiState.saving,
            onConfirm = viewModel::confirm,
            onDismiss = viewModel::dismissPreview,
        )
    }
}

@Composable
private fun SearchHeader(
    uiState: CoverSearchUiState,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onPickPhoto: () -> Unit,
) {
    val colors = NineLivesTheme.colors
    val searching = uiState.phase == Phase.SEARCHING
    Column(modifier = Modifier.fillMaxWidth()) {
        // The book this is for, with the cover it has now.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(TileShape)
                    .background(colors.archiveVoidElevated)
                    .border(1.dp, colors.goldFilamentFaint, TileShape),
            ) {
                BookCoverImage(
                    coverUrl = uiState.bookCover,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    title = uiState.bookTitle,
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = uiState.bookTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.archiveTextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                uiState.bookAuthor?.takeIf { it.isNotBlank() }?.let { author ->
                    Text(
                        text = author,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.archiveTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        OutlinedTextField(
            value = uiState.query,
            onValueChange = onQueryChange,
            label = { Text(CoverCopy.FIELD_LABEL) },
            singleLine = true,
            enabled = !uiState.saving,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.goldFilament.copy(alpha = 0.95f),
                unfocusedBorderColor = colors.archiveOutline,
                // No fill: the floating label's notch would show the page color
                // as a box cut into a filled field.
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                focusedTextColor = colors.archiveTextPrimary,
                unfocusedTextColor = colors.archiveTextPrimary,
                focusedLabelColor = colors.goldFilament,
                unfocusedLabelColor = colors.archiveTextMuted,
                cursorColor = colors.goldFilament,
            ),
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onSearch,
                enabled = uiState.query.isNotBlank() && !searching && !uiState.saving,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.goldFilament,
                    contentColor = colors.onAccent,
                    disabledContainerColor = colors.goldFilament.copy(alpha = 0.3f),
                    disabledContentColor = colors.onAccent.copy(alpha = 0.6f),
                ),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(vertical = 14.dp),
            ) {
                Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(CoverCopy.SEARCH, fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = onPickPhoto,
                enabled = !uiState.saving,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.goldFilament),
                border = ButtonDefaults.outlinedButtonBorder(true).copy(
                    brush = SolidColor(colors.goldFilament.copy(alpha = 0.5f)),
                ),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(vertical = 14.dp, horizontal = 8.dp),
            ) {
                Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(CoverCopy.PICK_FROM_PHOTOS, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        StatusLine(uiState)
    }
}

@Composable
private fun StatusLine(uiState: CoverSearchUiState) {
    val colors = NineLivesTheme.colors
    val text = uiState.notice ?: when (uiState.phase) {
        Phase.SEARCHING -> CoverCopy.SEARCHING
        Phase.NO_MATCHES -> CoverCopy.NO_MATCHES
        Phase.UNREACHABLE -> CoverCopy.UNREACHABLE
        Phase.IDLE, Phase.RESULTS -> null
    } ?: return
    val warn = uiState.notice != null || uiState.phase == Phase.UNREACHABLE
    Row(
        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            uiState.notice == null && uiState.phase == Phase.SEARCHING -> CircularProgressIndicator(
                color = colors.goldFilament,
                strokeWidth = 2.dp,
                modifier = Modifier.size(14.dp),
            )
            uiState.notice == null && uiState.phase == Phase.UNREACHABLE -> Icon(
                Icons.Outlined.CloudOff,
                contentDescription = null,
                tint = colors.archiveWarning,
                modifier = Modifier.size(16.dp),
            )
            else -> null
        }?.let { Spacer(modifier = Modifier.width(10.dp)) }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (warn) colors.archiveWarning else colors.archiveTextMuted,
        )
    }
}

private fun CoverSourceId.label(): String = when (this) {
    CoverSourceId.AUDIOBOOK_COVERS -> CoverCopy.SOURCE_AUDIOBOOK_COVERS
    CoverSourceId.OPEN_LIBRARY -> CoverCopy.SOURCE_OPEN_LIBRARY
}

/** The credit under a preview: the artists for audiobookcovers.com, the service otherwise. */
private fun CoverSourceId.credit(): String = when (this) {
    CoverSourceId.AUDIOBOOK_COVERS -> CoverCopy.AUDIOBOOK_COVERS_CREDIT
    CoverSourceId.OPEN_LIBRARY -> CoverCopy.SOURCE_OPEN_LIBRARY
}

@Composable
private fun CoverTile(result: CoverResult, onClick: () -> Unit) {
    val colors = NineLivesTheme.colors
    Column(
        modifier = Modifier
            .clip(TileShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = result.candidate.title ?: result.candidate.source.label() },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(TileShape)
                .background(colors.archiveVoidElevated)
                .border(1.dp, colors.goldFilamentFaint, TileShape),
            contentAlignment = Alignment.Center,
        ) {
            // Portrait covers show as they are. The square fit happens only when one is set.
            AsyncImage(
                model = result.thumb.bytes,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(6.dp),
            )
        }
        Text(
            text = result.candidate.source.label(),
            style = MaterialTheme.typography.labelSmall,
            color = colors.archiveTextMuted,
            modifier = Modifier.padding(top = 6.dp),
            maxLines = 1,
        )
    }
}

@Composable
private fun PreviewDialog(
    preview: CoverPreview,
    saving: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = NineLivesTheme.colors
    val (model, description) = when (preview) {
        is CoverPreview.FromSearch -> preview.result.thumb.bytes to preview.result.candidate.title
        is CoverPreview.FromPhoto -> preview.uri to null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.archiveVoidDeep)
                        .border(1.dp, colors.goldFilamentFaint, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    AsyncImage(
                        model = model,
                        contentDescription = description,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (preview is CoverPreview.FromSearch) {
                    Text(
                        text = preview.result.candidate.source.credit(),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.archiveTextMuted,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            if (saving) {
                CircularProgressIndicator(
                    color = colors.goldFilament,
                    strokeWidth = 2.dp,
                    modifier = Modifier
                        .padding(end = 12.dp, bottom = 8.dp)
                        .size(22.dp),
                )
            } else {
                TextButton(onClick = onConfirm) {
                    Text(CoverCopy.USE_THIS_COVER, color = colors.goldFilament, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            if (!saving) {
                TextButton(onClick = onDismiss) {
                    Text(CoverCopy.CANCEL, color = colors.archiveTextSecondary)
                }
            }
        },
        containerColor = colors.archiveVoidSurface,
    )
}
