package com.sk7software.locationupdate.phone

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.nio.charset.StandardCharsets

class PhoneMainActivity : ComponentActivity() {
    companion object {
        private const val PREFS_NAME = "LocationUpdatePrefs"
        private const val KEY_SERVER_URL = "server_url"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var urlText by remember { mutableStateOf(prefs.getString(KEY_SERVER_URL, "") ?: "") }
                    var statusMessage by remember { mutableStateOf("") }
                    var lastReceived by remember { mutableStateOf(prefs.getString(WatchBridgeService.KEY_LAST_RECEIVED, "None") ?: "None") }
                    var lastUploadStatus by remember { mutableStateOf(prefs.getString(WatchBridgeService.KEY_LAST_UPLOAD_STATUS, "None") ?: "None") }

                    val scope = rememberCoroutineScope()

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.Start
                    ) {
                        Text(
                            text = "Location Update Diagnostics",
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedTextField(
                            value = urlText,
                            onValueChange = { urlText = it },
                            label = { Text("receiver.php URL") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                prefs.edit().putString(KEY_SERVER_URL, urlText).apply()
                                statusMessage = "URL Saved & Synced to Watch!"

                                scope.launch(Dispatchers.IO) {
                                    try {
                                        val nodes = Tasks.await(Wearable.getNodeClient(applicationContext).connectedNodes)
                                        val urlBytes = urlText.toByteArray(StandardCharsets.UTF_8)
                                        for (node in nodes) {
                                            Wearable.getMessageClient(applicationContext).sendMessage(node.id, "/ride/set_url", urlBytes).await()
                                        }
                                    } catch (e: Exception) {
                                        // Ignore or log error
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Save URL")
                        }
                        if (statusMessage.isNotEmpty()) {
                            Text(
                                text = statusMessage,
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = {
                                lastReceived = prefs.getString(WatchBridgeService.KEY_LAST_RECEIVED, "None") ?: "None"
                                lastUploadStatus = prefs.getString(WatchBridgeService.KEY_LAST_UPLOAD_STATUS, "None") ?: "None"
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Refresh Diagnostics")
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Last Received from Watch:",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = lastReceived,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Last Server Upload Status:",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = lastUploadStatus,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        val client = OkHttpClient()
                                        val json = JSONObject().apply {
                                            put("event", "workout_started")
                                            put("workoutId", "test-workout-123")
                                            put("time", System.currentTimeMillis())
                                        }
                                        val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                                        val request = Request.Builder().url(urlText).post(body).build()
                                        client.newCall(request).execute().use { response ->
                                            val respBody = response.body?.string() ?: ""
                                            statusMessage = if (response.isSuccessful) "Test OK (${response.code}): $respBody" else "Test Failed (${response.code}): $respBody"
                                        }
                                    } catch (e: Exception) {
                                        statusMessage = "Test Error: ${e.message}"
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                        ) {
                            Text("Test Server POST Now")
                        }
                    }
                }
            }
        }
    }
}
