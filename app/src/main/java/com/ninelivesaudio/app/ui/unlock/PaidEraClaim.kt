package com.ninelivesaudio.app.ui.unlock

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import com.ninelivesaudio.app.entitlement.BillingManager
import com.ninelivesaudio.app.entitlement.EntitlementRepository
import com.ninelivesaudio.app.entitlement.PaidEraClaimPolicy
import com.ninelivesaudio.app.entitlement.PaidEraClaimPrefs
import com.ninelivesaudio.app.ui.theme.NineLivesTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

private const val SUPPORT_EMAIL = "Static@StaticHum.Studio"
private const val CLAIM_SUBJECT = "Nine Lives: paid-app unlock claim"

/**
 * Open a mail client with the paid-era claim prefilled.
 *
 * Used by the one-time prompt. It was shared with a Settings claim row until
 * that row was removed on 2026-08-20, and it stays a named function rather than
 * being inlined because the claim copy belongs in exactly one place regardless
 * of how many callers there are. Falls back to a chooser when nothing
 * handles `mailto:`, because a dead button here means a past buyer has no way
 * to reach us at all: Play does not expose buyer email addresses for a
 * paid-app order, so this really is the only channel.
 */
fun sendPaidEraClaimEmail(context: Context, appVersion: String) {
    // No order ID asked for, on purpose. Making somebody dig through Play Store,
    // then Payments and subscriptions, then Budget and history, is work we would be
    // imposing on a person we already took money from. Play Console's order search
    // accepts an email address, so the sender address IS the lookup key and the
    // whole ask collapses to "send this".
    val body = buildString {
        appendLine("I bought Nine Lives Audio back when it was a paid app, and I would like the unlock.")
        appendLine()
        appendLine("Sending this from the Google account I bought it with, so it should be findable on your end.")
        appendLine()
        appendLine("App version: $appVersion")
    }
    val mail = Intent(Intent.ACTION_SENDTO).apply {
        data = Uri.parse("mailto:")
        putExtra(Intent.EXTRA_EMAIL, arrayOf(SUPPORT_EMAIL))
        putExtra(Intent.EXTRA_SUBJECT, CLAIM_SUBJECT)
        putExtra(Intent.EXTRA_TEXT, body)
    }
    if (mail.resolveActivity(context.packageManager) != null) {
        context.startActivity(mail.withNewTaskIfNeeded(context))
        return
    }
    val fallback = Intent(Intent.ACTION_SEND).apply {
        type = "message/rfc822"
        putExtra(Intent.EXTRA_EMAIL, arrayOf(SUPPORT_EMAIL))
        putExtra(Intent.EXTRA_SUBJECT, CLAIM_SUBJECT)
        putExtra(Intent.EXTRA_TEXT, body)
    }
    context.startActivity(
        Intent.createChooser(fallback, "Send claim via").withNewTaskIfNeeded(context),
    )
}

/**
 * Add FLAG_ACTIVITY_NEW_TASK when the context is not an Activity.
 *
 * Caught on a real device, and it would never have shown up in a unit test.
 * At the time there were two callers: a Settings claim row (since removed) which
 * passed an Activity context and worked, and this dialog, which routed through a
 * ViewModel holding the application context. ContextImpl throws outright rather
 * than degrading, so only one of the two crashed and they looked identical from
 * the source. Applied conditionally rather than always,
 * because forcing a new task from an Activity changes the back stack the mail
 * client comes back to.
 */
private fun Intent.withNewTaskIfNeeded(context: Context): Intent {
    var c: Context? = context
    while (c is ContextWrapper) {
        if (c is Activity) return this
        c = c.baseContext
    }
    return apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
}

