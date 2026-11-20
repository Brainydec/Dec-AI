package com.dec.ai

import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

data class Plan(val reply: String, val actions: List<Pair<String, JSONObject>> = emptyList())

/** Rule-based command understanding that works with no internet and no model. */
object OfflineParser {
    private fun a(tool: String, vararg kv: Pair<String, Any>) = tool to JSONObject().also { o -> kv.forEach { o.put(it.first, it.second) } }

    fun parse(raw: String, prefs: Prefs): Plan {
        val t = raw.lowercase().trim().trimEnd('.', '!', '?')

        Regex("""remember (?:that )?(?:my )?(.+?) (?:is|are) (.+)""").find(t)?.let {
            return Plan("Okay, I'll remember that.", listOf(a("remember", "k" to it.groupValues[1], "v" to it.groupValues[2])))
        }
        Regex("""forget (?:about )?(?:my )?(.+)""").find(t)?.let {
            return Plan("Forgotten.", listOf(a("forget", "k" to it.groupValues[1])))
        }
        if (Regex("(flashlight|torch)").containsMatchIn(t)) {
            val off = Regex("\\b(off|disable|kill)\\b").containsMatchIn(t)
            return Plan(if (off) "Flashlight off." else "Flashlight on.", listOf(a("flashlight", "on" to !off)))
        }
        if (Regex("\\b(answer|pick up|accept)\\b.*\\bcall\\b|^answer$|^pick up$").containsMatchIn(t))
            return Plan("", listOf(a("answer_call")))
        if (Regex("\\b(reject|decline|hang up|end)\\b.*\\bcall\\b|^reject$|^decline$").containsMatchIn(t))
            return Plan("", listOf(a("reject_call")))
        Regex("""set (?:a |an )?timer (?:for )?(\d+) (second|minute|hour)""").find(t)?.let {
            val n = it.groupValues[1].toInt()
            val s = n * when (it.groupValues[2]) { "hour" -> 3600; "minute" -> 60; else -> 1 }
            return Plan("Timer set.", listOf(a("set_timer", "seconds" to s, "message" to "DEC timer")))
        }
        Regex("""(?:set|wake me).*?(?:alarm )?(?:for|at) (\d{1,2})(?::(\d{2}))?\s*(am|pm|a m|p m)?""").find(t)?.let {
            var h = it.groupValues[1].toInt(); val m = it.groupValues[2].ifEmpty { "0" }.toInt()
            val ap = it.groupValues[3].replace(" ", "")
            if (ap == "pm" && h < 12) h += 12
            if (ap == "am" && h == 12) h = 0
            if (h in 0..23 && m in 0..59) return Plan("Alarm set.", listOf(a("set_alarm", "hour" to h, "minute" to m, "message" to "DEC alarm")))
        }
        Regex("""volume (?:to )?(\d{1,3})""").find(t)?.let {
            return Plan("Volume set.", listOf(a("volume", "level" to it.groupValues[1].toInt().coerceIn(0, 100))))
        }
        if (Regex("volume up|louder|turn it up").containsMatchIn(t)) return Plan("", listOf(a("volume", "direction" to "up")))
        if (Regex("volume down|quieter|turn it down").containsMatchIn(t)) return Plan("", listOf(a("volume", "direction" to "down")))
        Regex("""brightness (?:to )?(\d{1,3})""").find(t)?.let {
            return Plan("Brightness set.", listOf(a("brightness", "level" to it.groupValues[1].toInt().coerceIn(0, 100))))
        }
        Regex("""(?:send (?:a )?(?:text|sms|message) to|text) (.+?) (?:saying|that says|say) (.+)""").find(t)?.let {
            return Plan("", listOf(a("send_sms", "to" to it.groupValues[1], "body" to it.groupValues[2])))
        }
        Regex("""whatsapp (.+?) (?:saying|that says|say) (.+)""").find(t)?.let {
            return Plan("", listOf(a("whatsapp", "to" to it.groupValues[1], "body" to it.groupValues[2])))
        }
        Regex("""(?:call|phone|dial) (.+)""").find(t)?.let {
            return Plan("", listOf(a("call", "contact" to it.groupValues[1].removePrefix("my "))))
        }
        if (Regex("read (?:my )?(?:texts|messages|sms)").containsMatchIn(t)) return Plan("", listOf(a("read_sms", "count" to 3)))
        Regex("""(?:open|launch|start) (.+)""").find(t)?.let {
            return Plan("", listOf(a("open_app", "name" to it.groupValues[1].removePrefix("my ").removePrefix("the "))))
        }
        if (Regex("battery").containsMatchIn(t)) return Plan("__battery__")
        if (Regex("what(?:'s| is) the time|what time").containsMatchIn(t))
            return Plan("It's " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date()) + ".")
        if (Regex("what(?:'s| is) (?:the )?(?:date|day)|today's date").containsMatchIn(t))
            return Plan("Today is " + DateFormat.getDateInstance(DateFormat.FULL).format(Date()) + ".")
        return Plan("I'm offline right now, so I can only handle device commands like flashlight, alarms, timers, volume, calls and opening apps.")
    }
}
