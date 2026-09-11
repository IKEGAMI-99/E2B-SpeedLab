package com.e2bspeedlab

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

/** Local Gemma 4 E2B engine dedicated to long-form Aozora compression. */
@OptIn(ExperimentalApi::class)
internal class AozoraCompressor(private val cacheDirectory: File) : Closeable {
    companion object {
        private const val CONTEXT_TOKENS = 3072
        private const val MAX_OUTPUT_TOKENS = 1152
        private const val THINKING_TOKENS = 96
        private const val CHUNK_CHARS = 720
    }

    private var engine: Engine? = null
    private var dispatcher: ExecutorCoroutineDispatcher? = null

    suspend fun load(modelPath: String): Double = withContext(Dispatchers.Default) {
        if (engine != null) return@withContext 0.0
        ExperimentalFlags.enableBenchmark = true
        ExperimentalFlags.enableSpeculativeDecoding = true
        Engine.setNativeMinLogSeverity(LogSeverity.ERROR)
        cacheDirectory.mkdirs()

        val originalMask = CpuAffinity.currentMask()
        val started = System.nanoTime()
        val newEngine = Engine(
            EngineConfig(
                modelPath = modelPath,
                backend = Backend.GPU(),
                visionBackend = null,
                audioBackend = null,
                maxNumTokens = CONTEXT_TOKENS,
                cacheDir = cacheDirectory.absolutePath,
            )
        )
        try {
            CpuAffinity.pinFast(2)
            newEngine.initialize()
            engine = newEngine
            dispatcher = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "E2B-Aozora-Compress").apply { priority = Thread.MAX_PRIORITY }
            }.asCoroutineDispatcher()
        } catch (t: Throwable) {
            runCatching { newEngine.close() }
            throw t
        } finally {
            runCatching { CpuAffinity.restore(originalMask) }
        }
        (System.nanoTime() - started) / 1_000_000_000.0
    }

    suspend fun compress(
        text: String,
        ratioPercent: Int,
        onProgress: (done: Int, total: Int, sourceChars: Int, outputChars: Int) -> Unit,
    ): String {
        require(ratioPercent in setOf(20, 40, 70)) { "Unsupported compression ratio" }
        check(engine != null) { "Compression engine is not loaded" }

        val chunks = splitText(text, CHUNK_CHARS)
        require(chunks.isNotEmpty()) { "No text to compress" }
        val output = StringBuilder((text.length * ratioPercent / 100).coerceAtLeast(1024))

        for ((index, chunk) in chunks.withIndex()) {
            val targetChars = (chunk.length * ratioPercent / 100.0).toInt().coerceAtLeast(80)
            val compressed = generateChunk(chunk, ratioPercent, targetChars)
                .trim()
                .ifBlank { chunk }
            if (output.isNotEmpty()) output.append('\n')
            output.append(compressed)
            onProgress(index + 1, chunks.size, text.length, output.length)
        }
        return output.toString().trim()
    }

    private suspend fun generateChunk(source: String, ratio: Int, targetChars: Int): String {
        val d = dispatcher ?: Dispatchers.Default
        return withContext(d) {
            val active = engine ?: error("Compression engine is not loaded")
            val originalMask = CpuAffinity.currentMask()
            try {
                CpuAffinity.pinFast(2)
                val result = StringBuilder()
                active.createConversation(conversationConfig()).use { conversation ->
                    val prompt = buildString {
                        append("次の小説本文を高速読書向けに圧縮してください。\n")
                        append("原文の約").append(ratio).append("%の長さ、目安")
                            .append(targetChars).append("文字にしてください。\n")
                        append("筋、時系列、人物名、重要な会話、因果関係を保持してください。")
                        append("新しい事実を追加しないでください。文体の雰囲気は可能な範囲で残してください。")
                        append("冗長な描写、反復、長い修飾を優先して削り、一文を短くしてください。")
                        append("要約の解説や見出し、箇条書き、Markdownは禁止です。圧縮後の本文だけを出力してください。\n\n")
                        append("本文:\n").append(source)
                    }
                    conversation.sendMessageAsync(
                        text = prompt,
                        maxOutputToken = MAX_OUTPUT_TOKENS,
                        thinkingConfig = ThinkingConfig(
                            enableThinking = true,
                            thinkingTokenBudget = THINKING_TOKENS,
                        ),
                    ).collect { message ->
                        val s = message.toString()
                        if (s.isNotEmpty()) result.append(s)
                    }
                }
                cleanModelOutput(result.toString())
            } finally {
                runCatching { CpuAffinity.restore(originalMask) }
            }
        }
    }

    private fun conversationConfig() = ConversationConfig(
        tools = emptyList(),
        automaticToolCalling = false,
        channels = null,
        samplerConfig = SamplerConfig(topK = 32, topP = 0.90, temperature = 0.45, seed = 0),
        prefillPrefaceOnInit = false,
        maxOutputToken = MAX_OUTPUT_TOKENS,
        thinkingConfig = ThinkingConfig(enableThinking = true, thinkingTokenBudget = THINKING_TOKENS),
        enableResponseFormat = false,
    )

    /** Prefer paragraph/sentence edges so a chunk does not begin in the middle of a thought. */
    private fun splitText(text: String, maxChars: Int): List<String> {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (normalized.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        var start = 0
        while (start < normalized.length) {
            val hardEnd = (start + maxChars).coerceAtMost(normalized.length)
            if (hardEnd == normalized.length) {
                normalized.substring(start).trim().takeIf { it.isNotEmpty() }?.let(out::add)
                break
            }
            val searchStart = (start + (maxChars * 0.58).toInt()).coerceAtMost(hardEnd)
            var end = -1
            for (i in hardEnd - 1 downTo searchStart) {
                val ch = normalized[i]
                if (ch == '\n' || ch == '。' || ch == '！' || ch == '？') {
                    end = i + 1
                    break
                }
            }
            if (end <= start) end = hardEnd
            normalized.substring(start, end).trim().takeIf { it.isNotEmpty() }?.let(out::add)
            start = end
            while (start < normalized.length && normalized[start].isWhitespace()) start++
        }
        return out
    }

    private fun cleanModelOutput(raw: String): String = raw
        .replace("```", "")
        .replace(Regex("^(圧縮後の本文|圧縮本文|本文)[:：]\\s*"), "")
        .trim()

    override fun close() {
        runCatching { engine?.close() }
        engine = null
        runCatching { dispatcher?.close() }
        dispatcher = null
    }
}

/** Persistent compressed-book cache. Original downloads remain owned by AozoraBookClient. */
internal class AozoraCompressionStore(private val root: File) {
    init { root.mkdirs() }

    fun file(workId: String, ratio: Int): File = File(root, "${sanitize(workId)}_ai${ratio}.txt")

    fun read(workId: String, ratio: Int): String? = file(workId, ratio)
        .takeIf { it.isFile && it.length() > 0L }
        ?.readText(Charsets.UTF_8)
        ?.takeIf { it.isNotBlank() }

    fun write(workId: String, ratio: Int, text: String) {
        val target = file(workId, ratio)
        val temp = File(target.parentFile, target.name + ".partial")
        temp.writeText(text, Charsets.UTF_8)
        if (target.exists()) target.delete()
        check(temp.renameTo(target)) { "Could not save compressed book" }
    }

    private fun sanitize(value: String): String = value.replace(Regex("[^A-Za-z0-9_-]"), "_")
}