@HiltViewModel
class PaidEraClaimViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: PaidEraClaimPrefs,
    private val billing: BillingManager,
    entitlements: EntitlementRepository,
) : ViewModel() {

    private val _isVisible = MutableStateFlow(false)
    val isVisible: StateFlow<Boolean> = _isVisible.asStateFlow()

    /**
     * Read once and cached. `firstInstallTime` cannot change while the process
     * is alive, and re-reading it per recomposition would put a binder call on
     * every frame that touches this state.
     */
    private val firstInstallTime: Long = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime
    }.getOrDefault(0L)

    init {
        // WAIT for the first purchase query before deciding anything, then keep
        // observing.
        //
        // Observing alone is not enough, which is what an earlier version of
        // this comment got wrong. Before Billing answers, entitlement reads free
        // for EVERYBODY, because the Play-grant cache is excluded from backup
        // and does not survive a reinstall or a device move. Merely watching the
        // flow would show the dialog during that window and then hide it once
        // the truth arrived, so an unlock owner reinstalling would get a flash
        // of a prompt offering them something they already bought. Worse, a
        // reinstall resets firstInstallTime to today, which is inside the
        // window, so the date gate does not save them either.
        //
        // Bounded, not indefinite. A device with no Play Store is a legitimate
        // state, not an error, and a paid-era buyer on one still deserves the
        // offer. If Billing never settles we fall through and decide on what we
        // have, which for that person is the correct answer anyway.
        viewModelScope.launch {
            withTimeoutOrNull(SETTLE_TIMEOUT_MS) {
                billing.purchaseQuerySettled.first { it }
            }
            entitlements.state
                .map { it.isUnlocked }
                .distinctUntilChanged()
                .collect { isUnlocked ->
                    _isVisible.value = PaidEraClaimPolicy.shouldPrompt(
                        firstInstallTimeMillis = firstInstallTime,
                        isUnlocked = isUnlocked,
                        alreadyPrompted = prefs.wasPrompted,
                    )
                }
        }
    }

    /**
     * Close the prompt and latch it as seen.
     *
     * Latching on EITHER button, not just "No thanks". Someone who taps through
     * to email has been served, and meeting the same dialog again on the next
     * cold start reads as a bug rather than a courtesy.
     */
    fun dismiss() {
        prefs.markPrompted()
        _isVisible.value = false
    }

    fun appVersion(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    }.getOrDefault("unknown")

    private companion object {
        /**
         * Backstop for the wait on Billing, and it MUST exceed BillingManager's
         * own BILLING_TIMEOUT_MS (30s).
         *
         * An earlier 8s value was wrong and codex caught it: it expired while
         * Play was still legitimately working, so a slow query on an unlocked
         * reinstall fell through to the provisional free reading and offered a
         * claim to somebody who already owned the unlock. Undercutting the
         * layer below turns its patience into our bug.
         *
         * This is only a backstop now. The settle flag is set in a `finally`,
         * so the normal timeout path resolves this wait in about 30s anyway.
         * A late prompt is harmless. A wrong one is not.
         */
        const val SETTLE_TIMEOUT_MS = 35_000L
    }
}

/**
 * One-time offer shown to installs that predate the switch to free.
 *
 * Hosted at the top level rather than on a screen, so it survives whatever the
 * user happened to open first and does not depend on them finding Settings.
 */
@Composable
fun PaidEraClaimDialog(
    viewModel: PaidEraClaimViewModel = hiltViewModel(),
) {
    val isVisible by viewModel.isVisible.collectAsStateWithLifecycle()
    if (!isVisible) return

    // Activity context, deliberately, not the ViewModel's application context.
    // Routing the send through the ViewModel is what crashed this on device.
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = viewModel::dismiss,
        title = { Text("You paid for this") },
        text = {
            Text(
                "Nine Lives is free now. You bought it back when it cost money, so the " +
                    "unlock is yours at no charge. Tap below and send the email from the " +
                    "account you bought it with. We'll find the purchase on our end and send " +
                    "a code back. Nothing to pay, and this is the only time we'll ask."
            )
        },
        confirmButton = {
            TextButton(onClick = {
                sendPaidEraClaimEmail(context, viewModel.appVersion())
                viewModel.dismiss()
            }) {
                Text("Email for a code", color = NineLivesTheme.colors.goldFilament)
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismiss) { Text("No thanks") }
        },
        containerColor = NineLivesTheme.colors.archiveVoidSurface,
    )
}
