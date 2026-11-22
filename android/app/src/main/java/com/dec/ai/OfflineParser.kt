
package com.dec.ai

import org.json.JSONObject
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class Plan(
    val reply: String,
    val actions: List<Pair<String, JSONObject>> = emptyList()
)

/** Rule-based command understanding that works with no internet or AI model. */
object OfflineParser {

    private fun a(
        tool: String,
        vararg kv: Pair<String, Any>
    ) = tool to JSONObject().also { obj ->
        kv.forEach { obj.put(it.first, it.second) }
    }

    fun parse(raw: String, prefs: Prefs): Plan {
        val t = raw.lowercase(Locale.ROOT)
            .trim()
            .trimEnd('.', '!', '?')

        // ---------------- EMERGENCY STOP ----------------

        if (
            t == "emergency stop" ||
            t == "stop dec" ||
            t == "stop everything"
        ) {
            return Plan(
                "Emergency stop requested. DEC actions must remain disabled until resumed in the app."
            )
        }

        // ---------------- RESUME DEC ----------------

        if (
            t == "start dec" ||
            t == "resume dec" ||
            t == "continue dec"
        ) {
            return Plan(
                if (prefs.emergencyStop) {
                    "Emergency stop is still enabled. Resume DEC in the app first."
                } else {
                    "DEC is ready."
                }
            )
        }

        // ---------------- MEMORY ----------------

        Regex("""remember (?:that )?(?:my )?(.+?) (?:is|are) (.+)""")
            .find(t)?.let {
                return Plan(
                    "Okay, I'll remember that.",
                    listOf(
                        a(
                            "remember",
                            "k" to it.groupValues[1],
                            "v" to it.groupValues[2]
                        )
                    )
                )
            }

        Regex("""forget (?:about )?(?:my )?(.+)""")
            .find(t)?.let {
                return Plan(
                    "Okay.",
                    listOf(a("forget", "k" to it.groupValues[1]))
                )
            }

        // ---------------- FLASHLIGHT ----------------

        if (Regex("""\b(flashlight|torch)\b""").containsMatchIn(t)) {
            val off = Regex("""\b(off|disable|turn off|switch off)\b""")
                .containsMatchIn(t)

            return Plan(
                if (off) "Turning off the flashlight."
                else "Turning on the flashlight.",
                listOf(a("flashlight", "on" to !off))
            )
        }

        // ---------------- CALL CONTROLS ----------------

        if (
            Regex("""\b(answer|pick up|accept)\b.*\bcall\b""").containsMatchIn(t) ||
            t == "answer" ||
            t == "pick up"
        ) {
            return Plan("", listOf(a("answer_call")))
        }

        if (
            Regex("""\b(reject|decline|hang up|end)\b.*\bcall\b""")
                .containsMatchIn(t) ||
            t == "reject" ||
            t == "decline"
        ) {
            return Plan("", listOf(a("reject_call")))
        }

        // ---------------- TIMERS ----------------

        val timerMatch = Regex(
            """(?:set )?(?:a )?timer (?:for )?(\d+) (seconds?|secs?|minutes?|mins?|hours?|hrs?)"""
        ).find(t)

        if (timerMatch != null) {
            val amount = timerMatch.groupValues[1].toLongOrNull()
                ?: return Plan("Please specify a valid timer duration.")

            val unit = timerMatch.groupValues[2]

            val seconds = when {
                unit.startsWith("sec") -> amount
                unit.startsWith("min") -> amount * 60L
                else -> amount * 3600L
            }

            if (seconds !in 1L..86400L) {
                return Plan("Please choose a timer between 1 second and 24 hours.")
            }

            return Plan(
                "Opening your timer for $amount $unit. Confirm Start in the clock app.",
                listOf(
                    a(
                        "set_timer",
                        "seconds" to seconds.toInt(),
                        "message" to "DEC timer"
                    )
                )
            )
        }

        // ---------------- RELATIVE ALARMS ----------------

        val relativeAlarm = Regex(
            """(?:set )?(?:an? )?alarm (?:for )?(?:the )?next (\d+) (seconds?|secs?|minutes?|mins?|hours?|hrs?)"""
        ).find(t)

        if (relativeAlarm != null) {
            val amount = relativeAlarm.groupValues[1].toLongOrNull()
                ?: return Plan("Please specify a valid alarm duration.")

            val unit = relativeAlarm.groupValues[2]

            val minutes = when {
                unit.startsWith("sec") -> {
                    if (amount < 30) 1L else (amount + 30L) / 60L
                }
                unit.startsWith("min") -> amount
                else -> amount * 60L
            }

            if (minutes !in 1L..1440L) {
                return Plan("Please choose an alarm within the next 24 hours.")
            }

            val calendar = Calendar.getInstance()
            calendar.add(Calendar.MINUTE, minutes.toInt())

            val hour = calendar.get(Calendar.HOUR_OF_DAY)
            val minute = calendar.get(Calendar.MINUTE)

            return Plan(
                "Setting an alarm for $amount $unit from now.",
                listOf(
                    a(
                        "set_alarm",
                        "hour" to hour,
                        "minute" to minute,
                        "message" to "DEC alarm"
                    )
                )
            )
        }

        // ---------------- CLOCK ALARMS ----------------

        val alarmMatch = Regex(
            """(?:set )?(?:an? )?alarm (?:for |at )?(\d{1,2})(?::(\d{2}))?\s*(am|pm|a m|p m)?"""
        ).find(t)

        if (alarmMatch != null) {
            var hour = alarmMatch.groupValues[1].toIntOrNull()
                ?: return Plan("Please specify a valid alarm time.")

            val minute = alarmMatch.groupValues[2]
                .ifEmpty { "0" }
                .toIntOrNull() ?: 0

            val period = alarmMatch.groupValues[3].replace(" ", "")

            if (period == "pm" && hour < 12) hour += 12
            if (period == "am" && hour == 12) hour = 0

            if (hour in 0..23 && minute in 0..59) {
                return Plan(
                    "Opening an alarm for $hour:${minute.toString().padStart(2, '0')}.",
                    listOf(
                        a(
                            "set_alarm",
                            "hour" to hour,
                            "minute" to minute,
                            "message" to "DEC alarm"
                        )
                    )
                )
            }
        }

        // ---------------- VOLUME ----------------

        val volumeMatch = Regex(
            """(?:set )?(?:the )?volume (?:to |at )?(\d{1,3})\s*%?"""
        ).find(t)

        if (volumeMatch != null) {
            val level = volumeMatch.groupValues[1]
                .toInt()
                .coerceIn(0, 100)

            return Plan(
                "Setting volume to $level percent.",
                listOf(a("volume", "level" to level))
            )
        }

        if (
            Regex("""volume up|increase volume|turn it up|turn up the volume|louder""")
                .containsMatchIn(t)
        ) {
            return Plan(
                "Increasing volume.",
                listOf(a("volume", "direction" to "up"))
            )
        }

        if (
            Regex("""volume down|decrease volume|turn it down|turn down the volume|quieter""")
                .containsMatchIn(t)
        ) {
            return Plan(
                "Decreasing volume.",
                listOf(a("volume", "direction" to "down"))
            )
        }

        // ---------------- BRIGHTNESS ----------------

        if (
            t.contains("brightness") &&
            Regex("""\b(maximum|max|full|highest)\b""").containsMatchIn(t)
        ) {
            return Plan(
                "Setting brightness to maximum.",
                listOf(a("brightness", "level" to 100))
            )
        }

        if (
            t.contains("brightness") &&
            Regex("""\b(minimum|min|lowest)\b""").containsMatchIn(t)
        ) {
            return Plan(
                "Setting brightness to minimum.",
                listOf(a("brightness", "level" to 0))
            )
        }

        val brightnessMatch = Regex(
            """(?:set )?(?:the )?brightness (?:to |at )?(\d{1,3})\s*%?"""
        ).find(t)

        if (brightnessMatch != null) {
            val level = brightnessMatch.groupValues[1]
                .toInt()
                .coerceIn(0, 100)

            return Plan(
                "Setting brightness to $level percent.",
                listOf(a("brightness", "level" to level))
            )
        }

        // ---------------- SMS ----------------

        Regex(
            """(?:send (?:a )?(?:text|sms|message) to|text) (.+?) (?:saying|that says|say) (.+)"""
        ).find(t)?.let {
            return Plan(
                "",
                listOf(
                    a(
                        "send_sms",
                        "to" to it.groupValues[1],
                        "body" to it.groupValues[2]
                    )
                )
            )
        }

        // ---------------- WHATSAPP ----------------

        Regex(
            """whatsapp (.+?) (?:saying|that says|say) (.+)"""
        ).find(t)?.let {
            return Plan(
                "",
                listOf(
                    a(
                        "whatsapp",
                        "to" to it.groupValues[1],
                        "body" to it.groupValues[2]
                    )
                )
            )
        }

        // ---------------- CALL ----------------

        Regex("""(?:call|phone|dial) (.+)""").find(t)?.let {
            return Plan(
                "",
                listOf(
                    a(
                        "call",
                        "contact" to it.groupValues[1]
                            .removePrefix("my ")
                    )
                )
            )
        }

        // ---------------- READ MESSAGES ----------------

        if (
            Regex("""read (?:my )?(?:texts|messages|sms)""")
                .containsMatchIn(t)
        ) {
            return Plan(
                "",
                listOf(a("read_sms", "count" to 3))
            )
        }

        // ---------------- OPEN APPS ----------------

        Regex("""(?:open|launch) (.+)""").find(t)?.let {
            val app = it.groupValues[1]
                .removePrefix("my ")
                .removePrefix("the ")

            return Plan(
                "Opening $app.",
                listOf(a("open_app", "name" to app))
            )
        }

        // ---------------- APP EXIT / HOME ----------------

        if (t in setOf("close app", "close this app", "exit app", "exit this app", "go to home screen", "return to home screen", "go home", "home screen")) {
            return Plan("", listOf(a("close_app")))
        }

        // ---------------- ACCESSIBILITY / SCREEN CONTROLS ----------------

        if (t in setOf("open accessibility settings", "enable accessibility", "accessibility settings")) {
            return Plan("Opening Android Accessibility settings. Find DEC AI and enable its service.", listOf(a("accessibility_settings")))
        }

        if (t in setOf("read screen", "read my screen", "read this screen", "what is on my screen",
                "what's on my screen", "what does this say", "what does my screen say",
                "describe this screen", "look at my screen", "inspect my screen")) {
            return Plan("", listOf(a("read_screen")))
        }

        if (t in setOf("go back", "press back", "back", "navigate back", "return to previous screen")) {
            return Plan("", listOf(a("press_back")))
        }

        Regex("""^(?:please )?(?:scroll|swipe)(?: the screen)? (up|down)(?: a bit| a little| slightly| on (?:my |the )?screen)?$""").find(t)?.let {
            return Plan("", listOf(a("scroll_screen", "direction" to it.groupValues[1])))
        }
        if (t in setOf("scroll down a bit", "scroll down a little", "swipe up a bit", "swipe down a bit")) {
            val direction = if (t.contains("up")) "up" else "down"
            return Plan("", listOf(a("scroll_screen", "direction" to direction)))
        }

        Regex("""^(?:please )?(?:tap|click|press) (?:on )?(.+)$""").find(t)?.let {
            return Plan("", listOf(a("tap_screen", "label" to it.groupValues[1])))
        }

        Regex("""^(?:please )?(?:type|enter|write) (.+?)(?: on (?:my )?(?:keyboard|screen|text field|message box))?$""").find(t)?.let {
                val value = it.groupValues[1].trim().trim('"')
            if (value.isNotBlank()) return Plan("", listOf(a("type_text", "text" to value)))
        }

        // ---------------- BATTERY ----------------

        if (Regex("""\bbattery\b""").containsMatchIn(t)) {
            return Plan("__battery__")
        }

        // ---------------- TIME ----------------

        if (
            Regex("""what(?:'s| is) the time|what time""")
                .containsMatchIn(t)
        ) {
            return Plan(
                "It's " +
                    DateFormat.getTimeInstance(DateFormat.SHORT)
                        .format(Date()) +
                    "."
            )
        }

        // ---------------- DATE ----------------

        if (
            Regex("""what(?:'s| is) (?:the )?(?:date|day)|today's date""")
                .containsMatchIn(t)
        ) {
            return Plan(
                "Today is " +
                    DateFormat.getDateInstance(DateFormat.FULL)
                        .format(Date()) +
                    "."
            )
        }

        return Plan(
            "I'm offline right now, but I can still handle device commands like flashlight, alarms, timers, volume, brightness, calls and opening apps."
        )
    }
}
