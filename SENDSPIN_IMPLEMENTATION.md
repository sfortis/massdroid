# Sendspin Implementation for Music Assistant Android

This document describes the Sendspin audio streaming implementation added to the Music Assistant Android PWA app.

## Reference Repository

We analyzed the official Music Assistant KMP (Kotlin Multiplatform) client app:

```
https://github.com/music-assistant/kmp-client-app
```

Cloned to `/tmp/music-assistant-kmp/` for analysis.

### Key Files Analyzed

| File | Purpose |
|------|---------|
| `SendspinClient.kt` | Main client orchestration |
| `SendspinCapabilities.kt` | Client capabilities (formats, roles) |
| `WebSocketHandler.kt` | WebSocket connection management |
| `MessageDispatcher.kt` | Protocol message routing |
| `Messages.kt` | All JSON message models |
| `Binary.kt` | Binary audio message parsing |
| `AudioStreamManager.kt` | Audio buffering and playback |
| `MediaPlayerController.android.kt` | Android AudioTrack + AudioFocus |
| `MainMediaPlaybackService.kt` | Foreground service |
| `AndroidAutoPlaybackService.kt` | MediaBrowserService for Android Auto |

## Sendspin Protocol Overview

Sendspin is Music Assistant's protocol for streaming audio to client devices. It registers the client as a native player in the MA ecosystem.

### Connection

```
WebSocket: ws://[server]:8927/sendspin
```

### Message Flow

```
1. CLIENT → SERVER: client/hello
   {
     "type": "client/hello",
     "payload": {
       "client_id": "uuid",
       "name": "Device Name",
       "version": 1,
       "supported_roles": ["player/v1", "metadata/v1"],
       "player_support": {
         "supported_formats": [
           {"codec": "pcm", "sample_rate": 48000, "channels": 2, "bit_depth": 16}
         ],
         "buffer_capacity": 500000
       }
     }
   }

2. SERVER → CLIENT: server/hello
   {
     "type": "server/hello",
     "payload": {
       "server_id": "...",
       "name": "Music Assistant",
       "version": 1,
       "active_roles": ["player/v1", "metadata/v1"],
       "connection_reason": "discovery"
     }
   }

3. CLIENT → SERVER: client/state (initial)
   {
     "type": "client/state",
     "payload": {
       "player": {
         "state": "synchronized",
         "volume": 100,
         "muted": false
       }
     }
   }

4. CLOCK SYNC (every 1 second):
   CLIENT → SERVER: client/time
   SERVER → CLIENT: server/time

5. When user selects this device as output:
   SERVER → CLIENT: stream/start
   {
     "type": "stream/start",
     "payload": {
       "player": {
         "codec": "pcm",
         "sample_rate": 48000,
         "channels": 2,
         "bit_depth": 16
       }
     }
   }

6. SERVER → CLIENT: [BINARY] Audio chunks
   Format: 1 byte type + 8 bytes timestamp (big-endian int64) + PCM data

7. SERVER → CLIENT: stream/metadata
   {
     "type": "stream/metadata",
     "payload": {
       "title": "Song Title",
       "artist": "Artist Name",
       "album": "Album Name",
       "artwork_url": "https://..."
     }
   }

8. SERVER → CLIENT: stream/end (when playback stops)
```

### Binary Message Format

```
Byte 0:      Message type (4 = AUDIO_CHUNK)
Bytes 1-8:   Timestamp (big-endian int64, microseconds)
Bytes 9+:    Raw PCM audio data
```

## Implementation Details

### New Files Created

```
android/app/src/main/java/net/asksakis/mass/sendspin/
├── SendspinMessages.java    # Protocol message models
├── SendspinClient.java      # WebSocket + protocol handler
├── AudioStreamPlayer.java   # AudioTrack + AudioFocus
├── SendspinMediaService.java # MediaBrowserService
└── SendspinManager.java     # MainActivity integration helper
```

### SendspinMessages.java

Handles all JSON message serialization/deserialization:

- `buildClientHello()` - Creates client registration message
- `buildClientTime()` - Creates clock sync message
- `buildClientState()` - Creates player state message
- `buildClientGoodbye()` - Creates disconnect message
- `ServerHello` - Parses server hello response
- `ServerTime` - Parses clock sync response
- `StreamStart` - Parses stream configuration
- `StreamMetadata` - Parses track metadata
- `ServerCommand` - Parses volume/mute commands
- `BinaryAudioMessage` - Parses binary audio chunks

### SendspinClient.java

Main client implementation:

- OkHttp WebSocket connection
- Protocol state machine
- Clock synchronization (NTP-style offset calculation)
- Binary message routing to AudioStreamPlayer
- Automatic reconnection handling
- Listener interface for UI updates

### AudioStreamPlayer.java

Android audio output:

- `AudioTrack` for raw PCM streaming
- `AudioFocusRequest` for proper audio focus management
- Handles `AUDIOFOCUS_GAIN`, `AUDIOFOCUS_LOSS`, `AUDIOFOCUS_LOSS_TRANSIENT`
- `ACTION_AUDIO_BECOMING_NOISY` receiver for headphone/Bluetooth disconnect
- Volume and mute control

### SendspinMediaService.java

