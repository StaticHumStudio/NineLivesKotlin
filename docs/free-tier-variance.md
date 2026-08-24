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

## Status, 2026-08-24

**Every listing conflict in this document is closed IN THE APPROVED 2.1.0
COPY.** The store copy was rewritten on 2026-08-24 and re-approved the same
day, and the paid-feature list in it was copied verbatim from
`UNLOCK_BENEFITS` in `ui/unlock/UnlockScreen.kt` rather than written
alongside it.

**That copy is a one-time reconciliation, not a mechanism, and this document
should not have called it structural.** `UNLOCK_BENEFITS` is a private Kotlin
value in this repo, the canonical listing lives in the marketing repo, and
nothing generates one from the other or checks them against each other. The
next edit to either side can re-drift exactly as before. A real check is
filed separately.

**The listing LIVE on Play is a different question and it is still open.** It
carries the pre-rewrite text until the flip-week refresh replaces it
wholesale, so the grouping conflict in row 9 is closed in the approved copy
and open on the live listing. Where this document says a row is resolved, it
means the approved copy unless it says otherwise.

Read section 1 for what changed, and 1b for the class of error this chart was
never built to catch and therefore missed.

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
| 4 | Sleep timer motion grace, shake-to-reset, rewind-on-expire | n | **Y** | **Y** normalized off | covered by "full sleep timer control" in the unlock list | summary gap · **listing aligned 2026-08-24** |
| 5 | Auto-rewind on resume | n | **Y** | **Y** `autoRewindEnabled=false` | named in the unlock list | summary gap · **listing aligned 2026-08-24** |
| 6 | Equalizer and volume boost | n | **Y** | **Y** normalized off, clamped in `PlaybackManager` | named in the unlock list | summary gap · **listing aligned 2026-08-24** |
| 7 | Silence skipping | n | **Y** | **Y** `skipSilenceEnabled=false` | named in the unlock list, and the release notes now mark it "(new)" inside the unlock sentence | **resolved 2026-08-24** |
| 8 | Sorts: 8 of 11 gated | n | **Y** | **all 11 FREE** as of 2026-08-15, `FreeTier.SORT_MODES` = every entry, mechanism retained | n | **code ahead of plan, deliberate** |
| 9 | Grouping: series, author, genre | n | **Y** | **Y** `VIEW_MODES = {ALL}` | named in the unlock list | gated, ships gated · **approved copy resolved 2026-08-24, LIVE listing still open until the refresh** |
| 10 | Archive Shelf browsing and manual restore | n | **Y** (auto-restore and deletion stay free) | **Y** `LibraryScreen` tab lock | **names browsing as a paid unlock feature**, and points at the full list | **resolved 2026-08-24** |
| 11 | Nightwatch Dossier and share card | n | **Y** | **30-day window FREE**, longer periods gated, as of 2026-08-16 | **names the free window as permanent and the longer ones as paid** | **resolved 2026-08-24** |
| 12 | Themes | n | **Y** NOIR free, other three gated | **NOIR + BRIGHT free** as of 2026-08-15 | AMOLED and Candlelight named in the unlock list | **code ahead of plan, deliberate** |
| 13 | Advanced server settings (self-signed cert, TOFU) | n | **Y** "gated in the UI" | **NO GATE** ... bare `Switch`, no `GatedControl` | n | ships ungated in 2.1.0, deliberate · see reconciliation |
| 14 | Android Auto browse gating and command grants | n | **Y** | **NO GATE** ... zero entitlement refs in `PlaybackService`, `MediaBrowseTree`, `RemoteMediaAccessPolicy` | n | **never gated, decided 2026-08-16** · see reconciliation |
| 15 | Local folder cap | dropped 2026-08-15 | marked superseded | never built | correctly silent | **aligned** |
| 16 | ABS library cap | dropped 2026-08-15 | marked superseded | never built | correctly silent | **aligned** |

## What actually needs a decision

### 1. The listing conflicts (rows 7, 10, 11) ... RESOLVED 2026-08-24

This was the row set that produces angry reviews, because the listing promised
something the app then charges for. It was also the cheapest to fix, being copy
rather than code.

The recommendation below was to move the gated features into the unlock
paragraph rather than tag each mention. Jeff took a version of that on
2026-08-24 and went further:

- `BUILT FOR LONG LISTENS` is now free features only, and ends with a line
  pointing at the paid list.
- The Archive Shelf and Dossier sections each name their gate inline and point
  at the same list.
- The old `FREE AND UNLOCKED` section split into `WHAT FREE GETS` and
  `THE PAID UNLOCK, IN FULL`, so the pointers land on a header that says what
  it is.
- The paid list was copied **verbatim** from `UNLOCK_BENEFITS` in
  `ui/unlock/UnlockScreen.kt`, whose own comment says it exists so the unlock
  screen and the store listing cannot drift. It had drifted anyway, which is
  the point: a comment asking people to remember is not a mechanism. Copying
  beats paraphrasing and it makes rows 4 through 11 true today, but it is a
  one-time reconciliation and the next edit to either artifact can undo it.
  The mechanical check is issue #13, not this paste. See the status block.

