package net.asksakis.massdroid

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView

/**
 * Bridge between Music Assistant PWA and Android MediaSession.
 *
 * ARCHITECTURE CHANGE (2025-01):
 * Previously, this bridge attempted to access Vue's internal API via __vue_app__ to monitor
 * player state changes. This was fragile and required polling for API initialization.
 *
 * NEW APPROACH:
 * Music Assistant already uses the standard MediaSession API (navigator.mediaSession).
 * Instead of accessing Vue internals, we intercept MediaSession API calls:
 * - Intercept navigator.mediaSession.metadata setter to capture track info
 * - Intercept navigator.mediaSession.setActionHandler() to capture media controls
 * - Create window.musicPlayer interface that calls the captured handlers
 *
 * This bridge is now minimal - the real work happens in the MediaSession interceptor
 * injected by MainActivity.injectMediaSessionPolyfill() during onPageStarted().
 *
 * FUTURE: This class can be deprecated once the interceptor approach proves stable.
 * The callbacks remain for backwards compatibility with MainActivity.
 */
class MusicAssistantBridge(
    private val webView: WebView,
    private val callback: BridgeCallback
) {
    companion object {
        private const val TAG = "MusicAssistantBridge"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var bridgeInitialized = false

    interface BridgeCallback {
        fun onBridgeReady()
        fun onBridgeError(error: String)
        fun onMetadataUpdate(
            title: String,
            artist: String,
            album: String,
            artworkUrl: String,
            durationMs: Long
        )
        fun onPlaybackStateUpdate(state: String, positionMs: Long)
    }

    /**
     * Initialize the bridge.
     *
     * With the new MediaSession interception approach, initialization is immediate.
     * The interceptors are already installed in onPageStarted(), so we just verify
     * that window.musicPlayer exists.
     */
    fun initialize() {
        Log.i(TAG, "========================================")
        Log.i(TAG, "Initializing Music Assistant bridge...")
        Log.i(TAG, "Using MediaSession interception approach")
        Log.i(TAG, "WebView URL: ${webView.url}")
        Log.i(TAG, "========================================")

        // Small delay to ensure interceptors have been installed
        handler.postDelayed({
            verifyBridgeState()
        }, 500)
    }

    /**
     * Verify that window.musicPlayer was properly exposed by the interceptor
     */
    private fun verifyBridgeState() {
        val verificationScript = """
            (function() {
                try {
                    if (typeof window.musicPlayer === 'undefined') {
                        return JSON.stringify({ success: false, error: 'window.musicPlayer undefined' });
                    }
                    const methods = Object.keys(window.musicPlayer);
                    if (methods.length === 0) {
                        return JSON.stringify({ success: false, error: 'no methods on window.musicPlayer' });
                    }
                    return JSON.stringify({ success: true, methods: methods });
                } catch (e) {
                    return JSON.stringify({ success: false, error: e.message });
                }
            })();
        """.trimIndent()

        webView.evaluateJavascript(verificationScript) { result ->
            Log.i(TAG, "Bridge verification result: $result")

            try {
                if (result != null && result.contains("\"success\":true")) {
                    bridgeInitialized = true
                    Log.i(TAG, "========================================")
                    Log.i(TAG, "Bridge verified successfully")
                    Log.i(TAG, "window.musicPlayer is ready")
                    Log.i(TAG, "MediaSession interceptors are active")
                    Log.i(TAG, "========================================")
                    callback.onBridgeReady()
                } else {
                    val error = "Bridge verification failed: $result"
                    Log.e(TAG, error)
                    callback.onBridgeError(error)

                    // Log additional diagnostic info
                    logDiagnostics()
                }
            } catch (e: Exception) {
                val error = "Error parsing verification result: ${e.message}"
                Log.e(TAG, error)
                callback.onBridgeError(error)
            }
        }
    }

    /**
     * Log diagnostic information to help debug bridge issues
     */
    private fun logDiagnostics() {
        val diagnosticScript = """
            (function() {
                console.log('[MusicAssistantBridge] DIAGNOSTICS:');
                console.log('[MusicAssistantBridge] - window.musicPlayer exists:', typeof window.musicPlayer !== 'undefined');
                console.log('[MusicAssistantBridge] - window.AndroidMediaSession exists:', typeof window.AndroidMediaSession !== 'undefined');
                console.log('[MusicAssistantBridge] - navigator.mediaSession exists:', typeof navigator.mediaSession !== 'undefined');
                if (window.musicPlayer) {
                    console.log('[MusicAssistantBridge] - musicPlayer methods:', Object.keys(window.musicPlayer));
                    if (window.musicPlayer._getHandlers) {
                        console.log('[MusicAssistantBridge] - registered handlers:', window.musicPlayer._getHandlers());
                    }
                }
                return 'diagnostics_logged';
            })();
        """.trimIndent()

        webView.evaluateJavascript(diagnosticScript) { result ->
            Log.d(TAG, "Diagnostic script executed - check WebView console for details")
        }
    }

    /**
     * Reset the bridge state (useful for page reloads or reconnections)
     */
    fun reset() {
        Log.d(TAG, "Resetting bridge state")
        bridgeInitialized = false
        handler.removeCallbacksAndMessages(null)
    }

    /**
     * Manually trigger a metadata refresh (useful for testing)
     *
     * Note: With MediaSession interception, metadata updates happen automatically
     * when Music Assistant updates navigator.mediaSession.metadata.
     * This method is kept for backwards compatibility.
     */
    fun refreshMetadata() {
        if (!bridgeInitialized) {
            Log.w(TAG, "Cannot refresh metadata - bridge not initialized")
            return
        }

        // Verify current state
        verifyBridgeState()
    }

    fun isInitialized(): Boolean = bridgeInitialized

    /**
     * Get the WebView instance (for testing/debugging)
     */
    fun getWebView(): WebView = webView
}
