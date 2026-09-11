package com.e2bspeedlab

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import android.icu.text.BreakIterator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Human-read optimized UI.
 * FLASH shows 1-4 RSVP units at a fixed gaze point; FLOW is a continuous marquee.
 * Each generation is intentionally stateless: the Conversation is recreated before START.
 */
class ReadActivityV2 : Activity() {

    companion object {
        private const val PICK_MODEL_REQUEST = 7201
        private const val MODEL_FILE_NAME = "gemma-4-E2B-it.litertlm"
        private const val MODEL_NAME_PREF = "active_model_display_name"
        private const val PREF_TURBO_MODEL = "turbo_model"
        private const val PREF_TURBO_CONTEXT = "turbo_context"
        private const val PREF_TURBO_FAST_CPUS = "turbo_fast_cpus"
        private const val PREF_TURBO_TPS = "turbo_tps"
        private const val PREF_FLASH_LINES = "flash_lines"
        private const val PREF_PACE = "read_pace"

        private const val VERIFIED_CONTEXT = 1536
        private const val VERIFIED_FAST_CPUS = 2
        private const val DEFAULT_PACE = 62
    }

    private enum class DisplayMode { FLASH, FLOW }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var speedLab: SpeedLabEngine
    private lateinit var modelFile: File

    private lateinit var root: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var metricText: TextView
    private lateinit var flashBoard: FlashBoard
    private lateinit var flowView: FlowTextViewV2
    private lateinit var promptInput: EditText
    private lateinit var flashButton: Button
    private lateinit var flowButton: Button
    private lateinit var startButton: Button
    private lateinit var modelButton: Button
    private lateinit var pace: SeekBar
    private lateinit var paceText: TextView
    private lateinit var progress: ProgressBar
    private val lineButtons = ArrayList<Button>(4)

    private lateinit var flashPresenter: MultiLineFlashPresenter
    private var mode = DisplayMode.FLASH
    private var flashLines = 1
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setDecorFitsSystemWindows(false)

        modelFile = File(File(filesDir, "models").apply { mkdirs() }, MODEL_FILE_NAME)
        speedLab = SpeedLabEngine(File(cacheDir, "litertlm"))
        flashLines = prefs().getInt(PREF_FLASH_LINES, 1).coerceIn(1, 4)

        buildUi()
        installInsets()
        applyMode(DisplayMode.FLASH)
        setFlashLines(flashLines, persist = false)
        val savedPace = prefs().getInt(PREF_PACE, DEFAULT_PACE).coerceIn(0, 100)
        pace.progress = savedPace
        updatePace(savedPace)
        refreshState()

