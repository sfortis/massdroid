package net.asksakis.massdroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.session.MediaSessionCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle

/**
 * Foreground service that manages media playback notification with MediaStyle.
 *
 * Features:
 * - MediaStyle notification with album artwork
 * - Play/Pause/Next/Previous controls
 * - Integration with MediaSession for lock screen controls
 * - Dynamic updates when track or playback state changes
 * - Thread-safe notification updates
 */
class AudioService : Service() {
    companion object {
        private const val TAG = "AudioService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "music_assistant_playback"

        // Action constants for notification buttons
        private const val ACTION_PLAY_PAUSE = "net.asksakis.massdroid.ACTION_PLAY_PAUSE"
        private const val ACTION_PREVIOUS = "net.asksakis.massdroid.ACTION_PREVIOUS"
        private const val ACTION_NEXT = "net.asksakis.massdroid.ACTION_NEXT"
    }

    // Current metadata state
    private var currentTitle = "Music Assistant"
    private var currentArtist = "Ready to play"
    private var currentAlbum = ""
    private var currentArtworkUrl = ""
    @Volatile
    private var isPlaying = false

    // Lock object for synchronizing playback state modifications
    private val stateLock = Any()

    // Current artwork bitmap (set via setArtworkBitmap from MainActivity)
    private var currentArtwork: Bitmap? = null
    private lateinit var mainHandler: Handler

    // MediaSession for lock screen controls
    private var mediaSession: MediaSessionCompat? = null

    // Callback interface for media commands
    private var mediaControlCallback: MediaControlCallback? = null

    interface MediaControlCallback {
        fun onPlayPause()
        fun onNext()
        fun onPrevious()
    }

