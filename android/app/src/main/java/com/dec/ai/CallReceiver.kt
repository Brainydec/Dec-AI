package com.dec.ai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager

/** Announces who is calling (if DEC is running and Answer-calls is enabled). Never answers by itself. */
class CallReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        if (intent.getStringExtra(TelephonyManager.EXTRA_STATE) != TelephonyManager.EXTRA_STATE_RINGING) return
        val prefs = Prefs.get(ctx)
        if (prefs.emergencyStop || !prefs.toolEnabled(Tool.ANSWER_CALL) || Dec.state.value == DecState.OFF) return
        val now = System.currentTimeMillis()
        if (now - last < 3000) return
        last = now
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        val who = number?.let { Contacts.nameFor(ctx, it) ?: it } ?: "an unknown number"
        try {
            ctx.startService(Intent(ctx, DecService::class.java).setAction(DecService.ACTION_INCOMING).putExtra("who", who))
        } catch (_: Exception) {}
    }

    companion object { @Volatile private var last = 0L }
}
