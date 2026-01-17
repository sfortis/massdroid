package net.asksakis.mass

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
        private const val DEFAULT_URL = "https://mass.asksakis.net"
    }

    var pwaUrl: String
        get() = sharedPreferences.getString(KEY_PWA_URL, DEFAULT_URL) ?: DEFAULT_URL
        set(value) = sharedPreferences.edit().putString(KEY_PWA_URL, value).apply()

    var keepScreenOn: Boolean
        get() = sharedPreferences.getBoolean(KEY_KEEP_SCREEN_ON, false)
        set(value) = sharedPreferences.edit().putBoolean(KEY_KEEP_SCREEN_ON, value).apply()

    var autoPlayOnBluetooth: Boolean
        get() = sharedPreferences.getBoolean(KEY_AUTO_PLAY_BLUETOOTH, true)
        set(value) = sharedPreferences.edit().putBoolean(KEY_AUTO_PLAY_BLUETOOTH, value).apply()

    var autoResumeOnNetwork: Boolean
        get() = sharedPreferences.getBoolean(KEY_AUTO_RESUME_NETWORK, true)
        set(value) = sharedPreferences.edit().putBoolean(KEY_AUTO_RESUME_NETWORK, value).apply()

    fun registerOnChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        sharedPreferences.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterOnChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(listener)
    }
}
