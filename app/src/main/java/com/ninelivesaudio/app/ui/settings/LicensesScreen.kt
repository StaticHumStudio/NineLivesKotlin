package com.ninelivesaudio.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ninelivesaudio.app.ui.theme.unhinged.*

private data class LibraryLicense(
    val name: String,
    val copyright: String,
    val url: String,
    val license: String,
)

private val thirdPartyLibraries = listOf(
    LibraryLicense(
        name = "AndroidX Libraries",
        copyright = "The Android Open Source Project",
        url = "https://developer.android.com/jetpack/androidx",
        license = "Apache License 2.0",
    ),
    LibraryLicense(
        name = "Jetpack Compose",
        copyright = "The Android Open Source Project",
        url = "https://developer.android.com/jetpack/compose",
        license = "Apache License 2.0",
    ),
    LibraryLicense(
        name = "Media3 / ExoPlayer",
        copyright = "The Android Open Source Project",
        url = "https://developer.android.com/media/media3",
        license = "Apache License 2.0",
    ),
    LibraryLicense(
        name = "Kotlin & Coroutines",
        copyright = "JetBrains s.r.o.",
        url = "https://kotlinlang.org",
        license = "Apache License 2.0",
    ),
    LibraryLicense(
        name = "Kotlinx Serialization",
        copyright = "JetBrains s.r.o.",
        url = "https://github.com/Kotlin/kotlinx.serialization",
        license = "Apache License 2.0",
    ),
    LibraryLicense(
        name = "Hilt / Dagger",
        copyright = "The Dagger Authors (Google)",
        url = "https://dagger.dev/hilt/",
        license = "Apache License 2.0",
    ),
    LibraryLicense(
        name = "Retrofit",
        copyright = "Square, Inc.",
        url = "https://square.github.io/retrofit/",
        license = "Apache License 2.0",
    ),
    LibraryLicense(
        name = "OkHttp",
        copyright = "Square, Inc.",
        url = "https://square.github.io/okhttp/",
        license = "Apache License 2.0",
    ),
    LibraryLicense(
        name = "Retrofit Kotlinx Serialization Converter",
        copyright = "Jake Wharton",
        url = "https://github.com/JakeWharton/retrofit2-kotlinx-serialization-converter",
        license = "Apache License 2.0",
    ),
    LibraryLicense(
        name = "Coil",
        copyright = "Coil Contributors",
        url = "https://coil-kt.github.io/coil/",
        license = "Apache License 2.0",
    ),
    LibraryLicense(
        name = "ACRA",
        copyright = "Kevin Gaudin & ACRA Contributors",
        url = "https://github.com/ACRA/acra",
        license = "Apache License 2.0",
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(
    onNavigateBack: () -> Unit,
) {
    Scaffold(
        containerColor = ArchiveVoidDeep,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Open Source Licenses",
                        style = MaterialTheme.typography.titleMedium,
                        color = GoldFilament,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Back",
                            tint = GoldFilament,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ArchiveVoidBase,
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "NineLives is licensed under the GNU General Public License v3.0.",
                style = MaterialTheme.typography.bodySmall,
                color = ArchiveTextSecondary,
                modifier = Modifier.padding(bottom = 4.dp),
            )

            Text(
                text = "Third-Party Libraries",
                style = MaterialTheme.typography.titleSmall,
                color = GoldFilament,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(top = 4.dp),
            )

            Card(
                colors = CardDefaults.cardColors(containerColor = ArchiveVoidSurface),
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    thirdPartyLibraries.forEachIndexed { index, lib ->
                        if (index > 0) {
                            HorizontalDivider(
                                color = ArchiveVoidElevated,
                                thickness = 1.dp,
                                modifier = Modifier.padding(vertical = 10.dp),
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = lib.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = ArchiveTextPrimary,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = lib.copyright,
                                style = MaterialTheme.typography.bodySmall,
                                color = ArchiveTextMuted,
                            )
                            Text(
                                text = lib.license,
                                style = MaterialTheme.typography.bodySmall,
                                color = GoldFilamentDim,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "A Static Hum Studio Production",
                style = MaterialTheme.typography.labelMedium,
                color = GoldFilamentDim,
                letterSpacing = 1.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
