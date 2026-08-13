package com.i5autolock.ui.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.core.content.getSystemService
import com.i5autolock.R
import com.i5autolock.data.metrics.ApiCall
import com.i5autolock.domain.LogEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TS = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

@JvmName("logEntriesToClipboardText")
fun List<LogEntry>.toClipboardText(): String = buildString {
    if (this@toClipboardText.isEmpty()) return@buildString
    // Oldest first — easier to read a copied timeline top-to-bottom.
    this@toClipboardText.asReversed().forEach { e ->
        append(TS.format(Date(e.timestamp)))
        append("  ").append(e.level.name).append("  ").append(e.message).append('\n')
    }
}

@JvmName("apiCallsToClipboardText")
fun List<ApiCall>.toClipboardText(): String = buildString {
    if (this@toClipboardText.isEmpty()) return@buildString
    this@toClipboardText.asReversed().forEach { c ->
        append(TS.format(Date(c.timestamp)))
        append("  ").append(c.outcome.name)
        append("  ").append(c.durationMs).append("ms")
        append("  ").append(c.operation)
        c.detail?.takeIf { it.isNotBlank() }?.let { append("  | ").append(it) }
        append('\n')
    }
}

fun Context.copyToClipboard(label: String, text: String) {
    val cm = getSystemService<ClipboardManager>() ?: return
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(this, getString(R.string.msg_copied), Toast.LENGTH_SHORT).show()
}
