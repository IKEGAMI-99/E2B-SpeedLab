package com.e2bspeedlab

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.icu.text.BreakIterator
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import com.google.ai.edge.litertlm.BenchmarkInfo
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayDeque
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Practical FLASH/FLOW reader UI. No conventional chat transcript by design. */
class ReadActivity : Activity() {

    companion object {
        private const val PICK_MODEL_REQUEST = 7201
        private const val MODEL_FILE_NAME = "gemma-4-E2B-it.litertlm"
        private const val MODEL_NAME_PREF = "active_model_display_name"
        private const val PREF_TURBO_MODEL = "turbo_model"
        private const val PREF_TURBO_CONTEXT = "turbo_context"
        private const val PREF_TURBO_FAST_CPUS = "turbo_fast_cpus"
        private const val PREF_TURBO_TPS = "turbo_tps"

        private const val VERIFIED_CONTEXT = 1536
        private const val VERIFIED_FAST_CPUS = 2
        private const val DEFAULT_PACE = 62
    }

    private enum class DisplayMode { FLASH, FLOW }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var speedLab: SpeedLabEngine
    private lateinit var modelFile: File

    private lateinit var statusText: TextView
    private lateinit var metricText: TextView
    private lateinit var flashText: TextView
    private lateinit var flowView: FlowTextView2
    private lateinit var promptInput: EditText
    private lateinit var flashButton: Button
    private lateinit var flowButton: Button
    private lateinit var startButton: Button
    private lateinit var modelButton: Button
    private lateinit var pace: SeekBar
    private lateinit var paceText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var flashPresenter: FlashPresenter2

    private var mode = DisplayMode.FLASH
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.rgb(8, 10, 14)
        window.navigationBarColor = Color.rgb(8, 10, 14)
        window.setDecorFitsSystemWindows(false)

        modelFile = File(File(filesDir, "models").apply { mkdirs() }, MODEL_FILE_NAME)
        speedLab = SpeedLabEngine(File(cacheDir, "litertlm"))

        buildUi()
        applyMode(DisplayMode.FLASH)
        updatePace(DEFAULT_PACE)
        refreshState()

