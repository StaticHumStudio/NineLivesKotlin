# Free-tier variance: plan doc vs shipped code vs store copy

Produced 2026-08-15 by reading all three sources directly. Sources:

- **Plan summary** ... the free-unlock production plan, kept in the private companion,
  the supersession block near the top: *"The free tier's limits are now exactly
  three, and they are final."*
- **Plan detail** ... the same document's Locked Decisions section and the PR E /
  PR F acceptance checklists.
- **Code** ... `entitlement/FreeTier.kt`, `entitlement/EffectiveSettings.kt`,
  and every `isUnlocked` call site in `ui/` and `service/`.
- **Store copy** ... the 2.1.0 full description and release notes drafted
  2026-08-15 in `/projects/Marketing/nine-lives-marketing/store-listing.md`.

## The headline

**The code is not drifting from the plan. The plan is drifting from itself.**

The "exactly three, and they are final" sentence describes three limits. The
same document's own locked decisions and acceptance checklists specify
thirteen. The code implements eleven of those thirteen and skips two.

The damage is downstream: the store listing was written off the three-limit
sentence, so it understates the free tier's real boundaries, and in three
places it advertises a gated feature as though it ships free.

## The chart

Legend: **Y** gated / specified · **n** not mentioned · **--** not applicable

| # | Feature | Plan summary ("exactly three") | Plan detail (locked + checklist) | Shipped code | Store copy | Verdict |
|---|---|---|---|---|---|---|
| 1 | Downloads capped at one offline book | **Y** | **Y** | **Y** `DownloadSlotStore.slotApplies` | states it | **aligned** |
| 2 | Playback speed pinned 1.0x | **Y** | **Y** | **Y** `FreeTier.allowsSpeed` | states it | **aligned** |
| 3 | Sleep timer, 30-minute preset only | **Y** | **Y** | **Y** `FreeTier.SLEEP_TIMER_MINUTES` | states it | **aligned** |
| 4 | Sleep timer motion grace, shake-to-reset, rewind-on-expire | n | **Y** | **Y** normalized off | n | summary gap · **listing silent** |
| 5 | Auto-rewind on resume | n | **Y** | **Y** `autoRewindEnabled=false` | n | summary gap · **listing silent** |
| 6 | Equalizer and volume boost | n | **Y** | **Y** normalized off, clamped in `PlaybackManager` | n | summary gap · **listing silent** |
| 7 | Silence skipping | n | **Y** | **Y** `skipSilenceEnabled=false` | **advertises it as new, no gate note** | **listing conflict** |
| 8 | Sorts: 8 of 11 gated | n | **Y** | **all 11 FREE** as of 2026-08-15, `FreeTier.SORT_MODES` = every entry, mechanism retained | n | **code ahead of plan, deliberate** |
| 9 | Grouping: series, author, genre | n | **Y** | **Y** `VIEW_MODES = {ALL}` | n | gated, ships gated in 2.1.0 · see reconciliation |
| 10 | Archive Shelf browsing and manual restore | n | **Y** (auto-restore and deletion stay free) | **Y** `LibraryScreen` tab lock | **describes it as a feature, no gate note** | **listing conflict** |
| 11 | Nightwatch Dossier and share card | n | **Y** | **30-day window FREE**, longer periods gated, as of 2026-08-16 | **describes it as a feature, no gate note** | **resolved in code**, listing still needs the period note |
| 12 | Themes | n | **Y** NOIR free, other three gated | **NOIR + BRIGHT free** as of 2026-08-15 | n | **code ahead of plan, deliberate** |
| 13 | Advanced server settings (self-signed cert, TOFU) | n | **Y** "gated in the UI" | **NO GATE** ... bare `Switch`, no `GatedControl` | n | ships ungated in 2.1.0, deliberate · see reconciliation |
| 14 | Android Auto browse gating and command grants | n | **Y** | **NO GATE** ... zero entitlement refs in `PlaybackService`, `MediaBrowseTree`, `RemoteMediaAccessPolicy` | n | **never gated, decided 2026-08-16** · see reconciliation |
| 15 | Local folder cap | dropped 2026-08-15 | marked superseded | never built | correctly silent | **aligned** |
| 16 | ABS library cap | dropped 2026-08-15 | marked superseded | never built | correctly silent | **aligned** |

## What actually needs a decision

### 1. The three listing conflicts (rows 7, 10, 11)

This is the row set that produces angry reviews, because the listing promises
something the app then charges for. It is also the cheapest to fix: it is copy,
not code.

- **Silence skipping** is in the 2.1.0 release notes as "Also new: silence
  skipping" with no indication it is an unlock feature. A free user reads the
  release note, updates, and finds a locked toggle.
- **Archive Shelf** and **Nightwatch Dossier** each get their own section in the
  full description, written as capabilities the app has. Both are gated.

Fix: either add the gate to each mention, or move all three into the unlock
paragraph. Recommend the second, since it makes the unlock paragraph a real
value proposition instead of three limits phrased as subtractions.

### 2. The Dossier strategy conflict (row 11) ... DECIDED 2026-08-16

**Resolved: free users get the Dossier limited to the 30-day period, unlock
opens the longer periods.** Jeff's call, matching the recommendation below.

`gates.md` item H1 makes the Nightwatch Dossier share card the one mechanism in
the whole marketing plan where users do the distribution. That only works if
users can generate a card, and gating your own acquisition asset behind the
purchase it is meant to drive is a closed loop.

What changed in code:

