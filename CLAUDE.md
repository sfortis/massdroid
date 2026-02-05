# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**MassDroid** - A native Android application that wraps the Music Assistant PWA in a WebView, providing:
- Android MediaSession integration for Bluetooth/lock screen controls
- Media notification with playback controls and album art
- Progress bar with seek support
- Auto-play on Bluetooth audio connection (phone speaker only)
- Auto-stop on Bluetooth audio disconnection (phone speaker only)
- Auto-resume on network change (phone speaker only, with retry + WebView reload strategy)

### Important: Phone Speaker Scope

Bluetooth and network auto-resume features **only affect the phone speaker**. When external speakers (Sonos, Chromecast, etc.) are selected in Music Assistant, Bluetooth connect/disconnect events are ignored. This is by design - the app uses `checkIfPhoneIsActivePlayer()` to verify the phone's SendSpin player is active before triggering auto-play/stop/resume.

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

## Core Components

### Kotlin Files (`app/src/main/java/net/asksakis/massdroid/`)

| File | Purpose |
|------|---------|
| `MainActivity.kt` | Main entry, WebView setup, MediaSession, JS interface |
| `AudioService.kt` | Foreground service, MediaStyle notification |
| `BluetoothAutoPlayReceiver.kt` | Detects Bluetooth audio connections for auto-play |
| `NetworkChangeMonitor.kt` | Monitors network changes for auto-resume playback |
| `PreferencesHelper.kt` | SharedPreferences wrapper for settings |
| `SettingsActivity.kt` | Settings screen with PreferenceFragment |
| `UpdateChecker.kt` | GitHub release checker, APK download and install |

### JavaScript Files (`app/src/main/assets/js/`)

| File | Purpose |
|------|---------|
| `inject.js` | Entry point, loads other scripts in correct order |
| `ma-websocket.js` | Music Assistant WebSocket manager for API commands (play/stop/seek) |
| `mediasession-polyfill.js` | Intercepts `navigator.mediaSession` and forwards to Android |
| `player-selection-observer.js` | Tracks user's selected player in MA UI |
| `ws-interceptor.js` | Intercepts WebSocket connections, tracks SendSpin state for auto-resume |

## Build & Development

### Prerequisites
- Android SDK (API 35)
- Kotlin 1.9+
- Gradle 8.x

### Build Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Clean build (use when getting resource errors)
./gradlew clean assembleDebug

# Build release APK
./gradlew assembleRelease
```

## Configuration

### Settings (in app)
- **Music Assistant URL**: Your server URL (configured on first run)
- **Keep Screen On**: Prevent screen timeout
- **Auto-play on Bluetooth**: Auto-start playback when Bluetooth audio connects
- **Auto-resume on network change**: Resume playback after network reconnection

### Files
- `app/build.gradle.kts` - Dependencies, SDK versions
- `app/src/main/AndroidManifest.xml` - Permissions, service declarations
- `app/src/main/res/xml/preferences.xml` - Settings screen layout

## Code Style

- **Language**: Kotlin
- **Min SDK**: 26 (Android 8.0)
- **Target SDK**: 35 (Android 15)
- **Architecture**: Single Activity with WebView

## Release Workflow

**Always test locally before pushing!**

### 1. Build & Test

```bash
# Build debug APK
./gradlew assembleDebug

# Install on device
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Test the changes on device
```

### 2. Commit & Push

```bash
# After testing is successful
git add -A && git commit -m "Brief description"
git push origin master
```

### 3. Create Release

```bash
# IMPORTANT: Update version in app/build.gradle.kts first!
# - versionCode (increment by 1)
# - versionName (e.g., "1.0.3")

# Build release APK
bash gradlew assembleRelease

# Create GitHub release (name APK as massdroid-vX.X.X.apk)
cp app/build/outputs/apk/release/app-release.apk /tmp/massdroid-v1.x.x.apk
gh release create v1.x.x /tmp/massdroid-v1.x.x.apk \
  --title "MassDroid v1.x.x" \
  --notes "Brief changelog"
```

### Notes
- Keep commit messages brief
- Test on device before pushing
- **Always update versionCode/versionName before release!**
