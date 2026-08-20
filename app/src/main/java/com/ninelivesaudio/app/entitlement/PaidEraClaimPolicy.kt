package com.ninelivesaudio.app.entitlement

/**
 * Decides whether to show the one-time "you bought this back when it cost
 * money" prompt.
 *
 * ## Why a date here is fine, when a date in a grandfather writer was not
 *
 * A date-gated WRITER was built on 2026-08-20 and deliberately thrown away. It
 * was only safe while a human remembered to flip the price after a compiled-in
 * cutoff, and one slip would have marked every free install paid-for-life and
 * quietly ended the paid tier.
 *
 * This is the same `firstInstallTime` mechanism pointed at a completely
 * different blast radius. Nothing here grants entitlement. The worst case for a
 * wrong date is that somebody who never paid sees a prompt that does not apply
 * to them and taps "No thanks". That asymmetry is the whole reason this is
 * allowed to exist and the writer is not.
 *
 * Which means the bias runs the OTHER way from the writer. Over-showing costs a
 * dismissed dialog. Under-showing costs a real customer their unlock, and they
 * have no other way to find out the offer exists. So [PROMPT_CUTOFF_MILLIS] is
 * set generously past the expected flip rather than tightly against it.
 *
 * ## Why a prompt and not just the Settings row
 *
 * The Settings row is the permanent path, but nobody scrolls into Settings
 * hunting for a refund they do not know exists. Play does not hand out buyer
 * email addresses for a paid-app order, so the app is the only channel between
 * a past buyer and us, and a channel nobody opens is not a channel.
 */
object PaidEraClaimPolicy {

    /**
     * Installs first created before this (UTC epoch millis, 2026-12-01T00:00:00Z)
     * are offered the claim prompt.
     *
     * Deliberately later than the expected price flip. Every install before the
     * flip genuinely paid, and the slack past it only costs a few free users a
     * dialog they will dismiss once. Unlike a grandfather cutoff, moving this
     * later is the SAFE direction and moving it earlier is the one that strands
     * people.
     */
    const val PROMPT_CUTOFF_MILLIS: Long = 1796083200000L

    /**
     * @param firstInstallTimeMillis `PackageInfo.firstInstallTime`. Survives
     *   updates, so a buyer who updates late still gets the prompt. Does not
     *   survive uninstall and reinstall, which is what the Settings row covers.
     * @param isUnlocked already entitled, by purchase or by a restored flag, so
     *   there is nothing to claim.
     * @param alreadyPrompted the prompt has been shown once. Once is the whole
     *   contract: a nag box on every cold start earns a one-star review faster
     *   than a missing feature does.
     *
     * A non-positive `firstInstallTimeMillis` means the lookup failed. Treated
     * as "do not prompt", so a broken read is silent rather than showing a
     * confusing dialog to every install on the error path. The Settings row
     * still covers anyone that misses.
     */
    fun shouldPrompt(
        firstInstallTimeMillis: Long,
        isUnlocked: Boolean,
        alreadyPrompted: Boolean,
    ): Boolean =
        !isUnlocked &&
            !alreadyPrompted &&
            firstInstallTimeMillis > 0L &&
            firstInstallTimeMillis < PROMPT_CUTOFF_MILLIS
}
