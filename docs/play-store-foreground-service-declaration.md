# Play Store Foreground Service Declaration

Last updated: 2026-06-05

Use this when completing Play Console App content for foreground service permissions.

## Manifest Evidence

The release manifest declares:

```text
android.permission.FOREGROUND_SERVICE
android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK
```

`PlaybackService` declares:

```text
android:foregroundServiceType="mediaPlayback"
```

The app targets API 36, so Play Console requires a foreground service declaration.

## Declaration Values

Foreground service type:

```text
Media playback
```

Use case:

```text
Background audio playback
```

Feature description:

```text
Nine Lives Audio uses a media playback foreground service so user selected audiobooks can keep playing while the app is backgrounded, the screen is off, lock screen controls are used, notification controls are used, or Android Auto media controls are used.
```

Why the task must start immediately:

```text
Playback starts only after the user chooses or resumes an audiobook. The media session and notification must start immediately so playback controls, audio focus, and progress handling are available while the user is listening.
```

User impact if the task is deferred:

```text
The selected audiobook may not start when the user presses play. Lock screen controls, notification controls, and Android Auto controls may not appear when expected.
```

User impact if the task is interrupted:

```text
Playback can stop unexpectedly. Listening progress may be delayed or inaccurate until the app recovers. Android Auto and lock screen controls may stop responding.
```

How the user stops the task:

```text
The user can pause playback, stop playback from the app, use notification controls, use lock screen controls, or use Android Auto controls. The service only runs while media playback is active or being controlled through the media session.
```

## Demo Video Script

Record a short phone screen video. If possible, record a second Android Auto Desktop Head Unit clip.

Phone clip:

1. Launch Nine Lives Audio.
2. Open a library item.
3. Press play.
4. Show the playback notification.
5. Background the app.
6. Show audio continuing.
7. Use notification controls to pause and resume.
8. Turn the screen off or show lock screen controls if available.
9. Stop playback.

Android Auto clip:

1. Connect the app to Android Auto Desktop Head Unit.
2. Open the media app list.
3. Select Nine Lives Audio.
4. Browse library content.
5. Start playback.
6. Use car media controls to pause, resume, and skip.
7. Show that playback continues through the media session.

## Official References

Foreground service declaration requirements:

https://support.google.com/googleplay/android-developer/answer/13392821

Foreground service policy:

https://support.google.com/googleplay/android-developer/answer/16273414
