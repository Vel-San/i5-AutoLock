package com.i5autolock.tile

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.i5autolock.data.settings.SettingsRepository
import com.i5autolock.service.AutoLockService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Quick Settings tile to toggle AutoLock on/off from the system pull-down. */
@AndroidEntryPoint
class AutoLockTileService : TileService() {

    @Inject lateinit var settingsRepo: SettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartListening() {
        super.onStartListening()
        scope.launch { render(settingsRepo.settings.first().enabled) }
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            val enabled = !settingsRepo.settings.first().enabled
            settingsRepo.update { it.copy(enabled = enabled) }
            if (enabled) AutoLockService.startWatching(applicationContext)
            else AutoLockService.stopWatching(applicationContext)
            render(enabled)
        }
    }

    private fun render(enabled: Boolean) {
        qsTile?.apply {
            state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "AutoLock"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                subtitle = if (enabled) "Watching" else "Off"
            }
            updateTile()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
