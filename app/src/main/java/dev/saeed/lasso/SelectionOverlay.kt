package dev.saeed.lasso

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Path
import android.graphics.PorterDuffXfermode
import android.graphics.Region
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Full-screen view drawn over the live app: dims everything except the text,
 * and lets the user select words and copy or share them.
 */
@SuppressLint("ViewConstructor")
class SelectionOverlay(
    context: Context,
    private var screen: ScreenText,
    /** Screenshot taken when Lasso opened, shown instead of the live app; null if none. */
    private val frozen: Bitmap?,
    /** Where the screenshot shows apps. Elsewhere (system bars, panels) stays live. */
    frozenArea: Region,
    private val onCopy: (String) -> Unit,
    private val onShare: (String) -> Unit,
    private val onTranslate: (String) -> Unit,
    private val onLaunch: (android.content.Intent) -> Unit,
    private val onDismiss: () -> Unit,
) : FrameLayout(context) {

    private val palette = Palette(context)

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private val accent = palette.accent
    private val scrimPaint = Paint().apply { color = 0x8C000000.toInt() }
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; alpha = 0x66 }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }

    private val holePadding = dp(3f)
    private val holeRadius = dp(4f)
    private val handleRadius = dp(9f)
    private val touchSlop = dp(8f)

    private val holeRect = RectF()
    private val frozenClip: Path = frozenArea.boundaryPath

    /** Where this view sits on screen; node coordinates are screen-based. */
    private val origin = IntArray(2)

    // Selection: word indices; -1 when nothing is selected.
    private var anchor = -1
    private var focus = -1
    private val hasSelection get() = anchor >= 0
    private val first get() = min(anchor, focus)
    private val last get() = max(anchor, focus)
    private var highlight: List<RectF> = emptyList()

    private enum class Drag { NONE, WORDS, START_HANDLE, END_HANDLE, OUTSIDE }
    private var drag = Drag.NONE
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var lastTapAt = 0L
    private var lastTapBlock: TextBlock? = null

    /** Card for picking words inside one block; null when closed. */
    private var picker: WordPicker? = null

    private val toolbar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        background = GradientDrawable().apply {
            cornerRadius = dp(22f)
            setColor(palette.surface)
        }
        elevation = dp(6f)
        visibility = GONE
        addView(button(R.string.action_copy) { onCopy(selectedText()) })
        addView(button(R.string.action_share) { onShare(selectedText()) })
        addView(button(R.string.action_translate) { onTranslate(selectedText()) })
        addView(button(R.string.action_select_all) { selectAll() })
    }

    private val emptyMessage = TextView(context).apply {
        setText(R.string.no_text_found)
        setTextColor(Color.WHITE)
        textSize = 15f
        val pad = dp(14f).toInt()
        setPadding(pad, pad, pad, pad)
        background = GradientDrawable().apply { cornerRadius = dp(20f); setColor(0xCC000000.toInt()) }
    }

    init {
        setWillNotDraw(false)
        isFocusable = true
        isFocusableInTouchMode = true
        addView(toolbar, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(emptyMessage, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        emptyMessage.visibility = if (screen.words.isEmpty()) VISIBLE else GONE
        alpha = 0f
    }

    /**
     * Swaps in a newer reading of the screen (apps like Chrome fill in their text a moment
     * after the first read). Ignored once the user has started selecting.
     */
    fun update(newScreen: ScreenText): Boolean {
        if (hasSelection || drag != Drag.NONE || picker != null) return false
        screen = newScreen
        emptyMessage.visibility = if (screen.words.isEmpty()) VISIBLE else GONE
        invalidate()
        return true
    }

    val wordCount get() = screen.words.size

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestFocus()
        animate().alpha(1f).setDuration(120).start()
        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    }

    override fun onDetachedFromWindow() {
        closePicker()
        super.onDetachedFromWindow()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        getLocationOnScreen(origin)
        positionToolbar()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) onDismiss()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ---- Drawing ----

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.translate(-origin[0].toFloat(), -origin[1].toFloat())

        // Freeze the app: draw the screenshot over it.
        frozen?.let {
            canvas.save()
            canvas.clipPath(frozenClip)
            canvas.drawBitmap(it, 0f, 0f, null)
            canvas.restore()
        }

        // Dim layer with rounded holes around every line of text.
        val layer = canvas.saveLayer(null, null)
        canvas.drawPaint(scrimPaint)
        val r = holeRect
        for (block in screen.blocks) for (line in block.lineBoxes) {
            r.set(line); r.inset(-holePadding, -holePadding)
            canvas.drawRoundRect(r, holeRadius, holeRadius, clearPaint)
        }
        canvas.restoreToCount(layer)

        if (hasSelection) {
            for (box in highlight) canvas.drawRect(box, highlightPaint)
            startHandle()?.let { drawHandle(canvas, it, left = true) }
            endHandle()?.let { drawHandle(canvas, it, left = false) }
        }
        canvas.restore()
    }

    /** A circle hanging below the text, with a pointed corner touching the selection edge. */
    private fun drawHandle(canvas: Canvas, at: Pair<Float, Float>, left: Boolean) {
        val (x, y) = at
        val cx = if (left) x - handleRadius else x + handleRadius
        val cy = y + handleRadius
        canvas.drawCircle(cx, cy, handleRadius, handlePaint)
        if (left) canvas.drawRect(cx, y, x, cy, handlePaint) else canvas.drawRect(x, y, cx, cy, handlePaint)
    }

    private fun startHandle() = highlight.firstOrNull()?.let { it.left to it.bottom }
    private fun endHandle() = highlight.lastOrNull()?.let { it.right to it.bottom }

    // ---- Touch ----

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Work in screen coordinates, like the node boxes.
        val x = event.rawX
        val y = event.rawY
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x; downY = y; moved = false
                drag = when {
                    picker != null -> Drag.OUTSIDE
                    hasSelection && hitsHandle(startHandle(), x, y, left = true) -> Drag.START_HANDLE
                    hasSelection && hitsHandle(endHandle(), x, y, left = false) -> Drag.END_HANDLE
                    else -> screen.wordAt(x, y, touchSlop / 2)?.let { word ->
                        select(word.index, word.index)
                        Drag.WORDS
                    } ?: Drag.OUTSIDE
                }
                if (drag != Drag.OUTSIDE) toolbar.visibility = GONE
            }
            MotionEvent.ACTION_MOVE -> {
                if (!moved && hypot(x - downX, y - downY) > touchSlop) moved = true
                if (!moved) return true
                // Handles sit below the text; aim a bit above the finger.
                val aimY = y - handleRadius * 2
                when (drag) {
                    Drag.WORDS -> screen.nearestWord(x, y)?.let { select(anchor, it.index) }
                    Drag.START_HANDLE -> screen.nearestWord(x, aimY)?.let { moveHandle(start = true, it.index) }
                    Drag.END_HANDLE -> screen.nearestWord(x, aimY)?.let { moveHandle(start = false, it.index) }
                    else -> Unit
                }
            }
            MotionEvent.ACTION_UP -> {
                when (drag) {
                    Drag.OUTSIDE -> if (!moved) {
                        when {
                            picker != null -> closePicker()
                            hasSelection -> clearSelection()
                            else -> onDismiss()
                        }
                    }
                    Drag.WORDS -> {
                        if (!moved) handleTap(x, y)
                        if (picker == null) showToolbar()
                    }
                    Drag.START_HANDLE, Drag.END_HANDLE -> showToolbar()
                    Drag.NONE -> Unit
                }
                drag = Drag.NONE
            }
            MotionEvent.ACTION_CANCEL -> {
                drag = Drag.NONE
                if (hasSelection) showToolbar()
            }
        }
        return true
    }

    /** A second tap on the same block selects all of it. */
    private fun handleTap(x: Float, y: Float) {
        val word = screen.wordAt(x, y, touchSlop / 2) ?: return
        // No word positions for this block: pick words from a card instead.
        if (!word.block.hasCharBoxes && word.block.text.trim().any(Char::isWhitespace)) {
            openPicker(word.block)
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastTapAt < 350 && lastTapBlock === word.block) {
            val words = screen.words.filter { it.block === word.block }
            select(words.first().index, words.last().index)
            lastTapAt = 0
        } else {
            lastTapAt = now
            lastTapBlock = word.block
        }
    }

    private fun hitsHandle(handle: Pair<Float, Float>?, x: Float, y: Float, left: Boolean): Boolean {
        val (hx, hy) = handle ?: return false
        val cx = if (left) hx - handleRadius else hx + handleRadius
        return hypot(x - cx, y - (hy + handleRadius)) < handleRadius * 2.5f
    }

    private fun moveHandle(start: Boolean, index: Int) {
        // Keep the other end fixed, even if the handles cross.
        select(if (start) last else first, index)
    }

    // ---- Selection ----

    private fun select(a: Int, f: Int) {
        anchor = a; focus = f
        highlight = screen.highlightOf(first, last)
        invalidate()
    }

    private fun selectAll() {
        if (screen.words.isEmpty()) return
        select(0, screen.words.lastIndex)
        showToolbar()
    }

    private fun clearSelection() {
        anchor = -1; focus = -1
        highlight = emptyList()
        toolbar.visibility = GONE
        invalidate()
    }

    private fun selectedText() = if (hasSelection) screen.textOf(first, last) else ""

    // ---- Word picker ----

    private fun openPicker(block: TextBlock) {
        clearSelection()
        val card = WordPicker(
            context, block.text.trim(), palette,
            // Opposite half of the screen from the block, so it stays visible.
            atBottom = block.bounds.centerY() - origin[1] < height / 2,
            maxTextHeight = (height * 0.4f).toInt(),
            onCopy = onCopy, onShare = onShare, onTranslate = onTranslate, onLaunch = onLaunch,
            onClosed = { picker = null },
        )
        picker = card
        card.show()
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    private fun closePicker() {
        picker?.dismiss()
        picker = null
    }

    // ---- Toolbar ----

    private fun showToolbar() {
        if (!hasSelection) return
        toolbar.visibility = VISIBLE
        positionToolbar()
    }

    /** Above the selection if there's room, otherwise below it. */
    private fun positionToolbar() {
        if (toolbar.visibility != VISIBLE || highlight.isEmpty()) return
        toolbar.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
        val w = toolbar.measuredWidth
        val h = toolbar.measuredHeight
        val top = highlight.minOf { it.top } - origin[1]
        val bottom = highlight.maxOf { it.bottom } - origin[1]
        val centerX = (highlight.minOf { it.left } + highlight.maxOf { it.right }) / 2 - origin[0]
        val gap = dp(12f)
        val minY = dp(40f) // Below the status bar.
        var y = top - gap - h
        if (y < minY) y = bottom + handleRadius * 2 + gap
        y = y.coerceIn(minY, max(minY, height - h - dp(48f)))
        toolbar.x = (centerX - w / 2f).coerceIn(dp(8f), max(dp(8f), width - w - dp(8f)))
        toolbar.y = y
    }

    private fun button(textRes: Int, onClick: () -> Unit) = TextView(context).apply {
        setText(textRes)
        setTextColor(palette.onSurface)
        textSize = 15f
        val padH = dp(16f).toInt()
        val padV = dp(11f).toInt()
        setPadding(padH, padV, padH, padV)
        background = with(TypedValue()) {
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, this, true)
            context.getDrawable(resourceId)
        }
        setOnClickListener { onClick() }
    }
}
