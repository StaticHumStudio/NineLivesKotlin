package com.ninelivesaudio.app.ui.settings

import android.app.Activity
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.graphics.graphicsLayer
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ninelivesaudio.app.domain.model.AppMode
import com.ninelivesaudio.app.domain.model.Library
import com.ninelivesaudio.app.domain.model.ThemeMode
import com.ninelivesaudio.app.ui.components.ArchiveScreenHeader
import com.ninelivesaudio.app.ui.components.StatusPill
import com.ninelivesaudio.app.ui.copy.unhinged.CopyEngine
import com.ninelivesaudio.app.ui.copy.unhinged.CopyStyleGuide
import com.ninelivesaudio.app.ui.theme.NineLivesTheme
import com.ninelivesaudio.app.ui.theme.unhinged.*

/**
 * Whether to show the library selector. Driven purely by how many libraries are
 * cached, not by connectivity: switching only persists the choice and the list
 * is local, so the picker must remain reachable offline (airplane mode).
 */
internal fun shouldShowLibrarySelector(libraryCount: Int): Boolean = libraryCount > 1

@Composable
fun SettingsScreen(
    onNavigateToDossier: () -> Unit = {},
    onNavigateToLicenses: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current

    // SAF folder picker launcher
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data
            if (uri != null) {
                val resultFlags = result.data?.flags ?: 0
                val hasReadGrant = resultFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
                val hasWriteGrant = resultFlags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0
                val persistableFlags = when {
                    hasReadGrant && hasWriteGrant ->
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    hasReadGrant -> Intent.FLAG_GRANT_READ_URI_PERMISSION
                    hasWriteGrant -> Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    else -> 0
                }

                if (persistableFlags == 0) {
                    viewModel.onLocalFolderPermissionFailed("The selected folder did not grant persistent access.")
                } else {
                    try {
                        context.contentResolver.takePersistableUriPermission(uri, persistableFlags)
                        viewModel.onLocalFolderPicked(uri.toString())
                    } catch (e: SecurityException) {
                        viewModel.onLocalFolderPermissionFailed(e.message ?: "Folder permission could not be saved.")
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NineLivesTheme.colors.archiveVoidDeep)
    ) {
        // ─── Unified Header ───────────────────────────────────────────
        ArchiveScreenHeader(
            title = CopyStyleGuide.Settings.SETTINGS_NAV,
            subtitle = CopyEngine.getSubtitle(
                CopyStyleGuide.Settings.SETTINGS_NAV_RITUAL,
                CopyStyleGuide.Settings.SETTINGS_NAV_UNHINGED,
            ),
            trailing = {
                StatusPill(
                    connectionStatus = uiState.connectionStatus,
                    isLocalMode = uiState.appMode == AppMode.LOCAL,
                )
            },
        )

        // ─── Scrollable Content ───────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // ─── Messages ─────────────────────────────────────────────
            AnimatedVisibility(visible = uiState.errorMessage != null) {
                MessageCard(
                    message = uiState.errorMessage ?: "",
                    isError = true,
                    onDismiss = viewModel::dismissError,
                )
            }

            AnimatedVisibility(visible = uiState.successMessage != null) {
                MessageCard(
                    message = uiState.successMessage ?: "",
                    isError = false,
                    onDismiss = viewModel::dismissSuccess,
                )
            }

            // ═════════════════════════════════════════════════════════════════
            //  Group 0: Source Mode
            // ═════════════════════════════════════════════════════════════════
            SettingsGroup(title = "Source") {
                SourceModeToggle(
                    currentMode = uiState.appMode,
                    onModeSelected = viewModel::switchMode,
                )
            }

            // ═════════════════════════════════════════════════════════════════
            //  Group 1: Connection (ABS mode) or Local Folders (LOCAL mode)
            // ═════════════════════════════════════════════════════════════════
            if (uiState.appMode == AppMode.LOCAL) {
                SettingsGroup(title = "Local Folders") {
                    LocalLoadingGuide(
                        startExpanded = localGuideStartsExpanded(
                            hasLocalLibraries = uiState.localLibraries.isNotEmpty(),
                        ),
                    )
                    LocalFoldersSection(
                        localLibraries = uiState.localLibraries,
                        selectedLocalLibrary = uiState.selectedLocalLibrary,
                        isScanning = uiState.isScanning,
                        lastScanMessage = uiState.lastScanMessage,
                        onAddFolder = {
                            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                                addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
                            }
                            folderPickerLauncher.launch(intent)
                        },
                        onRescan = viewModel::rescanLocalLibrary,
                        onRemove = viewModel::removeLocalLibrary,
                        onSelect = viewModel::onLocalLibrarySelected,
                    )
                }
            } else {
            SettingsGroup(title = "Connection") {
                ServerConnectionGuide(
                    startExpanded = serverGuideStartsExpanded(isConnected = uiState.isConnected),
                    onOpenSite = { uriHandler.openUri("https://www.audiobookshelf.org") },
                )
                // Connection status
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StatusPill(
                        connectionStatus = uiState.connectionStatus,
                        isLocalMode = uiState.appMode == AppMode.LOCAL,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = uiState.connectionStatusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = NineLivesTheme.colors.archiveTextSecondary,
                    )
                }

                HorizontalDivider(color = NineLivesTheme.colors.archiveVoidElevated, thickness = 1.dp)

                // Server URL field
                CosmicTextField(
                    value = uiState.serverUrl,
                    onValueChange = viewModel::onServerUrlChanged,
                    label = "Server URL",
                    placeholder = "https://your-server.com",
                    enabled = !uiState.isConnected,
                    leadingIcon = Icons.Outlined.Dns,
                    keyboardType = KeyboardType.Uri,
                )

                HorizontalDivider(color = NineLivesTheme.colors.archiveVoidElevated, thickness = 1.dp)

                // Auth mode toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Use API Token",
                            style = MaterialTheme.typography.bodyMedium,
                            color = NineLivesTheme.colors.archiveTextPrimary,
                        )
                        Text(
                            text = "Login with a pre-generated API token",
                            style = MaterialTheme.typography.bodySmall,
                            color = NineLivesTheme.colors.archiveTextMuted,
                        )
                    }
                    Switch(
                        checked = uiState.useApiToken,
                        onCheckedChange = viewModel::onUseApiTokenChanged,
                        enabled = !uiState.isConnected,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = NineLivesTheme.colors.goldFilament,
                            checkedTrackColor = NineLivesTheme.colors.goldFilamentFaint,
                            uncheckedThumbColor = NineLivesTheme.colors.archiveTextSecondary,
                            uncheckedTrackColor = NineLivesTheme.colors.archiveVoidElevated,
                        ),
                    )
                }

                if (uiState.useApiToken) {
                    CosmicTextField(
                        value = uiState.apiToken,
                        onValueChange = viewModel::onApiTokenChanged,
                        label = "API Token",
                        placeholder = "Paste token from ABS settings",
                        enabled = !uiState.isConnected,
                        leadingIcon = Icons.Outlined.Key,
                        isPassword = true,
                    )
                } else {
                    CosmicTextField(
                        value = uiState.username,
                        onValueChange = viewModel::onUsernameChanged,
                        label = "Username",
                        placeholder = "Enter username",
                        enabled = !uiState.isConnected,
                        leadingIcon = Icons.Outlined.Person,
                    )
                    CosmicTextField(
                        value = uiState.password,
                        onValueChange = viewModel::onPasswordChanged,
                        label = "Password",
                        placeholder = "Enter password",
                        enabled = !uiState.isConnected,
                        leadingIcon = Icons.Outlined.Lock,
                        isPassword = true,
                    )
                }

                HorizontalDivider(color = NineLivesTheme.colors.archiveVoidElevated, thickness = 1.dp)

                // Self-signed certificates
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Allow Self-Signed Certificates",
                            style = MaterialTheme.typography.bodyMedium,
                            color = NineLivesTheme.colors.archiveTextPrimary,
                        )
                        Text(
                            text = "Required for TOFU on self-hosted servers",
                            style = MaterialTheme.typography.bodySmall,
                            color = NineLivesTheme.colors.archiveTextMuted,
                        )
                    }
                    Switch(
                        checked = uiState.allowSelfSignedCertificates,
                        onCheckedChange = viewModel::onAllowSelfSignedChanged,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = NineLivesTheme.colors.goldFilament,
                            checkedTrackColor = NineLivesTheme.colors.goldFilamentFaint,
                            uncheckedThumbColor = NineLivesTheme.colors.archiveTextSecondary,
                            uncheckedTrackColor = NineLivesTheme.colors.archiveVoidElevated,
                        ),
                    )
                }

                Text(
                    text = if (uiState.hasTrustedFingerprint) {
                        "Trusted fingerprint saved for ${uiState.trustedFingerprintHost}."
                    } else {
                        "No trusted fingerprint stored yet for this host."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = NineLivesTheme.colors.archiveTextMuted,
                )

                TextButton(
                    onClick = viewModel::resetTrustedCertificateFingerprint,
                    enabled = uiState.allowSelfSignedCertificates && uiState.trustedFingerprintHost != null,
                ) {
                    Icon(Icons.Outlined.RestartAlt, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Reset trusted fingerprint")
                }

                HorizontalDivider(color = NineLivesTheme.colors.archiveVoidElevated, thickness = 1.dp)

                // Connect / Disconnect buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (!uiState.isConnected) {
                        Button(
                            onClick = viewModel::connect,
                            modifier = Modifier.weight(1f),
                            enabled = !uiState.isConnecting,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NineLivesTheme.colors.goldFilament,
                                contentColor = NineLivesTheme.colors.archiveVoidDeep,
                                disabledContainerColor = NineLivesTheme.colors.goldFilamentDim,
                            ),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            if (uiState.isConnecting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = NineLivesTheme.colors.archiveVoidDeep,
                                    strokeWidth = 2.dp,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(
                                text = if (uiState.isConnecting) "Connecting..." else "Connect",
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    } else {
                        Button(
                            onClick = viewModel::refreshConnection,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NineLivesTheme.colors.archiveVoidSurface,
                                contentColor = NineLivesTheme.colors.goldFilament,
                            ),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(
                                Icons.Outlined.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Refresh", fontWeight = FontWeight.SemiBold)
                        }

                        Button(
                            onClick = viewModel::testConnection,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NineLivesTheme.colors.archiveVoidSurface,
                                contentColor = NineLivesTheme.colors.goldFilament,
                            ),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(
                                Icons.Outlined.NetworkCheck,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Test", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                if (uiState.isConnected) {
                    Button(
                        onClick = viewModel::disconnect,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NineLivesTheme.colors.archiveError.copy(alpha = 0.15f),
                            contentColor = NineLivesTheme.colors.archiveError,
                        ),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("Disconnect", fontWeight = FontWeight.SemiBold)
                    }
                }

                // Library Selector (whenever more than one library is cached).
                // Not gated on connectivity: selecting a library only persists the
                // choice (no network), and the list comes from cache, so the picker
                // must stay reachable in airplane mode.
                if (shouldShowLibrarySelector(uiState.libraries.size)) {
                    HorizontalDivider(color = NineLivesTheme.colors.archiveVoidElevated, thickness = 1.dp)

                    var libraryExpanded by remember { mutableStateOf(false) }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { libraryExpanded = true }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            @Suppress("DEPRECATION") Icons.Outlined.LibraryBooks,
                            contentDescription = null,
                            tint = NineLivesTheme.colors.goldFilament,
                            modifier = Modifier.size(20.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = uiState.selectedLibrary?.name ?: "Select Library",
                                style = MaterialTheme.typography.bodyMedium,
                                color = NineLivesTheme.colors.archiveTextPrimary,
                            )
                            Text(
                                text = "${uiState.libraries.size} libraries available",
                                style = MaterialTheme.typography.bodySmall,
                                color = NineLivesTheme.colors.archiveTextMuted,
                            )
                        }
                        Icon(
                            if (libraryExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            contentDescription = null,
                            tint = NineLivesTheme.colors.archiveTextSecondary,
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    DropdownMenu(
                        expanded = libraryExpanded,
                        onDismissRequest = { libraryExpanded = false },
                        containerColor = NineLivesTheme.colors.archiveVoidSurface,
                    ) {
                        uiState.libraries.forEach { library ->
                            val isSelected = library.id == uiState.selectedLibrary?.id
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = library.name,
                                        color = if (isSelected) NineLivesTheme.colors.goldFilament else NineLivesTheme.colors.archiveTextPrimary,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    )
                                },
                                onClick = {
                                    viewModel.onLibrarySelected(library)
                                    libraryExpanded = false
                                },
                                leadingIcon = {
                                    Icon(
                                        if (isSelected) Icons.Outlined.CheckCircle else Icons.Outlined.Circle,
                                        contentDescription = null,
                                        tint = if (isSelected) NineLivesTheme.colors.goldFilament else NineLivesTheme.colors.archiveTextMuted,
                                        modifier = Modifier.size(18.dp),
                                    )
                                },
                            )
                        }
                    }
                }
                }
            } // end else (ABS mode)

            // ═════════════════════════════════════════════════════════════
            //  Group 2: Experience
            // ═════════════════════════════════════════════════════════════
            SettingsGroup(title = "Experience") {
                ArchivePreferencesSection(
                    anomaliesEnabled = uiState.anomaliesEnabled,
                    whispersEnabled = uiState.whispersEnabled,
                    reduceMotionRequested = uiState.reduceMotionRequested,
                    sessionCount = uiState.sessionCount,
                    onToggleAnomalies = viewModel::toggleAnomalies,
                    onToggleWhispers = viewModel::toggleWhispers,
                    onToggleReduceMotion = viewModel::toggleReduceMotion,
                )
            }

            // ═════════════════════════════════════════════════════════════
            //  Group: Appearance
            // ═════════════════════════════════════════════════════════════
            SettingsGroup(title = "Appearance") {
                ThemeSelectorSection(
                    selected = uiState.themeMode,
                    onThemeSelected = viewModel::setThemeMode,
                )
            }

            // ═════════════════════════════════════════════════════════════
            //  Group 3: Audio
            // ═════════════════════════════════════════════════════════════
            SettingsGroup(title = "Audio") {
                SectionLabel("Equalizer")

                EqualizerSection(
                    eqEnabled = uiState.eqEnabled,
                    bandGains = uiState.eqBandGains,
                    bandFrequencies = uiState.eqBandFrequencies,
                    bandRange = uiState.eqBandRange,
                    onToggleEq = viewModel::toggleEq,
                    onBandGainChange = viewModel::setEqBandGain,
                    onResetEq = viewModel::resetEq,
                )

                HorizontalDivider(color = NineLivesTheme.colors.archiveVoidElevated, thickness = 1.dp)

                SectionLabel("Playback Behavior")

                PlaybackBehaviorSection(
                    autoRewindEnabled = uiState.autoRewindEnabled,
                    autoRewindMode = uiState.autoRewindMode,
                    autoRewindSeconds = uiState.autoRewindSeconds,
                    onAutoRewindEnabledChange = viewModel::setAutoRewindEnabled,
                    onAutoRewindModeChange = viewModel::setAutoRewindMode,
                    onAutoRewindSecondsChange = viewModel::setAutoRewindSeconds,
                )

                HorizontalDivider(color = NineLivesTheme.colors.archiveVoidElevated, thickness = 1.dp)

                SectionLabel("Sleep Timer")

                SleepTimerSettingsSection(
                    motionEnabled = uiState.sleepTimerMotionEnabled,
                    shakeResetEnabled = uiState.sleepTimerShakeResetEnabled,
                    rewindSeconds = uiState.sleepTimerRewindSeconds,
                    onMotionEnabledChange = viewModel::setSleepTimerMotionEnabled,
                    onShakeResetEnabledChange = viewModel::setSleepTimerShakeResetEnabled,
                    onRewindSecondsChange = viewModel::setSleepTimerRewindSeconds,
                )
            }

            // ═════════════════════════════════════════════════════════════
            // ═════════════════════════════════════════════════════════════
            //  Group 4: Data (ABS mode only)
            // ═════════════════════════════════════════════════════════════
            if (uiState.appMode == AppMode.AUDIOBOOKSHELF) {
            SettingsGroup(title = "Data") {
                Button(
                    onClick = viewModel::syncNow,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = uiState.isConnected && !uiState.isSyncing,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NineLivesTheme.colors.archiveVoidElevated,
                        contentColor = NineLivesTheme.colors.goldFilament,
                        disabledContainerColor = NineLivesTheme.colors.archiveVoidElevated.copy(alpha = 0.5f),
                        disabledContentColor = NineLivesTheme.colors.archiveTextMuted,
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    if (uiState.isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = NineLivesTheme.colors.goldFilament,
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Syncing...", fontWeight = FontWeight.SemiBold)
                    } else {
                        Icon(
                            Icons.Outlined.Sync,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Sync Now", fontWeight = FontWeight.SemiBold)
                    }
                }

                HorizontalDivider(color = NineLivesTheme.colors.archiveVoidElevated, thickness = 1.dp)

                OutlinedButton(
                    onClick = viewModel::clearCache,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                        brush = androidx.compose.ui.graphics.SolidColor(NineLivesTheme.colors.archiveVoidElevated)
                    ),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = NineLivesTheme.colors.archiveTextSecondary,
                    ),
                ) {
                    Icon(
                        Icons.Outlined.DeleteSweep,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Clear Cache")
                }
            }
            }

            // ═════════════════════════════════════════════════════════════
            //  Group 5: About
            // ═════════════════════════════════════════════════════════════
            SettingsGroup(title = "About") {
                DiagnosticRow("App Version", uiState.appVersion)
                DiagnosticRow("Settings File", uiState.settingsFilePath.ifEmpty { "(default)" })

                HorizontalDivider(color = NineLivesTheme.colors.archiveVoidElevated, thickness = 1.dp)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Studio",
                        style = MaterialTheme.typography.bodySmall,
                        color = NineLivesTheme.colors.archiveTextMuted,
                    )
                    Text(
                        text = "StaticHum.Studio",
                        style = MaterialTheme.typography.bodySmall.copy(
                            textDecoration = TextDecoration.Underline,
                        ),
                        color = NineLivesTheme.colors.goldFilament,
                        modifier = Modifier.clickable {
                            uriHandler.openUri("https://statichum.studio")
                        },
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Privacy Policy",
                        style = MaterialTheme.typography.bodySmall,
                        color = NineLivesTheme.colors.archiveTextMuted,
                    )
                    Text(
                        text = "View",
                        style = MaterialTheme.typography.bodySmall.copy(
                            textDecoration = TextDecoration.Underline,
                        ),
                        color = NineLivesTheme.colors.goldFilament,
                        modifier = Modifier.clickable {
                            uriHandler.openUri("https://statichum.studio/apps/nine-lives/privacy")
                        },
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToLicenses() },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Open Source Licenses",
                        style = MaterialTheme.typography.bodySmall,
                        color = NineLivesTheme.colors.archiveTextMuted,
                    )
                    Text(
                        text = "View",
                        style = MaterialTheme.typography.bodySmall.copy(
                            textDecoration = TextDecoration.Underline,
                        ),
                        color = NineLivesTheme.colors.goldFilament,
                    )
                }

                HorizontalDivider(color = NineLivesTheme.colors.archiveVoidElevated, thickness = 1.dp)

                // Feedback
                SectionLabel("Feedback & Reports")

                FeedbackSection(
                    reportType = uiState.reportType,
                    includeLogs = uiState.includeLogsInReport,
                    isCollecting = uiState.isCollectingReport,
                    onReportTypeChanged = viewModel::onReportTypeChanged,
                    onIncludeLogsChanged = viewModel::onIncludeLogsChanged,
                    onSubmit = {
                        viewModel.buildReport { subject, body ->
                            val intent = Intent(Intent.ACTION_SENDTO).apply {
                                data = Uri.parse("mailto:")
                                putExtra(Intent.EXTRA_EMAIL, arrayOf("Static@StaticHum.Studio"))
                                putExtra(Intent.EXTRA_SUBJECT, subject)
                                putExtra(Intent.EXTRA_TEXT, body)
                            }
                            if (intent.resolveActivity(context.packageManager) != null) {
                                context.startActivity(intent)
                            } else {
                                val fallback = Intent(Intent.ACTION_SEND).apply {
                                    type = "message/rfc822"
                                    putExtra(Intent.EXTRA_EMAIL, arrayOf("Static@StaticHum.Studio"))
                                    putExtra(Intent.EXTRA_SUBJECT, subject)
                                    putExtra(Intent.EXTRA_TEXT, body)
                                }
                                context.startActivity(Intent.createChooser(fallback, "Send report via"))
                            }
                        }
                    },
                )

                HorizontalDivider(color = NineLivesTheme.colors.archiveVoidElevated, thickness = 1.dp)

                // Nightwatch Dossier
                SectionLabel("Nightwatch Dossier")

                Button(
                    onClick = onNavigateToDossier,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NineLivesTheme.colors.archiveVoidElevated,
                        contentColor = NineLivesTheme.colors.goldFilament,
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(
                        Icons.Outlined.Analytics,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Open Dossier", fontWeight = FontWeight.SemiBold)
                }
                Text(
                    text = "Listening stats, behavioral patterns, and temporal analysis",
                    style = MaterialTheme.typography.bodySmall,
                    color = NineLivesTheme.colors.archiveTextMuted,
                )
            }

            // Bottom padding for mini player space
            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

