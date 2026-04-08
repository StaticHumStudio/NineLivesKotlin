# Nine Lives Audio — Play Store Data Safety Reference

Use this while filling out the Data Safety section in Google Play Console.
Every answer maps to what the app actually does based on the privacy policy and codebase.

---

## Data Collection Overview

**Does your app collect or share any of the required user data types?** YES

(You must declare crash report data. Everything else is "not collected.")

---

## Data Types — What to Declare

### Personal Info
- Name: **Not collected**
- Email address: **Not collected**
- User IDs: **Not collected** (random UUID per install, never leaves device)
- Address: **Not collected**
- Phone number: **Not collected**
- Race/ethnicity: **Not collected**
- Political/religious beliefs: **Not collected**
- Sexual orientation: **Not collected**
- Other personal info: **Not collected**

### Financial Info
- Purchase history: **Not collected**
- Credit card info: **Not collected**
- Other financial info: **Not collected**

### Location
- Approximate location: **Not collected**
- Precise location: **Not collected**

### Web Browsing
- Web browsing history: **Not collected**

### Emails and Text Messages
- Emails: **Not collected**
- SMS/MMS: **Not collected**
- Other messages: **Not collected**

### Photos and Videos
- Photos: **Not collected**
- Videos: **Not collected**

### Audio Files
- Voice/sound recordings: **Not collected**
- Music files: **Not collected**
- Other audio files: **Not collected**

(The app plays audio from the user's server but does not collect or transmit audio data to any third party.)

### Files and Docs
- Files and docs: **Not collected**

### Calendar
- Calendar events: **Not collected**

### Contacts
- Contacts: **Not collected**

### App Activity
- App interactions: **Not collected**
- In-app search history: **Not collected**
- Installed apps: **Not collected**
- Other user-generated content: **Not collected**
- Other actions: **Not collected**

### Device or Other IDs
- Device or other IDs: **Not collected**

(A random UUID is generated per install for internal use only. It never leaves the device.)

### App Info and Performance (DECLARE THIS)
- Crash logs: **Collected**
  - Is this data shared with third parties? **No**
  - Is this data processed ephemerally? **No** (sent via email to developer)
  - Is this data collection required or can users opt out? **Optional** (user must confirm dialog before sending)
  - Why is this data collected? **App functionality** (bug fixing)
- Diagnostics: **Not collected**
- Other app performance data: **Not collected**

---

## Data Handling Declarations

**Is all collected data encrypted in transit?** YES
(Crash reports sent via email use standard email encryption. Server communication uses HTTPS by default. Self-signed cert option available for self-hosted setups.)

**Do you provide a way for users to request data deletion?** YES
(Uninstalling the app removes all local data. No data is stored on any third-party server. Crash reports can be requested for deletion via developer email.)

**Does your app comply with the Families Policy?** Not applicable (not a kids app)

---

## Security Section

**Does your app use encryption?** YES
- Auth tokens: AES-256-GCM via Android EncryptedSharedPreferences
- Network: HTTPS (with optional self-signed cert TOFU)

---

## Summary: What You're Checking in Play Console

| Data Type | Collected | Shared | Purpose |
|-----------|-----------|--------|---------|
| Crash logs | Yes | No | App functionality (bug fixing) |
| Everything else | No | No | N/A |

That's it. One data type. No sharing. Optional collection with user confirmation.

---

## Privacy Policy URL

https://statichum.studio/nine-lives-audio/privacy

(Enter this in the Play Console listing and data safety section.)
