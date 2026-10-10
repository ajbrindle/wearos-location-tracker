package com.sk7software.locationupdate.service

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import java.nio.charset.StandardCharsets

class WatchAckListenerService : WearableListenerService() {
    companion object {
        private const val TAG = "WatchAckListener"
        const val KEY_SERVER_CONFIRMED = "server_confirmed"
        const val KEY_SERVER_URL = "server_url"
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        val path = messageEvent.path
        val data = messageEvent.data
        Log.d(TAG, "Received message from phone: path=$path")
        // Use the exact same SharedPreferences file as MainActivity and WorkoutForegroundService ("WorkoutStatePrefs")
        val prefs = getSharedPreferences(LocationPassiveListenerService.PREFS_NAME, Context.MODE_PRIVATE)

        when (path) {
            "/ride/set_url" -> {
                if (data != null && data.isNotEmpty()) {
                    val url = String(data, StandardCharsets.UTF_8)
                    prefs.edit().putString(KEY_SERVER_URL, url).apply()
                    Log.d(TAG, "Synced server URL on watch: $url")
                }
            }
            "/ride/server_confirmed" -> {
                prefs.edit().putBoolean(KEY_SERVER_CONFIRMED, true).apply()
                Log.d(TAG, "Server confirmation received on watch! KEY_SERVER_CONFIRMED set to true.")

                // Trigger haptic vibration on watch to alert the user
                try {
                    val vibrator = getSystemService(Vibrator::class.java)
                    vibrator?.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
                } catch (e: Exception) {
                    Log.e(TAG, "Vibration failed: ${e.message}", e)
                }
            }
        }
    }
}
