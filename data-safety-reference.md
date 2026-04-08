# Google Play Data Safety Questionnaire — Reference Sheet

> Use this as a guide when filling out the Data Safety section in Play Console.
> Based on the app's privacy policy and actual data practices.

---

## Overview Questions

**Does your app collect or share any of the required user data types?**
Yes — but only device-local and user-initiated.

**Does your app use encryption to protect collected data?**
Yes — credentials and auth tokens are encrypted with AES-256-GCM via EncryptedSharedPreferences.

**Do you provide a way for users to request data deletion?**
Yes — users can clear app data or uninstall. Server data is managed on their own Audiobookshelf server.

---

## Data Types to Declare

### 1. Crash Logs (App info and performance > Crash logs)

| Field | Value |
|-------|-------|
| Collected | Yes |
| Shared | No |
| Required or optional | Optional — user must actively choose to send each crash report |
| Purpose | App functionality (crash diagnosis) |
| Ephemeral | No (retained until issue is resolved, then deleted) |
| User-initiated | Yes — opt-in dialog after each crash |

**Details:** Crash reports include stack trace, app version, device model, Android version, and available memory. Sent via user's email client to developer inbox. No auth tokens, server URLs, or audiobook content included.

### 2. Device or other IDs (Device or other IDs)

| Field | Value |
|-------|-------|
| Collected | Yes |
| Shared | No |
| Required or optional | Required (for Audiobookshelf session identification) |
| Purpose | App functionality |
| Ephemeral | No |

**Details:** A randomly generated UUID, not tied to hardware identifiers (no IMEI, Android ID, etc.). Stored only on-device and sent to the user's own Audiobookshelf server for session tracking.

---

## Data Types to NOT Declare

These are NOT collected and should be answered "No":

| Category | Reason |
|----------|--------|
| Location | Not collected |
| Personal info (name, email, etc.) | Not collected by the app; credentials are for the user's own server |
| Financial info | Not collected |
| Health and fitness | Not collected |
| Messages | Not collected |
| Photos and videos | Not collected |
| Audio files | Audiobook files are downloaded from user's own server, not collected by developer |
| Files and docs | Not collected |
| Calendar | Not collected |
| Contacts | Not collected |
| App activity (page views, etc.) | No analytics or usage tracking |
| Web browsing history | Not collected |

---

## Security Practices

| Question | Answer |
|----------|--------|
| Is data encrypted in transit? | Yes — HTTPS to user's Audiobookshelf server (user-configurable; self-signed cert support is opt-in) |
| Is data encrypted at rest? | Yes — credentials via AES-256-GCM EncryptedSharedPreferences |
| Can users request data deletion? | Yes — clear app data or uninstall |
| Does the app follow Google's Families Policy? | App is not targeted at children (COPPA disclosure in privacy policy) |

---

## Notes for Jeff

- The Play Console form is at: Play Console > Your App > App content > Data safety
- Walk through each data type category and answer based on the table above
- For "crash logs": select "Optional" and "User-initiated"
- For "device IDs": select "Required" since the UUID is generated on first launch
- The form will ask about third-party SDKs — ACRA sends via email (no ACRA servers involved)
- Review the generated summary before publishing; it appears on the store listing
