package com.i5autolock.data.sound

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Synthesises a soft, premium EV-style chime at runtime (no audio assets). Used for the lock
 * confirmation and notification cues so they sound like an Ioniq 5 rather than a generic ding.
 *
 * Each tone is a bell-like blend of a fundamental plus a couple of quiet harmonics, shaped by a
 * quick attack and a gentle exponential decay, with the notes overlapped into a shimmering arpeggio.
 */
object EvChime {

    private const val SAMPLE_RATE = 44_100

    private data class Note(val freqHz: Double, val startSec: Double, val durSec: Double, val gain: Double)

    // Ascending D5 · F#5 · A5 · D6 shimmer — a confident "secured" flourish.
    private val LOCK = listOf(
        Note(587.33, 0.00, 0.55, 0.30),
        Note(739.99, 0.09, 0.55, 0.26),
        Note(880.00, 0.18, 0.65, 0.28),
        Note(1174.66, 0.28, 0.80, 0.24),
    )

    // A softer two-note cue for notifications (A5 · D6).
    private val NOTIFY = listOf(
        Note(880.00, 0.00, 0.40, 0.26),
        Note(1174.66, 0.10, 0.50, 0.20),
    )

    fun playLock() = play(LOCK)

    fun playNotify() = play(NOTIFY)

    private fun play(notes: List<Note>) {
        val totalSec = (notes.maxOf { it.startSec + it.durSec }) + 0.15
        val totalSamples = (totalSec * SAMPLE_RATE).toInt()
        val mix = FloatArray(totalSamples)

        for (note in notes) {
            val startSample = (note.startSec * SAMPLE_RATE).toInt()
            val durSamples = (note.durSec * SAMPLE_RATE).toInt()
            val attack = (0.006 * SAMPLE_RATE).toInt().coerceAtLeast(1)
            val tau = note.durSec * 0.32
            val w = 2.0 * PI * note.freqHz
            for (i in 0 until durSamples) {
                val idx = startSample + i
                if (idx >= totalSamples) break
                val t = i.toDouble() / SAMPLE_RATE
                // Attack ramp then exponential decay.
                val env = (if (i < attack) i.toDouble() / attack else 1.0) * exp(-t / tau)
                val tone = sin(w * t) + 0.35 * sin(2 * w * t) + 0.12 * sin(3 * w * t)
                mix[idx] += (note.gain * env * tone).toFloat()
            }
        }

        val pcm = ShortArray(totalSamples)
        for (i in 0 until totalSamples) {
            val v = mix[i].coerceIn(-0.95f, 0.95f)
            pcm[i] = (v * Short.MAX_VALUE).toInt().toShort()
        }

        val bytes = pcm.size * 2
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(bytes)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        runCatching {
            track.write(pcm, 0, pcm.size)
            track.play()
        }
        // Release on a background thread once playback has finished.
        thread(isDaemon = true) {
            Thread.sleep(min(4000L, (totalSec * 1000).toLong() + 150))
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }
}
