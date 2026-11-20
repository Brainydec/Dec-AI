package com.dec.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow

enum class DecState { OFF, STANDBY, LISTENING, THINKING, SPEAKING }

data class Msg(val fromUser: Boolean, val text: String, val time: Long = System.currentTimeMillis())
data class Pending(val question: String, val resolve: (Boolean) -> Unit)

/** Process-wide observable state shared by the service and the UI. */
object Dec {
    val state = MutableStateFlow(DecState.OFF)
    val messages = MutableStateFlow<List<Msg>>(emptyList())
    val level = MutableStateFlow(0f)
    val pending = MutableStateFlow<Pending?>(null)
    val online = MutableStateFlow(false)
    val wakeActive = MutableStateFlow(false)
    val notice = MutableStateFlow("")
    val resumeTick = MutableStateFlow(0)
    val logTick = MutableStateFlow(0)

    fun addMessage(m: Msg) {
        messages.value = (messages.value + m).takeLast(200)
    }

    fun isOnline(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