    inner class LocalBinder : Binder() {
        fun getService(): AudioService = this@AudioService
    }

    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "AudioService onCreate called")

        // Initialize handler for main thread operations
        mainHandler = Handler(Looper.getMainLooper())

        // Create notification channel
        createNotificationChannel()

        // Start foreground with initial notification
        // API 34+ requires explicit foreground service type
        try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            Log.d(TAG, "Foreground service started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground service", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "AudioService onStartCommand called - flags: $flags, startId: $startId")

        if (intent != null) {
            Log.d(TAG, "Intent details: action=${intent.action}, component=${intent.component}, flags=0x${Integer.toHexString(intent.flags)}")
        } else {
            Log.w(TAG, "onStartCommand received NULL intent")
        }

        // Handle notification action intents
        if (intent?.action != null) {
            val action = intent.action
            Log.i(TAG, "========================================")
            Log.i(TAG, "onStartCommand received action: $action")
            Log.i(TAG, "Timestamp: ${System.currentTimeMillis()}")
            Log.i(TAG, "Android SDK: ${Build.VERSION.SDK_INT}")
            Log.i(TAG, "========================================")

            when (action) {
                ACTION_PLAY_PAUSE -> {
                    Log.i(TAG, "Routing to handlePlayPause()...")
                    handlePlayPause()
                }
                ACTION_PREVIOUS -> {
                    Log.i(TAG, "Routing to handlePrevious()...")
                    handlePrevious()
                }
                ACTION_NEXT -> {
                    Log.i(TAG, "Routing to handleNext()...")
                    handleNext()
                }
                else -> Log.w(TAG, "Unrecognized action: $action")
            }
        } else {
            Log.d(TAG, "onStartCommand: null intent or null action (this is normal for initial service start)")
        }

        return START_STICKY // Restart service if killed by system
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "AudioService onBind called")
        return binder
    }

    /**
     * Set the MediaSession for lock screen control integration
     */
    fun setMediaSession(mediaSession: MediaSessionCompat) {
        this.mediaSession = mediaSession
        Log.d(TAG, "MediaSession attached to AudioService")
        updateNotification()
    }

    /**
     * Set the callback for media control commands from notification
     */
    fun setMediaControlCallback(callback: MediaControlCallback?) {
        this.mediaControlCallback = callback
        Log.d(TAG, "MediaControlCallback set")
    }

    /**
     * Update notification with new track metadata
     */
    fun updateMetadata(title: String, artist: String, album: String, artworkUrl: String?) {
        Log.d(TAG, "Updating metadata: $title - $artist")

        currentTitle = title
        currentArtist = artist
        currentAlbum = album
        currentArtworkUrl = artworkUrl ?: ""

        // Update notification (artwork will be set separately via setArtworkBitmap)
        updateNotification()
    }

    /**
     * Set artwork bitmap directly (called from MainActivity when artwork is fetched via JavaScript)
     * Note: We don't manually recycle bitmaps as it causes race conditions with async notification updates.
     * The garbage collector handles bitmap memory efficiently on modern Android.
     */
    fun setArtworkBitmap(bitmap: Bitmap) {
        Log.d(TAG, "setArtworkBitmap called")
        currentArtwork = bitmap
        updateNotification()
    }

    /**
     * Update notification with new playback state
     */
    fun updatePlaybackState(playing: Boolean) {
        Log.d(TAG, "========================================")
        Log.d(TAG, "updatePlaybackState() called")
        Log.d(TAG, "Current state: isPlaying=$isPlaying")
        Log.d(TAG, "New state: playing=$playing")
        Log.d(TAG, "State changed: ${isPlaying != playing}")
        Log.d(TAG, "========================================")

        synchronized(stateLock) {
            Log.i(TAG, "PLAYBACK STATE UPDATE: ${if (isPlaying) "playing" else "paused"} -> ${if (playing) "playing" else "paused"}")
            isPlaying = playing
            updateNotification()
            Log.i(TAG, "Notification forcefully updated with new state")
        }
    }

    /**
     * Create notification channel for Android O+
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Music Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows currently playing track with playback controls"
                setShowBadge(false)
                setSound(null, null)
                enableLights(false)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
            Log.d(TAG, "Notification channel created")
        }
    }

    /**
     * Handle play/pause action from notification
     */
    private fun handlePlayPause() {
        Log.i(TAG, "========================================")
        Log.i(TAG, "handlePlayPause() called")
        Log.i(TAG, "Current state: isPlaying=$isPlaying")
        Log.i(TAG, "Callback available: ${mediaControlCallback != null}")
        Log.i(TAG, "========================================")

        mediaControlCallback?.let {
            Log.i(TAG, "Invoking mediaControlCallback.onPlayPause()...")
            it.onPlayPause()
            Log.i(TAG, "Callback invocation completed")
        } ?: run {
            Log.e(TAG, "CRITICAL: MediaControlCallback is NULL - cannot execute play/pause!")
            Log.e(TAG, "This means MainActivity didn't set the callback properly")
        }

        synchronized(stateLock) {
            // Toggle state optimistically for immediate UI feedback
            isPlaying = !isPlaying
            Log.i(TAG, "State toggled to: isPlaying=$isPlaying")
            Log.i(TAG, "Updating notification...")
            updateNotification()
            Log.i(TAG, "Notification update completed")
        }
    }

    /**
     * Handle previous track action from notification
     */
    private fun handlePrevious() {
        Log.i(TAG, "========================================")
        Log.i(TAG, "handlePrevious() called")
        Log.i(TAG, "Callback available: ${mediaControlCallback != null}")
        Log.i(TAG, "========================================")

        mediaControlCallback?.let {
            Log.i(TAG, "Invoking mediaControlCallback.onPrevious()...")
            it.onPrevious()
            Log.i(TAG, "Callback invocation completed")
        } ?: Log.e(TAG, "CRITICAL: MediaControlCallback is NULL - cannot execute previous!")
    }

    /**
     * Handle next track action from notification
     */
    private fun handleNext() {
        Log.i(TAG, "========================================")
        Log.i(TAG, "handleNext() called")
        Log.i(TAG, "Callback available: ${mediaControlCallback != null}")
        Log.i(TAG, "========================================")

        mediaControlCallback?.let {
            Log.i(TAG, "Invoking mediaControlCallback.onNext()...")
            it.onNext()
            Log.i(TAG, "Callback invocation completed")
        } ?: Log.e(TAG, "CRITICAL: MediaControlCallback is NULL - cannot execute next!")
    }

    /**
     * Update the notification with current state
     */
    private fun updateNotification() {
        val notification = buildNotification()

        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, notification)
        Log.d(TAG, "Notification updated")
    }

    /**
     * Build notification with MediaStyle
     */
    private fun buildNotification(): Notification {
        // Create intent to open app when notification is tapped
        // Use SINGLE_TOP to bring existing activity to front without recreating it
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        var pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingIntentFlags = pendingIntentFlags or PendingIntent.FLAG_IMMUTABLE
        }

        val contentIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, pendingIntentFlags
        )

        // Create PendingIntents for action buttons
        val previousIntent = createActionIntent(ACTION_PREVIOUS, 1)
        val playPauseIntent = createActionIntent(ACTION_PLAY_PAUSE, 2)
        val nextIntent = createActionIntent(ACTION_NEXT, 3)

        // Build notification with MediaStyle
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(currentTitle)
            .setContentText(currentArtist)
            .setSubText(currentAlbum)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(contentIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(true)
            .setAutoCancel(false)

        // Add large icon (album artwork)
        currentArtwork?.let {
            builder.setLargeIcon(it)
        }

        // Add action buttons
        builder.addAction(R.drawable.ic_skip_previous, "Previous", previousIntent)

        // Play/Pause button icon selection based on isPlaying state
        val playPauseIcon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        val playPauseLabel = if (isPlaying) "Pause" else "Play"
        Log.d(TAG, "buildNotification: isPlaying=$isPlaying, icon=${if (isPlaying) "ic_pause" else "ic_play"}, label=$playPauseLabel")

        builder.addAction(playPauseIcon, playPauseLabel, playPauseIntent)
        builder.addAction(R.drawable.ic_skip_next, "Next", nextIntent)

        // Apply MediaStyle
        val mediaStyle = MediaStyle()
            .setShowActionsInCompactView(0, 1, 2) // Show all 3 actions in compact view

        // Set MediaSession token if available
        mediaSession?.let {
            mediaStyle.setMediaSession(it.sessionToken)
        }

        builder.setStyle(mediaStyle)

        // For Android 12+ (API 31+), set foreground service behavior
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }

        return builder.build()
    }

    /**
     * Create PendingIntent for notification action
     */
    private fun createActionIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, AudioService::class.java).apply {
            this.action = action
        }

        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }

        Log.d(TAG, "Creating PendingIntent for action: $action, requestCode: $requestCode, flags: $flags, SDK_INT: ${Build.VERSION.SDK_INT}")

        val pendingIntent = PendingIntent.getService(this, requestCode, intent, flags)

        Log.d(TAG, "PendingIntent created successfully: ${pendingIntent != null}")

        return pendingIntent
    }

    override fun onDestroy() {
        Log.d(TAG, "AudioService onDestroy called")
        currentArtwork = null
        super.onDestroy()
    }
}
