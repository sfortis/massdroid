package net.asksakis.massdroid

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.ComponentName
import android.media.AudioManager
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
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import java.security.PrivateKey
import java.security.cert.X509Certificate
import android.widget.Toast
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.navigation.NavigationView
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.asksakis.massdroid.R

class MainActivity : AppCompatActivity(),
    NavigationView.OnNavigationItemSelectedListener,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView
    private lateinit var toolbar: MaterialToolbar
    private lateinit var webView: WebView
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var preferencesHelper: PreferencesHelper

    // Media components
    private var mediaSession: MediaSessionCompat? = null
    private var audioService: AudioService? = null
    private var audioServiceBound = false
    private var musicAssistantBridge: MusicAssistantBridge? = null
    private val handler = Handler(Looper.getMainLooper())

    // Position state tracking
    private var currentDurationMs: Long = 0
    private var currentPositionMs: Long = 0
    private var currentPlaybackRate: Float = 1.0f
    @Volatile
    private var isCurrentlyPlaying = false  // Track playback state ourselves

    // Bluetooth auto-play
    private var bluetoothReceiver: BluetoothAutoPlayReceiver? = null
    @Volatile
    private var webViewReady = false

    // Network change monitoring
    private var networkMonitor: NetworkChangeMonitor? = null

    // Client certificate alias for mTLS
    private var clientCertAlias: String? = null

    // Track URL for detecting changes after settings
    private var urlBeforeSettings: String = ""
    private var colorBeforePause: String = ""
    @Volatile
    private var wasPlayingBeforeNetworkLoss = false
    private var savedPositionMs: Long = 0  // Position saved at moment of network loss
    private var savedDurationMs: Long = 0  // Duration saved at moment of network loss
    @Volatile
    private var waitingForStreamStart = false  // True while waiting for stream/start confirmation
    private var streamStartCheckId = 0  // Counter for obsolete timeout checks (main thread only)
    private var playRetryCount = 0
    private val MAX_PLAY_RETRIES = 3
    private val STREAM_START_TIMEOUT_MS = 5000L  // Wait 5 seconds for stream/start before calling play()

    // Coroutine scope for background operations (artwork decoding, etc.)
    private val backgroundScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Track current artwork bitmap to recycle on replacement
    private var currentArtworkBitmap: Bitmap? = null

    companion object {
        private const val TAG = "MainActivity"
        private const val BLUETOOTH_PERMISSION_REQUEST = 100
        private const val NOTIFICATION_PERMISSION_REQUEST = 101
    }

    // AudioService connection
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as AudioService.LocalBinder
            audioService = binder.getService()
            audioServiceBound = true

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
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            audioService = null
            audioServiceBound = false
            Log.d(TAG, "AudioService disconnected")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeHelper.applyTheme(this)
        setContentView(R.layout.activity_main)

        // Initialize preferences helper
        preferencesHelper = PreferencesHelper(this)

        // Setup views
        setupViews()
        setupToolbar()
        setupNavigationDrawer()
        setupWebView()

        // Check notification permission for Android 13+
        checkNotificationPermission()

        // Setup media components
        setupMediaSession()
        startAudioService()

        // Apply settings
        applyKeepScreenOnSetting()

        // Setup Bluetooth auto-play
        setupBluetoothAutoPlay()

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
                Log.i(TAG, "Network lost, isCurrentlyPlaying=$isCurrentlyPlaying")

                // Track playback state - will be used by onSendspinReady() when WebSocket reconnects
                // Only if the phone is the active player (not controlling external speakers)
                if (isCurrentlyPlaying) {
                    checkIfPhoneIsActivePlayer { isPhonePlayer ->
                        if (isPhonePlayer) {
                            wasPlayingBeforeNetworkLoss = true
                            Log.i(TAG, "Phone was playing before network loss - will auto-resume when SendSpin reconnects")
                        } else {
                            Log.i(TAG, "External speaker was active - skipping auto-resume")
                        }
                    }
                }
            }

            override fun onNetworkAvailable() {
                Log.i(TAG, "Network available - waiting for SendSpin to reconnect via WebSocket")
                // Auto-resume is now handled by onSendspinReady() when WebSocket receives server/hello
            }
        })

        networkMonitor?.start()
    }

    /**
     * Check and request POST_NOTIFICATIONS permission for Android 13+ (API 33+)
     * Required for foreground service notifications to be visible.
     */
    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "Requesting POST_NOTIFICATIONS permission")
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_REQUEST
                )
            } else {
                Log.d(TAG, "POST_NOTIFICATIONS permission already granted")
            }
        }
    }

    private fun setupBluetoothAutoPlay() {
        // Request Bluetooth permission on Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                    BLUETOOTH_PERMISSION_REQUEST
                )
                return
            }
        }

        registerBluetoothReceiver()
    }

    private fun registerBluetoothReceiver() {
        if (bluetoothReceiver != null) return

        bluetoothReceiver = BluetoothAutoPlayReceiver { deviceName ->
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
        }

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

        if (requestCode == BLUETOOTH_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "Bluetooth permission granted")
                registerBluetoothReceiver()
            } else {
                Log.w(TAG, "Bluetooth permission denied - auto-play won't work")
                Toast.makeText(this, "Bluetooth permission required for auto-play", Toast.LENGTH_LONG).show()
            }
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
            // Custom User-Agent to bypass Google OAuth "disallowed_useragent" error
            // when using Cloudflare Access with Google authentication
            userAgentString = "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; ${Build.MODEL}) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        }

        // Add JavaScript interface for media metadata
        webView.addJavascriptInterface(MediaMetadataInterface(), "AndroidMediaSession")
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
        backgroundScope.launch {
            try {
                val privateKey: PrivateKey? = KeyChain.getPrivateKey(this@MainActivity, alias)
                val certificateChain: Array<X509Certificate>? = KeyChain.getCertificateChain(this@MainActivity, alias)

                if (privateKey != null && certificateChain != null) {
                    Log.i(TAG, "Providing client certificate: $alias")
                    request?.proceed(privateKey, certificateChain)
                } else {
                    Log.e(TAG, "Failed to get certificate or private key")
                    request?.cancel()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error providing client certificate", e)
                request?.cancel()
            }
        }
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


    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            drawerLayout.isDrawerOpen(GravityCompat.START) -> {
                drawerLayout.closeDrawer(GravityCompat.START)
            }
            webView.canGoBack() -> {
                webView.goBack()
            }
            else -> {
                super.onBackPressed()
            }
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

    private fun startAudioService() {
        val intent = Intent(this, AudioService::class.java)
        startService(intent)
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)
        Log.d(TAG, "AudioService started and binding")
    }

    private fun injectMediaSessionPolyfill() {
        val polyfillScript = """
            (function() {
                console.log('[MediaSessionInterceptor] ========================================');
                console.log('[MediaSessionInterceptor] Installing MediaSession interceptors...');
                console.log('[MediaSessionInterceptor] ========================================');

                // Debounce helper to prevent notification flickering
                const debounce = (func, wait) => {
                    let timeout;
                    return function executedFunction(...args) {
                        const later = () => {
                            clearTimeout(timeout);
                            func(...args);
                        };
                        clearTimeout(timeout);
                        timeout = setTimeout(later, wait);
                    };
                };

                // Store current position state
                window._mediaPositionState = { duration: 0, position: 0, playbackRate: 1 };

                // CRITICAL: Create MediaMetadata class FIRST, before PWA tries to use it
                if (typeof window.MediaMetadata === 'undefined') {
                    console.log('[MediaSessionPolyfill] Creating MediaMetadata class...');
                    window.MediaMetadata = class MediaMetadata {
                        constructor(metadata = {}) {
                            this.title = metadata.title || '';
                            this.artist = metadata.artist || '';
                            this.album = metadata.album || '';
                            this.artwork = metadata.artwork || [];
                        }
                    };
                    console.log('[MediaSessionPolyfill] MediaMetadata class created');
                } else {
                    console.log('[MediaSessionPolyfill] MediaMetadata already exists');
                }

                // Check if MediaSession API exists
                if (typeof navigator.mediaSession === 'undefined') {
                    console.log('[MediaSessionInterceptor] navigator.mediaSession not available, creating polyfill...');
                    navigator.mediaSession = {
                        metadata: null,
                        playbackState: 'none',
                        setActionHandler: function(action, handler) {},
                        setPositionState: function(state) {}
                    };
                }
                console.log('[MediaSessionInterceptor] MediaSession API available');

                // Store original descriptor for metadata property
                const originalMetadataDescriptor = Object.getOwnPropertyDescriptor(navigator.mediaSession, 'metadata') || {};
                const originalGetter = originalMetadataDescriptor.get || function() { return this._metadata; };
                const originalSetter = originalMetadataDescriptor.set || function(value) { this._metadata = value; };

                // Fetch artwork and convert to base64
                const fetchArtworkBase64 = (artworkUrl) => {
                    if (!artworkUrl || !window.AndroidMediaSession) return;

                    fetch(artworkUrl)
                        .then(response => {
                            if (!response.ok) throw new Error('HTTP ' + response.status);
                            return response.blob();
                        })
                        .then(blob => {
                            const reader = new FileReader();
                            reader.onloadend = () => {
                                const base64 = reader.result.split(',')[1];
                                if (base64) {
                                    window.AndroidMediaSession.setArtworkBase64(base64);
                                    console.log('[MediaSessionInterceptor] Artwork sent as base64');
                                }
                            };
                            reader.readAsDataURL(blob);
                        })
                        .catch(err => console.warn('[MediaSessionInterceptor] Artwork fetch failed:', err));
                };

                // Debounced metadata update
                const debouncedMetadataUpdate = debounce((title, artist, album, artwork, duration) => {
                    if (window.AndroidMediaSession) {
                        window.AndroidMediaSession.updateMetadata(title, artist, album, artwork, duration);
                        console.log('[MediaSessionInterceptor] Metadata forwarded (debounced)');

                        // Fetch artwork via JavaScript (has cookies/auth)
                        if (artwork) {
                            fetchArtworkBase64(artwork);
                        }
                    }
                }, 300);

                // Intercept metadata setter
                Object.defineProperty(navigator.mediaSession, 'metadata', {
                    get: function() {
                        return originalGetter.call(this);
                    },
                    set: function(value) {
                        if (value) {
                            const artwork = value.artwork?.[2]?.src || value.artwork?.[1]?.src || value.artwork?.[0]?.src || '';
                            const duration = window._mediaPositionState.duration || 0;
                            debouncedMetadataUpdate(
                                value.title || 'Unknown',
                                value.artist || 'Unknown',
                                value.album || '',
                                artwork,
                                Math.round(duration * 1000)
                            );
                        }
                        originalSetter.call(this, value);
                    },
                    configurable: true,
                    enumerable: true
                });

                // Store original playbackState descriptor
                const originalPlaybackStateDescriptor = Object.getOwnPropertyDescriptor(navigator.mediaSession, 'playbackState') || {};
                const originalPlaybackStateGetter = originalPlaybackStateDescriptor.get || function() { return this._playbackState || 'none'; };
                const originalPlaybackStateSetter = originalPlaybackStateDescriptor.set || function(value) { this._playbackState = value; };

                // Debounced playback state update
                const debouncedPlaybackUpdate = debounce((state, position) => {
                    if (window.AndroidMediaSession) {
                        window.AndroidMediaSession.updatePlaybackState(state, position);
                        console.log('[MediaSessionInterceptor] Playback state forwarded (debounced):', state);
                    }
                }, 200);

                // Intercept playbackState setter
                Object.defineProperty(navigator.mediaSession, 'playbackState', {
                    get: function() {
                        return originalPlaybackStateGetter.call(this);
                    },
                    set: function(value) {
                        const position = Math.round((window._mediaPositionState.position || 0) * 1000);
                        debouncedPlaybackUpdate(value, position);
                        originalPlaybackStateSetter.call(this, value);
                    },
                    configurable: true,
                    enumerable: true
                });

                // Intercept setPositionState to capture duration and position
                const originalSetPositionState = navigator.mediaSession.setPositionState?.bind(navigator.mediaSession);
                navigator.mediaSession.setPositionState = function(state) {
                    if (state) {
                        window._mediaPositionState = {
                            duration: state.duration || 0,
                            position: state.position || 0,
                            playbackRate: state.playbackRate || 1
                        };

                        // Update Android with position info
                        if (window.AndroidMediaSession) {
                            const durationMs = Math.round((state.duration || 0) * 1000);
                            const positionMs = Math.round((state.position || 0) * 1000);
                            window.AndroidMediaSession.updatePositionState(durationMs, positionMs, state.playbackRate || 1);
                        }
                    }
                    if (originalSetPositionState) {
                        return originalSetPositionState(state);
                    }
                };

                // Intercept setActionHandler to capture handlers
                const originalSetActionHandler = navigator.mediaSession.setActionHandler?.bind(navigator.mediaSession);
                const handlers = {};
                navigator.mediaSession.setActionHandler = function(action, handler) {
                    handlers[action] = handler;
                    if (originalSetActionHandler) {
                        return originalSetActionHandler(action, handler);
                    }
                };

                // Create window.musicPlayer interface
                window.musicPlayer = {
                    play: function() {
                        const handler = handlers['play'];
                        if (handler) handler();
                    },
                    pause: function() {
                        const handler = handlers['pause'];
                        if (handler) handler();
                    },
                    next: function() {
                        const handler = handlers['nexttrack'];
                        if (handler) handler();
                    },
                    previous: function() {
                        const handler = handlers['previoustrack'];
                        if (handler) handler();
                    },
                    seekTo: function(positionSec) {
                        const handler = handlers['seekto'];
                        if (handler) {
                            handler({ seekTime: positionSec });
                        }
                    },
                    _getHandlers: function() {
                        return Object.keys(handlers);
                    }
                };

                console.log('[MediaSessionInterceptor] Interceptor installed successfully');

                // === WEBSOCKET INTERCEPTION FOR SENDSPIN ===
                // Intercept WebSocket to detect SendSpin protocol messages
                const OriginalWebSocket = window.WebSocket;
                window.WebSocket = function(url, protocols) {
                    const ws = protocols ? new OriginalWebSocket(url, protocols) : new OriginalWebSocket(url);
                    const urlStr = url.toString();

                    if (urlStr.includes('/sendspin')) {
                        console.log('[WebSocketInterceptor] Intercepting SendSpin WebSocket:', urlStr);

                        ws.addEventListener('message', function(event) {
                            try {
                                // Only parse text messages (not binary audio data)
                                if (typeof event.data === 'string') {
                                    const msg = JSON.parse(event.data);

                                    // server/hello = control connection ready
                                    if (msg.type === 'server/hello') {
                                        console.log('[WebSocketInterceptor] server/hello received');
                                        if (window.AndroidMediaSession && window.AndroidMediaSession.onSendspinConnected) {
                                            window.AndroidMediaSession.onSendspinConnected();
                                        }
                                    }

                                    // stream/start = audio streaming actually started
                                    if (msg.type === 'stream/start') {
                                        console.log('[WebSocketInterceptor] stream/start received - audio streaming!');
                                        if (window.AndroidMediaSession && window.AndroidMediaSession.onSendspinStreamStart) {
                                            window.AndroidMediaSession.onSendspinStreamStart();
                                        }
                                    }

                                    // stream/end = audio streaming stopped
                                    if (msg.type === 'stream/end' || msg.type === 'stream/clear') {
                                        console.log('[WebSocketInterceptor] stream ended');
                                    }
                                }
                            } catch (e) {
                                // Ignore parse errors (binary data, etc)
                            }
                        });

                        ws.addEventListener('close', function(event) {
                            console.log('[WebSocketInterceptor] SendSpin WebSocket closed:', event.code, event.reason);
                            if (window.AndroidMediaSession && window.AndroidMediaSession.onSendspinDisconnected) {
                                window.AndroidMediaSession.onSendspinDisconnected();
                            }
                        });
                    }

                    return ws;
                };
                // Preserve WebSocket constants
                window.WebSocket.CONNECTING = OriginalWebSocket.CONNECTING;
                window.WebSocket.OPEN = OriginalWebSocket.OPEN;
                window.WebSocket.CLOSING = OriginalWebSocket.CLOSING;
                window.WebSocket.CLOSED = OriginalWebSocket.CLOSED;

                console.log('[MediaSessionInterceptor] WebSocket interceptor installed');
            })();
        """.trimIndent()

        webView.evaluateJavascript(polyfillScript, null)
    }

    private fun initializeMusicAssistantBridge() {
        musicAssistantBridge = MusicAssistantBridge(webView, object : MusicAssistantBridge.BridgeCallback {
            override fun onBridgeReady() {
                Log.i(TAG, "Music Assistant bridge is ready")
            }

            override fun onBridgeError(error: String) {
                Log.e(TAG, "Music Assistant bridge error: $error")
            }

            override fun onMetadataUpdate(
                title: String,
                artist: String,
                album: String,
                artworkUrl: String,
                durationMs: Long
            ) {
                Log.d(TAG, "Metadata update: $title - $artist")
                audioService?.updateMetadata(title, artist, album, artworkUrl)
            }

            override fun onPlaybackStateUpdate(state: String, positionMs: Long) {
                Log.d(TAG, "Playback state update: $state")
                val isPlaying = state == "playing"
                audioService?.updatePlaybackState(isPlaying)
                updatePlaybackState(
                    if (isPlaying) PlaybackStateCompat.STATE_PLAYING
                    else PlaybackStateCompat.STATE_PAUSED
                )
            }
        })

        musicAssistantBridge?.initialize()
    }

    private fun executeMediaCommand(command: String) {
        val script = when (command) {
            "play" -> """
                (function() {
                    console.log('[AutoResume] Attempting to play...');
                    console.log('[AutoResume] window.musicPlayer exists:', !!window.musicPlayer);
                    if (window.musicPlayer) {
                        console.log('[AutoResume] Available handlers:', window.musicPlayer._getHandlers ? window.musicPlayer._getHandlers() : 'N/A');
                        if (window.musicPlayer.play) {
                            console.log('[AutoResume] Calling play()...');
                            window.musicPlayer.play();
                        }
                    }
                })();
            """.trimIndent()
            "pause" -> "if (window.musicPlayer && window.musicPlayer.pause) window.musicPlayer.pause();"
            "playPause" -> """
                (function() {
                    if (window.musicPlayer) {
                        const isPlaying = window.AndroidMediaSession?.playbackState === 'playing';
                        if (isPlaying && window.musicPlayer.pause) {
                            window.musicPlayer.pause();
                        } else if (window.musicPlayer.play) {
                            window.musicPlayer.play();
                        }
                    }
                })();
            """.trimIndent()
            "next" -> "if (window.musicPlayer && window.musicPlayer.next) window.musicPlayer.next();"
            "previous" -> "if (window.musicPlayer && window.musicPlayer.previous) window.musicPlayer.previous();"
            else -> return
        }

        webView.evaluateJavascript(script) { result ->
            Log.d(TAG, "Media command '$command' executed: $result")
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
                Log.i(TAG, "Bluetooth audio ready, starting playback on: $deviceName")
                executeMediaCommand("play")
                Toast.makeText(this, "Auto-playing: $deviceName", Toast.LENGTH_SHORT).show()
            } else if (attempts < maxAttempts) {
                handler.postDelayed({ checkAndPlay() }, 500)
            } else {
                Log.w(TAG, "Bluetooth audio not ready after ${maxAttempts * 500}ms, playing anyway")
                executeMediaCommand("play")
                Toast.makeText(this, "Auto-playing: $deviceName", Toast.LENGTH_SHORT).show()
            }
        }

        // Start checking
        handler.post { checkAndPlay() }
    }

    private fun startStreamStartTimeout() {
        val currentCheckId = ++streamStartCheckId

        Log.i(TAG, "Stream check #$currentCheckId: waiting ${STREAM_START_TIMEOUT_MS}ms for stream/start")
        Log.i(TAG, "Saved position for resume: ${savedPositionMs}ms / ${savedDurationMs}ms")

        handler.postDelayed({
            // Check if this timeout is still valid (not cancelled by stream/start)
            if (currentCheckId == streamStartCheckId && waitingForStreamStart) {
                playRetryCount++
                Log.i(TAG, "No stream/start received, triggering play() (attempt $playRetryCount/$MAX_PLAY_RETRIES)")

                if (playRetryCount == 1) {
                    Toast.makeText(this@MainActivity, "Resuming playback...", Toast.LENGTH_SHORT).show()
                }

                if (playRetryCount <= MAX_PLAY_RETRIES) {
                    triggerPlay(savedPositionMs, savedDurationMs)
                } else {
                    Log.e(TAG, "Max retries reached, giving up")
                    waitingForStreamStart = false
                    wasPlayingBeforeNetworkLoss = false
                    Toast.makeText(this@MainActivity, "Could not auto-resume", Toast.LENGTH_SHORT).show()
                }
            } else {
                Log.i(TAG, "Stream check #$currentCheckId cancelled (stream/start received or new check started)")
            }
        }, STREAM_START_TIMEOUT_MS)
    }

    private fun triggerPlay(resumePositionMs: Long, durationMs: Long) {
        // Validate seek position - only seek if position is reasonable
        val seekPositionMs = if (resumePositionMs > 0 && durationMs > 0 && resumePositionMs < durationMs - 1000) {
            resumePositionMs
        } else {
            0L // Don't seek if position is invalid
        }

        Log.i(TAG, "triggerPlay: position=${resumePositionMs}ms, duration=${durationMs}ms, will seek to=${seekPositionMs}ms")

        val script = """
            (function() {
                if (!window.musicPlayer || !window.musicPlayer.play) {
                    console.log('[AutoResume] musicPlayer not ready');
                    if (window.AndroidMediaSession) {
                        window.AndroidMediaSession.onPlayFailed();
                    }
                    return;
                }

                const playerId = localStorage.getItem('sendspin_webplayer_id');
                console.log('[AutoResume] Calling play(), player ID: ' + playerId);
                window.musicPlayer.play();

                // Seek to saved position after a short delay (only if valid)
                if ($seekPositionMs > 0) {
                    setTimeout(function() {
                        console.log('[AutoResume] Seeking to ${seekPositionMs}ms');
                        if (window.musicPlayer.seekTo) {
                            window.musicPlayer.seekTo(${seekPositionMs / 1000.0});
                        }
                    }, 1500);
                } else {
                    console.log('[AutoResume] No seek needed (position: $resumePositionMs, duration: $durationMs)');
                }
            })();
        """.trimIndent()

        webView.evaluateJavascript(script, null)
    }

    private fun executeSeekCommand(positionMs: Long) {
        val positionSec = positionMs / 1000.0
        val script = """
            (function() {
                if (window.musicPlayer && window.musicPlayer.seekTo) {
                    window.musicPlayer.seekTo($positionSec);
                    return 'seeked to $positionSec';
                }
                return 'seekTo not available';
            })();
        """.trimIndent()

        webView.evaluateJavascript(script) { result ->
            Log.d(TAG, "Seek command executed: $result")
        }
    }

    private fun updatePlaybackState(state: Int, positionMs: Long = currentPositionMs) {
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

    override fun onResume() {
        super.onResume()
        webView.onResume()
        preferencesHelper.registerOnChangeListener(this)
        // Update keep screen on state in case it changed in settings
        applyKeepScreenOnSetting()
        // Update drawer header with current URL
        updateDrawerHeader()

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
     * Check if the phone (SendSpin web player) is the active player.
     * Used to determine if auto-resume should trigger on network change.
     */
    private fun checkIfPhoneIsActivePlayer(callback: (Boolean) -> Unit) {
        val checkScript = """
            (function() {
                const sendspinId = localStorage.getItem('sendspin_webplayer_id');
                const activeId = localStorage.getItem('activePlayerId');
                return sendspinId && activeId && sendspinId === activeId;
            })();
        """.trimIndent()

        // WebView methods must be called on the main thread
        runOnUiThread {
            webView.evaluateJavascript(checkScript) { result ->
                val isPhonePlayer = result == "true"
                Log.d(TAG, "checkIfPhoneIsActivePlayer: sendspinId==activeId? $isPhonePlayer")
                callback(isPhonePlayer)
            }
        }
    }

    /**
     * JavaScript interface for PWA to update media metadata and playback state
     */
    inner class MediaMetadataInterface {
        @JavascriptInterface
        fun updateMetadata(title: String, artist: String, album: String, artworkUrl: String, durationMs: Long) {
            Log.i(TAG, "========================================")
            Log.i(TAG, "MediaMetadataInterface.updateMetadata() called from JavaScript")
            Log.i(TAG, "Title: $title")
            Log.i(TAG, "Artist: $artist")
            Log.i(TAG, "Album: $album")
            Log.i(TAG, "Artwork URL: $artworkUrl")
            Log.i(TAG, "Duration: ${durationMs}ms")
            Log.i(TAG, "========================================")

            runOnUiThread {
                Log.d(TAG, "Running metadata update on UI thread...")

                // Update MediaSession metadata for Bluetooth/system controls
                val metadataBuilder = MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)

                // Set metadata without artwork (artwork will be set separately via setArtworkBase64)
                if (artworkUrl.isNotEmpty()) {
                    metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, artworkUrl)
                }
                mediaSession?.setMetadata(metadataBuilder.build())
                Log.d(TAG, "MediaSession metadata updated: $title")

                // Update AudioService notification
                if (audioServiceBound && audioService != null) {
                    audioService?.updateMetadata(title, artist, album, artworkUrl)
                    Log.d(TAG, "AudioService notification updated with metadata")
                }
            }
        }

        @JavascriptInterface
        fun updatePlaybackState(state: String, positionMs: Long) {
            Log.i(TAG, "========================================")
            Log.i(TAG, "MediaMetadataInterface.updatePlaybackState() called from JavaScript")
            Log.i(TAG, "State: $state")
            Log.i(TAG, "Position: ${positionMs}ms")
            Log.i(TAG, "Thread: ${Thread.currentThread().name}")
            Log.i(TAG, "========================================")

            runOnUiThread {
                val isPlaying = state == "playing"

                // Track playback state for network change detection
                isCurrentlyPlaying = isPlaying
                // Note: auto-resume flags are now cleared by onSendspinStreamStart

                val playbackState = if (isPlaying) {
                    PlaybackStateCompat.STATE_PLAYING
                } else {
                    PlaybackStateCompat.STATE_PAUSED
                }

                // Store position if provided
                if (positionMs > 0) {
                    currentPositionMs = positionMs
                }

                Log.d(TAG, "Playback state update: isPlaying=$isPlaying, position=${currentPositionMs}ms")

                // Update MediaSession state for Bluetooth/system controls
                updatePlaybackState(playbackState, currentPositionMs)

                // Update AudioService notification - CRITICAL for icon sync
                if (audioServiceBound && audioService != null) {
                    audioService?.updatePlaybackState(isPlaying)
                } else {
                    Log.w(TAG, "AudioService not bound - cannot update notification state")
                }
            }
        }

        @JavascriptInterface
        fun setArtworkBase64(base64Data: String) {
            Log.i(TAG, "setArtworkBase64() called, data length: ${base64Data.length}")

            backgroundScope.launch {
                try {
                    val decodedBytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
                    val artwork = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)

                    if (artwork != null) {
                        withContext(Dispatchers.Main) {
                            // Recycle old bitmap to prevent memory leak
                            currentArtworkBitmap?.let { oldBitmap ->
                                if (!oldBitmap.isRecycled && oldBitmap != artwork) {
                                    try {
                                        oldBitmap.recycle()
                                        Log.d(TAG, "Recycled old artwork bitmap in MainActivity")
                                    } catch (e: IllegalStateException) {
                                        // Bitmap was already recycled by another coroutine, safe to ignore
                                        Log.d(TAG, "Bitmap already recycled")
                                    }
                                }
                            }
                            currentArtworkBitmap = artwork

                            // Update MediaSession with artwork
                            val currentMetadata = mediaSession?.controller?.metadata
                            if (currentMetadata != null) {
                                val metadataBuilder = MediaMetadataCompat.Builder(currentMetadata)
                                    .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, artwork)
                                mediaSession?.setMetadata(metadataBuilder.build())
                                Log.d(TAG, "MediaSession artwork updated via base64")
                            }

                            // Update AudioService notification
                            if (audioServiceBound && audioService != null) {
                                audioService?.setArtworkBitmap(artwork)
                                Log.d(TAG, "AudioService artwork updated via base64")
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
            Log.i(TAG, "SendSpin connected (server/hello)")
            Log.i(TAG, "wasPlayingBeforeNetworkLoss: $wasPlayingBeforeNetworkLoss")
            Log.i(TAG, "waitingForStreamStart: $waitingForStreamStart")
            Log.i(TAG, "========================================")

            runOnUiThread {
                if (wasPlayingBeforeNetworkLoss && preferencesHelper.autoResumeOnNetwork) {
                    if (!waitingForStreamStart) {
                        waitingForStreamStart = true
                        playRetryCount = 0
                        Log.i(TAG, "Waiting ${STREAM_START_TIMEOUT_MS}ms for stream/start (auto-resume by frontend)")

                        // Start timeout - if no stream/start, we'll trigger play()
                        startStreamStartTimeout()
                    } else {
                        Log.i(TAG, "Already waiting for stream/start, resetting timeout")
                        // Reset timeout on each server/hello (connection might be re-establishing)
                        startStreamStartTimeout()
                    }
                }
            }
        }

        @JavascriptInterface
        fun onSendspinStreamStart() {
            Log.i(TAG, "========================================")
            Log.i(TAG, "SendSpin stream/start - AUDIO IS PLAYING!")
            Log.i(TAG, "waitingForStreamStart: $waitingForStreamStart")
            Log.i(TAG, "========================================")

            runOnUiThread {
                if (waitingForStreamStart) {
                    Log.i(TAG, "Audio confirmed - auto-resume successful, clearing flags")
                    // Cancel any pending timeout by incrementing the check ID
                    streamStartCheckId++
                    waitingForStreamStart = false
                    wasPlayingBeforeNetworkLoss = false
                    playRetryCount = 0
                }
            }
        }


        @JavascriptInterface
        fun onSendspinDisconnected() {
            Log.i(TAG, "========================================")
            Log.i(TAG, "SendSpin disconnected")
            Log.i(TAG, "isCurrentlyPlaying: $isCurrentlyPlaying")
            Log.i(TAG, "waitingForStreamStart: $waitingForStreamStart")
            Log.i(TAG, "currentPositionMs: $currentPositionMs")
            Log.i(TAG, "========================================")

            runOnUiThread {
                // If we were playing or waiting for resume, mark for auto-resume on reconnect
                if (isCurrentlyPlaying || waitingForStreamStart) {
                    Log.i(TAG, "Will auto-resume on reconnect")
                    wasPlayingBeforeNetworkLoss = true
                    // Save position NOW at the moment of disconnect
                    savedPositionMs = currentPositionMs
                    savedDurationMs = currentDurationMs
                    Log.i(TAG, "Saved position: ${savedPositionMs}ms / ${savedDurationMs}ms")
                }
                // Note: We don't cancel the timeout here - let it expire and trigger play()
                // The reconnect will send server/hello which may reset the timeout
            }
        }

        @JavascriptInterface
        fun onPlayFailed() {
            Log.w(TAG, "Play failed - musicPlayer not ready")
            runOnUiThread {
                // Start another timeout to retry
                if (playRetryCount < MAX_PLAY_RETRIES) {
                    Log.i(TAG, "Will retry after next timeout")
                    startStreamStartTimeout()
                } else {
                    Log.e(TAG, "Max retries reached, giving up")
                    waitingForStreamStart = false
                    wasPlayingBeforeNetworkLoss = false
                    Toast.makeText(this@MainActivity, "Could not auto-resume", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun updatePositionState(durationMs: Long, positionMs: Long, playbackRate: Float) {
            Log.d(TAG, "updatePositionState: duration=${durationMs}ms, position=${positionMs}ms, rate=$playbackRate")

            runOnUiThread {
                // Store position state
                currentDurationMs = durationMs
                currentPositionMs = positionMs
                currentPlaybackRate = playbackRate

                // Update MediaSession metadata with duration if we have it
                if (durationMs > 0) {
                    mediaSession?.let { session ->
                        val currentMetadata = session.controller?.metadata
                        if (currentMetadata != null) {
                            val builder = MediaMetadataCompat.Builder(currentMetadata)
                                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)
                            session.setMetadata(builder.build())
                        }
                    }
                }

                // Update playback state with position
                val currentState = mediaSession?.controller?.playbackState?.state
                    ?: PlaybackStateCompat.STATE_NONE
                updatePlaybackState(currentState, positionMs)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        // Cancel all coroutines
        backgroundScope.cancel()

        // Remove all pending handler callbacks
        handler.removeCallbacksAndMessages(null)

        // Recycle artwork bitmap
        currentArtworkBitmap?.let { bitmap ->
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
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

        musicAssistantBridge?.reset()

        Log.d(TAG, "Media components cleaned up")
    }
}
