package com.e2bspeedlab

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Tiny preloaded sonification for FLASH frame boundaries.
 *
 * The WAVs are synthesized once into cache and loaded into SoundPool. Playback therefore does no
 * file I/O on the FLASH timing path. Sentence/clause endings use slightly lower clicks so the ear
 * can follow structure without needing a second visual cue.
 */
internal class FlashClickSound(context: Context) : AutoCloseable {

    private enum class Kind { WORD, CLAUSE, SENTENCE }

    private val appContext = context.applicationContext
    private val loaded = HashSet<Int>()
    private val soundPool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val wordId: Int
    private val clauseId: Int
    private val sentenceId: Int

    init {
        val dir = File(appContext.cacheDir, "flash_clicks").apply { mkdirs() }
        val word = File(dir, "word.wav").also {
            if (!it.exists()) writeClickWav(it, frequencyHz = 2600.0, durationMs = 5.0, gain = 0.34)
        }
        val clause = File(dir, "clause.wav").also {
            if (!it.exists()) writeClickWav(it, frequencyHz = 1750.0, durationMs = 6.0, gain = 0.38)
        }
        val sentence = File(dir, "sentence.wav").also {
            if (!it.exists()) writeClickWav(it, frequencyHz = 1050.0, durationMs = 8.0, gain = 0.45)
        }

        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) synchronized(loaded) { loaded += sampleId }
        }
        wordId = soundPool.load(word.absolutePath, 1)
        clauseId = soundPool.load(clause.absolutePath, 1)
        sentenceId = soundPool.load(sentence.absolutePath, 1)
    }

    fun playFor(unit: String) {
        val kind = when (unit.trimEnd().lastOrNull()) {
            '。', '！', '？', '.', '!', '?' -> Kind.SENTENCE
            '、', ',', ';', ':', '；', '：' -> Kind.CLAUSE
            else -> Kind.WORD
        }
        val id = when (kind) {
            Kind.WORD -> wordId
            Kind.CLAUSE -> clauseId
            Kind.SENTENCE -> sentenceId
        }
        val ready = synchronized(loaded) { id in loaded }
        if (!ready) return

        val volume = when (kind) {
            Kind.WORD -> 0.20f
            Kind.CLAUSE -> 0.22f
            Kind.SENTENCE -> 0.27f
        }
        soundPool.play(id, volume, volume, 1, 0, 1.0f)
    }

    override fun close() {
        soundPool.release()
        synchronized(loaded) { loaded.clear() }
    }

    private fun writeClickWav(file: File, frequencyHz: Double, durationMs: Double, gain: Double) {
        val sampleRate = 48_000
        val samples = (sampleRate * durationMs / 1000.0).toInt().coerceAtLeast(1)
        val pcm = ByteArray(samples * 2)

        for (i in 0 until samples) {
            val t = i.toDouble() / sampleRate
            // Fast exponential decay gives a dry transient instead of a tiny musical note.
            val envelope = exp(-t * 900.0)
            val transient = if (i == 0) 0.45 else 0.0
            val wave = (sin(2.0 * PI * frequencyHz * t) * envelope + transient) * gain
            val value = (wave.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
            pcm[i * 2] = (value.toInt() and 0xFF).toByte()
            pcm[i * 2 + 1] = ((value.toInt() ushr 8) and 0xFF).toByte()
        }

        FileOutputStream(file).use { out ->
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
        }
    }
}
