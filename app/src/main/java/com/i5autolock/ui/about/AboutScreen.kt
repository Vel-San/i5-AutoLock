package com.i5autolock.ui.about

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.i5autolock.BuildConfig
import com.i5autolock.R
import com.i5autolock.ui.components.HeroBanner
import com.i5autolock.ui.theme.ambientBackground

private const val PROJECT_URL = "https://github.com/Vel-San/i5-AutoLock"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val changelog = remember { readChangelog(context) }

    Scaffold(
        modifier = Modifier.fillMaxSize().background(ambientBackground()),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
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
                title = stringResource(R.string.app_name),
                subtitle = stringResource(R.string.about_hero_subtitle),
                icon = Icons.Default.Info,
                eyebrow = stringResource(R.string.about_hero_eyebrow),
            )

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        stringResource(R.string.about_open_source_body),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(
                        onClick = { openUrl(context, PROJECT_URL) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.about_view_project)) }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(R.string.about_whats_new),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    if (changelog.isEmpty()) {
                        Text(
                            stringResource(R.string.about_changelog_unavailable),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        changelog.forEach { line -> ChangelogLine(line) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChangelogLine(line: String) {
    when {
        line.startsWith("## ") -> Text(
            line.removePrefix("## ").trim(),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        line.startsWith("### ") -> Text(
            line.removePrefix("### ").trim(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        line.startsWith("# ") -> Unit // top-level title, skip
        line.startsWith("- ") || line.startsWith("* ") -> Text(
            "•  " + line.drop(2).trim(),
            style = MaterialTheme.typography.bodyMedium,
        )
        else -> Text(line.trim(), style = MaterialTheme.typography.bodyMedium)
    }
}

/** Reads the bundled CHANGELOG.md, keeping only the human-readable body (no link refs). */
private fun readChangelog(context: android.content.Context): List<String> = runCatching {
    context.assets.open("CHANGELOG.md").bufferedReader().use { it.readLines() }
        .filterNot { it.startsWith("[") && it.contains("]: ") } // drop markdown link references
        .dropWhile { it.isBlank() }
        .dropLastWhile { it.isBlank() }
}.getOrDefault(emptyList())

private fun openUrl(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
