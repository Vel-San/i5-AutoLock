package com.i5autolock.ui.about

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
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

/** Renders one Markdown line: headings, bullets, horizontal rules + inline **bold**, *italic*, `code`, links. */
@Composable
private fun ChangelogLine(line: String) {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeColor = MaterialTheme.colorScheme.secondary
    val trimmed = line.trimStart()
    when {
        line.startsWith("# ") -> Unit // top-level title, skip
        line.startsWith("### ") -> Text(
            inlineMarkdown(line.removePrefix("### ").trim(), linkColor, codeColor),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        line.startsWith("## ") -> Text(
            inlineMarkdown(line.removePrefix("## ").trim(), linkColor, codeColor),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        trimmed == "---" || trimmed == "***" || trimmed == "___" -> HorizontalDivider()
        trimmed.startsWith("- ") || trimmed.startsWith("* ") -> Text(
            buildAnnotatedString {
                append("•  ")
                append(inlineMarkdown(trimmed.drop(2).trim(), linkColor, codeColor))
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        line.isBlank() -> Spacer(Modifier.height(2.dp))
        else -> Text(
            inlineMarkdown(line.trim(), linkColor, codeColor),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** Parses inline Markdown (bold, italic, code, links) into a styled, clickable [AnnotatedString]. */
@Composable
private fun inlineMarkdown(text: String, linkColor: Color, codeColor: Color): AnnotatedString =
    remember(text, linkColor, codeColor) { parseInline(text, linkColor, codeColor) }

private fun parseInline(text: String, linkColor: Color, codeColor: Color): AnnotatedString =
    buildAnnotatedString {
        var i = 0
        val n = text.length
        while (i < n) {
            val rest = text.substring(i)
            when {
                // [label](url) → clickable link
                text[i] == '[' -> {
                    val closeBracket = text.indexOf(']', i + 1)
                    val openParen = if (closeBracket != -1) closeBracket + 1 else -1
                    if (closeBracket != -1 && openParen < n && text[openParen] == '(') {
                        val closeParen = text.indexOf(')', openParen + 1)
                        if (closeParen != -1) {
                            val label = text.substring(i + 1, closeBracket)
                            val url = text.substring(openParen + 1, closeParen)
                            withLink(
                                LinkAnnotation.Url(
                                    url,
                                    TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                                ),
                            ) { append(label) }
                            i = closeParen + 1
                            continue
                        }
                    }
                    append(text[i]); i++
                }
                // **bold** or __bold__
                rest.startsWith("**") || rest.startsWith("__") -> {
                    val delim = rest.substring(0, 2)
                    val close = text.indexOf(delim, i + 2)
                    if (close != -1) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i + 2, close)) }
                        i = close + 2
                    } else { append(text[i]); i++ }
                }
                // `code`
                text[i] == '`' -> {
                    val close = text.indexOf('`', i + 1)
                    if (close != -1) {
                        withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = codeColor)) {
                            append(text.substring(i + 1, close))
                        }
                        i = close + 1
                    } else { append(text[i]); i++ }
                }
                // *italic* or _italic_
                text[i] == '*' || text[i] == '_' -> {
                    val delim = text[i]
                    val close = text.indexOf(delim, i + 1)
                    if (close != -1 && close > i + 1) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text.substring(i + 1, close)) }
                        i = close + 1
                    } else { append(text[i]); i++ }
                }
                else -> { append(text[i]); i++ }
            }
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
