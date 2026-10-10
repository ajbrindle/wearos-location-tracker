package com.sk7software.locationupdate.presentation

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.*
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import com.sk7software.locationupdate.service.LocationPassiveListenerService
import com.sk7software.locationupdate.service.WatchAckListenerService
import com.sk7software.locationupdate.service.WorkoutForegroundService
import com.sk7software.locationupdate.presentation.theme.LocationUpdateTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ -> }

    private val handler = Handler(Looper.getMainLooper())
    private val screenOffRunnable = Runnable {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    fun keepScreenOnFor20Seconds() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        handler.removeCallbacks(screenOffRunnable)
        handler.postDelayed(screenOffRunnable, 20_000L)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep screen on initially for 20 seconds upon opening
        keepScreenOnFor20Seconds()
        checkAndRequestPermissions()

        setContent {
            WearApp(this, ::keepScreenOnFor20Seconds)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(screenOffRunnable)
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            permissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing) {
            requestPermissionLauncher.launch(permissions.toTypedArray())
        }
    }
}

@Composable
fun WearApp(context: Context, keepScreenOn: () -> Unit) {
    val prefs = context.getSharedPreferences(LocationPassiveListenerService.PREFS_NAME, Context.MODE_PRIVATE)
    var lastLocRaw by remember { mutableStateOf(prefs.getString(LocationPassiveListenerService.KEY_LAST_LOCATION, "None") ?: "None") }
    var lastStatus by remember { mutableStateOf(prefs.getString(LocationPassiveListenerService.KEY_LAST_SENT_STATUS, "None") ?: "None") }
    var nodeStatus by remember { mutableStateOf("Checking node...") }
    var isWorkoutActive by remember { mutableStateOf(prefs.getBoolean(LocationPassiveListenerService.KEY_WORKOUT_ACTIVE, false)) }
    var isPaused by remember { mutableStateOf(WorkoutForegroundService.isPaused) }
    var isServerConfirmed by remember { mutableStateOf(prefs.getBoolean(WatchAckListenerService.KEY_SERVER_CONFIRMED, false)) }
    var testResult by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()

    val lastLocFormatted = remember(lastLocRaw, isWorkoutActive) {
        if (!isWorkoutActive) {
            "None"
        } else {
            val millis = lastLocRaw.toLongOrNull()
            if (millis != null && millis > 0) {
                SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(millis))
            } else {
                lastLocRaw
            }
        }
    }

    // Poll SharedPreferences and Node status live every 1 second
    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            while (true) {
                try {
                    val nodes = Tasks.await(Wearable.getNodeClient(context).connectedNodes)
                    nodeStatus = if (nodes.isEmpty()) {
                        "No phone node found!"
                    } else {
                        "${nodes.first().displayName}"
                    }
                } catch (e: Exception) {
                    nodeStatus = "Node error: ${e.message}"
                }

                lastLocRaw = prefs.getString(LocationPassiveListenerService.KEY_LAST_LOCATION, "None") ?: "None"
                lastStatus = prefs.getString(LocationPassiveListenerService.KEY_LAST_SENT_STATUS, "None") ?: "None"
                isWorkoutActive = prefs.getBoolean(LocationPassiveListenerService.KEY_WORKOUT_ACTIVE, false)
                isPaused = WorkoutForegroundService.isPaused
                isServerConfirmed = prefs.getBoolean(WatchAckListenerService.KEY_SERVER_CONFIRMED, false)

                delay(1000L)
            }
        }
    }

    val headerText = if (isWorkoutActive) {
        if (!isServerConfirmed) "Starting..." else if (isPaused) "Paused" else "Tracking Active"
    } else {
        "Tracker Ready"
    }

    val headerColor = if (isWorkoutActive && isServerConfirmed) {
        if (isPaused) Color(0xFFFFC107) else Color(0xFF4CAF50) // Amber if paused, Green if active
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    LocationUpdateTheme {
        AppScaffold {
            val listState = rememberTransformingLazyColumnState()
            val transformationSpec = rememberTransformationSpec()
            ScreenScaffold(
                scrollState = listState
            ) { contentPadding ->
                TransformingLazyColumn(contentPadding = contentPadding, state = listState) {
                    item {
                        ListHeader(
                            modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                            transformation = SurfaceTransformation(transformationSpec),
                        ) {
                            Text(
                                text = headerText,
                                textAlign = TextAlign.Center,
                                color = headerColor,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    if (!isWorkoutActive) {
                        // Initial State: Start Button
                        item {
                            Button(
                                onClick = {
                                    keepScreenOn()
                                    val fineGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                                    if (!fineGranted) {
                                        testResult = "Location perm denied!"
                                        return@Button
                                    }

                                    prefs.edit().putBoolean(WatchAckListenerService.KEY_SERVER_CONFIRMED, false).apply()
                                    val intent = Intent(context, WorkoutForegroundService::class.java).apply {
                                        putExtra(WorkoutForegroundService.EXTRA_WORKOUT_TYPE, "Workout")
                                    }
                                    ContextCompat.startForegroundService(context, intent)
                                    isWorkoutActive = true
                                    isPaused = false
                                    isServerConfirmed = false
                                },
                                modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                                transformation = SurfaceTransformation(transformationSpec),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            ) {
                                Text(
                                    text = "Start",
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    } else if (!isServerConfirmed) {
                        // Starting Modal / Spinner State with Retry & Abort
                        item {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                        item {
                            Text(
                                text = "Waiting for server...",
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.fillMaxWidth().padding(4.dp)
                            )
                        }
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Button(
                                    onClick = {
                                        keepScreenOn()
                                        // Retry starting
                                        val intent = Intent(context, WorkoutForegroundService::class.java).apply {
                                            putExtra(WorkoutForegroundService.EXTRA_WORKOUT_TYPE, "Workout")
                                        }
                                        ContextCompat.startForegroundService(context, intent)
                                    },
                                    modifier = Modifier.weight(1f).height(48.dp)
                                ) {
                                    Text(
                                        text = "Retry",
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        keepScreenOn()
                                        // Abort starting
                                        val intent = Intent(context, WorkoutForegroundService::class.java).apply {
                                            action = WorkoutForegroundService.ACTION_STOP_WORKOUT
                                        }
                                        context.startService(intent)
                                        isWorkoutActive = false
                                        isPaused = false
                                        isServerConfirmed = false
                                    },
                                    modifier = Modifier.weight(1f).height(48.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                ) {
                                    Text(
                                        text = "Abort",
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    } else {
                        // Active State: Pause/Resume and End buttons side by side
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                if (!isPaused) {
                                    Button(
                                        onClick = {
                                            keepScreenOn()
                                            val intent = Intent(context, WorkoutForegroundService::class.java).apply {
                                                action = WorkoutForegroundService.ACTION_PAUSE_WORKOUT
                                            }
                                            context.startService(intent)
                                            isPaused = true
                                        },
                                        modifier = Modifier.weight(1f).height(48.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                    ) {
                                        Text(
                                            text = "Pause",
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                } else {
                                    Button(
                                        onClick = {
                                            keepScreenOn()
                                            val intent = Intent(context, WorkoutForegroundService::class.java).apply {
                                                action = WorkoutForegroundService.ACTION_RESUME_WORKOUT
                                            }
                                            context.startService(intent)
                                            isPaused = false
                                        },
                                        modifier = Modifier.weight(1f).height(48.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    ) {
                                    Text(
                                            text = "Resume",
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        keepScreenOn()
                                        val intent = Intent(context, WorkoutForegroundService::class.java).apply {
                                            action = WorkoutForegroundService.ACTION_STOP_WORKOUT
                                        }
                                        context.startService(intent)
                                        isWorkoutActive = false
                                        isPaused = false
                                        isServerConfirmed = false
                                    },
                                    modifier = Modifier.weight(1f).height(48.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                ) {
                                    Text(
                                        text = "End",
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }

                    item {
                        Text(
                            text = "Phone: $nodeStatus",
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth().padding(4.dp)
                        )
                    }
                    item {
                        Text(
                            text = "Last Update: $lastLocFormatted",
                            textAlign = TextAlign.Center,
                            color = if (isServerConfirmed && !isPaused) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth().padding(4.dp)
                        )
                    }
                    if (testResult.isNotEmpty()) {
                        item {
                            Text(
                                text = testResult,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.fillMaxWidth().padding(4.dp)
                            )
                        }
                    }

                    // Test BLE button placed right at the bottom out of the way
                    item {
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                keepScreenOn()
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        val nodes = Tasks.await(Wearable.getNodeClient(context).connectedNodes)
                                        if (nodes.isEmpty()) {
                                            testResult = "Ping failed: No nodes!"
                                            return@launch
                                        }
                                        val nodeId = nodes.first().id
                                        val workoutId = "test-watch-" + UUID.randomUUID().toString()
                                        val idBytes = workoutId.toByteArray(StandardCharsets.UTF_8)
                                        val buffer = ByteBuffer.allocate(4 + idBytes.size + 8).apply {
                                            putInt(idBytes.size)
                                            put(idBytes)
                                            putLong(System.currentTimeMillis())
                                        }.array()

                                        Tasks.await(Wearable.getMessageClient(context).sendMessage(nodeId, "/ride/workout_started", buffer))
                                        testResult = "Ping OK to $nodeId"
                                    } catch (e: Exception) {
                                        testResult = "Ping Err: ${e.message}"
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                            transformation = SurfaceTransformation(transformationSpec),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        ) {
                            Text(
                                text = "Test BLE",
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }
}