// ─── Settings Group Card ──────────────────────────────────────────────────

@Composable
private fun SettingsGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = NineLivesTheme.colors.goldFilament,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Card(
            colors = CardDefaults.cardColors(containerColor = NineLivesTheme.colors.archiveVoidSurface),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
}

// ─── Source Mode Toggle ───────────────────────────────────────────────────

@Composable
private fun SourceModeToggle(
    currentMode: AppMode,
    onModeSelected: (AppMode) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Library Source",
            style = MaterialTheme.typography.bodyMedium,
            color = NineLivesTheme.colors.archiveTextPrimary,
        )
        Text(
            text = "Where your audiobooks live",
            style = MaterialTheme.typography.bodySmall,
            color = NineLivesTheme.colors.archiveTextMuted,
        )

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            data class ModeOption(
                val mode: AppMode,
                val label: String,
                val icon: ImageVector,
            )
            val options = listOf(
                ModeOption(AppMode.AUDIOBOOKSHELF, "Server", Icons.Outlined.Dns),
                ModeOption(AppMode.LOCAL, "Local Files", Icons.Outlined.FolderOpen),
            )

            options.forEach { option ->
                val isSelected = currentMode == option.mode
                FilterChip(
                    selected = isSelected,
                    onClick = { onModeSelected(option.mode) },
                    label = {
                        Text(
                            option.label,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            option.icon,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    modifier = Modifier.weight(1f),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = NineLivesTheme.colors.goldFilamentFaint,
                        selectedLabelColor = NineLivesTheme.colors.goldFilament,
                        selectedLeadingIconColor = NineLivesTheme.colors.goldFilament,
                        containerColor = NineLivesTheme.colors.archiveVoidElevated,
                        labelColor = NineLivesTheme.colors.archiveTextSecondary,
                        iconColor = NineLivesTheme.colors.archiveTextMuted,
                    ),
                )
            }
        }
    }
}

