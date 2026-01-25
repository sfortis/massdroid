package net.asksakis.massdroid

import android.os.Build
import android.os.Bundle
import android.webkit.WebStorage
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeHelper.applyTheme(this)
        setContentView(R.layout.activity_settings)

        // Set up the toolbar with back arrow
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener {
            finish()
        }

        // Load the preferences fragment
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            // Show app version
            findPreference<Preference>("app_version")?.summary = getAppVersion()

            // Handle check for updates click
            findPreference<Preference>("check_updates")?.setOnPreferenceClickListener {
                checkForUpdatesManually()
                true
            }

            // Handle clear certificate click
            findPreference<Preference>("clear_certificate")?.setOnPreferenceClickListener {
                clearCertificateAndCache()
                true
            }
        }

        private fun getAppVersion(): String {
            return try {
                val context = requireContext()
                val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo(
                        context.packageName,
                        android.content.pm.PackageManager.PackageInfoFlags.of(0)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(context.packageName, 0)
                }
                packageInfo.versionName ?: "1.0"
            } catch (e: Exception) {
                "1.0"
            }
        }

        private fun checkForUpdatesManually() {
            val context = requireContext()
            val updateChecker = UpdateChecker(context)

            // Create progress dialog
            val progressBar = ProgressBar(context).apply {
                isIndeterminate = true
            }

            val textView = TextView(context).apply {
                text = "Checking for updates..."
                setPadding(0, 0, 0, 16)
            }

            val layout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(50, 40, 50, 40)
                addView(textView)
                addView(progressBar)
            }

            val progressDialog = AlertDialog.Builder(context)
                .setTitle("Checking for Updates")
                .setView(layout)
                .setCancelable(false)
                .create()

            progressDialog.show()

            lifecycleScope.launch {
                val updateInfo = updateChecker.checkForUpdates(force = true)
                progressDialog.dismiss()

                if (updateInfo != null) {
                    updateChecker.showUpdateDialog(
                        requireActivity() as AppCompatActivity,
                        updateInfo
                    )
                } else {
                    Toast.makeText(context, "No updates available", Toast.LENGTH_SHORT).show()
                }
            }
        }

        private fun clearCertificateAndCache() {
            val context = requireContext()

            // Show confirmation dialog
            AlertDialog.Builder(context)
                .setTitle("Clear Certificate & Cache")
                .setMessage("This will clear the saved client certificate and WebView cache. You will need to select your certificate again on next connection.\n\nContinue?")
                .setPositiveButton("Clear") { _, _ ->
                    // Clear saved certificate alias
                    val preferencesHelper = PreferencesHelper(context)
                    preferencesHelper.clientCertAlias = null

                    // Clear WebView storage
                    WebStorage.getInstance().deleteAllData()

                    // Clear WebView cache directory
                    context.cacheDir.deleteRecursively()

                    Toast.makeText(context, "Certificate and cache cleared", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
}
