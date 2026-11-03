package dev.saeed.lasso

import android.graphics.Rect
import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TextModelTest {

    // ---- TextBlock ----

    @Test
    fun oneLineHasOneLineBox() {
        val boxes = lineOf("Hello world").lineBoxes
        assertEquals(listOf(RectF(0f, 0f, 110f, 20f)), boxes)
    }

    @Test
    fun wrappedTextHasALineBoxPerLine() {
        // "abc" on the first line, "de" wrapped to the next one, back at the left edge.
        val boxes = arrayOf<RectF?>(
            RectF(0f, 0f, 10f, 20f), RectF(10f, 0f, 20f, 20f), RectF(20f, 0f, 30f, 20f),
            RectF(0f, 30f, 10f, 50f), RectF(10f, 30f, 20f, 50f),
        )
        val block = TextBlock("abcde", Rect(0, 0, 30, 50), boxes)
        assertEquals(listOf(RectF(0f, 0f, 30f, 20f), RectF(0f, 30f, 20f, 50f)), block.lineBoxes)
    }

    @Test
    fun charactersOutsideTheBlockAreLeftOut() {
        val boxes = arrayOf<RectF?>(RectF(0f, 0f, 10f, 20f), RectF(500f, 0f, 510f, 20f))
        val block = TextBlock("ab", Rect(0, 0, 10, 20), boxes)
        assertEquals(listOf(RectF(0f, 0f, 10f, 20f)), block.lineBoxes)
        assertNull(block.boxOf(1, 2))
    }

    @Test
    fun boxOfCoversTheGivenCharacters() {
        assertEquals(RectF(60f, 0f, 110f, 20f), lineOf("Hello world").boxOf(6, 11))
    }

    // ---- ScreenText: words ----

    @Test
    fun textIsSplitIntoWordsInReadingOrder() {
        val screen = ScreenText(listOf(lineOf("Hello big"), lineOf("world", top = 30)))
        assertEquals(listOf("Hello", "big", "world"), screen.words.map { screen.textOf(it.index, it.index) })
        assertEquals(listOf(0, 1, 2), screen.words.map { it.index })
    }

    @Test
    fun wordsWithoutVisibleCharactersAreSkipped() {
        val boxes = arrayOf<RectF?>(RectF(0f, 0f, 10f, 20f), null, null, null)
        val screen = ScreenText(listOf(TextBlock("a bc", Rect(0, 0, 40, 20), boxes)))
        assertEquals(1, screen.words.size)
    }

    @Test
    fun emptyScreenHasNothingToSelect() {
        val screen = ScreenText(emptyList())
        assertTrue(screen.words.isEmpty())
        assertEquals("", screen.textOf(0, 5))
        assertTrue(screen.highlightOf(0, 5).isEmpty())
        assertNull(screen.nearestWord(10f, 10f))
    }

    // ---- ScreenText: hit testing ----

    @Test
    fun wordAtFindsTheWordUnderTheFinger() {
        val screen = ScreenText(listOf(lineOf("Hello world")))
        assertEquals(1, screen.wordAt(80f, 10f, slop = 0f)?.index)
    }

    @Test
    fun wordAtAllowsSomeSlop() {
        val screen = ScreenText(listOf(lineOf("Hello")))
        assertNull(screen.wordAt(55f, 10f, slop = 0f))
        assertEquals(0, screen.wordAt(55f, 10f, slop = 6f)?.index)
    }

    @Test
    fun nearestWordPrefersTheSameLineWhenDraggingSideways() {
        // The finger is on the first line, 30 px right of "first". "second" is right below the
        // finger but 20 px down: vertical distance counts double, so "first" wins.
        val screen = ScreenText(listOf(lineOf("first"), lineOf("second", left = 60, top = 30)))
        assertEquals(0, screen.nearestWord(80f, 10f)?.index)
    }

    // ---- ScreenText: selected text ----

    @Test
    fun wordsInOneLineKeepTheirSpacing() {
        val screen = ScreenText(listOf(lineOf("Hello big world")))
        assertEquals("big world", screen.textOf(1, 2))
        assertEquals("Hello big world", screen.textOf(0, 2))
    }

    @Test
    fun rangesBeyondTheWordsAreClamped() {
        val screen = ScreenText(listOf(lineOf("Hello world")))
        assertEquals("Hello world", screen.textOf(-2, 9))
    }

    @Test
    fun linesBelowEachOtherAreJoinedWithANewline() {
        val screen = ScreenText(listOf(lineOf("first line"), lineOf("second line", top = 30)))
        assertEquals("line\nsecond", screen.textOf(1, 2))
    }

    @Test
    fun blocksSideBySideWithAGapGetASpace() {
        // Two recognized lines on the same row, like table columns.
        val screen = ScreenText(listOf(lineOf("Name"), lineOf("Value", left = 100)))
        assertEquals("Name Value", screen.textOf(0, 1))
    }

    @Test
    fun blocksThatTouchAreJoinedWithoutASpace() {
        val screen = ScreenText(listOf(lineOf("foo"), lineOf("bar", left = 30)))
        assertEquals("foobar", screen.textOf(0, 1))
    }

    @Test
    fun aSpaceInTheTextIsKeptBetweenBlocks() {
        // "Hello " ends with a space; the next block starts right after it.
        val screen = ScreenText(listOf(lineOf("Hello "), lineOf("world", left = 60)))
        assertEquals("Hello world", screen.textOf(0, 1))
    }

    @Test
    fun highlightHasABoxPerSelectedLine() {
        val screen = ScreenText(listOf(lineOf("first line"), lineOf("second line", top = 30)))
        assertEquals(
            listOf(RectF(60f, 0f, 100f, 20f), RectF(0f, 30f, 60f, 50f)),
            screen.highlightOf(1, 2),
        )
    }
}