// ─── Theme Selector ───────────────────────────────────────────────────────

@Composable
private fun ThemeSelectorSection(
    selected: ThemeMode,
    onThemeSelected: (ThemeMode) -> Unit,
) {
    data class ThemeOption(
        val mode: ThemeMode,
        val label: String,
        val description: String,
    )

    val options = listOf(
        ThemeOption(ThemeMode.NOIR, "Noir", "Deep indigo void, gold filament"),
        ThemeOption(ThemeMode.CANDLELIGHT, "Candlelight", "Warm sepia, amber glow"),
        ThemeOption(ThemeMode.AMOLED, "AMOLED", "True black, saves OLED battery"),
        ThemeOption(ThemeMode.BRIGHT, "Bright", "Light parchment, bronze accent"),
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Theme",
            style = MaterialTheme.typography.bodyMedium,
            color = NineLivesTheme.colors.archiveTextPrimary,
        )
        Text(
            text = "Color palette for the whole app",
            style = MaterialTheme.typography.bodySmall,
            color = NineLivesTheme.colors.archiveTextMuted,
        )

        Spacer(modifier = Modifier.height(4.dp))

        options.forEach { option ->
            val isSelected = selected == option.mode
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onThemeSelected(option.mode) }
                    .padding(vertical = 6.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    if (isSelected) Icons.Outlined.CheckCircle else Icons.Outlined.Circle,
                    contentDescription = null,
                    tint = if (isSelected) NineLivesTheme.colors.goldFilament else NineLivesTheme.colors.archiveTextMuted,
                    modifier = Modifier.size(20.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = option.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isSelected) NineLivesTheme.colors.goldFilament else NineLivesTheme.colors.archiveTextPrimary,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    Text(
                        text = option.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = NineLivesTheme.colors.archiveTextMuted,
                    )
                }
            }
        }
    }
}

