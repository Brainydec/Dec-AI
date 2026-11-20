package com.dec.ai

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/** Text-to-speech (interruptible) and speech-to-text using Android's built-in engines. */
class Speech(private val ctx: Context) {
    private val prefs = Prefs.get(ctx)
    private val ready = CompletableDeferred<Boolean>()
    private var tts: TextToSpeech? = null
    private var currentDone: (() -> Unit)? = null

    init {
        tts = TextToSpeech(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val loc = Locale.getDefault()
                if (tts?.isLanguageAvailable(loc) ?: -1 < 0) tts?.language = Locale.US else tts?.language = loc
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { currentDone?.invoke() }
                    @Deprecated("Deprecated in Java") override fun onError(id: String?) { currentDone?.invoke() }
                    override fun onStop(id: String?, interrupted: Boolean) { currentDone?.invoke() }
                })
            }
            ready.complete(status == TextToSpeech.SUCCESS)
        }
    }

    suspend fun speak(text: String) {
        if (text.isBlank() || !ready.await()) return
        suspendCancellableCoroutine<Unit> { cont ->
            val id = UUID.randomUUID().toString()
            currentDone = { if (cont.isActive) cont.resume(Unit) }
            cont.invokeOnCancellation { tts?.stop() }
            tts?.setSpeechRate(prefs.ttsRate)
            val r = tts?.speak(text.take(3500), TextToSpeech.QUEUE_FLUSH, null, id)
            if (r != TextToSpeech.SUCCESS) currentDone?.invoke()
        }
    }

    /** Barge-in: the user taps the orb while DEC is talking. */
    fun stopSpeaking() { tts?.stop() }

    fun shutdown() { tts?.stop(); tts?.shutdown() }

    /** Listens for one utterance. Returns null on silence/error. Must run on Main (handled here). */
    suspend fun listen(preferOffline: Boolean): String? = withContext(Dispatchers.Main) {
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) { Dec.notice.value = "No speech recognition service installed"; return@withContext null }
        suspendCancellableCoroutine<String?> { cont ->
            val sr = SpeechRecognizer.createSpeechRecognizer(ctx)
            var done = false
            fun finish(r: String?) {
                if (done) return
                done = true; Dec.level.value = 0f
                runCatching { sr.destroy() }
                if (cont.isActive) cont.resume(r)
            }
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rms: Float) { Dec.level.value = ((rms + 2f) / 12f).coerceIn(0f, 1f) }
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) { finish(null) }
                override fun onResults(r: Bundle?) { finish(r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()) }
                override fun onPartialResults(p: Bundle?) {}
                override fun onEvent(t: Int, p: Bundle?) {}
            })
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
            sr.startListening(i)
            cont.invokeOnCancellation { android.os.Handler(android.os.Looper.getMainLooper()).post { if (!done) { done = true; runCatching { sr.destroy() } } } }
        }
    }
}
