package com.phonemcp.mcp

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Offline OCR fallback using Tesseract (rus+eng, tessdata_fast). Used only when
 * the accessibility tree has few text nodes. Returns text lines with the screen
 * coordinates of their centre, so Claude can tap them.
 */
object OcrHelper {

    data class Line(val text: String, val cx: Int, val cy: Int)
    data class Result(val lines: List<Line>, val failed: Boolean)

    @Volatile private var copied = false

    /** Copies the bundled traineddata into filesDir/tessdata once. */
    private fun ensureData(context: Context): String? {
        val dataDir = File(context.filesDir, "tessdata")
        if (!dataDir.exists()) dataDir.mkdirs()
        val langs = listOf("rus", "eng")
        try {
            for (l in langs) {
                val out = File(dataDir, "$l.traineddata")
                if (out.exists() && out.length() > 100_000) continue
                context.assets.open("tessdata/$l.traineddata").use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
            }
            copied = true
        } catch (e: Exception) {
            return null
        }
        // Tesseract wants the dir CONTAINING "tessdata".
        return context.filesDir.absolutePath
    }

    /** Recognize text lines. Blocking; call off the main thread. */
    fun recognize(context: Context, bitmap: Bitmap, timeoutMs: Long = 3000): Result {
        val dataPath = ensureData(context) ?: return Result(emptyList(), true)
        val tess = TessBaseAPI()
        if (!tess.init(dataPath, "rus+eng")) {
            try { tess.recycle() } catch (_: Exception) {}
            return Result(emptyList(), true)
        }
        val exec = Executors.newSingleThreadExecutor()
        val future = exec.submit(Callable {
            tess.setImage(bitmap)
            tess.getUTF8Text() // triggers recognition
            val lines = ArrayList<Line>()
            val it = tess.resultIterator
            it.begin()
            do {
                val t = it.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)?.trim()
                if (!t.isNullOrBlank()) {
                    val r = it.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)
                    if (r != null) lines.add(Line(t, r.centerX(), r.centerY()))
                }
            } while (it.next(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE))
            it.delete()
            lines as List<Line>
        })
        return try {
            Result(future.get(timeoutMs, TimeUnit.MILLISECONDS), false)
        } catch (e: TimeoutException) {
            try { tess.stop() } catch (_: Exception) {}
            future.cancel(true)
            Result(emptyList(), true)
        } catch (e: Exception) {
            Result(emptyList(), true)
        } finally {
            exec.shutdownNow()
            try { tess.recycle() } catch (_: Exception) {}
        }
    }
}
