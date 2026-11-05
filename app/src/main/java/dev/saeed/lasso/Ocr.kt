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
            Tasks.await(recognizer.process(InputImage.fromBitmap(screenshot, 0)), TIMEOUT_S, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.w("Lasso", "Text recognition failed", e)
            return emptyList()
        }
        return result.textBlocks.flatMap { it.lines }
            // Icons read as text ("-)", "iii") score below this; real text scores 0.75 and up.
            .filter { it.confidence >= MIN_CONFIDENCE }
            .mapNotNull { line -> lineBlock(line.elements.mapNotNull(::wordOf)) }
    }

    private fun wordOf(element: Text.Element): RecognizedWord? {
        val box = element.boundingBox ?: return null
        val symbols = element.symbols.mapNotNull { it.boundingBox }
        val perChar = symbols.size == element.symbols.size && symbols.size == element.text.length
        return RecognizedWord(element.text, box, symbols.takeIf { perChar })
    }

    /**
     * Loads the recognizer's model by reading a tiny blank image. The first read after startup
     * is slow (several seconds on slow phones), so do it before the user opens Lasso.
     */
    fun warmUp() {
        val blank = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        try {
            Tasks.await(recognizer.process(InputImage.fromBitmap(blank, 0)), TIMEOUT_S, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.w("Lasso", "Text recognizer warm-up failed", e)
        } finally {
            blank.recycle()
        }
    }

    override fun close() = recognizer.close()

    private companion object {
        const val MIN_CONFIDENCE = 0.65f
        /** Generous, for the first read on a slow phone; later reads take well under a second. */
        const val TIMEOUT_S = 15L
    }
}

/** A recognized word, with a box per character if the recognizer gave one for each. */
internal class RecognizedWord(val text: String, val box: Rect, val charBoxes: List<Rect>?)

/** One line of recognized words as a [TextBlock], or null if it has no text. */
internal fun lineBlock(words: List<RecognizedWord>): TextBlock? {
    val used = words.filter { it.text.isNotBlank() }
    if (used.isEmpty()) return null
    // Words joined by single spaces, so we know which character each box belongs to.
    val text = StringBuilder()
    val boxes = mutableListOf<RectF?>()
    for (word in used) {
        if (text.isNotEmpty()) {
            text.append(' ')
            boxes += null
        }
        text.append(word.text)
        boxes += charBoxes(word)
    }
    val bounds = Rect(used[0].box).apply { used.forEach { union(it.box) } }
    return TextBlock(text.toString(), bounds, boxes.toTypedArray())
}

/** Boxes of each character of a word: from the recognizer if it has them, else evenly split. */
private fun charBoxes(word: RecognizedWord): List<RectF> {
    word.charBoxes?.let { return it.map(::RectF) }
    val box = word.box
    val step = box.width().toFloat() / word.text.length
    return List(word.text.length) { i ->
        RectF(box.left + i * step, box.top.toFloat(), box.left + (i + 1) * step, box.bottom.toFloat())
    }
}
