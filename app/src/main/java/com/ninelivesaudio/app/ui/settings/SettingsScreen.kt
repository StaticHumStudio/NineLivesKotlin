package com.ninelivesaudio.app.ui.settings

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ninelivesaudio.app.domain.model.Library
import com.ninelivesaudio.app.ui.components.ArchiveScreenHeader
import com.ninelivesaudio.app.ui.components.StatusPill
import com.ninelivesaudio.app.ui.copy.unhinged.CopyEngine
import com.ninelivesaudio.app.ui.copy.unhinged.CopyStyleGuide
import com.ninelivesaudio.app.ui.theme.unhinged.*

@Composable
fun SettingsScreen(
    onNavigateToDossier: () -> Unit = {},
    onNavigateToLicenses: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ArchiveVoidDeep)
    ) {
        // ─── Unified Header ───────────────────────────────────────────
        ArchiveScreenHeader(
            title = CopyStyleGuide.Settings.SETTINGS_NAV,
            subtitle = CopyEngine.getSubtitle(
                CopyStyleGuide.Settings.SETTINGS_NAV_RITUAL,
                CopyStyleGuide.Settings.SETTINGS_NAV_UNHINGED,
            ),
            trailing = { StatusPill(connectionStatus = uiState.connectionStatus) },
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

            // ═════════════════════════════════════════════════════════════
            //  Group 1: Connection
            // ═════════════════════════════════════════════════════════════
            SettingsGroup(title = "Connection") {
                // Connection status
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StatusPill(connectionStatus = uiState.connectionStatus)
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = uiState.connectionStatusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = ArchiveTextSecondary,
                    )
                }

                HorizontalDivider(color = ArchiveVoidElevated, thickness = 1.dp)

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

                HorizontalDivider(color = ArchiveVoidElevated, thickness = 1.dp)

                // Auth mode toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Use API Token",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ArchiveTextPrimary,
                        )
                        Text(
                            text = "Login with a pre-generated API token",
                            style = MaterialTheme.typography.bodySmall,
                            color = ArchiveTextMuted,
                        )
                    }
                    Switch(
                        checked = uiState.useApiToken,
                        onCheckedChange = viewModel::onUseApiTokenChanged,
                        enabled = !uiState.isConnected,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = GoldFilament,
                            checkedTrackColor = GoldFilamentFaint,
                            uncheckedThumbColor = ArchiveTextSecondary,
                            uncheckedTrackColor = ArchiveVoidElevated,
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

                HorizontalDivider(color = ArchiveVoidElevated, thickness = 1.dp)

                // Self-signed certificates
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Allow Self-Signed Certificates",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ArchiveTextPrimary,
                        )
                        Text(
                            text = "Required for TOFU on self-hosted servers",
                            style = MaterialTheme.typography.bodySmall,
                            color = ArchiveTextMuted,
                        )
                    }
                    Switch(
                        checked = uiState.allowSelfSignedCertificates,
                        onCheckedChange = viewModel::onAllowSelfSignedChanged,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = GoldFilament,
                            checkedTrackColor = GoldFilamentFaint,
                            uncheckedThumbColor = ArchiveTextSecondary,
                            uncheckedTrackColor = ArchiveVoidElevated,
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
                    color = ArchiveTextMuted,
                )

                TextButton(
                    onClick = viewModel::resetTrustedCertificateFingerprint,
                    enabled = uiState.allowSelfSignedCertificates && uiState.trustedFingerprintHost != null,
                ) {
                    Icon(Icons.Outlined.RestartAlt, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Reset trusted fingerprint")
                }

                HorizontalDivider(color = ArchiveVoidElevated, thickness = 1.dp)

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
                                containerColor = GoldFilament,
                                contentColor = ArchiveVoidDeep,
                                disabledContainerColor = GoldFilamentDim,
                            ),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            if (uiState.isConnecting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = ArchiveVoidDeep,
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
                                containerColor = ArchiveVoidSurface,
                                contentColor = GoldFilament,
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
                                containerColor = ArchiveVoidSurface,
                                contentColor = GoldFilament,
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
                            containerColor = ArchiveError.copy(alpha = 0.15f),
                            contentColor = ArchiveError,
                        ),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("Disconnect", fontWeight = FontWeight.SemiBold)
                    }
                }

                // Library Selector (when connected with multiple libraries)
                if (uiState.isConnected && uiState.libraries.size > 1) {
                    HorizontalDivider(color = ArchiveVoidElevated, thickness = 1.dp)

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
                            tint = GoldFilament,
                            modifier = Modifier.size(20.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = uiState.selectedLibrary?.name ?: "Select Library",
                                style = MaterialTheme.typography.bodyMedium,
                                color = ArchiveTextPrimary,
                            )
                            Text(
                                text = "${uiState.libraries.size} libraries available",
                                style = MaterialTheme.typography.bodySmall,
                                color = ArchiveTextMuted,
                            )
                        }
                        Icon(
                            if (libraryExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            contentDescription = null,
                            tint = ArchiveTextSecondary,
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    DropdownMenu(
                        expanded = libraryExpanded,
                        onDismissRequest = { libraryExpanded = false },
                        containerColor = ArchiveVoidSurface,
                    ) {
                        uiState.libraries.forEach { library ->
                            val isSelected = library.id == uiState.selectedLibrary?.id
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = library.name,
                                        color = if (isSelected) GoldFilament else ArchiveTextPrimary,
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
                                        tint = if (isSelected) GoldFilament else ArchiveTextMuted,
                                        modifier = Modifier.size(18.dp),
                                    )
                                },
                            )
                        }
                    }
                }
            }

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

                HorizontalDivider(color = ArchiveVoidElevated, thickness = 1.dp)

                SectionLabel("Playback Behavior")

                PlaybackBehaviorSection(
                    autoRewindEnabled = uiState.autoRewindEnabled,
                    autoRewindMode = uiState.autoRewindMode,
                    autoRewindSeconds = uiState.autoRewindSeconds,
                    onAutoRewindEnabledChange = viewModel::setAutoRewindEnabled,
                    onAutoRewindModeChange = viewModel::setAutoRewindMode,
                    onAutoRewindSecondsChange = viewModel::setAutoRewindSeconds,
                )

                HorizontalDivider(color = ArchiveVoidElevated, thickness = 1.dp)

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
            //  Group 4: Data
            // ═════════════════════════════════════════════════════════════
            SettingsGroup(title = "Data") {
                Button(
                    onClick = viewModel::syncNow,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = uiState.isConnected && !uiState.isSyncing,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ArchiveVoidElevated,
                        contentColor = GoldFilament,
                        disabledContainerColor = ArchiveVoidElevated.copy(alpha = 0.5f),
                        disabledContentColor = ArchiveTextMuted,
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    if (uiState.isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = GoldFilament,
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

                HorizontalDivider(color = ArchiveVoidElevated, thickness = 1.dp)

                OutlinedButton(
                    onClick = viewModel::clearCache,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                        brush = androidx.compose.ui.graphics.SolidColor(ArchiveVoidElevated)
                    ),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = ArchiveTextSecondary,
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

            // ═════════════════════════════════════════════════════════════
            //  Group 5: About
            // ═════════════════════════════════════════════════════════════
            SettingsGroup(title = "About") {
                DiagnosticRow("App Version", uiState.appVersion)
                DiagnosticRow("Settings File", uiState.settingsFilePath.ifEmpty { "(default)" })

                HorizontalDivider(color = ArchiveVoidElevated, thickness = 1.dp)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Studio",
                        style = MaterialTheme.typography.bodySmall,
                        color = ArchiveTextMuted,
                    )
                    Text(
                        text = "StaticHum.Studio",
                        style = MaterialTheme.typography.bodySmall.copy(
                            textDecoration = TextDecoration.Underline,
                        ),
                        color = GoldFilament,
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
                        color = ArchiveTextMuted,
                    )
                    Text(
                        text = "View",
                        style = MaterialTheme.typography.bodySmall.copy(
                            textDecoration = TextDecoration.Underline,
                        ),
                        color = GoldFilament,
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
                        color = ArchiveTextMuted,
                    )
                    Text(
                        text = "View",
                        style = MaterialTheme.typography.bodySmall.copy(
                            textDecoration = TextDecoration.Underline,
                        ),
                        color = GoldFilament,
                    )
                }

                HorizontalDivider(color = ArchiveVoidElevated, thickness = 1.dp)

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

                HorizontalDivider(color = ArchiveVoidElevated, thickness = 1.dp)

                // Nightwatch Dossier
                SectionLabel("Nightwatch Dossier")

                Button(
                    onClick = onNavigateToDossier,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ArchiveVoidElevated,
                        contentColor = GoldFilament,
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
                    color = ArchiveTextMuted,
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
            color = GoldFilament,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Card(
            colors = CardDefaults.cardColors(containerColor = ArchiveVoidSurface),
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

// ─── Section Label (inside a group) ──────────────────────────────────────

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = ArchiveTextSecondary,
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
            color = ArchiveTextSecondary,
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
                    color = ArchiveTextMuted,
                )
            },
            leadingIcon = leadingIcon?.let {
                {
                    Icon(
                        imageVector = it,
                        contentDescription = null,
                        tint = if (enabled) ArchiveTextSecondary else ArchiveTextMuted,
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
                            tint = ArchiveTextMuted,
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
                focusedBorderColor = GoldFilament,
                unfocusedBorderColor = ArchiveVoidElevated,
                disabledBorderColor = ArchiveVoidElevated.copy(alpha = 0.5f),
                focusedContainerColor = ArchiveVoidSurface,
                unfocusedContainerColor = ArchiveVoidSurface,
                disabledContainerColor = ArchiveVoidSurface.copy(alpha = 0.5f),
                focusedTextColor = ArchiveTextPrimary,
                unfocusedTextColor = ArchiveTextPrimary,
                disabledTextColor = ArchiveTextSecondary,
                cursorColor = GoldFilament,
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
    val bgColor = if (isError) ArchiveError.copy(alpha = 0.1f) else ArchiveSuccess.copy(alpha = 0.1f)
    val borderColor = if (isError) ArchiveError.copy(alpha = 0.3f) else ArchiveSuccess.copy(alpha = 0.3f)
    val textColor = if (isError) ArchiveError else ArchiveSuccess
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
            color = ArchiveTextMuted,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = ArchiveTextPrimary,
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
            color = ArchiveTextPrimary,
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
                        selectedContainerColor = GoldFilamentFaint,
                        selectedLabelColor = GoldFilament,
                        selectedLeadingIconColor = GoldFilament,
                        containerColor = ArchiveVoidElevated,
                        labelColor = ArchiveTextSecondary,
                    ),
                )
            }
        }

        HorizontalDivider(color = ArchiveVoidElevated, thickness = 1.dp)

        // Include logs toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Attach Logs",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ArchiveTextPrimary,
                )
                Text(
                    text = "Include recent app logs for debugging",
                    style = MaterialTheme.typography.bodySmall,
                    color = ArchiveTextMuted,
                )
            }
            Switch(
                checked = includeLogs,
                onCheckedChange = onIncludeLogsChanged,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = GoldFilament,
                    checkedTrackColor = GoldFilamentFaint,
                    uncheckedThumbColor = ArchiveTextSecondary,
                    uncheckedTrackColor = ArchiveVoidElevated,
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
            containerColor = GoldFilament,
            contentColor = ArchiveVoidDeep,
            disabledContainerColor = GoldFilamentDim,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        if (isCollecting) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = ArchiveVoidDeep,
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
        color = ArchiveTextMuted,
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

        HorizontalDivider(color = ArchiveVoidElevated, thickness = 1.dp)

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

        HorizontalDivider(color = ArchiveVoidElevated, thickness = 1.dp)

        // Reduced Motion toggle
        ArchivePreferenceRow(
            title = "Reduced Motion",
            subtitle = "Disables all animations and effects",
            checked = reduceMotionRequested,
            onCheckedChange = { onToggleReduceMotion() },
        )

        HorizontalDivider(color = ArchiveVoidElevated, thickness = 1.dp)

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
                    color = ArchiveTextSecondary,
                )
                Text(
                    text = "The Archive remembers every visit",
                    style = MaterialTheme.typography.bodySmall,
                    color = ArchiveTextMuted,
                )
            }
            Text(
                text = sessionCount.toString(),
                style = MaterialTheme.typography.headlineSmall,
                color = GoldFilament,
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
                color = ArchiveTextPrimary,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = ArchiveTextMuted,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = GoldFilament,
                checkedTrackColor = GoldFilamentFaint,
                uncheckedThumbColor = ArchiveTextSecondary,
                uncheckedTrackColor = ArchiveVoidElevated,
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
                    HorizontalDivider(color = ArchiveVoidElevated, thickness = 1.dp)

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
                                    selectedContainerColor = GoldFilamentFaint,
                                    selectedLabelColor = GoldFilament,
                                    containerColor = ArchiveVoidElevated,
                                    labelColor = ArchiveTextSecondary,
                                ),
                            )
                        }
                    }

                    // Smart mode description
                    if (autoRewindMode == "smart") {
                        Text(
                            text = "Rewind scales with pause duration — short pauses rewind less, long pauses rewind more",
                            style = MaterialTheme.typography.bodySmall,
                            color = ArchiveTextMuted,
                        )
                    }

                    // Flat mode slider
                    AnimatedVisibility(visible = autoRewindMode == "flat") {
                        Column {
                            Text(
                                text = "Rewind: ${formatSeconds(autoRewindSeconds)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = GoldFilament,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Slider(
                                value = autoRewindSeconds.toFloat(),
                                onValueChange = { onAutoRewindSecondsChange((it / 5).toInt() * 5) },
                                valueRange = 0f..120f,
                                steps = 23, // (120 - 0) / 5 - 1
                                colors = SliderDefaults.colors(
                                    thumbColor = GoldFilament,
                                    activeTrackColor = GoldFilament,
                                    inactiveTrackColor = ArchiveVoidElevated,
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

            HorizontalDivider(color = ArchiveVoidElevated, thickness = 1.dp)

            // Shake to reset toggle
            ArchivePreferenceRow(
                title = "Shake to Reset",
                subtitle = "Shake the phone to restart the sleep timer",
                checked = shakeResetEnabled,
                onCheckedChange = onShakeResetEnabledChange,
            )

            HorizontalDivider(color = ArchiveVoidElevated, thickness = 1.dp)

            // Rewind on sleep slider
            Column(
                modifier = Modifier.padding(vertical = 8.dp),
            ) {
                Text(
                    text = "Rewind on Sleep: ${formatSeconds(rewindSeconds)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ArchiveTextPrimary,
                )
                Text(
                    text = "How far to rewind when the sleep timer stops playback",
                    style = MaterialTheme.typography.bodySmall,
                    color = ArchiveTextMuted,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Slider(
                    value = rewindSeconds.toFloat(),
                    onValueChange = { onRewindSecondsChange((it / 5).toInt() * 5) },
                    valueRange = 0f..60f,
                    steps = 11, // (60 - 0) / 5 - 1
                    colors = SliderDefaults.colors(
                        thumbColor = GoldFilament,
                        activeTrackColor = GoldFilament,
                        inactiveTrackColor = ArchiveVoidElevated,
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
                        color = ArchiveTextPrimary,
                    )
                    Text(
                        text = "Shape the frequency response",
                        style = MaterialTheme.typography.bodySmall,
                        color = ArchiveTextMuted,
                    )
                }
                Switch(
                    checked = eqEnabled,
                    onCheckedChange = { onToggleEq() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = GoldFilament,
                        checkedTrackColor = GoldFilamentFaint,
                        uncheckedThumbColor = ArchiveTextSecondary,
                        uncheckedTrackColor = ArchiveVoidElevated,
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
                    color = ArchiveTextMuted,
                    fontSize = 9.sp,
                )
                Text(
                    text = "0dB",
                    style = MaterialTheme.typography.labelSmall,
                    color = ArchiveTextMuted,
                    fontSize = 9.sp,
                )
                Text(
                    text = "${minGain / 100}dB",
                    style = MaterialTheme.typography.labelSmall,
                    color = ArchiveTextMuted,
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
                    brush = androidx.compose.ui.graphics.SolidColor(ArchiveVoidElevated)
                ),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = ArchiveTextSecondary,
                    disabledContentColor = ArchiveTextMuted,
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
            color = if (enabled) ArchiveTextSecondary else ArchiveTextMuted,
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
                    thumbColor = if (enabled) GoldFilament else ArchiveTextMuted,
                    activeTrackColor = if (enabled) GoldFilament else ArchiveTextMuted,
                    inactiveTrackColor = ArchiveOutline,
                    disabledThumbColor = ArchiveTextMuted,
                    disabledActiveTrackColor = ArchiveTextMuted.copy(alpha = 0.5f),
                    disabledInactiveTrackColor = ArchiveOutline.copy(alpha = 0.5f),
                ),
            )
        }

        Text(
            text = frequencyLabel,
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) ArchiveTextSecondary else ArchiveTextMuted,
            fontSize = 9.sp,
        )
    }
}

private fun formatSettingsFrequency(hz: Int): String = when {
    hz >= 1000 -> "${hz / 1000}k"
    else -> "$hz"
}
