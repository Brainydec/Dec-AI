package com.dec.ai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

/**
 * User-enabled bridge for visible Android UI inspection and automation.
 * Never reads password fields or types into secret fields.
 */
class AccessibilityControlService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
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
        val wanted = normalize(label)
        if (wanted.isBlank()) return false
        for (root in visibleRoots()) {
            val match = findTextNode(root, wanted) ?: continue
            var target: AccessibilityNodeInfo? = match
            while (target != null) {
                if (target.isVisibleToUser && target.isClickable && target.isEnabled) {
                    if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                }
                target = target.parent
            }
            if (match.isVisibleToUser && match.isEnabled &&
                match.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        return false
    }

    fun typeText(value: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return false
        if (!focused.isEditable || !focused.isEnabled || focused.isPassword) return false
        val sensitiveHint = listOf(
            focused.hintText?.toString().orEmpty(),
            focused.text?.toString().orEmpty(),
            focused.viewIdResourceName.orEmpty(),
            focused.contentDescription?.toString().orEmpty()
        ).joinToString(" ")
        if (Regex("(?i)password|passcode|pin|otp|one.?time.?code|cvv|security.?code").containsMatchIn(sensitiveHint)) return false
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                value.take(2000)
            )
        }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    /** Leaves the foreground app and returns to the Android launcher. */
    fun goHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    fun readScreen(): String {
        val lines = linkedSetOf<String>()
        for (root in visibleRoots()) {
            collectText(root, lines, 0)
            if (lines.size >= 100) break
        }
        return lines.take(100).joinToString(". ").take(3500)
    }

    private fun visibleRoots(): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        runCatching {
            windows.forEach { window ->
                window.root?.let { roots += it }
            }
        }
        rootInActiveWindow?.let { activeRoot ->
            if (roots.none { it.windowId == activeRoot.windowId }) roots.add(0, activeRoot)
        }
        return roots
    }

    private fun normalize(value: String): String =
        value.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()

    private fun findTextNode(node: AccessibilityNodeInfo, wanted: String): AccessibilityNodeInfo? {
        if (!node.isVisibleToUser) return null
        val text = normalize(node.text?.toString().orEmpty())
        val description = normalize(node.contentDescription?.toString().orEmpty())
        if (text == wanted || description == wanted ||
            (wanted.length >= 3 && (text.contains(wanted) || description.contains(wanted)))) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findTextNode(child, wanted)?.let { return it }
        }
        return null
    }

    private fun findScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isVisibleToUser && node.isScrollable && node.isEnabled) return node
        for (i in 0 until node.childCount) {
            val found = node.getChild(i)?.let { findScrollable(it) }
            if (found != null) return found
        }
        return null
    }

    private fun collectText(
        node: AccessibilityNodeInfo,
        out: MutableSet<String>,
        depth: Int
    ) {
        if (depth > 35 || out.size >= 100 || !node.isVisibleToUser) return
        // Do not expose password or other explicitly secret controls to the assistant.
        if (node.isPassword) return
        val text = node.text?.toString()?.trim().orEmpty()
        val description = node.contentDescription?.toString()?.trim().orEmpty()
        if (text.isNotEmpty()) out += text
        if (description.isNotEmpty() && description != text) out += description
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectText(child, out, depth + 1)
            if (out.size >= 100) return
        }
    }

    companion object {
        @Volatile private var active: AccessibilityControlService? = null

        fun isConnected(): Boolean = active != null
        fun scrollVisible(direction: String) = active?.scroll(direction) ?: false
        fun tapVisibleText(label: String) = active?.tapText(label) ?: false
        fun typeIntoFocusedField(value: String) = active?.typeText(value) ?: false
        fun back() = active?.pressBack() ?: false
        fun home() = active?.goHome() ?: false
        fun visibleText() = active?.readScreen().orEmpty()
    }
}