        if (modelFile.exists()) loadTurboEngine(auto = true)
    }

    private fun buildUi() {
        val baseLeft = dp(18)
        val baseTop = dp(12)
        val baseRight = dp(18)
        val baseBottom = dp(10)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(baseLeft, baseTop, baseRight, baseBottom)
            setBackgroundColor(Color.rgb(8, 10, 14))
            setOnApplyWindowInsetsListener { view, insets ->
                val safe = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                view.setPadding(
                    baseLeft + safe.left,
                    baseTop + safe.top,
                    baseRight + safe.right,
                    baseBottom + safe.bottom,
                )
                insets
            }
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleColumn.addView(TextView(this).apply {
            text = "E2B SPEEDLAB"
            textSize = 24f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            includeFontPadding = false
        })
        titleColumn.addView(TextView(this).apply {
            text = "HUMAN-READ OPTIMIZED OUTPUT"
            textSize = 10f
            letterSpacing = 0.12f
            setTextColor(Color.rgb(125, 138, 154))
            includeFontPadding = false
            setPadding(0, dp(4), 0, 0)
        })
        modelButton = compactButton("MODEL") { selectModel() }
        top.addView(titleColumn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(modelButton, LinearLayout.LayoutParams(dp(92), dp(42)))
        root.addView(top)

        val infoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(8))
        }
        statusText = TextView(this).apply {
            text = "NO MODEL"
            textSize = 12f
            setTextColor(Color.rgb(255, 190, 90))
            maxLines = 2
        }
        metricText = TextView(this).apply {
            textSize = 11f
            gravity = Gravity.END
            setTextColor(Color.rgb(125, 226, 190))
            setSingleLine(true)
        }
        infoRow.addView(statusText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        infoRow.addView(metricText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.75f))
        root.addView(infoRow)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        root.addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3)))

        val displayFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(12, 15, 20))
        }
        flashText = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 54f)
            setSingleLine(true)
            maxLines = 1
            includeFontPadding = false
            setHorizontallyScrolling(false)
            setPadding(dp(24), dp(20), dp(24), dp(20))
            text = "READY"
        }
        flowView = FlowTextView2(this).apply { visibility = View.GONE }
        displayFrame.addView(
            flashText,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        )
        displayFrame.addView(
            flowView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        )
        root.addView(
            displayFrame,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = dp(8)
                bottomMargin = dp(10)
            }
        )

        flashPresenter = FlashPresenter2(flashText, Color.rgb(120, 235, 195))

        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        flashButton = actionButton("FLASH") { applyMode(DisplayMode.FLASH) }
        flowButton = actionButton("FLOW") { applyMode(DisplayMode.FLOW) }
        modeRow.addView(flashButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(5) })
        modeRow.addView(flowButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(5) })
        root.addView(modeRow)

        val paceRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(4))
        }
        paceText = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.rgb(155, 164, 178))
        }
        pace = SeekBar(this).apply {
            max = 100
            progress = DEFAULT_PACE
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    updatePace(value)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        paceRow.addView(pace, LinearLayout.LayoutParams(0, dp(38), 1f))
        paceRow.addView(paceText, LinearLayout.LayoutParams(dp(132), LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(paceRow)

        promptInput = EditText(this).apply {
            hint = "Ask something…"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(92, 102, 116))
            setBackgroundColor(Color.rgb(20, 24, 31))
            setPadding(dp(13), dp(10), dp(13), dp(10))
            textSize = 16f
            minLines = 2
            maxLines = 4
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    startGeneration()
                    true
                } else false
            }
        }
        root.addView(promptInput)

        startButton = actionButton("START FLASH") { startGeneration() }
        root.addView(
            startButton,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54)).apply {
                topMargin = dp(10)
            }
        )

        setContentView(root)
        root.requestApplyInsets()
    }

    private fun applyMode(newMode: DisplayMode) {
        mode = newMode
        val isFlash = newMode == DisplayMode.FLASH
        flashText.visibility = if (isFlash) View.VISIBLE else View.GONE
        flowView.visibility = if (isFlash) View.GONE else View.VISIBLE
        flashButton.setTextColor(if (isFlash) Color.BLACK else Color.WHITE)
        flashButton.setBackgroundColor(if (isFlash) Color.rgb(125, 226, 190) else Color.rgb(50, 54, 61))
        flowButton.setTextColor(if (!isFlash) Color.BLACK else Color.WHITE)
        flowButton.setBackgroundColor(if (!isFlash) Color.rgb(125, 226, 190) else Color.rgb(50, 54, 61))
        startButton.text = if (isFlash) "START FLASH" else "START FLOW"
        updatePace(pace.progress)
    }

    private fun updatePace(value: Int) {
        val clamped = value.coerceIn(0, 100)
        val flashMs = (240 - clamped * 1.85).toLong().coerceAtLeast(55L)
        val flowPx = 220f + clamped * 9f
        flashPresenter.intervalMs = flashMs
        flowView.speedPxPerSecond = flowPx * resources.displayMetrics.density
        paceText.text = if (mode == DisplayMode.FLASH) {
            val wpm = (60_000L / flashMs).coerceAtMost(1200L)
            "$wpm WPM"
        } else {
            String.format(Locale.US, "%.1f× FLOW", 0.5 + clamped / 50.0)
        }
    }

    private fun startGeneration() {
        val prompt = promptInput.text.toString().trim()
        if (prompt.isEmpty() || !speedLab.isLoaded || busy) return

        hideKeyboard()
        setBusy(true)
        metricText.text = "STREAMING"

        val unitizer = FlashUnitizer(Locale.getDefault())
        if (mode == DisplayMode.FLASH) {
            flowView.stopAndClear()
            flashPresenter.begin()
        } else {
            flashPresenter.cancel()
            flowView.begin()
        }

        scope.launch {
            try {
                val visualPrompt = if (mode == DisplayMode.FLASH) {
                    "$prompt\nAnswer in plain text optimized for rapid one-unit-at-a-time reading. " +
                        "Do not use Markdown, headings, numbered lists, bullets, or tables. Use natural short sentences."
                } else {
                    "$prompt\nAnswer in plain text. Do not use Markdown formatting, headings, tables, or bullet lists."
                }

                val info = speedLab.generate(visualPrompt) { chunk ->
                    when (mode) {
                        DisplayMode.FLASH -> {
                            val units = unitizer.push(chunk)
                            if (units.isNotEmpty()) flashPresenter.enqueue(units)
                        }
                        DisplayMode.FLOW -> {
                            val clean = cleanFlowChunk(chunk)
                            if (clean.isNotEmpty()) flowView.appendStreaming(clean)
                        }
                    }
                }

                if (mode == DisplayMode.FLASH) {
                    flashPresenter.enqueue(unitizer.finish())
                    flashPresenter.finishInput()
                } else {
                    flowView.finishInput()
                }
                showMetrics(info)
                statusText.text = profileStatus("READY")
            } catch (t: Throwable) {
                flashPresenter.cancel()
                flowView.stopAndClear()
                flashPresenter.showStatic("ERROR")
                statusText.text = "ERROR • ${t.message ?: t.javaClass.simpleName}"
            } finally {
                setBusy(false)
            }
        }
    }

    private fun loadTurboEngine(auto: Boolean = false) {
        if (!modelFile.exists() || busy) return

        val savedModel = prefs().getString(PREF_TURBO_MODEL, null)
        val activeName = activeModelDisplayName()
        val profileMatches = savedModel == activeName
        val context = if (profileMatches) prefs().getInt(PREF_TURBO_CONTEXT, VERIFIED_CONTEXT) else VERIFIED_CONTEXT
        val fastCpus = if (profileMatches) prefs().getInt(PREF_TURBO_FAST_CPUS, VERIFIED_FAST_CPUS) else VERIFIED_FAST_CPUS

        setBusy(true)
        statusText.text = "LOADING • CTX $context • FAST$fastCpus"
        metricText.text = if (auto) "AUTO" else ""

        scope.launch {
            try {
                val seconds = speedLab.load(
                    modelPath = modelFile.absolutePath,
                    maxContext = context.coerceIn(768, 4096),
                    fastestCpuCount = fastCpus.takeIf { it == 2 || it == 4 } ?: VERIFIED_FAST_CPUS,
                )
                statusText.text = profileStatus("READY")
                val savedTps = prefs().getFloat(PREF_TURBO_TPS, 0f)
                metricText.text = if (savedTps > 0f) {
                    String.format(Locale.US, "%.1f tok/s target", savedTps)
                } else {
                    String.format(Locale.US, "loaded %.1fs", seconds)
                }
                if (mode == DisplayMode.FLASH) flashPresenter.showStatic("READY")
            } catch (t: Throwable) {
                statusText.text = "LOAD ERROR"
                metricText.text = t.message ?: t.javaClass.simpleName
                flashPresenter.showStatic("LOAD FAILED")
            } finally {
                setBusy(false)
                refreshState()
            }
        }
    }

    private fun profileStatus(prefix: String): String =
        "$prefix • CTX ${speedLab.loadedContextTokens} • ${cpuLabel(speedLab.loadedFastestCpuCount)} • MTP"

    private fun showMetrics(info: BenchmarkInfo) {
        metricText.text = String.format(
            Locale.US,
            "%.1f tok/s • %.0f ms",
            info.lastDecodeTokensPerSecond,
            info.timeToFirstTokenInSecond * 1000.0,
        )
    }

    private fun selectModel() {
        if (busy) return
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            },
            PICK_MODEL_REQUEST,
        )
    }

    @Deprecated("Legacy callback keeps the prototype dependency-light.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_MODEL_REQUEST || resultCode != RESULT_OK) return
        data?.data?.let(::importModel)
    }

    private fun importModel(uri: Uri) {
        if (busy) return
        setBusy(true)
        statusText.text = "IMPORTING MODEL"
        scope.launch {
            try {
                speedLab.close()
                val originalName = queryDisplayName(uri)
                withContext(Dispatchers.IO) { copyModel(uri, modelFile) }
                prefs().edit().putString(MODEL_NAME_PREF, originalName).apply()
                statusText.text = "MODEL READY"
            } catch (t: Throwable) {
                modelFile.delete()
                statusText.text = "IMPORT ERROR"
                metricText.text = t.message ?: t.javaClass.simpleName
            } finally {
                setBusy(false)
                refreshState()
                if (modelFile.exists()) loadTurboEngine()
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

    private fun copyModel(uri: Uri, destination: File) {
        destination.parentFile?.mkdirs()
        val temp = File(destination.parentFile, destination.name + ".partial")
        temp.delete()
        try {
            contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Unable to open selected model" }
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(8 * 1024 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            check(temp.renameTo(destination)) { "Could not finalize model file" }
        } catch (t: Throwable) {
            temp.delete()
            throw t
        }
    }

    private fun refreshState() {
        modelButton.text = if (modelFile.exists()) "MODEL ✓" else "MODEL"
        if (!modelFile.exists() && !busy) {
            statusText.text = "SELECT E2B MODEL"
            metricText.text = ""
            flashPresenter.showStatic("NO MODEL")
        }
        updateControls()
    }

    private fun setBusy(value: Boolean) {
        busy = value
        progress.visibility = if (value) View.VISIBLE else View.GONE
        updateControls()
    }

    private fun updateControls() {
        startButton.isEnabled = !busy && speedLab.isLoaded
        flashButton.isEnabled = !busy
        flowButton.isEnabled = !busy
        modelButton.isEnabled = !busy
        promptInput.isEnabled = !busy
        pace.isEnabled = !busy
    }

    private fun activeModelDisplayName(): String =
        prefs().getString(MODEL_NAME_PREF, null)?.takeIf { it.isNotBlank() } ?: MODEL_FILE_NAME

    private fun prefs() = getSharedPreferences("speedlab", MODE_PRIVATE)

    private fun cleanFlowChunk(chunk: String): String = chunk
        .replace("**", "")
        .replace("__", "")
        .replace("`", "")
        .replace("\n", "   •   ")

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(promptInput.windowToken, 0)
    }

    private fun cpuLabel(count: Int): String = if (count > 0) "FAST$count" else "CPU ALL"

    private fun actionButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.rgb(50, 54, 61))
        setOnClickListener { action() }
    }

    private fun compactButton(label: String, action: () -> Unit) = actionButton(label, action).apply {
        textSize = 12f
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        flashPresenter.cancel()
        flowView.stopAndClear()
        speedLab.close()
        scope.cancel()
        super.onDestroy()
    }
}

