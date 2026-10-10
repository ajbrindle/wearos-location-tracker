package com.sk7software.locationupdate.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID

class WorkoutForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private val client = OkHttpClient()

    companion object {
        private const val TAG = "WorkoutForegroundService"
        private const val CHANNEL_ID = "WorkoutChannel"
        private const val NOTIF_ID = 1001

        const val EXTRA_WORKOUT_TYPE = "workout_type"
        const val ACTION_START_WORKOUT = "ACTION_START_WORKOUT"
        const val ACTION_PAUSE_WORKOUT = "ACTION_PAUSE_WORKOUT"
        const val ACTION_RESUME_WORKOUT = "ACTION_RESUME_WORKOUT"
        const val ACTION_STOP_WORKOUT = "ACTION_STOP_WORKOUT"

        @Volatile
        var isPaused = false
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val workoutType = intent?.getStringExtra(EXTRA_WORKOUT_TYPE) ?: "Workout"

        when (action) {
            ACTION_PAUSE_WORKOUT -> {
                pauseWorkout()
                return START_NOT_STICKY
            }
            ACTION_RESUME_WORKOUT -> {
                resumeWorkout()
                return START_NOT_STICKY
            }
            ACTION_STOP_WORKOUT -> {
                stopWorkout()
                return START_NOT_STICKY
            }
            else -> {
                isPaused = false
                startForeground(NOTIF_ID, createNotification(workoutType, "Tracking location every 15s..."))
                startLocationTracking(workoutType)
                return START_STICKY
            }
        }
    }

    private fun createNotification(workoutType: String, text: String): Notification {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Workout Tracking",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("$workoutType in Progress")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    private fun startLocationTracking(workoutType: String) {
        val prefs = getSharedPreferences(LocationPassiveListenerService.PREFS_NAME, Context.MODE_PRIVATE)
        val workoutId = UUID.randomUUID().toString()
        prefs.edit()
            .putString(LocationPassiveListenerService.KEY_WORKOUT_ID, workoutId)
            .putBoolean(LocationPassiveListenerService.KEY_WORKOUT_ACTIVE, true)
            .putBoolean(WatchAckListenerService.KEY_SERVER_CONFIRMED, false)
            .putString(LocationPassiveListenerService.KEY_LAST_LOCATION, "Service started, waiting for GPS fix...")
            .putString(LocationPassiveListenerService.KEY_LAST_SENT_STATUS, "Sending workout_started...")
            .apply()

        // Direct HTTP POST for workout_started
        scope.launch {
            sendDirectHttpEvent("workout_started", workoutId, System.currentTimeMillis())
        }

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 15_000L)
            .setMinUpdateIntervalMillis(15_000L)
            .setMaxUpdateDelayMillis(60_000L)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                if (isPaused) return

                for (location in result.locations) {
                    val lat = location.latitude
                    val lon = location.longitude
                    val timestamp = if (location.time > 0) location.time else System.currentTimeMillis()

                    val locStr = "$timestamp"
                    Log.d(TAG, "FusedLocation captured: Lat $lat, Lon $lon at $timestamp")
                    prefs.edit().putString(LocationPassiveListenerService.KEY_LAST_LOCATION, locStr).apply()

                    scope.launch {
                        sendLocationUpdateViaBle(workoutId, lat, lon, timestamp)
                    }
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
            Log.d(TAG, "Location updates requested successfully.")
        } catch (e: SecurityException) {
            val err = "SecurityException: ${e.message}"
            Log.e(TAG, err, e)
            prefs.edit().putString(LocationPassiveListenerService.KEY_LAST_LOCATION, err).apply()
        } catch (e: Exception) {
            val err = "Exception: ${e.message}"
            Log.e(TAG, err, e)
            prefs.edit().putString(LocationPassiveListenerService.KEY_LAST_LOCATION, err).apply()
        }
    }

    private fun pauseWorkout() {
        isPaused = true
        val prefs = getSharedPreferences(LocationPassiveListenerService.PREFS_NAME, Context.MODE_PRIVATE)
        val workoutId = prefs.getString(LocationPassiveListenerService.KEY_WORKOUT_ID, null)

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIF_ID, createNotification("Workout", "Workout Paused"))

        if (workoutId != null) {
            scope.launch {
                sendDirectHttpEvent("workout_paused", workoutId, System.currentTimeMillis())
            }
        }
    }

    private fun resumeWorkout() {
        isPaused = false
        val prefs = getSharedPreferences(LocationPassiveListenerService.PREFS_NAME, Context.MODE_PRIVATE)
        val workoutId = prefs.getString(LocationPassiveListenerService.KEY_WORKOUT_ID, null)

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIF_ID, createNotification("Workout", "Tracking location every 15s..."))

        if (workoutId != null) {
            scope.launch {
                sendDirectHttpEvent("workout_resumed", workoutId, System.currentTimeMillis())
            }
        }
    }

    private fun stopWorkout() {
        try {
            if (::locationCallback.isInitialized) {
                fusedLocationClient.removeLocationUpdates(locationCallback)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing location updates", e)
        }

        val prefs = getSharedPreferences(LocationPassiveListenerService.PREFS_NAME, Context.MODE_PRIVATE)
        val workoutId = prefs.getString(LocationPassiveListenerService.KEY_WORKOUT_ID, null)

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIF_ID, createNotification("Workout", "Ending workout..."))

        if (workoutId != null) {
            scope.launch {
                sendDirectHttpEvent("workout_ended", workoutId, System.currentTimeMillis())
                prefs.edit()
                    .putBoolean(LocationPassiveListenerService.KEY_WORKOUT_ACTIVE, false)
                    .putBoolean(WatchAckListenerService.KEY_SERVER_CONFIRMED, false)
                    .remove(LocationPassiveListenerService.KEY_WORKOUT_ID)
                    .apply()
                stopSelf()
            }
        } else {
            stopSelf()
        }
    }

    private suspend fun sendDirectHttpEvent(event: String, workoutId: String, timestamp: Long) {
        val prefs = getSharedPreferences(LocationPassiveListenerService.PREFS_NAME, Context.MODE_PRIVATE)
        var serverUrl = prefs.getString(WatchAckListenerService.KEY_SERVER_URL, "") ?: ""

        // If URL is blank on watch, request it from phone on-demand
        if (serverUrl.isBlank()) {
            Log.w(TAG, "Server URL is blank on watch. Requesting URL from phone...")
            try {
                val nodes = Tasks.await(Wearable.getNodeClient(this).connectedNodes)
                val nodeId = nodes.firstOrNull()?.id
                if (nodeId != null) {
                    Wearable.getMessageClient(this).sendMessage(nodeId, "/ride/get_url", ByteArray(0)).await()
                    delay(1500L) // Wait for sync reply
                    serverUrl = prefs.getString(WatchAckListenerService.KEY_SERVER_URL, "") ?: ""
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch URL from phone: ${e.message}", e)
            }
        }

        if (serverUrl.isBlank()) {
            val err = "ERROR: Server URL not set on watch! Save URL in phone app first."
            Log.e(TAG, err)
            prefs.edit().putString(LocationPassiveListenerService.KEY_LAST_SENT_STATUS, err).apply()
            return
        }

        val json = JSONObject().apply {
            put("event", event)
            put("workoutId", workoutId)
            put("time", timestamp)
        }

        Log.d(TAG, "DEBUG: Sending direct HTTP POST ($event) to $serverUrl with payload: $json")
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = json.toString().toRequestBody(mediaType)
        val request = Request.Builder().url(serverUrl).post(body).build()

        try {
            client.newCall(request).execute().use { response ->
                val respBody = response.body?.string() ?: ""
                val code = response.code
                Log.d(TAG, "DEBUG: HTTP response code = $code, body = $respBody")
                if (response.isSuccessful) {
                    val status = "HTTP OK ($event): $respBody"
                    Log.d(TAG, status)
                    prefs.edit().putString(LocationPassiveListenerService.KEY_LAST_SENT_STATUS, status).apply()

                    if (event == "workout_started") {
                        prefs.edit().putBoolean(WatchAckListenerService.KEY_SERVER_CONFIRMED, true).apply()
                        try {
                            val vibrator = getSystemService(Vibrator::class.java)
                            vibrator?.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
                        } catch (e: Exception) {}
                    }
                } else {
                    val err = "HTTP Fail ($event): Code $code - $respBody"
                    Log.e(TAG, err)
                    prefs.edit().putString(LocationPassiveListenerService.KEY_LAST_SENT_STATUS, err).apply()
                }
            }
        } catch (e: Exception) {
            val err = "HTTP Exception ($event): ${e.javaClass.simpleName} - ${e.message}"
            Log.e(TAG, err, e)
            prefs.edit().putString(LocationPassiveListenerService.KEY_LAST_SENT_STATUS, err).apply()
        }
    }

    private suspend fun sendLocationUpdateViaBle(workoutId: String, lat: Double, lon: Double, timestamp: Long, maxRetries: Int = 3) {
        val prefs = getSharedPreferences(LocationPassiveListenerService.PREFS_NAME, Context.MODE_PRIVATE)
        for (attempt in 1..maxRetries) {
            try {
                val nodes = Tasks.await(Wearable.getNodeClient(this).connectedNodes)
                val nodeId = nodes.firstOrNull()?.id
                if (nodeId == null) {
                    Log.w(TAG, "Attempt $attempt: No connected phone node found for location update. Retrying...")
                    delay(1000L)
                    continue
                }

                val idBytes = workoutId.toByteArray(StandardCharsets.UTF_8)
                val buffer = ByteBuffer.allocate(4 + idBytes.size + 8 + 8 + 8).apply {
                    putInt(idBytes.size)
                    put(idBytes)
                    putDouble(lat)
                    putDouble(lon)
                    putLong(timestamp)
                }.array()

                Tasks.await(Wearable.getMessageClient(this).sendMessage(nodeId, "/ride/location_batch", buffer))
                val status = "Sent location ($lat, $lon) via BLE"
                Log.d(TAG, status)
                prefs.edit().putString(LocationPassiveListenerService.KEY_LAST_SENT_STATUS, status).apply()
                return
            } catch (e: Exception) {
                Log.e(TAG, "Attempt $attempt failed to send location update via BLE: ${e.message}", e)
                delay(1000L)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
