# MassDroid

A native Android companion app for [Music Assistant](https://music-assistant.io/) that provides seamless integration with your phone's media controls.

## Features

- **Full Music Assistant UI** - Access the complete Music Assistant web interface
- **Native Media Controls** - Control playback from:
  - Lock screen
  - Notification shade
  - Bluetooth headphones/car stereo
  - Wear OS watches
- **Album Artwork** - See album art in notifications and on lock screen
- **Progress Bar** - Track progress with seekable progress bar
- **Auto-play on Bluetooth** - Automatically resume playback when connecting to Bluetooth audio
- **Auto-resume on Network Change** - Seamless playback when switching between WiFi and mobile data
- **Dark Mode** - Follows system theme

## Screenshots

*Coming soon*

## Installation

### Option 1: Download APK

Download the latest APK from the [Releases](https://github.com/sfortis/massdroid/releases) page.

### Option 2: Build from Source

```bash
git clone https://github.com/sfortis/massdroid.git
cd massdroid
./gradlew assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`

## Setup

1. Install the APK on your Android device
2. On first launch, enter your Music Assistant server URL (e.g., `https://your-server.com`)
3. Log in to Music Assistant
4. Start playing music!

## Requirements

- Android 8.0 (Oreo) or higher
- A running [Music Assistant](https://music-assistant.io/) server

## How It Works

MassDroid loads the Music Assistant PWA (Progressive Web App) in a WebView and bridges the web player with Android's MediaSession API. This allows native Android media controls to work with Music Assistant's SendSpin audio streaming.

## Settings

Access settings from the navigation drawer (hamburger menu):

- **Music Assistant URL** - Change your server URL
- **Keep Screen On** - Prevent screen timeout while app is open
- **Auto-play on Bluetooth** - Resume playback when Bluetooth audio connects
- **Auto-resume on Network Change** - Resume after WiFi/mobile switch

## Permissions

- **Internet** - Connect to your Music Assistant server
- **Bluetooth** - Detect Bluetooth audio connections for auto-play
- **Foreground Service** - Keep playing music when app is in background
- **Notifications** - Show media notification with playback controls

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## License

This project is licensed under the GNU General Public License v3.0 - see the [LICENSE](LICENSE) file for details.

## Acknowledgments

- [Music Assistant](https://music-assistant.io/) - The amazing music server this app connects to
- [SendSpin](https://github.com/music-assistant/sendspin) - The audio streaming protocol

## Support

If you encounter any issues, please [open an issue](https://github.com/sfortis/massdroid/issues) on GitHub.
