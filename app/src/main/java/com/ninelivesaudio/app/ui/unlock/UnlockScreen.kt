package com.ninelivesaudio.app.ui.unlock

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ninelivesaudio.app.ui.components.ArchiveScreenHeader
import com.ninelivesaudio.app.ui.theme.NineLivesTheme

/**
 * The purchase surface.
 *
 * Studio canon applies here in a specific way: the flavor lives in the header
 * and the one lore line, and everything to do with money is plain. Nobody should
 * have to decode a metaphor to know what they are being charged or what they get.
 */
@Composable
fun UnlockScreen(
    onNavigateBack: () -> Unit = {},
    viewModel: UnlockViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val restoreMessage by viewModel.restoreMessage.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(restoreMessage) {
        if (restoreMessage != null) {
            kotlinx.coroutines.delay(RESTORE_MESSAGE_MS)
            viewModel.consumeRestoreMessage()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NineLivesTheme.colors.archiveVoidDeep),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back",
                    tint = NineLivesTheme.colors.archiveTextSecondary,
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            ArchiveScreenHeader(
                title = if (uiState.isUnlocked) "Unlocked" else "Unlock Nine Lives",
                subtitle = if (uiState.isUnlocked) {
                    "The lights are on. Nothing left to buy."
                } else {
                    // Reworded 2026-08-20. The old line ("Lighting is a
                    // privilege you buy") stopped being true the moment BRIGHT
                    // went free, and a paywall that lies about what it gates is
                    // the same con as an unlock list selling what you already
                    // own.
                    "The vault is yours, lit well enough to read. The comforts are what you buy."
                },
            )

            when {
                uiState.isGrandfathered -> GrandfatheredCard()
                uiState.isUnlocked -> UnlockedCard()
                else -> PurchaseCard(
                    formattedPrice = uiState.formattedPrice,
                    isPriceLoading = uiState.isPriceLoading,
                    isPriceUnavailable = uiState.isPriceUnavailable,
                    onPurchase = {
                        context.findActivity()?.let(viewModel::purchase)
                    },
                )
            }

            BenefitsCard()

            if (!uiState.isUnlocked) {
                Text(
                    text = "One payment, once. No subscription. No ads either way.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NineLivesTheme.colors.archiveTextMuted,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Already bought it?",
                    style = MaterialTheme.typography.bodySmall,
                    color = NineLivesTheme.colors.archiveTextMuted,
                )
                Text(
                    text = "Restore purchase",
                    style = MaterialTheme.typography.bodySmall.copy(
                        textDecoration = TextDecoration.Underline,
                    ),
                    color = NineLivesTheme.colors.goldFilament,
                    modifier = Modifier.clickable { viewModel.restorePurchases() },
                )
            }

            restoreMessage?.let {
                Text(
                    text = it.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = NineLivesTheme.colors.archiveTextSecondary,
                )
            }
        }
    }
}

@Composable
private fun PurchaseCard(
    formattedPrice: String?,
    isPriceLoading: Boolean,
    isPriceUnavailable: Boolean,
    onPurchase: () -> Unit,
) {
    ArchiveCard {
        Button(
            onClick = onPurchase,
            enabled = formattedPrice != null,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = NineLivesTheme.colors.goldFilament,
                contentColor = NineLivesTheme.colors.archiveVoidDeep,
            ),
        ) {
            when {
                isPriceLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = NineLivesTheme.colors.archiveVoidDeep,
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("Checking price", fontWeight = FontWeight.SemiBold)
                }
                // Disabled in this state, but it still needs a label. Without
                // one the else branch below renders "Unlock for null".
                isPriceUnavailable -> Text("Unavailable", fontWeight = FontWeight.SemiBold)
                // Price comes from Play, so it is already localized and already
                // carries the right currency. Never format one ourselves.
                else -> Text("Unlock for $formattedPrice", fontWeight = FontWeight.SemiBold)
            }
        }

        if (isPriceUnavailable) {
            Text(
                text = "Google Play is not answering right now. Nothing is wrong with " +
                    "your copy of the app, and nothing has been charged. Try again later.",
                style = MaterialTheme.typography.bodySmall,
                color = NineLivesTheme.colors.archiveTextMuted,
            )
        }
    }
}

@Composable
private fun UnlockedCard() {
    ArchiveCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = NineLivesTheme.colors.goldFilament,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = "Everything below is yours.",
                style = MaterialTheme.typography.bodyMedium,
                color = NineLivesTheme.colors.archiveTextPrimary,
            )
        }
    }
}

/**
 * Shown to anyone who bought the app back when it cost money.
 *
 * They must never see a purchase button. Charging a second time for something
 * already paid for is the one outcome this whole migration exists to avoid.
 */
@Composable
private fun GrandfatheredCard() {
    ArchiveCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = NineLivesTheme.colors.goldFilament,
                modifier = Modifier.size(20.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "You bought this before it was free.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NineLivesTheme.colors.archiveTextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "You already have everything, permanently. Nothing was " +
                        "taken away and there is nothing to buy.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NineLivesTheme.colors.archiveTextMuted,
                )
            }
        }
    }
}

@Composable
private fun BenefitsCard() {
    ArchiveCard {
        Text(
            text = "WHAT THE UNLOCK OPENS",
            style = MaterialTheme.typography.labelMedium,
            color = NineLivesTheme.colors.goldFilament,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
        )
        UNLOCK_BENEFITS.forEach { Benefit(it) }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Free keeps full playback, your local folders, your Audiobookshelf " +
                "server, chapters and bookmarks. One downloaded book at a time, " +
                "1.0x speed, and a 30 minute sleep timer.",
            style = MaterialTheme.typography.bodySmall,
            color = NineLivesTheme.colors.archiveTextMuted,
        )
    }
}

@Composable
private fun Benefit(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "·",
            style = MaterialTheme.typography.bodyMedium,
            color = NineLivesTheme.colors.goldFilamentDim,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = NineLivesTheme.colors.archiveTextPrimary,
        )
    }
}

@Composable
private fun ArchiveCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = NineLivesTheme.colors.archiveVoidSurface,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

/**
 * Kept in one list so the unlock screen and the store listing cannot drift.
 * Plain language on purpose. This is the part people are paying for.
 */
private val UNLOCK_BENEFITS = listOf(
    "Every playback speed, 0.5x to 3.0x",
    "Unlimited offline books, and the download queue",
    "Full sleep timer control, including custom durations",
    "Silence skipping",
    "Equalizer and volume boost",
    "Auto-rewind on resume",
    // These three were rewritten 2026-08-15/16 as free gained ground. An unlock
    // list that sells something the reader already has is worse than a short
    // list: it reads as a con the moment they notice.
    "The AMOLED and Candlelight themes",          // NOIR and BRIGHT are free
    "Series, author and genre grouping",          // every sort order is free
    "The Archive Shelf, and Dossier reports beyond 30 days",
)

/**
 * Walk out of any ContextWrapper to the hosting Activity.
 *
 * `LocalContext.current` is not guaranteed to BE the Activity. A theme wrapper
 * or any other ContextWrapper in the chain makes a direct cast fail silently,
 * which would leave an enabled purchase button that does nothing at all.
 */
private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

private const val RESTORE_MESSAGE_MS = 4_000L