// ─── Collapsible Guide Scaffold ───────────────────────────────────────────

@Composable
private fun CollapsibleGuide(
    startExpanded: Boolean,
    content: @Composable () -> Unit,
) {
    var expanded by remember(startExpanded) { mutableStateOf(startExpanded) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "How it works",
                style = MaterialTheme.typography.bodyMedium,
                color = NineLivesTheme.colors.archiveTextPrimary,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = NineLivesTheme.colors.archiveTextMuted,
                modifier = Modifier.size(20.dp),
            )
        }
        AnimatedVisibility(visible = expanded) {
            content()
        }
    }
}

// ─── Local Loading Guide ──────────────────────────────────────────────────

@Composable
private fun LocalLoadingGuide(
    startExpanded: Boolean,
) {
    CollapsibleGuide(startExpanded = startExpanded) {
        Text(
            text = "Point Nine Lives at one folder that holds your audiobooks. " +
                "Two ways to arrange what’s inside:\n\n" +
                "• A folder per book — each book gets its own folder of audio files. " +
                "The folder name becomes the title. For multi-part books, tag the tracks " +
                "or name them 01, 02… so they play in order. Drop a cover.jpg or folder.jpg in for art.\n\n" +
                "• Single files — a lone .m4b or .mp3 sitting loose in that folder becomes its own book.\n\n" +
                "Only the top folder is read, one level down. " +
                "Supported: m4b, m4a, mp3, opus, ogg, flac, aac, wma, wav.",
            style = MaterialTheme.typography.bodySmall,
            color = NineLivesTheme.colors.archiveTextMuted,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

// ─── Server Connection Guide ──────────────────────────────────────────────

@Composable
private fun ServerConnectionGuide(
    startExpanded: Boolean,
    onOpenSite: () -> Unit,
) {
    CollapsibleGuide(startExpanded = startExpanded) {
        Column(
            modifier = Modifier.padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Audiobookshelf is a free, open-source media server you host yourself, " +
                    "on a home PC, a NAS, or a small box. It keeps your audiobooks and podcasts " +
                    "in one place and tracks your progress. Point Nine Lives at your server’s " +
                    "address and sign in, and your library streams here with progress synced " +
                    "across your devices.\n\nNew to it? Set up a server first, then come back and connect.",
                style = MaterialTheme.typography.bodySmall,
                color = NineLivesTheme.colors.archiveTextMuted,
            )
            Text(
                text = "Open audiobookshelf.org",
                style = MaterialTheme.typography.bodyMedium,
                color = NineLivesTheme.colors.goldFilament,
                fontWeight = FontWeight.SemiBold,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable { onOpenSite() },
            )
        }
    }
}

// ─── Local Folders Section ────────────────────────────────────────────────

@Composable
private fun LocalFoldersSection(
    localLibraries: List<Library>,
    selectedLocalLibrary: Library?,
    isScanning: Boolean,
    lastScanMessage: String?,
    onAddFolder: () -> Unit,
    onRescan: (Library) -> Unit,
    onRemove: (Library) -> Unit,
    onSelect: (Library) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Pick folders containing your audiobooks. Each subfolder becomes one book.",
            style = MaterialTheme.typography.bodySmall,
            color = NineLivesTheme.colors.archiveTextMuted,
        )

        // Scanning indicator
        AnimatedVisibility(visible = isScanning) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(NineLivesTheme.colors.goldFilamentFaint.copy(alpha = 0.15f))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = NineLivesTheme.colors.goldFilament,
                    strokeWidth = 2.dp,
                )
                Text(
                    text = "Scanning folder...",
                    style = MaterialTheme.typography.bodySmall,
                    color = NineLivesTheme.colors.goldFilament,
                )
            }
        }

        // Scan result message
        AnimatedVisibility(visible = lastScanMessage != null && !isScanning) {
            Text(
                text = lastScanMessage ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = NineLivesTheme.colors.archiveTextSecondary,
            )
        }

        // Added folders list
        if (localLibraries.isNotEmpty()) {
            HorizontalDivider(color = NineLivesTheme.colors.archiveVoidElevated, thickness = 1.dp)

            localLibraries.forEach { library ->
                val isSelected = library.id == selectedLocalLibrary?.id
                var showConfirmRemove by remember { mutableStateOf(false) }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (isSelected) NineLivesTheme.colors.goldFilamentFaint.copy(alpha = 0.08f)
                            else androidx.compose.ui.graphics.Color.Transparent
                        )
                        .clickable { onSelect(library) }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        if (isSelected) Icons.Outlined.FolderSpecial else Icons.Outlined.Folder,
                        contentDescription = null,
                        tint = if (isSelected) NineLivesTheme.colors.goldFilament else NineLivesTheme.colors.archiveTextSecondary,
                        modifier = Modifier.size(20.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = library.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isSelected) NineLivesTheme.colors.goldFilament else NineLivesTheme.colors.archiveTextPrimary,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        library.folderUri?.let { uri ->
                            Text(
                                text = Uri.parse(uri).lastPathSegment
                                    ?.substringAfterLast(':') ?: uri,
                                style = MaterialTheme.typography.bodySmall,
                                color = NineLivesTheme.colors.archiveTextMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    // Rescan button
                    IconButton(
                        onClick = { onRescan(library) },
                        enabled = !isScanning,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Refresh,
                            contentDescription = "Rescan",
                            tint = NineLivesTheme.colors.archiveTextSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    }

                    // Remove button (with confirmation)
                    if (showConfirmRemove) {
                        IconButton(
                            onClick = {
                                showConfirmRemove = false
                                onRemove(library)
                            },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                Icons.Outlined.DeleteForever,
                                contentDescription = "Confirm remove",
                                tint = NineLivesTheme.colors.archiveError,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    } else {
                        IconButton(
                            onClick = { showConfirmRemove = true },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = "Remove",
                                tint = NineLivesTheme.colors.archiveTextMuted,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }

        // Add folder button
        Button(
            onClick = onAddFolder,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isScanning,
            colors = ButtonDefaults.buttonColors(
                containerColor = NineLivesTheme.colors.goldFilament,
                contentColor = NineLivesTheme.colors.archiveVoidDeep,
                disabledContainerColor = NineLivesTheme.colors.goldFilamentDim,
            ),
            shape = RoundedCornerShape(12.dp),
        ) {
            Icon(
                Icons.Outlined.CreateNewFolder,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Add Folder", fontWeight = FontWeight.SemiBold)
        }
    }
}

// ─── Section Label (inside a group) ──────────────────────────────────────

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = NineLivesTheme.colors.archiveTextSecondary,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.5.sp,
    )
}

// ─── Cosmic Text Field ───────────────────────────────────────────────────

@Composable
private fun CosmicTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    var passwordVisible by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = NineLivesTheme.colors.archiveTextSecondary,
            fontSize = 12.sp,
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            placeholder = {
                Text(
                    text = placeholder,
                    color = NineLivesTheme.colors.archiveTextMuted,
                )
            },
            leadingIcon = leadingIcon?.let {
                {
                    Icon(
                        imageVector = it,
                        contentDescription = null,
                        tint = if (enabled) NineLivesTheme.colors.archiveTextSecondary else NineLivesTheme.colors.archiveTextMuted,
                        modifier = Modifier.size(20.dp),
                    )
                }
            },
            trailingIcon = if (isPassword) {
                {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible)
                                Icons.Outlined.VisibilityOff
                            else
                                Icons.Outlined.Visibility,
                            contentDescription = if (passwordVisible) "Hide password" else "Show password",
                            tint = NineLivesTheme.colors.archiveTextMuted,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            } else null,
            visualTransformation = if (isPassword && !passwordVisible)
                PasswordVisualTransformation()
            else
                VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboardType,
                imeAction = ImeAction.Next,
            ),
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = NineLivesTheme.colors.goldFilament,
                unfocusedBorderColor = NineLivesTheme.colors.archiveVoidElevated,
                disabledBorderColor = NineLivesTheme.colors.archiveVoidElevated.copy(alpha = 0.5f),
                focusedContainerColor = NineLivesTheme.colors.archiveVoidSurface,
                unfocusedContainerColor = NineLivesTheme.colors.archiveVoidSurface,
                disabledContainerColor = NineLivesTheme.colors.archiveVoidSurface.copy(alpha = 0.5f),
                focusedTextColor = NineLivesTheme.colors.archiveTextPrimary,
                unfocusedTextColor = NineLivesTheme.colors.archiveTextPrimary,
                disabledTextColor = NineLivesTheme.colors.archiveTextSecondary,
                cursorColor = NineLivesTheme.colors.goldFilament,
            ),
        )
    }
}

