package com.i5autolock

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class AutoLockApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // v3 = silent channel; AutoLock plays its own Ioniq-style chime (EvChime) instead of the
        // generic system ding, so the channel itself must not make sound.
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(true)
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
        // Cleanup: remove older channel ids.
        runCatching { manager.deleteNotificationChannel("autolock_activity") }
        runCatching { manager.deleteNotificationChannel("autolock_activity_v2") }
    }

    companion object {
        const val CHANNEL_ID = "autolock_activity_v3"
    }
}
