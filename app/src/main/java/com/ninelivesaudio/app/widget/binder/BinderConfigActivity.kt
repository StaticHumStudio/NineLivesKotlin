package com.ninelivesaudio.app.widget.binder

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.ninelivesaudio.app.entitlement.EffectiveSettingsRepository
import com.ninelivesaudio.app.entitlement.FreeTier
import com.ninelivesaudio.app.service.SettingsManager
import com.ninelivesaudio.app.ui.components.ArchiveScreenHeader
import com.ninelivesaudio.app.ui.components.CosmicBackgroundGradient
import com.ninelivesaudio.app.ui.library.SortMode
import com.ninelivesaudio.app.ui.library.label
import com.ninelivesaudio.app.ui.theme.NineLivesAudioTheme
import com.ninelivesaudio.app.ui.theme.NineLivesTheme
import com.ninelivesaudio.app.widget.WidgetCopy
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The Binder's setup screen. Runs when the widget is added and again from the
 * launcher's Reconfigure. A free user sees it too (Android only places a
 * widget after this screen says OK), with a line saying the picks wait for
 * the unlock. Reads no books, the sort list is the Library's static one.
 */
@AndroidEntryPoint
class BinderConfigActivity : ComponentActivity() {

    @Inject lateinit var configStore: BinderConfigStore

    @Inject lateinit var updater: BinderWidgetUpdater

    @Inject lateinit var settingsManager: SettingsManager

    @Inject lateinit var effectiveSettings: EffectiveSettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appWidgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Back out, or a crash, leaves the widget unplaced.
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        enableEdgeToEdge()
        val initial = configStore.get(appWidgetId)
        val unlocked = effectiveSettings.isUnlockedNow
        setContent {
            NineLivesAudioTheme(
                themeMode = FreeTier.effectiveTheme(settingsManager.currentSettings.themeMode, unlocked),
            ) {
                BinderSetupScreen(
                    initial = initial,
                    showLockedNote = !unlocked,
                    onDone = { config -> finishWith(appWidgetId, config) },
                )
            }
        }
    }

    private fun finishWith(appWidgetId: Int, config: BinderConfig) {
        configStore.put(appWidgetId, config)
        lifecycleScope.launch {
            updater.update(intArrayOf(appWidgetId))
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
            finish()
        }
    }
}

@Composable
private fun BinderSetupScreen(
    initial: BinderConfig,
    showLockedNote: Boolean,
    onDone: (BinderConfig) -> Unit,
) {
    var tap by remember { mutableStateOf(initial.tap) }
    var sort by remember { mutableStateOf(initial.sort) }
    var saving by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        CosmicBackgroundGradient()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            ArchiveScreenHeader(title = WidgetCopy.SETUP_TITLE)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                if (showLockedNote) LockedNote()
                SetupGroup(WidgetCopy.SETUP_TAP_HEADER) {
                    BinderTap.entries.forEach { option ->
                        ChoiceRow(
                            label = if (option == BinderTap.PLAY) WidgetCopy.SETUP_TAP_PLAY else WidgetCopy.SETUP_TAP_OPEN,
                            selected = tap == option,
                            onClick = { tap = option },
                        )
                    }
                }
                SetupGroup(WidgetCopy.SETUP_SORT_HEADER) {
                    SortMode.entries.forEach { option ->
                        ChoiceRow(label = option.label(), selected = sort == option, onClick = { sort = option })
                    }
                }
            }
            Button(
                onClick = {
                    if (!saving) {
                        saving = true
                        onDone(BinderConfig(tap, sort))
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NineLivesTheme.colors.goldFilament,
                    contentColor = NineLivesTheme.colors.archiveVoidDeep,
                ),
            ) {
                Text(WidgetCopy.SETUP_DONE, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun LockedNote() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            Icons.Outlined.Lock,
            contentDescription = null,
            tint = NineLivesTheme.colors.goldFilamentDim,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = WidgetCopy.SETUP_LOCKED_NOTE,
            style = MaterialTheme.typography.bodyMedium,
            color = NineLivesTheme.colors.archiveTextSecondary,
        )
    }
}

@Composable
private fun SetupGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
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
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.Circle,
            contentDescription = null,
            tint = if (selected) NineLivesTheme.colors.goldFilament else NineLivesTheme.colors.archiveTextMuted,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) NineLivesTheme.colors.goldFilament else NineLivesTheme.colors.archiveTextPrimary,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
