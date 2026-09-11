package com.e2bspeedlab

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.icu.text.BreakIterator
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

/**
 * Single-purpose rapid reader.
 *
 * Intentionally no conventional chat transcript, FLOW mode, multi-line mode, or line-gap controls.
 * Every generation is stateless so the verified 1536-token Turbo context never fills across turns.
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
        private const val PREF_PACE = "read_pace"

        private const val VERIFIED_CONTEXT = 1536
        private const val VERIFIED_FAST_CPUS = 2
        private const val DEFAULT_PACE = 62
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var speedLab: SpeedLabEngine
    private lateinit var modelFile: File

    private lateinit var root: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var metricText: TextView
    private lateinit var flashText: TextView
    private lateinit var promptInput: EditText
    private lateinit var startButton: Button
    private lateinit var modelButton: Button
    private lateinit var pace: SeekBar
    private lateinit var paceText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var presenter: SingleFlashPresenter

    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor = Color.rgb(8, 10, 14)
        window.navigationBarColor = Color.rgb(8, 10, 14)

        modelFile = File(File(filesDir, "models").apply { mkdirs() }, MODEL_FILE_NAME)
        speedLab = SpeedLabEngine(File(cacheDir, "litertlm"))

        buildUi()
        installInsets()

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
            includeFontPadding = false
            maxLines = 1
        })
        titleColumn.addView(TextView(this).apply {
            text = "HUMAN-READ OPTIMIZED OUTPUT"
            textSize = 10f
            letterSpacing = 0.12f
            setTextColor(Color.rgb(125, 138, 154))
            includeFontPadding = false
            maxLines = 1
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
            setSingleLine(true)
            maxLines = 1
            includeFontPadding = false
            setHorizontallyScrolling(false)
            setPadding(dp(20), dp(20), dp(20), dp(20))
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.NORMAL)
            setAutoSizeTextTypeUniformWithConfiguration(18, 68, 1, TypedValue.COMPLEX_UNIT_SP)
            text = "READY"
        }
        displayFrame.addView(
            flashText,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        )
        root.addView(
            displayFrame,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = dp(8)
                bottomMargin = dp(12)
            }
        )

        presenter = SingleFlashPresenter(flashText, Color.rgb(120, 235, 195))

        val paceRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(3), 0, dp(5))
        }
        paceText = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.rgb(155, 164, 178))
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
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
        paceRow.addView(TextView(this).apply {
            text = "PACE"
            textSize = 10f
            letterSpacing = 0.12f
            setTextColor(Color.rgb(125, 138, 154))
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(dp(56), dp(38)))
        paceRow.addView(pace, LinearLayout.LayoutParams(0, dp(38), 1f))
        paceRow.addView(paceText, LinearLayout.LayoutParams(dp(92), LinearLayout.LayoutParams.WRAP_CONTENT))
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

    private fun updatePace(value: Int) {
        val clamped = value.coerceIn(0, 100)
        val flashMs = (240 - clamped * 1.85).toLong().coerceAtLeast(55L)
        presenter.intervalMs = flashMs
        val wpm = (60_000L / flashMs).coerceAtMost(1200L)
        paceText.text = "$wpm WPM"
    }

    private fun startGeneration() {
        val prompt = promptInput.text.toString().trim()
        if (prompt.isEmpty() || !speedLab.isLoaded || busy) return

        hideKeyboard()
        setBusy(true)
        metricText.text = "STREAMING"
        presenter.begin()

        val unitizer = KatakanaSafeUnitizer(Locale.getDefault())

        scope.launch {
            try {
                // No visible chat history means there should be no hidden chat history either.
                speedLab.resetConversation()

                val visualPrompt = "$prompt\nAnswer in plain text optimized for rapid one-word-at-a-time reading. " +
                    "Do not use Markdown, headings, numbered lists, bullets, or tables. Use natural short sentences."

                val info = speedLab.generate(visualPrompt) { chunk ->
                    val units = unitizer.push(chunk)
                    if (units.isNotEmpty()) presenter.enqueue(units)
                }

                presenter.enqueue(unitizer.finish())
                presenter.finishInput()
                showMetrics(info)
                statusText.text = profileStatus("READY")
            } catch (t: Throwable) {
                presenter.cancel()
                presenter.showStatic("ERROR")
                statusText.text = "ERROR • ${t.message ?: t.javaClass.simpleName}"
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
                presenter.showStatic("READY")
            } catch (t: Throwable) {
                statusText.text = "LOAD ERROR"
                metricText.text = t.message ?: t.javaClass.simpleName
                presenter.showStatic("LOAD FAILED")
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
            presenter.showStatic("NO MODEL")
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
        modelButton.isEnabled = !busy
        promptInput.isEnabled = !busy
        pace.isEnabled = !busy
    }

    private fun activeModelDisplayName(): String =
        prefs().getString(MODEL_NAME_PREF, null)?.takeIf { it.isNotBlank() } ?: MODEL_FILE_NAME

    private fun prefs() = getSharedPreferences("speedlab", MODE_PRIVATE)

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
        presenter.cancel()
        speedLab.close()
        scope.cancel()
        super.onDestroy()
    }
}

/**
 * Streaming word segmenter tuned for one-line RSVP.
 *
 * ICU can expose boundaries inside katakana compounds depending on dictionary/stream boundaries.
 * Adjacent katakana fragments are therefore coalesced before anything is emitted. The last lexical
 * unit is always held back until a following boundary arrives, which also protects words split
 * across model streaming chunks.
 */
