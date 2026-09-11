package com.e2bspeedlab

import android.content.Context
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.ZipInputStream

internal data class AozoraBook(
    val workId: String,
    val title: String,
    val titleReading: String,
    val authors: String,
    val textUrl: String,
    val textEncoding: String,
    val workCopyright: String,
    val personCopyright: String,
    val characterCount: Int?,
) {
    val hasCopyright: Boolean
        get() = workCopyright == "あり" || personCopyright == "あり"
}

/** Official Aozora Bunko catalogue + text downloader. */
internal class AozoraBookClient(context: Context) {
    companion object {
        private const val INDEX_URL =
            "https://www.aozora.gr.jp/index_pages/list_person_all_extended_utf8.zip"
        private const val INDEX_CACHE_MAX_AGE_MS = 24L * 60L * 60L * 1000L
        private const val USER_AGENT = "E2B-SpeedLab-Aozora/0.3.8"
    }

    private val appContext = context.applicationContext
    private val indexZip = File(appContext.cacheDir, "aozora/list_person_all_extended_utf8.zip")
    private var books: List<AozoraBook>? = null

    fun ensureIndex(forceRefresh: Boolean = false): List<AozoraBook> {
        books?.takeIf { !forceRefresh }?.let { return it }
        indexZip.parentFile?.mkdirs()
        val fresh = indexZip.exists() && indexZip.length() > 0L &&
            System.currentTimeMillis() - indexZip.lastModified() < INDEX_CACHE_MAX_AGE_MS
        if (!fresh || forceRefresh) downloadToFile(INDEX_URL, indexZip)
        val parsed = parseIndex(indexZip)
        books = parsed
        return parsed
    }

    fun search(query: String, limit: Int = 80): List<AozoraBook> {
        val all = ensureIndex()
        val q = normalize(query)
        if (q.isBlank()) return all.take(limit)
        return all.asSequence()
            .mapNotNull { book ->
                val title = normalize(book.title)
                val reading = normalize(book.titleReading)
                val author = normalize(book.authors)
                val score = when {
                    title == q -> 0
                    title.startsWith(q) -> 1
                    author == q -> 2
                    author.startsWith(q) -> 3
                    title.contains(q) -> 4
                    reading.contains(q) -> 5
                    author.contains(q) -> 6
                    else -> return@mapNotNull null
                }
                score to book
            }
            .sortedWith(compareBy<Pair<Int, AozoraBook>> { it.first }.thenBy { it.second.title.length })
            .take(limit)
            .map { it.second }
            .toList()
    }

    fun fetchBookText(book: AozoraBook): String {
        require(book.textUrl.startsWith("https://www.aozora.gr.jp/")) {
            "Unsupported text source"
        }
        val bytes = downloadBytes(book.textUrl, maxBytes = 32 * 1024 * 1024)
        val raw = if (book.textUrl.lowercase(Locale.ROOT).endsWith(".zip")) {
            unzipFirstText(bytes)
        } else {
            bytes
        }
        val charset = when {
            book.textEncoding.contains("UTF", ignoreCase = true) -> StandardCharsets.UTF_8
            else -> Charset.forName("Windows-31J")
        }
        return cleanAozoraText(String(raw, charset))
    }

