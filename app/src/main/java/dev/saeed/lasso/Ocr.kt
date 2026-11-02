package dev.saeed.lasso

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit

/**
 * Reads text from a screenshot with ML Kit's on-device recognizer (Latin script).
 * Blocks while recognizing, so run it off the main thread.
 */
class Ocr : AutoCloseable {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /** One block per recognized line, with a box per character. Screenshot pixels are screen pixels. */
    fun read(screenshot: Bitmap): List<TextBlock> {
        val result = try {
            Tasks.await(recognizer.process(InputImage.fromBitmap(screenshot, 0)), 5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.w("Lasso", "Text recognition failed", e)
            return emptyList()
        }
        return result.textBlocks.flatMap { it.lines }
            // Icons read as text ("-)", "iii") score below this; real text scores 0.75 and up.
            .filter { it.confidence >= MIN_CONFIDENCE }
            .mapNotNull(::toBlock)
    }

    private fun toBlock(line: Text.Line): TextBlock? {
        val elements = line.elements.filter { it.boundingBox != null && it.text.isNotBlank() }
        if (elements.isEmpty()) return null
        // Words joined by single spaces, so we know which character each box belongs to.
        val text = StringBuilder()
        val boxes = mutableListOf<RectF?>()
        for (element in elements) {
            if (text.isNotEmpty()) {
                text.append(' ')
                boxes += null
            }
            text.append(element.text)
            boxes += charBoxes(element)
        }
        val bounds = Rect(elements[0].boundingBox!!).apply { elements.forEach { union(it.boundingBox!!) } }
        return TextBlock(text.toString(), bounds, boxes.toTypedArray())
    }

    /** Boxes of each character of a word: from the recognizer if it has them, else evenly split. */
    private fun charBoxes(element: Text.Element): List<RectF> {
        val word = element.text
        val symbols = element.symbols
        if (symbols.size == word.length && symbols.all { it.boundingBox != null }) {
            return symbols.map { RectF(it.boundingBox!!) }
        }
        val box = element.boundingBox!!
        val step = box.width().toFloat() / word.length
        return List(word.length) { i ->
            RectF(box.left + i * step, box.top.toFloat(), box.left + (i + 1) * step, box.bottom.toFloat())
        }
    }

    override fun close() = recognizer.close()

    private companion object {
        const val MIN_CONFIDENCE = 0.65f
    }
}
