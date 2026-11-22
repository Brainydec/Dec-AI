package com.dec.ai

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.SmsManager
import org.json.JSONObject
import kotlinx.coroutines.delay

/**
 * Executes phone actions. EVERY action passes through [guarded], which enforces, in order:
 * emergency stop -> per-tool switch -> offline switch -> Android runtime permission -> (Category B) explicit approval.
 */
class Executor(private val ctx: Context, private val confirm: suspend (String) -> Boolean) {
    private val prefs = Prefs.get(ctx)
    private var screenApproved = false
    private val sensitiveApp = Regex("(?i)bank|wallet|pay|upi|phonepe|paytm|authenticator|keepass|bitwarden|1password|lastpass|password|vault")
    private val riskyTap = Regex("(?i)\\b(pay|send|transfer|buy|purchase|order|checkout|delete|remove|erase|uninstall|reset|sign out|log ?out|confirm|submit|donate|subscribe|install)\\b")
    // Screen approval remains valid for this running DEC service session.
    // It resets only when Android restarts the service or the user stops DEC.
    fun newTask() = Unit
    private fun sensitiveScreen(): Boolean = AccessibilityControlService.foregroundPackage()?.let { sensitiveApp.containsMatchIn(it) } == true
    suspend fun takeObservation(): String {
        if (!requireAccessibility()) return ""
        delay(700)
        val tree = AccessibilityControlService.visibleText()
        val ocr = AccessibilityControlService.visualText()
        return listOf(tree, ocr).filter { it.isNotBlank() }.distinct().joinToString(". ").take(4500)
    }

    /** Returns text to speak ("" = success, nothing to say). */
    suspend fun run(toolId: String, args: JSONObject, online: Boolean): String {
        val tool = Tool.forAction(toolId) ?: return "I don't know how to do $toolId."
        val result = try {
            guarded(tool, toolId, args, online)
        } catch (e: SecurityException) {
            "Android blocked that action. A permission is missing."
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            "That didn't work: ${e.message}"
        }
        prefs.addLog(tool.label, args.toString().take(160), result.ifBlank { "done" }.take(120))
        return result
    }

    private fun hardPerms(t: Tool): List<String> = when (t) {
        Tool.CALL -> listOf(Manifest.permission.CALL_PHONE)
        Tool.ANSWER_CALL, Tool.REJECT_CALL -> listOf(Manifest.permission.ANSWER_PHONE_CALLS)
        Tool.SEND_SMS -> listOf(Manifest.permission.SEND_SMS)
        Tool.READ_SMS -> listOf(Manifest.permission.READ_SMS)
        else -> emptyList()
    }

    private suspend fun guarded(tool: Tool, id: String, a: JSONObject, online: Boolean): String {
        if (prefs.emergencyStop) return "Emergency stop is on. Resume DEC in the app first."
        if (!prefs.toolEnabled(tool)) return "${tool.label} is switched off in Permissions."
        if (!online && !prefs.offlineAllowed(tool)) return "${tool.label} is disabled for offline use."
        if (hardPerms(tool).any { ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED })
            return "I need the ${tool.label} permission. Open Permissions in the DEC app and tap Grant."
        return when (id) {
            "flashlight" -> flashlight(a.optBoolean("on", true))
            "volume" -> volume(a)
            "brightness" -> brightness(a.optInt("level", 50))
            "open_app" -> openApp(a.optString("name"))
            "accessibility_settings" -> accessibilitySettings()
            "scroll_screen" -> scrollScreen(a.optString("direction", "down"))
            "read_screen" -> readScreen()
            "press_back" -> pressBack()
            "close_app" -> closeApp(a.optString("name", "current"))
            "navigate" -> navigate(a.optString("to"))
            "tap_screen" -> tapScreen(a.optString("label"))
            "type_text" -> typeText(a.optString("text"))
            "set_alarm" -> alarm(a)
            "set_timer" -> timer(a)
            "remember" -> if (prefs.putMemory(a.optString("k"), a.optString("v"))) "" else "I don't store passwords or secrets."
            "forget" -> { prefs.deleteMemory(a.optString("k")); "" }
            "call" -> call(a.optString("contact"))
            "answer_call" -> answerCall()
            "reject_call" -> rejectCall()
            "send_sms" -> sendSms(a.optString("to"), a.optString("body"))
            "read_sms" -> readSms(a.optInt("count", 3).coerceIn(1, 5))
            "whatsapp" -> whatsapp(a.optString("to"), a.optString("body"))
            else -> "I can't do that yet."
        }
    }

