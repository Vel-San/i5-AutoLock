package com.i5autolock.data.sound

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri

/**
 * Plays the lock confirmation sound — the user's custom sound when set, otherwise the built-in
 * synthesized EV chime. Falls back to the chime if the custom sound can't be played.
 */
object LockSound {

    fun play(context: Context, customUri: String?) {
        if (customUri.isNullOrBlank()) {
            EvChime.playLock()
            return
        }
        val ok = runCatching {
            RingtoneManager.getRingtone(context, Uri.parse(customUri))?.also { it.play() } != null
        }.getOrDefault(false)
        if (!ok) EvChime.playLock()
    }

    /** Play the built-in default chime (for the settings "test default" button). */
    fun playDefault() = EvChime.playLock()
}
