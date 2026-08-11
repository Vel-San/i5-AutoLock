package com.i5autolock.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.i5autolock.data.settings.SettingsRepository
import com.i5autolock.domain.ActivityLog
import com.i5autolock.domain.LogLevel
import com.i5autolock.service.AutoLockService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** After a reboot, resume persistent background watching if AutoLock is enabled. */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var log: ActivityLog
    @Inject lateinit var settingsRepo: SettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        scope.launch {
            try {
                if (settingsRepo.settings.first().enabled) {
                    AutoLockService.startWatching(context)
                    log.add(LogLevel.INFO, "Device restarted — AutoLock resumed watching.")
                }
            } finally {
                pending.finish()
            }
        }
    }
}
