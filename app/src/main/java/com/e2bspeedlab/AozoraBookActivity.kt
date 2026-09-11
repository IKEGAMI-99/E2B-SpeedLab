package com.e2bspeedlab

import android.app.Activity
import android.graphics.Color
import android.icu.text.BreakIterator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import java.util.ArrayDeque
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Direct Aozora Bunko search/download/FLASH reader. */
class AozoraBookActivity : Activity() {
    companion object {
        private const val PREF_PACE = "read_pace"
        private const val PREF_CLICK = "flash_click_enabled"
        private const val DEFAULT_PACE = 62
        private const val FLASH_SP = 64f
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var client: AozoraBookClient
    private lateinit var clickSound: FlashClickSound
    private lateinit var root: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var reader: BookFlashPlayer

    private var searchResults: List<AozoraBook> = emptyList()
    private var activeBook: AozoraBook? = null
    private var currentUnits: List<String> = emptyList()
    private var clickEnabled = true
    private var paceValue = DEFAULT_PACE
    private var inReader = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor = Color.rgb(8, 10, 14)
        window.navigationBarColor = Color.rgb(8, 10, 14)

        client = AozoraBookClient(this)
        clickSound = FlashClickSound(this)
        clickEnabled = prefs().getBoolean(PREF_CLICK, true)
        paceValue = prefs().getInt(PREF_PACE, DEFAULT_PACE).coerceIn(0, 100)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(8, 10, 14))
        }
        setContentView(root)
        installInsets()
        showSearch()
    }

    private fun showSearch() {
        inReader = false
        if (::reader.isInitialized) reader.stop()
        root.removeAllViews()

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(Button(this).apply {
            text = "← AI FLASH"
            isAllCaps = false
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(108), dp(44)))
        top.addView(TextView(this).apply {
            text = "青空文庫"
            textSize = 23f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
        root.addView(top)

        root.addView(TextView(this).apply {
            text = "作品名・著者名から公式公開作品を検索"
            textSize = 11f
            setTextColor(Color.rgb(130, 143, 158))
            setPadding(0, dp(6), 0, dp(10))
        })

        val queryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val query = EditText(this).apply {
            hint = "例：こころ / 夏目漱石"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(90, 101, 116))
            setBackgroundColor(Color.rgb(20, 24, 31))
            setPadding(dp(12), 0, dp(12), 0)
            singleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        val searchButton = Button(this).apply {
            text = "SEARCH"
            isAllCaps = false
        }
        queryRow.addView(query, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) })
        queryRow.addView(searchButton, LinearLayout.LayoutParams(dp(92), dp(48)))
        root.addView(queryRow)

        val state = TextView(this).apply {
            text = "INDEX READYING…"
            textSize = 11f
            setTextColor(Color.rgb(125, 226, 190))
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(state)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.VISIBLE
        }
        root.addView(progressBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3)))

        val list = ListView(this).apply {
            dividerHeight = 1
            setBackgroundColor(Color.rgb(10, 13, 18))
        }
        root.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        fun renderResults(results: List<AozoraBook>) {
            searchResults = results
            val labels = results.map { book ->
                val rights = if (book.hasCopyright) "  ⚠ 著作権あり" else ""
                val chars = book.characterCount?.let { "  ${it / 1000}k字" } ?: ""
                "${book.title}\n${book.authors}$chars$rights"
            }
            list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_2, android.R.id.text1, labels).also { adapter ->
                // simple_list_item_2 does not bind a single string into both views consistently, so use standard one-line fallback below if needed.
            }
            list.adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_2, android.R.id.text1, labels) {
                override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                    val view = super.getView(position, convertView, parent)
                    val book = results[position]
                    view.findViewById<TextView>(android.R.id.text1)?.apply {
                        text = book.title
                        setTextColor(Color.WHITE)
                        textSize = 17f
                    }
                    view.findViewById<TextView>(android.R.id.text2)?.apply {
                        val rights = if (book.hasCopyright) " • 著作権あり" else ""
                        val chars = book.characterCount?.let { " • ${String.format(Locale.US, "%,d", it)}字" } ?: ""
                        text = "${book.authors}$chars$rights"
                        setTextColor(if (book.hasCopyright) Color.rgb(255, 190, 90) else Color.rgb(135, 148, 165))
                        textSize = 12f
                    }
                    return view
                }
            }
            state.text = "${results.size} RESULTS"
        }

        fun performSearch() {
            val q = query.text.toString().trim()
            if (q.isBlank()) return
            hideKeyboard(query)
            searchButton.isEnabled = false
            progressBar.visibility = View.VISIBLE
            state.text = "SEARCHING…"
            scope.launch {
                try {
                    val results = withContext(Dispatchers.IO) { client.search(q) }
                    renderResults(results)
                    if (results.isEmpty()) state.text = "NO RESULTS"
                } catch (t: Throwable) {
                    state.text = "ERROR • ${t.message ?: t.javaClass.simpleName}"
                } finally {
                    searchButton.isEnabled = true
                    progressBar.visibility = View.GONE
                }
            }
        }

        searchButton.setOnClickListener { performSearch() }
        query.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                performSearch(); true
            } else false
        }
        list.setOnItemClickListener { _, _, position, _ -> loadBook(searchResults[position]) }

        scope.launch {
            try {
                val count = withContext(Dispatchers.IO) { client.ensureIndex().size }
                state.text = "$count WORKS • SEARCH READY"
            } catch (t: Throwable) {
                state.text = "INDEX ERROR • ${t.message ?: t.javaClass.simpleName}"
            } finally {
                progressBar.visibility = View.GONE
            }
        }
    }

    private fun loadBook(book: AozoraBook) {
        root.removeAllViews()
        val loading = TextView(this).apply {
            text = "${book.title}\n\nDOWNLOADING…"
            gravity = Gravity.CENTER
            textSize = 20f
            setTextColor(Color.WHITE)
        }
        root.addView(loading, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(ProgressBar(this).apply { isIndeterminate = true })

        scope.launch {
            try {
                val text = withContext(Dispatchers.IO) { client.fetchBookText(book) }
                activeBook = book
                currentUnits = AozoraUnitizer.unitize(text)
                require(currentUnits.isNotEmpty()) { "No readable text" }
                showReader(book)
            } catch (t: Throwable) {
                loading.text = "DOWNLOAD ERROR\n${t.message ?: t.javaClass.simpleName}"
                root.addView(Button(this@AozoraBookActivity).apply {
                    text = "BACK"
                    setOnClickListener { showSearch() }
                })
            }
        }
    }

    private fun showReader(book: AozoraBook) {
        inReader = true
        root.removeAllViews()

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(Button(this).apply {
            text = "← BOOKS"
            isAllCaps = false
            setOnClickListener { saveProgress(); showSearch() }
        }, LinearLayout.LayoutParams(dp(96), dp(44)))
        val titleBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleBlock.addView(TextView(this).apply {
            text = book.title
            textSize = 17f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            maxLines = 1
        })
        titleBlock.addView(TextView(this).apply {
            text = book.authors
            textSize = 11f
            setTextColor(Color.rgb(130, 143, 158))
            maxLines = 1
        })
        top.addView(titleBlock, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(8) })
        root.addView(top)

        val progressText = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.rgb(125, 226, 190))
            gravity = Gravity.END
            setPadding(0, dp(6), 0, dp(6))
        }
        root.addView(progressText)

        val frame = FrameLayout(this).apply { setBackgroundColor(Color.rgb(12, 15, 20)) }
        val flash = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = FLASH_SP
            setTextColor(Color.WHITE)
            setSingleLine(true)
            maxLines = 1
            includeFontPadding = false
            setPadding(dp(16), dp(16), dp(16), dp(16))
            text = "READY"
        }
        frame.addView(flash, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(frame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(6); bottomMargin = dp(8)
        })

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val playButton = Button(this).apply { text = "START"; isAllCaps = false }
        val clickButton = Button(this).apply { isAllCaps = false }
        controls.addView(playButton, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(4) })
        controls.addView(clickButton, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(4) })
        root.addView(controls)

        val paceRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val paceLabel = TextView(this).apply { setTextColor(Color.rgb(150, 162, 178)); textSize = 11f; gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        val seek = SeekBar(this).apply { max = 100; progress = paceValue }
        paceRow.addView(TextView(this).apply { text = "PACE"; textSize = 10f; setTextColor(Color.rgb(125, 138, 154)); gravity = Gravity.CENTER_VERTICAL }, LinearLayout.LayoutParams(dp(52), dp(38)))
        paceRow.addView(seek, LinearLayout.LayoutParams(0, dp(38), 1f))
        paceRow.addView(paceLabel, LinearLayout.LayoutParams(dp(88), dp(38)))
        root.addView(paceRow)

        fun intervalFor(value: Int): Long = (240 - value.coerceIn(0, 100) * 1.85).toLong().coerceAtLeast(55L)
        fun updatePaceUi() {
            val ms = intervalFor(paceValue)
            paceLabel.text = "${(60_000L / ms).coerceAtMost(1200L)} WPM"
            if (::reader.isInitialized) reader.intervalMs = ms
        }
        fun updateClickUi() {
            clickButton.text = if (clickEnabled) "CLICK ON" else "CLICK OFF"
            clickButton.setTextColor(if (clickEnabled) Color.BLACK else Color.WHITE)
            clickButton.setBackgroundColor(if (clickEnabled) Color.rgb(125, 226, 190) else Color.rgb(50, 54, 61))
        }

        flash.post {
            val fitted = splitUnitsToFit(currentUnits, flash)
            val saved = prefs().getInt("book_pos_${book.workId}", 0).coerceIn(0, (fitted.size - 1).coerceAtLeast(0))
            reader = BookFlashPlayer(
                view = flash,
                accentColor = Color.rgb(120, 235, 195),
                units = fitted,
                startIndex = saved,
                onShown = { unit, index, total ->
                    if (clickEnabled) clickSound.playFor(unit)
                    if (index % 50 == 0) prefs().edit().putInt("book_pos_${book.workId}", index).apply()
                    val pct = if (total > 0) (index * 100 / total) else 0
                    val remaining = (total - index).coerceAtLeast(0)
                    val mins = kotlin.math.ceil(remaining * reader.intervalMs / 60_000.0).toInt()
                    progressText.text = "$pct% • ${index + 1}/$total • 約${mins}分"
                },
                onPlayingChanged = { playing -> playButton.text = if (playing) "PAUSE" else "START" },
            )
            reader.intervalMs = intervalFor(paceValue)
            progressText.text = "${saved * 100 / fitted.size.coerceAtLeast(1)}% • ${saved + 1}/${fitted.size}"
            if (saved > 0) flash.text = "RESUME"
        }

        playButton.setOnClickListener {
            if (::reader.isInitialized) {
                if (reader.isPlaying) reader.pause() else reader.play()
            }
        }
        clickButton.setOnClickListener {
            clickEnabled = !clickEnabled
            prefs().edit().putBoolean(PREF_CLICK, clickEnabled).apply()
            updateClickUi()
            if (clickEnabled) clickSound.playFor("TEST")
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                paceValue = value
                updatePaceUi()
                if (fromUser) prefs().edit().putInt(PREF_PACE, value).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        updateClickUi()
        updatePaceUi()
    }

    private fun splitUnitsToFit(source: List<String>, view: TextView): List<String> {
        val maxWidth = (view.width - view.paddingLeft - view.paddingRight).coerceAtLeast(100) * 0.94f
        val out = ArrayList<String>(source.size)
        source.forEach { original ->
            var rest = original
            while (rest.isNotEmpty() && view.paint.measureText(rest) > maxWidth) {
                var pos = rest.length
                while (pos > 1 && view.paint.measureText(rest.substring(0, pos)) > maxWidth) pos--
                while (pos > 1 && pos < rest.length && rest[pos] in "ーァィゥェォャュョッ") pos--
                out += rest.substring(0, pos.coerceAtLeast(1))
                rest = rest.substring(pos.coerceAtLeast(1))
            }
            if (rest.isNotBlank()) out += rest
        }
        return out
    }

    private fun saveProgress() {
        val book = activeBook ?: return
        if (::reader.isInitialized) prefs().edit().putInt("book_pos_${book.workId}", reader.currentIndex).apply()
    }

    private fun installInsets() {
        root.setOnApplyWindowInsetsListener { view, insets ->
            val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(dp(16) + safe.left, dp(10) + safe.top, dp(16) + safe.right, dp(10) + safe.bottom)
            insets
        }
        root.requestApplyInsets()
    }

    private fun prefs() = getSharedPreferences("speedlab", MODE_PRIVATE)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun hideKeyboard(view: View) {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(view.windowToken, 0)
    }

    @Deprecated("Back is intentionally mode-aware in this prototype")
    override fun onBackPressed() {
        if (inReader) {
            saveProgress(); showSearch()
        } else super.onBackPressed()
    }

    override fun onDestroy() {
        saveProgress()
        if (::reader.isInitialized) reader.stop()
        clickSound.close()
        scope.cancel()
        super.onDestroy()
    }
}

