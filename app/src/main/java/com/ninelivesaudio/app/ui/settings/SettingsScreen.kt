package com.ninelivesaudio.app.ui.settings

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ninelivesaudio.app.ui.components.StatusPill
import com.ninelivesaudio.app.ui.theme.*

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(VoidDeep)
    ) {
        // ─── Page Header ──────────────────────────────────────────────
        SettingsHeader(uiState)

        // ─── Scrollable Content ───────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
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

            // ─── Server Connection ────────────────────────────────────
            SectionHeader(text = "Server Connection")

            // Connection status card
            Card(
                colors = CardDefaults.cardColors(containerColor = VoidSurface),
                shape = RoundedCornerShape(12.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StatusPill(connectionStatus = uiState.connectionStatus)
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = uiState.connectionStatusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = Mist,
                    )
                }
            }

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

            // Username field
            CosmicTextField(
                value = uiState.username,
                onValueChange = viewModel::onUsernameChanged,
                label = "Username",
                placeholder = "Enter username",
                enabled = !uiState.isConnected,
                leadingIcon = Icons.Outlined.Person,
            )

            // Password field
            CosmicTextField(
                value = uiState.password,
                onValueChange = viewModel::onPasswordChanged,
                label = "Password",
                placeholder = "Enter password",
                enabled = !uiState.isConnected,
                leadingIcon = Icons.Outlined.Lock,
                isPassword = true,
            )

            // Self-signed certificates toggle
            Card(
                colors = CardDefaults.cardColors(containerColor = VoidSurface),
                shape = RoundedCornerShape(12.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Allow Self-Signed Certificates",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Starlight,
                        )
                        Text(
                            text = "Enable for local/development servers",
                            style = MaterialTheme.typography.bodySmall,
                            color = MistFaint,
                        )
                    }
                    Switch(
                        checked = uiState.allowSelfSignedCertificates,
                        onCheckedChange = viewModel::onAllowSelfSignedChanged,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = SigilGold,
                            checkedTrackColor = SigilGoldFaint,
                            uncheckedThumbColor = Mist,
                            uncheckedTrackColor = VoidElevated,
                        ),
                    )
                }
            }

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
                            containerColor = SigilGold,
                            contentColor = VoidDeep,
                            disabledContainerColor = SigilGoldDim,
                        ),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        if (uiState.isConnecting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = VoidDeep,
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
                        onClick = viewModel::testConnection,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = VoidSurface,
                            contentColor = SigilGold,
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

                    Button(
                        onClick = viewModel::disconnect,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CosmicError.copy(alpha = 0.15f),
                            contentColor = CosmicError,
                        ),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("Disconnect", fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // ─── Divider ──────────────────────────────────────────────
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = VoidElevated,
                thickness = 1.dp,
            )

            // ─── Archive Configuration ────────────────────────────────
            SectionHeader(text = "Archive Configuration")

            ArchiveConfigurationSection(
                unhingedThemeEnabled = uiState.unhingedThemeEnabled,
                sessionCount = uiState.sessionCount,
                onToggleUnhinged = viewModel::toggleUnhingedTheme
            )

            // ─── Divider ──────────────────────────────────────────────
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = VoidElevated,
                thickness = 1.dp,
            )

            // ─── Diagnostics ──────────────────────────────────────────
            SectionHeader(text = "Diagnostics")

            Card(
                colors = CardDefaults.cardColors(containerColor = VoidSurface),
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    DiagnosticRow("App Version", uiState.appVersion)
                    DiagnosticRow("Settings File", uiState.settingsFilePath.ifEmpty { "(default)" })
                }
            }

            // Diagnostic action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = viewModel::clearCache,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                        brush = androidx.compose.ui.graphics.SolidColor(VoidElevated)
                    ),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Mist,
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

            // Bottom padding for mini player space
            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

// ─── Header ───────────────────────────────────────────────────────────────

@Composable
private fun SettingsHeader(uiState: SettingsViewModel.UiState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(VoidDeep)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineMedium,
            color = Starlight,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.weight(1f))
        StatusPill(connectionStatus = uiState.connectionStatus)
    }
}

// ─── Section Header ───────────────────────────────────────────────────────

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = SigilGold,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(top = 4.dp),
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
            color = Mist,
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
                    color = MistFaint,
                )
            },
            leadingIcon = leadingIcon?.let {
                {
                    Icon(
                        imageVector = it,
                        contentDescription = null,
                        tint = if (enabled) Mist else MistFaint,
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
                            tint = MistFaint,
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
                focusedBorderColor = SigilGold,
                unfocusedBorderColor = VoidElevated,
                disabledBorderColor = VoidElevated.copy(alpha = 0.5f),
                focusedContainerColor = VoidSurface,
                unfocusedContainerColor = VoidSurface,
                disabledContainerColor = VoidSurface.copy(alpha = 0.5f),
                focusedTextColor = Starlight,
                unfocusedTextColor = Starlight,
                disabledTextColor = Mist,
                cursorColor = SigilGold,
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
    val bgColor = if (isError) CosmicError.copy(alpha = 0.1f) else CosmicSuccess.copy(alpha = 0.1f)
    val borderColor = if (isError) CosmicError.copy(alpha = 0.3f) else CosmicSuccess.copy(alpha = 0.3f)
    val textColor = if (isError) CosmicError else CosmicSuccess
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
            color = MistFaint,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = Starlight,
        )
    }
}

// ─── Archive Configuration ────────────────────────────────────────────────

@Composable
private fun ArchiveConfigurationSection(
    unhingedThemeEnabled: Boolean,
    sessionCount: Int,
    onToggleUnhinged: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = VoidSurface),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Archive Beneath Theme Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Archive Beneath Theme",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Starlight,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Deep purple/indigo aesthetic with gold accents",
                        style = MaterialTheme.typography.bodySmall,
                        color = MistFaint,
                    )
                }
                Switch(
                    checked = unhingedThemeEnabled,
                    onCheckedChange = { onToggleUnhinged() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = SigilGold,
                        checkedTrackColor = SigilGoldFaint,
                    ),
                )
            }

            HorizontalDivider(color = VoidElevated, thickness = 1.dp)

            // Session Counter
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "Session Count",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Mist,
                    )
                    Text(
                        text = "Total app launches tracked",
                        style = MaterialTheme.typography.bodySmall,
                        color = MistFaint,
                    )
                }
                Text(
                    text = sessionCount.toString(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = SigilGold,
                    fontWeight = FontWeight.Bold,
                )
            }

            // Info message
            if (unhingedThemeEnabled) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = CosmicInfo.copy(alpha = 0.1f),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.Info,
                        contentDescription = null,
                        tint = CosmicInfo,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = "Archive Beneath theme active. Surface textures and accent colors enabled.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Starlight,
                    )
                }
            }
        }
    }
}
