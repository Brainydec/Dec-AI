package com.dec.ai

import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback
import android.content.Context
import java.io.File

/**
 * Offline "Hey Deck" detection with Picovoice Porcupine.
 * Needs (1) a free AccessKey and (2) a custom "Hey Deck" .ppn file trained for Android; both are set up in Settings.
 */
class WakeWord(private val ctx: Context, private val onWake: () -> Unit) {
    private var mgr: PorcupineManager? = null
    private val prefs = Prefs.get(ctx)

    fun keywordFile() = File(ctx.filesDir, "hey_deck.ppn")
    fun configured() = keywordFile().exists() && prefs.picoKey.isNotBlank()

    /** Returns null on success, or a human-readable error. */
    fun start(): String? {
        if (!configured()) return "Wake word not set up"
        return try {
            if (mgr == null) {
                mgr = PorcupineManager.Builder()
                    .setAccessKey(prefs.picoKey)
                    .setKeywordPath(keywordFile().absolutePath)
                    .setSensitivity(0.7f)
                    .build(ctx, PorcupineManagerCallback { _ -> onWake() })
            }
            mgr?.start(); null
        } catch (e: Exception) {
            release(); e.message ?: "Wake word error"
        }
    }

    fun stop() { try { mgr?.stop() } catch (_: Exception) {} }

    fun release() { try { mgr?.stop(); mgr?.delete() } catch (_: Exception) {}; mgr = null }
}