- `FreeTier.DOSSIER_PERIODS` (= `{THIRTY_DAYS}`), `allowsDossierPeriod()`, and
  `effectiveDossierPeriod()`, mirroring the sort and theme pattern.
- The clamp lives in `NightwatchDossierViewModel.loadDossier()` where the cutoff
  is computed, **not** on the chips. A period chosen while unlocked survives a
  downgrade in ViewModel state, so gating the control alone would leave a free
  install reading a year of history behind a greyed chip. Same failure mode the
  theme constant had when it was read in two places.
- Whole-feature gates removed at all three entry points: the nav-host route
  guard, the Home banner `GatedControl`, and the Settings row `GatedControl`.
  The nav host now navigates to Unlock **without** popping the Dossier, so Back
  returns to the report.
- Period chips greyed-never-hidden with the same `Icons.Outlined.Lock` sigil
  `GatedControl` uses.

Rejected alternatives, for the record: share card without the Dossier screen
(splits one feature into two half-features), and leaving it gated while dropping
H1 (throws away the only user-distribution mechanism in the plan).

Still open: the listing copy needs a period note, since the full description
describes the Dossier without saying the long windows are an unlock feature.

Recommend the first. The card is an acquisition asset, and gating your own
acquisition asset behind a purchase is backwards.

### 3. The two code gaps (rows 13, 14)

Both are planned gates that were never built. Neither is a correctness bug and
neither leaks entitlement in the wrong direction, so free users currently get
*more* than intended.

- **Advanced server settings**: this one is arguably better left ungated.
  Self-signed cert trust is how a self-hoster connects to their own box at all.
  Gating it is close to gating access to a user's own data, which is the line
  the paywall philosophy says not to cross.
- **Android Auto browse gating**: worth deciding deliberately rather than by
  omission, especially since PRs #88, #90, and #92 all touched Auto and none
  added it.

### 4. The plan doc sentence

"The free tier's limits are now exactly three, and they are final" is the
sentence that caused all of this. It should say the three *headline* limits and
point at the acceptance checklists for the full gate list, or it should be
deleted. Leaving it is how the next document written off this plan repeats the
same mistake.

## Changed tonight

Row 12. `FreeTier.THEME` (single constant) became `FreeTier.THEMES` (set) plus
`DEFAULT_THEME` and a new `effectiveTheme()` clamp. NOIR and BRIGHT are free,
AMOLED and CANDLELIGHT stay gated. The Settings theme picker moved from one
section-level gate to per-swatch gating, since a section gate would have locked
free users out of a theme they are entitled to. `MainActivity` and
`EffectiveSettings` both route through `effectiveTheme()` now, because the old
single constant was read in two places and a set-valued free tier would have
silently kept forcing NOIR in whichever one got missed.

Verification: `./gradlew testDebugUnitTest --rerun-tasks --tests
"com.ninelivesaudio.app.entitlement.*"` ... 82 tests, 0 failures, including
three new cases (`NOIR and BRIGHT are free and every theme opens on unlock`,
`normalization leaves a free install on BRIGHT`, `effectiveTheme falls back to
the default, not the first free entry`).

The plan doc's locked decision on themes and the `FreeTier` lore line ("the
vault is what you get, lighting is a privilege you buy") are both now superseded
and need updating in place.

## Reconciliation, 2026-08-23

Written the night 2.1.0 (212) was finalized for production, to close the rows
still marked open above. Facts and decisions, with provenance:

- **Row 14, Android Auto: never gated. Locked decision, 2026-08-16.** Auto
  ships free in the category king's Basic tier, and a gate here would punish
  the exact commute use case the listing sells. The absence of entitlement
  references in `PlaybackService`, `MediaBrowseTree`, and
  `RemoteMediaAccessPolicy` is the intended end state, not a gap. The old
  "code gap" verdict is superseded.
- **Row 13, advanced server settings: ships ungated in 2.1.0.** The standing
  recommendation (self-signed cert trust is how a self-hoster reaches their
  own box, and gating it sits next door to gating access to a user's own
  data) has not been formally locked, but the code ships ungated and the
  listing is silent, so nothing is promised either way. If a future release
  wants this gate, that is a new decision, not a regression fix.
- **Row 9, grouping: gated, and 2.1.0 ships it gated.** Whether it stays
  gated long-term is formally still open, marked low priority 2026-08-16.
  The listing does not mention grouping, so the open decision has no copy
  exposure.
- **Row 8, sorts: all 11 free since 2026-08-15.** Chart cell updated. The
  gate mechanism is retained deliberately ... a lever not pulled is not a
  lever removed.
- **Rows 7, 10, 11, the listing conflicts: substantially resolved by the
  approved 2.1.0 copy** (approved 2026-08-23, paste source of record in the
  marketing package). The release notes now say "silence skipping, included
  with the unlock," which was row 7's worst case. The Archive Shelf
  paragraph describes retention, which is free, rather than browsing, which
  is not (row 10). The Dossier paragraph leads with "thirty days," the free
  window (row 11). One remaining nit, flagged rather than silently edited
  because the copy is approved: the BUILT FOR LONG LISTENS bullet list still
  names silence skipping, the equalizer, and the motion-grace sleep timer
  without saying they ride with the unlock. Candidate one-line fix, pending
  Jeff: extend the unlock sentence to "every speed, silence skipping, the
  equalizer, unlimited offline books, and full sleep timer control."
- **The 30-day trial is not in 2.1.0.** Decided 2026-08-16, cut 2026-08-22:
  v212 ships without it and the trial moves to 2.2 as a release beat. No
  listing or doc text may mention a trial until it ships.
