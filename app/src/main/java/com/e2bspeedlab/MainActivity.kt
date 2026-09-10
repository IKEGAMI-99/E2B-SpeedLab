package com.e2bspeedlab

import android.app.Activity
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.google.ai.edge.litertlm.BenchmarkInfo
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : Activity() {

    companion object {
        private const val PICK_MODEL_REQUEST = 7001

        // Keep the legacy private filename so upgrading does not force a 2+ GiB re-import.
        // The original selected filename is now preserved separately for package classification.
        private const val MODEL_FILE_NAME = "gemma-4-E2B-it.litertlm"
        private const val MODEL_NAME_PREF = "active_model_display_name"

        private const val GENERIC_MODEL_BYTES = 2_588_147_712L
        private const val GPU_MODEL_BYTES = 2_008_432_640L
        private const val SIZE_TOLERANCE_BYTES = 96L * 1024L * 1024L

        private const val UI_FLUSH_MS = 32L
        private val EXTREME_STEPS = intArrayOf(1, 2, 4, 8)
    }

    private enum class ModelKind {
        GENERIC,
        GPU_OPT,
        ARTISAN,
        UNKNOWN,
    }

    private data class ExtremeMeasured(
        val result: ExtremeNative.Result,
        val batteryBeforeC: Float?,
        val batteryAfterC: Float?,
        val thermalBefore: Int,
        val thermalAfter: Int,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var speedLab: SpeedLabEngine
    private lateinit var modelFile: File

    private lateinit var statusText: TextView
    private lateinit var modelText: TextView
    private lateinit var metricsText: TextView
    private lateinit var outputText: TextView
    private lateinit var promptInput: EditText
    private lateinit var progress: ProgressBar
    private lateinit var loadButton: Button
    private lateinit var generateButton: Button
    private lateinit var benchmarkButton: Button
    private lateinit var extremeButton: Button
    private lateinit var resetButton: Button
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val modelsDir = File(filesDir, "models").apply { mkdirs() }
        modelFile = File(modelsDir, MODEL_FILE_NAME)
        speedLab = SpeedLabEngine(File(cacheDir, "litertlm"))

        buildUi()
        refreshModelState()
        if (modelFile.exists()) showPackageHint(overwrite = true)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            setBackgroundColor(Color.rgb(10, 12, 16))
        }

        root.addView(TextView(this).apply {
            text = "E2B SPEEDLAB"
            textSize = 25f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })

        root.addView(TextView(this).apply {
            text = "Gemma 4 E2B  •  LiteRT-LM  •  GPU  •  MTP"
            textSize = 12f
            setTextColor(Color.rgb(150, 160, 176))
            setPadding(0, dp(3), 0, dp(14))
        })

        statusText = label("STATUS  NOT LOADED", 14f, Color.rgb(255, 190, 90))
        modelText = label("MODEL  NONE", 12f, Color.rgb(190, 196, 208))
        metricsText = label(
            "DECODE  -- tok/s    PREFILL  -- tok/s\nTTFT  -- ms    INIT  -- s",
            16f,
            Color.rgb(140, 230, 190)
        )

        root.addView(statusText)
        root.addView(modelText)
        root.addView(metricsText, marginParams(top = 10, bottom = 12))

        val modelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val pickButton = actionButton("SELECT MODEL") { selectModel() }
        loadButton = actionButton("LOAD GPU + MTP") { loadEngine() }
        modelRow.addView(pickButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) })
        modelRow.addView(loadButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(6) })
        root.addView(modelRow)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = View.GONE
            isIndeterminate = true
        }
        root.addView(progress, marginParams(top = 8, bottom = 8))

        promptInput = EditText(this).apply {
            setText("Explain why on-device AI should be fast. Keep the answer under 200 words.")
            setTextColor(Color.WHITE)
            setHintTextColor(Color.DKGRAY)
            setBackgroundColor(Color.rgb(24, 28, 36))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            minLines = 2
            maxLines = 4
        }
        root.addView(
            promptInput,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        )

        val runRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        generateButton = actionButton("GENERATE") { generate() }
        benchmarkButton = actionButton("MTP A/B ×3") { runBenchmark() }
        resetButton = actionButton("RESET") { resetConversation() }
        runRow.addView(generateButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(4) })
        runRow.addView(benchmarkButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            marginStart = dp(4)
            marginEnd = dp(4)
        })
        runRow.addView(resetButton, LinearLayout.LayoutParams(0, dp(48), 0.7f).apply { marginStart = dp(4) })
        root.addView(runRow, marginParams(top = 10, bottom = 4))

        extremeButton = actionButton("ARTISAN SYNC SWEEP  •  HW MODEL ONLY") { runExtremeSweep() }
        root.addView(
            extremeButton,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply {
                topMargin = dp(4)
                bottomMargin = dp(10)
            }
        )

        outputText = TextView(this).apply {
            text = "Select a Gemma 4 E2B LiteRT-LM package. For maximum speed, the dedicated gemma-4-E2B-it-gpu.litertlm package is preferred.\n"
            textSize = 15f
            setTextColor(Color.rgb(225, 229, 236))
            setTextIsSelectable(true)
            setPadding(dp(12), dp(12), dp(12), dp(20))
        }

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(15, 18, 24))
            addView(outputText)
        }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        updateControls()
    }

    private fun selectModel() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            },
            PICK_MODEL_REQUEST
        )
    }

    @Deprecated("Legacy callback keeps this prototype dependency-light.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_MODEL_REQUEST || resultCode != RESULT_OK) return
        data?.data?.let(::importModel)
    }

    private fun importModel(uri: Uri) {
        setBusy(true, "IMPORTING MODEL")
        scope.launch {
            try {
                speedLab.close()
                val originalName = queryDisplayName(uri)
                val bytes = withContext(Dispatchers.IO) { copyModel(uri, modelFile) }
                getSharedPreferences("speedlab", MODE_PRIVATE)
                    .edit()
                    .putString(MODEL_NAME_PREF, originalName)
                    .apply()

                statusText.text = "STATUS  MODEL READY"
                refreshModelState()
                showPackageHint(overwrite = true)

                if (currentModelKind() == ModelKind.UNKNOWN) {
                    outputText.append(
                        "\nPackage size: ${formatGiB(bytes)} GiB. This package is not one of SpeedLab's known generic/GPU/Artisan E2B profiles; normal GPU loading is still allowed.\n"
                    )
                }
            } catch (t: Throwable) {
                modelFile.delete()
                getSharedPreferences("speedlab", MODE_PRIVATE).edit().remove(MODEL_NAME_PREF).apply()
                showError("Model import failed", t)
            } finally {
                setBusy(false)
                refreshModelState()
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String {
        var result = uri.lastPathSegment ?: "selected-model.litertlm"
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index)?.takeIf { it.isNotBlank() }?.let { result = it }
                }
            }
        }
        return result.substringAfterLast('/')
    }

    private fun copyModel(uri: Uri, destination: File): Long {
        destination.parentFile?.mkdirs()
        val temp = File(destination.parentFile, destination.name + ".partial")
        temp.delete()
        var copied = 0L
        try {
            contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Unable to open selected file" }
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(8 * 1024 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        if ((copied and ((64L * 1024 * 1024) - 1)) < count) {
                            runOnUiThread { modelText.text = "COPYING  ${formatGiB(copied)} GiB" }
                        }
                    }
                    output.fd.sync()
                }
            }
            check(temp.renameTo(destination)) { "Could not finalize model file" }
            return copied
        } catch (t: Throwable) {
            temp.delete()
            throw t
        }
    }

    private fun loadEngine() {
        if (!modelFile.exists()) return
        setBusy(true, "INITIALIZING GPU + MTP")
        outputText.text = "Loading ${modelKindLabel(currentModelKind())} E2B package with Backend.GPU() + MTP.\n"
        scope.launch {
            try {
                val seconds = speedLab.load(modelFile.absolutePath)
                statusText.text = "STATUS  GPU + MTP ACTIVE"
                metricsText.text = "DECODE  -- tok/s    PREFILL  -- tok/s\nTTFT  -- ms    LOAD  ${f(seconds, 2)} s"
                outputText.append(
                    "Engine ready. Context=${SpeedLabEngine.MAX_CONTEXT_TOKENS}, max output=${SpeedLabEngine.MAX_OUTPUT_TOKENS}.\n"
                )
            } catch (t: Throwable) {
                showError("GPU engine failed to initialize", t)
            } finally {
                setBusy(false)
            }
        }
    }

    private fun generate() {
        val prompt = promptInput.text.toString().trim()
        if (prompt.isEmpty() || !speedLab.isLoaded) return
        setBusy(true, "GENERATING")
        outputText.text = ""

        scope.launch {
            val uiBatch = StringBuilder(256)
            var lastFlush = SystemClock.uptimeMillis()
            try {
                val info = speedLab.generate(prompt) { chunk ->
                    uiBatch.append(chunk)
                    val now = SystemClock.uptimeMillis()
                    if (now - lastFlush >= UI_FLUSH_MS) {
                        val text = uiBatch.toString()
                        uiBatch.setLength(0)
                        lastFlush = now
                        outputText.post { outputText.append(text) }
                    }
                }
                if (uiBatch.isNotEmpty()) outputText.append(uiBatch.toString())
                showMetrics(info)
                statusText.text = "STATUS  READY"
            } catch (t: Throwable) {
                showError("Generation failed", t)
            } finally {
                setBusy(false)
            }
        }
    }

    private fun runBenchmark() {
        if (!modelFile.exists()) return
        setBusy(true, "MTP A/B MULTI-RUN")
        outputText.text =
            "Package: ${modelKindLabel(currentModelKind())} • ${activeModelDisplayName()}\n" +
                "Backend: GPU\n" +
                "Balanced MTP A/B benchmark.\n" +
                "Warmup OFF → ON is discarded. Measured order is OFF → ON → ON → OFF → OFF → ON.\n" +
                "Final result uses the median of three samples per mode.\n\n"

        scope.launch {
            try {
                val result = speedLab.benchmarkMtpComparison(modelFile.absolutePath) { stage ->
                    statusText.post { statusText.text = "STATUS  $stage" }
                }
                showComparison(result)
                statusText.text = "STATUS  MTP A/B ×3 COMPLETE"
            } catch (t: Throwable) {
                showError("MTP A/B benchmark failed", t)
            } finally {
                setBusy(false)
            }
        }
    }

    /**
     * num_decode_steps_per_sync is an Artisan-only knob upstream. The normal public E2B package
     * and the dedicated -gpu package both use the regular GPU backend, so deliberately refuse to
     * force them through GPU_ARTISAN. v0.1.8 did that and could kill the child process.
     */
    private fun runExtremeSweep() {
        if (!modelFile.exists()) return
        if (currentModelKind() != ModelKind.ARTISAN) {
            statusText.text = "STATUS  ARTISAN MODEL REQUIRED"
            outputText.text = artisanUnavailableMessage()
            updateControls()
            return
        }

        setBusy(true, "ARTISAN PREPARING")
        outputText.text =
            "ARTISAN EXTREME MODE\n" +
                "Direct LiteRT-LM C API benchmark with MTP ON.\n" +
                "Testing GPU Artisan num_decode_steps_per_sync = 1 / 2 / 4 / 8.\n" +
                "512-token prefill + 256-token decode. One steps=1 warmup is discarded.\n" +
                "Battery temperature and Android thermal status are recorded around every run.\n\n"

        scope.launch {
            try {
                speedLab.close()
                val extremeCache = File(cacheDir, "litertlm_extreme").apply { mkdirs() }

                statusText.text = "STATUS  ARTISAN WARMUP • SYNC 1"
                val warmTemp = batteryTempC()
                val warmup = withContext(Dispatchers.Default) {
                    ExtremeNative.benchmark(modelFile.absolutePath, extremeCache.absolutePath, 1, true)
                }
                outputText.append(
                    "WARMUP discarded: ${f(warmup.decodeTokensPerSecond, 1)} tok/s" +
                        tempSuffix(warmTemp, batteryTempC()) + "\n\n"
                )

                val measured = ArrayList<ExtremeMeasured>(EXTREME_STEPS.size)
                EXTREME_STEPS.forEachIndexed { index, steps ->
                    val beforeTemp = batteryTempC()
                    val beforeThermal = thermalStatus()
                    statusText.text = "STATUS  ARTISAN ${index + 1}/${EXTREME_STEPS.size} • SYNC $steps"

                    val result = withContext(Dispatchers.Default) {
                        ExtremeNative.benchmark(
                            modelPath = modelFile.absolutePath,
                            cacheDir = extremeCache.absolutePath,
                            decodeStepsPerSync = steps,
                            enableMtp = true,
                        )
                    }
                    val afterTemp = batteryTempC()
                    val afterThermal = thermalStatus()
                    measured += ExtremeMeasured(result, beforeTemp, afterTemp, beforeThermal, afterThermal)

                    outputText.append(
                        "SYNC $steps  ${f(result.decodeTokensPerSecond, 1)} tok/s" +
                            "  prefill ${f(result.prefillTokensPerSecond, 0)} tok/s" +
                            "  TTFT ${f(result.ttftSeconds * 1000.0, 0)} ms" +
                            tempSuffix(beforeTemp, afterTemp) +
                            "  thermal ${thermalName(afterThermal)}\n"
                    )
                }

                val best = measured.maxBy { it.result.decodeTokensPerSecond }
                val baseline = measured.first { it.result.decodeStepsPerSync == 1 }
                val speedup = best.result.decodeTokensPerSecond / baseline.result.decodeTokensPerSecond
                val gain = (speedup - 1.0) * 100.0

                getSharedPreferences("speedlab", MODE_PRIVATE)
                    .edit()
                    .putInt("best_decode_steps_per_sync", best.result.decodeStepsPerSync)
                    .putFloat("best_extreme_tps", best.result.decodeTokensPerSecond.toFloat())
                    .apply()

                metricsText.text =
                    "ARTISAN BEST  ${f(best.result.decodeTokensPerSecond, 1)} tok/s\n" +
                        "SYNC ${best.result.decodeStepsPerSync}   vs SYNC 1  ${f(speedup, 2)}× (${signed(gain)}%)"
                statusText.text = "STATUS  ARTISAN COMPLETE • BEST SYNC ${best.result.decodeStepsPerSync}"

                outputText.append(
                    "\nRESULT\n" +
                        "  Best decode steps/sync: ${best.result.decodeStepsPerSync}\n" +
                        "  Best decode: ${f(best.result.decodeTokensPerSecond, 1)} tok/s\n" +
                        "  Baseline sync=1: ${f(baseline.result.decodeTokensPerSecond, 1)} tok/s\n" +
                        "  Native tuning gain: ${f(speedup, 2)}× (${signed(gain)}%)\n" +
                        "  Best TTFT: ${f(best.result.ttftSeconds * 1000.0, 0)} ms\n" +
                        "  Best prefill: ${f(best.result.prefillTokensPerSecond, 0)} tok/s\n" +
                        "  End battery temp: ${tempText(best.batteryAfterC)}\n" +
                        "  End thermal: ${thermalName(best.thermalAfter)}\n\n" +
                        "Reload GPU + MTP to return to normal chat.\n"
                )
            } catch (t: Throwable) {
                showError("Artisan sweep failed", t)
            } finally {
                setBusy(false)
            }
        }
    }

    private fun showComparison(result: SpeedLabEngine.MtpComparison) {
        val off = result.mtpOff
        val on = result.mtpOn
        val decodeGainPercent = (result.decodeSpeedup - 1.0) * 100.0
        val ttftChangePercent = (result.ttftRatio - 1.0) * 100.0

        metricsText.text =
            "MTP ON   ${f(on.decodeMedian, 1)} tok/s median\n" +
                "MTP OFF  ${f(off.decodeMedian, 1)} tok/s   SPEEDUP ${f(result.decodeSpeedup, 2)}×"

        outputText.append(
            "WARMUP (discarded)\n" +
                "  OFF: ${f(result.warmupOff.lastDecodeTokensPerSecond, 1)} tok/s\n" +
                "  ON : ${f(result.warmupOn.lastDecodeTokensPerSecond, 1)} tok/s\n\n" +
                statsBlock("MTP OFF", off) + "\n" +
                statsBlock("MTP ON", on) + "\n" +
                "RESULT — MEDIAN\n" +
                "  Decode speedup: ${f(result.decodeSpeedup, 2)}× (${signed(decodeGainPercent)}%)\n" +
                "  Prefill ratio: ${f(result.prefillSpeedup, 2)}×\n" +
                "  TTFT change: ${signed(ttftChangePercent)}%\n" +
                "  Backend: GPU\n" +
                "  Package: ${modelKindLabel(currentModelKind())}\n" +
                "  Samples: ${SpeedLabEngine.BENCH_SAMPLES_PER_MODE} per mode\n\n" +
                "Reload GPU + MTP before using GENERATE again.\n"
        )
    }

    private fun statsBlock(title: String, stats: SpeedLabEngine.BenchStats): String {
        val decodeSamples = stats.samples.joinToString(", ") { f(it.lastDecodeTokensPerSecond, 1) }
        val prefillSamples = stats.samples.joinToString(", ") { f(it.lastPrefillTokensPerSecond, 0) }
        val ttftSamples = stats.samples.joinToString(", ") { f(it.timeToFirstTokenInSecond * 1000.0, 0) }
        return title + "\n" +
            "  Decode median: ${f(stats.decodeMedian, 1)} tok/s\n" +
            "  Decode range: ${f(stats.decodeMin, 1)}–${f(stats.decodeMax, 1)} tok/s\n" +
            "  Decode runs: [$decodeSamples]\n" +
            "  Prefill median: ${f(stats.prefillMedian, 0)} tok/s\n" +
            "  Prefill runs: [$prefillSamples]\n" +
            "  TTFT median: ${f(stats.ttftMedianSeconds * 1000.0, 0)} ms\n" +
            "  TTFT runs: [$ttftSamples] ms\n" +
            "  Init median: ${f(stats.initMedianSeconds, 2)} s\n"
    }

    private fun resetConversation() {
        if (!speedLab.isLoaded) return
        setBusy(true, "RESETTING KV CACHE")
        scope.launch {
            try {
                speedLab.resetConversation()
                outputText.text = "Conversation reset. Engine and compiled GPU cache remain loaded.\n"
                statusText.text = "STATUS  GPU + MTP ACTIVE"
            } catch (t: Throwable) {
                showError("Reset failed", t)
            } finally {
                setBusy(false)
            }
        }
    }

    private fun showMetrics(info: BenchmarkInfo) {
        metricsText.text =
            "DECODE  ${f(info.lastDecodeTokensPerSecond, 1)} tok/s    " +
                "PREFILL  ${f(info.lastPrefillTokensPerSecond, 0)} tok/s\n" +
                "TTFT  ${f(info.timeToFirstTokenInSecond * 1000.0, 0)} ms    " +
                "INIT  ${f(info.initTimeInSecond, 2)} s"
    }

    private fun batteryTempC(): Float? {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return if (tenths == Int.MIN_VALUE) null else tenths / 10f
    }

    private fun thermalStatus(): Int =
        (getSystemService(POWER_SERVICE) as PowerManager).currentThermalStatus

    private fun thermalName(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "NONE"
        PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
        PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
        PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
        PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
        else -> status.toString()
    }

    private fun tempSuffix(before: Float?, after: Float?): String =
        if (before == null && after == null) "" else "  temp ${tempText(before)}→${tempText(after)}"

    private fun tempText(value: Float?): String = value?.let { f(it.toDouble(), 1) + "°C" } ?: "--"

    private fun activeModelDisplayName(): String {
        val prefsName = getSharedPreferences("speedlab", MODE_PRIVATE).getString(MODEL_NAME_PREF, null)
        if (!prefsName.isNullOrBlank()) return prefsName
        return when (classifyModel(null, modelFile.length())) {
            ModelKind.GENERIC -> "gemma-4-E2B-it.litertlm"
            ModelKind.GPU_OPT -> "gemma-4-E2B-it-gpu.litertlm"
            ModelKind.ARTISAN -> "Gemma 4 E2B Artisan/HW package"
            ModelKind.UNKNOWN -> MODEL_FILE_NAME
        }
    }

    private fun currentModelKind(): ModelKind =
        if (!modelFile.exists()) ModelKind.UNKNOWN else classifyModel(activeModelDisplayName(), modelFile.length())

    private fun classifyModel(name: String?, bytes: Long): ModelKind {
        val lower = name.orEmpty().lowercase(Locale.US)
        if ("e2b-hw" in lower || "artisan" in lower) return ModelKind.ARTISAN
        if ("e2b-it-gpu" in lower || nearSize(bytes, GPU_MODEL_BYTES)) return ModelKind.GPU_OPT
        if (nearSize(bytes, GENERIC_MODEL_BYTES)) return ModelKind.GENERIC
        return ModelKind.UNKNOWN
    }

    private fun nearSize(actual: Long, expected: Long): Boolean =
        kotlin.math.abs(actual - expected) <= SIZE_TOLERANCE_BYTES

    private fun modelKindLabel(kind: ModelKind): String = when (kind) {
        ModelKind.GENERIC -> "GENERIC GPU/CPU"
        ModelKind.GPU_OPT -> "DEDICATED GPU"
        ModelKind.ARTISAN -> "GPU ARTISAN/HW"
        ModelKind.UNKNOWN -> "UNKNOWN"
    }

    private fun showPackageHint(overwrite: Boolean) {
        if (!modelFile.exists()) return
        val text = when (currentModelKind()) {
            ModelKind.GPU_OPT ->
                "DEDICATED GPU package detected.\n" +
                    "Use LOAD GPU + MTP and MTP A/B ×3. This is the fastest public E2B package path SpeedLab currently targets.\n" +
                    "Artisan SYNC tuning is intentionally disabled because the public -gpu package uses the regular GPU backend.\n"

            ModelKind.GENERIC ->
                "GENERIC E2B package detected.\n" +
                    "GPU + MTP is supported. For the next speed test, select gemma-4-E2B-it-gpu.litertlm instead.\n" +
                    "Artisan SYNC tuning is intentionally disabled for this package.\n"

            ModelKind.ARTISAN ->
                "GPU ARTISAN/HW package detected. Artisan SYNC 1/2/4/8 sweep is enabled.\n"

            ModelKind.UNKNOWN ->
                "Unknown E2B package. Normal Backend.GPU() loading is allowed, but Artisan-only tuning stays disabled unless the package is explicitly identified as an Artisan/HW model.\n"
        }
        if (overwrite) outputText.text = text else outputText.append(text)
    }

    private fun artisanUnavailableMessage(): String =
        "ARTISAN SWEEP NOT AVAILABLE FOR THIS PACKAGE\n\n" +
            "Current package: ${modelKindLabel(currentModelKind())} • ${activeModelDisplayName()}\n\n" +
            "LiteRT-LM's num_decode_steps_per_sync setting is currently supported only by the GPU_ARTISAN backend. " +
            "The public gemma-4-E2B-it.litertlm and gemma-4-E2B-it-gpu.litertlm packages are used through Backend.GPU(), " +
            "so SpeedLab will not force them through GPU_ARTISAN and crash the child process.\n\n" +
            "Use MTP A/B ×3 for this model.\n"

    private fun refreshModelState() {
        if (modelFile.exists()) {
            modelText.text =
                "MODEL  ${formatGiB(modelFile.length())} GiB  •  ${modelKindLabel(currentModelKind())}\n" +
                    activeModelDisplayName()
            if (!speedLab.isLoaded && !busy) statusText.text = "STATUS  MODEL READY"
        } else {
            modelText.text = "MODEL  NONE"
            statusText.text = "STATUS  SELECT E2B MODEL"
        }
        updateControls()
    }

    private fun setBusy(value: Boolean, status: String? = null) {
        busy = value
        progress.visibility = if (value) View.VISIBLE else View.GONE
        if (status != null) statusText.text = "STATUS  $status"
        updateControls()
    }

    private fun updateControls() {
        loadButton.isEnabled = !busy && modelFile.exists()
        generateButton.isEnabled = !busy && speedLab.isLoaded
        benchmarkButton.isEnabled = !busy && modelFile.exists()
        extremeButton.isEnabled = !busy && modelFile.exists() && currentModelKind() == ModelKind.ARTISAN
        extremeButton.text = if (currentModelKind() == ModelKind.ARTISAN) {
            "ARTISAN SWEEP  •  GPU SYNC 1 / 2 / 4 / 8"
        } else {
            "ARTISAN SYNC SWEEP  •  HW MODEL ONLY"
        }
        resetButton.isEnabled = !busy && speedLab.isLoaded
    }

    private fun showError(prefix: String, t: Throwable) {
        statusText.text = "STATUS  ERROR"
        outputText.append("\n$prefix:\n${t.javaClass.simpleName}: ${t.message ?: "unknown error"}\n")
    }

    private fun label(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
    }

    private fun actionButton(value: String, action: () -> Unit) = Button(this).apply {
        text = value
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun marginParams(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        }

    private fun formatGiB(bytes: Long): String = f(bytes / 1024.0 / 1024.0 / 1024.0, 2)

    private fun f(value: Double, decimals: Int): String =
        String.format(Locale.US, "%.${decimals}f", value)

    private fun signed(value: Double): String =
        if (value >= 0.0) "+${f(value, 1)}" else f(value, 1)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        speedLab.close()
        scope.cancel()
        super.onDestroy()
    }
}