// ─── Message Card ─────────────────────────────────────────────────────────

@Composable
private fun MessageCard(
    message: String,
    isError: Boolean,
    onDismiss: () -> Unit,
) {
    val bgColor = if (isError) NineLivesTheme.colors.archiveError.copy(alpha = 0.1f) else NineLivesTheme.colors.archiveSuccess.copy(alpha = 0.1f)
    val borderColor = if (isError) NineLivesTheme.colors.archiveError.copy(alpha = 0.3f) else NineLivesTheme.colors.archiveSuccess.copy(alpha = 0.3f)
    val textColor = if (isError) NineLivesTheme.colors.archiveError else NineLivesTheme.colors.archiveSuccess
    val icon = if (isError) Icons.Outlined.ErrorOutline else Icons.Outlined.CheckCircle

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = textColor,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = textColor,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.size(24.dp),
        ) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = "Dismiss",
                tint = textColor,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

// ─── Diagnostic Row ───────────────────────────────────────────────────────

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = NineLivesTheme.colors.archiveTextMuted,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = NineLivesTheme.colors.archiveTextPrimary,
        )
    }
}

// ─── Feedback & Reports ──────────────────────────────────────────────────

@Composable
private fun FeedbackSection(
    reportType: SettingsViewModel.ReportType,
    includeLogs: Boolean,
    isCollecting: Boolean,
    onReportTypeChanged: (SettingsViewModel.ReportType) -> Unit,
    onIncludeLogsChanged: (Boolean) -> Unit,
    onSubmit: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Report type selector
        Text(
            text = "Report Type",
            style = MaterialTheme.typography.bodyMedium,
            color = NineLivesTheme.colors.archiveTextPrimary,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SettingsViewModel.ReportType.entries.forEach { type ->
                FilterChip(
                    selected = reportType == type,
                    onClick = { onReportTypeChanged(type) },
                    label = { Text(type.label) },
                    leadingIcon = if (reportType == type) {
                        {
                            Icon(
                                imageVector = when (type) {
                                    SettingsViewModel.ReportType.BUG -> Icons.Outlined.BugReport
                                    SettingsViewModel.ReportType.UPGRADE -> Icons.Outlined.RocketLaunch
                                },
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    } else null,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = NineLivesTheme.colors.goldFilamentFaint,
                        selectedLabelColor = NineLivesTheme.colors.goldFilament,
                        selectedLeadingIconColor = NineLivesTheme.colors.goldFilament,
                        containerColor = NineLivesTheme.colors.archiveVoidElevated,
                        labelColor = NineLivesTheme.colors.archiveTextSecondary,
                    ),
                )
            }
        }

        HorizontalDivider(color = NineLivesTheme.colors.archiveVoidElevated, thickness = 1.dp)

        // Include logs toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Attach Optional Logs",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NineLivesTheme.colors.archiveTextPrimary,
                )
                Text(
                    text = "Adds recent app logs to the email report",
                    style = MaterialTheme.typography.bodySmall,
                    color = NineLivesTheme.colors.archiveTextMuted,
                )
            }
            Switch(
                checked = includeLogs,
                onCheckedChange = onIncludeLogsChanged,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = NineLivesTheme.colors.goldFilament,
                    checkedTrackColor = NineLivesTheme.colors.goldFilamentFaint,
                    uncheckedThumbColor = NineLivesTheme.colors.archiveTextSecondary,
                    uncheckedTrackColor = NineLivesTheme.colors.archiveVoidElevated,
                ),
            )
        }
    }

    // Submit button
    Button(
        onClick = onSubmit,
        modifier = Modifier.fillMaxWidth(),
        enabled = !isCollecting,
        colors = ButtonDefaults.buttonColors(
            containerColor = NineLivesTheme.colors.goldFilament,
            contentColor = NineLivesTheme.colors.archiveVoidDeep,
            disabledContainerColor = NineLivesTheme.colors.goldFilamentDim,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        if (isCollecting) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = NineLivesTheme.colors.archiveVoidDeep,
                strokeWidth = 2.dp,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Collecting info...", fontWeight = FontWeight.SemiBold)
        } else {
            Icon(
                Icons.Outlined.Email,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Submit Report via Email", fontWeight = FontWeight.SemiBold)
        }
    }

    Text(
        text = "Opens your email app with device info pre-filled",
        style = MaterialTheme.typography.bodySmall,
        color = NineLivesTheme.colors.archiveTextMuted,
    )
}

