# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**MassDroid** - A native Android application that wraps the Music Assistant PWA (https://mass.asksakis.net) in a WebView, providing:
- Android MediaSession integration for Bluetooth/lock screen controls
- Media notification with playback controls and album art
- Progress bar with seek support
- Auto-play on Bluetooth connection

The app uses the **WebView's built-in SendSpin client** for audio playback. The PWA handles the SendSpin WebSocket connection and audio streaming. Android intercepts `navigator.mediaSession` updates via JavaScript injection to sync metadata and playback state with the native MediaSession.

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        MainActivity                              │
│  ┌─────────────────┐       ┌────────────────────────────────┐   │
│  │  WebView        │       │     MediaSession               │   │
│  │  - PWA UI       │       │  - Bluetooth controls          │   │
│  │  - SendSpin     │◄─────►│  - Lock screen controls        │   │
│  │  - Audio output │       │  - Notification controls       │   │
│  └─────────────────┘       └────────────────────────────────┘   │
│           │                              │                       │
│           │ JS Interceptor               │                       │
│           ▼                              ▼                       │
│  ┌─────────────────┐       ┌────────────────────────────────┐   │
│  │ AndroidMedia-   │       │     AudioService               │   │
│  │ Session (JS)    │──────►│  - Foreground notification     │   │
│  │ - metadata      │       │  - MediaStyle controls         │   │
│  │ - playbackState │       │  - Album artwork               │   │
│  │ - positionState │       └────────────────────────────────┘   │
│  └─────────────────┘                                            │
└─────────────────────────────────────────────────────────────────┘
```

### Data Flow

1. **Metadata Flow (PWA → Android)**:
   ```
   PWA SendSpin → navigator.mediaSession.metadata → JS Interceptor
   → AndroidMediaSession.updateMetadata() → MediaSessionCompat + AudioService notification
   ```

2. **Playback Control Flow (Android → PWA)**:
   ```
   Bluetooth/Notification → MediaSession callback → executeMediaCommand()
   → window.musicPlayer.play/pause/next/previous() → PWA SendSpin handlers
   ```

3. **Position/Seek Flow**:
   ```
   PWA → navigator.mediaSession.setPositionState() → JS Interceptor
   → AndroidMediaSession.updatePositionState() → MediaSession progress bar

   User seeks → MediaSession.onSeekTo() → executeSeekCommand()
   → window.musicPlayer.seekTo() → PWA handler
   ```

## Core Components

### Kotlin Files (`app/src/main/java/net/asksakis/mass/`)

| File | Purpose |
|------|---------|
| `MainActivity.kt` | Main entry, WebView setup, MediaSession, JS interface |
| `AudioService.kt` | Foreground service, MediaStyle notification |
| `MusicAssistantBridge.kt` | Legacy bridge (mostly unused, kept for compatibility) |
| `BluetoothAutoPlayReceiver.kt` | Detects Bluetooth audio connections for auto-play |
| `NetworkChangeMonitor.kt` | Monitors network changes for auto-resume playback |
| `PreferencesHelper.kt` | SharedPreferences wrapper for settings |
| `SettingsActivity.kt` | Settings screen with PreferenceFragment |

### Key JavaScript Interface

The `AndroidMediaSession` interface is exposed to JavaScript via `@JavascriptInterface`:

```kotlin
// Called by JS interceptor when PWA updates metadata
fun updateMetadata(title: String, artist: String, album: String, artworkUrl: String, durationMs: Long)

// Called by JS interceptor when playback state changes
fun updatePlaybackState(state: String, positionMs: Long)

// Called by JS interceptor when position updates
fun updatePositionState(durationMs: Long, positionMs: Long, playbackRate: Float)
```

### JavaScript Interceptor

Injected in `onPageStarted()`, intercepts:
- `navigator.mediaSession.metadata` setter
- `navigator.mediaSession.playbackState` setter
- `navigator.mediaSession.setPositionState()`
- `navigator.mediaSession.setActionHandler()` (captures play/pause/next/prev/seekto handlers)

Creates `window.musicPlayer` interface for Android → PWA commands.

## Build & Development

### Prerequisites
- Android SDK (API 34)
- Kotlin 1.9+
- Gradle 8.x

### Build Commands

```bash
# From project root (/home/sfortis/work/massdroid_pwa_new)

# Build debug APK
bash gradlew assembleDebug

# Clean build (use when getting resource errors)
bash gradlew clean assembleDebug

