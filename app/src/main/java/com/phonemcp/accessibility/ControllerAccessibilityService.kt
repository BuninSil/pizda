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

    @Volatile var lastWindowEventAt: Long = 0L
        private set

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                event.packageName?.let { currentPackage = it.toString() }
                lastWindowEventAt = System.currentTimeMillis()
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                lastWindowEventAt = System.currentTimeMillis()
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                handleLearningClick(event)
            }
        }
    }

    /** Wait until window events go quiet (quietMs) or maxMs elapses. */
    fun waitForStable(maxMs: Long = 1500, quietMs: Long = 350) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < maxMs) {
            if (System.currentTimeMillis() - lastWindowEventAt > quietMs) return
            try { Thread.sleep(50) } catch (_: InterruptedException) { return }
        }
    }

    /** Snapshot of visible texts/descriptions, for computing screen diffs. */
    fun visibleTexts(limit: Int = 250): Set<String> {
        val root = rootInActiveWindow ?: return emptySet()
        val set = LinkedHashSet<String>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        var guard = 0
        while (stack.isNotEmpty() && set.size < limit && guard < 4000) {
            guard++
            val n = stack.removeFirst()
            if (n.isVisibleToUser) {
                n.text?.toString()?.ifBlank { null }?.let { set.add(it) }
                n.contentDescription?.toString()?.ifBlank { null }?.let { set.add(it) }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
        }
        return set
    }

    /** Best-effort title of the active window. */
    fun windowTitle(): String? {
        return try {
            windows.firstOrNull { it.isActive }?.title?.toString()?.ifBlank { null }
        } catch (e: Exception) {
            null
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

    /** One collected, interactable/text-bearing element with a stable index. */
    class Element(
        val node: AccessibilityNodeInfo,
        val text: String?,
        val desc: String?,
        val id: String?,
        val clickable: Boolean,
        val editable: Boolean,
        val checkable: Boolean,
        val checked: Boolean,
        val cx: Int,
        val cy: Int
    )

    /**
     * Recursively collect the screen's meaningful elements in a stable
     * (pre-order) sequence. Clickable containers with no own text get their
     * children's text merged in, so list rows (Telegram chats, forum topics…)
     * show up. include_all also emits non-clickable text nodes everywhere.
     */
    fun collectElements(includeAll: Boolean, maxNodes: Int = 250): List<Element> {
        val out = ArrayList<Element>()
        val root = rootInActiveWindow ?: return out
        collect(root, includeAll, false, out, maxNodes)
        return out
    }

    private fun collect(
        node: AccessibilityNodeInfo,
        includeAll: Boolean,
        suppressText: Boolean,
        out: ArrayList<Element>,
        maxNodes: Int
    ) {
        if (out.size >= maxNodes) return
        val r = Rect()
        node.getBoundsInScreen(r)
        val visible = node.isVisibleToUser && r.width() > 0 && r.height() > 0
        val own = (node.text?.toString()?.ifBlank { null }) ?: node.contentDescription?.toString()?.ifBlank { null }
        val interactive = node.isClickable || node.isEditable || node.isCheckable
        var childSuppress = suppressText

        if (visible) {
            if (interactive) {
                val label = own ?: mergeChildText(node)
                out.add(
                    Element(
                        node,
                        node.text?.toString()?.ifBlank { null } ?: label,
                        node.contentDescription?.toString()?.ifBlank { null },
                        node.viewIdResourceName,
                        node.isClickable, node.isEditable, node.isCheckable, node.isChecked,
                        r.centerX(), r.centerY()
                    )
                )
                if (!label.isNullOrBlank()) childSuppress = true
            } else if (own != null && (includeAll || !suppressText)) {
                out.add(
                    Element(
                        node, node.text?.toString()?.ifBlank { null },
                        node.contentDescription?.toString()?.ifBlank { null },
                        node.viewIdResourceName, false, false, false, false,
                        r.centerX(), r.centerY()
                    )
                )
            }
        }
        for (i in 0 until node.childCount) {
            if (out.size >= maxNodes) break
            node.getChild(i)?.let { collect(it, includeAll, childSuppress, out, maxNodes) }
        }
    }

    /** Join all descendant text/desc of a node (bounded), for labelling containers. */
    private fun mergeChildText(node: AccessibilityNodeInfo): String? {
        val parts = ArrayList<String>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        for (i in 0 until node.childCount) node.getChild(i)?.let { stack.add(it) }
        var guard = 0
        while (stack.isNotEmpty() && parts.size < 12 && guard < 300) {
            guard++
            val n = stack.removeFirst()
            (n.text?.toString()?.ifBlank { null } ?: n.contentDescription?.toString()?.ifBlank { null })
                ?.let { parts.add(it) }
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
        }
        val joined = parts.joinToString(" | ").trim()
        return joined.ifBlank { null }?.take(200)
    }

    fun dumpScreenCompact(includeAll: Boolean = false): JSONObject {
        val out = JSONObject()
        out.put("package", activePackage() ?: JSONObject.NULL)
        val elements = JSONArray()
        collectElements(includeAll).forEachIndexed { i, e ->
            val o = JSONObject()
            o.put("i", i)
            e.text?.let { o.put("text", it) }
            e.desc?.let { o.put("desc", it) }
            e.id?.let { o.put("id", it) }
            if (e.clickable) o.put("clickable", true)
            if (e.editable) o.put("editable", true)
            if (e.checkable) o.put("checked", e.checked)
            o.put("cx", e.cx)
            o.put("cy", e.cy)
            elements.put(o)
        }
        out.put("elements", elements)
        out.put("count", elements.length())
        return out
    }

    /** tap_index: click the i-th element from collectElements (default collection). */
    fun tapIndex(i: Int): Boolean {
        val els = collectElements(false)
        val e = els.getOrNull(i) ?: return false
        if (clickNode(e.node)) return true
        return tap(e.cx, e.cy)
    }

    /** Polls for an element (view id or visible text) up to timeoutMs. */
    fun waitForElement(selector: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(0, 60000)
        while (System.currentTimeMillis() < deadline) {
            if (elementPresent(selector)) return true
            try { Thread.sleep(200) } catch (_: InterruptedException) { return false }
        }
        return elementPresent(selector)
    }

    /** Waits until the given package is the foreground app (or timeout). */
    fun waitForPackage(pkg: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(0, 60000)
        while (System.currentTimeMillis() < deadline) {
            if (activePackage() == pkg) return true
            try { Thread.sleep(200) } catch (_: InterruptedException) { return false }
        }
        return activePackage() == pkg
    }

    // --- Screenshot (API 30+) --------------------------------------------

    fun screenshotJpeg(maxWidth: Int = 720, quality: Int = 70): ByteArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return ScreenshotHelper.captureJpeg(this, maxWidth, quality)
    }

    /** Full-resolution bitmap for OCR (caller recycles). */
    fun screenshotBitmapFull(): android.graphics.Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return ScreenshotHelper.captureBitmapFull(this)
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

    private fun focusedEditable(): AccessibilityNodeInfo? =
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findFirstEditable(rootInActiveWindow)

    private fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** replace=false appends to the field's current text; replace=true overwrites it. */
    fun typeText(text: String, replace: Boolean): Boolean {
        val node = focusedEditable() ?: return false
        val newText = if (replace) text else (node.text?.toString() ?: "") + text
        return setText(node, newText)
    }

    fun clearText(): Boolean {
        val node = focusedEditable() ?: return false
        return setText(node, "")
    }

    fun backspace(): Boolean {
        val node = focusedEditable() ?: return false
        val cur = node.text?.toString() ?: ""
        if (cur.isEmpty()) return true
        return setText(node, cur.substring(0, cur.length - 1))
    }

    fun deleteForward(): Boolean {
        // Without a reliable cursor position, treat forward-delete like clearing
        // the last character; good enough for most fields.
        return backspace()
    }

    fun pressEnter(): Boolean {
        val node = focusedEditable() ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) return true
        }
        // Fallback: append a newline.
        val cur = node.text?.toString() ?: ""
        return setText(node, cur + "\n")
    }

    fun paste(): Boolean {
        val node = focusedEditable() ?: return false
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    /** True if a node matching the selector (view id or visible text) is present. */
    fun elementPresent(selector: String): Boolean = findNode(selector) != null

    // --- System panels ----------------------------------------------------

    fun isShadeOpen(): Boolean = activePackage() == "com.android.systemui"

    fun openNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    fun openQuickSettings(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS) else false

    fun closePanels(): Boolean {
        // GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE (API 31+) is the sanctioned way,
        // but on HyperOS the Control Center can linger — follow up with BACK.
        var ok = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ok = performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        }
        // BACK reliably collapses a stuck shade/control-center on MIUI/HyperOS.
        back()
        return ok || true
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
