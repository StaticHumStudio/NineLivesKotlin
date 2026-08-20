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
 * ## Why a prompt, and why it is now the only claim-specific path
 *
 * There was briefly a permanent Settings row too. It was removed on 2026-08-20:
 * a standing question about a price that no longer exists, shown forever to
 * every free user, to serve a paid population of two. Nobody scrolls into
 * Settings hunting for a refund they do not know exists anyway.
 *
 * So the prompt is the claim. Play does not hand out buyer email addresses for
 * a paid-app order, so the app is the only channel between a past buyer and us,
 * and a channel nobody opens is not a channel. Anyone who dismisses the prompt
 * falls back to the general direct-contact row in Settings, which is why that
 * row is load-bearing and must not be removed without revisiting this.
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
     *   updates, so a buyer who updates late still gets the prompt. Does NOT
     *   survive uninstall and reinstall, and since the Settings claim row was
     *   removed nothing in the app catches that case. A buyer who reinstalls has
     *   to write in through the direct-contact row instead.
     * @param isUnlocked already entitled, by purchase or by a restored flag, so
     *   there is nothing to claim.
     * @param alreadyPrompted the prompt has been shown once. Once is the whole
     *   contract: a nag box on every cold start earns a one-star review faster
     *   than a missing feature does.
     *
     * A non-positive `firstInstallTimeMillis` means the lookup failed. Treated
     * as "do not prompt", so a broken read is silent rather than showing a
     * confusing dialog to every install on the error path. Nothing else catches
     * that case now, so the miss is real: the buyer would have to write in. That
     * trade still holds, because the error path would otherwise prompt EVERY
     * install, and annoying everyone to catch a lookup failure that may never
     * happen is the worse side of the bet.
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