/**
 * Streaming word segmenter for FLASH.
 * Keeps one complete lexical unit in reserve so punctuation arriving in the next model chunk can be
 * attached instead of being flashed as a separate item.
 */
private class FlashUnitizer(private val locale: Locale) {
    private val pending = StringBuilder()
    private var carry: String? = null
    private var joinNext = false

    private val japaneseParticles = setOf(
        "の", "は", "が", "を", "に", "へ", "と", "で", "も", "や", "か", "ね", "よ", "から", "まで", "より", "ので"
    )
    private val joiners = setOf("-", "‐", "‑", "–", "'", "’", "/")
    private val ignoredPunctuation = setOf("•", "●", "○", "▪", "◦", "・")

    fun push(chunk: String): List<String> {
        pending.append(chunk)
        return drain(final = false)
    }

    fun finish(): List<String> = drain(final = true)

    private fun drain(final: Boolean): List<String> {
        if (pending.isEmpty()) {
            return if (final) flushCarry() else emptyList()
        }

        val text = pending.toString()
        val iterator = BreakIterator.getWordInstance(locale).apply { setText(text) }
        val segments = ArrayList<Pair<String, Int>>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            segments += text.substring(start, end) to end
            start = end
            end = iterator.next()
        }

        val processCount = if (final) segments.size else (segments.size - 1).coerceAtLeast(0)
        if (processCount == 0) return emptyList()

