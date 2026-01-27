package net.asksakis.massdroid

import android.bluetooth.BluetoothA2dp
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
 * BroadcastReceiver that detects Bluetooth audio device connections/disconnections.
 * - Triggers auto-play when a device connects
 * - Triggers stop when a device disconnects while playing
 */
class BluetoothAutoPlayReceiver(
    private val onBluetoothAudioConnected: (deviceName: String) -> Unit,
    private val onBluetoothAudioDisconnected: ((deviceName: String) -> Unit)? = null
) : BroadcastReceiver() {

    companion object {
        private const val TAG = "BluetoothAutoPlay"

        fun getIntentFilter(): IntentFilter {
            return IntentFilter().apply {
                // A2DP (Advanced Audio Distribution Profile) - for music streaming
                addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
                // Headset profile - for car kits and headsets
                addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
                // Note: ACL events removed - profile events are more reliable for audio
                // and ACL caused duplicate triggers with the profile events
            }
        }
    }

    private var lastConnectedDevice: String? = null
    private var lastConnectionTime: Long = 0
    private var lastDisconnectedDevice: String? = null
    private var lastDisconnectionTime: Long = 0

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent == null) return

        val action = intent.action ?: return

        when (action) {
            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
            BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                handleProfileConnectionChange(intent)
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

        if (device == null) return

        val deviceName = try {
            device.name ?: "Unknown Device"
        } catch (e: SecurityException) {
            "Bluetooth Device"
        }

        // Check if it's actually an audio output device (speaker, headphones, car kit)
        // Skip wearables like smartwatches that have Headset profile for calls but aren't audio outputs
        val deviceClass = try {
            device.bluetoothClass
        } catch (e: SecurityException) {
            null
        }

        val majorClass = deviceClass?.majorDeviceClass ?: 0
        val isWearable = majorClass == android.bluetooth.BluetoothClass.Device.Major.WEARABLE
        val isAudioOutput = deviceClass?.let { btClass ->
            majorClass == android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO ||
            btClass.hasService(android.bluetooth.BluetoothClass.Service.RENDER)
        } ?: false

        if (isWearable || !isAudioOutput) {
            Log.d(TAG, "Skipping non-audio device: $deviceName (wearable=$isWearable, audioOutput=$isAudioOutput)")
            return
        }

        when (state) {
            BluetoothProfile.STATE_CONNECTED -> {
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
            BluetoothProfile.STATE_DISCONNECTED -> {
                // Debounce: avoid triggering multiple times for same disconnection
                val now = System.currentTimeMillis()
                if (deviceName == lastDisconnectedDevice && now - lastDisconnectionTime < 5000) {
                    Log.d(TAG, "Ignoring duplicate disconnection event for: $deviceName")
                    return
                }

                lastDisconnectedDevice = deviceName
                lastDisconnectionTime = now

                Log.i(TAG, "Bluetooth audio device disconnected: $deviceName")
                onBluetoothAudioDisconnected?.invoke(deviceName)
            }
        }
    }

}
