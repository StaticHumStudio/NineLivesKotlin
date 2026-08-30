package com.ninelivesaudio.app.ui.changelog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ninelivesaudio.app.ui.theme.NineLivesTheme
import com.ninelivesaudio.app.ui.theme.unhinged.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangelogScreen(
    onNavigateBack: () -> Unit,
    viewModel: ChangelogViewModel = hiltViewModel(),
) {
    LaunchedEffect(Unit) {
        viewModel.markCurrentVersionSeen()
    }

    Scaffold(
        containerColor = NineLivesTheme.colors.archiveVoidDeep,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Version History",
                        style = MaterialTheme.typography.titleMedium,
                        color = NineLivesTheme.colors.goldFilament,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Back",
                            tint = NineLivesTheme.colors.goldFilament,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = NineLivesTheme.colors.archiveVoidBase,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = ChangelogData.ARCHIVE_SUBTITLE,
                style = MaterialTheme.typography.bodySmall,
                color = NineLivesTheme.colors.archiveTextSecondary,
            )

            ChangelogData.releases.forEach { release ->
                ChangelogReleaseCard(release)
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ChangelogReleaseCard(release: ChangelogRelease) {
    Card(
        colors = CardDefaults.cardColors(containerColor = NineLivesTheme.colors.archiveVoidSurface),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = release.version,
                    style = MaterialTheme.typography.titleLarge,
                    color = NineLivesTheme.colors.goldFilament,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = release.dateLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = NineLivesTheme.colors.goldFilamentDim,
                )
            }

            displaySections(release.sections).forEach { section ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = section.header,
                        style = MaterialTheme.typography.titleSmall,
                        color = NineLivesTheme.colors.archiveTextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.5.sp,
                    )
                    section.entries.forEach { entry ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                text = "•",
                                style = MaterialTheme.typography.bodySmall,
                                color = NineLivesTheme.colors.goldFilamentDim,
                            )
                            Text(
                                text = entry,
                                style = MaterialTheme.typography.bodySmall,
                                color = NineLivesTheme.colors.archiveTextMuted,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}
