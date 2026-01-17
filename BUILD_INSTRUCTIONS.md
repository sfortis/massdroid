# Build Instructions for Mass PWA Android App

## Quick Start

### Using Android Studio (Recommended)

1. **Open the project**
   - Launch Android Studio
   - Select "Open an existing project"
   - Navigate to `/mnt/c/Users/d.fortis/OneDrive/Projects/massdroid_pwa1`
   - Click OK

2. **Wait for Gradle sync**
   - Android Studio will automatically sync Gradle
   - Wait for the process to complete (shown in bottom status bar)

3. **Connect your Android device or start an emulator**
   - Enable USB debugging on your device
   - Or create/start an AVD (Android Virtual Device) in Android Studio

4. **Run the app**
   - Click the green "Run" button (or press Shift+F10)
   - Select your device from the deployment target dialog
   - The app will build and install automatically

### Using Command Line

1. **Navigate to project directory**
   ```bash
   cd /mnt/c/Users/d.fortis/OneDrive/Projects/massdroid_pwa1
   ```

2. **Build debug APK**
   ```bash
   ./gradlew assembleDebug
   ```

3. **Install on device**
   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

   Or use Gradle:
   ```bash
   ./gradlew installDebug
   ```

## Building Release Version

### Command Line

1. **Build unsigned release APK**
   ```bash
   ./gradlew assembleRelease
   ```
   Output: `app/build/outputs/apk/release/app-release-unsigned.apk`

2. **Sign the APK** (you'll need a keystore)
   ```bash
   jarsigner -verbose -sigalg SHA256withRSA -digestalg SHA-256 \
     -keystore your-keystore.jks \
     app/build/outputs/apk/release/app-release-unsigned.apk \
     your-key-alias
   ```

3. **Align the APK**
   ```bash
   zipalign -v 4 app/build/outputs/apk/release/app-release-unsigned.apk \
     app/build/outputs/apk/release/app-release.apk
   ```

### Android Studio

1. **Build > Generate Signed Bundle / APK**
2. Select **APK**
3. Create new keystore or use existing one
4. Fill in keystore details
5. Choose release build variant
6. Click Finish

## Troubleshooting

### Gradle Sync Failed

- Check internet connection (Gradle needs to download dependencies)
- Try "File > Invalidate Caches / Restart" in Android Studio
- Ensure you have JDK 8 or later installed

### Build Failed

- Check that Android SDK is installed with API level 34
- Verify that ANDROID_HOME environment variable is set
- Run `./gradlew clean` then try building again

### ADB Not Recognized (WSL)

If using WSL and Windows ADB:
```bash
# Use the Windows ADB path specified in CLAUDE.md
/mnt/c/Users/d.fortis/AppData/Local/Android/Sdk/platform-tools/adb.exe devices
```

### App Crashes on Launch

- Check that minimum API level (26 / Android 8.0) is met
- Review logs: `adb logcat | grep "net.asksakis.mass"`

## Testing the App

After installation:

1. **Launch the app** - You should see the Mass PWA loading
2. **Test navigation drawer** - Tap the burger menu icon (top-left)
3. **Test settings**:
   - Open navigation drawer
   - Tap "Settings"
   - Try changing the URL
   - Toggle "Keep Screen On"
4. **Test WebView navigation**:
   - Navigate within the PWA
   - Press back button - should navigate WebView history
5. **Test refresh** - Use the refresh option from navigation drawer

## Development Notes

- **Hot Reload**: Not available for Kotlin (unlike Flutter/React Native)
- **Incremental Builds**: Android Studio caches builds for faster compilation
- **Debug Logs**: Use `Log.d("TAG", "message")` in Kotlin code
- **View Logs**: `adb logcat` or Android Studio's Logcat panel

## Files Overview

Key files you might need to modify:

- **Default URL**: `app/src/main/res/values/strings.xml` (settings_url_default)
- **App Name**: `app/src/main/res/values/strings.xml` (app_name)
- **Colors**: `app/src/main/res/values/colors.xml`
- **Theme**: `app/src/main/res/values/themes.xml`
- **App Icon**: `app/src/main/res/drawable/ic_launcher_foreground.xml`
- **Permissions**: `app/src/main/AndroidManifest.xml`
- **Main Logic**: `app/src/main/java/net/asksakis/mass/MainActivity.kt`

## Next Steps

After successful build:

1. Test on multiple Android versions (especially minimum API 26)
2. Replace placeholder launcher icon with custom icon
3. Configure ProGuard rules if needed for release
4. Set up signing configuration in build.gradle.kts for automated release builds
5. Consider adding crash reporting (e.g., Firebase Crashlytics)
6. Add app to Google Play Console for distribution
