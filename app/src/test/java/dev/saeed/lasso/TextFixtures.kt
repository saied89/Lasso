package dev.saeed.lasso

import android.graphics.Rect
import android.graphics.RectF

const val CHAR_W = 10
const val LINE_H = 20

/**
 * A one-line block like the text recognizer makes: each character is [CHAR_W] wide, starting
 * at ([left], [top]), and spaces have no box.
 */
fun lineOf(text: String, left: Int = 0, top: Int = 0): TextBlock {
    val boxes = Array(text.length) { i ->
        if (text[i] == ' ') null
        else RectF((left + i * CHAR_W).toFloat(), top.toFloat(), (left + (i + 1) * CHAR_W).toFloat(), (top + LINE_H).toFloat())
    }
    return TextBlock(text, Rect(left, top, left + text.length * CHAR_W, top + LINE_H), boxes)
}

/** Center of the [n]th character of [block], in screen coordinates. */
fun TextBlock.charCenter(n: Int): Pair<Float, Float> {
    val box = boxOf(n, n + 1)!!
    return box.centerX() to box.centerY()
}
