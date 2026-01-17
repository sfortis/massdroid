package net.asksakis.massdroid

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log

/**
 * Monitors network connectivity changes and notifies when network is lost/restored.
 * Used to auto-resume playback after network reconnection.
 */
class NetworkChangeMonitor(
    private val context: Context,
    private val listener: NetworkChangeListener
) {
    companion object {
        private const val TAG = "NetworkChangeMonitor"
    }

    interface NetworkChangeListener {
        fun onNetworkLost()
        fun onNetworkAvailable()
    }

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var isRegistered = false
    private var lastNetworkState: Boolean? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.i(TAG, "Network available")
            if (lastNetworkState == false) {
                // Network was lost and is now restored
                Log.i(TAG, "Network restored after loss")
                listener.onNetworkAvailable()
            }
            lastNetworkState = true
        }

        override fun onLost(network: Network) {
            Log.i(TAG, "Network lost")
            lastNetworkState = false
            listener.onNetworkLost()
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            Log.d(TAG, "Network capabilities changed: hasInternet=$hasInternet, isValidated=$isValidated, lastState=$lastNetworkState")

            // Also trigger network available when capabilities show valid network after a loss
            // This handles cases where onAvailable() isn't called during network switch
            if (hasInternet && isValidated && lastNetworkState == false) {
                Log.i(TAG, "Network restored via capabilities change")
                lastNetworkState = true
                listener.onNetworkAvailable()
            }
        }
    }

    /**
     * Start monitoring network changes
     */
    fun start() {
        if (isRegistered) {
            Log.d(TAG, "Already registered")
            return
        }

        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            connectivityManager.registerNetworkCallback(request, networkCallback)
            isRegistered = true

            // Check initial state
            lastNetworkState = isNetworkAvailable()
            Log.i(TAG, "Network monitor started, initial state: ${if (lastNetworkState == true) "connected" else "disconnected"}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback", e)
        }
    }

    /**
     * Stop monitoring network changes
     */
    fun stop() {
        if (!isRegistered) return

        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            isRegistered = false
            Log.i(TAG, "Network monitor stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister network callback", e)
        }
    }

    /**
     * Check if network is currently available
     */
    fun isNetworkAvailable(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
