package com.e2bspeedlab

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
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
        private const val EXPECTED_MODEL_BYTES = 2_588_147_712L
        private const val UI_FLUSH_MS = 32L
    }

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
    private lateinit var resetButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val modelsDir = File(filesDir, "models").apply { mkdirs() }
        modelFile = File(modelsDir, MODEL_FILE_NAME)
        speedLab = SpeedLabEngine(File(cacheDir, "litertlm"))

        buildUi()
        refreshModelState()
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

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val pickButton = actionButton("SELECT MODEL") { selectModel() }
        loadButton = actionButton("LOAD GPU + MTP") { loadEngine() }
        buttonRow.addView(pickButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) })
        buttonRow.addView(loadButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(6) })
        root.addView(buttonRow)

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
        root.addView(promptInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
        })

        val runRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        generateButton = actionButton("GENERATE") { generate() }
        benchmarkButton = actionButton("BENCH MTP A/B") { runBenchmark() }
        resetButton = actionButton("RESET") { resetConversation() }
        runRow.addView(generateButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(4) })
        runRow.addView(benchmarkButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
        runRow.addView(resetButton, LinearLayout.LayoutParams(0, dp(48), 0.7f).apply { marginStart = dp(4) })
        root.addView(runRow, marginParams(top = 10, bottom = 10))

        outputText = TextView(this).apply {
            text = "Select the MTP-capable gemma-4-E2B-it.litertlm model, then load the GPU engine.\n"
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
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
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
        val uri = data?.data ?: return
        importModel(uri)
    }

    private fun importModel(uri: Uri) {
        setBusy(true, "IMPORTING MODEL")
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { copyModel(uri, modelFile) }
                statusText.text = "STATUS  MODEL READY"
                modelText.text = "MODEL  ${formatGiB(bytes)} GiB  •  private storage"
                if (kotlin.math.abs(bytes - EXPECTED_MODEL_BYTES) > 32L * 1024 * 1024) {
                    outputText.text = "Warning: model size differs from the current official E2B package. SpeedLab will still allow loading it.\n"
                } else {
                    outputText.text = "Model imported. Load GPU + MTP to initialize LiteRT-LM.\n"
                }
            } catch (t: Throwable) {
                modelFile.delete()
                showError("Model import failed", t)
            } finally {
                setBusy(false)
                refreshModelState()
            }
        }
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
                            runOnUiThread {
                                modelText.text = "COPYING  ${formatGiB(copied)} GiB"
                            }
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
        outputText.text = "Loading E2B. First load can be much slower than later cached loads.\n"
        scope.launch {
            try {
                val seconds = speedLab.load(modelFile.absolutePath)
                statusText.text = "STATUS  GPU + MTP ACTIVE"
                metricsText.text = "DECODE  -- tok/s    PREFILL  -- tok/s\nTTFT  -- ms    LOAD  ${f(seconds, 2)} s"
                outputText.append("Engine ready. Context=${SpeedLabEngine.MAX_CONTEXT_TOKENS}, max output=${SpeedLabEngine.MAX_OUTPUT_TOKENS}.\n")
            } catch (t: Throwable) {
                showError("GPU engine failed to initialize", t)
            } finally {
                setBusy(false)
                updateControls()
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
                updateControls()
            }
        }
    }

    private fun runBenchmark() {
        if (!modelFile.exists()) return
        setBusy(true, "MTP A/B BENCHMARK")
        outputText.text =
            "Native A/B benchmark. The exact same GPU path and prompt are run sequentially.\n" +
                "Chat engine is released first so only one E2B instance is resident at a time.\n\n"

        scope.launch {
            try {
                val result = speedLab.benchmarkMtpComparison(modelFile.absolutePath) { stage ->
                    statusText.post { statusText.text = "STATUS  BENCHMARK $stage" }
                }
                showComparison(result)
                statusText.text = "STATUS  MTP A/B COMPLETE"
            } catch (t: Throwable) {
                showError("MTP A/B benchmark failed", t)
            } finally {
                setBusy(false)
                updateControls()
            }
        }
    }

    private fun showComparison(result: SpeedLabEngine.MtpComparison) {
        val off = result.mtpOff
        val on = result.mtpOn
        val decodeGainPercent = (result.decodeSpeedup - 1.0) * 100.0
        val ttftChangePercent = if (off.timeToFirstTokenInSecond > 0.0) {
            (on.timeToFirstTokenInSecond / off.timeToFirstTokenInSecond - 1.0) * 100.0
        } else {
            Double.NaN
        }

        metricsText.text =
            "MTP ON   ${f(on.lastDecodeTokensPerSecond, 1)} tok/s\n" +
                "MTP OFF  ${f(off.lastDecodeTokensPerSecond, 1)} tok/s   SPEEDUP ${f(result.decodeSpeedup, 2)}×"

        outputText.append(
            "MTP OFF\n" +
                "  Decode: ${f(off.lastDecodeTokensPerSecond, 1)} tok/s\n" +
                "  Prefill: ${f(off.lastPrefillTokensPerSecond, 0)} tok/s\n" +
                "  TTFT: ${f(off.timeToFirstTokenInSecond * 1000.0, 0)} ms\n" +
                "  Init: ${f(off.initTimeInSecond, 2)} s\n" +
                "  Tokens: ${off.lastPrefillTokenCount} prefill / ${off.lastDecodeTokenCount} decode\n\n" +
                "MTP ON\n" +
                "  Decode: ${f(on.lastDecodeTokensPerSecond, 1)} tok/s\n" +
                "  Prefill: ${f(on.lastPrefillTokensPerSecond, 0)} tok/s\n" +
                "  TTFT: ${f(on.timeToFirstTokenInSecond * 1000.0, 0)} ms\n" +
                "  Init: ${f(on.initTimeInSecond, 2)} s\n" +
                "  Tokens: ${on.lastPrefillTokenCount} prefill / ${on.lastDecodeTokenCount} decode\n\n" +
                "RESULT\n" +
                "  Decode speedup: ${f(result.decodeSpeedup, 2)}× (${signed(decodeGainPercent)}%)\n" +
                "  Prefill ratio: ${f(result.prefillSpeedup, 2)}×\n" +
                "  TTFT change: ${signed(ttftChangePercent)}%\n" +
                "  Backend: GPU\n\n" +
                "Reload GPU + MTP before using GENERATE again.\n"
        )
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
                updateControls()
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

    private fun refreshModelState() {
        if (modelFile.exists()) {
            modelText.text = "MODEL  ${formatGiB(modelFile.length())} GiB  •  $MODEL_FILE_NAME"
            if (!speedLab.isLoaded) statusText.text = "STATUS  MODEL READY"
        } else {
            modelText.text = "MODEL  NONE"
            statusText.text = "STATUS  SELECT E2B MODEL"
        }
        updateControls()
    }

    private fun setBusy(busy: Boolean, status: String? = null) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        if (status != null) statusText.text = "STATUS  $status"
        loadButton.isEnabled = !busy && modelFile.exists()
        generateButton.isEnabled = !busy && speedLab.isLoaded
        benchmarkButton.isEnabled = !busy && modelFile.exists()
        resetButton.isEnabled = !busy && speedLab.isLoaded
    }

    private fun updateControls() {
        loadButton.isEnabled = modelFile.exists()
        generateButton.isEnabled = speedLab.isLoaded
        benchmarkButton.isEnabled = modelFile.exists()
        resetButton.isEnabled = speedLab.isLoaded
    }

    private fun showError(prefix: String, t: Throwable) {
        statusText.text = "STATUS  ERROR"
        outputText.append("\n$prefix:\n${t.javaClass.simpleName}: ${t.message ?: "unknown error"}\n")
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
