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

/** Single-line FLASH reader with fixed-size text and optional hidden reasoning. */
class ReadActivityV3 : Activity() {

    companion object {
        private const val PICK_MODEL_REQUEST = 7201
        private const val MODEL_FILE_NAME = "gemma-4-E2B-it.litertlm"
        private const val MODEL_NAME_PREF = "active_model_display_name"
        private const val PREF_PACE = "read_pace"
        private const val PREF_READER_MODE = "reader_mode"
        private const val DEFAULT_PACE = 62
        private const val FLASH_TEXT_SP = 64f
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var reader: ReaderEngine
    private lateinit var modelFile: File

    private lateinit var root: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var metricText: TextView
    private lateinit var flashText: TextView
    private lateinit var promptInput: EditText
    private lateinit var startButton: Button
    private lateinit var modelButton: Button
    private lateinit var fastButton: Button
    private lateinit var thinkButton: Button
    private lateinit var pace: SeekBar
    private lateinit var paceText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var presenter: FixedFlashPresenter

    private var readMode = ReaderEngine.Mode.FAST
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor = Color.rgb(8, 10, 14)
        window.navigationBarColor = Color.rgb(8, 10, 14)

        modelFile = File(File(filesDir, "models").apply { mkdirs() }, MODEL_FILE_NAME)
        reader = ReaderEngine(File(cacheDir, "litertlm"))
        readMode = runCatching {
            ReaderEngine.Mode.valueOf(prefs().getString(PREF_READER_MODE, ReaderEngine.Mode.FAST.name)!!)
        }.getOrDefault(ReaderEngine.Mode.FAST)

        buildUi()
        installInsets()
        updateModeButtons()

        val savedPace = prefs().getInt(PREF_PACE, DEFAULT_PACE).coerceIn(0, 100)
        pace.progress = savedPace
        updatePace(savedPace)
        refreshState()

        if (modelFile.exists()) loadEngine(auto = true)
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
            textSize = FLASH_TEXT_SP
            setSingleLine(true)
            maxLines = 1
            includeFontPadding = false
            setHorizontallyScrolling(false)
            setPadding(dp(16), dp(20), dp(16), dp(20))
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.SANS_SERIF,
                android.graphics.Typeface.NORMAL,
            )
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
                bottomMargin = dp(10)
            }
        )

        presenter = FixedFlashPresenter(flashText, Color.rgb(120, 235, 195))

        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fastButton = actionButton("FAST") { changeMode(ReaderEngine.Mode.FAST) }
        thinkButton = actionButton("THINK") { changeMode(ReaderEngine.Mode.THINK) }
        modeRow.addView(fastButton, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(5) })
        modeRow.addView(thinkButton, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(5) })
        root.addView(modeRow)

        val paceRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(5))
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

    private fun changeMode(newMode: ReaderEngine.Mode) {
        if (busy || newMode == readMode) return
        readMode = newMode
        prefs().edit().putString(PREF_READER_MODE, readMode.name).apply()
        updateModeButtons()
        presenter.showStatic(if (readMode == ReaderEngine.Mode.THINK) "THINK" else "FAST")
        if (modelFile.exists()) loadEngine(auto = false)
    }

    private fun updateModeButtons() {
        val fast = readMode == ReaderEngine.Mode.FAST
        fastButton.setTextColor(if (fast) Color.BLACK else Color.WHITE)
        fastButton.setBackgroundColor(if (fast) Color.rgb(125, 226, 190) else Color.rgb(50, 54, 61))
        thinkButton.setTextColor(if (!fast) Color.BLACK else Color.WHITE)
        thinkButton.setBackgroundColor(if (!fast) Color.rgb(125, 226, 190) else Color.rgb(50, 54, 61))
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
        if (prompt.isEmpty() || !reader.isLoaded || reader.mode != readMode || busy) return

        hideKeyboard()
        setBusy(true)
        metricText.text = if (readMode == ReaderEngine.Mode.THINK) "THINKING" else "STREAMING"
        presenter.begin()
        val unitizer = ReadUnitizer(Locale.getDefault())
        var firstVisibleChunk = true

        scope.launch {
            try {
                val modeHint = if (readMode == ReaderEngine.Mode.THINK) {
                    "Reason carefully before answering. Give the final answer in clear plain text. "
                } else {
                    "Answer directly in clear plain text. "
                }
                val visualPrompt = prompt + "\n" + modeHint +
                    "Optimize the final answer for rapid one-unit-at-a-time reading. " +
                    "Do not use Markdown, headings, numbered lists, bullets, or tables. Use natural short sentences."

                val info = reader.generate(visualPrompt, readMode) { chunk ->
                    if (firstVisibleChunk) {
                        firstVisibleChunk = false
                        runOnUiThread { metricText.text = "STREAMING" }
                    }
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
            } finally {
                setBusy(false)
            }
        }
    }

    private fun loadEngine(auto: Boolean) {
        if (!modelFile.exists() || busy) return
        setBusy(true)
        statusText.text = "LOADING • ${readMode.name} • CTX ${readMode.contextTokens}"
        metricText.text = if (auto) "AUTO" else ""

        scope.launch {
            try {
                val seconds = reader.load(modelFile.absolutePath, readMode)
                statusText.text = profileStatus("READY")
                metricText.text = if (seconds > 0.0) {
                    String.format(Locale.US, "loaded %.1fs", seconds)
                } else {
                    "READY"
                }
                presenter.showStatic(if (readMode == ReaderEngine.Mode.THINK) "THINK" else "READY")
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

    private fun profileStatus(prefix: String): String = when (readMode) {
        ReaderEngine.Mode.FAST -> "$prefix • FAST • CTX 1536 • FAST2 • MTP"
        ReaderEngine.Mode.THINK -> "$prefix • THINK • CTX 2048 • 512T • MTP"
    }

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
                reader.close()
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
                if (modelFile.exists()) loadEngine(auto = false)
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
        startButton.isEnabled = !busy && reader.isLoaded && reader.mode == readMode
        fastButton.isEnabled = !busy
        thinkButton.isEnabled = !busy
        modelButton.isEnabled = !busy
        promptInput.isEnabled = !busy
        pace.isEnabled = !busy
    }

    private fun prefs() = getSharedPreferences("speedlab", MODE_PRIVATE)

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(promptInput.windowToken, 0)
    }

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
        reader.close()
        scope.cancel()
        super.onDestroy()
    }
}

