
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

class DecService : Service() {

    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var prefs: Prefs
    private lateinit var speech: Speech
    private lateinit var brain: Brain
    private lateinit var executor: Executor
    private lateinit var wake: WakeWord

    private var job: Job? = null
    @Volatile private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        prefs = Prefs.get(this)
        speech = Speech(this)
        brain = Brain(this)

        executor = Executor(this) { question ->
            confirm(question)
        }

        wake = WakeWord(this) {
            scope.launch {
                if (
                    !stopping &&
                    Dec.state.value == DecState.STANDBY &&
                    !prefs.emergencyStop
                ) {
                    startConversation()
                }
            }
        }

        val manager =
            getSystemService(NotificationManager::class.java)

        manager.createNotificationChannel(
            NotificationChannel(
                "dec",
                "DEC assistant",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        try {
            ServiceCompat.startForeground(
                this,
                1,
                notification(),
                if (Build.VERSION.SDK_INT >= 29) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    0
                }
            )
        } catch (e: Exception) {
            Dec.notice.value =
                "Could not start DEC: ${e.message}"
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {

            ACTION_STOP,
            ACTION_ESTOP -> {
                stopping = true
                job?.cancel()
                wake.stop()
                speech.stopSpeaking()
                Dec.pending.value?.resolve(false)
                Dec.wakeActive.value = false
                Dec.state.value = DecState.OFF
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_TALK -> onTap()

            ACTION_INCOMING -> announce(
                intent.getStringExtra("who") ?: "someone"
            )

            else -> {
                if (
                    Dec.state.value == DecState.OFF ||
                    job?.isActive != true
                ) {
                    goStandby()
                }
            }
        }

        return START_STICKY
    }

    private fun notification(): Notification {

        fun serviceIntent(action: String): PendingIntent {
            val intent = Intent(
                this,
                DecService::class.java
            ).setAction(action)

            return PendingIntent.getService(
                this,
                action.hashCode(),
                intent,
                PendingIntent.FLAG_IMMUTABLE or
                    PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or
                PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, "dec")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("DEC is on")
            .setContentText(
                if (wake.configured()) {
                    "Say \"Hey Deck\" or tap Talk"
                } else {
                    "Tap Talk to speak"
                }
            )
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, "Talk", serviceIntent(ACTION_TALK))
            .addAction(0, "Stop", serviceIntent(ACTION_STOP))
            .build()
    }

    private fun online(): Boolean {
        return brain.canUseCloud()
    }

    private fun goStandby() {

        Dec.level.value = 0f

        if (stopping || prefs.emergencyStop) {
            wake.stop()
            Dec.wakeActive.value = false
            Dec.state.value = DecState.OFF
            return
        }

        val error = if (wake.configured()) {
            wake.start()
        } else {
            "Wake model missing. Tap the orb to talk."
        }

        Dec.wakeActive.value = error == null

        if (error != null) {
            Dec.notice.value = error
        } else {
            Dec.notice.value = "Listening for Hey Deck"
        }

        Dec.state.value = DecState.STANDBY
    }

    private fun onTap() {

        if (stopping) return

        when (Dec.state.value) {

            DecState.SPEAKING -> speech.stopSpeaking()

            DecState.LISTENING,
            DecState.THINKING -> {
                job?.cancel()
                wake.stop()
                speech.stopSpeaking()
                goStandby()
            }

            else -> startConversation()
        }
    }

    private fun startConversation() {

        if (stopping || prefs.emergencyStop) return

        job?.cancel()

        job = scope.launch {
            conversation()
        }
    }

    private suspend fun conversation() {

        wake.stop()
        Dec.wakeActive.value = false

        try {
            runCatching {
                ToneGenerator(
                    AudioManager.STREAM_MUSIC,
                    70
                ).startTone(
                    ToneGenerator.TONE_PROP_BEEP,
                    120
                )
            }

            while (
                currentCoroutineContext().isActive &&
                !stopping &&
                !prefs.emergencyStop
            ) {

                Dec.state.value = DecState.LISTENING

                val said = speech.listen(!online()) ?: break

                if (said.isBlank()) break

                if (
                    Regex(
                        "(?i)^(stop|go to sleep|standby|that's all|goodbye|never mind|cancel)\\b"
                    ).containsMatchIn(said.trim())
                ) {
                    say("Going to standby.")
                    break
                }

                handle(said)
            }

        } finally {
            withContext(NonCancellable) {
                goStandby()
            }
        }
    }

    private suspend fun handle(said: String) {

        Dec.addMessage(Msg(true, said))
        Dec.state.value = DecState.THINKING

        val response = brain.ask(
            said,
            Dec.messages.value.dropLast(1).takeLast(8)
        )

        Dec.online.value = response.online

        if (response.reply.isNotBlank()) {
            Dec.addMessage(Msg(false, response.reply))
            say(response.reply)
        }

        for ((tool, args) in response.actions) {

            if (stopping || prefs.emergencyStop) break

            Dec.state.value = DecState.THINKING

            val result = executor.run(
                tool,
                args,
                response.online
            )

            if (result.isNotBlank()) {
                Dec.addMessage(Msg(false, result))
                say(result)
            }
        }
    }

    private suspend fun say(text: String) {

        val previous = Dec.state.value
        Dec.state.value = DecState.SPEAKING

        try {
            speech.speak(text)
        } finally {
            if (Dec.state.value == DecState.SPEAKING) {
                Dec.state.value = previous
            }
        }
    }

    private fun isYes(answer: String?): Boolean {

        if (answer == null) return false

        val positive = Regex(
            "(?i)\\b(yes|yeah|yep|yup|sure|confirm|confirmed|approve|go ahead|do it|okay|ok|please do)\\b"
        )

        val negative = Regex(
            "(?i)\\b(no|don't|do not|cancel|stop|deny|negative)\\b"
        )

        return positive.containsMatchIn(answer) &&
            !negative.containsMatchIn(answer)
    }

    private suspend fun confirm(
        question: String
    ): Boolean = coroutineScope {

        if (stopping || prefs.emergencyStop) return@coroutineScope false

        val decision = CompletableDeferred<Boolean>()

        Dec.pending.value = Pending(question) {
            decision.complete(it)
        }

        Dec.addMessage(Msg(false, question))

        val voice = launch {

            say("$question Say yes or no.")

            if (!decision.isCompleted && !stopping) {

                Dec.state.value = DecState.LISTENING

                val answer = speech.listen(!online())

                Dec.addMessage(
                    Msg(true, answer ?: "(no answer)")
                )

                decision.complete(isYes(answer))
            }
        }

        try {
            decision.await()
        } finally {
            voice.cancel()
            speech.stopSpeaking()
            Dec.pending.value = null
        }
    }

    private fun announce(who: String) {

        if (stopping) return

        job?.cancel()

        job = scope.launch {

            wake.stop()
            Dec.wakeActive.value = false

            try {
                say("Incoming call from $who. Say answer or reject.")

                Dec.state.value = DecState.LISTENING

                val said = speech.listen(!online())

                if (!said.isNullOrBlank() && !stopping) {
                    handle(said)
                }

            } finally {
                withContext(NonCancellable) {
                    goStandby()
                }
            }
        }
    }

    override fun onDestroy() {

        stopping = true
        job?.cancel()
        wake.release()
        speech.shutdown()
        scope.cancel()

        Dec.state.value = DecState.OFF
        Dec.wakeActive.value = false

        super.onDestroy()
    }

    companion object {

        const val ACTION_STOP = "com.dec.ai.STOP"
        const val ACTION_ESTOP = "com.dec.ai.ESTOP"
        const val ACTION_TALK = "com.dec.ai.TALK"
        const val ACTION_INCOMING = "com.dec.ai.INCOMING"
    }
}
