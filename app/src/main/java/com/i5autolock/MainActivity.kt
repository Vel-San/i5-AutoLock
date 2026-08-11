package com.i5autolock

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.i5autolock.ui.AppNavigation
import com.i5autolock.ui.permissions.PermissionGate
import com.i5autolock.ui.theme.AutoLockTheme
import com.i5autolock.ui.theme.ThemeViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val themeViewModel: ThemeViewModel = hiltViewModel()
            val prefs by themeViewModel.prefs.collectAsStateWithLifecycle()
            AutoLockTheme(themeMode = prefs.mode, dynamicColor = prefs.dynamicColor) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    PermissionGate {
                        AppNavigation()
                    }
                }
            }
        }
    }
}