/**
 * Streaming word/phrase segmentation. Katakana fragments are rejoined first; the presenter later
 * splits only when the fixed 64sp text physically cannot fit on screen.
 */
private class ReadUnitizer(private val locale: Locale) {
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

    private fun isJoiner(s: String): Boolean = s in setOf("-", "‐", "‑", "–", "'", "’", "・", "/")

    private fun isKatakanaRun(s: String): Boolean {
        if (s.isEmpty()) return false
        var i = 0
        var sawKatakana = false
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val valid = when {
                cp in 0x30A0..0x30FF -> true
                cp in 0x31F0..0x31FF -> true
                cp in 0xFF65..0xFF9F -> true
                cp == 0x3099 || cp == 0x309A -> true
                else -> false
            }
            if (!valid) return false
            if (cp in 0x30A1..0x30FA || cp in 0x31F0..0x31FF || cp in 0xFF66..0xFF9D) sawKatakana = true
            i += Character.charCount(cp)
        }
        return sawKatakana
    }

    private fun isPunctuationOnly(s: String): Boolean = s.isNotEmpty() && s.all { ch ->
        !ch.isLetterOrDigit() && !Character.isIdeographic(ch.code) && !ch.isWhitespace() && !isJoiner(ch.toString())
    }
}

private class FixedFlashPresenter(
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
            units.filter { it.isNotBlank() }.forEach { unit ->
                splitToFit(unit).forEach(queue::addLast)
            }
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
        handler.postDelayed({ step() }, displayDelay(unit))
    }

    /** Keep the font fixed. Only over-wide units are split into consecutive FLASH frames. */
    private fun splitToFit(text: String): List<String> {
        val maxWidth = (view.width - view.paddingLeft - view.paddingRight).takeIf { it > 80 }
            ?.times(0.94f)
            ?: (view.resources.displayMetrics.widthPixels * 0.82f)
        if (view.paint.measureText(text) <= maxWidth) return listOf(text)

        val result = ArrayList<String>()
        var remaining = text
        while (remaining.isNotEmpty()) {
            if (view.paint.measureText(remaining) <= maxWidth) {
                result += remaining
                break
            }
            val end = chooseBreak(remaining, maxWidth)
            if (end <= 0 || end >= remaining.length) {
                result += remaining
                break
            }
            result += remaining.substring(0, end)
            remaining = remaining.substring(end)
        }
        return result.filter { it.isNotBlank() }
    }

    private fun chooseBreak(text: String, maxWidth: Float): Int {
        val offsets = ArrayList<Int>()
        offsets += 0
        var pos = 0
        while (pos < text.length) {
            pos += Character.charCount(text.codePointAt(pos))
            offsets += pos
        }
        if (offsets.size <= 2) return text.length

        var longestIndex = 1
        for (i in 1 until offsets.size) {
            if (view.paint.measureText(text.substring(0, offsets[i])) <= maxWidth) longestIndex = i else break
        }
        longestIndex = longestIndex.coerceAtLeast(1)

        // Prefer a visually natural boundary close to the longest fitting point.
        val earliestPreferred = (longestIndex - 3).coerceAtLeast(1)
        for (i in longestIndex downTo earliestPreferred) {
            val end = offsets[i]
            if (i < offsets.lastIndex && !badStart(text.codePointAt(offsets[i])) && goodBoundary(text, end)) {
                return end
            }
        }

        // Never start the next flash with a small kana, prolonged sound mark, or combining mark.
        var safeIndex = longestIndex
        while (safeIndex > 1 && safeIndex < offsets.lastIndex && badStart(text.codePointAt(offsets[safeIndex]))) {
            safeIndex--
        }
        return offsets[safeIndex.coerceAtLeast(1)]
    }

    private fun goodBoundary(text: String, end: Int): Boolean {
        if (end <= 0 || end >= text.length) return true
        val before = text.codePointBefore(end)
        val after = text.codePointAt(end)
        if (before in setOf('ー'.code, '・'.code, '-'.code, '/'.code, ' '.code)) return true
        if (after in setOf('・'.code, '-'.code, '/'.code, ' '.code)) return true
        return scriptClass(before) != scriptClass(after)
    }

    private fun scriptClass(cp: Int): Int = when {
        cp in 0x30A0..0x30FF || cp in 0x31F0..0x31FF || cp in 0xFF65..0xFF9F -> 1
        cp in 0x3040..0x309F -> 2
        Character.isIdeographic(cp) -> 3
        Character.isLetterOrDigit(cp) -> 4
        else -> 5
    }

    private fun badStart(cp: Int): Boolean = cp in setOf(
        'ー'.code, 'ァ'.code, 'ィ'.code, 'ゥ'.code, 'ェ'.code, 'ォ'.code,
        'ャ'.code, 'ュ'.code, 'ョ'.code, 'ッ'.code, 'ヮ'.code, 'ヵ'.code, 'ヶ'.code,
        0x3099, 0x309A,
    )

    private fun displayDelay(unit: String): Long {
        val cp = unit.codePointCount(0, unit.length)
        var multiplier = 1.0
        if (cp >= 8) multiplier += 0.10
        if (cp >= 12) multiplier += 0.12
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
