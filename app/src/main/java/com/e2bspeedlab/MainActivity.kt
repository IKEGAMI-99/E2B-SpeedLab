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
        private const val MODEL_FILE_NAME = "gemma-4-E2B-it.litertlm"
        private const val MODEL_NAME_PREF = "active_model_display_name"

        private const val GENERIC_MODEL_BYTES = 2_588_147_712L
        private const val GPU_MODEL_BYTES = 2_008_432_640L
        private const val SIZE_TOLERANCE_BYTES = 96L * 1024L * 1024L

        private const val UI_FLUSH_MS = 32L
        private const val TURBO_VERIFY_SAMPLES = 5
        private val ARTISAN_STEPS = intArrayOf(1, 2, 4, 8)
        private val TURBO_CONTEXTS = intArrayOf(1024, 1536, 2048, 4096)

        private const val PREF_LAST_KOTLIN_MODEL = "last_kotlin_model"
        private const val PREF_LAST_KOTLIN_MTP_TPS = "last_kotlin_mtp_tps"
        private const val PREF_TURBO_MODEL = "turbo_model"
        private const val PREF_TURBO_CONTEXT = "turbo_context"
        private const val PREF_TURBO_FAST_CPUS = "turbo_fast_cpus"
        private const val PREF_TURBO_TPS = "turbo_tps"
    }

    private enum class ModelKind { GENERIC, GPU_OPT, ARTISAN, UNKNOWN }

    private data class MeasuredNative(
        val result: NativeGpu.Result,
        val batteryBeforeC: Float?,
        val batteryAfterC: Float?,
        val thermalBefore: Int,
        val thermalAfter: Int,
    )

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
    private lateinit var turboButton: Button
    private lateinit var extremeButton: Button
    private lateinit var resetButton: Button
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        modelFile = File(File(filesDir, "models").apply { mkdirs() }, MODEL_FILE_NAME)
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
            text = "Gemma 4 E2B  •  LiteRT-LM  •  GPU  •  MTP  •  TURBO"
            textSize = 12f
            setTextColor(Color.rgb(150, 160, 176))
            setPadding(0, dp(3), 0, dp(14))
        })

        statusText = label("STATUS  NOT LOADED", 14f, Color.rgb(255, 190, 90))
        modelText = label("MODEL  NONE", 12f, Color.rgb(190, 196, 208))
        metricsText = label(
            "DECODE  -- tok/s    PREFILL  -- tok/s\nTTFT  -- ms    INIT  -- s",
            16f,
            Color.rgb(140, 230, 190),
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
        root.addView(promptInput)

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

        turboButton = actionButton("TURBO SWEEP  •  STABLE GPU") { runTurboSweep() }
        root.addView(turboButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply {
            topMargin = dp(4)
            bottomMargin = dp(4)
        })

        extremeButton = actionButton("ARTISAN SYNC SWEEP  •  HW MODEL ONLY") { runExtremeSweep() }
        root.addView(extremeButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply {
            topMargin = dp(4)
            bottomMargin = dp(10)
        })

        outputText = TextView(this).apply {
            text = "Select a Gemma 4 E2B LiteRT-LM package. Generic + MTP currently has the best measured decode path; TURBO tunes the same proven Maven GPU runtime.\n"
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
            PICK_MODEL_REQUEST,
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
                prefs().edit().putString(MODEL_NAME_PREF, originalName).apply()
                statusText.text = "STATUS  MODEL READY"
                refreshModelState()
                showPackageHint(overwrite = true)
                if (currentModelKind() == ModelKind.UNKNOWN) {
                    outputText.append("\nUnknown package size: ${formatGiB(bytes)} GiB. Regular GPU tests remain available.\n")
                }
            } catch (t: Throwable) {
                modelFile.delete()
                prefs().edit().remove(MODEL_NAME_PREF).apply()
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

        val profileMatches = prefs().getString(PREF_TURBO_MODEL, null) == activeModelDisplayName()
        val tunedContext = if (profileMatches) {
            prefs().getInt(PREF_TURBO_CONTEXT, SpeedLabEngine.MAX_CONTEXT_TOKENS)
        } else {
            SpeedLabEngine.MAX_CONTEXT_TOKENS
        }.coerceIn(768, 4096)
        val tunedFastCpus = if (profileMatches) {
            prefs().getInt(PREF_TURBO_FAST_CPUS, 0).takeIf { it == 2 || it == 4 } ?: 0
        } else {
            0
        }
        val profileLabel = if (profileMatches) {
            "TURBO PROFILE • CTX $tunedContext • ${cpuModeLabel(tunedFastCpus)}"
        } else {
            "DEFAULT • CTX ${SpeedLabEngine.MAX_CONTEXT_TOKENS} • CPU ALL"
        }

        setBusy(true, "INITIALIZING GPU + MTP")
        outputText.text =
            "Loading ${modelKindLabel(currentModelKind())} with Maven LiteRT-LM GPU + MTP.\n" +
                "$profileLabel\n"
        scope.launch {
            try {
                val seconds = speedLab.load(
                    modelPath = modelFile.absolutePath,
                    maxContext = tunedContext,
                    fastestCpuCount = tunedFastCpus,
                )
                statusText.text = if (profileMatches) {
                    "STATUS  GPU + MTP • TURBO PROFILE"
                } else {
                    "STATUS  GPU + MTP ACTIVE"
                }
                metricsText.text =
                    "DECODE  -- tok/s    PREFILL  -- tok/s\n" +
                        "TTFT  -- ms    LOAD  ${f(seconds, 2)} s"
                outputText.append(
                    "Engine ready. Context=${speedLab.loadedContextTokens}; " +
                        "${cpuModeLabel(speedLab.loadedFastestCpuCount)}; " +
                        "init mask=${maskText(speedLab.loadedAffinityMask)}.\n"
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
            "Maven LiteRT-LM 0.17.0-alpha1\n" +
                "Package: ${modelKindLabel(currentModelKind())} • ${activeModelDisplayName()}\n" +
                "Warmup OFF → ON discarded; measured OFF → ON → ON → OFF → OFF → ON.\n\n"
        scope.launch {
            try {
                val result = speedLab.benchmarkMtpComparison(modelFile.absolutePath) { stage ->
                    statusText.post { statusText.text = "STATUS  $stage" }
                }
                showComparison(result)
                prefs().edit()
                    .putString(PREF_LAST_KOTLIN_MODEL, activeModelDisplayName())
                    .putFloat(PREF_LAST_KOTLIN_MTP_TPS, result.mtpOn.decodeMedian.toFloat())
                    .apply()
                statusText.text = "STATUS  MTP A/B ×3 COMPLETE"
            } catch (t: Throwable) {
                showError("MTP A/B benchmark failed", t)
            } finally {
                setBusy(false)
            }
        }
    }

    /**
     * Tunes only the Maven GPU runtime already proven stable on the target device.
     * Candidate search is cheap (one run each), then the winning context/CPU configuration is
     * verified with five OFF and five ON samples in a near-balanced order before it is persisted.
     */
    private fun runTurboSweep() {
        if (!modelFile.exists()) return
        if (currentModelKind() == ModelKind.ARTISAN) {
            statusText.text = "STATUS  USE ARTISAN SWEEP"
            outputText.text = "TURBO uses the regular GPU backend. This package is identified as Artisan/HW; use ARTISAN SWEEP instead.\n"
            return
        }

        setBusy(true, "TURBO INSPECT")
        outputText.text =
            "TURBO SWEEP\n" +
                "Stable Maven LiteRT-LM 0.17.0-alpha1 • regular GPU backend\n" +
                "1) Inspect known package capability\n" +
                "2) Sweep context 1024 / 1536 / 2048 / 4096\n" +
                "3) Sweep CPU affinity ALL / FAST4 / FAST2\n" +
                "4) Verify winner with MTP OFF/ON ×$TURBO_VERIFY_SAMPLES each\n" +
                "5) Save verified winner as the normal LOAD profile\n\n"

        scope.launch {
            try {
                speedLab.close()
                val nativeCache = File(cacheDir, "litertlm_nativegpu").apply { mkdirs() }

                val capabilities = withContext(Dispatchers.Default) {
                    NativeGpu.inspect(modelFile.absolutePath)
                }
                appendCapabilities(capabilities)

                val mtpForSweep = capabilities.supportsMtp != false
                if (capabilities.supportsMtp == false) {
                    outputText.append(
                        "\nPackage reports MTP: NO. TURBO will measure the regular-GPU path with MTP OFF.\n"
                    )
                }

                val contexts = turboContexts(capabilities)
                check(contexts.isNotEmpty()) { "No safe context size is available for this package" }
                val warmContext = contexts.minBy { kotlin.math.abs(it - 2048) }

                statusText.text = "STATUS  TURBO WARMUP • CTX $warmContext"
                val warmup = nativeMeasure(nativeCache, warmContext, mtpForSweep, 0)
                outputText.append(
                    "\nWARMUP discarded: ${f(warmup.result.decodeTokensPerSecond, 1)} tok/s" +
                        "  CTX $warmContext  CPU ALL" + tempSuffix(warmup.batteryBeforeC, warmup.batteryAfterC) + "\n\n"
                )

                val contextRuns = ArrayList<MeasuredNative>()
                contexts.forEachIndexed { index, contextSize ->
                    statusText.text = "STATUS  TURBO CTX ${index + 1}/${contexts.size} • $contextSize"
                    try {
                        val measured = nativeMeasure(nativeCache, contextSize, mtpForSweep, 0)
                        contextRuns += measured
                        outputText.append(nativeLine("CTX $contextSize / ALL", measured))
                    } catch (t: Throwable) {
                        outputText.append("CTX $contextSize / ALL  FAILED: ${t.message ?: t.javaClass.simpleName}\n")
                    }
                }
                check(contextRuns.isNotEmpty()) { "Every context candidate failed" }
                val bestContextRun = contextRuns.maxBy { it.result.decodeTokensPerSecond }
                val bestContext = bestContextRun.result.maxContext

                outputText.append("\nCPU AFFINITY @ CTX $bestContext\n")
                val affinityRuns = ArrayList<MeasuredNative>()
                affinityRuns += bestContextRun
                intArrayOf(4, 2).forEachIndexed { index, fastCpus ->
                    statusText.text = "STATUS  TURBO CPU ${index + 1}/2 • FAST$fastCpus"
                    try {
                        val measured = nativeMeasure(nativeCache, bestContext, mtpForSweep, fastCpus)
                        affinityRuns += measured
                        outputText.append(nativeLine("CTX $bestContext / FAST$fastCpus", measured))
                    } catch (t: Throwable) {
                        outputText.append("CTX $bestContext / FAST$fastCpus  FAILED: ${t.message ?: t.javaClass.simpleName}\n")
                    }
                }

                val searchWinner = affinityRuns.maxBy { it.result.decodeTokensPerSecond }
                val bestCpuLabel = cpuModeLabel(searchWinner.result.fastestCpuCount)

                var verifiedOnMedian = searchWinner.result.decodeTokensPerSecond
                var verifiedOffMedian: Double? = null
                var verifiedPrefillMedian = searchWinner.result.prefillTokensPerSecond
                var verifiedTtftMedian = searchWinner.result.ttftSeconds
                var verifiedMask = searchWinner.result.affinityMask

                if (mtpForSweep) {
                    outputText.append(
                        "\nTURBO VERIFY ×$TURBO_VERIFY_SAMPLES @ CTX ${searchWinner.result.maxContext} / $bestCpuLabel\n"
                    )

                    statusText.text = "STATUS  VERIFY WARMUP • MTP OFF"
                    val verifyWarmOff = nativeMeasure(
                        nativeCache,
                        searchWinner.result.maxContext,
                        false,
                        searchWinner.result.fastestCpuCount,
                    )
                    statusText.text = "STATUS  VERIFY WARMUP • MTP ON"
                    val verifyWarmOn = nativeMeasure(
                        nativeCache,
                        searchWinner.result.maxContext,
                        true,
                        searchWinner.result.fastestCpuCount,
                    )
                    outputText.append(
                        "Warmup discarded: OFF ${f(verifyWarmOff.result.decodeTokensPerSecond, 1)} / " +
                            "ON ${f(verifyWarmOn.result.decodeTokensPerSecond, 1)} tok/s\n"
                    )

                    // Five samples per mode. Position sums differ by only one (27 vs 28), limiting
                    // simple linear thermal/order bias while still avoiding long same-mode streaks.
                    val verifyOrder = listOf(false, true, true, false, false, true, true, false, false, true)
                    val offRuns = ArrayList<MeasuredNative>(TURBO_VERIFY_SAMPLES)
                    val onRuns = ArrayList<MeasuredNative>(TURBO_VERIFY_SAMPLES)

                    verifyOrder.forEachIndexed { index, mtpOn ->
                        val mode = if (mtpOn) "ON" else "OFF"
                        statusText.text = "STATUS  TURBO VERIFY ${index + 1}/${verifyOrder.size} • MTP $mode"
                        val measured = nativeMeasure(
                            nativeCache,
                            searchWinner.result.maxContext,
                            mtpOn,
                            searchWinner.result.fastestCpuCount,
                        )
                        if (mtpOn) onRuns += measured else offRuns += measured
                    }

                    verifiedOnMedian = median(onRuns.map { it.result.decodeTokensPerSecond })
                    verifiedOffMedian = median(offRuns.map { it.result.decodeTokensPerSecond })
                    verifiedPrefillMedian = median(onRuns.map { it.result.prefillTokensPerSecond })
                    verifiedTtftMedian = median(onRuns.map { it.result.ttftSeconds })
                    verifiedMask = onRuns.firstOrNull()?.result?.affinityMask ?: searchWinner.result.affinityMask

                    val offValues = offRuns.joinToString(", ") { f(it.result.decodeTokensPerSecond, 1) }
                    val onValues = onRuns.joinToString(", ") { f(it.result.decodeTokensPerSecond, 1) }
                    val tempStart = (offRuns + onRuns).firstOrNull()?.batteryBeforeC
                    val tempEnd = (offRuns + onRuns).lastOrNull()?.batteryAfterC
                    val worstThermal = (offRuns + onRuns).maxOfOrNull { it.thermalAfter } ?: thermalStatus()

                    outputText.append("MTP OFF runs: [$offValues]  median ${f(verifiedOffMedian, 1)} tok/s\n")
                    outputText.append("MTP ON  runs: [$onValues]  median ${f(verifiedOnMedian, 1)} tok/s\n")
                    outputText.append(
                        "Verify temp: ${tempText(tempStart)}→${tempText(tempEnd)}  thermal ${thermalName(worstThermal)}\n"
                    )
                }

                val mtpSpeedup = verifiedOffMedian
                    ?.takeIf { it > 0.0 }
                    ?.let { verifiedOnMedian / it }

                prefs().edit()
                    .putString(PREF_TURBO_MODEL, activeModelDisplayName())
                    .putInt(PREF_TURBO_CONTEXT, searchWinner.result.maxContext)
                    .putInt(PREF_TURBO_FAST_CPUS, searchWinner.result.fastestCpuCount)
                    .putFloat(PREF_TURBO_TPS, verifiedOnMedian.toFloat())
                    .apply()

                metricsText.text =
                    "TURBO VERIFIED  ${f(verifiedOnMedian, 1)} tok/s median\n" +
                        "CTX ${searchWinner.result.maxContext}  $bestCpuLabel" +
                        (mtpSpeedup?.let { "  MTP ${f(it, 2)}×" } ?: "")
                statusText.text = "STATUS  TURBO VERIFIED ×$TURBO_VERIFY_SAMPLES"

                outputText.append("\nRESULT — VERIFIED MEDIAN\n")
                outputText.append("  Runtime: Maven LiteRT-LM 0.17.0-alpha1 GPU\n")
                outputText.append("  Decode: ${f(verifiedOnMedian, 1)} tok/s\n")
                outputText.append("  Best context: ${searchWinner.result.maxContext}\n")
                outputText.append("  Best CPU mode: $bestCpuLabel\n")
                outputText.append("  CPU mask: ${maskText(verifiedMask)}\n")
                outputText.append("  Prefill median: ${f(verifiedPrefillMedian, 0)} tok/s\n")
                outputText.append("  TTFT median: ${f(verifiedTtftMedian * 1000.0, 0)} ms\n")
                if (mtpSpeedup != null) {
                    outputText.append(
                        "  MTP speedup: ${f(mtpSpeedup, 2)}× (${signed((mtpSpeedup - 1.0) * 100.0)}%)\n"
                    )
                }
                appendBaselineComparison(verifiedOnMedian)
                outputText.append(
                    "\nSaved as the normal LOAD profile. LOAD GPU + MTP will now initialize with " +
                        "CTX ${searchWinner.result.maxContext} / $bestCpuLabel.\n"
                )
            } catch (t: Throwable) {
                showError("Turbo sweep failed", t)
            } finally {
                setBusy(false)
            }
        }
    }

    private suspend fun nativeMeasure(
        cache: File,
        context: Int,
        mtp: Boolean,
        fastCpus: Int,
    ): MeasuredNative {
        val beforeTemp = batteryTempC()
        val beforeThermal = thermalStatus()
        val result = withContext(Dispatchers.Default) {
            NativeGpu.benchmark(
                modelPath = modelFile.absolutePath,
                cacheDir = cache.absolutePath,
                maxContext = context,
                enableMtp = mtp,
                fastestCpuCount = fastCpus,
            )
        }
        return MeasuredNative(
            result = result,
            batteryBeforeC = beforeTemp,
            batteryAfterC = batteryTempC(),
            thermalBefore = beforeThermal,
            thermalAfter = thermalStatus(),
        )
    }

    private fun turboContexts(capabilities: NativeGpu.Capabilities): List<Int> {
        val max = capabilities.maxContext?.takeIf { it > 0 } ?: Int.MAX_VALUE
        if (capabilities.dynamicContext == false) {
            val fixed = capabilities.maxContext?.takeIf { it in 768..4096 } ?: 2048
            return listOf(fixed)
        }
        return TURBO_CONTEXTS.filter { it <= max }
    }

    private fun appendCapabilities(cap: NativeGpu.Capabilities) {
        outputText.append("PACKAGE INSPECT\n")
        outputText.append("  Runtime: ${cap.runtime ?: "unknown"}\n")
        outputText.append("  MTP packaged: ${when (cap.supportsMtp) { true -> "YES"; false -> "NO"; null -> "UNKNOWN" }}\n")
        outputText.append("  Max context: ${cap.maxContext?.toString() ?: "UNKNOWN"}\n")
        outputText.append("  Dynamic context: ${when (cap.dynamicContext) { true -> "YES"; false -> "NO"; null -> "UNKNOWN" }}\n")
        outputText.append("  Text backends: ${cap.backends.ifEmpty { listOf("unknown") }.joinToString(", ")}\n")
        cap.minRuntime?.let { outputText.append("  Min runtime: $it\n") }
    }

    private fun nativeLine(label: String, measured: MeasuredNative): String {
        val r = measured.result
        return "$label  ${f(r.decodeTokensPerSecond, 1)} tok/s" +
            "  prefill ${f(r.prefillTokensPerSecond, 0)}" +
            "  TTFT ${f(r.ttftSeconds * 1000.0, 0)} ms" +
            "  mask ${maskText(r.affinityMask)}" +
            tempSuffix(measured.batteryBeforeC, measured.batteryAfterC) +
            "  thermal ${thermalName(measured.thermalAfter)}\n"
    }

    private fun appendBaselineComparison(turboTps: Double) {
        val savedModel = prefs().getString(PREF_LAST_KOTLIN_MODEL, null)
        val baselineTps = prefs().getFloat(PREF_LAST_KOTLIN_MTP_TPS, 0f).toDouble()
        if (savedModel == activeModelDisplayName() && baselineTps > 0.0) {
            val ratio = turboTps / baselineTps
            outputText.append("\nBASELINE COMPARISON\n")
            outputText.append("  Default CTX 2048 / CPU ALL MTP median: ${f(baselineTps, 1)} tok/s\n")
            outputText.append("  Turbo verified median: ${f(turboTps, 1)} tok/s\n")
            outputText.append("  Turbo / baseline: ${f(ratio, 2)}× (${signed((ratio - 1.0) * 100.0)}%)\n")
        }
    }

    /** Artisan-only control retained for actual HW/Artisan E2B packages. */
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
                "GPU Artisan num_decode_steps_per_sync = 1 / 2 / 4 / 8.\n\n"
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

                val measured = ArrayList<ExtremeMeasured>()
                ARTISAN_STEPS.forEachIndexed { index, steps ->
                    statusText.text = "STATUS  ARTISAN ${index + 1}/${ARTISAN_STEPS.size} • SYNC $steps"
                    val beforeTemp = batteryTempC()
                    val beforeThermal = thermalStatus()
                    val result = withContext(Dispatchers.Default) {
                        ExtremeNative.benchmark(modelFile.absolutePath, extremeCache.absolutePath, steps, true)
                    }
                    val item = ExtremeMeasured(result, beforeTemp, batteryTempC(), beforeThermal, thermalStatus())
                    measured += item
                    outputText.append(
                        "SYNC $steps  ${f(result.decodeTokensPerSecond, 1)} tok/s" +
                            "  prefill ${f(result.prefillTokensPerSecond, 0)}" +
                            "  TTFT ${f(result.ttftSeconds * 1000.0, 0)} ms" +
                            tempSuffix(item.batteryBeforeC, item.batteryAfterC) +
                            "  thermal ${thermalName(item.thermalAfter)}\n"
                    )
                }

                val best = measured.maxBy { it.result.decodeTokensPerSecond }
                val baseline = measured.first { it.result.decodeStepsPerSync == 1 }
                val speedup = best.result.decodeTokensPerSecond / baseline.result.decodeTokensPerSecond
                metricsText.text =
                    "ARTISAN BEST  ${f(best.result.decodeTokensPerSecond, 1)} tok/s\n" +
                        "SYNC ${best.result.decodeStepsPerSync}  ${f(speedup, 2)}×"
                statusText.text = "STATUS  ARTISAN COMPLETE"
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
        val decodeGain = (result.decodeSpeedup - 1.0) * 100.0
        val ttftChange = (result.ttftRatio - 1.0) * 100.0
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
                "  Decode speedup: ${f(result.decodeSpeedup, 2)}× (${signed(decodeGain)}%)\n" +
                "  Prefill ratio: ${f(result.prefillSpeedup, 2)}×\n" +
                "  TTFT change: ${signed(ttftChange)}%\n" +
                "  Backend: GPU\n" +
                "  Package: ${modelKindLabel(currentModelKind())}\n" +
                "  Samples: ${SpeedLabEngine.BENCH_SAMPLES_PER_MODE} per mode\n\n" +
                "Run TURBO SWEEP to tune context and CPU affinity on the same stable GPU runtime.\n"
        )
    }

    private fun statsBlock(title: String, stats: SpeedLabEngine.BenchStats): String {
        val decode = stats.samples.joinToString(", ") { f(it.lastDecodeTokensPerSecond, 1) }
        val prefill = stats.samples.joinToString(", ") { f(it.lastPrefillTokensPerSecond, 0) }
        val ttft = stats.samples.joinToString(", ") { f(it.timeToFirstTokenInSecond * 1000.0, 0) }
        return title + "\n" +
            "  Decode median: ${f(stats.decodeMedian, 1)} tok/s\n" +
            "  Decode range: ${f(stats.decodeMin, 1)}–${f(stats.decodeMax, 1)} tok/s\n" +
            "  Decode runs: [$decode]\n" +
            "  Prefill median: ${f(stats.prefillMedian, 0)} tok/s\n" +
            "  Prefill runs: [$prefill]\n" +
            "  TTFT median: ${f(stats.ttftMedianSeconds * 1000.0, 0)} ms\n" +
            "  TTFT runs: [$ttft] ms\n" +
            "  Init median: ${f(stats.initMedianSeconds, 2)} s\n"
    }

    private fun resetConversation() {
        if (!speedLab.isLoaded) return
        setBusy(true, "RESETTING KV CACHE")
        scope.launch {
            try {
                speedLab.resetConversation()
                outputText.text =
                    "Conversation reset. Engine and compiled GPU cache remain loaded. " +
                        "CTX ${speedLab.loadedContextTokens} / ${cpuModeLabel(speedLab.loadedFastestCpuCount)}.\n"
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

    private fun cpuModeLabel(fastCpus: Int): String = if (fastCpus <= 0) "CPU ALL" else "CPU FAST$fastCpus"

    private fun maskText(mask: Long): String = "0x" + mask.toULong().toString(16).uppercase(Locale.US)

    private fun activeModelDisplayName(): String {
        prefs().getString(MODEL_NAME_PREF, null)?.takeIf { it.isNotBlank() }?.let { return it }
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
        val profileMatches = prefs().getString(PREF_TURBO_MODEL, null) == activeModelDisplayName()
        val profileText = if (profileMatches) {
            val ctx = prefs().getInt(PREF_TURBO_CONTEXT, SpeedLabEngine.MAX_CONTEXT_TOKENS)
            val cpus = prefs().getInt(PREF_TURBO_FAST_CPUS, 0)
            val tps = prefs().getFloat(PREF_TURBO_TPS, 0f).toDouble()
            "\nSaved Turbo profile: CTX $ctx / ${cpuModeLabel(cpus)}" +
                if (tps > 0.0) " / ${f(tps, 1)} tok/s median.\n" else ".\n"
        } else {
            ""
        }
        val text = when (currentModelKind()) {
            ModelKind.GENERIC ->
                "GENERIC E2B package detected.\n" +
                    "This package produced the strongest MTP decode result so far. Run MTP A/B ×3, then TURBO SWEEP for context/CPU tuning and ×$TURBO_VERIFY_SAMPLES verification.\n" +
                    profileText
            ModelKind.GPU_OPT ->
                "DEDICATED GPU package detected.\n" +
                    "This package previously showed little MTP gain. TURBO can still optimize its stable regular-GPU path.\n" +
                    profileText
            ModelKind.ARTISAN ->
                "GPU ARTISAN/HW package detected. Artisan SYNC sweep is enabled.\n"
            ModelKind.UNKNOWN ->
                "Unknown E2B package. Regular GPU tuning is allowed; Artisan-only tuning remains disabled.\n" +
                    profileText
        }
        if (overwrite) outputText.text = text else outputText.append(text)
    }

    private fun artisanUnavailableMessage(): String =
        "ARTISAN SWEEP NOT AVAILABLE FOR THIS PACKAGE\n\n" +
            "Current package: ${modelKindLabel(currentModelKind())} • ${activeModelDisplayName()}\n\n" +
            "num_decode_steps_per_sync is Artisan-only. Use TURBO SWEEP for the regular GPU path instead.\n"

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
        val hasModel = modelFile.exists()
        val profileMatches = hasModel && prefs().getString(PREF_TURBO_MODEL, null) == activeModelDisplayName()
        loadButton.isEnabled = !busy && hasModel
        loadButton.text = if (profileMatches) "LOAD TURBO PROFILE" else "LOAD GPU + MTP"
        generateButton.isEnabled = !busy && speedLab.isLoaded
        benchmarkButton.isEnabled = !busy && hasModel
        turboButton.isEnabled = !busy && hasModel && currentModelKind() != ModelKind.ARTISAN
        resetButton.isEnabled = !busy && speedLab.isLoaded
        extremeButton.isEnabled = !busy && hasModel && currentModelKind() == ModelKind.ARTISAN
        extremeButton.text = if (currentModelKind() == ModelKind.ARTISAN) {
            "ARTISAN SWEEP  •  GPU SYNC 1 / 2 / 4 / 8"
        } else {
            "ARTISAN SYNC SWEEP  •  HW MODEL ONLY"
        }
    }

    private fun showError(prefix: String, t: Throwable) {
        statusText.text = "STATUS  ERROR"
        outputText.append("\n$prefix:\n${t.javaClass.simpleName}: ${t.message ?: "unknown error"}\n")
    }

    private fun prefs() = getSharedPreferences("speedlab", MODE_PRIVATE)

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
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        }

    private fun formatGiB(bytes: Long): String = f(bytes / 1024.0 / 1024.0 / 1024.0, 2)

    private fun f(value: Double, decimals: Int): String =
        String.format(Locale.US, "%.${decimals}f", value)

    private fun signed(value: Double): String = if (value >= 0.0) "+${f(value, 1)}" else f(value, 1)

    private fun median(values: List<Double>): Double {
        require(values.isNotEmpty())
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        speedLab.close()
        scope.cancel()
        super.onDestroy()
    }
}