// ─── Archive Preferences ──────────────────────────────────────────────────

@Composable
private fun ArchivePreferencesSection(
    anomaliesEnabled: Boolean,
    whispersEnabled: Boolean,
    reduceMotionRequested: Boolean,
    sessionCount: Int,
    onToggleAnomalies: () -> Unit,
    onToggleWhispers: () -> Unit,
    onToggleReduceMotion: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Anomalies toggle
        ArchivePreferenceRow(
            title = CopyStyleGuide.Settings.ANOMALIES_TOGGLE,
            subtitle = CopyEngine.getSubtitle(
                CopyStyleGuide.Settings.ANOMALIES_TOGGLE_DESC_RITUAL,
                CopyStyleGuide.Settings.ANOMALIES_TOGGLE_DESC_UNHINGED,
            ) ?: "Screen tears, ink bleed, crack whispers",
            checked = anomaliesEnabled,
            onCheckedChange = { onToggleAnomalies() },
        )

        HorizontalDivider(color = NineLivesTheme.colors.archiveVoidElevated, thickness = 1.dp)

        // Whispers toggle
        ArchivePreferenceRow(
            title = CopyStyleGuide.Settings.WHISPERS_TOGGLE,
            subtitle = CopyEngine.getSubtitle(
                CopyStyleGuide.Settings.WHISPERS_TOGGLE_DESC_RITUAL,
                CopyStyleGuide.Settings.WHISPERS_TOGGLE_DESC_UNHINGED,
            ) ?: "Atmospheric contextual text fragments",
            checked = whispersEnabled,
            onCheckedChange = { onToggleWhispers() },
        )

        HorizontalDivider(color = NineLivesTheme.colors.archiveVoidElevated, thickness = 1.dp)

        // Reduced Motion toggle
        ArchivePreferenceRow(
            title = "Reduced Motion",
            subtitle = "Disables all animations and effects",
            checked = reduceMotionRequested,
            onCheckedChange = { onToggleReduceMotion() },
        )

        HorizontalDivider(color = NineLivesTheme.colors.archiveVoidElevated, thickness = 1.dp)

        // Session Counter
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "Sessions Recorded",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NineLivesTheme.colors.archiveTextSecondary,
                )
                Text(
                    text = "The Archive remembers every visit",
                    style = MaterialTheme.typography.bodySmall,
                    color = NineLivesTheme.colors.archiveTextMuted,
                )
            }
            Text(
                text = sessionCount.toString(),
                style = MaterialTheme.typography.headlineSmall,
                color = NineLivesTheme.colors.goldFilament,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun ArchivePreferenceRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = NineLivesTheme.colors.archiveTextPrimary,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = NineLivesTheme.colors.archiveTextMuted,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = NineLivesTheme.colors.goldFilament,
                checkedTrackColor = NineLivesTheme.colors.goldFilamentFaint,
                uncheckedThumbColor = NineLivesTheme.colors.archiveTextSecondary,
                uncheckedTrackColor = NineLivesTheme.colors.archiveVoidElevated,
            ),
        )
    }
}

