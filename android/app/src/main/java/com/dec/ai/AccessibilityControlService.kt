package com.dec.ai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Bundle
import android.graphics.Path
import android.graphics.Bitmap
import android.view.Display
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import android.graphics.Rect
import kotlinx.coroutines.delay
import android.accessibilityservice.GestureDescription
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
        val roots = visibleRoots()
        if (roots.isEmpty()) return false
        val action = if (direction.equals("up", true)) {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        }
        // Try every visible scroll container: some apps expose nested lists,
        // where the first scrollable parent may reject the action.
        for (root in roots) {
            val candidates = mutableListOf<AccessibilityNodeInfo>()
            collectScrollable(root, candidates, 0)
            for (node in candidates) {
                if (node.performAction(action)) return true
            }
        }

        // Fallback for apps whose accessibility tree does not expose a scroll
        // action but still permits standard touch gestures.
        val bounds = Rect()
        val root = roots.firstOrNull() ?: return false
        root.getBoundsInScreen(bounds)
        if (bounds.width() < 80 || bounds.height() < 160) return false
        val x = bounds.centerX().toFloat()
        val startY = if (direction.equals("up", true)) bounds.bottom * 0.78f else bounds.top + bounds.height() * 0.22f
        val endY = if (direction.equals("up", true)) bounds.top + bounds.height() * 0.22f else bounds.bottom * 0.78f
        val path = Path().apply { moveTo(x, startY); lineTo(x, endY) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 420))
            .build()
        return dispatchGesture(gesture, null, null)
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
    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun openRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun foregroundPackage(): String? = rootInActiveWindow?.packageName?.toString()
    fun appLabel(packageName: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    /** Best-effort dismissal of a matching app card from Android Recents; this is not force-stop. */
    suspend fun dismissRecentsCard(label: String): Boolean {
        if (!openRecents()) return false
        delay(900)
        val root = rootInActiveWindow
        val hits = runCatching { root?.findAccessibilityNodeInfosByText(label) }.getOrNull().orEmpty()
        var bounds: Rect? = null
        val screenHeight = resources.displayMetrics.heightPixels
        for (hit in hits) {
            var node: AccessibilityNodeInfo? = hit
            var depth = 0
            val rect = Rect()
            while (node != null && depth < 8) {
                node.getBoundsInScreen(rect)
                if (rect.height() >= screenHeight * 0.3f) { bounds = Rect(rect); break }
                node = node.parent
                depth++
            }
            if (bounds != null) break
        }
        val r = bounds
        val dismissed = if (r == null) false else performSwipe(r.exactCenterX(), r.exactCenterY(), r.exactCenterX(), (r.top - r.height() * 0.45f).coerceAtLeast(4f), 220)
        delay(350)
        pressHome()
        return dismissed
    }

    /** Clears Recents only when Android exposes a recognizable Clear all control. */
    suspend fun clearRecents(): Boolean {
        if (!openRecents()) return false
        delay(900)
        val root = rootInActiveWindow ?: run { pressHome(); return false }
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var target: AccessibilityNodeInfo? = null
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 500 && target == null) {
            val node = queue.removeFirst()
            val text = (node.text?.toString() ?: node.contentDescription?.toString() ?: "").trim().lowercase()
            val viewId = node.viewIdResourceName?.lowercase().orEmpty()
            if (viewId.contains("clear") || text == "clear all" || text == "close all" || text == "dismiss all") target = node
            else for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
        }
        val success = target?.let { clickNode(it) } ?: false
        delay(350)
        pressHome()
        return success
    }

    private suspend fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth++ < 6) {
            if (current.isClickable && current.isEnabled && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            current = current.parent
        }
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return if (rect.isEmpty) false else performTap(rect.exactCenterX(), rect.exactCenterY())
    }

    private suspend fun performTap(x: Float, y: Float): Boolean = performGesture(Path().apply { moveTo(x, y) }, 70)
    private suspend fun performSwipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long): Boolean =
        performGesture(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, duration)

    private suspend fun performGesture(path: Path, duration: Long): Boolean = suspendCancellableCoroutine { continuation ->
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build()
        val sent = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(description: GestureDescription?) { if (continuation.isActive) continuation.resume(true) }
            override fun onCancelled(description: GestureDescription?) { if (continuation.isActive) continuation.resume(false) }
        }, null)
        if (!sent && continuation.isActive) continuation.resume(false)
    }
    /** Leaves the foreground app and returns to the Android launcher. */
    fun goHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    /**
     * Adds on-device OCR from a real display capture to the accessibility tree.
     * Android requires API 30+; on older devices the accessibility tree remains available.
     */
    suspend fun visualText(): String {
        if (android.os.Build.VERSION.SDK_INT < 30) return ""
        return suspendCancellableCoroutine { continuation ->
            try {
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(result: ScreenshotResult) {
                            val bitmap = try {
                                Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                                    ?.copy(Bitmap.Config.ARGB_8888, false)
                            } catch (_: Exception) { null }
                            result.hardwareBuffer.close()
                            if (bitmap == null) {
                                if (continuation.isActive) continuation.resume("")
                                return
                            }
                            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                                .addOnSuccessListener { text ->
                                    if (continuation.isActive) continuation.resume(
                                        text.textBlocks.joinToString(". ") { it.text }.take(3500)
                                    )
                                }
                                .addOnFailureListener {
                                    if (continuation.isActive) continuation.resume("")
                                }
                                .addOnCompleteListener {
                                    bitmap.recycle()
                                    recognizer.close()
                                }
                        }

                        override fun onFailure(errorCode: Int) {
                            if (continuation.isActive) continuation.resume("")
                        }
                    })
            } catch (_: Exception) {
                if (continuation.isActive) continuation.resume("")
            }
        }
    }

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

    private fun collectScrollable(
        node: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>,
        depth: Int
    ) {
        if (depth > 35 || out.size >= 40 || !node.isVisibleToUser) return
        if (node.isScrollable && node.isEnabled) out += node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectScrollable(child, out, depth + 1)
            if (out.size >= 40) return
        }
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
        fun home() = active?.pressHome() ?: false
        fun back() = active?.pressBack() ?: false
        fun recents() = active?.openRecents() ?: false
        fun foregroundPackage() = active?.foregroundPackage()
        fun appLabel(packageName: String) = active?.appLabel(packageName) ?: packageName
        suspend fun dismissRecentsCard(label: String) = active?.dismissRecentsCard(label) ?: false
        suspend fun clearRecents() = active?.clearRecents() ?: false
        fun scrollVisible(direction: String) = active?.scroll(direction) ?: false
        fun tapVisibleText(label: String) = active?.tapText(label) ?: false
        fun typeIntoFocusedField(value: String) = active?.typeText(value) ?: false
        fun visibleText() = active?.readScreen().orEmpty()
        suspend fun visualText() = active?.visualText().orEmpty()
    }
}
