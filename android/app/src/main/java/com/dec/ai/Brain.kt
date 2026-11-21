
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
    val online: Boolean
)

class Brain(private val ctx: Context) {

    private val prefs = Prefs.get(ctx)

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(75, TimeUnit.SECONDS)
        .build()

    fun canUseCloud() =
        !prefs.forceOffline &&
        Dec.isOnline(ctx) &&
        prefs.backendUrl.startsWith("https://") &&
        prefs.apiToken.isNotBlank()

    suspend fun ask(text: String, history: List<Msg>): BrainResult =
        withContext(Dispatchers.IO) {

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

    private fun cloud(
        text: String,
        history: List<Msg>
    ): BrainResult {

        val body = JSONObject()
            .put("text", text)
            .put("session_id", prefs.sessionId)
            .put("personality", prefs.personality)
            .put("custom_instructions", prefs.customInstructions)
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
            .put("device", deviceContext())

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
                online = true
            )
        }
    }

    private fun deviceContext(): JSONObject {

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
    }
}
