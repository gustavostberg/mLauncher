package com.gustav.mlauncher.tesla

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.gustav.mlauncher.data.LauncherPreferences
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

object TeslaSyncScheduler {
    private const val UNIQUE_WORK_NAME = "tesla_hourly_sync"

    fun syncFromPreferences(context: Context) {
        val preferences = LauncherPreferences(context)
        if (!preferences.loadTeslaEnabled() || !preferences.isTeslaConfigured()) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
            return
        }

        val now = ZonedDateTime.now()
        val firstRunAt =
            if (now.hour < 6) {
                now.withHour(6).withMinute(0).withSecond(0).withNano(0)
            } else {
                now.plusHours(1).withMinute(0).withSecond(0).withNano(0)
            }
        val initialDelay = Duration.between(now, firstRunAt).toMinutes().coerceAtLeast(1)

        val request =
            PeriodicWorkRequestBuilder<TeslaSyncWorker>(1, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setInitialDelay(initialDelay, TimeUnit.MINUTES)
                .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}