# Build and install to device (Windows WSL)
bash gradlew assembleDebug && /mnt/c/Users/d.fortis/AppData/Local/Android/Sdk/platform-tools/adb.exe install -r app/build/outputs/apk/debug/app-debug.apk
```

### ADB Access (WSL Environment)

**IMPORTANT**: Always use Windows ADB, not Linux `/usr/bin/adb`. The device is connected via USB to Windows.

```bash
# Windows ADB (ALWAYS USE THIS)
/mnt/c/Users/d.fortis/AppData/Local/Android/Sdk/platform-tools/adb.exe

# Install APK
/mnt/c/Users/d.fortis/AppData/Local/Android/Sdk/platform-tools/adb.exe install -r app/build/outputs/apk/debug/app-debug.apk

# View logs
/mnt/c/Users/d.fortis/AppData/Local/Android/Sdk/platform-tools/adb.exe logcat | grep -E "(MainActivity|AudioService|MediaSession|Bluetooth)"

# Do NOT use /usr/bin/adb - it won't see the device
```

## Debugging

### Logcat Filters

```bash
# All app logs
adb logcat | grep -E "(MainActivity|AudioService|MusicAssistantBridge|BluetoothAutoPlay)"

# MediaSession specific
adb logcat | grep -i mediasession

# JavaScript interceptor (in Chrome DevTools console)
# Look for [MediaSessionInterceptor] prefix
```

### Chrome DevTools (WebView Debugging)

1. Open Chrome browser on computer
2. Navigate to `chrome://inspect`
3. Find app's WebView and click "inspect"
4. Console shows JS interceptor logs with `[MediaSessionInterceptor]` prefix

### Test Commands (Chrome DevTools Console)

```javascript
// Test metadata update
AndroidMediaSession.updateMetadata("Test", "Artist", "Album", "", 180000);

// Test playback controls
window.musicPlayer.play();
window.musicPlayer.pause();
window.musicPlayer.next();
window.musicPlayer.seekTo(60); // Seek to 60 seconds

// Check registered handlers
window.musicPlayer._getHandlers();

// Check position state
window._mediaPositionState;
```

## Configuration

### Settings (in app)
- **PWA URL**: Server URL (default: https://mass.asksakis.net)
- **Keep Screen On**: Prevent screen timeout
- **Auto-play on Bluetooth**: Auto-start playback when Bluetooth audio connects
- **Auto-resume on network change**: Resume playback after network reconnection (WiFi/mobile switch)

### Files
- `app/build.gradle.kts` - Dependencies, SDK versions
- `app/src/main/AndroidManifest.xml` - Permissions, service declarations
- `app/src/main/res/xml/preferences.xml` - Settings screen layout
- `app/src/main/res/xml/network_security_config.xml` - Network security rules

### Permissions Required
```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
```

## Features

### Implemented
- [x] WebView PWA wrapper
- [x] MediaSession integration (Bluetooth, lock screen, notification)
- [x] Media notification with album art
- [x] Play/Pause/Next/Previous controls
- [x] Progress bar with seek support
- [x] Auto-play on Bluetooth connection
- [x] Settings screen
- [x] Keep screen on option
- [x] Debounced metadata updates (prevents notification flickering)
- [x] Network change detection + auto-resume playback

### Planned
- [ ] Battery optimizations (reduce logging in release builds)

## Troubleshooting

### Build Errors

**"Resource compilation failed"**
```bash
bash gradlew clean assembleDebug
```

**"Kotlin daemon compilation failed"**
```bash
./gradlew --stop
bash gradlew clean assembleDebug
```

### Runtime Issues

**Notification not showing metadata**
- Check Chrome DevTools console for `[MediaSessionInterceptor]` logs
- Verify `AndroidMediaSession` interface is available: `typeof window.AndroidMediaSession`

**Bluetooth controls not working**
- Check if `window.musicPlayer._getHandlers()` returns handlers
- Verify MediaSession is active in logs

**Audio stops on notification click**
- Fixed: Using `FLAG_ACTIVITY_SINGLE_TOP` instead of `FLAG_ACTIVITY_CLEAR_TOP`

## Code Style

- **Language**: Kotlin
- **Min SDK**: 26 (Android 8.0)
- **Target SDK**: 34 (Android 14)
- **Architecture**: Single Activity with WebView
- **Threading**: Main thread for UI, background threads for artwork loading
