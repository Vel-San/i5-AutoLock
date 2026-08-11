package com.i5autolock.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.i5autolock.data.bluelink.BlueLinkProvider
import com.i5autolock.data.settings.SettingsRepository
import com.i5autolock.data.status.StatusCache
import com.i5autolock.domain.StatusSummary
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Periodically refreshes the vehicle status in the background and writes it to [StatusCache],
 * which drives the widget + the "watching" notification and seeds the home screen. Scheduling is
 * user-controlled (Settings → auto-refresh interval); WorkManager's minimum period is 15 minutes.
 */
@HiltWorker
class StatusRefreshWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val settingsRepo: SettingsRepository,
    private val provider: BlueLinkProvider,
    private val statusCache: StatusCache,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val s = settingsRepo.settings.first()
        val vehicleId = s.vehicleId ?: if (s.demoMode) "demo-ioniq5" else return Result.success()
        val client = provider.client(s)
        if (!s.demoMode && !client.ensureFreshSession()) return Result.retry()
        return runCatching { client.status(vehicleId, forceRefresh = false) }
            .fold(
                onSuccess = {
                    statusCache.saveStatus(it, StatusSummary.build(it, s.notificationFields))
                    Result.success()
                },
                onFailure = { Result.retry() },
            )
    }

    companion object {
        private const val NAME = "status_refresh"

        /** Schedule (or cancel when [minutes] <= 0) the periodic refresh. */
        fun schedule(context: Context, minutes: Int) {
            val wm = WorkManager.getInstance(context)
            if (minutes <= 0) {
                wm.cancelUniqueWork(NAME)
                return
            }
            val period = minutes.toLong().coerceAtLeast(15)
            val request = PeriodicWorkRequestBuilder<StatusRefreshWorker>(period, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
