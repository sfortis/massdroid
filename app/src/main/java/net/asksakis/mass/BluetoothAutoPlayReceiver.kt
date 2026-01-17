package net.asksakis.mass

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log

/**
 * BroadcastReceiver that detects Bluetooth audio device connections
 * and triggers auto-play when a device connects.
 */
class BluetoothAutoPlayReceiver(
    private val onBluetoothAudioConnected: (deviceName: String) -> Unit
) : BroadcastReceiver() {

    companion object {
        private const val TAG = "BluetoothAutoPlay"

        fun getIntentFilter(): IntentFilter {
            return IntentFilter().apply {
                // A2DP (Advanced Audio Distribution Profile) - for music streaming
                addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
                // Headset profile - for car kits and headsets
                addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
                // Generic ACL connection (fallback)
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            }
        }
    }

    private var lastConnectedDevice: String? = null
    private var lastConnectionTime: Long = 0

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent == null) return

        val action = intent.action ?: return

        when (action) {
            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
            BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                handleProfileConnectionChange(intent)
            }
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                handleAclConnected(intent)
            }
        }
    }

    private fun handleProfileConnectionChange(intent: Intent) {
        val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

        if (state == BluetoothProfile.STATE_CONNECTED && device != null) {
            val deviceName = try {
                device.name ?: "Unknown Device"
            } catch (e: SecurityException) {
                "Bluetooth Device"
            }

            // Debounce: avoid triggering multiple times for same connection
            val now = System.currentTimeMillis()
            if (deviceName == lastConnectedDevice && now - lastConnectionTime < 5000) {
                Log.d(TAG, "Ignoring duplicate connection event for: $deviceName")
                return
            }

            lastConnectedDevice = deviceName
            lastConnectionTime = now

            Log.i(TAG, "Bluetooth audio device connected: $deviceName")
            onBluetoothAudioConnected(deviceName)
        }
    }

    private fun handleAclConnected(intent: Intent) {
        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

        if (device != null) {
            val deviceName = try {
                device.name ?: "Unknown Device"
            } catch (e: SecurityException) {
                "Bluetooth Device"
            }

            // Check if it's an audio device (has A2DP or Headset capability)
            val deviceClass = try {
                device.bluetoothClass
            } catch (e: SecurityException) {
                null
            }

            val isAudioDevice = deviceClass?.let { btClass ->
                val majorClass = btClass.majorDeviceClass
                majorClass == android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO ||
                majorClass == android.bluetooth.BluetoothClass.Device.Major.PHONE ||
                btClass.hasService(android.bluetooth.BluetoothClass.Service.AUDIO) ||
                btClass.hasService(android.bluetooth.BluetoothClass.Service.RENDER)
            } ?: false

            if (isAudioDevice) {
                // Debounce
                val now = System.currentTimeMillis()
                if (deviceName == lastConnectedDevice && now - lastConnectionTime < 5000) {
                    return
                }

                lastConnectedDevice = deviceName
                lastConnectionTime = now

                Log.i(TAG, "Bluetooth audio device (ACL) connected: $deviceName")
                onBluetoothAudioConnected(deviceName)
            } else {
                Log.d(TAG, "Non-audio Bluetooth device connected, ignoring: $deviceName")
            }
        }
    }
}
