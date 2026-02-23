package net.asksakis.massdroid

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.ComponentName
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.SystemClock
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import android.webkit.ClientCertRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
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

class MainActivity : AppCompatActivity(),
    NavigationView.OnNavigationItemSelectedListener,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView
    private lateinit var toolbar: MaterialToolbar
    internal lateinit var webView: WebView
    private lateinit var webviewWrapper: LinearLayout
    private lateinit var progressBar: LinearProgressIndicator
    internal lateinit var preferencesHelper: PreferencesHelper

    // Media components (internal for WeakReference access from MediaMetadataInterface)
    internal var mediaSession: MediaSessionCompat? = null
    internal var audioService: AudioService? = null
    internal var audioServiceBound = false
    // AtomicBoolean for thread-safe check-then-act pattern in startAudioService()
    private val isStartingService = AtomicBoolean(false)
    internal val handler = Handler(Looper.getMainLooper())

    // Audio manager
    private lateinit var audioManager: AudioManager

    // Detect other media apps so we can yield (Chromium's WebView doesn't yield on its own)
    private var audioPlaybackCallback: AudioManager.AudioPlaybackCallback? = null
    private var lastKnownMediaConfigCount = 0
    private var playStartTime = 0L  // To ignore our own Chromium AudioContext startup
    private var lastPauseTime = 0L  // When playback last transitioned to paused
    @Volatile
    private var webViewAudioMuted = false

    // Voice call detection (via AudioPlaybackCallback, no permission needed)
    @Volatile
    private var pausedDueToVoiceCall = false
    private var voiceCallEndRunnable: Runnable? = null  // Debounce for voice call end

    // Position state tracking (internal for WeakReference access from MediaMetadataInterface)
    internal var currentDurationMs: Long = 0
    internal var currentPositionMs: Long = 0
    internal var currentPlaybackRate: Float = 1.0f
    @Volatile
    internal var isCurrentlyPlaying = false  // Track playback state ourselves
    @Volatile
    internal var yieldedToOtherApp = false  // When true, ignore server "playing" to prevent re-unmuting

    // Bluetooth auto-play
    private var bluetoothReceiver: BluetoothAutoPlayReceiver? = null
    @Volatile
    internal var webViewReady = false

    // Auto-resume after page reload
    @Volatile
    internal var pendingAutoPlayAfterReload = false

    // Bluetooth auto-play after reload (when WebSocket was stale)
    @Volatile
    private var pendingBluetoothAutoPlayDevice: BluetoothAutoPlayReceiver.BluetoothAudioDevice? = null
    private var pendingBtDisconnectStopRunnable: Runnable? = null
    private var pendingBtDisconnectDeviceAddress: String? = null
    private var btFlowCounter = 0L
    private lateinit var networkAutoResume: NetworkAutoResumeCoordinator


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
    internal var phonePlayerId: String? = null  // SendSpin player ID (local phone speaker)

    // Track URL for detecting changes after settings
    private var urlBeforeSettings: String = ""
    private var colorBeforePause: String = ""
    internal var currentTrackTitle: String = ""  // Current track title for verification

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
        private const val BT_DISCONNECT_GRACE_MS = 3000L
    }

    private fun nextBtFlowId(prefix: String): String {
        btFlowCounter++
        return "BT-$prefix-$btFlowCounter"
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
     * Check if the phone speaker (SendSpin) is the currently selected player.
     * Used to guard audio focus events - we should only pause/resume for phone speaker,
     * not for external players like Sonos/Chromecast.
     */
    internal fun isPhonePlayerSelected(): Boolean {
        val selected = selectedPlayerId ?: return true  // No selection yet, assume phone
        val phone = phonePlayerId ?: return true  // Phone ID unknown, assume phone (safe default)
        return selected == phone
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeHelper.applyTheme(this)
        setContentView(R.layout.activity_main)

        // Initialize preferences helper
        preferencesHelper = PreferencesHelper(this)

        // Restore saved client certificate alias
        clientCertAlias = preferencesHelper.clientCertAlias

        // Restore persisted phone player ID (needed before audio focus events fire)
        phonePlayerId = preferencesHelper.phonePlayerId
        Log.d(TAG, "Restored phonePlayerId from prefs: $phonePlayerId")

        // Initialize audio manager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        registerAudioPlaybackCallback()

        // Setup views
        setupViews()
        setupToolbar()
        setupNavigationDrawer()
        setupWebView()
        setupAutoResumeCoordinator()

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
     * Checks on app launch, respecting the cooldown interval in UpdateChecker.
     */
    private fun checkForAppUpdates() {
        val updateChecker = UpdateChecker(this)
        lifecycleScope.launch {
            val updateInfo = updateChecker.checkForUpdates(force = false)
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
                networkAutoResume.onNetworkLost(
                    isCurrentlyPlaying = isCurrentlyPlaying,
                    autoResumeEnabled = preferencesHelper.autoResumeOnNetwork,
                    currentPositionMs = currentPositionMs,
                    currentDurationMs = currentDurationMs,
                    currentTrackTitle = currentTrackTitle
                )
            }

            override fun onNetworkAvailable() {
                Log.i(TAG, "========================================")
                Log.i(TAG, "Network available")
                Log.i(TAG, "wasPlayingBeforeNetworkLoss=${networkAutoResume.wasPlayingBeforeNetworkLoss}")
                Log.i(TAG, "========================================")
                networkAutoResume.onNetworkAvailable(preferencesHelper.autoResumeOnNetwork)
            }
        })

        networkMonitor?.start()
    }

    private fun setupAutoResumeCoordinator() {
        networkAutoResume = NetworkAutoResumeCoordinator(handler, object : NetworkAutoResumeCoordinator.Host {
            override fun evaluateJavascript(script: String, callback: (String) -> Unit) {
                runOnUiThread {
                    webView.evaluateJavascript(script, callback)
                }
            }

            override fun showToast(message: String, duration: Int) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, message, duration).show()
                }
            }

            override fun reloadWebViewForAutoResumeRetry(retryCount: Int, maxRetries: Int) {
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "Reloading... ($retryCount/$maxRetries)",
                        Toast.LENGTH_SHORT
                    ).show()
                    pendingAutoPlayAfterReload = true
                    webView.reload()
                }
            }

            override fun forceCloseSocketsForReconnect() {
                runOnUiThread {
                    Log.i(TAG, "Forcing controlled WebSocket reset after resume timeout...")
                    webView.evaluateJavascript(
                        """
                        (function() {
                            console.log('[AutoResume] Controlled socket reset after timeout');
                            if (window.closeSendspinSocket) {
                                window.closeSendspinSocket();
                            }
                            if (window.MaWebSocket && window.MaWebSocket.close) {
                                window.MaWebSocket.close();
                            }
                            return 'closed';
                        })();
                        """.trimIndent()
                    ) { result ->
                        Log.i(TAG, "WebSocket close result: $result")
                    }
                }
            }

            override fun getCurrentTrackTitle(): String = currentTrackTitle

            override fun getPhonePlayerId(): String? = phonePlayerId

            override fun isCurrentlyPlaying(): Boolean = this@MainActivity.isCurrentlyPlaying

            override fun getCurrentPositionMs(): Long = currentPositionMs
        })
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

    }

    private fun registerBluetoothReceiver() {
        if (bluetoothReceiver != null) return

        bluetoothReceiver = BluetoothAutoPlayReceiver(
            onBluetoothAudioConnected = { device ->
                val flowId = nextBtFlowId("CONNECT")
                Log.i(TAG, "[BT_FLOW][$flowId] connected name=${device.name} addr=${device.address}")

                // Cancel pending disconnect stop on any new BT connect event (handoff protection)
                pendingBtDisconnectStopRunnable?.let {
                    handler.removeCallbacks(it)
                    pendingBtDisconnectStopRunnable = null
                    Log.i(TAG, "[BT_FLOW][$flowId] canceled pending disconnect stop (handoff/reconnect)")
                }
                pendingBtDisconnectDeviceAddress = null

                // Check if auto-play is enabled
                if (!preferencesHelper.autoPlayOnBluetooth) {
                    Log.d(TAG, "[BT_FLOW][$flowId] auto-play disabled")
                    return@BluetoothAutoPlayReceiver
                }

                // Check if WebView is ready
                if (!webViewReady) {
                    Log.d(TAG, "[BT_FLOW][$flowId] WebView not ready, skipping")
                    return@BluetoothAutoPlayReceiver
                }

                // Ignore if we're already processing a BT auto-play
                if (pendingBluetoothAutoPlayDevice != null) {
                    val pending = pendingBluetoothAutoPlayDevice
                    Log.d(TAG, "[BT_FLOW][$flowId] auto-play already in progress for ${pending?.name} (${pending?.address}), ignoring duplicate")
                    return@BluetoothAutoPlayReceiver
                }

                // Resume WebView in case it was paused (onPause stops JS execution)
                webView.onResume()
                webView.resumeTimers()

                // Check if PHONE is actively streaming audio (not just selected in UI)
                checkIfPhoneIsActivePlayer { isPhoneSelected ->
                    if (isPhoneSelected) {
                        checkIfPhoneActuallyPlaying { phoneActuallyPlaying ->
                            if (phoneActuallyPlaying) {
                                // Phone is truly playing - don't interrupt with stop/play
                                Log.i(TAG, "[BT_FLOW][$flowId] phone confirmed playing, route should switch automatically")
                                Toast.makeText(this, "Connected: ${device.name}", Toast.LENGTH_SHORT).show()
                                return@checkIfPhoneActuallyPlaying
                            }

                            Log.i(TAG, "[BT_FLOW][$flowId] phone selected but not actually playing, proceeding with auto-play")
                            continueBluetoothAutoPlay(flowId, device)
                        }
                        return@checkIfPhoneIsActivePlayer
                    }

                    continueBluetoothAutoPlay(flowId, device)
                }
            },
            onBluetoothAudioDisconnected = { device ->
                val flowId = nextBtFlowId("DISCONNECT")
                Log.i(TAG, "[BT_FLOW][$flowId] disconnected name=${device.name} addr=${device.address}")

                // Prevent network auto-resume from triggering after BT disconnect
                // (BT disconnect can cause SendSpin reconnect → false auto-resume)
                networkAutoResume.onBluetoothDisconnect()

                // Only stop if currently playing
                if (!isCurrentlyPlaying) {
                    Log.d(TAG, "[BT_FLOW][$flowId] not playing, no stop needed")
                    return@BluetoothAutoPlayReceiver
                }

                // Check if WebView is ready
                if (!webViewReady) {
                    Log.d(TAG, "[BT_FLOW][$flowId] WebView not ready, cannot stop")
                    return@BluetoothAutoPlayReceiver
                }

                // Grace period to avoid false stop during BT handoff/reconnect races
                pendingBtDisconnectStopRunnable?.let { handler.removeCallbacks(it) }
                pendingBtDisconnectDeviceAddress = device.address
                pendingBtDisconnectStopRunnable = Runnable {
                    if (pendingBtDisconnectDeviceAddress != device.address) {
                        Log.d(TAG, "[BT_FLOW][$flowId] disconnect runnable stale, skipping")
                        return@Runnable
                    }

                    // Only stop if phone speaker is selected (don't stop external speakers like Sonos)
                    checkIfPhoneIsActivePlayer { isPhoneSelected ->
                        if (isPhoneSelected) {
                            Log.i(TAG, "[BT_FLOW][$flowId] grace elapsed, stopping playback due to disconnect")
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
                                    Log.i(TAG, "[BT_FLOW][$flowId] disconnect stop result=$result")
                                }
                            }
                        } else {
                            Log.i(TAG, "[BT_FLOW][$flowId] phone not selected, ignoring disconnect")
                        }
                        pendingBtDisconnectDeviceAddress = null
                        pendingBtDisconnectStopRunnable = null
                    }
                }
                handler.postDelayed(pendingBtDisconnectStopRunnable!!, BT_DISCONNECT_GRACE_MS)
                Log.i(TAG, "[BT_FLOW][$flowId] scheduled disconnect grace=${BT_DISCONNECT_GRACE_MS}ms")
            }
        )

        try {
            ContextCompat.registerReceiver(
                this,
                bluetoothReceiver,
                BluetoothAutoPlayReceiver.getIntentFilter(),
                ContextCompat.RECEIVER_EXPORTED
            )
            Log.i(TAG, "Bluetooth auto-play receiver registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register Bluetooth receiver", e)
        }
    }

    private fun continueBluetoothAutoPlay(
        flowId: String,
        device: BluetoothAutoPlayReceiver.BluetoothAudioDevice
    ) {
        // Skip reload only if app is in foreground AND WebSocket is alive
        val isInForeground = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)

        if (!isInForeground) {
            // App in background - WebSocket likely stale, reload
            Log.i(TAG, "[BT_FLOW][$flowId] app in background, reloading for auto-play")
            pendingBluetoothAutoPlayDevice = device
            Toast.makeText(this, "Connecting ${device.name}...", Toast.LENGTH_SHORT).show()
            webView.reload()
            return
        }

        webView.evaluateJavascript(
            "(window.MaWebSocket && window.MaWebSocket.isConnected()) ? 'connected' : 'disconnected'"
        ) { result ->
            if (result.contains("connected")) {
                // Foreground + WebSocket alive - select phone and play directly
                Log.i(TAG, "[BT_FLOW][$flowId] foreground + WebSocket alive, selecting phone + play")
                pendingBluetoothAutoPlayDevice = device
                selectPhoneAndPlay(device, flowId)
            } else {
                // WebSocket stale - need full reload
                Log.i(TAG, "[BT_FLOW][$flowId] WebSocket stale, reloading for auto-play")
                pendingBluetoothAutoPlayDevice = device
                Toast.makeText(this, "Connecting ${device.name}...", Toast.LENGTH_SHORT).show()
                webView.reload()
            }
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
        webviewWrapper = findViewById(R.id.webviewWrapper)
        applyPaddingForSystemBars(webviewWrapper)

        progressBar = findViewById(R.id.progress_bar)
    }

    private fun applyPaddingForSystemBars(webviewWrapper: LinearLayout) {
        // Apply window insets to handle navigation bar and status bar padding
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(webviewWrapper) { view, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())

            // Add padding to WebView to account for navigation bar and other system UI
            view.setPadding(
                systemBars.left,
                0,  // Top is handled by the AppBar positioning
                systemBars.right,
                systemBars.bottom  // This is the navigation bar height
            )

            // Consume the insets
            insets
        }
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
        // Start with WebView audio muted to prevent Chromium from stealing audio focus.
        // Will be unmuted when phone speaker playback starts.
        setWebViewAudioMuted(true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            // Security: disable file/content access (not needed for remote PWA)
            allowFileAccess = false
            allowContentAccess = false
            // Disable pinch-to-zoom - zoom is controlled via settings
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            // CRITICAL: Allow audio playback without user gesture (needed for auto-resume after reload)
            mediaPlaybackRequiresUserGesture = false
            // Custom User-Agent to bypass Google OAuth "disallowed_useragent" error
            // when using Cloudflare Access with Google authentication
            userAgentString = "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; ${Build.MODEL}) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        }

        // Note: Page zoom is applied via JavaScript in onPageFinished -> applyPageZoom()

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

                // Apply page zoom from settings
                applyPageZoom()

                // MediaSession interceptor already injected in onPageStarted
                // WebView's SendSpin handles audio, interceptor forwards metadata to Android

                // Validate this is a Music Assistant server (skip for auth pages)
                if (url != null && !isAuthPage(url)) {
                    validateMusicAssistantServer()

                    // Query current playback state after delay (wait for Vue app to initialize)
                    Handler(Looper.getMainLooper()).postDelayed({
                        queryCurrentPlaybackState()
                    }, 3000)

                    // Handle pending Bluetooth auto-play after page reload
                    // Note: We don't start polling here - onSendspinStabilized will handle it
                    if (pendingBluetoothAutoPlayDevice != null) {
                        Log.i(TAG, "Page reloaded for BT auto-play, waiting for onSendspinStabilized...")
                    }
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

                    // Cancel this request before reloading
                    handler?.cancel()

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
            "page_zoom" -> {
                applyPageZoom()
            }
        }
    }

    internal fun applyPageZoom() {
        val zoom = preferencesHelper.pageZoom
        val scale = zoom / 100.0
        // Inject JavaScript to modify viewport meta tag for reliable scaling
        webView.evaluateJavascript("""
            (function() {
                var viewport = document.querySelector('meta[name="viewport"]');
                if (!viewport) {
                    viewport = document.createElement('meta');
                    viewport.name = 'viewport';
                    document.head.appendChild(viewport);
                }
                viewport.content = 'width=device-width, initial-scale=$scale, maximum-scale=$scale, user-scalable=no';
                console.log('[MassDroid] Zoom set to ${zoom}%');
                return 'zoom: ${zoom}%';
            })();
        """.trimIndent(), null)
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
        ContextCompat.startForegroundService(this, intent)
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
     * Always targets the phone speaker via phonePlayerId, regardless of which
     * player is selected in the MA UI. Also updates MediaSession immediately
     * so notification controls stay responsive even when an external player is selected.
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

        // Always target phone speaker for native controls (notification, BT, lock screen).
        // The MA UI handles its own player selection through the WebView.
        val playerArg = phonePlayerId?.let { "'$it'" } ?: ""

        // Ensure WebView can execute JS (timers may be paused in background battery saving)
        if (command == "play") {
            webView.resumeTimers()
        }

        val script = """
            (function() {
                if (window.MaWebSocket && window.MaWebSocket.isConnected()) {
                    const result = window.MaWebSocket.$maCommand($playerArg);
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
            Log.d(TAG, "Media command '$command' (phone speaker) -> $result")
        }

        // Update MediaSession/AudioService immediately for play/pause.
        // When an external player is selected, the JS callback won't fire for
        // phone speaker state, so we update directly to keep notification in sync.
        when (command) {
            "play" -> {
                yieldedToOtherApp = false  // User wants to play - clear yield
                isCurrentlyPlaying = true
                playStartTime = SystemClock.elapsedRealtime()
                setWebViewAudioMuted(false)  // Restore if muted from yield
                updatePlaybackState(PlaybackStateCompat.STATE_PLAYING, currentPositionMs)
                pendingIsPlaying = true
                audioService?.updatePlaybackState(true)
            }
            "pause" -> {
                isCurrentlyPlaying = false
                // Don't mute here - server will stop streaming naturally via WebSocket
                // Muting is only done in yield/phone call cases for immediate silence
                updatePlaybackState(PlaybackStateCompat.STATE_PAUSED, currentPositionMs)
                pendingIsPlaying = false
                audioService?.updatePlaybackState(false)
            }
        }
    }

    private fun waitForBluetoothAudioAndPlay(
        device: BluetoothAutoPlayReceiver.BluetoothAudioDevice,
        flowId: String
    ) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        var attempts = 0
        val maxAttempts = 20  // 10 seconds max (500ms intervals)

        fun checkAndPlay() {
            attempts++
            val isBluetoothA2dpOn = audioManager.isBluetoothA2dpOn
            val isBluetoothScoOn = audioManager.isBluetoothScoOn

            Log.d(TAG, "[BT_FLOW][$flowId] audio check #$attempts A2DP=$isBluetoothA2dpOn SCO=$isBluetoothScoOn")

            if (isBluetoothA2dpOn || isBluetoothScoOn) {
                Log.i(TAG, "[BT_FLOW][$flowId] audio route ready, selecting phone + play")
                // Select phone speaker and play
                selectPhoneAndPlay(device, flowId)
            } else if (attempts < maxAttempts) {
                handler.postDelayed({ checkAndPlay() }, 500)
            } else {
                Log.w(TAG, "[BT_FLOW][$flowId] audio route not ready after ${maxAttempts * 500}ms, trying anyway")
                selectPhoneAndPlay(device, flowId)
            }
        }

        // Start checking
        handler.post { checkAndPlay() }
    }

    /**
     * Select the phone speaker (SendSpin) and start playback using stop+play sequence.
     * Uses the same approach as network reconnect auto-resume for reliability.
     */
    private fun selectPhoneAndPlay(
        device: BluetoothAutoPlayReceiver.BluetoothAudioDevice,
        flowId: String = nextBtFlowId("AUTOPLAY")
    ) {
        Log.i(TAG, "[BT_FLOW][$flowId] selecting phone speaker and starting playback for ${device.name} (${device.address})")

        // Step 1: Select phone speaker
        val selectScript = """
            (function() {
                const sendspinId = localStorage.getItem('sendspin_webplayer_id');
                if (!sendspinId) {
                    console.log('[BT-AutoPlay] No SendSpin player ID found');
                    return JSON.stringify({ success: false, error: 'no_sendspin_id' });
                }

                if (window.MaWebSocket && window.MaWebSocket.setSelectedPlayer) {
                    console.log('[BT-AutoPlay] Selecting phone speaker:', sendspinId);
                    let clickedCard = false;
                    if (window.MaWebSocket.setSelectedPlayerAndSyncUI) {
                        clickedCard = window.MaWebSocket.setSelectedPlayerAndSyncUI(sendspinId, 'Phone');
                    } else {
                        window.MaWebSocket.setSelectedPlayer(sendspinId, 'Phone');
                        localStorage.setItem('massdroid_selected_player_id', sendspinId);
                    }

                    return JSON.stringify({ success: true, player: sendspinId, clickedCard: clickedCard });
                }

                return JSON.stringify({ success: false, error: 'no_websocket' });
            })();
        """.trimIndent()

        webView.evaluateJavascript(selectScript) { selectResult ->
            Log.i(TAG, "[BT_FLOW][$flowId] select result=$selectResult")

            if (!selectResult.contains("\\\"success\\\":true")) {
                Log.w(TAG, "[BT_FLOW][$flowId] failed to select phone speaker")
                pendingBluetoothAutoPlayDevice = null
                return@evaluateJavascript
            }

            // Step 2: Stop current playback (like network reconnect does)
            handler.postDelayed({
                Log.i(TAG, "[BT_FLOW][$flowId] sending stop command")
                webView.evaluateJavascript("""
                    (function() {
                        if (window.MaWebSocket && window.MaWebSocket.isConnected()) {
                            console.log('[BT-AutoPlay] Sending stop command');
                            window.MaWebSocket.stop();
                            return 'stop_sent';
                        }
                        return 'not_connected';
                    })();
                """.trimIndent()) { stopResult ->
                    Log.i(TAG, "[BT_FLOW][$flowId] stop result=$stopResult")

                    // Step 3: Play (without player ID - uses selected player)
                    handler.postDelayed({
                        Log.i(TAG, "[BT_FLOW][$flowId] sending play command")
                        webView.evaluateJavascript("""
                            (function() {
                                if (window.MaWebSocket && window.MaWebSocket.isConnected()) {
                                    console.log('[BT-AutoPlay] Sending play command');
                                    window.MaWebSocket.play();
                                    return 'play_sent';
                                }
                                return 'not_connected';
                            })();
                        """.trimIndent()) { playResult ->
                            Log.i(TAG, "[BT_FLOW][$flowId] play result=$playResult")
                            pendingBluetoothAutoPlayDevice = null

                            if (playResult.contains("play_sent")) {
                                Toast.makeText(this, "Auto-playing: ${device.name}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }, 500)  // Wait 500ms after stop before play
                }
            }, 300)  // Small delay after select before stop
        }
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

                        // Set phone as selected player (sync internal + UI state)
                        if (window.MaWebSocket.setSelectedPlayerAndSyncUI) {
                            window.MaWebSocket.setSelectedPlayerAndSyncUI(phonePlayerId, 'Phone');
                        } else {
                            window.MaWebSocket.setSelectedPlayer(phonePlayerId, 'Phone');
                            localStorage.setItem('massdroid_selected_player_id', phonePlayerId);
                        }

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
        val playerArg = phonePlayerId?.let { ", '$it'" } ?: ""

        val script = """
            (function() {
                if (window.MaWebSocket && window.MaWebSocket.isConnected()) {
                    const result = window.MaWebSocket.seek($positionSec$playerArg);
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
            Log.d(TAG, "Seek command (phone speaker) -> $result")
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
     * Monitor other apps' audio playback via AudioPlaybackCallback (standard Android API).
     * Handles two cases:
     * 1. Media apps (Deezer, YouTube etc.) — yield and stay paused
     * 2. Voice calls (phone, WhatsApp, Teams) — pause and auto-resume when call ends
     */
    // Track last logged values to avoid flooding logcat (callback fires hundreds of times/sec during playback)
    private var lastLoggedMediaCount = -1
    private var lastLoggedVoiceCall = false

    private fun registerAudioPlaybackCallback() {
        audioPlaybackCallback = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
                val mediaCount = configs?.count {
                    it.audioAttributes.usage == AudioAttributes.USAGE_MEDIA
                } ?: 0

                val hasVoiceCall = configs?.any {
                    it.audioAttributes.usage == AudioAttributes.USAGE_VOICE_COMMUNICATION ||
                    it.audioAttributes.usage == AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING
                } ?: false
                val autoResumeSensitiveWindow =
                    ::networkAutoResume.isInitialized &&
                        (networkAutoResume.waitingForStreamStart || networkAutoResume.isRecoveryWindowActive())

                // Only log when something meaningful changes
                if (mediaCount != lastLoggedMediaCount || hasVoiceCall != lastLoggedVoiceCall) {
                    lastLoggedMediaCount = mediaCount
                    lastLoggedVoiceCall = hasVoiceCall
                    Log.d(TAG, "AudioPlayback: mediaCount=$mediaCount, hasVoiceCall=$hasVoiceCall, isPlaying=$isCurrentlyPlaying")
                }

                // Voice call detection: pause on call start, resume on call end (with debounce)
                if (hasVoiceCall && !pausedDueToVoiceCall) {
                    // Cancel any pending "call ended" resume
                    voiceCallEndRunnable?.let { handler.removeCallbacks(it) }
                    voiceCallEndRunnable = null

                    val wasPlaying = isCurrentlyPlaying
                    val recentlyPaused = SystemClock.elapsedRealtime() - lastPauseTime < 3000
                    if (wasPlaying || recentlyPaused) {
                        Log.i(TAG, "Voice call detected - pausing (wasPlaying=$wasPlaying, recentlyPaused=$recentlyPaused)")
                        pausedDueToVoiceCall = true
                        runOnUiThread {
                            setWebViewAudioMuted(true)
                            if (wasPlaying) executeMediaCommand("pause")
                        }
                    }
                } else if (hasVoiceCall && pausedDueToVoiceCall) {
                    // Still in call — cancel any pending resume (voice config may flicker)
                    voiceCallEndRunnable?.let { handler.removeCallbacks(it) }
                    voiceCallEndRunnable = null
                } else if (!hasVoiceCall && pausedDueToVoiceCall && voiceCallEndRunnable == null) {
                    // Voice config disappeared — debounce 3s before resuming
                    // (voice configs flicker during calls, don't resume prematurely)
                    Log.d(TAG, "Voice call config gone - waiting 3s before resume")
                    voiceCallEndRunnable = object : Runnable {
                        override fun run() {
                            if (!pausedDueToVoiceCall) {
                                voiceCallEndRunnable = null
                                return
                            }
                            // Check AudioManager mode — telecom calls keep mode != NORMAL
                            // even after USAGE_VOICE_COMMUNICATION disappears from configs
                            val mode = audioManager.mode
                            if (mode == AudioManager.MODE_IN_CALL ||
                                mode == AudioManager.MODE_IN_COMMUNICATION ||
                                mode == AudioManager.MODE_RINGTONE) {
                                Log.d(TAG, "Voice call still active (audioManager.mode=$mode) - rechecking in 2s")
                                handler.postDelayed(this, 2000)
                                return
                            }
                            voiceCallEndRunnable = null
                            pausedDueToVoiceCall = false
                            Log.i(TAG, "Voice call ended (confirmed, mode=$mode) - resuming playback")
                            executeMediaCommand("play")
                        }
                    }
                    handler.postDelayed(voiceCallEndRunnable!!, 3000)
                }

                // Media app detection: yield when another media app starts
                val pastGrace = SystemClock.elapsedRealtime() - playStartTime > 3000
                if (isCurrentlyPlaying && pastGrace && mediaCount > lastKnownMediaConfigCount) {
                    if (autoResumeSensitiveWindow) {
                        Log.d(
                            TAG,
                            "Suppressing external-yield during auto-resume window (mediaCount=$mediaCount baseline=$lastKnownMediaConfigCount)"
                        )
                        lastKnownMediaConfigCount = mediaCount
                        return
                    }
                    Log.i(TAG, "Other media app detected ($lastKnownMediaConfigCount -> $mediaCount configs) - yielding")
                    yieldedToOtherApp = true
                    runOnUiThread {
                        setWebViewAudioMuted(true)
                        executeMediaCommand("pause")
                    }
                }

                // Don't lower baseline while playing — audio system may briefly drop configs
                // during reconfiguration (e.g. phone call starting), causing false 0→1 yields
                if (!isCurrentlyPlaying || mediaCount >= lastKnownMediaConfigCount) {
                    lastKnownMediaConfigCount = mediaCount
                }
            }
        }
        audioManager.registerAudioPlaybackCallback(audioPlaybackCallback!!, handler)
        Log.d(TAG, "AudioPlaybackCallback registered")
    }

    private fun unregisterAudioPlaybackCallback() {
        audioPlaybackCallback?.let { audioManager.unregisterAudioPlaybackCallback(it) }
        audioPlaybackCallback = null
    }

    /**
     * Mute/unmute WebView audio to control Chromium's internal AudioFocusDelegate.
     * When muted, Chromium releases its audio focus so other apps can play.
     */
    internal fun setWebViewAudioMuted(muted: Boolean) {
        try {
            if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.MUTE_AUDIO)) {
                androidx.webkit.WebViewCompat.setAudioMuted(webView, muted)
                webViewAudioMuted = muted
                Log.d(TAG, "WebView audio ${if (muted) "muted" else "unmuted"}")
            } else {
                Log.w(TAG, "WebView audio muting not supported")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting WebView audio muted=$muted", e)
        }
    }

    /**
     * Auto-select phone speaker ("This Device") in MA web UI when it's the active player.
     * Clicks the player card programmatically so the UI shows the correct player.
     */
    private fun selectPhoneSpeakerInUI() {
        val playerId = phonePlayerId ?: return

        handler.postDelayed({
            // Ask the MA server if the phone speaker is actively playing,
            // and if so, switch our internal tracking to it
            webView.evaluateJavascript("""
                (function() {
                    var playerId = '$playerId';
                    if (!window.MaWebSocket || !window.MaWebSocket.isConnected()) {
                        return JSON.stringify({status: 'no_connection'});
                    }

                    var current = window.MaWebSocket._selectedPlayerId;
                    if (current === playerId) {
                        return JSON.stringify({status: 'already_selected'});
                    }

                    // Query MA server for the phone speaker's actual state
                    window.MaWebSocket.sendCommand('players/all', {}).then(function(players) {
                        if (!Array.isArray(players)) return;
                        var phone = players.find(function(p) { return p.player_id === playerId; });
                        if (!phone || phone.playback_state !== 'playing') {
                            console.log('[AutoSelect] Phone speaker state: ' + (phone ? phone.playback_state : 'not_found'));
                            return;
                        }

                        // Phone speaker IS playing — switch tracking + UI
                        var name = phone.display_name || 'This Device';
                        console.log('[AutoSelect] Switching to: ' + name);

                        // Update internal tracking + persist + sync MA frontend selection UI
                        if (window.MaWebSocket.setSelectedPlayerAndSyncUI) {
                            window.MaWebSocket.setSelectedPlayerAndSyncUI(playerId, name);
                        } else {
                            window.MaWebSocket.setSelectedPlayer(playerId, name);
                            localStorage.setItem('massdroid_selected_player_id', playerId);
                            localStorage.setItem('massdroid_selected_player_name', name);
                        }
                    });

                    return JSON.stringify({status: 'checking', currentSelection: current});
                })();
            """.trimIndent(), null)
        }, 1500)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        webView.resumeTimers()  // Always resume timers when app comes to foreground
        preferencesHelper.registerOnChangeListener(this)
        // Update keep screen on state in case it changed in settings
        applyKeepScreenOnSetting()
        // Apply page zoom in case it changed in settings
        applyPageZoom()
        // Update drawer header with current URL
        updateDrawerHeader()

        // Check if WebSocket connection is lost and reload if needed
        checkAndReconnectIfNeeded()

        // Auto-select phone speaker in MA UI if it's the active player
        selectPhoneSpeakerInUI()

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
        // Only pause timers if NOT playing - saves battery when idle in background
        // When playing, we need timers for audio streaming
        if (!isCurrentlyPlaying) {
            webView.pauseTimers()
            Log.d(TAG, "WebView timers paused (not playing)")
        }
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

                // Fallback: if no selection yet, DON'T assume phone (safer for disconnect handling)
                console.log('[AutoResume] No selection info available');
                return false;
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
     * Checks if the phone player is actually in "playing" state based on MaWebSocket runtime state.
     * This avoids false positives from stale Kotlin-side isCurrentlyPlaying state.
     */
    private fun checkIfPhoneActuallyPlaying(callback: (Boolean) -> Unit) {
        val checkScript = """
            (function() {
                const sendspinId = localStorage.getItem('sendspin_webplayer_id');
                if (!sendspinId || !window.MaWebSocket) return false;
                const currentPlaying = window.MaWebSocket._currentlyPlayingId;
                return currentPlaying && currentPlaying === sendspinId;
            })();
        """.trimIndent()

        runOnUiThread {
            webView.evaluateJavascript(checkScript) { result ->
                val isActuallyPlaying = result == "true"
                Log.d(TAG, "checkIfPhoneActuallyPlaying: $isActuallyPlaying")
                callback(isActuallyPlaying)
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
//            SendSpinDebug.logConnection(url, label)
        }

        @JavascriptInterface
        fun logWsDisconnection(label: String, code: Int, reason: String) {
//            SendSpinDebug.logDisconnection(label, code, reason)
        }

        @JavascriptInterface
        fun logWsMessage(source: String, msgType: String, payload: String) {
//            SendSpinDebug.logMessage(source, msgType, payload)
        }

        @JavascriptInterface
        fun dumpDebugState() {
            val activity = activityRef.get() ?: return
//            SendSpinDebug.dumpState(activity.webView)
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
                // Only update MediaSession/notification for phone speaker
                if (!activity.isPhonePlayerSelected()) return@runOnUiThread

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
                activity.networkAutoResume.onPlaybackStateUpdate(isPlaying)
                val autoResumeSensitiveWindow =
                    (activity.networkAutoResume.waitingForStreamStart || activity.networkAutoResume.isRecoveryWindowActive())

                // External player selected: don't update MediaSession, don't mute.
                // The phone speaker may still be playing in the background while
                // the user controls another player (e.g. Sonos) from the MA UI.
                if (!activity.isPhonePlayerSelected()) {
                    Log.d(TAG, "External player selected - preserving phone speaker state")
                    return@runOnUiThread
                }

                // If we yielded to another app, ignore server "playing" updates
                // until the server confirms our pause (prevents unmuting loop)
                if (isPlaying && activity.yieldedToOtherApp) {
                    if (autoResumeSensitiveWindow) {
                        Log.i(TAG, "Clearing yieldedToOtherApp during auto-resume playback confirmation")
                        activity.yieldedToOtherApp = false
                    } else {
                        Log.d(TAG, "Ignoring server 'playing' - yielded to other app")
                        return@runOnUiThread
                    }
                }
                if (!isPlaying) {
                    activity.yieldedToOtherApp = false  // Server confirmed pause
                }

                // Phone speaker: track state and update MediaSession
                val wasPlaying = activity.isCurrentlyPlaying
                activity.isCurrentlyPlaying = isPlaying

                if (isPlaying) {
                    val resumedFlowId = activity.networkAutoResume.onPlaybackConfirmedPlaying(positionMs)
                    if (resumedFlowId != null) {
                        Log.i(TAG, "[NET_RETRY][$resumedFlowId] playback state confirmed playing, auto-resume successful")
                    }
                }

                // Track transitions for grace period and phone call detection
                if (isPlaying && !wasPlaying) {
                    activity.playStartTime = SystemClock.elapsedRealtime()
                }
                if (!isPlaying && wasPlaying) {
                    activity.lastPauseTime = SystemClock.elapsedRealtime()
                }

                // Unmute on play transition (needed when user starts from MA web UI).
                // Do NOT mute on pause — server stops streaming naturally, avoids audio clicks.
                if (isPlaying && (!wasPlaying || activity.webViewAudioMuted)) {
                    activity.setWebViewAudioMuted(false)
                }

                val playbackState = if (isPlaying) {
                    PlaybackStateCompat.STATE_PLAYING
                } else {
                    PlaybackStateCompat.STATE_PAUSED
                }

                if (positionMs > 0) {
                    activity.currentPositionMs = positionMs
                }

                Log.d(TAG, "Playback state: isPlaying=$isPlaying, position=${activity.currentPositionMs}ms")

                activity.updatePlaybackState(playbackState, activity.currentPositionMs)
                activity.pendingIsPlaying = isPlaying

                if (activity.audioServiceBound && activity.audioService != null) {
                    activity.audioService?.updatePlaybackState(isPlaying)
                }
            }
        }

        @JavascriptInterface
        fun setArtworkBase64(base64Data: String) {
            val activity = activityRef.get() ?: return
            if (!activity.isPhonePlayerSelected()) return

            Log.i(TAG, "setArtworkBase64() called, data length: ${base64Data.length}")

            activity.backgroundScope.launch(Dispatchers.IO) {
                try {
                    val decodedBytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)

                    // Decode with bounds check to prevent OOM on large artwork
                    val maxSize = 512
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size, options)

                    // Calculate sample size for downsampling
                    var sampleSize = 1
                    if (options.outHeight > maxSize || options.outWidth > maxSize) {
                        val halfH = options.outHeight / 2
                        val halfW = options.outWidth / 2
                        while (halfH / sampleSize >= maxSize && halfW / sampleSize >= maxSize) {
                            sampleSize *= 2
                        }
                    }

                    val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                    val artwork = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size, decodeOptions)

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
            Log.i(TAG, "  wasPlayingBeforeNetworkLoss: ${activity.networkAutoResume.wasPlayingBeforeNetworkLoss}")
            Log.i(TAG, "  autoResumeEnabled: ${activity.preferencesHelper.autoResumeOnNetwork}")
            Log.i(TAG, "========================================")

            if (!isConnected) {
                Log.w(TAG, "Stabilized but not connected - waiting for SendSpin to connect...")
                // DON'T clear pendingBluetoothAutoPlayDevice - we need it for when SendSpin connects
                // The next stabilization event (after SendSpin connects) will handle it
                return
            }

            // Handle pending Bluetooth auto-play (triggered by BT connect + reload)
            val btDevice = activity.pendingBluetoothAutoPlayDevice
            if (btDevice != null) {
                val btFlowId = activity.nextBtFlowId("RESUME")
                Log.i(TAG, "[BT_FLOW][$btFlowId] triggering pending BT auto-play for ${btDevice.name} (${btDevice.address})")
                // DON'T clear pendingBluetoothAutoPlayDevice here - let selectPhoneAndPlay clear it
                // This protects against duplicate BT profile events during selectPhoneAndPlay
                activity.runOnUiThread {
                    activity.selectPhoneAndPlay(btDevice, btFlowId)
                }
                return
            }

            if (!activity.networkAutoResume.wasPlayingBeforeNetworkLoss) {
                Log.i(TAG, "Was not playing before network loss - no auto-resume needed")
                return
            }

            if (!activity.networkAutoResume.isRecoveryWindowActive()) {
                Log.i(TAG, "Auto-resume recovery window expired - skipping")
                activity.networkAutoResume.clearRecoveryState()
                return
            }

            if (!activity.preferencesHelper.autoResumeOnNetwork) {
                Log.i(TAG, "Auto-resume disabled in settings - skipping")
                activity.networkAutoResume.clearRecoveryState()
                return
            }

            // Check if phone speaker is selected before auto-resume
            activity.checkIfPhoneIsActivePlayer { isPhoneSelected ->
                if (!isPhoneSelected) {
                    Log.i(TAG, "Phone not selected - skipping auto-resume (external speaker in use)")
                    activity.networkAutoResume.clearRecoveryState()
                    return@checkIfPhoneIsActivePlayer
                }

                activity.networkAutoResume.startOrContinueFromStabilized()
            }
        }

        @JavascriptInterface
        fun onSendspinStreamStart() {
            val activity = activityRef.get() ?: return

            Log.i(TAG, "========================================")
            Log.i(TAG, "SendSpin stream/start - AUDIO IS PLAYING!")
            Log.i(TAG, "waitingForStreamStart: ${activity.networkAutoResume.waitingForStreamStart}")
            Log.i(TAG, "========================================")

            activity.runOnUiThread {
                val flowId = activity.networkAutoResume.onStreamStart()
                if (flowId != null) {
                    Log.i(TAG, "[NET_RETRY][$flowId] stream/start received, auto-resume successful")
                }
            }
        }


        @JavascriptInterface
        fun onSendspinSeek() {
            val activity = activityRef.get() ?: return
            Log.d(TAG, "SendSpin stream/clear (seek detected)")
            // Reset position - next position update from server will have the correct value
            activity.currentPositionMs = 0L
        }

        @JavascriptInterface
        fun onSendspinDisconnected() {
            val activity = activityRef.get() ?: return
            Log.i(TAG, "========================================")
            Log.i(TAG, "SendSpin disconnected")
            Log.i(TAG, "========================================")
            activity.runOnUiThread {
                val recoveryRetryArmed = activity.networkAutoResume.onSendspinDisconnected()
                if (!recoveryRetryArmed) return@runOnUiThread

                Log.i(TAG, "[NET_RETRY] sendspin disconnected during recovery - forcing temporary paused/muted state")
                activity.isCurrentlyPlaying = false
                activity.pendingIsPlaying = false
                activity.lastPauseTime = SystemClock.elapsedRealtime()
                activity.updatePlaybackState(PlaybackStateCompat.STATE_PAUSED, activity.currentPositionMs)
                if (activity.audioServiceBound && activity.audioService != null) {
                    activity.audioService?.updatePlaybackState(false)
                }
                activity.setWebViewAudioMuted(true)
            }
        }

        @JavascriptInterface
        fun onPlayFailed() {
            val activity = activityRef.get() ?: return

            Log.w(TAG, "[NET_RETRY][${activity.networkAutoResume.activeFlowId ?: "none"}] play failed - auto-resume timed out")
            activity.runOnUiThread {
                activity.networkAutoResume.onPlayFailed()
            }
        }

        @JavascriptInterface
        fun updatePositionState(durationMs: Long, positionMs: Long, playbackRate: Float) {
            val activity = activityRef.get() ?: return
            if (!activity.isPhonePlayerSelected()) return

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

        @JavascriptInterface
        fun setPhonePlayerId(playerId: String) {
            val activity = activityRef.get() ?: return
            Log.i(TAG, "Phone player ID set: $playerId")
            activity.phonePlayerId = playerId
            // Persist so it's available immediately on next launch
            activity.preferencesHelper.phonePlayerId = playerId
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        // Stop monitoring other apps' playback
        unregisterAudioPlaybackCallback()

        // Note: lifecycleScope automatically cancels when Activity is destroyed

        // Remove all pending handler callbacks
        networkAutoResume.onDestroy()
        handler.removeCallbacksAndMessages(null)
        pendingBtDisconnectStopRunnable = null
        pendingBtDisconnectDeviceAddress = null

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

        // Destroy WebView to release native resources
        try {
            webView.removeJavascriptInterface("AndroidMediaSession")
            webView.stopLoading()
            (webView.parent as? android.view.ViewGroup)?.removeView(webView)
            webView.destroy()
            Log.d(TAG, "WebView destroyed")
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying WebView", e)
        }

        Log.d(TAG, "Media components cleaned up")
    }
}
