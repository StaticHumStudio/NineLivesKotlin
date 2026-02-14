# RAM Optimization Plan

This document outlines a staged plan to reduce memory usage without destabilizing playback behavior.

## Current hotspots

1. **Library list duplication in memory**
   - `LibraryViewModel` stores both `allBooks` and `filteredBooks`, duplicating large collections.
2. **Client-side filtering/sorting for all books**
   - Full-list filtering and sorting creates short-lived allocations on every query/toggle change.
3. **Image cache growth risk**
   - Cover-art loading is frequent; without explicit caps, cache usage may grow beyond desired limits on low-RAM devices.

## Phase 1 (low risk, immediate)

1. **Avoid list duplication in UI state**
   - Keep only the canonical source list in state.
   - Compute filtered/sorted output as a derived stream where possible.
2. **Use DB-side constraints for cheap filters**
   - Push `libraryId`, `downloaded-only`, and simple search predicates into DAO queries.
3. **Add memory telemetry baselines**
   - Track PSS and Java heap in debug builds during library browsing and rapid tab/sort changes.

## Phase 2 (medium risk, large gains)

1. **Adopt Paging 3 for library results**
   - Replace full in-memory lists with `Flow<PagingData<AudioBook>>`.
   - Render incrementally with `collectAsLazyPagingItems()`.
2. **Move sort/filter work to SQL where possible**
   - Add DAO queries for common sort modes and tabs.
   - Keep only exceptional transforms in Kotlin.
3. **Introduce lightweight projections for list rows**
   - Fetch only fields required by the list screen to reduce object size.

## Phase 3 (targeted media memory controls)

1. **Bound Coil memory cache**
   - Set explicit percent-based memory cap for low-RAM devices.
2. **Prefer downsampled thumbnails**
   - Request thumbnail-appropriate sizes for grid/list covers.
3. **Preload strategy guardrails**
   - Avoid aggressive preloading when scrolling velocity is high.

## Validation checklist

- Compare before/after memory while:
  - Opening large libraries
  - Switching tabs/sort modes rapidly
  - Typing in search
  - Navigating between Home, Library, and Player repeatedly
- Ensure no regressions in:
  - Time-to-first-content in Library
  - Scroll smoothness
  - Playback stability

## Rollout strategy

1. Ship Phase 1 first and measure.
2. Gate Phase 2 behind a feature flag for internal/beta testing.
3. Enable Phase 3 defaults conditionally for low-RAM devices first.
