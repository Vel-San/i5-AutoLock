package com.i5autolock

import android.app.Application
import android.content.Context
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.i5autolock.data.settings.SettingsRepository
import com.i5autolock.service.NotificationChannels
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class AutoLockApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface AppEntryPoint {
        fun settingsRepo(): SettingsRepository
    }

    override fun onCreate() {
        super.onCreate()
        // Create the channel immediately (badge off), then apply the user's saved preference.
        NotificationChannels.ensure(this, showBadge = false)
        val repo = EntryPointAccessors.fromApplication(this, AppEntryPoint::class.java).settingsRepo()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            NotificationChannels.ensure(this@AutoLockApp, repo.settings.first().showAppBadge)
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    companion object {
        const val CHANNEL_ID = "autolock_activity_v4"
        // Minimal-importance channel used when the user hides the status-bar icon.
        const val CHANNEL_ID_MINIMAL = "autolock_activity_min_v1"
    }
}