Approved copy is the paste package in the marketing repo, re-approved
2026-08-24 09:57 after three review passes.

### 1b. What this chart was never built to catch

Every row here is a **free-versus-paid variance**: is a gated thing described as
free. That framing is correct and it found five real conflicts. It is also
blind to a second class of error, and the 2026-08-24 audit found three of those
in copy this document had already reviewed:

- `Widget and full media-notification controls` promised a home-screen widget
  that **does not exist in this repo at all**. No `AppWidgetProvider`, no
  Glance, no appwidget receiver in the manifest, no widget XML. The
  notification half is real, which is how the bullet survived a read.
- `Sleep timer that fades out` ... it does not fade. `SleepTimerManager` pauses
  immediately at zero when motion sensing is off, which is the free path.
- `Run both modes side by side ... in one app` ... `AppMode` is an exclusive
  enum read as either/or in `LibraryViewModel` and `SettingsViewModel`. You
  configure both and switch.

None of those are variance. A feature that does not exist has no tier. So when
this chart is next run, run a second pass that asks a different question of
every claim: **not "is this gated" but "is this true."**

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

~~Still open: the listing copy needs a period note, since the full description
describes the Dossier without saying the long windows are an unlock feature.~~
**CLOSED 2026-08-24.** The approved copy now says the thirty-day window is
free and never expires, and that longer retrospectives are a paid unlock
feature, with a pointer to the full list. No copy work remains on this row.

Recommend the first. The card is an acquisition asset, and gating your own
acquisition asset behind a purchase is backwards.

### 3. The one open gate question (row 13), and one that is decided (row 14)

Both were planned gates that were never built. Neither is a correctness bug and
neither leaks entitlement in the wrong direction, so free users currently get
*more* than intended.

- **Advanced server settings (row 13), still open.** This one is arguably
  better left ungated. Self-signed cert trust is how a self-hoster connects to
  their own box at all. Gating it is close to gating access to a user's own
  data, which is the line the paywall philosophy says not to cross. No formal
  lock, and the code ships ungated with the listing silent, so nothing is
  promised either way.
- **~~Android Auto browse gating~~ (row 14), DECIDED 2026-08-16: never gated.**
  This section previously called it a gap and asked for a deliberate decision.
  That decision was made and Android Auto is free, so the absence of
  entitlement references in `PlaybackService`, `MediaBrowseTree` and
  `RemoteMediaAccessPolicy` is the intended end state rather than an omission.
  Kept here rather than deleted because the old framing was cited elsewhere.

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
  "code gap" verdict is superseded. One live artifact still contradicts the
  decision: the listing currently on Play (mirrored in `store-listing.md`
  at the repo root, PLAYBACK section) sells the unlock as adding Android
  Auto. The approved 2.1.0 copy drops that claim, and the flip-week listing
  refresh replaces the live text wholesale. Until then the live listing
  oversells the unlock in Auto's favor ... known, accepted, and it dies at
  the refresh. When the refresh publishes, re-sync the root mirror and
  `reference/play-store-listing.txt` in the marketing package from the
  approved paste source, per the mirror's own header rule.
- **Row 13, advanced server settings: ships ungated in 2.1.0.** The standing
  recommendation (self-signed cert trust is how a self-hoster reaches their
  own box, and gating it sits next door to gating access to a user's own
  data) has not been formally locked, but the code ships ungated and the
  listing is silent, so nothing is promised either way. If a future release
  wants this gate, that is a new decision, not a regression fix.
- **Row 9, grouping: gated, and 2.1.0 ships it gated.** Whether it stays
  gated long-term is formally still open, marked low priority 2026-08-16.
  As of the 2026-08-24 rewrite the approved copy names grouping in the paid
  list, as "Series, author and genre grouping", which is the honest place
  for it. Earlier drafts of this section said the copy never mentioned
  grouping at all, which was true of the 08-23 version and is not any more.
  The listing live today is another story: its LIBRARY section advertises
  "grouping by series, author, or genre" with no gate note
  (`store-listing.md` mirror, same section). That line is one more thing the
  flip-week refresh retires, and it stays a listing conflict, not a closed
  row, until the refresh is live.
- **Row 8, sorts: all 11 free since 2026-08-15.** Chart cell updated. The
  gate mechanism is retained deliberately ... a lever not pulled is not a
  lever removed.
- **Rows 4, 5, 6, 7, 10, 11, the listing gaps and conflicts: CLOSED
  2026-08-24.** The 2026-08-23 approval left every one of them open, because
  that approval had only ever checked the free-tier claims and not the
  feature claims. Jeff reopened it on 08-24 and the rewrite closed the set
  rather than patching the worst case. See section 1 above for what changed
  and why the paid list is now copied from `UNLOCK_BENEFITS` instead of
  written alongside it.
- **The 30-day trial is not in 2.1.0.** Decided 2026-08-16, cut 2026-08-22:
  v212 ships without it and the trial moves to 2.2 as a release beat. No
  listing or doc text may mention a trial until it ships.