    private fun parseIndex(zipFile: File): List<AozoraBook> {
        val csvBytes = FileInputStream(zipFile).use { input ->
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null && !entry.name.lowercase(Locale.ROOT).endsWith(".csv")) {
                    entry = zip.nextEntry
                }
                requireNotNull(entry) { "Aozora catalogue CSV not found" }
                zip.readAllLimited(48 * 1024 * 1024)
            }
        }
        val rows = parseCsv(String(csvBytes, StandardCharsets.UTF_8))
        require(rows.isNotEmpty()) { "Aozora catalogue is empty" }
        val header = rows.first().mapIndexed { index, name -> name.removePrefix("\uFEFF") to index }.toMap()
        fun col(name: String): Int = header[name] ?: -1

        val workIdCol = col("作品ID")
        val titleCol = col("作品名")
        val readingCol = col("作品名読み")
        val authorCol = col("姓名")
        val textUrlCol = col("テキストファイルURL")
        val encodingCol = col("テキストファイル符号化方式")
        val workCopyrightCol = col("作品著作権フラグ")
        val personCopyrightCol = col("人物著作権フラグ")
        val characterCountCol = col("文字数")
        require(workIdCol >= 0 && titleCol >= 0 && textUrlCol >= 0) { "Unknown Aozora CSV format" }

        val merged = LinkedHashMap<String, AozoraBook>()
        rows.drop(1).forEach { row ->
            fun value(index: Int): String = if (index >= 0 && index < row.size) row[index].trim() else ""
            val id = value(workIdCol)
            val title = value(titleCol)
            val textUrl = value(textUrlCol)
            if (id.isBlank() || title.isBlank() || textUrl.isBlank()) return@forEach

            val author = value(authorCol)
            val key = "$id|$textUrl"
            val existing = merged[key]
            if (existing == null) {
                merged[key] = AozoraBook(
                    workId = id,
                    title = title,
                    titleReading = value(readingCol),
                    authors = author,
                    textUrl = textUrl,
                    textEncoding = value(encodingCol),
                    workCopyright = value(workCopyrightCol),
                    personCopyright = value(personCopyrightCol),
                    characterCount = value(characterCountCol).filter(Char::isDigit).toIntOrNull(),
                )
            } else if (author.isNotBlank() && author !in existing.authors.split(" / ")) {
                merged[key] = existing.copy(authors = listOf(existing.authors, author).filter { it.isNotBlank() }.joinToString(" / "))
            }
        }
        return merged.values.toList()
    }

    private fun cleanAozoraText(source: String): String {
        var text = source.replace("\r\n", "\n").replace('\r', '\n')

        val separators = Regex("(?m)^-{20,}\\s*$").findAll(text).toList()
        if (separators.size >= 2) text = text.substring(separators[1].range.last + 1)

        val footer = listOf("\n底本：", "\n底本:").map { text.lastIndexOf(it) }.maxOrNull() ?: -1
        if (footer > text.length / 3) text = text.substring(0, footer)

        text = text
            .replace(Regex("［＃[^］]*］"), "")
            .replace(Regex("《[^》]*》"), "")
            .replace("｜", "")
            .replace(Regex("(?m)^［＃.*$"), "")
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()

        require(text.length >= 20) { "Could not extract book text" }
        return text
    }

    private fun downloadToFile(url: String, destination: File) {
        val temp = File(destination.parentFile, destination.name + ".partial")
        temp.delete()
        val connection = open(url)
        try {
            connection.inputStream.use { input ->
                FileOutputStream(temp).use { output -> input.copyTo(output) }
            }
            if (destination.exists()) destination.delete()
            check(temp.renameTo(destination)) { "Could not cache Aozora catalogue" }
        } finally {
            connection.disconnect()
            temp.delete()
        }
    }

    private fun downloadBytes(url: String, maxBytes: Int): ByteArray {
        val connection = open(url)
        try {
            val length = connection.contentLengthLong
            require(length <= 0L || length <= maxBytes) { "Book file is too large" }
            return connection.inputStream.use { input -> input.readAllLimited(maxBytes) }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        instanceFollowRedirects = true
        requestMethod = "GET"
        setRequestProperty("User-Agent", USER_AGENT)
        setRequestProperty("Accept-Encoding", "identity")
        connect()
        if (responseCode !in 200..299) {
            val code = responseCode
            disconnect()
            error("Aozora HTTP $code")
        }
    }

    private fun ZipInputStream.readAllLimited(maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) { "Aozora file is too large" }
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    private fun java.io.InputStream.readAllLimited(maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) { "Aozora file is too large" }
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    private fun unzipFirstText(bytes: ByteArray): ByteArray {
        return ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null && (entry.isDirectory || !entry.name.lowercase(Locale.ROOT).endsWith(".txt"))) {
                entry = zip.nextEntry
            }
            requireNotNull(entry) { "Text file not found in Aozora ZIP" }
            zip.readAllLimited(32 * 1024 * 1024)
        }
    }

    private fun normalize(value: String): String = value
        .lowercase(Locale.JAPAN)
        .replace("　", "")
        .replace(" ", "")
        .trim()

    /** Tiny RFC4180-ish parser. Handles quoted commas/newlines and escaped quotes. */
    private fun parseCsv(csv: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < csv.length) {
            val ch = csv[i]
            when {
                quoted && ch == '"' && i + 1 < csv.length && csv[i + 1] == '"' -> {
                    field.append('"')
                    i++
                }
                ch == '"' -> quoted = !quoted
                !quoted && ch == ',' -> {
                    row.add(field.toString())
                    field.setLength(0)
                }
                !quoted && ch == '\n' -> {
                    row.add(field.toString())
                    field.setLength(0)
                    if (row.any { it.isNotEmpty() }) rows.add(row)
                    row = ArrayList()
                }
                else -> field.append(ch)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            rows.add(row)
        }
        return rows
    }
}
