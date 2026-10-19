package com.phonemcp.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.phonemcp.voice.VoiceButtonMemory
import com.phonemcp.voice.VoiceSession
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The accessibility service that actually reads the screen and performs
 * gestures. Holds a static reference to itself so the MCP tools can reach it.
 */
class ControllerAccessibilityService : AccessibilityService() {

    @Volatile var currentPackage: String? = null
        private set

    private val main = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                event.packageName?.let { currentPackage = it.toString() }
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                handleLearningClick(event)
            }
        }
    }

    /** Learning mode: the user taps the voice button once, we record its signature. */
    private fun handleLearningClick(event: AccessibilityEvent) {
        if (!VoiceButtonMemory.isLearning(this)) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg != VoiceSession.CLAUDE_PACKAGE) return
        val src = event.source ?: return
        val r = Rect()
        src.getBoundsInScreen(r)
        val sig = VoiceButtonMemory.Signature(
            viewId = src.viewIdResourceName,
            text = src.text?.toString()?.ifBlank { null },
            desc = src.contentDescription?.toString()?.ifBlank { null },
            cx = r.centerX(),
            cy = r.centerY()
        )
        VoiceButtonMemory.save(this, sig)
    }

    /** True if the current screen contains any of the given (lower-cased) substrings. */
    fun screenContainsAny(keywordsLower: List<String>): Boolean {
        val root = rootInActiveWindow ?: return false
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        var guard = 0
        while (stack.isNotEmpty() && guard < 3000) {
            guard++
            val n = stack.removeLast()
            val t = (n.text?.toString().orEmpty() + " " + n.contentDescription?.toString().orEmpty()).lowercase()
            if (t.isNotBlank() && keywordsLower.any { t.contains(it) }) return true
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
        }
        return false
    }

    /** Try to find and tap Claude's voice button using a learned signature or defaults. */
    fun findAndTapVoiceButton(): Boolean {
        val root = rootInActiveWindow ?: return false

        VoiceButtonMemory.get(this)?.let { sig ->
            // 1) by resource id
            sig.viewId?.let { id ->
                root.findAccessibilityNodeInfosByViewId(id).firstOrNull()?.let {
                    if (clickNode(it)) return true
                }
            }
            // 2) by text
            sig.text?.let { t ->
                root.findAccessibilityNodeInfosByText(t).firstOrNull()?.let {
                    if (clickNode(it)) return true
                }
            }
            // 3) by content description
            sig.desc?.let { d ->
                findByDesc(root, d)?.let { if (clickNode(it)) return true }
            }
            // 4) learned coordinates
            if (sig.cx >= 0 && sig.cy >= 0) {
                if (tap(sig.cx, sig.cy)) return true
            }
        }

        // No learned signature matched — try common labels.
        for (label in VoiceButtonMemory.CANDIDATE_LABELS) {
            root.findAccessibilityNodeInfosByText(label).firstOrNull()?.let {
                if (clickNode(it)) return true
            }
            findByDesc(root, label)?.let { if (clickNode(it)) return true }
        }
        return false
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        var p = node.parent
        var depth = 0
        while (p != null && depth < 8) {
            if (p.isClickable && p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            p = p.parent; depth++
        }
        val r = Rect()
        node.getBoundsInScreen(r)
        return tap(r.centerX(), r.centerY())
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    // --- Screen reading ---------------------------------------------------

    fun activePackage(): String? {
        rootInActiveWindow?.packageName?.let { return it.toString() }
        return currentPackage
    }

    fun dumpScreen(maxNodes: Int = 500): JSONObject {
        val root = rootInActiveWindow
        val out = JSONObject()
        out.put("package", activePackage() ?: JSONObject.NULL)
        val counter = intArrayOf(0)
        out.put("tree", if (root == null) JSONObject.NULL else serialize(root, counter, maxNodes))
        out.put("truncated", counter[0] >= maxNodes)
        return out
    }

    private fun serialize(node: AccessibilityNodeInfo, counter: IntArray, max: Int): JSONObject {
        val o = JSONObject()
        counter[0]++
        node.className?.let { o.put("class", shortClass(it.toString())) }
        node.text?.let { if (it.isNotEmpty()) o.put("text", it.toString()) }
        node.contentDescription?.let { if (it.isNotEmpty()) o.put("desc", it.toString()) }
        node.viewIdResourceName?.let { o.put("id", it) }
        if (node.isClickable) o.put("clickable", true)
        if (node.isLongClickable) o.put("longClickable", true)
        if (node.isScrollable) o.put("scrollable", true)
        if (node.isEditable) o.put("editable", true)
        if (node.isCheckable) o.put("checked", node.isChecked)
        val r = Rect()
        node.getBoundsInScreen(r)
        o.put("bounds", JSONArray(listOf(r.left, r.top, r.right, r.bottom)))
        o.put("cx", r.centerX())
        o.put("cy", r.centerY())

        if (counter[0] < max && node.childCount > 0) {
            val children = JSONArray()
            for (i in 0 until node.childCount) {
                if (counter[0] >= max) break
                val child = node.getChild(i) ?: continue
                children.put(serialize(child, counter, max))
            }
            if (children.length() > 0) o.put("children", children)
        }
        return o
    }

    private fun shortClass(c: String): String = c.substringAfterLast('.')

    // --- Screenshot (API 30+) --------------------------------------------

    fun takeScreenshotBase64(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return ScreenshotHelper.capture(this)
    }

    // --- Gestures ---------------------------------------------------------

    fun tap(x: Int, y: Int, durationMs: Long = 50): Boolean =
        dispatchPath(buildPath(x, y), durationMs)

    fun longTap(x: Int, y: Int, durationMs: Long = 700): Boolean =
        dispatchPath(buildPath(x, y), durationMs)

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        return dispatchPath(path, durationMs)
    }

    /** direction: "up"|"down"|"left"|"right" — scrolls the content in that direction. */
    fun scroll(direction: String): Boolean {
        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        val cx = w / 2
        val cy = h / 2
        val dx = (w * 0.35).toInt()
        val dy = (h * 0.35).toInt()
        // Swiping up scrolls content down, etc. We swipe opposite to the requested
        // content movement so "down" reveals content further down.
        return when (direction.lowercase()) {
            "down" -> swipe(cx, cy + dy, cx, cy - dy, 300)
            "up" -> swipe(cx, cy - dy, cx, cy + dy, 300)
            "left" -> swipe(cx + dx, cy, cx - dx, cy, 300)
            "right" -> swipe(cx - dx, cy, cx + dx, cy, 300)
            else -> false
        }
    }

    private fun buildPath(x: Int, y: Int): Path =
        Path().apply { moveTo(x.toFloat(), y.toFloat()); lineTo(x.toFloat(), y.toFloat()) }

    private fun dispatchPath(path: Path, durationMs: Long): Boolean {
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val latch = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        main.post {
            val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { ok.set(true); latch.countDown() }
                override fun onCancelled(g: GestureDescription?) { latch.countDown() }
            }, null)
            if (!dispatched) latch.countDown()
        }
        latch.await(10, TimeUnit.SECONDS)
        return ok.get()
    }

    // --- Global actions ---------------------------------------------------

    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun recents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    // --- Element-targeted actions -----------------------------------------

    fun tapElement(selector: String): Boolean {
        val node = findNode(selector) ?: return false
        if (node.isClickable) {
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        // Walk up to a clickable ancestor.
        var p = node.parent
        var depth = 0
        while (p != null && depth < 8) {
            if (p.isClickable && p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            p = p.parent
            depth++
        }
        // Fall back to tapping the element's centre.
        val r = Rect()
        node.getBoundsInScreen(r)
        return tap(r.centerX(), r.centerY())
    }

    fun typeText(text: String): Boolean {
        val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findFirstEditable(rootInActiveWindow)
            ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val found = findFirstEditable(node.getChild(i))
            if (found != null) return found
        }
        return null
    }

    /** selector can be a fully-qualified view id ("pkg:id/name") or visible text. */
    private fun findNode(selector: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        if (selector.contains(":id/")) {
            root.findAccessibilityNodeInfosByViewId(selector).firstOrNull()?.let { return it }
        }
        root.findAccessibilityNodeInfosByText(selector).firstOrNull()?.let { return it }
        // Exact match on contentDescription as a last resort.
        return findByDesc(root, selector)
    }

    /** Visible text + contentDescription of the smallest node containing (x,y). */
    fun nodeTextAt(x: Int, y: Int): String? {
        val root = rootInActiveWindow ?: return null
        var best: AccessibilityNodeInfo? = null
        var bestArea = Int.MAX_VALUE
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        val r = Rect()
        var guard = 0
        while (stack.isNotEmpty() && guard < 4000) {
            guard++
            val n = stack.removeLast()
            n.getBoundsInScreen(r)
            if (r.contains(x, y)) {
                val area = r.width() * r.height()
                val label = (n.text?.toString().orEmpty() + " " + n.contentDescription?.toString().orEmpty()).trim()
                if (label.isNotEmpty() && area < bestArea) {
                    best = n
                    bestArea = area
                }
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { stack.add(it) }
            }
        }
        best ?: return null
        return (best.text?.toString().orEmpty() + " " + best.contentDescription?.toString().orEmpty()).trim()
    }

    private fun findByDesc(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.contentDescription?.toString() == text) return node
        for (i in 0 until node.childCount) {
            val f = findByDesc(node.getChild(i), text)
            if (f != null) return f
        }
        return null
    }

    companion object {
        @Volatile
        var instance: ControllerAccessibilityService? = null
            private set
    }
}