        val out = ArrayList<String>()
        var cut = 0
        for (i in 0 until processCount) {
            val raw = segments[i].first
            cut = segments[i].second

            if (raw.any { it == '\n' || it == '\r' }) {
                carry?.takeIf { it.isNotBlank() }?.let(out::add)
                carry = null
                joinNext = false
                continue
            }

            val cleaned = clean(raw)
            if (cleaned.isEmpty()) continue

            if (isPunctuationOnly(cleaned)) {
                if (cleaned in ignoredPunctuation) continue
                if (cleaned in joiners && carry != null) {
                    carry += cleaned
                    joinNext = true
                } else if (carry != null) {
                    carry += cleaned
                }
                continue
            }

            val current = carry
            if (current == null) {
                carry = cleaned
                joinNext = false
                continue
            }

            if (joinNext) {
                carry = current + cleaned
                joinNext = false
                continue
            }

            if (cleaned in japaneseParticles && containsJapanese(current)) {
                carry = current + cleaned
                continue
            }

            out += current
            carry = cleaned
        }

        pending.delete(0, cut)
        if (final) out += flushCarry()
        return out
    }

    private fun flushCarry(): List<String> {
        val value = carry?.trim().orEmpty()
        carry = null
        joinNext = false
        return if (value.isEmpty()) emptyList() else listOf(value)
    }

    private fun clean(raw: String): String = raw
        .replace("**", "")
        .replace("__", "")
        .replace("`", "")
        .replace("#", "")
        .trim()

    private fun isPunctuationOnly(value: String): Boolean =
        value.none { it.isLetterOrDigit() || Character.UnicodeScript.of(it.code) in setOf(
            Character.UnicodeScript.HAN,
            Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA,
        ) }

    private fun containsJapanese(value: String): Boolean = value.any {
        when (Character.UnicodeScript.of(it.code)) {
            Character.UnicodeScript.HAN,
            Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA -> true
            else -> false
        }
    }
}

