package net.asksakis.mass

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
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.webkit.JavascriptInterface
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
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
import net.asksakis.mass.R

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
    private var isCurrentlyPlaying = false  // Track playback state ourselves

    // Bluetooth auto-play
    private var bluetoothReceiver: BluetoothAutoPlayReceiver? = null
    private var webViewReady = false

    // Network change monitoring
    private var networkMonitor: NetworkChangeMonitor? = null
    private var wasPlayingBeforeNetworkLoss = false
    private var savedPositionMs: Long = 0  // Position saved at moment of network loss
    private var savedDurationMs: Long = 0  // Duration saved at moment of network loss
    private var waitingForStreamStart = false  // True while waiting for stream/start confirmation
    private var streamStartCheckId = 0  // Incremented to cancel obsolete checks
    private var playRetryCount = 0
    private val MAX_PLAY_RETRIES = 3
    private val STREAM_START_TIMEOUT_MS = 5000L  // Wait 5 seconds for stream/start before calling play()

    companion object {
        private const val TAG = "MainActivity"
        private const val BLUETOOTH_PERMISSION_REQUEST = 100
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
        setContentView(R.layout.activity_main)

        // Initialize preferences helper
        preferencesHelper = PreferencesHelper(this)

        // Setup views
        setupViews()
        setupToolbar()
        setupNavigationDrawer()
        setupWebView()

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
            Log.i(TAG, "Restoring WebView state from savedInstanceState")
            webView.restoreState(savedInstanceState)
        } else {
            Log.i(TAG, "No saved state, loading fresh URL")
            loadPwaUrl()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        Log.i(TAG, "Saving WebView state")
        webView.saveState(outState)
    }

    private fun setupNetworkMonitor() {
        networkMonitor = NetworkChangeMonitor(this, object : NetworkChangeMonitor.NetworkChangeListener {
            override fun onNetworkLost() {
                Log.i(TAG, "Network lost, isCurrentlyPlaying=$isCurrentlyPlaying")

                // Track playback state - will be used by onSendspinReady() when WebSocket reconnects
                // Note: SendSpin WebSocket close event (onSendspinDisconnected) may also set this
                if (isCurrentlyPlaying) {
                    wasPlayingBeforeNetworkLoss = true
                    Log.i(TAG, "Was playing before network loss - will auto-resume when SendSpin reconnects")
                }
            }

            override fun onNetworkAvailable() {
                Log.i(TAG, "Network available - waiting for SendSpin to reconnect via WebSocket")
                // Auto-resume is now handled by onSendspinReady() when WebSocket receives server/hello
            }
        })

        networkMonitor?.start()
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
        }

        // Add JavaScript interface for media metadata
        webView.addJavascriptInterface(MediaMetadataInterface(), "AndroidMediaSession")
        Log.d(TAG, "JavaScript interface 'AndroidMediaSession' registered")

        // WebViewClient for handling page navigation
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                // Load all URLs in the WebView
                return false
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

    private fun loadPwaUrl() {
        val url = preferencesHelper.pwaUrl
        webView.loadUrl(url)
    }

    private fun applyKeepScreenOnSetting() {
        if (preferencesHelper.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.nav_home -> {
                loadPwaUrl()
            }
            R.id.nav_refresh -> {
                webView.reload()
            }
            R.id.nav_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
            }
        }
        drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

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

                // Debounced metadata update
                const debouncedMetadataUpdate = debounce((title, artist, album, artwork, duration) => {
                    if (window.AndroidMediaSession) {
                        window.AndroidMediaSession.updateMetadata(title, artist, album, artwork, duration);
                        console.log('[MediaSessionInterceptor] Metadata forwarded (debounced)');
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
        streamStartCheckId++
        val currentCheckId = streamStartCheckId

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
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        preferencesHelper.unregisterOnChangeListener(this)
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

                // Add artwork URL for high-res album art
                if (artworkUrl.isNotEmpty()) {
                    metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, artworkUrl)

                    // Download and set bitmap in background for MediaSession
                    Thread {
                        try {
                            val url = URL(artworkUrl)
                            val connection = url.openConnection() as HttpURLConnection
                            connection.doInput = true
                            connection.connectTimeout = 10000
                            connection.readTimeout = 10000
                            connection.connect()
                            val input: InputStream = connection.inputStream
                            val artwork = BitmapFactory.decodeStream(input)

                            runOnUiThread {
                                metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, artwork)
                                mediaSession?.setMetadata(metadataBuilder.build())
                                Log.d(TAG, "MediaSession metadata updated with artwork: $title")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error loading artwork for MediaSession", e)
                            runOnUiThread {
                                mediaSession?.setMetadata(metadataBuilder.build())
                                Log.d(TAG, "MediaSession metadata updated without artwork: $title")
                            }
                        }
                    }.start()
                } else {
                    mediaSession?.setMetadata(metadataBuilder.build())
                    Log.d(TAG, "MediaSession metadata updated: $title")
                }

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
                    // Cancel any pending timeout
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
