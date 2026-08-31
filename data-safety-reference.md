# Nine Lives Audio Play Store Data Safety Reference

Last updated: 2026-08-24

> **STATUS 2026-08-24: this document does NOT describe what is currently
> declared.** The live Play listing for `com.ninelivesaudio.app` declares
> **"No data collected"** and **"No data shared with third parties"**, verified
> by reading the public store page on 2026-08-24. The June recommendation below
> ("Answer: Yes") was never what shipped. The 2.1.0 store listing copy makes no
> claim about what the Data safety section says, but its PRIVACY FIRST
> paragraph is consistent with the live form: no analytics, no tracking, no
> ads, the studio runs no servers, and the only paths that reach the studio at
> all (crash reports and feedback) are opt-in and go through the user's own
> email client. Do not "correct" the Console form to match the June
> analysis... that would contradict what the shipped listing promises about
> nothing automatic leaving the device.
>
> Who set the form to No, and on what reasoning, is NOT recorded anywhere and
> was not reconstructed. The defensible argument is Play's user-initiated
> transfer exemption, and it has two legs. Server-mode data goes to an
> Audiobookshelf server the user configured and controls, and Static Hum
> Studio receives none of it. The two paths that DO reach the studio, opt-in
> crash reports and manual feedback, leave through an email the user composes
> and sends from their own mail client, which is user-initiated in the most
> literal sense. Both legs are plausible, neither is verified as the actual
> reasoning, and it is a genuine judgment call rather than a settled question.
>
> The per-category analysis below is kept ON PURPOSE. If Google ever challenges
> the "No data collected" declaration, this is the fallback: it is the work of
> deciding what each category would say under the opposite reading.
>
> Note that the "encrypted in transit" question below is moot while "No data
> collected" stands, because Play only asks it once collection is declared. The
> underlying fact is still true: the build allows a user-configured `http://`
> server, so that question could not be answered Yes today.

Use this file as background analysis, not as the form's current answers.

This is based on the release code path as of 2026-06-05, the live privacy URL,
and the pre AAB Play compliance audit in
`docs/play-store-compliance-audit-2026-06-04.md`.

## Play Console Overview (JUNE ANALYSIS, SUPERSEDED BY THE LIVE FORM)

Question: Does the app collect or share any required user data types?

Answer: Yes.

Reason: the app can transmit data off the device to the user configured Audiobookshelf server, and optional reports can be sent to Static Hum Studio by email.

Question: Is all user data encrypted in transit?

Recommended current answer: No.

Reason: HTTPS is the default for server setup, but the current build allows a user to explicitly configure an `http://` Audiobookshelf server for local or self hosted setups. If we want to answer Yes, the release build must block cleartext server URLs before submission.

Question: Can users request deletion of collected data?

Answer: Yes.

Reason: local app data can be deleted by clearing app data or uninstalling. Audiobookshelf data is controlled by the user on their own server. Crash reports and manual feedback emails sent to Static Hum Studio can be deleted by request at `Static@StaticHum.Studio`.

Question: Is the app a Kids or Families app?

Answer: No.

## Data Types To Declare

### Personal Info, User IDs

Collected: Yes, for server mode.

Shared: Play Console judgment call.

Required or optional: Required for Audiobookshelf server features.

Purpose: App functionality.

Notes: Audiobookshelf login uses user account identity on the server the user configured. Static Hum Studio does not receive this data. If Play treats the user configured Audiobookshelf server as a third party destination, mark this as shared. If Play treats this as a user directed first party destination, mark it as not shared.

### App Activity, App Interactions

Collected: Yes, for server mode.

Shared: Play Console judgment call.

Required or optional: Required for progress sync, bookmarks, listening sessions, and server library behavior.

Purpose: App functionality.

Notes: Playback progress, bookmarks, library requests, and session updates can be sent to the user's Audiobookshelf server. Static Hum Studio does not receive this data.

### Device Or Other IDs

Collected: Yes, for server mode.

Shared: Play Console judgment call.

Required or optional: Required for Audiobookshelf playback session behavior.

Purpose: App functionality.

Notes: The app generates a random install UUID. It is not an IMEI, Android ID, serial number, or hardware fingerprint. It can be sent to the user's Audiobookshelf server as the playback session device ID.

### App Info And Performance, Crash Logs

Collected: Yes.

Shared: No.

Required or optional: Optional.

Purpose: App functionality.

Notes: Crash reports are user initiated and sent through the user's email client to Static Hum Studio. ACRA prepares the report locally. It does not send through ACRA servers.

Declare as optional because the user must choose to send each report.

### App Info And Performance, Diagnostics

Collected: Yes.

Shared: No.

Required or optional: Optional.

Purpose: App functionality.

Notes: Manual bug reports and upgrade requests include app version, build type, device manufacturer and model, Android version and API level, connection status text, and selected playback settings. Recent app logs can be attached only if the user turns on the optional log switch. The switch is off by default in the current release branch.

## Data Types To Answer No

Use No for these categories unless the app behavior changes before release:

| Category | Reason |
| --- | --- |
| Approximate location | Not requested or transmitted |
| Precise location | Not requested or transmitted |
| Financial info | No payments, billing, or purchase tracking in app |
| Health and fitness | Not used |
| Messages | Not used |
| Photos and videos | Not collected |
| Audio files | Audiobook files are downloaded from the user's server or accessed through SAF. The app does not upload audiobook files from the device |
| Files and docs | SAF local library metadata stays on device |
| Calendar | Not used |
| Contacts | Not used |
| Web browsing history | Not used |
| Installed apps | Not used |
| Search history | Local or server library search only. Static Hum Studio does not receive it |
| Advertising ID | No ads or ad ID dependency found |

## Security Practices

Credentials and auth tokens are encrypted at rest through Android encrypted storage.

Crash reports and manual feedback are sent by the user's email client.

Server traffic defaults to HTTPS, but user configured HTTP is currently allowed for local and self hosted servers. Because of that, answer No to "all user data encrypted in transit" unless cleartext server URLs are blocked before release.

Self signed certificate support is opt in and host scoped.

## Privacy Policy URL

Use:

```text
https://statichum.studio/apps/nine-lives/privacy
```

Do not use the old URL:

```text
https://statichum.studio/nine-lives-audio/privacy
```

The old URL returned 404 during the release audit.

## Official References

Target API requirements:

https://support.google.com/googleplay/android-developer/answer/11926878

Data Safety form guidance:

https://support.google.com/googleplay/android-developer/answer/10787469

User Data policy:

https://support.google.com/googleplay/android-developer/answer/10144311