        if (modelFile.exists()) loadTurboEngine(auto = true)
    }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(8, 10, 14))
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
            maxLines = 1
        })
        titleColumn.addView(TextView(this).apply {
            text = "HUMAN-READ OPTIMIZED OUTPUT"
            textSize = 10f
            letterSpacing = 0.12f
            setTextColor(Color.rgb(125, 138, 154))
            maxLines = 1
        })
        modelButton = compactButton("MODEL") { selectModel() }
        top.addView(titleColumn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(modelButton, LinearLayout.LayoutParams(dp(92), dp(42)))
        root.addView(top)

        val infoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(8))
        }
        statusText = TextView(this).apply {
            text = "NO MODEL"
            textSize = 12f
            setTextColor(Color.rgb(255, 190, 90))
            maxLines = 2
        }
        metricText = TextView(this).apply {
            text = ""
            textSize = 11f
            gravity = Gravity.END
            setTextColor(Color.rgb(125, 226, 190))
            maxLines = 1
        }
        infoRow.addView(statusText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        infoRow.addView(metricText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(infoRow)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        root.addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3)))

        val displayFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(12, 15, 20))
        }
        flashBoard = FlashBoard(this)
        flowView = FlowTextViewV2(this).apply { visibility = View.GONE }
        displayFrame.addView(flashBoard, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        displayFrame.addView(flowView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        root.addView(displayFrame, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ).apply {
            topMargin = dp(8)
            bottomMargin = dp(10)
        })

        flashPresenter = MultiLineFlashPresenter(flashBoard, Color.rgb(120, 235, 195))

        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        flashButton = actionButton("FLASH") { applyMode(DisplayMode.FLASH) }
        flowButton = actionButton("FLOW") { applyMode(DisplayMode.FLOW) }
        modeRow.addView(flashButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(5) })
        modeRow.addView(flowButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(5) })
        root.addView(modeRow)

        val lineRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(7), 0, 0)
        }
        lineRow.addView(TextView(this).apply {
            text = "LINES"
            textSize = 10f
            letterSpacing = 0.12f
            setTextColor(Color.rgb(125, 138, 154))
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(dp(62), dp(38)))
        for (n in 1..4) {
            val button = compactButton(n.toString()) { setFlashLines(n) }
            lineButtons += button
            lineRow.addView(button, LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                marginStart = dp(3)
                marginEnd = dp(3)
            })
        }
        root.addView(lineRow)

        val paceRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(3), 0, dp(4))
        }
        paceText = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.rgb(155, 164, 178))
            gravity = Gravity.CENTER_VERTICAL
        }
        pace = SeekBar(this).apply {
            max = 100
            progress = DEFAULT_PACE
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    updatePace(value)
                    if (fromUser) prefs().edit().putInt(PREF_PACE, value).apply()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        paceRow.addView(pace, LinearLayout.LayoutParams(0, dp(38), 1f))
        paceRow.addView(paceText, LinearLayout.LayoutParams(dp(150), LinearLayout.LayoutParams.WRAP_CONTENT))
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
        root.addView(startButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(54),
        ).apply { topMargin = dp(10) })

        setContentView(root)
    }

    private fun installInsets() {
        root.setOnApplyWindowInsetsListener { view, insets ->
            val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(
                dp(18) + safe.left,
                dp(12) + safe.top,
                dp(18) + safe.right,
                dp(12) + safe.bottom,
            )
            insets
        }
        root.requestApplyInsets()
    }

    private fun setFlashLines(lines: Int, persist: Boolean = true) {
        flashLines = lines.coerceIn(1, 4)
        flashBoard.activeLines = flashLines
        flashPresenter.lineCount = flashLines
        lineButtons.forEachIndexed { index, button ->
            val selected = index + 1 == flashLines
            button.setTextColor(if (selected) Color.BLACK else Color.WHITE)
            button.setBackgroundColor(if (selected) Color.rgb(125, 226, 190) else Color.rgb(50, 54, 61))
        }
        if (persist) prefs().edit().putInt(PREF_FLASH_LINES, flashLines).apply()
        updatePace(pace.progress)
    }

    private fun applyMode(newMode: DisplayMode) {
        mode = newMode
        val flash = newMode == DisplayMode.FLASH
        flashBoard.visibility = if (flash) View.VISIBLE else View.GONE
        flowView.visibility = if (flash) View.GONE else View.VISIBLE
        flashButton.setTextColor(if (flash) Color.BLACK else Color.WHITE)
        flashButton.setBackgroundColor(if (flash) Color.rgb(125, 226, 190) else Color.rgb(50, 54, 61))
        flowButton.setTextColor(if (!flash) Color.BLACK else Color.WHITE)
        flowButton.setBackgroundColor(if (!flash) Color.rgb(125, 226, 190) else Color.rgb(50, 54, 61))
        startButton.text = if (flash) "START FLASH" else "START FLOW"
        lineButtons.forEach { it.visibility = if (flash) View.VISIBLE else View.INVISIBLE }
        updatePace(pace.progress)
    }

    private fun updatePace(value: Int) {
        val clamped = value.coerceIn(0, 100)
        val flashMs = (240 - clamped * 1.85).toLong().coerceAtLeast(55L)
        val flowPx = 220f + clamped * 9f
        flashPresenter.intervalMs = flashMs
        flowView.speedPxPerSecond = flowPx * resources.displayMetrics.density
        paceText.text = if (mode == DisplayMode.FLASH) {
            val perLine = (60_000L / flashMs).coerceAtMost(1200L)
            if (flashLines == 1) "$perLine WPM" else "$perLine WPM × $flashLines"
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

        val unitizer = StreamingReadUnitizer(Locale.getDefault())
        if (mode == DisplayMode.FLASH) {
            flowView.stopAndClear()
            flashPresenter.begin()
        } else {
            flashPresenter.cancel()
            flowView.begin()
        }

        scope.launch {
            try {
                // This product intentionally has no chat history. Recreating Conversation before
                // every START prevents the 1536-token Turbo context from filling after ~3-4 turns.
                speedLab.resetConversation()

                val visualPrompt = "$prompt\nAnswer in plain text. Do not use Markdown formatting."
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
                flashBoard.showStatus("ERROR")
                statusText.text = "ERROR • ${t.message ?: t.javaClass.simpleName}"
                // Make the next START recover from a partially failed native conversation too.
                runCatching { speedLab.resetConversation() }
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
                if (mode == DisplayMode.FLASH) flashBoard.showStatus("READY")
            } catch (t: Throwable) {
                statusText.text = "LOAD ERROR"
                metricText.text = t.message ?: t.javaClass.simpleName
                flashBoard.showStatus("LOAD FAILED")
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
            flashBoard.showStatus("NO MODEL")
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
        // Line count is visual-only, so it remains adjustable while output is playing.
        lineButtons.forEach { it.isEnabled = true }
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
        minWidth = 0
        minimumWidth = 0
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

/** Four physical single-line TextViews guarantee that a unit never wraps accidentally. */
private class FlashBoard(context: Context) : LinearLayout(context) {
    private val cells = ArrayList<TextView>(4)
    var activeLines: Int = 1
        set(value) {
            field = value.coerceIn(1, 4)
            cells.forEachIndexed { index, cell ->
                cell.visibility = if (index < field) View.VISIBLE else View.GONE
            }
            requestLayout()
        }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(Color.rgb(12, 15, 20))
        repeat(4) {
            val cell = TextView(context).apply {
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setSingleLine(true)
                maxLines = 1
                includeFontPadding = false
                setPadding(dp(16), 0, dp(16), 0)
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.NORMAL)
                setAutoSizeTextTypeUniformWithConfiguration(18, 54, 1, TypedValue.COMPLEX_UNIT_SP)
                visibility = if (it == 0) View.VISIBLE else View.GONE
            }
            cells += cell
            addView(cell, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    fun showUnits(units: List<String>, accentColor: Int) {
        for (i in 0 until activeLines) {
            val unit = units.getOrNull(i).orEmpty()
            cells[i].text = if (unit.isEmpty()) "" else accented(unit, accentColor)
        }
    }

    fun showStatus(text: String) {
        cells.forEach { it.text = "" }
        val index = ((activeLines - 1) / 2).coerceIn(0, 3)
        cells[index].text = text
    }

    private fun accented(text: String, accentColor: Int): CharSequence {
        val count = text.codePointCount(0, text.length)
        if (count < 2) return text
        val cpIndex = (count - 1) / 2
        val start = text.offsetByCodePoints(0, cpIndex)
        val end = text.offsetByCodePoints(start, 1)
        return SpannableString(text).apply {
            setSpan(ForegroundColorSpan(accentColor), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

/**
 * Streaming word/phrase segmenter tuned for RSVP rather than linguistic purity.
 * It holds one unit back so punctuation, hyphens and short Japanese particles can attach naturally.
 */
private class StreamingReadUnitizer(private val locale: Locale) {
    private val pending = StringBuilder()
    private var carry: String? = null
    private var joinNext = false

    private val japaneseParticles = setOf(
        "は", "が", "を", "に", "へ", "と", "で", "の", "も", "や", "か", "ね", "よ", "ぞ", "さ", "な", "って", "から", "まで", "より"
    )

    fun push(chunk: String): List<String> {
        pending.append(chunk)
        return drain(final = false)
    }

    fun finish(): List<String> = drain(final = true)

    private fun drain(final: Boolean): List<String> {
        if (pending.isEmpty() && !(final && carry != null)) return emptyList()
        val out = ArrayList<String>()

        if (pending.isNotEmpty()) {
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

            val count = if (final) segments.size else (segments.size - 1).coerceAtLeast(0)
            var cut = 0
            for (i in 0 until count) {
                cut = segments[i].second
                feed(clean(segments[i].first), out)
            }
            if (cut > 0) pending.delete(0, cut)
        }

        if (final) {
            if (pending.isNotEmpty()) {
                feed(clean(pending.toString()), out)
                pending.clear()
            }
            carry?.takeIf { it.isNotBlank() }?.let(out::add)
            carry = null
            joinNext = false
        }
        return out
    }

    private fun feed(segment: String, out: MutableList<String>) {
        if (segment.isBlank()) return

        if (isJoiner(segment)) {
            carry = (carry ?: "") + segment
            joinNext = true
            return
        }

        if (isPunctuationOnly(segment)) {
            if (carry != null) carry += segment else carry = segment
            return
        }

        if (joinNext && carry != null) {
            carry += segment
            joinNext = false
            return
        }

        if (segment in japaneseParticles && carry != null) {
            carry += segment
            return
        }

        carry?.takeIf { it.isNotBlank() }?.let(out::add)
        carry = segment
    }

    private fun clean(raw: String): String = raw
        .replace("**", "")
        .replace("__", "")
        .replace("`", "")
        .replace("\n", " ")
        .trim()

    private fun isJoiner(s: String): Boolean = s in setOf("-", "‐", "‑", "–", "'", "’")

    private fun isPunctuationOnly(s: String): Boolean = s.isNotEmpty() && s.all { ch ->
        !ch.isLetterOrDigit() && !Character.isIdeographic(ch.code) && !ch.isWhitespace() && !isJoiner(ch.toString())
    }
}

private class MultiLineFlashPresenter(
    private val board: FlashBoard,
    private val accentColor: Int,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<String>()
    private var scheduled = false
    private var accepting = false

    var intervalMs: Long = 120L
    var lineCount: Int = 1
        set(value) {
            field = value.coerceIn(1, 4)
            board.activeLines = field
        }

    fun begin() {
        handler.post {
            queue.clear()
            accepting = true
            scheduled = false
            handler.removeCallbacksAndMessages(null)
            board.showStatus("•")
        }
    }

    fun enqueue(units: List<String>) {
        if (units.isEmpty()) return
        handler.post {
            units.forEach(queue::addLast)
            if (!scheduled) step()
        }
    }

    fun finishInput() {
        handler.post {
            accepting = false
            if (!scheduled && queue.isEmpty()) board.showStatus("✓")
        }
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
        if (queue.isEmpty()) {
            scheduled = false
            if (!accepting) board.showStatus("✓")
            return
        }

        val frame = ArrayList<String>(lineCount)
        repeat(lineCount) {
            queue.pollFirst()?.let(frame::add)
        }
        scheduled = true
        board.showUnits(frame, accentColor)
        handler.postDelayed({ step() }, dwellFor(frame))
    }

    private fun dwellFor(frame: List<String>): Long {
        val longest = frame.maxOfOrNull { it.codePointCount(0, it.length) } ?: 1
        val punctuation = frame.any { unit ->
            val last = unit.lastOrNull()
            last != null && last in charArrayOf('.', '!', '?', '。', '！', '？')
        }
        val lengthBonus = when {
            longest >= 12 -> 1.35
            longest >= 8 -> 1.18
            else -> 1.0
        }
        val punctuationBonus = if (punctuation) 1.35 else 1.0
        // More simultaneous lines need a little extra dwell, but still increase net reading throughput.
        val lineBonus = 1.0 + (lineCount - 1) * 0.16
        return (intervalMs * lengthBonus * punctuationBonus * lineBonus).toLong()
    }
}

/** Streaming single-line marquee. */
private class FlowTextViewV2(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 42f * resources.displayMetrics.scaledDensity
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
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
