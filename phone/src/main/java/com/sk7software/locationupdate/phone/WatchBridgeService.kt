package com.sk7software.locationupdate.phone

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

class WatchBridgeService : WearableListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient()

    companion object {
        private const val TAG = "WatchBridgeService"
        private const val PREFS_NAME = "LocationUpdatePrefs"
        private const val KEY_SERVER_URL = "server_url"
        const val KEY_LAST_RECEIVED = "last_received"
        const val KEY_LAST_UPLOAD_STATUS = "last_upload_status"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "WatchBridgeService onCreate() called!")
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        val path = messageEvent.path
        val data = messageEvent.data

        Log.d(TAG, ">>> onMessageReceived TRIGGERED! path=$path, length=${data?.size}")

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LAST_RECEIVED, "Path: $path at ${System.currentTimeMillis()}").apply()

        scope.launch {
            try {
                if (path == "/ride/get_url") {
                    val serverUrl = prefs.getString(KEY_SERVER_URL, "") ?: ""
                    if (serverUrl.isNotBlank()) {
                        val nodes = Tasks.await(Wearable.getNodeClient(applicationContext).connectedNodes)
                        nodes.firstOrNull()?.id?.let { nodeId ->
                            Wearable.getMessageClient(applicationContext).sendMessage(
                                nodeId,
                                "/ride/set_url",
                                serverUrl.toByteArray(StandardCharsets.UTF_8)
                            ).await()
                            Log.d(TAG, "Replied to watch with server URL: $serverUrl")
                        }
                    }
                    return@launch
                }

                if (data == null || data.isEmpty()) {
                    Log.w(TAG, "Received message with null or empty data for path $path")
                    return@launch
                }

                when (path) {
                    "/ride/workout_started" -> {
                        val buffer = ByteBuffer.wrap(data)
                        val workoutId = readString(buffer)
                        val timestamp = buffer.long
                        Log.d(TAG, "Processed workout_started: id=$workoutId, time=$timestamp")
                        sendToServer(
                            JSONObject().apply {
                                put("event", "workout_started")
                                put("workoutId", workoutId)
                                put("time", timestamp)
                            }
                        )
                    }
                    "/ride/workout_paused" -> {
                        val buffer = ByteBuffer.wrap(data)
                        val workoutId = readString(buffer)
                        val timestamp = buffer.long
                        Log.d(TAG, "Processed workout_paused: id=$workoutId, time=$timestamp")
                        sendToServer(
                            JSONObject().apply {
                                put("event", "workout_paused")
                                put("workoutId", workoutId)
                                put("time", timestamp)
                            }
                        )
                    }
                    "/ride/workout_resumed" -> {
                        val buffer = ByteBuffer.wrap(data)
                        val workoutId = readString(buffer)
                        val timestamp = buffer.long
                        Log.d(TAG, "Processed workout_resumed: id=$workoutId, time=$timestamp")
                        sendToServer(
                            JSONObject().apply {
                                put("event", "workout_resumed")
                                put("workoutId", workoutId)
                                put("time", timestamp)
                            }
                        )
                    }
                    "/ride/location_batch" -> {
                        val buffer = ByteBuffer.wrap(data)
                        val workoutId = readString(buffer)
                        val lat = buffer.double
                        val lon = buffer.double
                        val timestamp = buffer.long
                        Log.d(TAG, "Processed location_update: id=$workoutId, lat=$lat, lon=$lon, time=$timestamp")
                        sendToServer(
                            JSONObject().apply {
                                put("event", "location_update")
                                put("workoutId", workoutId)
                                put("lat", lat)
                                put("lon", lon)
                                put("time", timestamp)
                            }
                        )
                    }
                    "/ride/workout_ended" -> {
                        val buffer = ByteBuffer.wrap(data)
                        val workoutId = readString(buffer)
                        val timestamp = buffer.long
                        Log.d(TAG, "Processed workout_ended: id=$workoutId, time=$timestamp")
                        sendToServer(
                            JSONObject().apply {
                                put("event", "workout_ended")
                                put("workoutId", workoutId)
                                put("time", timestamp)
                            }
                        )
                    }
                    else -> {
                        Log.w(TAG, "Unknown message path received: $path")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing message from watch: ${e.message}", e)
                prefs.edit().putString(KEY_LAST_UPLOAD_STATUS, "Error processing: ${e.message}").apply()
            }
        }
    }

    private fun readString(buffer: ByteBuffer): String {
        val length = buffer.int
        val bytes = ByteArray(length)
        buffer.get(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun sendToServer(jsonPayload: JSONObject) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val serverUrl = prefs.getString(KEY_SERVER_URL, "") ?: ""

        if (serverUrl.isBlank()) {
            val err = "Server URL is not set in phone app!"
            Log.e(TAG, err)
            prefs.edit().putString(KEY_LAST_UPLOAD_STATUS, err).apply()
            return
        }

        Log.d(TAG, "Sending POST request to $serverUrl with payload: $jsonPayload")
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = jsonPayload.toString().toRequestBody(mediaType)
        val request = Request.Builder()
            .url(serverUrl)
            .post(body)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val respBody = response.body?.string() ?: ""
                val status = if (response.isSuccessful) {
                    "SUCCESS (${response.code}): $respBody"
                } else {
                    "FAILED (${response.code}): $respBody"
                }
                Log.d(TAG, "Server response: $status")
                prefs.edit().putString(KEY_LAST_UPLOAD_STATUS, status).apply()

                if (response.isSuccessful) {
                    try {
                        val nodes = Tasks.await(Wearable.getNodeClient(this).connectedNodes)
                        nodes.firstOrNull()?.id?.let { nodeId ->
                            if (jsonPayload.optString("event") == "workout_started") {
                                Wearable.getMessageClient(this).sendMessage(nodeId, "/ride/server_confirmed", ByteArray(0))
                                Log.d(TAG, "Sent server_confirmed ACK back to watch")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send server_confirmed ACK to watch: ${e.message}", e)
                    }
                }
            }
        } catch (e: Exception) {
            val err = "Network Exception: ${e.message}"
            Log.e(TAG, err, e)
            prefs.edit().putString(KEY_LAST_UPLOAD_STATUS, err).apply()
        }
    }
}
