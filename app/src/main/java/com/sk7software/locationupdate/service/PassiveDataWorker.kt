package com.sk7software.locationupdate.service

import android.content.Context
import android.util.Log
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.PassiveListenerConfig
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.guava.await

class PassiveDataWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "PassiveDataWorker"
    }

    override suspend fun doWork(): Result {
        return try {
            val passiveMonitoringClient = HealthServices.getClient(applicationContext).passiveMonitoringClient
            val config = PassiveListenerConfig.builder()
                .setDataTypes(setOf(DataType.LOCATION))
                .build()

            passiveMonitoringClient.setPassiveListenerServiceAsync(
                LocationPassiveListenerService::class.java,
                config
            ).await()

            Log.d(TAG, "Successfully registered passive location listener")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register passive listener: ${e.message}", e)
            Result.failure()
        }
    }
}
