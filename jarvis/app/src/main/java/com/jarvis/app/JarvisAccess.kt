package com.jarvis.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Jarvis's "hands": presses system buttons, taps on-screen buttons by their text, types and scrolls,
 * and reads the screen as a list of elements for hands mode. Turned on by the user in
 * Settings → Accessibility → Jarvis. Without it everything except screen control still works.
 */
class JarvisAccess : AccessibilityService() {

    companion object {
        @Volatile var instance: JarvisAccess? = null
            private set
        val enabled get() = instance != null
    }

    /** One element of the screen as the model sees it. */
    class El(val n: Int, val kind: String, val label: String, val node: AccessibilityNodeInfo, val rect: Rect)

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: android.content.Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun global(what: String): Boolean = performGlobalAction(when (what) {
        "back" -> GLOBAL_ACTION_BACK
        "home" -> GLOBAL_ACTION_HOME
        "recents" -> GLOBAL_ACTION_RECENTS
        "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
        "lock" -> if (Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_LOCK_SCREEN else return false
        "screenshot" -> if (Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_TAKE_SCREENSHOT else return false
        else -> return false
    })

    private fun roots(): List<AccessibilityNodeInfo> {
        val list = windows.mapNotNull { it.root }.toMutableList()
        if (list.isEmpty()) rootInActiveWindow?.let { list.add(it) }
        // the app window first, system bars and our own windows last
        return list.sortedBy { if (it.packageName == packageName) 2 else if (it.packageName == "com.android.systemui") 1 else 0 }
    }

    private fun walk(n: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>, depth: Int = 0) {
        if (n == null || depth > 40 || out.size > 600) return
        out.add(n)
        for (i in 0 until n.childCount) walk(n.getChild(i), out, depth + 1)
    }

    private fun all(): List<AccessibilityNodeInfo> = mutableListOf<AccessibilityNodeInfo>().also { l -> roots().forEach { walk(it, l) } }

    private fun textOf(n: AccessibilityNodeInfo): String =
        (n.text?.toString()?.takeIf { it.isNotBlank() } ?: n.contentDescription?.toString() ?: n.hintText?.toString() ?: "")
            .replace(Regex("\\s+"), " ").trim()

    private fun clickableOf(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var c: AccessibilityNodeInfo? = n
        var d = 0
        while (c != null && d < 6) { if (c.isClickable) return c; c = c.parent; d++ }
        return null
    }

    private fun tapAt(r: Rect): Boolean {
        val p = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        return dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 60)).build(), null, null)
    }

    fun click(node: AccessibilityNodeInfo): Boolean {
        clickableOf(node)?.let { if (it.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true }
        val r = Rect(); node.getBoundsInScreen(r)
        return !r.isEmpty && tapAt(r)
    }

    /** Taps the element whose text best matches: exact, then starts-with, then contains. */
    fun tapText(text: String): Boolean {
        val want = text.lowercase().trim()
        if (want.isEmpty()) return false
        val nodes = all().filter { it.isVisibleToUser && textOf(it).isNotEmpty() }
        val best = nodes.firstOrNull { textOf(it).lowercase() == want }
            ?: nodes.firstOrNull { textOf(it).lowercase().startsWith(want) }
            ?: nodes.firstOrNull { textOf(it).lowercase().contains(want) }
            ?: nodes.firstOrNull { n -> val t = textOf(n).lowercase(); want.split(' ').all { it in t } }
            ?: return false
        return click(best)
    }

    fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val b = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
    }

    /** Types into the focused field, or into the first text field on screen. */
    fun type(text: String): Boolean {
        val focused = roots().firstNotNullOfOrNull { it.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }
        val field = focused?.takeIf { it.isEditable } ?: all().firstOrNull { it.isEditable && it.isVisibleToUser } ?: return false
        return setText(field, text)
    }

    fun scroll(dir: String): Boolean {
        val action = if (dir == "up") AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        val s = all().filter { it.isScrollable && it.isVisibleToUser }
            .maxByOrNull { val r = Rect(); it.getBoundsInScreen(r); r.width() * r.height() }
        if (s != null && s.performAction(action)) return true
        // no scrollable view reported: swipe in the middle of the screen
        val dm = resources.displayMetrics
        val x = dm.widthPixels / 2f; val y1 = dm.heightPixels * 0.7f; val y2 = dm.heightPixels * 0.3f
        val p = Path().apply { if (dir == "up") { moveTo(x, y2); lineTo(x, y1) } else { moveTo(x, y1); lineTo(x, y2) } }
        return dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 300)).build(), null, null)
    }

    fun currentApp(): String = roots().firstOrNull()?.packageName?.toString() ?: ""

    /** The screen as numbered elements: what can be tapped, typed into or read. Our own window is skipped. */
    fun snapshot(limit: Int = 40): List<El> {
        val out = mutableListOf<El>()
        val seen = HashSet<String>()
        for (n in all()) {
            if (out.size >= limit) break
            if (!n.isVisibleToUser || n.packageName == packageName) continue
            val t = textOf(n).take(60)
            val kind = when {
                n.isEditable -> "поле"
                n.isCheckable -> if (n.isChecked) "переключатель вкл" else "переключатель выкл"
                n.isClickable -> "кнопка"
                t.isNotEmpty() && clickableOf(n) != null -> "кнопка"
                t.isNotEmpty() -> "текст"
                else -> continue
            }
            if (t.isEmpty() && kind == "кнопка") continue          // a nameless button means nothing to the model
            val r = Rect(); n.getBoundsInScreen(r)
            if (r.isEmpty) continue
            val key = "$kind|$t|${r.centerX() / 20}|${r.centerY() / 20}"
            if (!seen.add(key)) continue
            out.add(El(out.size + 1, kind, t, n, r))
        }
        return out
    }
}
