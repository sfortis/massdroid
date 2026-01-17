# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**MassDroid** - A native Android application that wraps the Music Assistant PWA in a WebView, providing:
- Android MediaSession integration for Bluetooth/lock screen controls
- Media notification with playback controls and album art
- Progress bar with seek support
- Auto-play on Bluetooth connection
- Auto-resume on network change

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

## Build & Development

### Prerequisites
- Android SDK (API 34)
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
- **Target SDK**: 34 (Android 14)
- **Architecture**: Single Activity with WebView
