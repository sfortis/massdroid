package net.asksakis.massdroid

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

class PreferencesHelper(context: Context) {
    private val sharedPreferences: SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context)

    companion object {
        private const val KEY_PWA_URL = "pwa_url"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_AUTO_PLAY_BLUETOOTH = "auto_play_bluetooth"
        private const val KEY_AUTO_RESUME_NETWORK = "auto_resume_network"
        private const val KEY_CLIENT_CERT_ALIAS = "client_cert_alias"
        private const val KEY_PAGE_ZOOM = "page_zoom"
        private const val DEFAULT_URL = ""
        private const val DEFAULT_ZOOM = 100
    }

    var pwaUrl: String
        get() = sharedPreferences.getString(KEY_PWA_URL, DEFAULT_URL) ?: DEFAULT_URL
        set(value) = sharedPreferences.edit().putString(KEY_PWA_URL, value).apply()

    val isUrlConfigured: Boolean
        get() = pwaUrl.isNotBlank()

    var keepScreenOn: Boolean
        get() = sharedPreferences.getBoolean(KEY_KEEP_SCREEN_ON, false)
        set(value) = sharedPreferences.edit().putBoolean(KEY_KEEP_SCREEN_ON, value).apply()

    var autoPlayOnBluetooth: Boolean
        get() = sharedPreferences.getBoolean(KEY_AUTO_PLAY_BLUETOOTH, true)
        set(value) = sharedPreferences.edit().putBoolean(KEY_AUTO_PLAY_BLUETOOTH, value).apply()

    var autoResumeOnNetwork: Boolean
        get() = sharedPreferences.getBoolean(KEY_AUTO_RESUME_NETWORK, true)
        set(value) = sharedPreferences.edit().putBoolean(KEY_AUTO_RESUME_NETWORK, value).apply()

    var clientCertAlias: String?
        get() = sharedPreferences.getString(KEY_CLIENT_CERT_ALIAS, null)
        set(value) = sharedPreferences.edit().putString(KEY_CLIENT_CERT_ALIAS, value).apply()

    var pageZoom: Int
        get() = sharedPreferences.getInt(KEY_PAGE_ZOOM, DEFAULT_ZOOM)
        set(value) = sharedPreferences.edit().putInt(KEY_PAGE_ZOOM, value).apply()

    fun registerOnChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        sharedPreferences.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterOnChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(listener)
    }
}
