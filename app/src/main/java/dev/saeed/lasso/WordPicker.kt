package dev.saeed.lasso

import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.ActionMode
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

/**
 * A card showing one block's text with Android's own text selection, for apps that don't
 * report where each word is (e.g. newer WebViews). It's a dialog rather than a plain view,
 * because the selection menu (Copy, Translate, other apps' actions) needs a full window.
 */
class WordPicker(
    context: Context,
    private val text: String,
    private val dark: Boolean,
    atBottom: Boolean,
    maxTextHeight: Int,
    private val onCopy: (String) -> Unit,
    private val onShare: (String) -> Unit,
    private val onTranslate: (String) -> Unit,
    /** Starts an activity from the selection menu, closing Lasso first. */
    private val onLaunch: (Intent) -> Unit,
    onClosed: () -> Unit,
) {
    // The menu starts activities with this context. A service can't do that without
    // FLAG_ACTIVITY_NEW_TASK, so route them through onLaunch.
    private val launcher = object : ContextWrapper(context) {
        override fun startActivity(intent: Intent) = onLaunch(intent)
        override fun startActivity(intent: Intent, options: Bundle?) = onLaunch(intent)
    }

    private val dialog = Dialog(
        launcher,
        if (dark) android.R.style.Theme_DeviceDefault_Dialog_NoActionBar
        else android.R.style.Theme_DeviceDefault_Light_Dialog_NoActionBar,
    )
    private val ctx = dialog.context
    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics)
    private val textColor = if (dark) 0xFFE8EAED.toInt() else 0xFF202124.toInt()

    private val body = TextView(ctx).apply {
        setText(this@WordPicker.text)
        setTextColor(textColor)
        textSize = 18f
        setLineSpacing(dp(4f), 1f)
        setTextIsSelectable(true)
        val pad = dp(18f).toInt()
        setPadding(pad, dp(16f).toInt(), pad, dp(4f).toInt())
        customSelectionActionModeCallback = MenuHandler()
    }

    init {
        val hint = TextView(ctx).apply {
            setText(R.string.picker_hint)
            setTextColor(textColor and 0x00FFFFFF or 0x99000000.toInt())
            textSize = 13f
            setPadding(dp(18f).toInt(), 0, dp(18f).toInt(), 0)
        }
        val scroll = object : ScrollView(ctx) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxTextHeight, MeasureSpec.AT_MOST))
            }
        }.apply { addView(body) }
        val buttons = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(dp(8f).toInt(), 0, dp(8f).toInt(), dp(6f).toInt())
            addView(button(R.string.action_copy) { onCopy(selectedText()) })
            addView(button(R.string.action_share) { onShare(selectedText()) })
            addView(button(R.string.action_translate) { onTranslate(selectedText()) })
        }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(20f)
                setColor(if (dark) 0xFF303134.toInt() else Color.WHITE)
            }
            elevation = dp(8f)
            addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(hint)
            addView(buttons)
        }
        val margin = dp(12f).toInt()
        val root = FrameLayout(ctx).apply {
            setPadding(margin, margin, margin, margin)
            clipToPadding = false
            addView(card)
        }

        dialog.setContentView(root)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnDismissListener { onClosed() }
        dialog.window?.apply {
            setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND) // The overlay already dims.
            setLayout(MATCH_PARENT, WRAP_CONTENT)
            setGravity(if (atBottom) Gravity.BOTTOM else Gravity.TOP)
            attributes = attributes.apply { y = dp(36f).toInt() }
        }
    }

    fun show() = dialog.show()

    fun dismiss() = dialog.dismiss()

    /** The selection, or the whole text when nothing is selected. */
    private fun selectedText(): String {
        val s = min(body.selectionStart, body.selectionEnd)
        val e = max(body.selectionStart, body.selectionEnd)
        return if (s in 0..<e) text.substring(s, e) else text
    }

    /** Hands menu actions that would start an activity to Lasso, which can start it properly. */
    private inner class MenuHandler : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu) = true
        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
        override fun onDestroyActionMode(mode: ActionMode) = Unit

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            val intent = item.intent
            return when {
                // Translate and other apps' text actions.
                intent?.action == Intent.ACTION_PROCESS_TEXT -> {
                    onLaunch(
                        Intent(intent)
                            .putExtra(Intent.EXTRA_PROCESS_TEXT, selectedText())
                            .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
                    )
                    true
                }
                item.itemId == android.R.id.shareText -> {
                    onShare(selectedText())
                    true
                }
                else -> false
            }
        }
    }

    private fun button(textRes: Int, onClick: () -> Unit) = TextView(ctx).apply {
        setText(textRes)
        setTextColor(textColor)
        textSize = 15f
        val padH = dp(16f).toInt()
        val padV = dp(11f).toInt()
        setPadding(padH, padV, padH, padV)
        background = with(TypedValue()) {
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, this, true)
            ctx.getDrawable(resourceId)
        }
        setOnClickListener { onClick() }
    }
}
