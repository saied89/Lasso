package dev.saeed.lasso

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.os.BundleCompat

/**
 * Reads visible text from the accessibility node tree of every app window on screen.
 * Makes blocking calls into other apps, so run it off the main thread.
 */
object TextExtractor {
    private const val MAX_NODES_PER_WINDOW = 4000
    private const val MAX_CHARS_WITH_BOXES = 5000
    private val SKIPPED_PACKAGES = setOf("com.android.systemui")

    private class Found(val node: AccessibilityNodeInfo, val text: String, val bounds: Rect)

    class Result(
        val blocks: List<TextBlock>,
        /** Packages the text came from. */
        val packages: Set<String>,
        /** Screen area showing apps, without system bars, keyboard or panels on top. */
        val appArea: Region,
    )

    fun extract(service: AccessibilityService): Result {
        val all = service.windows
        // Topmost window first, so we know which areas are covered by windows above.
        val windows = all
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedByDescending { it.layer }

        val appArea = Region()
        for (window in windows) {
            if (window.root?.packageName?.toString() in SKIPPED_PACKAGES) continue
            appArea.op(Rect().also(window::getBoundsInScreen), Region.Op.UNION)
        }
        for (window in all) {
            if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION ||
                window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
            ) continue
            appArea.op(Rect().also(window::getBoundsInScreen), Region.Op.DIFFERENCE)
        }

        val covered = Region()
        val perWindow = mutableListOf<List<TextBlock>>()
        val packages = mutableSetOf<String>()

        for (window in windows) {
            val root = window.root ?: continue
            val pkg = root.packageName?.toString()
            if (pkg == null || pkg in SKIPPED_PACKAGES) continue

            val windowBounds = Rect().also(window::getBoundsInScreen)
            val found = collect(root, windowBounds)
                .filterNot { covered.contains(it.bounds.centerX(), it.bounds.centerY()) }
            val blocks = dedupe(found).mapNotNull(::toBlock)
            if (blocks.isNotEmpty()) {
                perWindow += blocks
                packages += pkg
            }
            covered.op(windowBounds, Region.Op.UNION)
        }
        // Bottom window first, so the reading order follows the stacking order.
        return Result(perWindow.asReversed().flatten(), packages, appArea)
    }

    /**
     * Combines accessibility text with text recognized in a screenshot. Accessibility text
     * is exact, so it wins wherever the app reports character positions. Recognized text
     * fills in the rest: blocks without positions (e.g. newer WebViews), images, games.
     */
    fun merge(nodes: List<TextBlock>, ocr: List<TextBlock>, appArea: Region): List<TextBlock> {
        val slop = 6f
        fun RectF.hasCenterOf(r: Rect) = r.exactCenterX() in (left - slop)..(right + slop) &&
            r.exactCenterY() in (top - slop)..(bottom + slop)

        val exact = nodes.filter { it.hasCharBoxes }
        val exactLines = exact.flatMap { it.lineBoxes }
        val recognized = ocr.filter { line ->
            appArea.contains(line.bounds.centerX(), line.bounds.centerY()) &&
                exactLines.none { it.hasCenterOf(line.bounds) }
        }
        // A block without positions is replaced by the recognized lines inside it, if any.
        val kept = nodes.filter { node ->
            node.hasCharBoxes || recognized.none { node.bounds.contains(it.bounds.centerX(), it.bounds.centerY()) }
        }
        // Keep the app's reading order; slot recognized lines in by position.
        val result = kept.toMutableList()
        for (line in recognized.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))) {
            val at = result.indexOfFirst { it.bounds.top > line.bounds.centerY() }
            if (at < 0) result += line else result.add(at, line)
        }
        return result
    }

    /** Depth-first walk in tree order, which usually matches reading order. */
    private fun collect(root: AccessibilityNodeInfo, windowBounds: Rect): List<Found> {
        val result = mutableListOf<Found>()
        val stack = ArrayDeque<AccessibilityNodeInfo>().apply { addLast(root) }
        var visited = 0
        while (stack.isNotEmpty() && visited++ < MAX_NODES_PER_WINDOW) {
            val node = stack.removeLast()
            if (!node.isVisibleToUser) continue

            val text = textOf(node)
            if (text != null) {
                val bounds = Rect().also(node::getBoundsInScreen)
                if (bounds.intersect(windowBounds) && !bounds.isEmpty) result += Found(node, text, bounds)
            }
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let(stack::addLast)
        }
        return result
    }

    private fun textOf(node: AccessibilityNodeInfo): String? {
        if (node.isPassword) return null
        val className = node.className?.toString().orEmpty()
        // A WebView's own text is the page title; an image's is its alt text or file name.
        if (className == "android.webkit.WebView" || className.contains("Image")) return null
        val text = node.text?.toString()
        if (!text.isNullOrBlank()) return text
        // Some frameworks put label text in the description. Skip icons and buttons,
        // whose descriptions ("Back", "More options") aren't visible text.
        val desc = node.contentDescription?.toString()
        val looksLikeLabel = node.childCount == 0 && !node.isClickable && !node.isLongClickable &&
            !className.contains("Image") && !className.contains("Button")
        return if (looksLikeLabel && desc != null && desc.trim().length >= 2) desc else null
    }

    /** Drops a node when a node inside it shows the same text (e.g. a link and its label). */
    private fun dedupe(found: List<Found>): List<Found> {
        val byText = found.groupBy { it.text.trim() }
        return found.filterNot { outer ->
            byText[outer.text.trim()].orEmpty().any { inner ->
                inner !== outer && outer.bounds.contains(inner.bounds) && inner.bounds != outer.bounds
            }
        }
    }

    /** Null when the text isn't shown as words (e.g. an icon font). */
    private fun toBlock(found: Found): TextBlock? {
        if (ICON_NAME.matches(found.text.trim())) return null
        var boxes = charBoxes(found)
        // Newer WebViews report the node's whole box for every character. Without real
        // positions, the block is selected as a whole.
        if (boxes != null && boxes.size > 1 && boxes.filterNotNull().distinct().size == 1) boxes = null
        return TextBlock(found.text, found.bounds, boxes)
    }

    /** Material-style icon font names, like "arrow_back" or "ol_info", which are drawn as icons. */
    private val ICON_NAME = Regex("[a-z][a-z0-9]*(_[a-z0-9]+)+")

    /** Asks the app for the position of each character. Null if unsupported. */
    private fun charBoxes(found: Found): Array<RectF?>? {
        val node = found.node
        val key = AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY
        val length = found.text.length
        if (length > MAX_CHARS_WITH_BOXES || key !in node.availableExtraData) return null
        // Character positions refer to node.text, so a description-only node can't use them.
        if (node.text?.toString() != found.text) return null

        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, 0)
            putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, length)
        }
        if (!node.refreshWithExtraData(key, args)) return null
        if (node.text?.toString() != found.text) return null // Text changed meanwhile.

        val boxes = BundleCompat.getParcelableArray(node.extras, key, RectF::class.java) ?: return null
        if (boxes.size != length || boxes.none { it != null }) return null
        return Array(length) { boxes[it] as RectF? }
    }
}