/** RSVP presenter: one unit, one physical line, with automatic font fitting. */
private class FlashPresenter2(
    private val target: TextView,
    private val accentColor: Int,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<String>()
    private var scheduled = false
    private var accepting = false
    private val basePx = 54f * target.resources.displayMetrics.scaledDensity
    private val minPx = 20f * target.resources.displayMetrics.scaledDensity

    var intervalMs: Long = 120L

    fun begin() {
        handler.post {
            queue.clear()
            accepting = true
            scheduled = false
            handler.removeCallbacksAndMessages(null)
            showUnit("•", accent = false)
        }
    }

    fun enqueue(words: List<String>) {
        if (words.isEmpty()) return
        handler.post {
            words.map(::normalize).filter { it.isNotEmpty() }.forEach(queue::addLast)
            if (!scheduled) step()
        }
    }

    fun finishInput() {
        handler.post {
            accepting = false
            if (!scheduled && queue.isEmpty()) showUnit("✓", accent = false)
        }
    }

    fun showStatic(value: String) {
        handler.post { showUnit(normalize(value), accent = false) }
    }

    fun cancel() {
        handler.post {
            accepting = false
            scheduled = false
            queue.clear()
            handler.removeCallbacksAndMessages(null)
        }
    }

    private fun step() {
        val next = queue.pollFirst()
        if (next == null) {
            scheduled = false
            if (!accepting) showUnit("✓", accent = false)
            return
        }
        scheduled = true
        showUnit(next, accent = true)
        handler.postDelayed({ step() }, dwellFor(next))
    }

    private fun dwellFor(unit: String): Long {
        val punctuationBonus = if (unit.lastOrNull() in charArrayOf('.', '!', '?', '。', '！', '？')) 1.35 else 1.0
        val lengthBonus = when {
            unit.codePointCount(0, unit.length) >= 12 -> 1.35
            unit.codePointCount(0, unit.length) >= 8 -> 1.18
            else -> 1.0
        }
        return (intervalMs * punctuationBonus * lengthBonus).toLong()
    }

    private fun showUnit(unit: String, accent: Boolean) {
        val safe = normalize(unit)
        target.textScaleX = 1f
        target.setTextSize(TypedValue.COMPLEX_UNIT_PX, basePx)

        val available = (target.width - target.paddingLeft - target.paddingRight).coerceAtLeast(1).toFloat()
        val measured = target.paint.measureText(safe).coerceAtLeast(1f)
        val fittedPx = (basePx * (available / measured).coerceAtMost(1f)).coerceAtLeast(minPx)
        target.setTextSize(TypedValue.COMPLEX_UNIT_PX, fittedPx)

        val afterFit = target.paint.measureText(safe).coerceAtLeast(1f)
        if (afterFit > available) {
            target.textScaleX = (available / afterFit).coerceIn(0.72f, 1f)
        }

        target.text = if (accent) accented(safe) else safe
    }

    private fun normalize(value: String): String = value
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun accented(word: String): CharSequence {
        val count = word.codePointCount(0, word.length)
        if (count < 2) return word
        val cpIndex = ((count - 1) * 0.38f).toInt().coerceIn(0, count - 1)
        val start = word.offsetByCodePoints(0, cpIndex)
        val end = word.offsetByCodePoints(start, 1)
        return SpannableString(word).apply {
            setSpan(
                ForegroundColorSpan(accentColor),
                start,
                end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }
}

/** Streaming single-line marquee moving generated text right to left. */
private class FlowTextView2(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 42f * resources.displayMetrics.scaledDensity
        typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.SANS_SERIF,
            android.graphics.Typeface.BOLD,
        )
    }
    private val text = StringBuilder()
    private var offsetX = 0f
    private var lastFrameMs = 0L
    private var running = false
    private var inputFinished = false

    var speedPxPerSecond: Float = 720f

    private val frame = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.uptimeMillis()
            if (lastFrameMs == 0L) lastFrameMs = now
            val dt = ((now - lastFrameMs).coerceAtMost(50L)) / 1000f
            lastFrameMs = now
            if (text.isNotEmpty()) offsetX -= speedPxPerSecond * dt
            invalidate()

            val widthPx = paint.measureText(text.toString())
            if (inputFinished && text.isNotEmpty() && offsetX + widthPx < -24f) {
                running = false
            } else {
                postOnAnimation(this)
            }
        }
    }

    fun begin() {
        post {
            removeCallbacks(frame)
            text.clear()
            offsetX = width.toFloat().coerceAtLeast(1f)
            lastFrameMs = 0L
            running = true
            inputFinished = false
            postOnAnimation(frame)
            invalidate()
        }
    }

    fun appendStreaming(chunk: String) {
        if (chunk.isEmpty()) return
        post {
            if (!running) {
                running = true
                inputFinished = false
                offsetX = width.toFloat().coerceAtLeast(1f)
                lastFrameMs = 0L
                postOnAnimation(frame)
            }
            text.append(chunk)
            invalidate()
        }
    }

    fun finishInput() {
        post { inputFinished = true }
    }

    fun stopAndClear() {
        post {
            running = false
            inputFinished = true
            removeCallbacks(frame)
            text.clear()
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (text.isEmpty()) return
        val baseline = height / 2f - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(text.toString(), offsetX, baseline, paint)
    }
}
