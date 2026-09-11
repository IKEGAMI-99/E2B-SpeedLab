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
import java.io.File
import java.util.Locale
import kotlin.math.ceil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Aozora Bunko direct reader with on-device Gemma 4 E2B compression. */
class AozoraBookActivityV2 : Activity() {
    companion object {
        private const val PREF_PACE = "read_pace"
        private const val PREF_CLICK = "flash_click_enabled"
        private const val DEFAULT_PACE = 62
        private const val FLASH_SP = 64f
        private const val MODEL_FILE_NAME = "gemma-4-E2B-it.litertlm"
    }

    private enum class Screen { SEARCH, OPTIONS, COMPRESSING, READER }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var client: AozoraBookClient
    private lateinit var clickSound: FlashClickSound
    private lateinit var compressionStore: AozoraCompressionStore
    private lateinit var root: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var reader: BookFlashPlayerV2

    private var compressor: AozoraCompressor? = null
    private var compressionJob: Job? = null
    private var searchResults: List<AozoraBook> = emptyList()
    private var activeBook: AozoraBook? = null
    private var rawBookText: String = ""
    private var currentUnits: List<String> = emptyList()
    private var currentVariantKey = "orig"
    private var currentVariantLabel = "ORIGINAL"
    private var clickEnabled = true
    private var paceValue = DEFAULT_PACE
    private var screen = Screen.SEARCH

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor = Color.rgb(8, 10, 14)
        window.navigationBarColor = Color.rgb(8, 10, 14)

