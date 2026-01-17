# Mass PWA - Native Android App

A native Android application that loads the Mass PWA (https://mass.asksakis.net) in a WebView with Material Design 3 UI components.

## Features

- **WebView Integration**: Loads the Mass PWA with full JavaScript and DOM storage support
- **Material Design 3**: Modern UI with Material Design 3 components and theming
- **Navigation Drawer**: Burger menu with navigation options
- **URL Configuration**: Settings screen to configure the PWA URL
- **Keep Screen On**: Toggle to prevent screen from turning off while using the app
- **Adaptive Icon**: Material Design 3 compliant adaptive launcher icon
- **Dark Mode Support**: Automatic dark theme support following system preferences

## Technical Specifications

- **Minimum API Level**: 26 (Android 8.0 Oreo)
- **Target API Level**: 34 (Android 14)
- **Language**: Kotlin
- **Build System**: Gradle with Kotlin DSL
- **UI Framework**: Material Design 3 (com.google.android.material:material:1.11.0)

## Project Structure

```
app/
├── src/main/
│   ├── java/net/asksakis/mass/
│   │   ├── MainActivity.kt           # Main activity with WebView and navigation drawer
│   │   ├── SettingsActivity.kt       # Settings screen
│   │   └── PreferencesHelper.kt      # Shared preferences helper
│   ├── res/
│   │   ├── layout/
│   │   │   ├── activity_main.xml     # Main activity layout
│   │   │   ├── activity_settings.xml # Settings activity layout
│   │   │   └── nav_header.xml        # Navigation drawer header
│   │   ├── menu/
│   │   │   └── drawer_menu.xml       # Navigation drawer menu items
│   │   ├── values/
│   │   │   ├── strings.xml           # String resources
│   │   │   ├── colors.xml            # Material Design 3 color palette
│   │   │   └── themes.xml            # Material Design 3 themes
│   │   ├── values-night/
│   │   │   └── themes.xml            # Dark theme
│   │   ├── xml/
│   │   │   └── preferences.xml       # Settings preferences
│   │   └── drawable/                 # Vector icons
│   └── AndroidManifest.xml
└── build.gradle.kts
```

## Building the App

### Prerequisites

- Android Studio Arctic Fox or later
- JDK 8 or later
- Android SDK with API level 34

### Build Instructions

1. **Clone or open the project in Android Studio**

2. **Sync Gradle files**
   - Android Studio should automatically prompt to sync
   - Or go to File > Sync Project with Gradle Files

3. **Build the APK**
   ```bash
   ./gradlew assembleDebug
   ```
   The APK will be generated at: `app/build/outputs/apk/debug/app-debug.apk`

4. **Install on device**
   ```bash
   ./gradlew installDebug
   ```

   Or use adb directly:
   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

### Building Release APK

1. **Build release APK**
   ```bash
   ./gradlew assembleRelease
   ```

2. **Sign the APK** (if not configured in build.gradle)
   - Use Android Studio's Build > Generate Signed Bundle / APK
   - Or configure signing in `app/build.gradle.kts`

## Configuration

### Default Settings

- **Default URL**: https://mass.asksakis.net
- **Keep Screen On**: Disabled by default

### Changing Default URL

Edit `app/src/main/res/values/strings.xml`:
```xml
<string name="settings_url_default">https://your-url-here.com</string>
```

### Permissions

The app requires the following permissions (configured in AndroidManifest.xml):
- `INTERNET` - Required for WebView to load web content
- `ACCESS_NETWORK_STATE` - To check network connectivity
- `WAKE_LOCK` - For the "Keep Screen On" feature

## Features Implementation

### WebView Configuration

- JavaScript enabled
- DOM storage enabled
- Database enabled
- Zoom controls enabled
- Loading progress indicator
- Back button navigation through WebView history

### Navigation Drawer

The navigation drawer includes:
- **Home**: Navigate to the configured PWA URL
- **Refresh**: Reload the current page
- **Settings**: Open settings screen
- **Keep Screen On**: Toggle to prevent screen from sleeping

### Settings Screen

Users can configure:
- **PWA URL**: The URL to load in the WebView (persisted across app restarts)
- **Keep Screen On**: Toggle to keep screen on while app is active

Settings are saved using SharedPreferences and apply immediately.

## Material Design 3

The app follows Material Design 3 principles:

- **Color System**: Dynamic color palette with primary, secondary, and tertiary colors
- **Typography**: Material Design 3 typography scale
- **Components**: Material Toolbar, NavigationView, LinearProgressIndicator
- **Dark Theme**: Automatic dark theme support
- **Adaptive Icon**: Material Design 3 compliant launcher icon

## Development Notes

- The app uses ViewBinding for type-safe view access
- SharedPreferences are managed through a helper class for cleaner code
- The WebView is configured to handle all navigation internally
- Progress indicator shows loading state for better UX
- The app responds to system dark mode settings automatically

## License

[Add your license here]

## Contact

[Add your contact information here]