// ─── Playback Behavior Section ──────────────────────────────────────────

@Composable
private fun PlaybackBehaviorSection(
    autoRewindEnabled: Boolean,
    autoRewindMode: String,
    autoRewindSeconds: Int,
    onAutoRewindEnabledChange: (Boolean) -> Unit,
    onAutoRewindModeChange: (String) -> Unit,
    onAutoRewindSecondsChange: (Int) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
            // Toggle: Auto-Rewind on Resume
            ArchivePreferenceRow(
                title = "Auto-Rewind on Resume",
                subtitle = "Rewind when playback resumes after a pause",
                checked = autoRewindEnabled,
                onCheckedChange = onAutoRewindEnabledChange,
            )

            // Mode selector + slider (visible when enabled)
            AnimatedVisibility(visible = autoRewindEnabled) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    HorizontalDivider(color = NineLivesTheme.colors.archiveVoidElevated, thickness = 1.dp)

                    // Mode: Smart / Fixed
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf("smart" to "Smart", "flat" to "Fixed").forEach { (value, label) ->
                            FilterChip(
                                selected = autoRewindMode == value,
                                onClick = { onAutoRewindModeChange(value) },
                                label = { Text(label) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = NineLivesTheme.colors.goldFilamentFaint,
                                    selectedLabelColor = NineLivesTheme.colors.goldFilament,
                                    containerColor = NineLivesTheme.colors.archiveVoidElevated,
                                    labelColor = NineLivesTheme.colors.archiveTextSecondary,
                                ),
                            )
                        }
                    }

                    // Smart mode description
                    if (autoRewindMode == "smart") {
                        Text(
                            text = "Rewind scales with pause duration — short pauses rewind less, long pauses rewind more",
                            style = MaterialTheme.typography.bodySmall,
                            color = NineLivesTheme.colors.archiveTextMuted,
                        )
                    }

                    // Flat mode slider
                    AnimatedVisibility(visible = autoRewindMode == "flat") {
                        Column {
                            Text(
                                text = "Rewind: ${formatSeconds(autoRewindSeconds)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = NineLivesTheme.colors.goldFilament,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Slider(
                                value = autoRewindSeconds.toFloat(),
                                onValueChange = { onAutoRewindSecondsChange((it / 5).toInt() * 5) },
                                valueRange = 0f..120f,
                                steps = 23, // (120 - 0) / 5 - 1
                                colors = SliderDefaults.colors(
                                    thumbColor = NineLivesTheme.colors.goldFilament,
                                    activeTrackColor = NineLivesTheme.colors.goldFilament,
                                    inactiveTrackColor = NineLivesTheme.colors.archiveVoidElevated,
                                ),
                            )
                        }
                    }
                }
            }
        }
}

// ─── Sleep Timer Settings Section ───────────────────────────────────────