        client = AozoraBookClient(this)
        clickSound = FlashClickSound(this)
        compressionStore = AozoraCompressionStore(File(filesDir, "aozora_compressed"))
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
        screen = Screen.SEARCH
        if (::reader.isInitialized) reader.stop()
        root.removeAllViews()

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(Button(this).apply {
            text = "← HOME"
            isAllCaps = false
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(96), dp(44)))
        top.addView(TextView(this).apply {
            text = "青空文庫"
            textSize = 23f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
        root.addView(top)

        root.addView(TextView(this).apply {
            text = "作品名・著者名から検索 → ORIGINAL / AI圧縮 → FLASH"
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
            setSingleLine(true)
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
            val labels = results.map { "${it.title}\n${it.authors}" }
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
        screen = Screen.OPTIONS
        root.removeAllViews()
        val loading = TextView(this).apply {
            text = "${book.title}\n\nDOWNLOADING ORIGINAL…"
            gravity = Gravity.CENTER
            textSize = 20f
            setTextColor(Color.WHITE)
        }
        root.addView(loading, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(ProgressBar(this).apply { isIndeterminate = true })

        scope.launch {
            try {
                val bookText = withContext(Dispatchers.IO) { client.fetchBookText(book) }
                activeBook = book
                rawBookText = bookText
                require(bookText.isNotBlank()) { "No readable text" }
                showBookOptions(book, bookText)
            } catch (t: Throwable) {
                loading.text = "DOWNLOAD ERROR\n${t.message ?: t.javaClass.simpleName}"
                root.addView(Button(this@AozoraBookActivityV2).apply {
                    text = "BACK"
                    setOnClickListener { showSearch() }
                })
            }
        }
    }

    private fun showBookOptions(book: AozoraBook, bookText: String) {
        screen = Screen.OPTIONS
        if (::reader.isInitialized) reader.stop()
        root.removeAllViews()

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(Button(this).apply {
            text = "← BOOKS"
            isAllCaps = false
            setOnClickListener { showSearch() }
        }, LinearLayout.LayoutParams(dp(96), dp(44)))
        val titleBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleBlock.addView(TextView(this).apply {
            text = book.title
            textSize = 18f
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

        root.addView(TextView(this).apply {
            text = "${String.format(Locale.US, "%,d", bookText.length)}字 • 読み方を選択"
            textSize = 12f
            setTextColor(Color.rgb(125, 226, 190))
            setPadding(0, dp(12), 0, dp(10))
        })

        val original = optionButton("ORIGINAL\n原文をそのままFLASH") {
            currentVariantKey = "orig"
            currentVariantLabel = "ORIGINAL"
            currentUnits = AozoraUnitizerV2.unitize(bookText)
            require(currentUnits.isNotEmpty()) { "No readable text" }
            showReader(book)
        }
        root.addView(original, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(72)).apply { bottomMargin = dp(8) })

        listOf(70, 40, 20).forEach { ratio ->
            val cached = compressionStore.file(book.workId, ratio).isFile
            val subtitle = when (ratio) {
                70 -> "軽圧縮 • 描写と会話を多めに残す"
                40 -> "中圧縮 • 筋と重要場面を中心に残す"
                else -> "強圧縮 • ストーリー中心"
            }
            val cacheMark = if (cached) " • CACHED" else ""
            root.addView(
                optionButton("AI $ratio%$cacheMark\n$subtitle") { startCompression(book, bookText, ratio) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(72)).apply { bottomMargin = dp(8) },
            )
        }

        val modelFile = modelFile()
        root.addView(TextView(this).apply {
            text = if (modelFile.exists()) {
                "AI圧縮: Gemma 4 E2B • GPU • CTX 3072 • 作品/圧縮率ごとに結果を保存"
            } else {
                "AI圧縮にはモデルが必要です。先にAI FLASHのMODELからGemma 4 E2Bを設定してください。"
            }
            textSize = 11f
            setTextColor(if (modelFile.exists()) Color.rgb(130, 143, 158) else Color.rgb(255, 190, 90))
            setPadding(0, dp(8), 0, 0)
        })
    }

    private fun startCompression(book: AozoraBook, originalText: String, ratio: Int) {
        val model = modelFile()
        if (!model.exists()) {
            showTemporaryError(book, originalText, "MODEL NOT FOUND\nAI FLASHでGemma 4 E2Bを設定してください")
            return
        }

        screen = Screen.COMPRESSING
        root.removeAllViews()
        val title = TextView(this).apply {
            text = "${book.title}\nAI $ratio% COMPRESS"
            gravity = Gravity.CENTER
            textSize = 20f
            setTextColor(Color.WHITE)
        }
        val state = TextView(this).apply {
            text = "CACHE CHECK…"
            gravity = Gravity.CENTER
            textSize = 13f
            setTextColor(Color.rgb(125, 226, 190))
        }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            max = 100
        }
        root.addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(state, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54)))
        root.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(5)))
        root.addView(Button(this).apply {
            text = "CANCEL"
            isAllCaps = false
            setOnClickListener {
                compressionJob?.cancel()
                state.text = "CANCELLING…"
                isEnabled = false
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(10) })

        compressionJob = scope.launch {
            try {
                val cached = withContext(Dispatchers.IO) { compressionStore.read(book.workId, ratio) }
                val compressed = if (cached != null) {
                    state.text = "CACHED • OPENING…"
                    cached
                } else {
                    state.text = "LOADING E2B…"
                    compressor?.close()
                    compressor = AozoraCompressor(File(cacheDir, "litertlm_aozora"))
                    val seconds = compressor!!.load(model.absolutePath)
                    state.text = String.format(Locale.US, "E2B READY %.1fs • COMPRESSING…", seconds)
                    bar.isIndeterminate = false
                    val generated = compressor!!.compress(originalText, ratio) { done, total, sourceChars, outputChars ->
                        runOnUiThread {
                            if (screen != Screen.COMPRESSING) return@runOnUiThread
                            val pct = (done * 100 / total.coerceAtLeast(1)).coerceIn(0, 100)
                            bar.progress = pct
                            val actual = outputChars * 100 / sourceChars.coerceAtLeast(1)
                            state.text = "$done/$total • $pct% • 現在 約$actual%"
                        }
                    }
                    withContext(Dispatchers.IO) { compressionStore.write(book.workId, ratio, generated) }
                    generated
                }

                currentVariantKey = "ai$ratio"
                currentVariantLabel = "AI $ratio%"
                currentUnits = AozoraUnitizerV2.unitize(compressed)
                require(currentUnits.isNotEmpty()) { "Compressed text is empty" }
                val actualRatio = compressed.length * 100 / originalText.length.coerceAtLeast(1)
                prefs().edit().putInt("book_ratio_${book.workId}_$ratio", actualRatio).apply()
                showReader(book)
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                showBookOptions(book, originalText)
            } catch (t: Throwable) {
                showTemporaryError(book, originalText, "COMPRESS ERROR\n${t.message ?: t.javaClass.simpleName}")
            } finally {
                compressor?.close()
                compressor = null
                compressionJob = null
            }
        }
    }

    private fun showTemporaryError(book: AozoraBook, bookText: String, message: String) {
        screen = Screen.OPTIONS
        root.removeAllViews()
        root.addView(TextView(this).apply {
            text = message
            gravity = Gravity.CENTER
            textSize = 18f
            setTextColor(Color.rgb(255, 190, 90))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(Button(this).apply {
            text = "BACK"
            isAllCaps = false
            setOnClickListener { showBookOptions(book, bookText) }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)))
    }

    private fun showReader(book: AozoraBook) {
        screen = Screen.READER
        root.removeAllViews()

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(Button(this).apply {
            text = "← OPTIONS"
            isAllCaps = false
            setOnClickListener { saveProgress(); showBookOptions(book, rawBookText) }
        }, LinearLayout.LayoutParams(dp(110), dp(44)))
        val titleBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleBlock.addView(TextView(this).apply {
            text = book.title
            textSize = 17f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            maxLines = 1
        })
        titleBlock.addView(TextView(this).apply {
            val ratio = currentVariantKey.removePrefix("ai").toIntOrNull()
            val actual = ratio?.let { prefs().getInt("book_ratio_${book.workId}_$it", it) }
            val variant = if (actual != null) "$currentVariantLabel • actual $actual%" else currentVariantLabel
            text = "${book.authors} • $variant"
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
            topMargin = dp(6)
            bottomMargin = dp(8)
        })

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val playButton = Button(this).apply { text = "START"; isAllCaps = false }
        val clickButton = Button(this).apply { isAllCaps = false }
        controls.addView(playButton, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(4) })
        controls.addView(clickButton, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(4) })
        root.addView(controls)

        val paceRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val paceLabel = TextView(this).apply {
            setTextColor(Color.rgb(150, 162, 178))
            textSize = 11f
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        val seek = SeekBar(this).apply { max = 100; progress = paceValue }
        paceRow.addView(TextView(this).apply {
            text = "PACE"
            textSize = 10f
            setTextColor(Color.rgb(125, 138, 154))
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(dp(52), dp(38)))
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
            val key = progressKey(book)
            val saved = prefs().getInt(key, 0).coerceIn(0, (fitted.size - 1).coerceAtLeast(0))
            reader = BookFlashPlayerV2(
                view = flash,
                accentColor = Color.rgb(120, 235, 195),
                units = fitted,
                startIndex = saved,
                onShown = { unit, index, total ->
                    if (clickEnabled) clickSound.playFor(unit)
                    if (index % 50 == 0) prefs().edit().putInt(key, index).apply()
                    val pct = if (total > 0) (index * 100 / total) else 0
                    val remaining = (total - index).coerceAtLeast(0)
                    val mins = ceil(remaining * reader.intervalMs / 60_000.0).toInt()
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

    private fun optionButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.rgb(35, 41, 50))
        setOnClickListener { action() }
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
                val safe = pos.coerceAtLeast(1)
                out += rest.substring(0, safe)
                rest = rest.substring(safe)
            }
            if (rest.isNotBlank()) out += rest
        }
        return out
    }

    private fun progressKey(book: AozoraBook) = "book_pos_${book.workId}_$currentVariantKey"

    private fun saveProgress() {
        val book = activeBook ?: return
        if (::reader.isInitialized) prefs().edit().putInt(progressKey(book), reader.currentIndex).apply()
    }

    private fun modelFile(): File = File(File(filesDir, "models"), MODEL_FILE_NAME)

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
        when (screen) {
            Screen.READER -> {
                saveProgress()
                activeBook?.let { showBookOptions(it, rawBookText) } ?: showSearch()
            }
            Screen.OPTIONS -> showSearch()
            Screen.COMPRESSING -> compressionJob?.cancel()
            Screen.SEARCH -> super.onBackPressed()
        }
    }

    override fun onDestroy() {
        saveProgress()
        compressionJob?.cancel()
        if (::reader.isInitialized) reader.stop()
        compressor?.close()
        clickSound.close()
        scope.cancel()
        super.onDestroy()
    }
}

private object AozoraUnitizerV2 {
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

    private fun isKatakana(s: String): Boolean = s.isNotEmpty() && s.all { ch ->
        ch.code in 0x30A0..0x30FF || ch == 'ー' || ch == '・'
    }
}

private class BookFlashPlayerV2(
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
