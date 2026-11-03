package dev.saeed.lasso

import android.graphics.Rect
import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OcrLineTest {

    @Test
    fun wordsAreJoinedWithSingleSpaces() {
        val block = lineBlock(listOf(word("Hi", 0), word("there", 40)))!!
        assertEquals("Hi there", block.text)
        assertEquals(Rect(0, 0, 90, 20), block.bounds)
    }

    @Test
    fun theSpaceBetweenWordsHasNoBox() {
        val block = lineBlock(listOf(word("a", 0), word("b", 40)))!!
        // Boxes of "a b": the middle one is missing, so the line box spans from a to b.
        assertNull(block.boxOf(1, 2))
        assertEquals(listOf(RectF(0f, 0f, 50f, 20f)), block.lineBoxes)
    }

    @Test
    fun theRecognizersCharacterBoxesAreUsedWhenGiven() {
        val chars = listOf(Rect(0, 0, 4, 20), Rect(4, 0, 20, 20))
        val block = lineBlock(listOf(RecognizedWord("iw", Rect(0, 0, 20, 20), chars)))!!
        assertEquals(RectF(0f, 0f, 4f, 20f), block.boxOf(0, 1))
        assertEquals(RectF(4f, 0f, 20f, 20f), block.boxOf(1, 2))
    }

    @Test
    fun withoutCharacterBoxesTheWordIsSplitEvenly() {
        val block = lineBlock(listOf(RecognizedWord("ab", Rect(0, 0, 20, 20), null)))!!
        assertEquals(RectF(0f, 0f, 10f, 20f), block.boxOf(0, 1))
        assertEquals(RectF(10f, 0f, 20f, 20f), block.boxOf(1, 2))
    }

    @Test
    fun blankWordsAreDropped() {
        val block = lineBlock(listOf(word(" ", 0), word("ok", 40)))!!
        assertEquals("ok", block.text)
        assertNull(lineBlock(listOf(word(" ", 0))))
        assertNull(lineBlock(emptyList()))
    }

    /** A word with an even box per character, starting at [left]. */
    private fun word(text: String, left: Int) = RecognizedWord(text, Rect(left, 0, left + text.length * 10, 20), null)
}