Android Auto and Bluetooth integration:

- Extends `MediaBrowserServiceCompat`
- `MediaSessionCompat` for media controls
- Foreground notification with play/pause/next/previous
- Artwork loading and display
- Proper service lifecycle management

### SendspinManager.java

Simple facade for MainActivity:

- `start(serverHost)` - Starts Sendspin service
- `stop()` - Stops Sendspin service
- `extractHost(url)` - Extracts host from full URL

## Modified Files

### build.gradle

```gradle
// Added OkHttp for WebSocket
implementation 'com.squareup.okhttp3:okhttp:4.12.0'
```

### AndroidManifest.xml

```xml
<!-- Android Auto metadata -->
<meta-data
    android:name="com.google.android.gms.car.application"
    android:resource="@xml/automotive_app_desc"/>
<meta-data
    android:name="android.app.category"
    android:value="android.app.category.MEDIA"/>

<!-- Sendspin Media Service -->
<service
    android:name=".sendspin.SendspinMediaService"
    android:exported="true"
    android:foregroundServiceType="mediaPlayback">
    <intent-filter>
        <action android:name="android.media.browse.MediaBrowserService"/>
    </intent-filter>
</service>
```

### res/xml/automotive_app_desc.xml (new)

```xml
<?xml version="1.0" encoding="utf-8"?>
<automotiveApp>
    <uses name="media"/>
</automotiveApp>
```

### MainActivity.java

```java
// Added import
import net.asksakis.mass.sendspin.SendspinManager;

// Added field
private SendspinManager sendspinManager;

// Added to Bluetooth receiver - start Sendspin on Bluetooth connect
if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
    startSendspinForCarMode();
}

// Added ACTION_ACL_CONNECTED to IntentFilter
filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);

// Added methods
private void startSendspinForCarMode() { ... }
private void stopSendspin() { ... }
private String getBluetoothDeviceName(Context context, BluetoothDevice device) { ... }

// Added cleanup in onDestroy
if (sendspinManager != null) {
    sendspinManager.release();
}
```

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                         MainActivity                                 │
│  ┌─────────────────┐    ┌─────────────────┐    ┌────────────────┐  │
│  │  WebView (PWA)  │    │ BluetoothReceiver│    │ SendspinManager│  │
│  │  Remote Control │    │ ACL_CONNECTED    │───▶│                │  │
│  └─────────────────┘    └─────────────────┘    └───────┬────────┘  │
└─────────────────────────────────────────────────────────┼───────────┘
                                                          │
                                                          ▼
┌─────────────────────────────────────────────────────────────────────┐
│                     SendspinMediaService                            │
│  ┌─────────────────┐    ┌─────────────────┐    ┌────────────────┐  │
│  │  MediaSession   │    │ SendspinClient  │    │ Notification   │  │
│  │  (BT controls)  │    │                 │    │                │  │
│  └─────────────────┘    └────────┬────────┘    └────────────────┘  │
└──────────────────────────────────┼──────────────────────────────────┘
                                   │
                    WebSocket ws://server:8927/sendspin
                                   │
                                   ▼
┌─────────────────────────────────────────────────────────────────────┐
│                     Music Assistant Server                          │
│  ┌─────────────────┐    ┌─────────────────┐    ┌────────────────┐  │
│  │  Player Manager │    │  Audio Pipeline │    │ Sendspin Server│  │
│  │  (sees Android) │    │  (PCM stream)   │───▶│ (port 8927)    │  │
│  └─────────────────┘    └─────────────────┘    └────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
                                   │
                           Binary audio chunks
                                   │
                                   ▼
┌─────────────────────────────────────────────────────────────────────┐
│                      AudioStreamPlayer                              │
│  ┌─────────────────┐    ┌─────────────────┐    ┌────────────────┐  │
│  │   AudioTrack    │    │   AudioFocus    │    │ Noisy Receiver │  │
│  │   (PCM output)  │    │   Management    │    │ (BT disconnect)│  │
│  └────────┬────────┘    └─────────────────┘    └────────────────┘  │
└───────────┼─────────────────────────────────────────────────────────┘
            │
            ▼
      Android Speaker → Bluetooth A2DP → Car Speakers
```

## Usage

### Automatic (Car Mode)

1. Connect phone to car via Bluetooth
2. App detects `ACTION_ACL_CONNECTED`
3. Sendspin starts automatically
4. Android device appears as player in Music Assistant
5. Select it as output → music plays through car speakers

### Manual (Future Enhancement)

Could add a toggle in settings to manually enable/disable Sendspin mode.

## Dependencies

- **OkHttp 4.12.0** - WebSocket client
- **androidx.media:media:1.7.0** - MediaSession/MediaBrowserService (already present)

## Known Limitations

1. **PCM only** - Currently only supports PCM codec. Opus decoding not implemented.
2. **No seek** - Seek commands from Bluetooth not forwarded to server yet.
3. **No queue browsing** - MediaBrowserService returns empty list for `onLoadChildren`.

## Future Improvements

1. Add Opus decoder for compressed audio
2. Implement seek functionality
3. Add queue browsing for Android Auto
4. Add manual Sendspin toggle in settings
5. Handle network transitions gracefully
6. Add buffering status UI
