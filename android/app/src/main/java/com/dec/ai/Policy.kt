package com.dec.ai

import android.Manifest

/** A = harmless, runs immediately once Android permission is granted. B = sensitive, always asks first. */
enum class Cat { A, B }

enum class Tool(
    val id: String, val label: String, val cat: Cat, val why: String, val perms: List<String> = emptyList()
) {
    FLASHLIGHT("flashlight", "Flashlight", Cat.A, "Turn the torch on or off."),
    VOLUME("volume", "Volume", Cat.A, "Change media volume."),
    BRIGHTNESS("brightness", "Brightness", Cat.A, "Change screen brightness (needs 'Modify system settings')."),
    OPEN_APP("open_app", "Open apps", Cat.A, "Launch installed apps and the browser."),
    ACCESSIBILITY_SETTINGS("accessibility_settings", "Accessibility setup", Cat.A, "Open Android Accessibility settings so you can enable DEC."),
    SCROLL_SCREEN("scroll_screen", "Screen scrolling", Cat.A, "Scroll the currently visible screen."),
    READ_SCREEN("read_screen", "Read screen", Cat.A, "Read visible, non-password screen text."),
    PRESS_BACK("press_back", "Back navigation", Cat.A, "Press Android Back."),
    TAP_SCREEN("tap_screen", "Tap screen", Cat.B, "Tap a named visible control. Always asks first."),
    TYPE_TEXT("type_text", "Type text", Cat.B, "Type into the currently focused field. Always asks first."),
    SET_ALARM("set_alarm", "Alarms", Cat.A, "Create alarms in your clock app."),
    SET_TIMER("set_timer", "Timers", Cat.A, "Create timers in your clock app."),
    REMEMBER("remember", "Memory", Cat.A, "Save or forget facts you ask DEC to remember."),
    CALL("call", "Make calls", Cat.B, "Place a phone call. Always asks first.",
        listOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_CONTACTS)),
    ANSWER_CALL("answer_call", "Answer calls", Cat.B, "Answer a ringing call. Always asks first.",
        listOf(Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS)),
    REJECT_CALL("reject_call", "Reject calls", Cat.B, "Reject a ringing call. Always asks first.",
        listOf(Manifest.permission.ANSWER_PHONE_CALLS)),
    SEND_SMS("send_sms", "Send SMS", Cat.B, "Send a text message. Always asks first.",
        listOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_CONTACTS)),
    READ_SMS("read_sms", "Read SMS", Cat.B, "Read your latest texts aloud. Always asks first.",
        listOf(Manifest.permission.READ_SMS, Manifest.permission.READ_CONTACTS)),
    WHATSAPP("whatsapp", "WhatsApp message", Cat.B, "Open WhatsApp with a drafted message. Always asks first; you tap Send.",
        listOf(Manifest.permission.READ_CONTACTS));

    companion object {
        fun byId(id: String) = values().firstOrNull { it.id == id }
        /** "forget" shares the REMEMBER switch. */
        fun forAction(id: String) = if (id == "forget") REMEMBER else byId(id)
    }
}
