# Nine Lives Audio Play Store Compliance Audit

Date: 2026-06-04 local time

Scope: Play Store release audit for `com.ninelivesaudio.app`, current `master` at `cee23e8` plus local release follow-up edits.

The initial pass checked the release APK path. The 2026-06-05 follow-up built and validated the release AAB. No upload has been performed.

Follow-up on 2026-06-05: release docs were updated after this audit. Data Safety now lives in `data-safety-reference.md`, the old `docs/DataSafety_Reference.md` is only a pointer, the foreground service declaration packet lives in `docs/play-store-foreground-service-declaration.md`, and the Android Auto review checklist lives in `docs/android-auto-review-checklist.md`. The `studio-web` privacy policy source was also updated to disclose optional manual feedback reports.

## Build Evidence

Validation command:

```bash
ANDROID_HOME=/home/static/.local/opt/android-sdk ANDROID_SDK_ROOT=/home/static/.local/opt/android-sdk ./gradlew :app:lintRelease :app:testDebugUnitTest :app:validateSigningRelease :app:assembleRelease :app:bundleRelease
```

Result: pass after two narrow lint fixes.

Release artifact: `app/build/outputs/apk/release/app-release.apk`

APK size: 8.5 MB

Release AAB: `app/build/outputs/bundle/release/app-release.aab`

AAB size: 9.7 MB

Application ID: `com.ninelivesaudio.app`

Version: `versionCode = 101`, `versionName = 1.0.1`

SDKs: `minSdk = 30`, `targetSdk = 36`, `compileSdk = 36`

Signing: verified with APK Signature Scheme v2. Certificate subject is `CN=Static Hum Studio, O=Static Hum Studio LLC, L=Huntington, ST=WV, C=US`.

AAB signing: `jarsigner -verify app-release.aab` reports `jar verified`. The self signed certificate warning is expected for the upload certificate path.

AAB structure: `bundletool validate --bundle=app-release.aab` completed without errors.

16 KB page size check: passed with `zipalign -c -P 16 -v 4 app-release.apk`.

Native libraries present:

```text
lib/arm64-v8a/libandroidx.graphics.path.so
lib/arm64-v8a/libdatastore_shared_counter.so
lib/armeabi-v7a/libandroidx.graphics.path.so
lib/armeabi-v7a/libdatastore_shared_counter.so
lib/x86/libandroidx.graphics.path.so
lib/x86/libdatastore_shared_counter.so
lib/x86_64/libandroidx.graphics.path.so
lib/x86_64/libdatastore_shared_counter.so
```

## Fixes Made During This Audit

`app/src/main/java/com/ninelivesaudio/app/ui/settings/SettingsScreen.kt`

The SAF folder picker now converts result flags into explicit persistable read and write grants before calling `takePersistableUriPermission`. This cleared a release lint `WrongConstant` error without changing the intended permission behavior.

`app/src/main/java/com/ninelivesaudio/app/service/PlaybackManager.kt`

`createPlayerListener()` is now annotated with `@OptIn(UnstableApi::class)`. This cleared two Media3 release lint `UnsafeOptInUsageError` failures around `onAudioSessionIdChanged` and `C.AUDIO_SESSION_ID_UNSET`.

## Current Pass Items

Target API compliance: pass. Google Play currently requires new apps and updates to target Android 15, API 35, or higher. This build targets API 36.

Package identity: pass. Release APK reports `com.ninelivesaudio.app`.

Release signing: pass for APK verification. The key is present locally and `validateSigningRelease` passes.

16 KB compatibility: pass for the release APK. This matters because the APK contains native libraries from dependencies.

Storage permissions: pass. The app does not request broad storage permissions such as `MANAGE_EXTERNAL_STORAGE`, `READ_MEDIA_*`, or legacy external storage permissions. Local library access uses SAF.

Advertising and monetization: pass from current code review. No ads, billing, or ad identifiers found.

Sensitive hardware permissions: pass. No camera, microphone, contacts, SMS, phone, calendar, location, or health permissions found.

