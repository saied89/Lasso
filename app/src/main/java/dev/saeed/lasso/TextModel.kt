package dev.saeed.lasso

import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

/**
 * One line of recognized text. All coordinates are in screen pixels.
 */
class TextBlock(
    val text: String,
    val bounds: Rect,
    /** Box per character of [text]; null for characters without one (spaces). */
    private val charBoxes: Array<RectF?>,
) {
    /** Boxes around each visual line of the whole text. Used to cut the bright "holes". */
    val lineBoxes: List<RectF> = lineBoxes(0, text.length)

    /** Merges the boxes of characters [from, to) into one box per visual line. */
    fun lineBoxes(from: Int, to: Int): List<RectF> {
        val boxes = charBoxes
        val clip = RectF(bounds)
        val lines = mutableListOf<RectF>()
        var current: RectF? = null
        for (i in max(0, from) until min(to, boxes.size)) {
            val box = boxes[i] ?: continue
            if (box.isEmpty || !RectF.intersects(box, clip)) continue
            val c = current
            if (c != null && sameLine(c, box)) {
                c.union(box)
            } else {
                current = RectF(box).also { lines += it }
            }
        }
        lines.forEach { it.intersect(clip) }
        return lines
    }

    /** Box around characters [from, to), or null if none of them are visible. */
    fun boxOf(from: Int, to: Int): RectF? {
        val lines = lineBoxes(from, to)
        if (lines.isEmpty()) return null
        return RectF(lines[0]).apply { lines.forEach { union(it) } }
    }

    private fun sameLine(line: RectF, box: RectF): Boolean {
        val overlap = min(line.bottom, box.bottom) - max(line.top, box.top)
        // Same line if they overlap vertically by at least half a character height,
        // and the new box doesn't jump back to the left (a wrapped line).
        return overlap > 0.5f * min(line.height(), box.height()) && box.left >= line.left - 1f
    }
}

/** A selectable unit: a whitespace-separated word. */
class Word(
    val index: Int,
    val block: TextBlock,
    val start: Int,
    val end: Int,
    val box: RectF,
)

/** All text on screen, split into words in reading order. */
class ScreenText(val blocks: List<TextBlock>) {
    val words: List<Word> = buildList {
        for (block in blocks) {
            for (match in WORD.findAll(block.text)) {
                val box = block.boxOf(match.range.first, match.range.last + 1) ?: continue
                add(Word(size, block, match.range.first, match.range.last + 1, box))
            }
        }
    }

    /** Word whose box contains the point, allowing [slop] pixels around it. */
    fun wordAt(x: Float, y: Float, slop: Float): Word? =
        words.firstOrNull { w -> x >= w.box.left - slop && x <= w.box.right + slop && y >= w.box.top - slop && y <= w.box.bottom + slop }

    /** Closest word to the point. */
    fun nearestWord(x: Float, y: Float): Word? = words.minByOrNull { w ->
        val dx = max(0f, max(w.box.left - x, x - w.box.right))
        // Weight vertical distance more, so dragging sideways stays on the same line.
        val dy = max(0f, max(w.box.top - y, y - w.box.bottom)) * 2f
        dx * dx + dy * dy
    }

    /**
     * Text of words [first, last], keeping the original spacing inside each block.
     * Web pages split a sentence into many blocks ("A ", "lasso", " or "...), so blocks on the
     * same visual line are joined inline; blocks on different lines go on separate lines.
     */
    fun textOf(first: Int, last: Int): String = buildString {
        var prev: Triple<TextBlock, Int, Int>? = null
        var prevBox: RectF? = null
        for (range in rangesOf(first, last)) {
            val (block, start, end) = range
            val lines = block.lineBoxes(start, end)
            val firstBox = lines.firstOrNull() ?: RectF(block.bounds)
            val p = prev
            val pb = prevBox
            if (p != null && pb != null) {
                append(separator(p, pb, range, firstBox))
            }
            append(block.text, start, end)
            prev = range
            prevBox = lines.lastOrNull() ?: RectF(block.bounds)
        }
    }

    private fun separator(
        prev: Triple<TextBlock, Int, Int>, prevBox: RectF,
        next: Triple<TextBlock, Int, Int>, nextBox: RectF,
    ): String {
        val overlap = min(prevBox.bottom, nextBox.bottom) - max(prevBox.top, nextBox.top)
        val sameLine = overlap > 0.5f * min(prevBox.height(), nextBox.height()) && nextBox.left >= prevBox.left
        if (!sameLine) return "\n"
        val (pBlock, _, pEnd) = prev
        val (nBlock, nStart, _) = next
        val spaceInText = (pEnd < pBlock.text.length && pBlock.text[pEnd].isWhitespace()) ||
            (nStart > 0 && nBlock.text[nStart - 1].isWhitespace())
        val visibleGap = nextBox.left - prevBox.right > 0.25f * min(prevBox.height(), nextBox.height())
        return if (spaceInText || visibleGap) " " else ""
    }

    /** Highlight boxes for words [first, last]. */
    fun highlightOf(first: Int, last: Int): List<RectF> = rangesOf(first, last).flatMap { (block, start, end) ->
        block.lineBoxes(start, end)
    }

    /** Groups words [first, last] by block into (block, startChar, endChar). */
    private fun rangesOf(first: Int, last: Int): List<Triple<TextBlock, Int, Int>> {
        if (words.isEmpty()) return emptyList()
        val result = mutableListOf<Triple<TextBlock, Int, Int>>()
        for (i in max(0, first)..min(last, words.lastIndex)) {
            val w = words[i]
            val prev = result.lastOrNull()
            if (prev != null && prev.first === w.block) {
                result[result.lastIndex] = Triple(w.block, prev.second, w.end)
            } else {
                result += Triple(w.block, w.start, w.end)
            }
        }
        return result
    }

    private companion object {
        val WORD = Regex("\\S+")
    }
}