private object AozoraUnitizer {
    private val particles = setOf("は", "が", "を", "に", "へ", "と", "で", "の", "も", "や", "か", "ね", "よ", "から", "まで", "より")

    fun unitize(text: String): List<String> {
        val iterator = BreakIterator.getWordInstance(Locale.JAPAN).apply { setText(text) }
        val raw = ArrayList<String>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            text.substring(start, end).trim().takeIf { it.isNotEmpty() }?.let(raw::add)
            start = end
            end = iterator.next()
        }

        val out = ArrayList<String>()
        var carry: String? = null
        raw.forEach { segment ->
            val punctuation = segment.all { !it.isLetterOrDigit() && !Character.isIdeographic(it.code) }
            when {
                punctuation -> carry = (carry ?: "") + segment
                segment in particles && carry != null -> carry += segment
                carry != null && isKatakana(carry!!) && isKatakana(segment) -> carry += segment
                else -> {
                    carry?.takeIf { it.isNotBlank() }?.let(out::add)
                    carry = segment
                }
            }
        }
        carry?.takeIf { it.isNotBlank() }?.let(out::add)
        return out
    }

    private fun isKatakana(s: String): Boolean = s.isNotEmpty() && s.all { ch -> ch.code in 0x30A0..0x30FF || ch == 'ー' || ch == '・' }
}

