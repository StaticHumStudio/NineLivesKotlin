# Android Auto Review Checklist

Last updated: 2026-06-05

Nine Lives declares Android Auto media support through `com.google.android.gms.car.application` and `PlaybackService`, so the Play review path includes car quality checks.

Use this checklist before uploading the release AAB.

## Required Manual Evidence

Tool:

```text
Android Auto Desktop Head Unit
```

Artifact to keep:

```text
One short screen recording that shows browse, playback, controls, and stop behavior.
```

## Core Media Checks

| Check | Expected result | Status |
| --- | --- | --- |
| DHU launches and connects to the app | Nine Lives appears as a media app | Not tested |
| Browse root loads | Root items appear within 10 seconds | Not tested |
| Library browse works | User can reach playable audiobook items | Not tested |
| Recently played works | Items load or empty state is clear | Not tested |
| Search works | Search returns matching media or a clear empty state | Not tested |
| Playback starts from a selected item | Audio starts only after user action | Not tested |
| No autoplay on launch | App does not start playback just from opening in car UI | Not tested |
| Now Playing metadata | Title, author, cover art if available, and progress are sensible | Not tested |
| Transport controls | Pause, resume, skip, and chapter navigation behave correctly | Not tested |
| Stop behavior | Playback can be stopped or paused by the user | Not tested |
| Notification behavior | Only relevant media playback notification appears | Not tested |
| Error states | Missing network, missing login, or empty library tells user to check phone when needed | Not tested |
| No dead ends | Every browse route either shows content or a clear empty state | Not tested |

## Phone Side Checks During Car Flow

| Check | Expected result | Status |
| --- | --- | --- |
| Login required state | Car flow does not ask for credentials on the car screen | Not tested |
| Permission required state | Car flow tells user to complete setup on phone | Not tested |
| Server unavailable state | Car flow does not freeze or loop | Not tested |
| Local downloads | Downloaded items remain playable if server is offline | Not tested |

## Store Listing Alignment

The store listing claims:

```text
Full media browsing, search, playback controls, and chapter navigation from your car screen.
```

Do not submit until DHU evidence supports that sentence. If search or chapter navigation fails in DHU, weaken the store listing before upload.

## Official Reference

Android car app quality:

https://developer.android.com/docs/quality-guidelines/car-app-quality