Backup rules: mostly pass. Backup is enabled, but encrypted prefs and legacy settings are excluded from cloud backup and device transfer rules.

Privacy link in app: pass. The app opens `https://statichum.studio/apps/nine-lives/privacy`, which currently returns HTTP 200 after a trailing slash redirect.

## Submission Blockers

### 1. Data Safety needs Play Console entry, but repo docs are now current

Status after follow-up: fixed locally.

Use `data-safety-reference.md` when filling out Play Console. It now covers optional crash reports, optional manual diagnostics, Audiobookshelf server mode user IDs, app activity, generated device ID, and the current encryption caveat.

Important caveat: because the current build allows user entered `http://` Audiobookshelf server URLs, the recommended current answer for "all user data encrypted in transit" is No unless cleartext server URLs are blocked before release.

### 2. Privacy policy needs one content update

Status after follow-up: fixed in local `studio-web` source.

`src/pages/apps/nine-lives/privacy.astro` now discloses optional manual feedback reports, diagnostic fields, and optional recent app logs. The log switch is now off by default in the app.

Still needed: deploy `studio-web` so the public privacy URL reflects the new policy before Play Console submission.

### 3. Foreground Service declaration is required in Play Console

The release manifest declares:

`android.permission.FOREGROUND_SERVICE`

`android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK`

`PlaybackService` with `foregroundServiceType="mediaPlayback"`

Because the app targets Android 14 plus, Play Console requires an FGS declaration with a description, user impact, and video demo.

Status after follow-up: declaration copy is staged in `docs/play-store-foreground-service-declaration.md`.

Still needed: record and attach the foreground service demo video in Play Console.

### 4. Android Auto support needs manual review evidence

The manifest declares Android Auto media support through `com.google.android.gms.car.application` and a `MediaLibraryService`.

Google's car quality docs say car apps get additional manual review. The app should be tested with Android Auto DHU before submission.

Status after follow-up: the DHU evidence checklist is staged in `docs/android-auto-review-checklist.md`.

Still needed: run the DHU pass and keep a short recording.

### 5. AAB upload path is now built, but Play Console upload is still needed

Google Play requires Android App Bundle publishing for new apps.

Status after follow-up: fixed locally.

`app/build/outputs/bundle/release/app-release.aab` was built, signed, and validated with bundletool.

Still needed: upload the AAB to Play Console internal testing or internal app sharing and inspect Play's generated warnings.

## Watchlist

Cleartext HTTP is globally enabled in `network_security_config.xml`. This is justified for self hosted LAN Audiobookshelf servers, and the code defaults URLs to HTTPS unless the user explicitly types `http://`. Still, this is a review risk and should be mentioned in privacy or security copy as user configurable self hosted behavior.

Self signed certificate support uses a custom trust manager. It is opt in, host scoped, and uses TOFU fingerprint pinning. Lint still warns because custom trust managers are inherently risky. This is acceptable for a self hosted client if the privacy policy and Settings copy are clear.

`PlaybackService` is exported for media browser and Android Auto support. Lint warns that the exported service does not require a permission. The service does controller package and UID checks before allowing browse commands. Keep this in the Android Auto review notes.

Package visibility queries are present for email report intents. This is narrow and expected.

Dependency freshness warnings remain. Not submission blockers, but the high value updates are Media3, ACRA, AndroidX Security Crypto replacement planning, AGP patch version, and Compose BOM.

Launcher icon shape warnings remain. Not usually a hard blocker, but worth cleaning before final store polish.

## Official References Checked

Target API requirements:

https://support.google.com/googleplay/android-developer/answer/11926878

Data Safety form guidance:

https://support.google.com/googleplay/android-developer/answer/10787469

User Data policy:

https://support.google.com/googleplay/android-developer/answer/10144311

Foreground Service declaration:

https://support.google.com/googleplay/android-developer/answer/13392821

Car app quality:

https://developer.android.com/docs/quality-guidelines/car-app-quality

16 KB page size support:

https://developer.android.com/guide/practices/page-sizes

Android App Bundle:

https://developer.android.com/guide/app-bundle