private class BookFlashPlayer(
    private val view: TextView,
    private val accentColor: Int,
    private val units: List<String>,
    startIndex: Int,
    private val onShown: (String, Int, Int) -> Unit,
    private val onPlayingChanged: (Boolean) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    var currentIndex: Int = startIndex
        private set
    var intervalMs: Long = 120L
    var isPlaying: Boolean = false
        private set

    fun play() {
        if (isPlaying || currentIndex >= units.size) return
        isPlaying = true
        onPlayingChanged(true)
        step()
    }

    fun pause() {
        isPlaying = false
        handler.removeCallbacksAndMessages(null)
        onPlayingChanged(false)
    }

    fun stop() = pause()

    private fun step() {
        if (!isPlaying || currentIndex >= units.size) {
            isPlaying = false
            onPlayingChanged(false)
            if (currentIndex >= units.size) view.text = "✓"
            return
        }
        val index = currentIndex
        val unit = units[index]
        view.text = accented(unit)
        onShown(unit, index, units.size)
        currentIndex++
        val bonus = when (unit.lastOrNull()) {
            '。', '！', '？', '.', '!', '?' -> 1.35
            '、', ',', ';', ':', '；', '：' -> 1.14
            else -> if (unit.codePointCount(0, unit.length) >= 8) 1.10 else 1.0
        }
        handler.postDelayed({ step() }, (intervalMs * bonus).toLong().coerceAtLeast(45L))
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
