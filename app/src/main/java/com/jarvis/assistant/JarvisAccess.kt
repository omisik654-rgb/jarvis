package com.jarvis.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class JarvisAccess : AccessibilityService() {

    companion object {
        @Volatile
        var instance: JarvisAccess? = null
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun global(action: String): Boolean {
        val a = when (action) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            else -> return false
        }
        return performGlobalAction(a)
    }

    fun readScreen(): String {
        val root = rootInActiveWindow ?: return "Ekran okunamadı"
        val sb = StringBuilder()
        collect(root, sb, 0)
        return sb.toString().take(1500)
    }

    private fun collect(n: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int) {
        if (n == null || sb.length > 1500 || depth > 25) return
        val t = n.text?.toString() ?: n.contentDescription?.toString()
        if (!t.isNullOrBlank()) sb.append(t).append(". ")
        for (i in 0 until n.childCount) collect(n.getChild(i), sb, depth + 1)
    }

    fun clickText(vararg labels: String): Boolean {
        val root = rootInActiveWindow ?: return false
        for (l in labels) {
            val nodes = root.findAccessibilityNodeInfosByText(l)
            if (nodes != null) {
                for (n in nodes) {
                    var c: AccessibilityNodeInfo? = n
                    while (c != null && !c.isClickable) c = c.parent
                    if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                }
            }
        }
        return clickDesc(root, labels.map { it.lowercase() }, 0)
    }

    private fun clickDesc(n: AccessibilityNodeInfo?, labels: List<String>, d: Int): Boolean {
        if (n == null || d > 25) return false
        val cd = n.contentDescription?.toString()?.lowercase()
        if (cd != null && labels.any { cd.contains(it) }) {
            var c: AccessibilityNodeInfo? = n
            while (c != null && !c.isClickable) c = c.parent
            if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        for (i in 0 until n.childCount) {
            if (clickDesc(n.getChild(i), labels, d + 1)) return true
        }
        return false
    }

    fun scroll(forward: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val n = findScrollable(root, 0) ?: return false
        return n.performAction(
            if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        )
    }

    private fun findScrollable(n: AccessibilityNodeInfo?, d: Int): AccessibilityNodeInfo? {
        if (n == null || d > 25) return null
        if (n.isScrollable) return n
        for (i in 0 until n.childCount) {
            val r = findScrollable(n.getChild(i), d + 1)
            if (r != null) return r
        }
        return null
    }

    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val b = Bundle()
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
    }

    fun tap(xf: Float, yf: Float): Boolean {
        val dm = resources.displayMetrics
        val p = Path()
        p.moveTo(dm.widthPixels * xf, dm.heightPixels * yf)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 80))
            .build()
        return dispatchGesture(g, null, null)
    }
}
