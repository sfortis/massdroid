package net.asksakis.massdroid

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.ComponentName
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.webkit.JavascriptInterface
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.security.KeyChain
import android.security.KeyChainAliasCallback
import android.webkit.ClientCertRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import java.lang.ref.WeakReference
import java.security.PrivateKey
import java.util.concurrent.atomic.AtomicBoolean
import java.security.cert.X509Certificate
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.Keep
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.navigation.NavigationView
import com.google.android.material.progressindicator.LinearProgressIndicator
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.asksakis.massdroid.R

class MainActivity : AppCompatActivity(),
    NavigationView.OnNavigationItemSelectedListener,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView
    private lateinit var toolbar: MaterialToolbar
    internal lateinit var webView: WebView
    private lateinit var progressBar: LinearProgressIndicator
    internal lateinit var preferencesHelper: PreferencesHelper

    // Media components (internal for WeakReference access from MediaMetadataInterface)
    internal var mediaSession: MediaSessionCompat? = null
    internal var audioService: AudioService? = null
    internal var audioServiceBound = false
    // AtomicBoolean for thread-safe check-then-act pattern in startAudioService()
    private val isStartingService = AtomicBoolean(false)
    internal val handler = Handler(Looper.getMainLooper())

    // Audio focus handling (pause on phone call, etc.)
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    @Volatile
    internal var pausedDueToFocusLoss = false  // Track if we paused due to losing focus
    @Volatile
    internal var ignoreFocusEvents = false  // Temporarily ignore focus events during startup

    // Phone call detection (backup for audio focus)
    private lateinit var telephonyManager: TelephonyManager
    private var telephonyCallback: TelephonyCallback? = null  // Android 12+
    @Suppress("DEPRECATION")
    private var phoneStateListener: android.telephony.PhoneStateListener? = null  // Pre-Android 12
    @Volatile
    private var pausedDueToPhoneCall = false

    // Position state tracking (internal for WeakReference access from MediaMetadataInterface)
    internal var currentDurationMs: Long = 0
    internal var currentPositionMs: Long = 0
    internal var currentPlaybackRate: Float = 1.0f
    @Volatile
    internal var isCurrentlyPlaying = false  // Track playback state ourselves

    // Bluetooth auto-play
    private var bluetoothReceiver: BluetoothAutoPlayReceiver? = null
    @Volatile
    internal var webViewReady = false

    // Auto-resume after page reload
    @Volatile
    internal var pendingAutoPlayAfterReload = false

    // Back navigation callbacks (OnBackPressedDispatcher)
    // Drawer callback: enabled when drawer is open
    private val drawerBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            drawerLayout.closeDrawer(GravityCompat.START)
        }
    }
    // WebView callback: enabled when WebView can go back (updated in doUpdateVisitedHistory)
    private val webViewBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            webView.goBack()
        }
    }

    // Network change monitoring
    private var networkMonitor: NetworkChangeMonitor? = null

    // Client certificate alias for mTLS
    private var clientCertAlias: String? = null

    // Selected player tracking (for multi-room speaker control)
    internal var selectedPlayerId: String? = null
    internal var selectedPlayerName: String? = null

    // Track URL for detecting changes after settings
    private var urlBeforeSettings: String = ""
    private var colorBeforePause: String = ""
    @Volatile
    internal var wasPlayingBeforeNetworkLoss = false
    internal var savedPositionMs: Long = 0  // Position saved at moment of network loss
    internal var savedDurationMs: Long = 0  // Duration saved at moment of network loss
    internal var currentTrackTitle: String = ""  // Current track title for verification
    internal var savedTrackTitle: String = ""  // Track title saved at moment of network loss
    @Volatile
    internal var waitingForStreamStart = false  // True while waiting for auto-resume to complete

    // Timeout runnable for auto-resume (so we can cancel it)
    internal var autoResumeTimeoutRunnable: Runnable? = null

    // Auto-resume retry tracking
    internal var autoResumeRetryCount = 0
    private val MAX_AUTO_RESUME_RETRIES = 5  // Each retry reloads WebView
    @Volatile
    internal var primaryAutoResumeActive = false  // Set by onSendspinStabilized to stop fallback

    // Use lifecycleScope for automatic cancellation when Activity is destroyed
    // This property provides backwards compatibility for existing code
    internal val backgroundScope get() = lifecycleScope

    // Track current artwork bitmap to recycle on replacement
    internal var currentArtworkBitmap: Bitmap? = null

    // Pending notification state (for when service isn't bound yet)
    internal var pendingTitle: String? = null
    internal var pendingArtist: String? = null
    internal var pendingAlbum: String? = null
    internal var pendingArtwork: Bitmap? = null
    internal var pendingIsPlaying: Boolean? = null

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSIONS_REQUEST = 100  // Combined permissions request
    }

    // AudioService connection
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as AudioService.LocalBinder
            audioService = binder.getService()
            audioServiceBound = true
            isStartingService.set(false)  // Reset flag now that service is connected

            Log.d(TAG, "AudioService connected and bound")

            // Pass MediaSession to service
            mediaSession?.let {
                audioService?.setMediaSession(it)
            }

            // Set callback for notification actions - forward to WebView's SendSpin
            audioService?.setMediaControlCallback(object : AudioService.MediaControlCallback {
                override fun onPlayPause() {
                    Log.i(TAG, "Notification Play/Pause pressed")
                    executeMediaCommand("playPause")
                }

                override fun onNext() {
                    Log.i(TAG, "Notification Next pressed")
                    executeMediaCommand("next")
                }

                override fun onPrevious() {
                    Log.i(TAG, "Notification Previous pressed")
                    executeMediaCommand("previous")
                }
            })

            // Replay any pending notification updates that arrived before binding
            replayPendingNotificationState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            audioService = null
            audioServiceBound = false
            isStartingService.set(false)  // Reset flag if service disconnects unexpectedly
            Log.d(TAG, "AudioService disconnected")
        }
    }

    /**
     * Audio focus change listener - handles phone calls, other media apps, etc.
     * When another app requests audio focus (e.g., phone call), we pause playback.
     */
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        Log.i(TAG, "Audio focus changed: $focusChange, ignoreFocusEvents=$ignoreFocusEvents")

        // Ignore focus events during playback startup to avoid race conditions
        if (ignoreFocusEvents) {
            Log.i(TAG, "Ignoring focus event during startup grace period")
            return@OnAudioFocusChangeListener
        }

        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Permanent loss - another app took over (e.g., opened Spotify)
                Log.i(TAG, "Audio focus LOST permanently - pausing playback")
                hasAudioFocus = false
                pausedDueToFocusLoss = true
                executeMediaCommand("pause")
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Temporary loss - phone call, navigation announcement, etc.
                Log.i(TAG, "Audio focus LOST transiently (phone call?) - pausing playback")
                hasAudioFocus = false
                if (isCurrentlyPlaying) {
                    pausedDueToFocusLoss = true
                    executeMediaCommand("pause")
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Can duck (lower volume) - notifications, navigation, etc.
                // Don't pause for duck events - just let the system lower volume
                Log.i(TAG, "Audio focus CAN_DUCK - ignoring (letting system handle volume)")
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                // Focus regained - phone call ended, etc.
                Log.i(TAG, "Audio focus GAINED")
                hasAudioFocus = true
                if (pausedDueToFocusLoss) {
                    Log.i(TAG, "Resuming playback after focus regained")
                    pausedDueToFocusLoss = false
                    executeMediaCommand("play")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeHelper.applyTheme(this)
        setContentView(R.layout.activity_main)

        // Initialize preferences helper
        preferencesHelper = PreferencesHelper(this)

        // Restore saved client certificate alias
        clientCertAlias = preferencesHelper.clientCertAlias

        // Initialize audio manager for audio focus handling
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Initialize telephony manager for phone call detection
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        // Setup views
        setupViews()
        setupToolbar()
        setupNavigationDrawer()
        setupWebView()

        // Register back navigation callbacks (order matters: last registered = first checked)
        // WebView callback first (lower priority), then drawer callback (higher priority)
        onBackPressedDispatcher.addCallback(this, webViewBackCallback)
        onBackPressedDispatcher.addCallback(this, drawerBackCallback)

        // Request all required permissions at once (avoids race condition with multiple dialogs)
        requestRequiredPermissions()

        // Setup media components
        setupMediaSession()
        startAudioService()

        // Apply settings
        applyKeepScreenOnSetting()

        // Setup network change monitor
        setupNetworkMonitor()

        // Restore WebView state if available, otherwise load fresh
        if (savedInstanceState != null) {
            // Check if URL has changed since state was saved
            val savedUrl = savedInstanceState.getString("saved_pwa_url")
            val currentUrl = preferencesHelper.pwaUrl

            if (savedUrl == currentUrl) {
                Log.i(TAG, "Restoring WebView state from savedInstanceState")
                webView.restoreState(savedInstanceState)
            } else {
                Log.i(TAG, "URL changed ($savedUrl -> $currentUrl), loading fresh instead of restoring")
                loadPwaUrl()
            }
        } else {
            Log.i(TAG, "No saved state, loading fresh URL")
            loadPwaUrl()
        }

        // Check for updates on every app launch
        checkForAppUpdates()
    }

    /**
     * Check for app updates from GitHub releases.
     * Always checks on app launch to ensure user sees available updates.
     */
    private fun checkForAppUpdates() {
        val updateChecker = UpdateChecker(this)
        lifecycleScope.launch {
            val updateInfo = updateChecker.checkForUpdates(force = true)
            updateInfo?.let {
                updateChecker.showUpdateDialog(this@MainActivity, it)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        Log.i(TAG, "Saving WebView state")
        // Save current URL to detect changes on restore
        outState.putString("saved_pwa_url", preferencesHelper.pwaUrl)
        webView.saveState(outState)
    }

    private fun setupNetworkMonitor() {
        networkMonitor = NetworkChangeMonitor(this, object : NetworkChangeMonitor.NetworkChangeListener {
            override fun onNetworkLost() {
                Log.i(TAG, "========================================")
                Log.i(TAG, "Network lost, isCurrentlyPlaying=$isCurrentlyPlaying")
                Log.i(TAG, "========================================")

                // Save state for potential auto-resume
                if (isCurrentlyPlaying && preferencesHelper.autoResumeOnNetwork) {
                    wasPlayingBeforeNetworkLoss = true
                    savedPositionMs = currentPositionMs
                    savedDurationMs = currentDurationMs
                    savedTrackTitle = currentTrackTitle
                    Log.i(TAG, "Saved state for auto-resume: position=${savedPositionMs}ms, duration=${savedDurationMs}ms, track=$savedTrackTitle")
                }

                // Force close WebSockets to ensure clean reconnection when network returns
                // This prevents "zombie" connections that may not trigger proper events
                runOnUiThread {
                    Log.i(TAG, "Forcing WebSocket close for clean reconnection...")
                    webView.evaluateJavascript("""
                        (function() {
                            console.log('[NetworkLost] Forcing WebSocket close');
                            // Close SendSpin WebSocket via exposed function
                            if (window.closeSendspinSocket) {
                                window.closeSendspinSocket();
                            }
                            // Close MaWebSocket if available
                            if (window.MaWebSocket && window.MaWebSocket.close) {
                                window.MaWebSocket.close();
                            }
                            return 'closed';
                        })();
                    """.trimIndent()) { result ->
                        Log.i(TAG, "WebSocket close result: $result")
                    }
                }
            }

            override fun onNetworkAvailable() {
                Log.i(TAG, "========================================")
                Log.i(TAG, "Network available")
                Log.i(TAG, "wasPlayingBeforeNetworkLoss=$wasPlayingBeforeNetworkLoss")
                Log.i(TAG, "========================================")

                // Primary: onSendspinStabilized() handles auto-resume when WebSocket reconnects
                // Fallback: If WebSocket didn't close (TCP survived), trigger a manual check
                if (wasPlayingBeforeNetworkLoss && preferencesHelper.autoResumeOnNetwork) {
                    Log.i(TAG, "Scheduling fallback auto-resume check in 5 seconds...")
                    handler.postDelayed({
                        // Only trigger fallback if wasPlayingBeforeNetworkLoss is still true
                        // (it gets cleared by onSendspinStabilized if that path worked)
                        if (wasPlayingBeforeNetworkLoss && !waitingForStreamStart) {
                            Log.i(TAG, "Fallback auto-resume: WebSocket didn't reconnect, forcing check...")
                            triggerFallbackAutoResume()
                        } else {
                            Log.d(TAG, "Fallback auto-resume not needed - already handled or in progress")
                        }
                    }, 5000)  // Wait 5s for normal WebSocket-based stabilization
                }
            }
        })

        networkMonitor?.start()
    }

    /**
     * Fallback auto-resume when network is restored but WebSocket didn't reconnect.
     * This handles the case where TCP connection survived the network change.
     * Waits for SendSpin to be connected before sending play command.
     */
    private fun triggerFallbackAutoResume() {
        Log.i(TAG, "========================================")
        Log.i(TAG, "Fallback auto-resume triggered")
        Log.i(TAG, "========================================")

        // Cancel any existing timeout from previous attempts
        autoResumeTimeoutRunnable?.let { handler.removeCallbacks(it) }
        autoResumeTimeoutRunnable = null

        // Mark that we're attempting auto-resume
        waitingForStreamStart = true

        runOnUiThread {
            Toast.makeText(this, "Waiting for connection...", Toast.LENGTH_SHORT).show()

            // Wait for SendSpin to be connected before resuming
            waitForSendspinAndResume(0)
        }
    }

    /**
     * Polls for SendSpin connection and resumes when ready.
     * @param attempts Number of attempts so far
     */
    private fun waitForSendspinAndResume(attempts: Int) {
        val maxAttempts = 15  // Try for 15 seconds (1s intervals)

        if (!waitingForStreamStart) {
            Log.d(TAG, "waitForSendspinAndResume: waitingForStreamStart is false, aborting")
            return
        }

        // Abort if primary path (onSendspinStabilized) has taken over
        if (primaryAutoResumeActive) {
            Log.d(TAG, "waitForSendspinAndResume: primary path active, stopping fallback")
            return
        }

        webView.evaluateJavascript("""
            (function() {
                const ssConnected = window.isSendspinConnected ? window.isSendspinConnected() : false;
                const ssStabilized = window.isSendspinStabilized ? window.isSendspinStabilized() : false;
                const maConnected = window.MaWebSocket && window.MaWebSocket.isConnected();
                return JSON.stringify({ssConnected: ssConnected, ssStabilized: ssStabilized, maConnected: maConnected});
            })();
        """.trimIndent()) { result ->
            Log.i(TAG, "Fallback check #$attempts - connection status: $result")

            // Parse JSON result
            val ssConnected = result.contains("\"ssConnected\":true")
            val maConnected = result.contains("\"maConnected\":true")

            when {
                ssConnected && maConnected -> {
                    // SendSpin is ready, wait 3 more seconds for stability then resume
                    Log.i(TAG, "SendSpin connected! Waiting 3s for stability...")
                    Toast.makeText(this, "Connection ready, resuming...", Toast.LENGTH_SHORT).show()
                    handler.postDelayed({
                        if (waitingForStreamStart) {
                            Log.i(TAG, "Stability delay complete, proceeding with auto-resume")
                            autoResumeRetryCount = 0  // Reset retry counter before first attempt
                            performAutoResumeStopPlay()
                        }
                    }, 3000)  // 3 second safety delay
                }
                attempts >= maxAttempts -> {
                    // Timeout - try anyway if MaWebSocket is connected
                    if (maConnected) {
                        Log.w(TAG, "SendSpin not connected after ${maxAttempts}s, trying resume anyway...")
                        Toast.makeText(this, "Auto-resuming...", Toast.LENGTH_SHORT).show()
                        performAutoResumeStopPlay()
                    } else {
                        Log.w(TAG, "Fallback auto-resume timed out - no connection")
                        waitingForStreamStart = false
                        wasPlayingBeforeNetworkLoss = false
                        Toast.makeText(this, "Could not auto-resume - no connection", Toast.LENGTH_SHORT).show()
                    }
                }
                else -> {
                    // Not ready yet, try again in 1 second
                    handler.postDelayed({
                        waitForSendspinAndResume(attempts + 1)
                    }, 1000)
                }
            }
        }
    }

    /**
     * Performs the actual stop/play sequence for auto-resume.
     */
    private fun performAutoResumeStopPlay() {
        Log.i(TAG, "Fallback: Sending stop command...")
        webView.evaluateJavascript("""
            (function() {
                if (window.MaWebSocket && window.MaWebSocket.isConnected()) {
                    console.log('[FallbackResume] Sending stop command');
                    window.MaWebSocket.stop();
                    return 'stop_sent';
                }
                return 'not_connected';
            })();
        """.trimIndent()) { stopResult ->
            Log.i(TAG, "Fallback stop result: $stopResult")

            handler.postDelayed({
                Log.i(TAG, "Fallback: Sending play command...")
                webView.evaluateJavascript("""
                    (function() {
                        if (window.MaWebSocket && window.MaWebSocket.isConnected()) {
                            console.log('[FallbackResume] Sending play command');
                            window.MaWebSocket.play();
                            return 'play_sent';
                        }
                        return 'not_connected';
                    })();
                """.trimIndent()) { playResult ->
                    Log.i(TAG, "Fallback play result: $playResult")

                    // Seek to saved position after play starts (only if same track)
                    val savedPosSec = savedPositionMs / 1000
                    if (savedPosSec > 5 && savedTrackTitle.isNotEmpty()) {
                        handler.postDelayed({
                            // Verify track hasn't changed before seeking
                            if (currentTrackTitle == savedTrackTitle) {
                                Log.i(TAG, "Fallback: Same track confirmed, seeking to saved position: ${savedPosSec}s")
                                webView.evaluateJavascript("""
                                    (function() {
                                        if (window.MaWebSocket && window.MaWebSocket.isConnected()) {
                                            console.log('[FallbackResume] Seeking to $savedPosSec seconds');
                                            window.MaWebSocket.seek($savedPosSec);
                                            return 'seek_sent';
                                        }
                                        return 'not_connected';
                                    })();
                                """.trimIndent()) { seekResult ->
                                    Log.i(TAG, "Fallback seek result: $seekResult")
                                }
                            } else {
                                Log.i(TAG, "Fallback: Track changed (was: $savedTrackTitle, now: $currentTrackTitle) - skipping seek")
                            }
                        }, 1000)  // Wait 1s for play to start before seeking
                    }
                }
            }, 500)  // Wait 500ms after stop before play
        }

        // Set timeout for stream/start detection - reload WebView on each retry
        autoResumeTimeoutRunnable = Runnable {
            if (waitingForStreamStart) {
                autoResumeRetryCount++
                Log.w(TAG, "Fallback auto-resume timed out - no stream/start (attempt $autoResumeRetryCount/$MAX_AUTO_RESUME_RETRIES)")

                if (autoResumeRetryCount < MAX_AUTO_RESUME_RETRIES) {
                    // Retry by reloading WebView
                    Log.i(TAG, "Reloading WebView for retry...")
                    runOnUiThread {
                        Toast.makeText(this, "Reloading... ($autoResumeRetryCount/$MAX_AUTO_RESUME_RETRIES)", Toast.LENGTH_SHORT).show()
                        pendingAutoPlayAfterReload = true
                        webView.reload()
                    }
                    // Keep waitingForStreamStart active for next attempt
                    // primaryAutoResumeActive will be reset after reload triggers new stabilization
                    primaryAutoResumeActive = false
                } else {
                    // All retries failed
                    Log.w(TAG, "All $MAX_AUTO_RESUME_RETRIES retries failed - giving up")
                    runOnUiThread {
                        Toast.makeText(this, "Could not resume playback", Toast.LENGTH_LONG).show()
                    }
                    waitingForStreamStart = false
                    wasPlayingBeforeNetworkLoss = false
                    autoResumeRetryCount = 0
                    primaryAutoResumeActive = false
                }
            }
        }
        handler.postDelayed(autoResumeTimeoutRunnable!!, 5000)
    }

    /**
     * Request all required runtime permissions at once.
     * This avoids race conditions where multiple permission dialogs would interfere.
     */
    private fun requestRequiredPermissions() {
        val permissionsNeeded = mutableListOf<String>()

        // Android 12+ (API 31): Bluetooth permission for auto-play on connect
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }

        // Android 13+ (API 33): Notification permission for foreground service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Phone state permission for pausing during calls
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.READ_PHONE_STATE)
        }

        if (permissionsNeeded.isNotEmpty()) {
            Log.i(TAG, "Requesting permissions: ${permissionsNeeded.joinToString()}")
            ActivityCompat.requestPermissions(
                this,
                permissionsNeeded.toTypedArray(),
                PERMISSIONS_REQUEST
            )
        } else {
            Log.d(TAG, "All permissions already granted")
            setupPermissionDependentFeatures()
        }
    }

    /**
     * Setup features that depend on runtime permissions.
     * Called after permissions are granted.
     */
    private fun setupPermissionDependentFeatures() {
        // Setup Bluetooth auto-play (needs BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED) {
            registerBluetoothReceiver()
        }

        // Setup phone call listener (needs READ_PHONE_STATE)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            == PackageManager.PERMISSION_GRANTED) {
            registerPhoneCallListener()
        }
    }

    private fun registerBluetoothReceiver() {
        if (bluetoothReceiver != null) return

        bluetoothReceiver = BluetoothAutoPlayReceiver(
            onBluetoothAudioConnected = { deviceName ->
                Log.i(TAG, "Bluetooth device connected: $deviceName")

                // Check if auto-play is enabled
                if (!preferencesHelper.autoPlayOnBluetooth) {
                    Log.d(TAG, "Auto-play on Bluetooth is disabled")
                    return@BluetoothAutoPlayReceiver
                }

                // Check if WebView is ready
                if (!webViewReady) {
                    Log.d(TAG, "WebView not ready yet, skipping auto-play")
                    return@BluetoothAutoPlayReceiver
                }

                // Wait for Bluetooth audio to be ready before playing
                waitForBluetoothAudioAndPlay(deviceName)
            },
            onBluetoothAudioDisconnected = { deviceName ->
                Log.i(TAG, "Bluetooth device disconnected: $deviceName")

                // Only stop if currently playing
                if (!isCurrentlyPlaying) {
                    Log.d(TAG, "Not playing, no need to stop on BT disconnect")
                    return@BluetoothAutoPlayReceiver
                }

                // Check if WebView is ready
                if (!webViewReady) {
                    Log.d(TAG, "WebView not ready, can't stop playback")
                    return@BluetoothAutoPlayReceiver
                }

                // Only stop if phone speaker is selected (don't stop external speakers like Sonos)
                checkIfPhoneIsActivePlayer { isPhoneSelected ->
                    if (isPhoneSelected) {
                        Log.i(TAG, "Phone selected, stopping playback due to Bluetooth disconnect")
                        runOnUiThread {
                            Toast.makeText(this, "Bluetooth disconnected - stopping playback", Toast.LENGTH_SHORT).show()
                            webView.evaluateJavascript("""
                                (function() {
                                    if (window.MaWebSocket && window.MaWebSocket.isConnected()) {
                                        console.log('[BluetoothDisconnect] Sending stop command');
                                        window.MaWebSocket.stop();
                                        return 'stopped';
                                    }
                                    return 'not_connected';
                                })();
                            """.trimIndent()) { result ->
                                Log.i(TAG, "BT disconnect stop result: $result")
                            }
                        }
                    } else {
                        Log.i(TAG, "Phone not selected - ignoring BT disconnect (external speaker in use)")
                    }
                }
            }
        )

        try {
            registerReceiver(bluetoothReceiver, BluetoothAutoPlayReceiver.getIntentFilter())
            Log.i(TAG, "Bluetooth auto-play receiver registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register Bluetooth receiver", e)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSIONS_REQUEST) {
            // Log results for each permission
            permissions.forEachIndexed { index, permission ->
                val granted = grantResults.getOrNull(index) == PackageManager.PERMISSION_GRANTED
                val shortName = permission.substringAfterLast('.')
                if (granted) {
                    Log.i(TAG, "Permission granted: $shortName")
                } else {
                    Log.w(TAG, "Permission denied: $shortName")
                }
            }

            // Setup features based on granted permissions
            setupPermissionDependentFeatures()
        }
    }

    private fun setupViews() {
        drawerLayout = findViewById(R.id.drawer_layout)
        navigationView = findViewById(R.id.navigation_view)
        toolbar = findViewById(R.id.toolbar)
        webView = findViewById(R.id.webview)
        progressBar = findViewById(R.id.progress_bar)
    }

    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeButtonEnabled(true)
    }

    private fun setupNavigationDrawer() {
        val toggle = ActionBarDrawerToggle(
            this,
            drawerLayout,
            toolbar,
            R.string.navigation_drawer_open,
            R.string.navigation_drawer_close
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        // Update back callback when drawer state changes
        drawerLayout.addDrawerListener(object : DrawerLayout.DrawerListener {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {}
            override fun onDrawerOpened(drawerView: View) {
                drawerBackCallback.isEnabled = true
            }
            override fun onDrawerClosed(drawerView: View) {
                drawerBackCallback.isEnabled = false
            }
            override fun onDrawerStateChanged(newState: Int) {}
        })

        navigationView.setNavigationItemSelectedListener(this)

        // Update header with current URL
        updateDrawerHeader()
    }

    private fun updateDrawerHeader() {
        val headerView = navigationView.getHeaderView(0)
        val subtitleView = headerView?.findViewById<android.widget.TextView>(R.id.nav_header_subtitle)
        subtitleView?.text = preferencesHelper.pwaUrl.ifEmpty { "Not configured" }
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.nav_home -> {
                loadPwaUrl()
            }
            R.id.nav_refresh -> {
                loadPwaUrl()
            }
            R.id.nav_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
            }
        }
        drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            // CRITICAL: Allow audio playback without user gesture (needed for auto-resume after reload)
            mediaPlaybackRequiresUserGesture = false
            // Custom User-Agent to bypass Google OAuth "disallowed_useragent" error
            // when using Cloudflare Access with Google authentication
            userAgentString = "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; ${Build.MODEL}) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        }

        // Add JavaScript interface for media metadata
        // Use WeakReference to avoid memory leaks if Activity is destroyed while WebView holds reference
        webView.addJavascriptInterface(MediaMetadataInterface(WeakReference(this)), "AndroidMediaSession")
        Log.d(TAG, "JavaScript interface 'AndroidMediaSession' registered")

        // WebViewClient for handling page navigation
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false

                // Allow authentication pages in WebView (Cloudflare, Google OAuth, etc.)
                if (isAuthPage(url)) {
                    Log.d(TAG, "Allowing auth page in WebView: $url")
                    return false // Load in WebView
                }

                // Get configured Music Assistant host
                val configuredUrl = preferencesHelper.pwaUrl
                val allowedHost = try {
                    Uri.parse(configuredUrl).host?.lowercase() ?: ""
                } catch (e: Exception) {
                    ""
                }

                // Get requested URL host
                val requestedHost = try {
                    Uri.parse(url).host?.lowercase() ?: ""
                } catch (e: Exception) {
                    ""
                }

                // Allow if same host or subdomain
                if (requestedHost == allowedHost ||
                    requestedHost.endsWith(".$allowedHost") ||
                    allowedHost.endsWith(".$requestedHost")) {
                    return false // Load in WebView
                }

                // Open external URLs in browser
                Log.d(TAG, "Opening external URL in browser: $url")
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open external URL", e)
                }
                return true // Don't load in WebView
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // Inject MediaSession interceptor when page starts loading
                injectMediaSessionPolyfill()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                webViewReady = true
                Log.d(TAG, "Page finished loading: $url")
                // MediaSession interceptor already injected in onPageStarted
                // WebView's SendSpin handles audio, interceptor forwards metadata to Android

                // Validate this is a Music Assistant server (skip for auth pages)
                if (url != null && !isAuthPage(url)) {
                    validateMusicAssistantServer()

                    // Query current playback state after delay (wait for Vue app to initialize)
                    Handler(Looper.getMainLooper()).postDelayed({
                        queryCurrentPlaybackState()
                    }, 3000)

                    // AUTO-RESUME DISABLED FOR DEBUGGING
                    // if (pendingAutoPlayAfterReload) { ... }
                }
            }

            override fun onReceivedClientCertRequest(view: WebView?, request: ClientCertRequest?) {
                Log.i(TAG, "Client certificate requested by ${request?.host}")

                // If we have a saved alias, use it
                if (clientCertAlias != null) {
                    provideClientCertificate(request, clientCertAlias!!)
                    return
                }

                // Prompt user to select certificate
                KeyChain.choosePrivateKeyAlias(
                    this@MainActivity,
                    { alias ->
                        if (alias != null) {
                            Log.i(TAG, "User selected certificate: $alias")
                            clientCertAlias = alias
                            preferencesHelper.clientCertAlias = alias  // Persist for next launch
                            provideClientCertificate(request, alias)
                        } else {
                            Log.w(TAG, "No certificate selected")
                            request?.cancel()
                        }
                    },
                    request?.keyTypes,
                    request?.principals,
                    request?.host,
                    request?.port ?: -1,
                    null
                )
            }

            // Handle SSL errors - clear saved certificate if handshake fails
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: android.net.http.SslError?) {
                Log.e(TAG, "SSL error: ${error?.primaryError} - ${error?.url}")

                // If we have a saved certificate and SSL fails, it might be expired/invalid
                if (clientCertAlias != null) {
                    Log.w(TAG, "SSL error with saved certificate - clearing alias and retrying")
                    clearSavedCertificateAlias()

                    // Reload the page to trigger new certificate prompt
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Certificate error - please select again", Toast.LENGTH_SHORT).show()
                        view?.reload()
                    }
                } else {
                    // No saved cert - cancel the request (don't proceed with invalid SSL)
                    handler?.cancel()
                }
            }

            // Update WebView back callback when navigation history changes
            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                webViewBackCallback.isEnabled = view?.canGoBack() == true
            }
        }

        // WebChromeClient for handling progress
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                if (newProgress < 100) {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = newProgress
                } else {
                    progressBar.visibility = View.GONE
                }
            }
        }
    }

    private fun provideClientCertificate(request: ClientCertRequest?, alias: String) {
        backgroundScope.launch(Dispatchers.IO) {
            try {
                val privateKey: PrivateKey? = KeyChain.getPrivateKey(this@MainActivity, alias)
                val certificateChain: Array<X509Certificate>? = KeyChain.getCertificateChain(this@MainActivity, alias)

                if (privateKey != null && certificateChain != null) {
                    Log.i(TAG, "Providing client certificate: $alias")
                    request?.proceed(privateKey, certificateChain)
                } else {
                    Log.e(TAG, "Failed to get certificate or private key - clearing saved alias")
                    clearSavedCertificateAlias()
                    request?.cancel()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error providing client certificate - clearing saved alias", e)
                clearSavedCertificateAlias()
                request?.cancel()
            }
        }
    }

    private fun clearSavedCertificateAlias() {
        clientCertAlias = null
        preferencesHelper.clientCertAlias = null
        Log.i(TAG, "Cleared saved certificate alias - will prompt on next request")
    }

    private fun loadPwaUrl() {
        if (!preferencesHelper.isUrlConfigured) {
            showSetupDialog()
            return
        }
        // Check battery optimization on every app start
        checkBatteryOptimization()
        // Reset validation when URL changes
        validationAttempted = false
        val url = preferencesHelper.pwaUrl
        webView.loadUrl(url)
    }

    private var validationAttempted = false

    private fun isAuthPage(url: String): Boolean {
        // Common auth/login page patterns
        val authPatterns = listOf(
            "cloudflareaccess.com",
            "access.cloudflare.com",
            "/cdn-cgi/access/",
            "accounts.google.com",
            "login.microsoftonline.com",
            "auth0.com",
            "okta.com",
            "/login",
            "/signin",
            "/oauth"
        )
        val lowercaseUrl = url.lowercase()
        return authPatterns.any { lowercaseUrl.contains(it) }
    }

    private fun validateMusicAssistantServer() {
        // Only validate once per session to avoid repeated warnings
        if (validationAttempted) return
        validationAttempted = true

        val validationScript = """
            (function() {
                // Check multiple indicators that this is Music Assistant
                var indicators = {
                    title: document.title.toLowerCase().includes('music assistant'),
                    appElement: !!document.querySelector('#app'),
                    vueApp: !!(document.querySelector('#app') && document.querySelector('#app').__vue_app__),
                    maApi: !!(document.querySelector('#app')?.__vue_app__?.config?.globalProperties?.${'$'}api),
                    maPlayer: !!(document.querySelector('#app')?.__vue_app__?.config?.globalProperties?.${'$'}api?.players)
                };

                console.log('[Validation] Music Assistant indicators:', JSON.stringify(indicators));

                // Valid if title matches OR if we have the Vue app with MA API
                var isValid = indicators.title || (indicators.vueApp && indicators.maApi);

                return JSON.stringify({ valid: isValid, indicators: indicators });
            })();
        """.trimIndent()

        // Delay validation to allow Vue app to fully initialize
        handler.postDelayed({
            webView.evaluateJavascript(validationScript) { result ->
                Log.d(TAG, "Validation result: $result")
                try {
                    // Parse the JSON result (it comes wrapped in quotes)
                    val jsonStr = result.trim('"').replace("\\\"", "\"").replace("\\\\", "\\")
                    val json = org.json.JSONObject(jsonStr)
                    val isValid = json.getBoolean("valid")

                    if (!isValid) {
                        runOnUiThread {
                            showInvalidServerWarning()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Validation parse error", e)
                    // Don't show warning on parse errors - could be auth redirect
                }
            }
        }, 2000) // Wait 2 seconds for Vue to initialize
    }

    private fun showInvalidServerWarning() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Warning")
            .setMessage("This doesn't appear to be a Music Assistant server.\n\nThe app may not work correctly. Please check your server URL in Settings.")
            .setPositiveButton("Open Settings") { _, _ ->
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            .setNegativeButton("Ignore", null)
            .show()
    }

    private fun showSetupDialog() {
        val builder = android.app.AlertDialog.Builder(this)
        val input = android.widget.EditText(this)
        input.hint = getString(R.string.setup_hint)
        input.inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
        input.setPadding(48, 32, 48, 32)

        builder.setTitle(R.string.setup_title)
            .setMessage(R.string.setup_message)
            .setView(input)
            .setCancelable(false)
            .setPositiveButton(R.string.setup_button) { _, _ ->
                val url = input.text.toString().trim()
                when {
                    url.isEmpty() -> {
                        Toast.makeText(this, R.string.setup_error_empty, Toast.LENGTH_SHORT).show()
                        showSetupDialog()
                    }
                    !url.startsWith("http://") && !url.startsWith("https://") -> {
                        Toast.makeText(this, R.string.setup_error_invalid, Toast.LENGTH_SHORT).show()
                        showSetupDialog()
                    }
                    else -> {
                        preferencesHelper.pwaUrl = url
                        checkBatteryOptimization()
                        loadPwaUrl()
                    }
                }
            }
            .show()
    }

    private fun checkBatteryOptimization() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            showBatteryOptimizationDialog()
        }
    }

    @SuppressLint("BatteryLife")
    private fun showBatteryOptimizationDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.battery_optimization_title)
            .setMessage(R.string.battery_optimization_message)
            .setPositiveButton(R.string.battery_optimization_button) { _, _ ->
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }
            .setNegativeButton(R.string.battery_optimization_skip, null)
            .show()
    }

    private fun applyKeepScreenOnSetting() {
        if (preferencesHelper.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            "pwa_url" -> {
                loadPwaUrl()
            }
            "keep_screen_on" -> {
                applyKeepScreenOnSetting()
            }
        }
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, TAG).apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                        MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )

            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    Log.i(TAG, "MediaSession onPlay() - forwarding to WebView")
                    executeMediaCommand("play")
                }

                override fun onPause() {
                    Log.i(TAG, "MediaSession onPause() - forwarding to WebView")
                    executeMediaCommand("pause")
                }

                override fun onSkipToNext() {
                    Log.i(TAG, "MediaSession onSkipToNext() - forwarding to WebView")
                    executeMediaCommand("next")
                }

                override fun onSkipToPrevious() {
                    Log.i(TAG, "MediaSession onSkipToPrevious() - forwarding to WebView")
                    executeMediaCommand("previous")
                }

                override fun onSeekTo(pos: Long) {
                    Log.i(TAG, "MediaSession onSeekTo($pos) - forwarding to WebView")
                    currentPositionMs = pos
                    executeSeekCommand(pos)
                }
            })

            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(
                        PlaybackStateCompat.ACTION_PLAY or
                                PlaybackStateCompat.ACTION_PAUSE or
                                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                    )
                    .setState(PlaybackStateCompat.STATE_NONE, 0, 1.0f)
                    .build()
            )

            isActive = true
        }

        Log.d(TAG, "MediaSession initialized")
    }

    /**
     * Replay any pending notification state that arrived before AudioService was bound.
     * Called from onServiceConnected to ensure no updates are lost.
     */
    private fun replayPendingNotificationState() {
        Log.d(TAG, "Replaying pending notification state...")

        // Replay metadata if we have any
        if (pendingTitle != null || pendingArtist != null) {
            audioService?.updateMetadata(
                pendingTitle ?: "Music Assistant",
                pendingArtist ?: "",
                pendingAlbum ?: "",
                null
            )
            Log.d(TAG, "Replayed pending metadata: $pendingTitle - $pendingArtist")
        }

        // Replay artwork if we have it
        pendingArtwork?.let {
            audioService?.setArtworkBitmap(it)
            Log.d(TAG, "Replayed pending artwork")
        }

        // Replay playback state if we have it
        pendingIsPlaying?.let {
            audioService?.updatePlaybackState(it)
            Log.d(TAG, "Replayed pending playback state: $it")
        }
    }

    private fun startAudioService() {
        // Atomic check-then-act: prevents race condition if called multiple times rapidly
        // compareAndSet returns false if another thread is already starting the service
        if (!isStartingService.compareAndSet(false, true)) {
            Log.d(TAG, "startAudioService already in progress, skipping")
            return
        }

        // Unbind first if already bound (handles app restart scenario)
        if (audioServiceBound) {
            try {
                unbindService(serviceConnection)
                Log.d(TAG, "Unbound existing AudioService connection")
            } catch (e: Exception) {
                Log.w(TAG, "Error unbinding existing service", e)
            }
            audioServiceBound = false
            audioService = null
        }

        val intent = Intent(this, AudioService::class.java)
        startService(intent)
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)
        Log.d(TAG, "AudioService started and binding")
    }

    /**
     * Load JavaScript file from assets and inject into WebView.
     */
    private fun loadJsFromAssets(filename: String): String {
        return try {
            assets.open("js/$filename").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load JS file: $filename", e)
            ""
        }
    }

    /**
     * Inject all JavaScript modules into WebView.
     * Load order matters: ma-websocket -> mediasession-polyfill -> ws-interceptor
     */
    private fun injectMediaSessionPolyfill() {
        Log.d(TAG, "Injecting JavaScript modules from assets...")

        // Load all JS files
        val maWebSocketJs = loadJsFromAssets("ma-websocket.js")
        val mediaSessionJs = loadJsFromAssets("mediasession-polyfill.js")
        val wsInterceptorJs = loadJsFromAssets("ws-interceptor.js")
        val playerObserverJs = loadJsFromAssets("player-selection-observer.js")
        val injectJs = loadJsFromAssets("inject.js")

        // Combine in correct order
        val combinedScript = """
            (function() {
                // === MA WEBSOCKET MANAGER ===
                $maWebSocketJs

                // === MEDIASESSION POLYFILL ===
                $mediaSessionJs

                // === WEBSOCKET INTERCEPTOR ===
                $wsInterceptorJs

                // === PLAYER SELECTION OBSERVER ===
                $playerObserverJs

                // === INJECTION MARKER ===
                $injectJs
            })();
        """.trimIndent()

        webView.evaluateJavascript(combinedScript) { result ->
            Log.d(TAG, "JavaScript injection complete")
        }
    }

    /**
     * Execute media command via Music Assistant WebSocket API.
     * This controls ANY player (Sonos, Chromecast, local SendSpin, etc.)
     */
    private fun executeMediaCommand(command: String) {
        val maCommand = when (command) {
            "play" -> "play"
            "pause" -> "pause"
            "playPause" -> "play_pause"
            "next" -> "next"
            "previous" -> "previous"
            else -> return
        }

        // Clean, simple script using MaWebSocket manager
        val script = """
            (function() {
                // Try MaWebSocket first (controls any player including Sonos)
                if (window.MaWebSocket && window.MaWebSocket.isConnected()) {
                    const result = window.MaWebSocket.$maCommand();
                    return result ? 'ma_websocket' : 'ma_websocket_failed';
                }

                // Fallback to local musicPlayer (only controls SendSpin WebView player)
                if (window.musicPlayer && window.musicPlayer.$command) {
                    window.musicPlayer.$command();
                    return 'local_fallback';
                }

                console.warn('[MediaCommand] No handler available for: $command');
                return 'no_handler';
            })();
        """.trimIndent()

        webView.evaluateJavascript(script) { result ->
            Log.d(TAG, "Media command '$command' -> $result")
        }
    }

    private fun waitForBluetoothAudioAndPlay(deviceName: String) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        var attempts = 0
        val maxAttempts = 20  // 10 seconds max (500ms intervals)

        fun checkAndPlay() {
            attempts++
            val isBluetoothA2dpOn = audioManager.isBluetoothA2dpOn
            val isBluetoothScoOn = audioManager.isBluetoothScoOn

            Log.d(TAG, "Bluetooth audio check #$attempts: A2DP=$isBluetoothA2dpOn, SCO=$isBluetoothScoOn")

            if (isBluetoothA2dpOn || isBluetoothScoOn) {
                Log.i(TAG, "Bluetooth audio ready, checking if phone speaker is selected...")
                // Only auto-play if phone speaker is selected
                checkIfPhoneIsActivePlayer { isPhoneSelected ->
                    if (isPhoneSelected) {
                        Log.i(TAG, "Phone selected, starting playback on: $deviceName")
                        executeMediaCommand("play")
                        Toast.makeText(this, "Auto-playing: $deviceName", Toast.LENGTH_SHORT).show()
                    } else {
                        Log.i(TAG, "Phone not selected - skipping Bluetooth auto-play (external speaker in use)")
                    }
                }
            } else if (attempts < maxAttempts) {
                handler.postDelayed({ checkAndPlay() }, 500)
            } else {
                Log.w(TAG, "Bluetooth audio not ready after ${maxAttempts * 500}ms, checking phone selection...")
                checkIfPhoneIsActivePlayer { isPhoneSelected ->
                    if (isPhoneSelected) {
                        Log.i(TAG, "Phone selected, playing anyway on: $deviceName")
                        executeMediaCommand("play")
                        Toast.makeText(this, "Auto-playing: $deviceName", Toast.LENGTH_SHORT).show()
                    } else {
                        Log.i(TAG, "Phone not selected - skipping Bluetooth auto-play")
                    }
                }
            }
        }

        // Start checking
        handler.post { checkAndPlay() }
    }

    private fun triggerPlay(resumePositionMs: Long, durationMs: Long) {
        Log.i(TAG, "triggerPlay: using PAGE RELOAD approach")

        // Strategy: musicPlayer.play() doesn't work without a page refresh.
        // The PWA's internal state becomes stale after network reconnection.
        // Only a full page reload + play works reliably.
        //
        // Approach:
        // 1. Set a flag that we want to auto-play after reload
        // 2. Reload the page
        // 3. After page loads, wait for SendSpin to connect, then trigger play

        pendingAutoPlayAfterReload = true
        Log.i(TAG, "Setting pendingAutoPlayAfterReload=true, resetting JS flags, reloading page...")

        // Reset JavaScript flags before reload so auto-resume can run fresh
        webView.evaluateJavascript("""
            window._autoResumeInProgress = false;
            window._autoResumeCompleted = false;
        """.trimIndent(), null)

        webView.reload()
    }

    private fun executeAutoPlay() {
        Log.i(TAG, "executeAutoPlay: waiting for SendSpin then triggering play")

        val script = """
            (function() {
                // Prevent duplicate auto-resume scripts from running
                if (window._autoResumeInProgress) {
                    console.log('[AutoResume-Reload] Already running, skipping duplicate');
                    return;
                }
                window._autoResumeInProgress = true;

                // Also check if we already successfully played
                if (window._autoResumeCompleted) {
                    console.log('[AutoResume-Reload] Already completed, skipping');
                    return;
                }

                console.log('[AutoResume-Reload] Starting auto-play after page reload...');

                var attempts = 0;
                var maxAttempts = 30; // 30 seconds max

                function checkAndPlay() {
                    attempts++;
                    console.log('[AutoResume-Reload] Checking readiness, attempt ' + attempts);

                    // Get phone player ID
                    var phonePlayerId = localStorage.getItem('sendspin_webplayer_id');
                    if (!phonePlayerId) {
                        console.log('[AutoResume-Reload] No phone player ID yet');
                        if (attempts < maxAttempts) {
                            setTimeout(checkAndPlay, 1000);
                        } else {
                            window._autoResumeInProgress = false;
                        }
                        return;
                    }

                    // Check MaWebSocket connection
                    if (!window.MaWebSocket || !window.MaWebSocket.isConnected()) {
                        console.log('[AutoResume-Reload] MaWebSocket not ready');
                        if (attempts < maxAttempts) {
                            setTimeout(checkAndPlay, 1000);
                        } else {
                            window._autoResumeInProgress = false;
                        }
                        return;
                    }

                    // Check if player is available and has items in queue
                    window.MaWebSocket.getPlayers().then(function(players) {
                        var phonePlayer = players.find(function(p) {
                            return p.player_id === phonePlayerId;
                        });

                        if (!phonePlayer) {
                            console.log('[AutoResume-Reload] Phone player not found in list');
                            if (attempts < maxAttempts) {
                                setTimeout(checkAndPlay, 1000);
                            } else {
                                window._autoResumeInProgress = false;
                            }
                            return;
                        }

                        console.log('[AutoResume-Reload] Phone player status:', {
                            available: phonePlayer.available,
                            powered: phonePlayer.powered,
                            state: phonePlayer.playback_state,
                            hasMedia: !!phonePlayer.current_media
                        });

                        // Skip if already playing!
                        if (phonePlayer.playback_state === 'playing') {
                            console.log('[AutoResume-Reload] Already playing, no action needed');
                            window._autoResumeCompleted = true;
                            window._autoResumeInProgress = false;
                            return;
                        }

                        // Check if player is available and powered
                        if (phonePlayer.available !== true) {
                            console.log('[AutoResume-Reload] Player not available yet');
                            if (attempts < maxAttempts) {
                                setTimeout(checkAndPlay, 1000);
                            } else {
                                window._autoResumeInProgress = false;
                            }
                            return;
                        }

                        // Check if SendSpin WebSocket is connected
                        if (!window.isSendspinConnected || !window.isSendspinConnected()) {
                            console.log('[AutoResume-Reload] SendSpin WebSocket not connected yet');
                            if (attempts < maxAttempts) {
                                setTimeout(checkAndPlay, 1000);
                            } else {
                                window._autoResumeInProgress = false;
                            }
                            return;
                        }

                        // CRITICAL: Wait for queue to have media before playing!
                        // After page reload, the queue takes time to populate
                        if (!phonePlayer.current_media) {
                            console.log('[AutoResume-Reload] No media in queue yet, waiting...');
                            if (attempts < maxAttempts) {
                                setTimeout(checkAndPlay, 1000);
                            } else {
                                window._autoResumeInProgress = false;
                            }
                            return;
                        }

                        console.log('[AutoResume-Reload] All checks passed! Triggering play...');

                        // Set phone as selected player
                        window.MaWebSocket.setSelectedPlayer(phonePlayerId, 'Phone');
                        localStorage.setItem('massdroid_selected_player_id', phonePlayerId);

                        // CRITICAL FIX: Use MaWebSocket.play() instead of musicPlayer.play()
                        // After page reload, the MediaSession handlers exist but don't properly
                        // trigger the SendSpin audio stream. MaWebSocket.play() sends WebSocket
                        // command directly to MA server which properly initiates the stream.
                        console.log('[AutoResume-Reload] Using MaWebSocket.play() for reliable stream start');
                        window.MaWebSocket.play(phonePlayerId)
                            .then(function() {
                                console.log('[AutoResume-Reload] Play command sent via WebSocket');
                                window._autoResumeCompleted = true;
                                window._autoResumeInProgress = false;
                            })
                            .catch(function(err) {
                                console.error('[AutoResume-Reload] Play command failed:', err);
                                window._autoResumeInProgress = false;
                            });

                    }).catch(function(err) {
                        console.error('[AutoResume-Reload] API error:', err);
                        if (attempts < maxAttempts) {
                            setTimeout(checkAndPlay, 1000);
                        } else {
                            window._autoResumeInProgress = false;
                        }
                    });
                }

                // Start checking after 5 seconds for page init and SendSpin stabilization
                setTimeout(checkAndPlay, 5000);
            })();
        """.trimIndent()

        webView.evaluateJavascript(script, null)
    }

    /**
     * Execute seek command via Music Assistant WebSocket API.
     */
    private fun executeSeekCommand(positionMs: Long) {
        val positionSec = positionMs / 1000.0
        val script = """
            (function() {
                // Try MaWebSocket first
                if (window.MaWebSocket && window.MaWebSocket.isConnected()) {
                    const result = window.MaWebSocket.seek($positionSec);
                    return result ? 'ma_seek' : 'ma_seek_failed';
                }

                // Fallback to local musicPlayer
                if (window.musicPlayer && window.musicPlayer.seekTo) {
                    window.musicPlayer.seekTo($positionSec);
                    return 'local_seek';
                }

                return 'seek_not_available';
            })();
        """.trimIndent()

        webView.evaluateJavascript(script) { result ->
            Log.d(TAG, "Seek command -> $result")
        }
    }

    internal fun updatePlaybackState(state: Int, positionMs: Long = currentPositionMs) {
        mediaSession?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                            PlaybackStateCompat.ACTION_PAUSE or
                            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                            PlaybackStateCompat.ACTION_SEEK_TO
                )
                .setState(state, positionMs, currentPlaybackRate)
                .build()
        )
    }

    /**
     * Request audio focus when starting playback.
     * This ensures we play nicely with other apps and pause for phone calls.
     */
    internal fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) {
            Log.d(TAG, "Already have audio focus")
            return true
        }

        val result: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Android 8.0+ uses AudioFocusRequest
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener(audioFocusChangeListener, handler)
                .setWillPauseWhenDucked(true) // We'll pause instead of ducking
                .build()

            audioFocusRequest = focusRequest
            result = audioManager.requestAudioFocus(focusRequest)
        } else {
            // Pre-Android 8.0
            @Suppress("DEPRECATION")
            result = audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }

        hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        Log.i(TAG, "Audio focus requested: ${if (hasAudioFocus) "GRANTED" else "DENIED"}")
        return hasAudioFocus
    }

    /**
     * Abandon audio focus when stopping playback.
     */
    internal fun abandonAudioFocus() {
        if (!hasAudioFocus) {
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let {
                audioManager.abandonAudioFocusRequest(it)
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }

        hasAudioFocus = false
        Log.i(TAG, "Audio focus abandoned")
    }

    /**
     * Register the phone call listener to pause music during calls.
     * Called after READ_PHONE_STATE permission is granted.
     */
    private fun registerPhoneCallListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ uses TelephonyCallback
            telephonyCallback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    handleCallStateChanged(state)
                }
            }
            try {
                telephonyManager.registerTelephonyCallback(
                    mainExecutor,
                    telephonyCallback!!
                )
                Log.i(TAG, "TelephonyCallback registered for phone call detection")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register TelephonyCallback", e)
            }
        } else {
            // Pre-Android 12: Use deprecated PhoneStateListener
            @Suppress("DEPRECATION")
            phoneStateListener = object : android.telephony.PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    handleCallStateChanged(state)
                }
            }
            @Suppress("DEPRECATION")
            telephonyManager.listen(phoneStateListener, android.telephony.PhoneStateListener.LISTEN_CALL_STATE)
            Log.i(TAG, "PhoneStateListener registered for phone call detection")
        }
    }

    /**
     * Handle phone call state changes - pause during calls, resume after.
     */
    private fun handleCallStateChanged(state: Int) {
        Log.i(TAG, "Phone call state changed: $state (IDLE=0, RINGING=1, OFFHOOK=2)")

        runOnUiThread {
            when (state) {
                TelephonyManager.CALL_STATE_RINGING,
                TelephonyManager.CALL_STATE_OFFHOOK -> {
                    // Phone is ringing or in a call - pause if playing
                    if (!pausedDueToPhoneCall) {
                        Log.i(TAG, "Phone call detected - pausing music via direct JS + muting stream")
                        pausedDueToPhoneCall = true

                        // Method 1: Mute the music stream directly via AudioManager
                        // This works even for WebSocket-based audio like SendSpin
                        // Using adjustStreamVolume instead of deprecated setStreamMute
                        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
                        Log.i(TAG, "Music stream muted via AudioManager")

                        // Method 2: Also try to pause via JavaScript
                        webView.evaluateJavascript("""
                            (function() {
                                console.log('[PhoneCall] Pausing audio for phone call...');
                                if (window.musicPlayer && window.musicPlayer.pause) {
                                    window.musicPlayer.pause();
                                    return 'paused_via_musicPlayer';
                                }
                                // Fallback: try to pause all audio/video elements
                                document.querySelectorAll('audio, video').forEach(function(el) {
                                    el.pause();
                                });
                                return 'paused_via_elements';
                            })();
                        """.trimIndent()) { result ->
                            Log.i(TAG, "Phone call pause result: $result")
                        }
                    }
                }
                TelephonyManager.CALL_STATE_IDLE -> {
                    // Call ended - resume if we paused for the call
                    if (pausedDueToPhoneCall) {
                        Log.i(TAG, "Phone call ended - unmuting and resuming music")
                        pausedDueToPhoneCall = false

                        // Unmute the music stream
                        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                        Log.i(TAG, "Music stream unmuted via AudioManager")

                        // Small delay to let the call fully end, then resume playback
                        handler.postDelayed({
                            webView.evaluateJavascript("""
                                (function() {
                                    console.log('[PhoneCall] Resuming audio after phone call...');
                                    if (window.musicPlayer && window.musicPlayer.play) {
                                        window.musicPlayer.play();
                                        return 'resumed';
                                    }
                                    return 'no_player';
                                })();
                            """.trimIndent()) { result ->
                                Log.i(TAG, "Phone call resume result: $result")
                            }
                        }, 1000)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        preferencesHelper.registerOnChangeListener(this)
        // Update keep screen on state in case it changed in settings
        applyKeepScreenOnSetting()
        // Update drawer header with current URL
        updateDrawerHeader()

        // Check if WebSocket connection is lost and reload if needed
        checkAndReconnectIfNeeded()

        // Check if color changed - need to recreate activity
        if (colorBeforePause.isNotEmpty()) {
            val newColor = androidx.preference.PreferenceManager
                .getDefaultSharedPreferences(this)
                .getString("color_accent", "purple") ?: "purple"
            if (colorBeforePause != newColor) {
                Log.i(TAG, "Color changed, recreating activity: $colorBeforePause -> $newColor")
                colorBeforePause = ""
                urlBeforeSettings = ""
                recreate()
                return
            }
            colorBeforePause = ""
        }

        // Reload if URL changed in settings
        if (urlBeforeSettings.isNotEmpty()) {
            val newUrl = preferencesHelper.pwaUrl
            Log.d(TAG, "Checking URL change: before=$urlBeforeSettings, after=$newUrl")
            if (urlBeforeSettings != newUrl) {
                Log.i(TAG, "URL changed in settings, reloading: $urlBeforeSettings -> $newUrl")
                urlBeforeSettings = "" // Reset before loading to avoid loop
                loadPwaUrl()
            } else {
                urlBeforeSettings = "" // Reset even if not changed
            }
        }
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        // Don't pause timers - we want audio to continue in background
        // webView.pauseTimers() would stop ALL JavaScript including audio playback
        preferencesHelper.unregisterOnChangeListener(this)
        // Track URL and color to detect changes when resuming
        urlBeforeSettings = preferencesHelper.pwaUrl
        colorBeforePause = androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(this)
            .getString("color_accent", "purple") ?: "purple"
        Log.d(TAG, "onPause - tracking URL: $urlBeforeSettings, color: $colorBeforePause")
    }

    /**
     * Query current playback state from Music Assistant on app launch.
     * This populates the notification with current track if music is already playing.
     */
    private fun queryCurrentPlaybackState() {
        Log.i(TAG, "========================================")
        Log.i(TAG, "Querying current playback state from Music Assistant...")
        Log.i(TAG, "========================================")

        val queryScript = """
            (function() {
                try {
                    const appElement = document.querySelector('#app');
                    if (!appElement || !appElement.__vue_app__) {
                        console.log('[QueryState] Vue app not ready yet');
                        return JSON.stringify({ success: false, error: 'Vue app not ready' });
                    }

                    const vueApp = appElement.__vue_app__;
                    const api = vueApp.config.globalProperties.${'$'}api;

                    if (!api || !api.players) {
                        console.log('[QueryState] API not ready');
                        return JSON.stringify({ success: false, error: 'API not ready' });
                    }

                    // Find active player
                    const players = Object.values(api.players);
                    const activePlayer = players.find(p => p.powered && p.state !== 'idle');

                    if (!activePlayer) {
                        console.log('[QueryState] No active player found');
                        return JSON.stringify({ success: false, error: 'No active player' });
                    }

                    console.log('[QueryState] Active player:', activePlayer.player_id, 'state:', activePlayer.state);

                    // Get current track
                    const currentMedia = activePlayer.current_media;
                    if (!currentMedia) {
                        console.log('[QueryState] No current media');
                        return JSON.stringify({ success: false, error: 'No current media' });
                    }

                    // Extract metadata
                    const title = currentMedia.name || 'Unknown';
                    const artist = currentMedia.artists?.[0]?.name || 'Unknown';
                    const album = currentMedia.album?.name || '';
                    const artworkUrl = currentMedia.image_url || '';
                    const isPlaying = activePlayer.state === 'playing';

                    console.log('[QueryState] Found track:', title, '-', artist);
                    console.log('[QueryState] State:', activePlayer.state);

                    // Update Android immediately
                    if (window.AndroidMediaSession) {
                        window.AndroidMediaSession.updateNowPlaying(title, artist, album, artworkUrl, isPlaying);
                    }

                    return JSON.stringify({
                        success: true,
                        title: title,
                        artist: artist,
                        album: album,
                        artworkUrl: artworkUrl,
                        isPlaying: isPlaying
                    });
                } catch (e) {
                    console.error('[QueryState] Error:', e);
                    return JSON.stringify({ success: false, error: e.message });
                }
            })();
        """.trimIndent()

        webView.evaluateJavascript(queryScript) { result ->
            Log.i(TAG, "Query result: $result")
        }
    }

    /**
     * Check if the phone (SendSpin web player) is the selected player.
     * Used to determine if auto-resume should trigger on network change.
     * Auto-resume only makes sense for the phone speaker, not external players.
     */
    private fun checkIfPhoneIsActivePlayer(callback: (Boolean) -> Unit) {
        val checkScript = """
            (function() {
                const sendspinId = localStorage.getItem('sendspin_webplayer_id');

                // Use MaWebSocket's selected player (our source of truth)
                if (window.MaWebSocket && window.MaWebSocket._selectedPlayerId) {
                    const isPhone = sendspinId && window.MaWebSocket._selectedPlayerId === sendspinId;
                    console.log('[AutoResume] Phone selected:', isPhone,
                        'sendspinId:', sendspinId,
                        'selectedId:', window.MaWebSocket._selectedPlayerId);
                    return isPhone;
                }

                // Fallback: if no selection yet, assume phone is active
                console.log('[AutoResume] No selection yet, assuming phone');
                return true;
            })();
        """.trimIndent()

        // WebView methods must be called on the main thread
        runOnUiThread {
            webView.evaluateJavascript(checkScript) { result ->
                val isPhonePlayer = result == "true"
                Log.d(TAG, "checkIfPhoneIsActivePlayer: $isPhonePlayer")
                callback(isPhonePlayer)
            }
        }
    }

    /**
     * Check if WebSocket connection is lost and reload if needed.
     * Called on resume to handle stale WebView after long background.
     */
    private fun checkAndReconnectIfNeeded() {
        // Delay check to allow WebView to resume first
        handler.postDelayed({
            webView.evaluateJavascript("""
                (function() {
                    if (window.MaWebSocket && window.MaWebSocket.isConnected()) {
                        return 'connected';
                    }
                    return 'disconnected';
                })();
            """.trimIndent()) { result ->
                val isConnected = result?.replace("\"", "") == "connected"
                Log.d(TAG, "WebSocket connection check on resume: $result")
                if (!isConnected) {
                    Log.i(TAG, "WebSocket disconnected, reloading page...")
                    webView.reload()
                }
            }
        }, 500)  // Small delay to let WebView resume
    }

    /**
     * JavaScript interface for PWA to update media metadata and playback state.
     *
     * Uses WeakReference to avoid memory leaks when Activity is destroyed
     * but WebView still holds a reference to this interface.
     */
    @Keep
    class MediaMetadataInterface(private val activityRef: WeakReference<MainActivity>) {
        // Debug logging via SendSpinDebug
        @JavascriptInterface
        fun logDebug(tag: String, message: String) {
            Log.d("WS_$tag", message)
        }

        @JavascriptInterface
        fun logWsConnection(url: String, label: String) {
            SendSpinDebug.logConnection(url, label)
        }

        @JavascriptInterface
        fun logWsDisconnection(label: String, code: Int, reason: String) {
            SendSpinDebug.logDisconnection(label, code, reason)
        }

        @JavascriptInterface
        fun logWsMessage(source: String, msgType: String, payload: String) {
            SendSpinDebug.logMessage(source, msgType, payload)
        }

        @JavascriptInterface
        fun dumpDebugState() {
            val activity = activityRef.get() ?: return
            SendSpinDebug.dumpState(activity.webView)
        }

        @JavascriptInterface
        fun updateMetadata(title: String, artist: String, album: String, artworkUrl: String, durationMs: Long) {
            val activity = activityRef.get() ?: return

            Log.i(TAG, "========================================")
            Log.i(TAG, "MediaMetadataInterface.updateMetadata() called from JavaScript")
            Log.i(TAG, "Title: $title")
            Log.i(TAG, "Artist: $artist")
            Log.i(TAG, "Album: $album")
            Log.i(TAG, "Artwork URL: $artworkUrl")
            Log.i(TAG, "Duration: ${durationMs}ms")
            Log.i(TAG, "========================================")

            activity.runOnUiThread {
                Log.d(TAG, "Running metadata update on UI thread...")

                // Update MediaSession metadata for Bluetooth/system controls
                val metadataBuilder = MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)

                // Set artwork URI
                if (artworkUrl.isNotEmpty()) {
                    metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, artworkUrl)
                }

                // IMPORTANT: Preserve existing artwork bitmap if we have it
                // This prevents metadata updates from overwriting the artwork
                activity.currentArtworkBitmap?.let { artwork ->
                    metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, artwork)
                }

                activity.mediaSession?.setMetadata(metadataBuilder.build())
                Log.d(TAG, "MediaSession metadata updated: $title")

                // Cache for replay if service binds later
                activity.pendingTitle = title
                activity.pendingArtist = artist
                activity.pendingAlbum = album

                // Track current title for seek verification after network reconnect
                activity.currentTrackTitle = title

                // Update AudioService notification if bound
                if (activity.audioServiceBound && activity.audioService != null) {
                    activity.audioService?.updateMetadata(title, artist, album, artworkUrl)
                    Log.d(TAG, "AudioService notification updated with metadata")
                } else {
                    Log.d(TAG, "AudioService not bound - metadata cached for later")
                }
            }
        }

        @JavascriptInterface
        fun updatePlaybackState(state: String, positionMs: Long) {
            val activity = activityRef.get() ?: return

            Log.i(TAG, "========================================")
            Log.i(TAG, "MediaMetadataInterface.updatePlaybackState() called from JavaScript")
            Log.i(TAG, "State: $state")
            Log.i(TAG, "Position: ${positionMs}ms")
            Log.i(TAG, "Thread: ${Thread.currentThread().name}")
            Log.i(TAG, "========================================")

            activity.runOnUiThread {
                val isPlaying = state == "playing"

                // Track playback state for network change detection
                val wasPlaying = activity.isCurrentlyPlaying
                activity.isCurrentlyPlaying = isPlaying

                // NOTE: Auto-resume success is ONLY detected via onSendspinStreamStart()
                // Do NOT use playback state change as it causes false positives - server reports
                // "playing" before SendSpin actually streams audio to the phone.
                // The retry logic will handle cases where stream/start is not received.

                // Handle audio focus based on playback state
                if (isPlaying && !wasPlaying) {
                    // Starting playback - request audio focus
                    // Don't set pausedDueToFocusLoss here - user initiated playback
                    activity.pausedDueToFocusLoss = false

                    // Ignore focus events for 2 seconds to avoid startup race conditions
                    activity.ignoreFocusEvents = true
                    activity.handler.postDelayed({
                        activity.ignoreFocusEvents = false
                        Log.d(TAG, "Audio focus grace period ended")
                    }, 2000)

                    activity.requestAudioFocus()
                } else if (!isPlaying && wasPlaying && !activity.pausedDueToFocusLoss) {
                    // User stopped playback (not due to focus loss) - abandon audio focus
                    activity.abandonAudioFocus()
                }

                val playbackState = if (isPlaying) {
                    PlaybackStateCompat.STATE_PLAYING
                } else {
                    PlaybackStateCompat.STATE_PAUSED
                }

                // Store position if provided
                if (positionMs > 0) {
                    activity.currentPositionMs = positionMs
                }

                Log.d(TAG, "Playback state update: isPlaying=$isPlaying, position=${activity.currentPositionMs}ms")

                // Update MediaSession state for Bluetooth/system controls
                activity.updatePlaybackState(playbackState, activity.currentPositionMs)

                // Cache for replay if service binds later
                activity.pendingIsPlaying = isPlaying

                // Update AudioService notification - CRITICAL for icon sync
                if (activity.audioServiceBound && activity.audioService != null) {
                    activity.audioService?.updatePlaybackState(isPlaying)
                } else {
                    Log.d(TAG, "AudioService not bound - playback state cached for later")
                }
            }
        }

        @JavascriptInterface
        fun setArtworkBase64(base64Data: String) {
            val activity = activityRef.get() ?: return

            Log.i(TAG, "setArtworkBase64() called, data length: ${base64Data.length}")

            activity.backgroundScope.launch(Dispatchers.IO) {
                try {
                    val decodedBytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
                    val artwork = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)

                    if (artwork != null) {
                        withContext(Dispatchers.Main) {
                            // Re-check activity reference after context switch
                            val act = activityRef.get() ?: return@withContext

                            act.currentArtworkBitmap = artwork

                            // Update MediaSession with artwork
                            val currentMetadata = act.mediaSession?.controller?.metadata
                            if (currentMetadata != null) {
                                val metadataBuilder = MediaMetadataCompat.Builder(currentMetadata)
                                    .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, artwork)
                                act.mediaSession?.setMetadata(metadataBuilder.build())
                                Log.d(TAG, "MediaSession artwork updated via base64")
                            }

                            // Cache for replay if service binds later
                            act.pendingArtwork = artwork

                            // Update AudioService notification
                            if (act.audioServiceBound && act.audioService != null) {
                                act.audioService?.setArtworkBitmap(artwork)
                                Log.d(TAG, "AudioService artwork updated via base64")
                            } else {
                                Log.d(TAG, "AudioService not bound - artwork cached for later")
                            }
                        }
                    } else {
                        Log.w(TAG, "Failed to decode artwork bitmap from base64")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error decoding artwork base64", e)
                }
            }
        }

        @JavascriptInterface
        fun updateNowPlaying(title: String, artist: String, album: String, artworkUrl: String, isPlaying: Boolean) {
            Log.i(TAG, "========================================")
            Log.i(TAG, "MediaMetadataInterface.updateNowPlaying() called from JavaScript")
            Log.i(TAG, "Title: $title")
            Log.i(TAG, "Artist: $artist")
            Log.i(TAG, "Album: $album")
            Log.i(TAG, "Artwork URL: $artworkUrl")
            Log.i(TAG, "Is Playing: $isPlaying")
            Log.i(TAG, "========================================")

            // Update metadata first
            updateMetadata(title, artist, album, artworkUrl, 0)

            // Then update playback state
            updatePlaybackState(if (isPlaying) "playing" else "paused", 0)
        }

        @JavascriptInterface
        fun onSendspinConnected() {
            Log.i(TAG, "========================================")
            Log.i(TAG, "SendSpin connected (waiting for stabilization)")
            Log.i(TAG, "========================================")
            // Don't trigger auto-resume here - wait for onSendspinStabilized
        }

        /**
         * Called when SendSpin connection has stabilized (no reconnects for 2.5+ seconds).
         * This is the safe point to trigger auto-resume.
         */
        @JavascriptInterface
        fun onSendspinStabilized(isConnected: Boolean, serverPlaybackState: String) {
            val activity = activityRef.get() ?: return

            Log.i(TAG, "========================================")
            Log.i(TAG, "SendSpin STABILIZED")
            Log.i(TAG, "  isConnected: $isConnected")
            Log.i(TAG, "  serverPlaybackState: $serverPlaybackState")
            Log.i(TAG, "  wasPlayingBeforeNetworkLoss: ${activity.wasPlayingBeforeNetworkLoss}")
            Log.i(TAG, "  autoResumeEnabled: ${activity.preferencesHelper.autoResumeOnNetwork}")
            Log.i(TAG, "========================================")

            if (!isConnected) {
                Log.w(TAG, "Stabilized but not connected - skipping auto-resume")
                return
            }

            if (!activity.wasPlayingBeforeNetworkLoss) {
                Log.i(TAG, "Was not playing before network loss - no auto-resume needed")
                return
            }

            if (!activity.preferencesHelper.autoResumeOnNetwork) {
                Log.i(TAG, "Auto-resume disabled in settings - skipping")
                activity.wasPlayingBeforeNetworkLoss = false
                return
            }

            // Check if phone speaker is selected before auto-resume
            activity.checkIfPhoneIsActivePlayer { isPhoneSelected ->
                if (!isPhoneSelected) {
                    Log.i(TAG, "Phone not selected - skipping auto-resume (external speaker in use)")
                    activity.wasPlayingBeforeNetworkLoss = false
                    return@checkIfPhoneIsActivePlayer
                }

                Log.i(TAG, "Triggering auto-resume (primary path)...")
                activity.waitingForStreamStart = true
                activity.autoResumeRetryCount = 0  // Reset retry counter
                activity.primaryAutoResumeActive = true  // Stop fallback polling

                activity.runOnUiThread {
                    // Show toast to indicate auto-resume is starting
                    Toast.makeText(activity, "Auto-resuming...", Toast.LENGTH_SHORT).show()
                // The server thinks it's already playing, but no stream/start was sent
                // Force a fresh stream by stopping then playing
                activity.handler.postDelayed({
                    Log.i(TAG, "Sending stop command to force stream reset...")
                    activity.webView.evaluateJavascript("""
                        (function() {
                            if (window.MaWebSocket && window.MaWebSocket.isConnected()) {
                                console.log('[AutoResume] Sending stop command');
                                window.MaWebSocket.stop();
                                return 'stop_sent';
                            }
                            return 'not_connected';
                        })();
                    """.trimIndent()) { stopResult ->
                        Log.i(TAG, "Stop command result: $stopResult")

                        // Wait a moment for stop to process, then play
                        activity.handler.postDelayed({
                            Log.i(TAG, "Sending play command...")
                            activity.webView.evaluateJavascript("""
                                (function() {
                                    if (window.MaWebSocket && window.MaWebSocket.isConnected()) {
                                        console.log('[AutoResume] Sending play command');
                                        window.MaWebSocket.play();
                                        return 'play_sent';
                                    }
                                    return 'not_connected';
                                })();
                            """.trimIndent()) { playResult ->
                                Log.i(TAG, "Play command result: $playResult")

                                // Seek to saved position after play starts (only if same track)
                                val savedPosSec = activity.savedPositionMs / 1000
                                val savedTitle = activity.savedTrackTitle
                                if (savedPosSec > 5 && savedTitle.isNotEmpty()) {
                                    activity.handler.postDelayed({
                                        // Verify track hasn't changed before seeking
                                        if (activity.currentTrackTitle == savedTitle) {
                                            Log.i(TAG, "Same track confirmed, seeking to saved position: ${savedPosSec}s")
                                            activity.webView.evaluateJavascript("""
                                                (function() {
                                                    if (window.MaWebSocket && window.MaWebSocket.isConnected()) {
                                                        console.log('[AutoResume] Seeking to $savedPosSec seconds');
                                                        window.MaWebSocket.seek($savedPosSec);
                                                        return 'seek_sent';
                                                    }
                                                    return 'not_connected';
                                                })();
                                            """.trimIndent()) { seekResult ->
                                                Log.i(TAG, "Seek command result: $seekResult")
                                            }
                                        } else {
                                            Log.i(TAG, "Track changed (was: $savedTitle, now: ${activity.currentTrackTitle}) - skipping seek")
                                        }
                                    }, 1000)  // Wait 1s for play to start before seeking
                                }
                            }
                        }, 500)  // Wait 500ms after stop before play
                    }
                }, 500)  // Small delay to ensure WebSocket is fully ready

                // Set timeout for auto-resume - reload WebView on each retry
                activity.autoResumeTimeoutRunnable = Runnable {
                    if (activity.waitingForStreamStart) {
                        activity.autoResumeRetryCount++
                        Log.w(TAG, "Auto-resume timed out - no stream/start (attempt ${activity.autoResumeRetryCount}/${activity.MAX_AUTO_RESUME_RETRIES})")

                        if (activity.autoResumeRetryCount < activity.MAX_AUTO_RESUME_RETRIES) {
                            // Retry by reloading WebView
                            Log.i(TAG, "Reloading WebView for retry...")
                            activity.runOnUiThread {
                                Toast.makeText(activity, "Reloading... (${activity.autoResumeRetryCount}/${activity.MAX_AUTO_RESUME_RETRIES})", Toast.LENGTH_SHORT).show()
                                activity.pendingAutoPlayAfterReload = true
                                activity.webView.reload()
                            }
                            // Keep waitingForStreamStart and flags active for next attempt
                            activity.primaryAutoResumeActive = false  // Allow new stabilization after reload
                        } else {
                            // All retries failed
                            Log.w(TAG, "All ${activity.MAX_AUTO_RESUME_RETRIES} retries failed - giving up")
                            activity.runOnUiThread {
                                Toast.makeText(activity, "Could not resume playback", Toast.LENGTH_LONG).show()
                            }
                            activity.waitingForStreamStart = false
                            activity.wasPlayingBeforeNetworkLoss = false
                            activity.autoResumeRetryCount = 0
                            activity.primaryAutoResumeActive = false
                        }
                    }
                }
                activity.handler.postDelayed(activity.autoResumeTimeoutRunnable!!, 5000)  // 5 second timeout
                }
            }
        }

        @JavascriptInterface
        fun onSendspinStreamStart() {
            val activity = activityRef.get() ?: return

            Log.i(TAG, "========================================")
            Log.i(TAG, "SendSpin stream/start - AUDIO IS PLAYING!")
            Log.i(TAG, "waitingForStreamStart: ${activity.waitingForStreamStart}")
            Log.i(TAG, "========================================")

            activity.runOnUiThread {
                if (activity.waitingForStreamStart) {
                    Log.i(TAG, "Auto-resume SUCCESSFUL - stream/start received!")
                    activity.waitingForStreamStart = false
                    activity.wasPlayingBeforeNetworkLoss = false
                    activity.autoResumeRetryCount = 0  // Reset retry counter on success
                    activity.primaryAutoResumeActive = false  // Allow future fallbacks

                    // Cancel the timeout runnable
                    activity.autoResumeTimeoutRunnable?.let { activity.handler.removeCallbacks(it) }
                    activity.autoResumeTimeoutRunnable = null

                    // Brief success toast
                    Toast.makeText(activity, "Playback resumed", Toast.LENGTH_SHORT).show()
                }
            }
        }


        @JavascriptInterface
        fun onSendspinDisconnected() {
            Log.i(TAG, "========================================")
            Log.i(TAG, "SendSpin disconnected")
            Log.i(TAG, "========================================")
            // AUTO-RESUME DISABLED - starting fresh
        }

        @JavascriptInterface
        fun onPlayFailed() {
            val activity = activityRef.get() ?: return

            Log.w(TAG, "Play failed - auto-resume timed out")
            activity.runOnUiThread {
                // JavaScript timed out waiting for track ready signal
                // Clear flags and notify user
                activity.waitingForStreamStart = false
                activity.wasPlayingBeforeNetworkLoss = false
                Toast.makeText(activity, "Could not auto-resume", Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun updatePositionState(durationMs: Long, positionMs: Long, playbackRate: Float) {
            val activity = activityRef.get() ?: return

            Log.d(TAG, "updatePositionState: duration=${durationMs}ms, position=${positionMs}ms, rate=$playbackRate")

            activity.runOnUiThread {
                // Store position state
                activity.currentDurationMs = durationMs
                activity.currentPositionMs = positionMs
                activity.currentPlaybackRate = playbackRate

                // Update MediaSession metadata with duration if we have it
                if (durationMs > 0) {
                    activity.mediaSession?.let { session ->
                        val currentMetadata = session.controller?.metadata
                        if (currentMetadata != null) {
                            val builder = MediaMetadataCompat.Builder(currentMetadata)
                                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)
                            session.setMetadata(builder.build())
                        }
                    }
                }

                // Update playback state with position
                val currentState = activity.mediaSession?.controller?.playbackState?.state
                    ?: PlaybackStateCompat.STATE_NONE
                activity.updatePlaybackState(currentState, positionMs)
            }
        }

        @JavascriptInterface
        fun onPlayerSelected(playerId: String, playerName: String) {
            val activity = activityRef.get() ?: return

            Log.i(TAG, "========================================")
            Log.i(TAG, "PLAYER SELECTED: $playerName")
            Log.i(TAG, "Player ID: $playerId")
            Log.i(TAG, "========================================")

            activity.runOnUiThread {
                // Store selected player info
                activity.selectedPlayerId = playerId
                activity.selectedPlayerName = playerName

                // Show toast
                Toast.makeText(activity, "Controlling: $playerName", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        // Abandon audio focus
        abandonAudioFocus()

        // Unregister telephony callback/listener
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback?.let {
                try {
                    telephonyManager.unregisterTelephonyCallback(it)
                    Log.d(TAG, "TelephonyCallback unregistered")
                } catch (e: Exception) {
                    Log.e(TAG, "Error unregistering TelephonyCallback", e)
                }
            }
            telephonyCallback = null
        } else {
            // Pre-Android 12: Unregister PhoneStateListener
            phoneStateListener?.let {
                try {
                    @Suppress("DEPRECATION")
                    telephonyManager.listen(it, android.telephony.PhoneStateListener.LISTEN_NONE)
                    Log.d(TAG, "PhoneStateListener unregistered")
                } catch (e: Exception) {
                    Log.e(TAG, "Error unregistering PhoneStateListener", e)
                }
            }
            phoneStateListener = null
        }

        // Make sure audio is unmuted if app closes during a call
        if (pausedDueToPhoneCall) {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            Log.d(TAG, "Unmuted audio stream on destroy")
        }

        // Note: lifecycleScope automatically cancels when Activity is destroyed

        // Remove all pending handler callbacks
        handler.removeCallbacksAndMessages(null)

        // Clear artwork reference (GC will handle cleanup)
        currentArtworkBitmap = null

        // Stop network monitor
        networkMonitor?.stop()
        networkMonitor = null

        // Unregister Bluetooth receiver
        bluetoothReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering Bluetooth receiver", e)
            }
            bluetoothReceiver = null
        }

        // Cleanup media components
        if (audioServiceBound) {
            unbindService(serviceConnection)
            audioServiceBound = false
        }

        mediaSession?.release()
        mediaSession = null

        Log.d(TAG, "Media components cleaned up")
    }
}
