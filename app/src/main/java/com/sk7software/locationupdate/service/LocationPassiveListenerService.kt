package com.sk7software.locationupdate.service

import android.content.Context
import android.util.Log
import androidx.health.services.client.PassiveListenerService
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID

class LocationPassiveListenerService : PassiveListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val TAG = "LocationPassiveListener"
        const val PREFS_NAME = "WorkoutStatePrefs"
        const val KEY_WORKOUT_ID = "active_workout_id"
        const val KEY_WORKOUT_ACTIVE = "workout_active"
        const val KEY_LAST_LOCATION = "last_location"
        const val KEY_LAST_SENT_STATUS = "last_sent_status"
        const val KEY_NODE_STATUS = "node_status"
    }

    override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
        val locations = dataPoints.getData(DataType.LOCATION)
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        if (locations.isNullOrEmpty()) {
            Log.d(TAG, "onNewDataPointsReceived called, but no location data points found.")
            prefs.edit().putString(KEY_LAST_LOCATION, "No location data in container at ${System.currentTimeMillis()}").apply()
            return
        }

        Log.d(TAG, "Received ${locations.size} location data points from Health Services.")

        var workoutId = prefs.getString(KEY_WORKOUT_ID, null)
        val isWorkoutActive = prefs.getBoolean(KEY_WORKOUT_ACTIVE, false)

        scope.launch {
            val activeWorkoutId = if (!isWorkoutActive || workoutId == null) {
                val newId = UUID.randomUUID().toString()
                prefs.edit()
                    .putString(KEY_WORKOUT_ID, newId)
                    .putBoolean(KEY_WORKOUT_ACTIVE, true)
                    .apply()

                Log.d(TAG, "Starting new workout session: $newId")
                sendWorkoutEvent("/ride/workout_started", newId, System.currentTimeMillis())
                newId
            } else {
                workoutId
            }

            for (locPoint in locations) {
                val lat = locPoint.value.latitude
                val lon = locPoint.value.longitude
                val timestamp = System.currentTimeMillis()

                val locStr = "Lat: $lat, Lon: $lon at $timestamp"
                Log.d(TAG, "Captured location: $locStr")
                prefs.edit().putString(KEY_LAST_LOCATION, locStr).apply()

                sendLocationUpdate(activeWorkoutId, lat, lon, timestamp)
            }
        }
    }

    private suspend fun sendWorkoutEvent(path: String, workoutId: String, timestamp: Long) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        try {
            val idBytes = workoutId.toByteArray(StandardCharsets.UTF_8)
            val buffer = ByteBuffer.allocate(4 + idBytes.size + 8).apply {
                putInt(idBytes.size)
                put(idBytes)
                putLong(timestamp)
            }.array()

            val nodes = Wearable.getNodeClient(this).connectedNodes.await()
            val nodeId = nodes.firstOrNull()?.id
            if (nodeId == null) {
                Log.e(TAG, "No connected phone node found via Wearable API!")
                prefs.edit().putString(KEY_NODE_STATUS, "No connected phone node found").apply()
                return
            } else {
                prefs.edit().putString(KEY_NODE_STATUS, "Connected to phone: $nodeId").apply()
            }

            Wearable.getMessageClient(this).sendMessage(nodeId, path, buffer).await()
            val status = "Successfully sent $path at ${System.currentTimeMillis()}"
            Log.d(TAG, status)
            prefs.edit().putString(KEY_LAST_SENT_STATUS, status).apply()
        } catch (e: Exception) {
            val err = "Failed to send $path: ${e.message}"
            Log.e(TAG, err, e)
            prefs.edit().putString(KEY_LAST_SENT_STATUS, err).apply()
        }
    }

    private suspend fun sendLocationUpdate(workoutId: String, lat: Double, lon: Double, timestamp: Long) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        try {
            val idBytes = workoutId.toByteArray(StandardCharsets.UTF_8)
            val buffer = ByteBuffer.allocate(4 + idBytes.size + 8 + 8 + 8).apply {
                putInt(idBytes.size)
                put(idBytes)
                putDouble(lat)
                putDouble(lon)
                putLong(timestamp)
            }.array()

            val nodes = Wearable.getNodeClient(this).connectedNodes.await()
            val nodeId = nodes.firstOrNull()?.id
            if (nodeId == null) {
                Log.e(TAG, "No connected phone node found for location update!")
                prefs.edit().putString(KEY_NODE_STATUS, "No connected phone node found").apply()
                return
            } else {
                prefs.edit().putString(KEY_NODE_STATUS, "Connected to phone: $nodeId").apply()
            }

            Wearable.getMessageClient(this).sendMessage(nodeId, "/ride/location_batch", buffer).await()
            val status = "Successfully sent location ($lat, $lon) at ${System.currentTimeMillis()}"
            Log.d(TAG, status)
            prefs.edit().putString(KEY_LAST_SENT_STATUS, status).apply()
        } catch (e: Exception) {
            val err = "Failed to send location update: ${e.message}"
            Log.e(TAG, err, e)
            prefs.edit().putString(KEY_LAST_SENT_STATUS, err).apply()
        }
    }
}
