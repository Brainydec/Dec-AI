
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

/**
 * Executes phone actions. EVERY action passes through [guarded], which enforces, in order:
 * emergency stop -> per-tool switch -> offline switch -> Android runtime permission
 * -> (Category B) explicit approval.
 */
class Executor(
    private val ctx: Context,
    private val confirm: suspend (String) -> Boolean
) {
    private val prefs = Prefs.get(ctx)

    /** Returns text to speak ("" = success, nothing to say). */
    suspend fun run(toolId: String, args: JSONObject, online: Boolean): String {
        val tool = Tool.forAction(toolId)
            ?: return "I don't know how to do $toolId."

        val result = try {
            guarded(tool, toolId, args, online)
        } catch (e: SecurityException) {
            "Android blocked that action. A permission is missing."
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            "That didn't work: ${e.message}"
        }

        prefs.addLog(
            tool.label,
            args.toString().take(160),
            result.ifBlank { "done" }.take(120)
        )

        return result
    }

    private fun hardPerms(t: Tool): List<String> = when (t) {
        Tool.CALL -> listOf(Manifest.permission.CALL_PHONE)
        Tool.ANSWER_CALL, Tool.REJECT_CALL ->
            listOf(Manifest.permission.ANSWER_PHONE_CALLS)
        Tool.SEND_SMS -> listOf(Manifest.permission.SEND_SMS)
        Tool.READ_SMS -> listOf(Manifest.permission.READ_SMS)
        else -> emptyList()
    }

    private suspend fun guarded(
        tool: Tool,
        id: String,
        a: JSONObject,
        online: Boolean
    ): String {
        if (prefs.emergencyStop) {
            return "Emergency stop is on. Resume DEC in the app first."
        }

        if (!prefs.toolEnabled(tool)) {
            return "${tool.label} is switched off in Permissions."
        }

        if (!online && !prefs.offlineAllowed(tool)) {
            return "${tool.label} is disabled for offline use."
        }

        if (hardPerms(tool).any {
                ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
            }
        ) {
            return "I need the ${tool.label} permission. Open Permissions in the DEC app and tap Grant."
        }

        return when (id) {
            "flashlight" -> flashlight(a.optBoolean("on", true))
            "volume" -> volume(a)
            "brightness" -> brightness(a.optInt("level", 50))
            "open_app" -> openApp(a.optString("name"))
            "set_alarm" -> alarm(a)
            "set_timer" -> timer(a)

            "remember" -> {
                if (prefs.putMemory(a.optString("k"), a.optString("v"))) {
                    ""
                } else {
                    "I don't store passwords or secrets."
                }
            }

            "forget" -> {
                prefs.deleteMemory(a.optString("k"))
                ""
            }

            "call" -> call(a.optString("contact"))
            "answer_call" -> answerCall()
            "reject_call" -> rejectCall()
            "send_sms" -> sendSms(
                a.optString("to"),
                a.optString("body")
            )
            "read_sms" -> readSms(
                a.optInt("count", 3).coerceIn(1, 5)
            )
            "whatsapp" -> whatsapp(
                a.optString("to"),
                a.optString("body")
            )

            else -> "I can't do that yet."
        }
    }

    private fun start(i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }

    // ---------------- Category A ----------------

    private fun flashlight(on: Boolean): String {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        val cameraId = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "This phone has no flashlight."

        cm.setTorchMode(cameraId, on)

        return ""
    }

    /**
     * Sets the actual media stream volume and verifies the result.
     * Supports both percentage levels and up/down commands.
     */
    private fun volume(a: JSONObject): String {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val stream = AudioManager.STREAM_MUSIC
        val maxVolume = am.getStreamMaxVolume(stream)

        if (maxVolume <= 0) {
            return "I couldn't access the media volume."
        }

        val currentVolume = am.getStreamVolume(stream)
        val direction = a.optString("direction").lowercase()

        val targetVolume = when (direction) {
            "up" -> (currentVolume + 1).coerceAtMost(maxVolume)
            "down" -> (currentVolume - 1).coerceAtLeast(0)
            else -> {
                val level = a.optInt("level", 50).coerceIn(0, 100)
                (level * maxVolume / 100.0).toInt()
            }
        }

        if (targetVolume == currentVolume) {
            return when (direction) {
                "up" -> "The media volume is already at maximum."
                "down" -> "The media volume is already at minimum."
                else -> ""
            }
        }

        am.setStreamVolume(
            stream,
            targetVolume,
            AudioManager.FLAG_SHOW_UI
        )

        val actualVolume = am.getStreamVolume(stream)

        return if (actualVolume == targetVolume) {
            ""
        } else {
            "Android did not apply the requested volume. Check your phone's sound settings."
        }
    }

    /**
     * Changes system screen brightness.
     * DEC must have Modify System Settings permission.
     */
    private fun brightness(level: Int): String {
        if (!Settings.System.canWrite(ctx)) {
            start(
                Intent(
                    Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:${ctx.packageName}")
                )
            )

            return "I need permission to modify system settings. I opened the permission page. Enable Allow modifying system settings for DEC, then try again."
        }

        val targetBrightness =
            level.coerceIn(0, 100) * 255 / 100

        val modeSaved = Settings.System.putInt(
            ctx.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        )

        if (!modeSaved) {
            return "I couldn't change the screen brightness mode."
        }

        val brightnessSaved = Settings.System.putInt(
            ctx.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS,
            targetBrightness
        )

        if (!brightnessSaved) {
            return "Android refused to change the screen brightness."
        }

        val actualBrightness = Settings.System.getInt(
            ctx.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS,
            -1
        )

        return if (actualBrightness == targetBrightness) {
            ""
        } else {
            "The phone did not save the requested brightness level."
        }
    }

    private fun openApp(name: String): String {
        val q = name.lowercase().trim()

        if (q.isEmpty()) return "Which app?"

        if (q in setOf("browser", "web browser", "internet", "the browser")) {
            start(
                Intent.makeMainSelectorActivity(
                    Intent.ACTION_MAIN,
                    Intent.CATEGORY_APP_BROWSER
                )
            )
            return ""
        }

        val pm = ctx.packageManager

        val launcher = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)

        val activities = pm.queryIntentActivities(launcher, 0)

        val match = activities.firstOrNull {
            it.loadLabel(pm).toString().lowercase() == q
        } ?: activities.firstOrNull {
            it.loadLabel(pm).toString().lowercase().contains(q)
        } ?: return "I couldn't find an app called $name."

        val intent = pm.getLaunchIntentForPackage(
            match.activityInfo.packageName
        ) ?: return "I can't launch $name."

        start(intent)

        return ""
    }

    private fun alarm(a: JSONObject): String {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, a.optInt("hour"))
            .putExtra(AlarmClock.EXTRA_MINUTES, a.optInt("minute"))
            .putExtra(
                AlarmClock.EXTRA_MESSAGE,
                a.optString("message", "DEC")
            )
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)

        if (intent.resolveActivity(ctx.packageManager) == null) {
            return "I couldn't find a clock app that can set alarms."
        }

        start(intent)

        return ""
    }

    /**
     * Opens the clock app with the requested timer.
     * The user confirms Start in the clock app.
     */
    private fun timer(a: JSONObject): String {
        val seconds = a.optInt("seconds", 60).coerceIn(1, 86400)

        val message = a.optString("message", "DEC")

        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_MESSAGE, message)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, false)

        if (intent.resolveActivity(ctx.packageManager) == null) {
            return "I couldn't find a clock app that supports timers."
        }

        start(intent)

        return "I opened the clock app with your ${seconds} second timer. Confirm Start to activate it."
    }

    // ---------------- Category B (explicit approval every time) ----------------

    private fun resolve(who: String): Pair<String, String>? {
        Contacts.find(ctx, who)?.let {
            return it
        }

        // "my brother" -> memory "brother: Sam" -> contact "Sam"
        val mem = prefs.memories().firstOrNull {
            it.k.equals(who.trim(), true)
        } ?: return null

        return Contacts.find(ctx, mem.v)
    }

    private suspend fun call(who: String): String {
        val c = resolve(who)
            ?: return "I couldn't find $who in your contacts."

        if (!confirm("Call ${c.first}?")) {
            return "Okay, I won't call."
        }

        start(
            Intent(
                Intent.ACTION_CALL,
                Uri.parse("tel:" + Uri.encode(c.second))
            )
        )

        return ""
    }

    private suspend fun answerCall(): String {
        if (!confirm("Answer the incoming call?")) {
            return "Okay, leaving it."
        }

        val tm = ctx.getSystemService(
            Context.TELECOM_SERVICE
        ) as TelecomManager

        tm.acceptRingingCall()

        return ""
    }

    private suspend fun rejectCall(): String {
        if (!confirm("Reject the incoming call?")) {
            return "Okay, leaving it."
        }

        if (Build.VERSION.SDK_INT < 28) {
            return "Rejecting calls needs Android 9 or newer."
        }

        val tm = ctx.getSystemService(
            Context.TELECOM_SERVICE
        ) as TelecomManager

        return if (tm.endCall()) {
            ""
        } else {
            "I couldn't reject the call."
        }
    }

    private suspend fun sendSms(
        to: String,
        body: String
    ): String {
        if (body.isBlank()) {
            return "What should the message say?"
        }

        val c = resolve(to)
            ?: return "I couldn't find $to in your contacts."

        if (!confirm("Send this text to ${c.first}: $body")) {
            return "Okay, not sent."
        }

        @Suppress("DEPRECATION")
        val sms = if (Build.VERSION.SDK_INT >= 31) {
            ctx.getSystemService(SmsManager::class.java)
        } else {
            SmsManager.getDefault()
        }

        sms.sendMultipartTextMessage(
            c.second,
            null,
            sms.divideMessage(body),
            null,
            null
        )

        return "Message sent."
    }

    private suspend fun readSms(n: Int): String {
        if (
            ctx.checkSelfPermission(Manifest.permission.READ_SMS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return "I need the Read SMS permission."
        }

        if (!confirm("Read your latest $n text messages aloud?")) {
            return "Okay, I won't read them."
        }

        val out = StringBuilder()

        ctx.contentResolver.query(
            Uri.parse("content://sms/inbox"),
            arrayOf("address", "body"),
            null,
            null,
            "date DESC"
        )?.use { cursor ->

            var i = 0

            while (cursor.moveToNext() && i < n) {
                val address = cursor.getString(0) ?: "unknown"
                val body = cursor.getString(1) ?: ""

                out.append(
                    "From ${Contacts.nameFor(ctx, address) ?: address}: " +
                    "${body.take(200)}. "
                )

                i++
            }
        }

        return out.toString().ifBlank {
            "You have no messages."
        }
    }

    private fun waNumber(raw: String): String {
        val cc = prefs.countryCode
        val digits = raw.filter { it.isDigit() }

        return when {
            raw.trim().startsWith("+") -> digits
            cc.isNotEmpty() && digits.startsWith("0") ->
                cc + digits.drop(1)
            cc.isNotEmpty() && digits.length <= 10 ->
                cc + digits
            else -> digits
        }
    }

    private suspend fun whatsapp(
        to: String,
        body: String
    ): String {
        if (body.isBlank()) {
            return "What should the WhatsApp message say?"
        }

        val c = resolve(to)
            ?: return "I couldn't find $to in your contacts."

        if (!confirm("Open WhatsApp with this message for ${c.first}: $body")) {
            return "Okay, not opening WhatsApp."
        }

        val uri = Uri.parse(
            "https://wa.me/${waNumber(c.second)}?text=${Uri.encode(body)}"
        )

        try {
            start(
                Intent(Intent.ACTION_VIEW, uri)
                    .setPackage("com.whatsapp")
            )
        } catch (e: Exception) {
            start(Intent(Intent.ACTION_VIEW, uri))
        }

        return "WhatsApp is open with your message. Tap send to deliver it."
    }

    companion object {
        fun batteryText(ctx: Context): String {
            val bm = ctx.getSystemService(
                Context.BATTERY_SERVICE
            ) as BatteryManager

            return "Battery is at ${
                bm.getIntProperty(
                    BatteryManager.BATTERY_PROPERTY_CAPACITY
                )
            } percent."
        }
    }
}
