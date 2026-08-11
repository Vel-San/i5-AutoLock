package com.i5autolock.ui.permissions

import android.Manifest
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState

/**
 * Gate that requests the runtime permissions AutoLock needs, then shows [content].
 * Background location + activity recognition are requested contextually.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PermissionGate(content: @Composable () -> Unit) {
    val permissions = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val state = rememberMultiplePermissionsState(permissions)

    if (state.allPermissionsGranted) {
        content()
    } else {
        Column(
            Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "AutoLock needs a few permissions",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Text(
                "\u2022 Bluetooth: to notice when you leave the car\n" +
                    "\u2022 Location: to confirm you've walked away\n" +
                    "\u2022 Activity: to tell driving from walking\n" +
                    "\u2022 Notifications: to show locking status",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 16.dp),
            )
            Button(onClick = { state.launchMultiplePermissionRequest() }) {
                Text("Grant permissions")
            }
        }
    }
}
