package com.e2bspeedlab

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Reliable preloaded sonification for FLASH frame boundaries.
 *
 * v0.3.6 used USAGE_ASSISTANCE_SONIFICATION plus 5-8 ms samples. Some OEM Android builds, notably
 * HyperOS, may mute that usage with system-effect settings or swallow such tiny buffers. This
 * implementation routes through the media/game path, uses low-latency audio attributes, and keeps
 * the click short but long enough to survive the device mixer.
 */
internal class FlashClickSound(context: Context) : AutoCloseable {

    private enum class Kind { WORD, CLAUSE, SENTENCE }

    companion object {
        private const val PREFS_NAME = "speedlab"
        private const val PREF_CLICK_ENABLED = "flash_click_enabled"
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val loaded = HashSet<Int>()
    private val soundPool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setFlags(AudioAttributes.FLAG_LOW_LATENCY)
                .build()
        )
        .build()

    private val wordId: Int
    private val clauseId: Int
    private val sentenceId: Int

    @Volatile
    private var released = false

    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == PREF_CLICK_ENABLED && prefs.getBoolean(PREF_CLICK_ENABLED, true)) {
            // Give the UI write a moment to settle. This also makes CLICK ON an audible sanity test.
            mainHandler.postDelayed({ playTest() }, 80L)
        }
    }

    init {
        // Version the cache directory so an in-place app update cannot keep the old 5 ms WAVs.
        val dir = File(appContext.cacheDir, "flash_clicks_v2").apply { mkdirs() }
        val word = File(dir, "word_v2.wav").also {
            writeClickWav(it, frequencyHz = 2350.0, durationMs = 18.0, gain = 0.92)
        }
        val clause = File(dir, "clause_v2.wav").also {
            writeClickWav(it, frequencyHz = 1550.0, durationMs = 21.0, gain = 0.95)
        }
        val sentence = File(dir, "sentence_v2.wav").also {
            writeClickWav(it, frequencyHz = 980.0, durationMs = 25.0, gain = 0.98)
        }

        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0 && !released) synchronized(loaded) { loaded += sampleId }
        }
        wordId = soundPool.load(word.absolutePath, 1)
        clauseId = soundPool.load(clause.absolutePath, 1)
        sentenceId = soundPool.load(sentence.absolutePath, 1)
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
    }

    /** Plays the marker matching the punctuation at the end of [unit]. */
    fun playFor(unit: String) {
        if (released) return
        val kind = when (unit.trimEnd().lastOrNull()) {
            '。', '！', '？', '.', '!', '?' -> Kind.SENTENCE
            '、', ',', ';', ':', '；', '：' -> Kind.CLAUSE
            else -> Kind.WORD
        }
        play(kind)
    }

    /** Loud word marker used when CLICK is switched on as an immediate audio sanity check. */
    fun playTest() {
        if (!released) play(Kind.WORD, test = true)
    }

    private fun play(kind: Kind, test: Boolean = false) {
        val id = when (kind) {
            Kind.WORD -> wordId
            Kind.CLAUSE -> clauseId
            Kind.SENTENCE -> sentenceId
        }
        if (id <= 0) return

        val ready = synchronized(loaded) { id in loaded }
        if (!ready) return

        val volume = if (test) {
            0.95f
        } else {
            when (kind) {
                Kind.WORD -> 0.62f
                Kind.CLAUSE -> 0.70f
                Kind.SENTENCE -> 0.78f
            }
        }

        // SoundPool is fully preloaded here. No filesystem or decoder work happens in the FLASH
        // step(), which keeps 1000+ WPM timing isolated from audio I/O.
        soundPool.play(id, volume, volume, 1, 0, 1.0f)
    }

    override fun close() {
        if (released) return
        released = true
        mainHandler.removeCallbacksAndMessages(null)
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        soundPool.release()
        synchronized(loaded) { loaded.clear() }
    }

    private fun writeClickWav(file: File, frequencyHz: Double, durationMs: Double, gain: Double) {
        val sampleRate = 48_000
        val samples = (sampleRate * durationMs / 1000.0).toInt().coerceAtLeast(1)
        val pcm = ByteArray(samples * 2)

        // A sharp bipolar impulse followed by a damped high-frequency body survives phone-speaker
        // filtering much better than the almost-one-millisecond transient used previously.
        var noiseState = 0x13579BDF
        for (i in 0 until samples) {
            val t = i.toDouble() / sampleRate
            noiseState = noiseState xor (noiseState shl 13)
            noiseState = noiseState xor (noiseState ushr 17)
            noiseState = noiseState xor (noiseState shl 5)
            val noise = ((noiseState and 0xFFFF) / 32767.5) - 1.0

            val envelope = exp(-t * 170.0)
            val body = sin(2.0 * PI * frequencyHz * t) * 0.70 + noise * 0.18
            val impulse = when (i) {
                0 -> 0.95
                1 -> -0.72
                2 -> 0.40
                else -> 0.0
            }
            val wave = (impulse + body * envelope) * gain
            val value = (wave.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
            pcm[i * 2] = (value.toInt() and 0xFF).toByte()
            pcm[i * 2 + 1] = ((value.toInt() ushr 8) and 0xFF).toByte()
        }

        FileOutputStream(file, false).use { out ->
            fun ascii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
            fun le16(v: Int) {
                out.write(v and 0xFF)
                out.write((v ushr 8) and 0xFF)
            }
            fun le32(v: Int) {
                out.write(v and 0xFF)
                out.write((v ushr 8) and 0xFF)
                out.write((v ushr 16) and 0xFF)
                out.write((v ushr 24) and 0xFF)
            }

            val dataSize = pcm.size
            ascii("RIFF")
            le32(36 + dataSize)
            ascii("WAVE")
            ascii("fmt ")
            le32(16)
            le16(1) // PCM
            le16(1) // mono
            le32(sampleRate)
            le32(sampleRate * 2)
            le16(2)
            le16(16)
            ascii("data")
            le32(dataSize)
            out.write(pcm)
            out.fd.sync()
        }
    }
}