@Composable
private fun SleepTimerSettingsSection(
    motionEnabled: Boolean,
    shakeResetEnabled: Boolean,
    rewindSeconds: Int,
    onMotionEnabledChange: (Boolean) -> Unit,
    onShakeResetEnabledChange: (Boolean) -> Unit,
    onRewindSecondsChange: (Int) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
            // Motion sensing toggle
            ArchivePreferenceRow(
                title = "Motion Sensing",
                subtitle = "Keep playing if the phone is held when timer expires",
                checked = motionEnabled,
                onCheckedChange = onMotionEnabledChange,
            )

            HorizontalDivider(color = NineLivesTheme.colors.archiveVoidElevated, thickness = 1.dp)

            // Shake to reset toggle
            ArchivePreferenceRow(
                title = "Shake to Reset",
                subtitle = "Shake the phone to restart the sleep timer",
                checked = shakeResetEnabled,
                onCheckedChange = onShakeResetEnabledChange,
            )

            HorizontalDivider(color = NineLivesTheme.colors.archiveVoidElevated, thickness = 1.dp)

            // Rewind on sleep slider
            Column(
                modifier = Modifier.padding(vertical = 8.dp),
            ) {
                Text(
                    text = "Rewind on Sleep: ${formatSeconds(rewindSeconds)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NineLivesTheme.colors.archiveTextPrimary,
                )
                Text(
                    text = "How far to rewind when the sleep timer stops playback",
                    style = MaterialTheme.typography.bodySmall,
                    color = NineLivesTheme.colors.archiveTextMuted,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Slider(
                    value = rewindSeconds.toFloat(),
                    onValueChange = { onRewindSecondsChange((it / 5).toInt() * 5) },
                    valueRange = 0f..60f,
                    steps = 11, // (60 - 0) / 5 - 1
                    colors = SliderDefaults.colors(
                        thumbColor = NineLivesTheme.colors.goldFilament,
                        activeTrackColor = NineLivesTheme.colors.goldFilament,
                        inactiveTrackColor = NineLivesTheme.colors.archiveVoidElevated,
                    ),
                )
            }
        }
}

private fun formatSeconds(seconds: Int): String {
    return when {
        seconds == 0 -> "Off"
        seconds < 60 -> "${seconds}s"
        seconds % 60 == 0 -> "${seconds / 60}m"
        else -> "${seconds / 60}m ${seconds % 60}s"
    }
}

// ─── Equalizer Section ───────────────────────────────────────────────────

@Composable
private fun EqualizerSection(
    eqEnabled: Boolean,
    bandGains: List<Int>,
    bandFrequencies: List<Int>,
    bandRange: Pair<Int, Int>,
    onToggleEq: () -> Unit,
    onBandGainChange: (Int, Int) -> Unit,
    onResetEq: () -> Unit,
) {
    val (minGain, maxGain) = bandRange

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Enable toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = "5-Band Equalizer",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NineLivesTheme.colors.archiveTextPrimary,
                    )
                    Text(
                        text = "Shape the frequency response",
                        style = MaterialTheme.typography.bodySmall,
                        color = NineLivesTheme.colors.archiveTextMuted,
                    )
                }
                Switch(
                    checked = eqEnabled,
                    onCheckedChange = { onToggleEq() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = NineLivesTheme.colors.goldFilament,
                        checkedTrackColor = NineLivesTheme.colors.goldFilamentFaint,
                        uncheckedThumbColor = NineLivesTheme.colors.archiveTextSecondary,
                        uncheckedTrackColor = NineLivesTheme.colors.archiveVoidElevated,
                    ),
                )
            }

            // dB labels
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "+${maxGain / 100}dB",
                    style = MaterialTheme.typography.labelSmall,
                    color = NineLivesTheme.colors.archiveTextMuted,
                    fontSize = 9.sp,
                )
                Text(
                    text = "0dB",
                    style = MaterialTheme.typography.labelSmall,
                    color = NineLivesTheme.colors.archiveTextMuted,
                    fontSize = 9.sp,
                )
                Text(
                    text = "${minGain / 100}dB",
                    style = MaterialTheme.typography.labelSmall,
                    color = NineLivesTheme.colors.archiveTextMuted,
                    fontSize = 9.sp,
                )
            }

            // Band sliders
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                bandGains.forEachIndexed { index, gain ->
                    val freq = bandFrequencies.getOrElse(index) { 0 }
                    SettingsEqBandSlider(
                        gain = gain,
                        minGain = minGain,
                        maxGain = maxGain,
                        frequencyLabel = formatSettingsFrequency(freq),
                        enabled = eqEnabled,
                        onGainChange = { newGain -> onBandGainChange(index, newGain) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // Reset button
            OutlinedButton(
                onClick = onResetEq,
                modifier = Modifier.fillMaxWidth(),
                enabled = eqEnabled,
                shape = RoundedCornerShape(10.dp),
                border = ButtonDefaults.outlinedButtonBorder(enabled = eqEnabled).copy(
                    brush = androidx.compose.ui.graphics.SolidColor(NineLivesTheme.colors.archiveVoidElevated)
                ),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = NineLivesTheme.colors.archiveTextSecondary,
                    disabledContentColor = NineLivesTheme.colors.archiveTextMuted,
                ),
            ) {
                Text("Reset EQ")
            }
        }
}

@Composable
private fun SettingsEqBandSlider(
    gain: Int,
    minGain: Int,
    maxGain: Int,
    frequencyLabel: String,
    enabled: Boolean,
    onGainChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "${if (gain >= 0) "+" else ""}${gain / 100}",
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) NineLivesTheme.colors.archiveTextSecondary else NineLivesTheme.colors.archiveTextMuted,
            fontSize = 9.sp,
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Slider(
                value = gain.toFloat(),
                onValueChange = { onGainChange(it.toInt()) },
                valueRange = minGain.toFloat()..maxGain.toFloat(),
                enabled = enabled,
                modifier = Modifier
                    .width(160.dp)
                    .graphicsLayer { rotationZ = -90f },
                colors = SliderDefaults.colors(
                    thumbColor = if (enabled) NineLivesTheme.colors.goldFilament else NineLivesTheme.colors.archiveTextMuted,
                    activeTrackColor = if (enabled) NineLivesTheme.colors.goldFilament else NineLivesTheme.colors.archiveTextMuted,
                    inactiveTrackColor = NineLivesTheme.colors.archiveOutline,
                    disabledThumbColor = NineLivesTheme.colors.archiveTextMuted,
                    disabledActiveTrackColor = NineLivesTheme.colors.archiveTextMuted.copy(alpha = 0.5f),
                    disabledInactiveTrackColor = NineLivesTheme.colors.archiveOutline.copy(alpha = 0.5f),
                ),
            )
        }

        Text(
            text = frequencyLabel,
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) NineLivesTheme.colors.archiveTextSecondary else NineLivesTheme.colors.archiveTextMuted,
            fontSize = 9.sp,
        )
    }
}

private fun formatSettingsFrequency(hz: Int): String = when {
    hz >= 1000 -> "${hz / 1000}k"
    else -> "$hz"
}
