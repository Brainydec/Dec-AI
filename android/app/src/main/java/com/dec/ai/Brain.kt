
package com.dec.ai

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

data class BrainResult(
    val reply: String,
    val actions: List<Pair<String, JSONObject>>,
    val online: Boolean,
    val more: Boolean = false
)

class Brain(private val ctx: Context) {

    private val prefs = Prefs.get(ctx)

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(75, TimeUnit.SECONDS)
        .build()

    fun canUseCloud() =
        !prefs.forceOffline &&
        !prefs.localOnlyConversation &&
        Dec.isOnline(ctx) &&
        prefs.backendUrl.startsWith("https://") &&
        prefs.apiToken.isNotBlank()

    suspend fun ask(text: String, history: List<Msg>, responseMode: String = "voice"): BrainResult =
        withContext(Dispatchers.IO) {

            // Requests that depend on understanding the current screen must reach the
            // model with the live visible UI context. Other deterministic device commands
            // remain local-first for reliability and speed.
            if (needsScreenContext(text) && !AccessibilityControlService.isConnected()) {
                return@withContext BrainResult(
                    "DEC's Accessibility Service is not enabled. I cannot inspect this screen yet. Enable DEC AI in Android Accessibility settings and try again.",
                    listOf("accessibility_settings" to JSONObject()),
                    online = false
                )
            }

            if (needsScreenContext(text) && canUseCloud()) {
                try {
                    return@withContext cloud(text, history, responseMode)
                } catch (e: Exception) {
                    Log.w("DEC", "screen-aware cloud request failed: ${e.message}")
                    Dec.notice.value = "screen analysis unavailable: ${e.message}"
                }
            }

            val local = parseDeviceCommand(text) ?: parseAccessibilityCommand(text)
            if (local != null) return@withContext local

            if (canUseCloud()) {
                try {
                    return@withContext cloud(text, history)
                } catch (e: Exception) {
                    Log.w("DEC", "cloud failed, using offline: ${e.message}")
                    Dec.notice.value = "cloud failed: ${e.message}"
                }
            }

            offline(text)
        }

    /**
     * Offline command handler.
     * Device commands are checked before the general OfflineParser.
     */
    private fun offline(text: String): BrainResult {

        val deviceCommand = parseDeviceCommand(text)

        if (deviceCommand != null) {
            return deviceCommand
        }

        val localReply = localConversationReply(text)
        if (localReply != null) {
            return BrainResult(
                reply = localReply,
                actions = emptyList(),
                online = false
            )
        }

        val plan = OfflineParser.parse(text, prefs)

        val reply = if (plan.reply == "__battery__") {
            Executor.batteryText(ctx)
        } else {
            plan.reply
        }

        return BrainResult(
            reply = reply,
            actions = plan.actions,
            online = false
        )
    }

    /**
     * Small, transparent offline conversation fallback. This is not a local LLM:
     * complex reasoning is intentionally left to the configured cloud provider.
     */
    private fun localConversationReply(text: String): String? {
        val t = text.lowercase(Locale.ROOT).trim().trimEnd('.', '!', '?')
        return when {
            t.matches(Regex("^(hi|hello|hey|good morning|good afternoon|good evening|hey dec|hey deck)$")) ->
                "Hello. I'm here with you. You can chat by text or voice, and your conversation is saved on this phone."
            t in setOf("how are you", "how are you doing", "how is it going") ->
                "I'm ready to help. What would you like to talk about?"
            t in setOf("thank you", "thanks", "thanks dec") ->
                "You're welcome. I'm here whenever you need me."
            t in setOf("who are you", "what are you") ->
                "I'm DEC, your phone assistant. I can chat, remember approved details, and help operate your phone when you grant the required permissions."
            t in setOf("what do you remember about me", "what do you know about me") -> {
                val facts = prefs.memories()
                if (facts.isEmpty()) "I don't have any saved facts about you yet."
                else "Here are the facts you've saved: " + facts.joinToString("; ") { "${it.k}: ${it.v}" }
            }
            t in setOf("what can you do offline", "what can you do without internet") ->
                "Offline, I can handle supported phone commands, use your saved memories, and give simple conversational replies. Deeper reasoning and research need cloud mode."
            t in setOf("are you private", "is this conversation private") ->
                if (prefs.localOnlyConversation || prefs.forceOffline) "Local-only mode is enabled. DEC does not send this conversation to its cloud AI provider. Your saved chat is stored encrypted on this phone."
                else "Your saved conversation is encrypted on this phone, but cloud mode may send your message, recent context, selected memories and requested screen text to your configured AI provider."
            else -> null
        }
    }

