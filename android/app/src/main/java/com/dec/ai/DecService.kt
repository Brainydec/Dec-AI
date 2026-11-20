package com.dec.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*

/**
 * Foreground service (microphone type). Standby = Porcupine listens for "Hey Deck".
 * On wake: listen -> think -> act/speak -> keep listening for follow-ups -> back to standby.
 */
class DecService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var prefs: Prefs
    private lateinit var speech: Speech
    private lateinit var brain: Brain
    private lateinit var executor: Executor
    private lateinit var wake: WakeWord
    private var job: Job? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs.get(this)
        speech = Speech(this)
        brain = Brain(this)
        executor = Executor(this) { q -> confirm(q) }
        wake = WakeWord(this) { scope.launch { if (Dec.state.value == DecState.STANDBY) startConversation() } }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("dec", "DEC assistant", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(this, 1, notification(), if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        } catch (e: Exception) {
            Dec.notice.value = "Could not start: ${e.message}"; stopSelf(); return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP, ACTION_ESTOP -> { job?.cancel(); speech.stopSpeaking(); Dec.pending.value?.resolve(false); stopSelf(); return START_NOT_STICKY }
            ACTION_TALK -> onTap()
            ACTION_INCOMING -> announce(intent?.getStringExtra("who") ?: "someone")
            else -> if (Dec.state.value == DecState.OFF || job?.isActive != true) goStandby()
        }
        return START_STICKY
    }

    private fun notification(): Notification {
        fun pi(action: String) = PendingIntent.getService(this, action.hashCode(), Intent(this, DecService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "dec")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("DEC is on")
            .setContentText(if (wake.configured()) "Say \"Hey Deck\" or tap Talk" else "Tap Talk to speak")
            .setContentIntent(open).setOngoing(true)
            .addAction(0, "Talk", pi(ACTION_TALK)).addAction(0, "Stop", pi(ACTION_STOP)).build()
    }

    private fun online() = brain.canUseCloud()

    private fun goStandby() {
        Dec.level.value = 0f
        if (prefs.emergencyStop) { Dec.state.value = DecState.OFF; return }
        val err = if (wake.configured()) wake.start() else "Tap the orb to talk (wake word not set up)"
        Dec.wakeActive.value = err == null
        if (err != null) Dec.notice.value = err
        Dec.state.value = DecState.STANDBY
    }

    /** Tap on orb: barge-in while speaking, cancel while busy, start when idle. */
    private fun onTap() {
        when (Dec.state.value) {
            DecState.SPEAKING -> speech.stopSpeaking()
            DecState.LISTENING, DecState.THINKING -> { job?.cancel(); speech.stopSpeaking(); goStandby() }
            else -> startConversation()
        }
    }

    private fun startConversation() {
        if (prefs.emergencyStop) return
        job?.cancel()
        job = scope.launch { conversation() }
    }

    private suspend fun conversation() {
        wake.stop()
        runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 70).startTone(ToneGenerator.TONE_PROP_BEEP, 120) }
        try {
            while (currentCoroutineContext().isActive && !prefs.emergencyStop) {
                Dec.state.value = DecState.LISTENING
                val said = speech.listen(!online()) ?: break   // silence -> back to standby
                if (said.isBlank()) break
                if (Regex("(?i)^(stop|go to sleep|standby|that'?s all|goodbye|never ?mind|cancel)\\b").containsMatchIn(said.trim())) {
                    say("Going to standby."); break
                }
                handle(said)
            }
        } finally {
            withContext(NonCancellable) { goStandby() }
        }
    }

    private suspend fun handle(said: String) {
        Dec.addMessage(Msg(true, said))
        Dec.state.value = DecState.THINKING
        val r = brain.ask(said, Dec.messages.value.dropLast(1).takeLast(8))
        Dec.online.value = r.online
        if (r.reply.isNotBlank()) { Dec.addMessage(Msg(false, r.reply)); say(r.reply) }
        for ((tool, args) in r.actions) {
            Dec.state.value = DecState.THINKING
            val out = executor.run(tool, args, r.online)
            if (out.isNotBlank()) { Dec.addMessage(Msg(false, out)); say(out) }
        }
    }

    private suspend fun say(text: String) {
        val prev = Dec.state.value
        Dec.state.value = DecState.SPEAKING
        try { speech.speak(text) } finally { if (Dec.state.value == DecState.SPEAKING) Dec.state.value = prev }
    }

    private fun isYes(s: String?) = s != null &&
        Regex("(?i)\\b(yes|yeah|yep|yup|sure|confirm|confirmed|approve|go ahead|do it|okay|ok|please do)\\b").containsMatchIn(s) &&
        !Regex("(?i)\\b(no|don'?t|do not|cancel|stop|deny|negative)\\b").containsMatchIn(s)

    /** Approval gate for Category B. Either the on-screen dialog or a spoken yes/no. Silence = NO. */
    private suspend fun confirm(question: String): Boolean = coroutineScope {
        val d = CompletableDeferred<Boolean>()
        Dec.pending.value = Pending(question) { d.complete(it) }
        Dec.addMessage(Msg(false, question))
        val voice = launch {
            say("$question Say yes or no.")
            if (!d.isCompleted) {
                Dec.state.value = DecState.LISTENING
                val a = speech.listen(!online())
                Dec.addMessage(Msg(true, a ?: "(no answer)"))
                d.complete(isYes(a))
            }
        }
        try { d.await() } finally { voice.cancel(); speech.stopSpeaking(); Dec.pending.value = null }
    }

    private fun announce(who: String) {
        job?.cancel()
        job = scope.launch {
            wake.stop()
            try {
                say("Incoming call from $who. Say answer or reject.")
                Dec.state.value = DecState.LISTENING
                val said = speech.listen(!online())
                if (!said.isNullOrBlank()) handle(said)
            } finally { withContext(NonCancellable) { goStandby() } }
        }
    }

    override fun onDestroy() {
        scope.cancel(); wake.release(); speech.shutdown()
        Dec.state.value = DecState.OFF; Dec.wakeActive.value = false
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.dec.ai.STOP"
        const val ACTION_ESTOP = "com.dec.ai.ESTOP"
        const val ACTION_TALK = "com.dec.ai.TALK"
        const val ACTION_INCOMING = "com.dec.ai.INCOMING"
    }
}
