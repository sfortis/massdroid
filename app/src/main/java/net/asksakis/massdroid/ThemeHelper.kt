package net.asksakis.massdroid

import android.app.Activity
import androidx.preference.PreferenceManager

object ThemeHelper {
    fun applyTheme(activity: Activity) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        val colorAccent = prefs.getString("color_accent", "purple") ?: "purple"

        val themeRes = when (colorAccent) {
            "indigo" -> R.style.Theme_MassPWA_Indigo
            "blue" -> R.style.Theme_MassPWA_Blue
            "teal" -> R.style.Theme_MassPWA_Teal
            "green" -> R.style.Theme_MassPWA_Green
            "orange" -> R.style.Theme_MassPWA_Orange
            "red" -> R.style.Theme_MassPWA_Red
            "pink" -> R.style.Theme_MassPWA_Pink
            else -> R.style.Theme_MassPWA // Default purple
        }

        activity.setTheme(themeRes)
    }
}