    /**
     * Handles common screen-control wording locally, before cloud interpretation.
     */
    private fun parseAccessibilityCommand(text: String): BrainResult? {
        val command = text.lowercase(Locale.ROOT)
            .replace(Regex("[,!?]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (command.isBlank()) return null

        fun result(tool: String, args: JSONObject = JSONObject()) =
            BrainResult("", listOf(tool to args), online = false)

        if (Regex("""^(?:please )?(?:can you |could you )?(?:please )?(?:read|see|show|tell me|describe)(?: me)? (?:what(?:'s| is) )?(?:on )?(?:my |this |the )?screen(?: to me)?$""").matches(command) ||
            command in setOf("read screen", "read my screen", "read this screen",
                "what is on my screen", "what's on my screen", "what is this screen showing",
                "what does my screen say", "what does this say", "describe this screen",
                "look at my screen", "inspect my screen", "can you see my screen",
                "what can you see on my screen")) {
            return result("read_screen")
        }

        if (command in setOf("go back", "press back", "back", "navigate back", "return to previous screen")) {
            return result("press_back")
        }

        Regex("""^(?:please )?(?:scroll|swipe) (up|down)(?: on (?:my |the )?screen)?$""")
            .find(command)?.let { return result("scroll_screen", JSONObject().put("direction", it.groupValues[1])) }

        // Natural-language taps go to the cloud planner with the current visible
        // screen context, so DEC can resolve descriptions such as "the blue button".
        Regex("""^(?:please )?(?:type|enter|write) (.+?)(?: on (?:my )?(?:keyboard|screen|text field|message box))?$""")
            .find(command)?.let {
                val value = it.groupValues[1].trim().trim('"')
                if (value.isNotBlank()) return result("type_text", JSONObject().put("text", value))
            }

        if (command in setOf("open accessibility settings", "accessibility settings", "enable accessibility")) {
            return result("accessibility_settings")
        }
        return null
    }

    /**
     * Recognizes common phone commands without internet.
     */
    private fun parseDeviceCommand(text: String): BrainResult? {

        val command = text.lowercase(Locale.ROOT)
            .replace(",", " ")
            .replace("percent", "%")
            .replace("per cent", "%")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (command.isBlank()) return null

        // ---------------- VOLUME ----------------

        val volumePattern = Regex(
            """(?:set\s+)?(?:the\s+)?volume\s*(?:to|at|on)?\s*(\d{1,3})\s*%?"""
        )

        val volumeMatch = volumePattern.find(command)

        if (volumeMatch != null) {
            val level = volumeMatch.groupValues[1]
                .toIntOrNull()
                ?.coerceIn(0, 100)
                ?: return null

            return BrainResult(
                reply = "Setting media volume to $level percent.",
                actions = listOf(
                    "volume" to JSONObject().put("level", level)
                ),
                online = false
            )
        }

        if (
            command.contains("increase volume") ||
            command.contains("turn up the volume") ||
            command.contains("volume up") ||
            command.contains("raise the volume") ||
            command == "louder"
        ) {
            return BrainResult(
                reply = "Increasing media volume.",
                actions = listOf(
                    "volume" to JSONObject().put("direction", "up")
                ),
                online = false
            )
        }

        if (
            command.contains("decrease volume") ||
            command.contains("turn down the volume") ||
            command.contains("volume down") ||
            command.contains("lower the volume") ||
            command == "quieter"
        ) {
            return BrainResult(
                reply = "Decreasing media volume.",
                actions = listOf(
                    "volume" to JSONObject().put("direction", "down")
                ),
                online = false
            )
        }

        if (
            command == "mute" ||
            command == "mute volume" ||
            command == "mute the volume"
        ) {
            return BrainResult(
                reply = "Muting media volume.",
                actions = listOf(
                    "volume" to JSONObject().put("level", 0)
                ),
                online = false
            )
        }

        // ---------------- BRIGHTNESS ----------------

        if (
            command.contains("brightness") &&
            (
                command.contains("maximum") ||
                command.contains("max") ||
                command.contains("full") ||
                command.contains("highest")
            )
        ) {
            return BrainResult(
                reply = "Setting screen brightness to maximum.",
                actions = listOf(
                    "brightness" to JSONObject().put("level", 100)
                ),
                online = false
            )
        }

        if (
            command.contains("brightness") &&
            (
                command.contains("minimum") ||
                command.contains("min") ||
                command.contains("lowest")
            )
        ) {
            return BrainResult(
                reply = "Setting screen brightness to minimum.",
                actions = listOf(
                    "brightness" to JSONObject().put("level", 0)
                ),
                online = false
            )
        }

        val brightnessPattern = Regex(
            """(?:set\s+)?(?:the\s+)?brightness\s*(?:to|at)?\s*(\d{1,3})\s*%?"""
        )

        val brightnessMatch = brightnessPattern.find(command)

        if (brightnessMatch != null) {
            val level = brightnessMatch.groupValues[1]
                .toIntOrNull()
                ?.coerceIn(0, 100)
                ?: return null

            return BrainResult(
                reply = "Setting screen brightness to $level percent.",
                actions = listOf(
                    "brightness" to JSONObject().put("level", level)
                ),
                online = false
            )
        }

        // ---------------- TIMERS ----------------

        val timerPattern = Regex(
            """(?:set\s+)?(?:a\s+)?timer\s+(?:for\s+)?(\d+)\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)"""
        )

        val timerMatch = timerPattern.find(command)

        if (timerMatch != null) {
            val amount = timerMatch.groupValues[1].toLongOrNull()
                ?: return null

            val unit = timerMatch.groupValues[2]

            val seconds = when {
                unit.startsWith("sec") -> amount
                unit.startsWith("min") -> amount * 60L
                unit.startsWith("hour") || unit.startsWith("hr") ->
                    amount * 3600L
                else -> return null
            }

            if (seconds !in 1L..86400L) {
                return BrainResult(
                    reply = "Please choose a timer between 1 second and 24 hours.",
                    actions = emptyList(),
                    online = false
                )
            }

            return BrainResult(
                reply = "Opening a timer for $amount $unit.",
                actions = listOf(
                    "set_timer" to JSONObject()
                        .put("seconds", seconds.toInt())
                        .put("message", "DEC")
                ),
                online = false
            )
        }

        // ---------------- FLASHLIGHT ----------------

        if (
            command.contains("turn on flashlight") ||
            command.contains("switch on flashlight") ||
            command.contains("flashlight on") ||
            command == "torch on" ||
            command == "turn on torch"
        ) {
            return BrainResult(
                reply = "Turning on the flashlight.",
                actions = listOf(
                    "flashlight" to JSONObject().put("on", true)
                ),
                online = false
            )
        }

        if (
            command.contains("turn off flashlight") ||
            command.contains("switch off flashlight") ||
            command.contains("flashlight off") ||
            command == "torch off" ||
            command == "turn off torch"
        ) {
            return BrainResult(
                reply = "Turning off the flashlight.",
                actions = listOf(
                    "flashlight" to JSONObject().put("on", false)
                ),
                online = false
            )
        }

        return null
    }

    private suspend fun cloud(
        text: String,
        history: List<Msg>,
        responseMode: String
    ): BrainResult {

        val body = JSONObject()
            .put("text", text)
            .put("session_id", prefs.sessionId)
            .put("personality", prefs.personality)
            .put("custom_instructions", prefs.customInstructions)
            .put("response_mode", if (responseMode == "text") "text" else "voice")
            .put(
                "history",
                JSONArray().also { array ->
                    history.forEach {
                        array.put(
                            JSONObject()
                                .put(
                                    "role",
                                    if (it.fromUser) "user" else "assistant"
                                )
                                .put("content", it.text)
                        )
                    }
                }
            )
            .put(
                "memories",
                JSONArray().also { array ->
                    prefs.memories().forEach {
                        array.put(
                            JSONObject()
                                .put("k", it.k)
                                .put("v", it.v)
                        )
                    }
                }
            )
            .put("device", deviceContext(includeScreen = needsScreenContext(text)))

        val request = Request.Builder()
            .url(prefs.backendUrl + "/v1/chat")
            .header("Authorization", "Bearer ${prefs.apiToken}")
            .post(
                body.toString().toRequestBody(
                    "application/json".toMediaType()
                )
            )
            .build()

        http.newCall(request).execute().use { response ->

            val responseText = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                throw RuntimeException(
                    "backend ${response.code}: ${responseText.take(120)}"
                )
            }

            val json = JSONObject(responseText)

            val actions = json.optJSONArray("actions") ?: JSONArray()

            return BrainResult(
                reply = json.optString("reply"),
                actions = (0 until actions.length()).map { index ->
                    actions.getJSONObject(index).let { action ->
                        action.getString("tool") to
                            (action.optJSONObject("args") ?: JSONObject())
                    }
                },
                online = true,
                more = json.optBoolean("more", false)
            )
        }
    }

    private fun needsScreenContext(text: String): Boolean {
        val command = text.lowercase(Locale.ROOT)
        return listOf(
            "[result]", "[screen]", "continue the original user task",
            "screen", "tap", "click", "press", "select", "choose",
            "button", "icon", "menu", "this page", "this app",
            "what is open", "what's open", "what can you see"
        ).any(command::contains)
    }

    private suspend fun deviceContext(includeScreen: Boolean = false): JSONObject {

        val battery = ctx.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )

        val level = battery?.getIntExtra(
            BatteryManager.EXTRA_LEVEL,
            -1
        ) ?: -1

        val scale = battery?.getIntExtra(
            BatteryManager.EXTRA_SCALE,
            100
        ) ?: 100

        val status = battery?.getIntExtra(
            BatteryManager.EXTRA_STATUS,
            -1
        ) ?: -1

        val cm = ctx.getSystemService(
            Context.CONNECTIVITY_SERVICE
        ) as ConnectivityManager

        val capabilities = cm.getNetworkCapabilities(cm.activeNetwork)

        val network = when {
            capabilities == null -> "none"

            capabilities.hasTransport(
                NetworkCapabilities.TRANSPORT_WIFI
            ) -> "wifi"

            capabilities.hasTransport(
                NetworkCapabilities.TRANSPORT_CELLULAR
            ) -> "mobile data"

            else -> "other"
        }

        return JSONObject()
            .put(
                "time_iso",
                SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ssXXX",
                    Locale.US
                ).format(Date())
            )
            .put("timezone", TimeZone.getDefault().id)
            .put(
                "battery_percent",
                if (level >= 0 && scale > 0) {
                    level * 100 / scale
                } else {
                    JSONObject.NULL
                }
            )
            .put(
                "charging",
                status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            )
            .put("network", network)
            .put("accessibility_connected", AccessibilityControlService.isConnected())
            .put(
                "screen_text",
                if (includeScreen && AccessibilityControlService.isConnected()) {
                    val tree = AccessibilityControlService.visibleText()
                    val ocr = AccessibilityControlService.visualText()
                    listOf(tree, ocr).filter { it.isNotBlank() }.distinct().joinToString(". ").take(5000)
                } else {
                    ""
                }
            )
    }
}
