package com.example.readercompose

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.charset.Charset
import java.util.Locale

// Один абзац текста; heading = true для заголовков FB2.
data class BookParagraph(val text: String, val heading: Boolean = false)

// Прочитанный файл: его имя и абзацы для экрана с прокруткой.
data class OpenedBook(
    val name: String,
    val paragraphs: List<BookParagraph>
)

// Читает TXT и FB2, не используя базу данных.
object BookReader {
    private const val MAX_FILE_BYTES = 10 * 1024 * 1024
    private const val MAX_PARAGRAPHS = 50_000
    private val paragraphTags = setOf("p", "subtitle", "v", "text-author")

    fun open(context: Context, uri: Uri): OpenedBook {
        val name = getFileName(context, uri)
        val extension = name.substringAfterLast('.', "")
            .lowercase(Locale.ROOT)

        if (extension != "txt" && extension != "fb2") {
            throw IOException("Поддерживаются только файлы .txt и .fb2")
        }

        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Не удалось открыть выбранный файл")

        val paragraphs = stream.use { input ->
            when (extension) {
                "txt" -> readTxt(input)
                else -> readFb2(input)
            }
        }

        if (paragraphs.none { it.text.isNotBlank() }) {
            throw IOException("Файл пустой или в нём нет текста")
        }

        return OpenedBook(name, paragraphs)
    }

    // Имя берём из файлового менеджера Android.
    private fun getFileName(context: Context, uri: Uri): String {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val column = cursor.getColumnIndex(
                    OpenableColumns.DISPLAY_NAME
                )
                if (column >= 0) {
                    return cursor.getString(column) ?: "Без названия"
                }
            }
        }
        return uri.lastPathSegment ?: "Без названия"
    }

    // TXT: сначала пробуем UTF-8, затем Windows-1251.
    // Также поддерживаются файлы с BOM UTF-8 / UTF-16.
    private fun readTxt(stream: InputStream): List<BookParagraph> {
        val bytes = readLimited(stream)
        if (bytes.isEmpty()) {
            throw IOException("Файл пустой")
        }

        val text = decodeTxt(bytes)
        if (text.isBlank()) {
            throw IOException("Файл пустой")
        }

        val lines = text.lineSequence().toList()
        if (lines.size > MAX_PARAGRAPHS) {
            throw IOException("Слишком много строк в файле")
        }

        return lines.map { line -> BookParagraph(line.trimEnd()) }
    }

    private fun decodeTxt(bytes: ByteArray): String {
        val charset: Charset
        val start: Int

        when {
            hasBom(bytes, 0xEF, 0xBB, 0xBF) -> {
                charset = StandardCharsets.UTF_8
                start = 3
            }
            hasBom(bytes, 0xFF, 0xFE) -> {
                charset = StandardCharsets.UTF_16LE
                start = 2
            }
            hasBom(bytes, 0xFE, 0xFF) -> {
                charset = StandardCharsets.UTF_16BE
                start = 2
            }
            else -> {
                charset = StandardCharsets.UTF_8
                start = 0
            }
        }

        val data = ByteBuffer.wrap(bytes, start, bytes.size - start)
        return try {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(data)
                .toString()
        } catch (error: CharacterCodingException) {
            // Для старых русских TXT без BOM.
            String(bytes, Charset.forName("windows-1251"))
        }
    }

    private fun hasBom(bytes: ByteArray, vararg values: Int): Boolean {
        if (bytes.size < values.size) return false
        return values.indices.all { index ->
            (bytes[index].toInt() and 0xFF) == values[index]
        }
    }

    // Читаем порциями, чтобы не загрузить гигантский файл в память.
    private fun readLimited(stream: InputStream): ByteArray {
        val result = ByteArrayOutputStream()
        val buffer = ByteArray(8192)

        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            if (result.size() + count > MAX_FILE_BYTES) {
                throw IOException("Файл слишком большой (максимум 10 МБ)")
            }
            result.write(buffer, 0, count)
        }
        return result.toByteArray()
    }

    // FB2 — это XML. Забираем текст только из секций <body>.
    // Теги форматирования (<emphasis>, <strong> и др.) не показываем.
    private fun readFb2(input: InputStream): List<BookParagraph> {
        val paragraphs = ArrayList<BookParagraph>()
        val parser = Xml.newPullParser()

        try {
            parser.setInput(SizeLimitedStream(input), null)

            var depth = 0
            var bodyDepth = 0
            var titleDepth = 0
            var validBook = false
            var text: StringBuilder? = null
            var isHeading = false

            fun finishParagraph() {
                val ready = text?.toString()?.trim().orEmpty()
                if (ready.isNotEmpty()) {
                    if (paragraphs.size >= MAX_PARAGRAPHS) {
                        throw IOException("Слишком много абзацев в книге")
                    }
                    paragraphs.add(BookParagraph(ready, isHeading))
                }
                text = null
            }

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        depth++
                        val tag = parser.name.lowercase(Locale.ROOT)

                        if (depth == 1 && tag == "fictionbook") {
                            validBook = true
                        }

                        if (tag == "body" && validBook) {
                            bodyDepth = depth
                        } else if (bodyDepth > 0) {
                            if (tag == "title") titleDepth = depth

                            if (tag in paragraphTags) {
                                finishParagraph()
                                text = StringBuilder()
                                isHeading = titleDepth > 0 || tag == "subtitle"
                            }
                        }
                    }

                    XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                        if (bodyDepth > 0) {
                            text?.append(parser.text)
                        }
                    }

                    XmlPullParser.END_TAG -> {
                        val tag = parser.name.lowercase(Locale.ROOT)
                        if (bodyDepth > 0 && tag in paragraphTags) {
                            finishParagraph()
                        }
                        if (tag == "title" && depth == titleDepth) {
                            titleDepth = 0
                        }
                        if (tag == "body" && depth == bodyDepth) {
                            bodyDepth = 0
                        }
                        depth--
                    }
                }
                event = parser.next()
            }

            if (!validBook) {
                throw IOException("Это не книга формата FB2")
            }
            return paragraphs
        } catch (error: XmlPullParserException) {
            throw IOException("Ошибка формата FB2: повреждённый XML", error)
        }
    }

    // Ограничение размера применяется и к FB2 при потоковом разборе.
    private class SizeLimitedStream(input: InputStream) :
        FilterInputStream(input) {

        private var bytesRead = 0L

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) checkSize(1)
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = `in`.read(buffer, offset, length)
            if (count > 0) checkSize(count)
            return count
        }

        private fun checkSize(amount: Int) {
            bytesRead += amount
            if (bytesRead > MAX_FILE_BYTES) {
                throw IOException("Файл слишком большой (максимум 10 МБ)")
            }
        }
    }
}