    private fun start(i: Intent) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); ctx.startActivity(i) }

    // ---------------- Category A ----------------
    private fun flashlight(on: Boolean): String {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ?: return "This phone has no flashlight."
        cm.setTorchMode(id, on)
        return ""
    }

    private fun volume(a: JSONObject): String {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val dir = a.optString("direction")
        if (dir == "up") am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
        else if (dir == "down") am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
        else {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, a.optInt("level", 50).coerceIn(0, 100) * max / 100, AudioManager.FLAG_SHOW_UI)
        }
        return ""
    }

    private fun brightness(level: Int): String {
        if (!Settings.System.canWrite(ctx)) {
            start(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}")))
            return "I need permission to modify system settings. I opened the page; switch it on for DEC."
        }
        Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, level.coerceIn(0, 100) * 255 / 100)
        return ""
    }

    private fun accessibilitySettings(): String {
        start(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        return "Android Accessibility settings are open. Find DEC AI and switch its service on."
    }

    private fun requireAccessibility(): Boolean =
        AccessibilityControlService.isConnected()

    private suspend fun scrollScreen(direction: String): String {
        if (!requireAccessibility()) return "DEC's Accessibility Service is not enabled. Say open accessibility settings, then enable DEC AI."
        if (sensitiveScreen()) return "I will not inspect or control a banking, wallet, or password app."
        if (!approveScreenOnce()) return "Okay, I will not control the screen."
        val normalized = if (direction.equals("up", true)) "up" else "down"
        val before = listOf(AccessibilityControlService.visibleText(), AccessibilityControlService.visualText())
            .filter { it.isNotBlank() }.joinToString(". ")
        val accepted = AccessibilityControlService.scrollVisible(normalized)
        if (!accepted) return "I couldn't scroll this screen. It may not contain a scrollable area."
        delay(700)
        val after = listOf(AccessibilityControlService.visibleText(), AccessibilityControlService.visualText())
            .filter { it.isNotBlank() }.joinToString(". ")
        if (after.isNotBlank() && after != before) {
            return "Verified: the visible screen content changed after scrolling $normalized."
        }

        // Some apps acknowledge ACTION_SCROLL without actually moving. Try one
        // real swipe, then observe again; never claim success from dispatch alone.
        val fallbackSent = AccessibilityControlService.scrollByGesture(normalized)
        if (fallbackSent) {
            delay(800)
            val fallbackAfter = listOf(
                AccessibilityControlService.visibleText(),
                AccessibilityControlService.visualText()
            ).filter { it.isNotBlank() }.joinToString(". ")
            if (fallbackAfter.isNotBlank() && fallbackAfter != before) {
                return "Verified: the screen moved after a swipe fallback."
            }
        }
        return "I tried the scroll action and a swipe, but could not verify screen movement. I stopped rather than guessing."
    }

    private suspend fun readScreen(): String {
        if (!requireAccessibility()) return "DEC's Accessibility Service is not enabled. Say open accessibility settings, then enable DEC AI."
        if (sensitiveScreen()) return "I will not inspect or control a banking, wallet, or password app."
        if (!approveScreenOnce()) return "Okay, I will not read the screen."
        val tree = AccessibilityControlService.visibleText()
        val ocr = AccessibilityControlService.visualText()
        val visible = listOf(tree, ocr).filter { it.isNotBlank() }.distinct().joinToString(". ")
        return visible.ifBlank { "I couldn't read visible text from the accessibility tree or screen OCR." }
    }

    private suspend fun closeApp(rawName: String): String {
        if (!requireAccessibility()) return "DEC Accessibility is not enabled."
        val name = rawName.trim().ifBlank { "current" }
        if (name.equals("all", true) || name.equals("everything", true) || name.equals("all apps", true)) {
            return if (AccessibilityControlService.clearRecents()) "Cleared the visible Recents list. Android may keep app processes alive." else "Returned Home, but could not find Clear all in Recents."
        }
        val pkg = if (name.equals("current", true) || name.equals("this", true) || name.equals("this app", true)) AccessibilityControlService.foregroundPackage() else findLauncherPackage(name)
        if (pkg == null) return "I could not identify an installed app called $name."
        if (pkg == ctx.packageName) return "That is DEC itself. Use Stop DEC to stop my service."
        val label = AccessibilityControlService.appLabel(pkg)
        return if (AccessibilityControlService.dismissRecentsCard(label)) "Dismissed $label from Recents. Android may keep its process alive." else "Returned Home, but could not reliably dismiss $label from Recents."
    }

    private fun navigate(destination: String): String {
        val ok = when (destination.trim().lowercase()) {
            "home" -> AccessibilityControlService.home()
            "back" -> AccessibilityControlService.back()
            "recents", "recent apps", "overview" -> AccessibilityControlService.recents()
            else -> return "I can navigate Home, Back, or Recents."
        }
        return if (ok) "Navigation action sent: $destination." else "Android did not accept that navigation action."
    }

    private suspend fun approveScreenOnce(): Boolean {
        if (screenApproved) return true
        if (!confirm("May I read and control the visible screen to complete this task?")) return false
        screenApproved = true
        return true
    }

    private fun pressBack(): String {
        if (!requireAccessibility()) return "DEC's Accessibility Service is not enabled. Say open accessibility settings, then enable DEC AI."
        return if (AccessibilityControlService.back()) "Went back." else "Android did not allow the Back action."
    }

    private suspend fun tapScreen(label: String): String {
        if (label.isBlank()) return "Tell me which visible control to tap."
        if (!requireAccessibility()) return "DEC's Accessibility Service is not enabled. Say open accessibility settings, then enable DEC AI."
        if (sensitiveScreen()) return "I will not inspect or control a banking, wallet, or password app."
        if (!approveScreenOnce()) return "Okay, I will not control the screen."
        if (riskyTap.containsMatchIn(label) && !confirm("This action may be sensitive. Tap '$label'?")) return "Okay, I did not tap it."
        val before = listOf(AccessibilityControlService.visibleText(), AccessibilityControlService.visualText())
            .filter { it.isNotBlank() }.joinToString(". ")
        if (!AccessibilityControlService.tapVisibleText(label)) {
            return "I couldn't find a tappable control matching '$label'."
        }
        delay(650)
        val after = listOf(AccessibilityControlService.visibleText(), AccessibilityControlService.visualText())
            .filter { it.isNotBlank() }.joinToString(". ")
        return if (after.isNotBlank() && after != before) {
            "Tapped '$label' and verified that the visible screen changed."
        } else {
            "The tap on '$label' was accepted, but I couldn't verify a screen change. I stopped rather than assuming it worked."
        }
    }

    private suspend fun typeText(value: String): String {
        if (value.isBlank()) return "Tell me what text to type."
        if (!requireAccessibility()) return "DEC's Accessibility Service is not enabled. Say open accessibility settings, then enable DEC AI."
        if (sensitiveScreen()) return "I will not inspect or control a banking, wallet, or password app."
        if (!approveScreenOnce()) return "Okay, I will not control the screen."
        return if (AccessibilityControlService.typeIntoFocusedField(value)) {
            "Typed the text into the focused field."
        } else {
            "I couldn't type there. Make sure a normal text field is focused; password fields are blocked."
        }
    }

    private fun findLauncherPackage(name: String): String? {
        val pm = ctx.packageManager
        val wanted = name.lowercase().trim().replace(Regex("[^a-z0-9]"), "")
        if (wanted.isBlank()) return null
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val candidates = pm.queryIntentActivities(launcher, PackageManager.MATCH_ALL)
        return candidates.firstOrNull { it.loadLabel(pm).toString().lowercase().replace(Regex("[^a-z0-9]"), "") == wanted }?.activityInfo?.packageName
            ?: candidates.firstOrNull {
                val label = it.loadLabel(pm).toString().lowercase().replace(Regex("[^a-z0-9]"), "")
                label.contains(wanted) || wanted.contains(label)
            }?.activityInfo?.packageName
    }

    private fun openApp(name: String): String {
        val q = name.lowercase().trim()
        if (q.isEmpty()) return "Which app?"
        if (q in setOf("browser", "web browser", "internet", "the browser")) {
            start(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER))
            return "Opened your browser."
        }

        val aliases = when (q) {
            "wa", "wa business", "whatsapp business", "business whatsapp" ->
                listOf("com.whatsapp.w4b", "com.whatsapp")
            "whatsapp", "what's app" -> listOf("com.whatsapp", "com.whatsapp.w4b")
            else -> emptyList()
        }
        val pm = ctx.packageManager

        // Resolve well-known app package IDs first. This avoids relying on a
        // localized launcher label such as "WhatsApp Business".
        for (packageName in aliases) {
            val launch = pm.getLaunchIntentForPackage(packageName)
            if (launch != null) {
                start(launch)
                return "Opened ${if (packageName == "com.whatsapp.w4b") "WhatsApp Business" else "WhatsApp"}."
            }
        }

        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val candidates = pm.queryIntentActivities(launcher, PackageManager.MATCH_ALL)
        val normalized = q.replace(Regex("[^a-z0-9]"), "")
        val match = candidates.firstOrNull {
            it.loadLabel(pm).toString().lowercase().replace(Regex("[^a-z0-9]"), "") == normalized
        } ?: candidates.firstOrNull {
            val label = it.loadLabel(pm).toString().lowercase().replace(Regex("[^a-z0-9]"), "")
            label.contains(normalized) || normalized.contains(label)
        } ?: return "I couldn't find an installed app matching '$name'. Check that it is installed."

        val packageName = match.activityInfo.packageName
        val launch = pm.getLaunchIntentForPackage(packageName)
            ?: return "I found $name, but Android did not provide a launch activity."
        start(launch)
        return "Opened ${match.loadLabel(pm)}."
    }

    private fun alarm(a: JSONObject): String {
        start(Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, a.optInt("hour")).putExtra(AlarmClock.EXTRA_MINUTES, a.optInt("minute"))
            .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("message", "DEC")).putExtra(AlarmClock.EXTRA_SKIP_UI, true))
        return ""
    }

    private fun timer(a: JSONObject): String {
        start(Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, a.optInt("seconds", 60).coerceAtLeast(1))
            .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("message", "DEC")).putExtra(AlarmClock.EXTRA_SKIP_UI, true))
        return ""
    }

    // ---------------- Category B (explicit approval every time) ----------------
    private fun resolve(who: String): Pair<String, String>? {
        Contacts.find(ctx, who)?.let { return it }
        // "my brother" -> memory "brother: Sam" -> contact "Sam"
        val mem = prefs.memories().firstOrNull { it.k.equals(who.trim(), true) } ?: return null
        return Contacts.find(ctx, mem.v)
    }

    private suspend fun call(who: String): String {
        val c = resolve(who) ?: return "I couldn't find $who in your contacts."
        if (!confirm("Call ${c.first}?")) return "Okay, I won't call."
        start(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(c.second)))); return ""
    }

    private suspend fun answerCall(): String {
        if (!confirm("Answer the incoming call?")) return "Okay, leaving it."
        val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        tm.acceptRingingCall(); return ""
    }

    private suspend fun rejectCall(): String {
        if (!confirm("Reject the incoming call?")) return "Okay, leaving it."
        if (Build.VERSION.SDK_INT < 28) return "Rejecting calls needs Android 9 or newer."
        val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        return if (tm.endCall()) "" else "I couldn't reject the call."
    }

    private suspend fun sendSms(to: String, body: String): String {
        if (body.isBlank()) return "What should the message say?"
        val c = resolve(to) ?: return "I couldn't find $to in your contacts."
        if (!confirm("Send this text to ${c.first}: $body")) return "Okay, not sent."
        @Suppress("DEPRECATION")
        val sms = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
        sms.sendMultipartTextMessage(c.second, null, sms.divideMessage(body), null, null)
        return "Message sent."
    }

    private suspend fun readSms(n: Int): String {
        if (ctx.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return "I need the Read SMS permission."
        if (!confirm("Read your latest $n text messages aloud?")) return "Okay, I won't read them."
        val out = StringBuilder()
        ctx.contentResolver.query(Uri.parse("content://sms/inbox"), arrayOf("address", "body"), null, null, "date DESC")?.use { c ->
            var i = 0
            while (c.moveToNext() && i < n) {
                val addr = c.getString(0) ?: "unknown"
                out.append("From ${Contacts.nameFor(ctx, addr) ?: addr}: ${c.getString(1).take(200)}. "); i++
            }
        }
        return out.toString().ifBlank { "You have no messages." }
    }

    private fun waNumber(raw: String): String {
        val cc = prefs.countryCode
        val d = raw.filter { it.isDigit() }
        return when {
            raw.trim().startsWith("+") -> d
            cc.isNotEmpty() && d.startsWith("0") -> cc + d.drop(1)
            cc.isNotEmpty() && d.length <= 10 -> cc + d
            else -> d
        }
    }

    private suspend fun whatsapp(to: String, body: String): String {
        if (body.isBlank()) return "What should the WhatsApp message say?"
        val c = resolve(to) ?: return "I couldn't find $to in your contacts."
        if (!confirm("Open WhatsApp with this message for ${c.first}: $body")) return "Okay, not opening WhatsApp."
        val uri = Uri.parse("https://wa.me/${waNumber(c.second)}?text=${Uri.encode(body)}")
        try { start(Intent(Intent.ACTION_VIEW, uri).setPackage("com.whatsapp")) } catch (e: Exception) { start(Intent(Intent.ACTION_VIEW, uri)) }
        return "WhatsApp is open with your message. Tap send to deliver it."
    }

    companion object {
        fun batteryText(ctx: Context): String {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            return "Battery is at ${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)} percent."
        }
    }
}
