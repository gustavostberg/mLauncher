package com.gustav.mlauncher.tesla

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.gustav.mlauncher.data.LauncherPreferences

class TeslaSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val preferences = LauncherPreferences(applicationContext)
        val repository = TeslaRepository(applicationContext, preferences)

        if (!repository.shouldSyncNow()) {
            return Result.success()
        }

        return when (val result = repository.fetchAndCacheBatteryStatus()) {
            is TeslaFetchResult.Success -> Result.success()
            is TeslaFetchResult.Failure -> {
                preferences.saveTeslaLastError(result.message)
                Result.success()
            }
        }
    }
}