private class KatakanaSafeUnitizer(private val locale: Locale) {
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

            // Keep the last segment buffered because a streamed model chunk may end mid-word.
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

        // The key fix: ICU/stream boundaries must never split a katakana lexical run into flashes.
        if (carry != null && isKatakanaRun(carry!!) && isKatakanaRun(segment)) {
            carry += segment
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

    private fun isJoiner(s: String): Boolean = s in setOf("-", "‐", "‑", "–", "'", "’", "・")

    private fun isKatakanaRun(s: String): Boolean {
        if (s.isEmpty()) return false
        var i = 0
        var sawKatakana = false
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val valid = when {
                cp in 0x30A0..0x30FF -> true // Katakana, middle dot, prolonged sound mark
                cp in 0x31F0..0x31FF -> true // Katakana phonetic extensions
                cp in 0xFF65..0xFF9F -> true // Half-width katakana
                cp == 0x3099 || cp == 0x309A -> true // combining voiced/semi-voiced marks
                else -> false
            }
            if (!valid) return false
            if (cp in 0x30A1..0x30FA || cp in 0x31F0..0x31FF || cp in 0xFF66..0xFF9D) {
                sawKatakana = true
            }
            i += Character.charCount(cp)
        }
        return sawKatakana
    }

    private fun isPunctuationOnly(s: String): Boolean = s.isNotEmpty() && s.all { ch ->
        !ch.isLetterOrDigit() && !Character.isIdeographic(ch.code) && !ch.isWhitespace() && !isJoiner(ch.toString())
    }
}

private class SingleFlashPresenter(
    private val view: TextView,
    private val accentColor: Int,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<String>()
    private var scheduled = false
    private var accepting = false

    var intervalMs: Long = 120L

    fun begin() {
        handler.post {
            handler.removeCallbacksAndMessages(null)
            queue.clear()
            accepting = true
            scheduled = false
            showStatic("•")
        }
    }

    fun enqueue(units: List<String>) {
        if (units.isEmpty()) return
        handler.post {
            units.filter { it.isNotBlank() }.forEach(queue::addLast)
            if (!scheduled) step()
        }
    }

    fun finishInput() {
        handler.post {
            accepting = false
            if (!scheduled && queue.isEmpty()) showStatic("✓")
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

    fun showStatic(text: String) {
        view.text = text
    }

    private fun step() {
        val unit = queue.pollFirst()
        if (unit == null) {
            scheduled = false
            if (!accepting) showStatic("✓")
            return
        }

        scheduled = true
        view.text = accented(unit)
        val delay = displayDelay(unit)
        handler.postDelayed({ step() }, delay)
    }

    private fun displayDelay(unit: String): Long {
        val cp = unit.codePointCount(0, unit.length)
        var multiplier = 1.0
        if (cp >= 10) multiplier += 0.18
        if (cp >= 16) multiplier += 0.16
        if (unit.lastOrNull() in setOf('。', '！', '？', '.', '!', '?')) multiplier += 0.35
        else if (unit.lastOrNull() in setOf('、', ',', ';', ':')) multiplier += 0.14
        return (intervalMs * multiplier).toLong().coerceAtLeast(45L)
    }

    private fun accented(text: String): CharSequence {
        val count = text.codePointCount(0, text.length)
        if (count < 2) return text
        val cpIndex = (count - 1) / 2
        val start = text.offsetByCodePoints(0, cpIndex)
        val end = text.offsetByCodePoints(start, 1)
        return SpannableString(text).apply {
            setSpan(ForegroundColorSpan(accentColor), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}
