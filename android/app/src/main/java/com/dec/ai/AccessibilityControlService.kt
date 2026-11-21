package com.dec.ai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * User-enabled bridge for basic, visible UI automation.
 * It only acts on the currently visible accessibility tree.
 */
class AccessibilityControlService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
        active = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (active === this) active = null
        super.onDestroy()
    }

    fun scroll(direction: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val action = if (direction.equals("up", true)) {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        }
        return findScrollable(root)?.performAction(action) == true
    }

    fun tapText(label: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = root.findAccessibilityNodeInfosByText(label)
            ?.firstOrNull { it.isVisibleToUser }
            ?: return false
        var target: AccessibilityNodeInfo? = node
        while (target != null) {
            if (target.isClickable && target.isEnabled) {
                return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            target = target.parent
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    fun typeText(value: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        if (!focused.isEditable || !focused.isEnabled) return false
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                value.take(2000)
            )
        }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun readScreen(): String {
        val root = rootInActiveWindow ?: return ""
        val lines = mutableListOf<String>()
        collectText(root, lines)
        return lines.distinct().take(60).joinToString(". ").take(1800)
    }

    private fun findScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable && node.isEnabled) return node
        for (i in 0 until node.childCount) {
            val found = node.getChild(i)?.let { findScrollable(it) }
            if (found != null) return found
        }
        return null
    }

    private fun collectText(node: AccessibilityNodeInfo, out: MutableList<String>) {
        val text = node.text?.toString()?.trim().orEmpty()
        val description = node.contentDescription?.toString()?.trim().orEmpty()
        if (text.isNotEmpty()) out += text
        if (description.isNotEmpty() && description != text) out += description
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectText(it, out) }
            if (out.size >= 80) return
        }
    }

    companion object {
        @Volatile private var active: AccessibilityControlService? = null

        fun isConnected(): Boolean = active != null
        fun scrollVisible(direction: String) = active?.scroll(direction) ?: false
        fun tapVisibleText(label: String) = active?.tapText(label) ?: false
        fun typeIntoFocusedField(value: String) = active?.typeText(value) ?: false
        fun back() = active?.pressBack() ?: false
        fun visibleText() = active?.readScreen().orEmpty()
    }
}
