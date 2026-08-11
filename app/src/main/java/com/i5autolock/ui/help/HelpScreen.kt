package com.i5autolock.ui.help

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.i5autolock.R
import com.i5autolock.ui.components.HeroBanner
import com.i5autolock.ui.theme.ambientBackground

/**
 * Static help / tutorial page. Explains every setting and how detection works. No ViewModel:
 * pure documentation rendered in-app so users never have to leave to understand a control.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(onBack: () -> Unit) {
    Scaffold(
        modifier = Modifier.fillMaxSize().background(ambientBackground()),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.help_title)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            HeroBanner(
                title = stringResource(R.string.help_hero_title),
                subtitle = stringResource(R.string.help_hero_body),
                icon = Icons.AutoMirrored.Filled.HelpOutline,
                eyebrow = stringResource(R.string.help_hero_eyebrow),
            )

            HelpSection(
                "The lock flow, step by step",
                listOf(
                    "1. Trigger" to "Your phone disconnects from the car's Bluetooth — the strongest sign you just got out and walked away.",
                    "2. Confirm" to "Optionally, activity recognition (driving → walking) and/or a geofence confirm you actually left, so a brief Bluetooth drop won't lock the car on you.",
                    "3. Grace" to "A countdown starts (you set the length). Getting back in or tapping Cancel aborts it.",
                    "4. Verify" to "AutoLock reads the car's live status and only continues if it's actually unlocked and the engine is off.",
                    "5. Lock" to "It sends the lock command — or, in Dry run, just logs what it would have done.",
                ),
            )

            HelpSection(
                "Home screen",
                listOf(
                    "Master switch" to "Turns the whole watcher on or off. When off, nothing happens automatically.",
                    "Status card" to "Shows the current state: Idle, Confirming, Locking in Ns, Locked ✓, Skipped, or an error.",
                    "Simulate leaving" to "Runs the entire flow on demand so you can test it safely (especially with Dry run on).",
                    "Cancel" to "Aborts an in-progress evaluation immediately.",
                    "Recent activity" to "A live log of what AutoLock is doing and why.",
                ),
            )

            HelpSection(
                "Safety",
                listOf(
                    "Demo mode" to "Uses a simulated car — no BlueLink account or real vehicle needed. Perfect for trying the app. Turn it off to use your real car.",
                    "Dry run" to "The default and safest mode. Runs everything but NEVER sends a real lock command; it logs \"would have locked\". Use this until you trust the detection.",
                    "Armed" to "Sends real lock commands to your car. Only switch to this once Dry run behaves the way you expect.",
                    "Confirm before locking" to "Instead of locking automatically, AutoLock will ask you first (via a notification) before sending the command.",
                ),
            )

            HelpSection(
                "Account",
                listOf(
                    "Region" to "Where your BlueLink account is registered. Europe (EU) uses a secure web sign-in to obtain a token; other regions use a direct login. EU is the primary supported region.",
                    "Sign in" to "Opens Hyundai's real login page inside a locked-down, screenshot-blocked window. Your token is captured on-device and stored encrypted. AutoLock never sees your password.",
                    "Reload vehicles" to "Fetches the cars on your account so you can pick one.",
                    "Sign out" to "Deletes the stored session from the device.",
                ),
            )

            HelpSection(
                "Vehicle & Car Bluetooth",
                listOf(
                    "Vehicle" to "Pick the car AutoLock should lock. Load it from your account first.",
                    "Car Bluetooth" to "Choose the paired Bluetooth device that means \"I'm in the car\" (your car's head unit / hands-free). Disconnecting from it is the primary trigger, so this must be set for automatic locking.",
                    "Refresh paired devices" to "Re-scans your phone's paired Bluetooth devices if your car isn't listed yet.",
                ),
            )

            HelpSection(
                "Detection",
                listOf(
                    "Bluetooth disconnect" to "The primary trigger. When your phone leaves the car's Bluetooth range, an evaluation begins. Recommended: on.",
                    "Activity confirmation" to "Requires your phone to detect a driving → walking change before locking. Reduces false triggers (e.g. a passing radio glitch). Needs the Activity permission.",
                    "Geofence confirmation" to "Requires you to physically move away from where the car is parked before locking. Needs location permission.",
                    "Geofence radius" to "How far (in metres) you must move from the parked spot before AutoLock acts. Smaller = locks sooner; larger = fewer false triggers.",
                ),
            )

            HelpSection(
                "Timing",
                listOf(
                    "Grace period" to "How many seconds AutoLock waits after the trigger before it locks. This is your window to get back in or cancel. Shorter = quicker locking; longer = more safety margin. Default is 45s.",
                ),
            )

            HelpSection(
                "Diagnostics — API statistics",
                listOf(
                    "Session" to "Your region, live/demo mode, command mode, account, and selected vehicle at a glance.",
                    "Rate limiting" to "BlueLink limits how often you can send commands. If you hit the limit, this shows a cooldown so you know when to try again.",
                    "Totals" to "Total API calls, success rate, failures, and average response time.",
                    "Recent API calls" to "Every call with its duration and outcome — handy for spotting slow or failing requests.",
                    "Activity log" to "The same human-readable events shown on the home screen.",
                ),
            )

            HelpSection(
                "Permissions & why they're needed",
                listOf(
                    "Bluetooth" to "To detect when your phone disconnects from the car — the main trigger.",
                    "Location (incl. background)" to "For the optional geofence, and to know where the car was parked. Background access lets it work when the app is closed.",
                    "Physical activity" to "For the optional driving → walking confirmation.",
                    "Notifications" to "To show the ongoing 'locking soon' notification with a Cancel button, and the result.",
                ),
            )

            HelpSection(
                "Sign-in troubleshooting (EU)",
                listOf(
                    "Automatic sign-in" to "Just enter your BlueLink email, password and 4-digit PIN. AutoLock generates the access token on your device (headless, no CAPTCHA) — you never handle a token.",
                    "Password rules" to "Hyundai/Kia require 8–20 characters with an uppercase letter, a lowercase letter, a digit, and a special character. Sign-in fails if the password is outside this range.",
                    "\"Sign-in rejected\"" to "Double-check the email and password work in the official Hyundai app, then try again.",
                    "Advanced: refresh token" to "If you already generated a 48-char refresh token elsewhere, paste it under \"Advanced\" instead.",
                    "No vehicles after login" to "Tap Reload vehicles in Settings → Account.",
                    "Prefer no account?" to "Turn on Demo mode to use the whole app with a simulated car.",
                ),
            )

            HelpSection(
                "Reliability tips",
                listOf(
                    "Battery optimization" to "Open Settings → Keep AutoLock running → Battery settings and allow AutoLock unrestricted background use so the trigger fires reliably.",
                    "Background limits" to "On Samsung/Xiaomi/Huawei/OnePlus, add AutoLock to \"never sleeping apps\" and disable any \"restrict background\" option (Settings → App info).",
                    "Keep it enabled" to "The master switch must be on. After a reboot the watcher re-activates automatically.",
                    "Test first" to "Use Dry run + Simulate leaving until you're happy, then switch to Armed.",
                ),
            )
        }
    }
}

@Composable
private fun HelpSection(title: String, items: List<Pair<String, String>>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            items.forEachIndexed { index, (name, desc) ->
                if (index > 0) HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text(desc, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
