package com.i5autolock.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.i5autolock.domain.ActivityLog
import com.i5autolock.domain.LogLevel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Re-registers nothing special (the manifest receiver is always live), but records that
 * we survived a reboot so the user has confidence the watcher is active.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var log: ActivityLog

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            log.add(LogLevel.INFO, "Device restarted — AutoLock is active in the background.")
        }
    }
}
