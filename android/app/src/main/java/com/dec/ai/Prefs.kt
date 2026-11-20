package com.dec.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Encrypted settings, memories, switches and action history. API keys never leave this store. */
class Prefs private constructor(ctx: Context) {
    private val sp: SharedPreferences = EncryptedSharedPreferences.create(
        ctx, "dec_secure",
        MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    var backendUrl: String
        get() = sp.getString("backendUrl", "") ?: ""
        set(v) = sp.edit().putString("backendUrl", v.trim().trimEnd('/')).apply()
    var apiToken: String
        get() = sp.getString("apiToken", "") ?: ""
        set(v) = sp.edit().putString("apiToken", v.trim()).apply()
    var picoKey: String
        get() = sp.getString("picoKey", "") ?: ""
        set(v) = sp.edit().putString("picoKey", v.trim()).apply()
    var personality: String
        get() = sp.getString("personality", "friendly, concise, a little witty") ?: ""
        set(v) = sp.edit().putString("personality", v).apply()
    var customInstructions: String
        get() = sp.getString("custom", "") ?: ""
        set(v) = sp.edit().putString("custom", v).apply()
    var countryCode: String
        get() = sp.getString("cc", "") ?: ""
        set(v) = sp.edit().putString("cc", v.filter { it.isDigit() }).apply()
    var ttsRate: Float
        get() = sp.getFloat("ttsRate", 1.0f)
        set(v) = sp.edit().putFloat("ttsRate", v).apply()
    var forceOffline: Boolean
        get() = sp.getBoolean("forceOffline", false)
        set(v) = sp.edit().putBoolean("forceOffline", v).apply()
    var emergencyStop: Boolean
        get() = sp.getBoolean("estop", false)
        set(v) = sp.edit().putBoolean("estop", v).apply()
    val sessionId: String
        get() = sp.getString("session", null) ?: UUID.randomUUID().toString().also { sp.edit().putString("session", it).apply() }

    // ---- per-tool switches (default on) and offline switches (default on) ----
    fun toolEnabled(t: Tool) = sp.getBoolean("tool_${t.id}", true)
    fun setToolEnabled(t: Tool, on: Boolean) = sp.edit().putBoolean("tool_${t.id}", on).apply()
    fun offlineAllowed(t: Tool) = sp.getBoolean("off_${t.id}", true)
    fun setOfflineAllowed(t: Tool, on: Boolean) = sp.edit().putBoolean("off_${t.id}", on).apply()

    // ---- memories ----
    data class Mem(val k: String, val v: String)
    fun memories(): List<Mem> {
        val a = JSONArray(sp.getString("mem", "[]"))
        return (0 until a.length()).map { Mem(a.getJSONObject(it).getString("k"), a.getJSONObject(it).getString("v")) }
    }
    private fun saveMem(l: List<Mem>) {
        val a = JSONArray(); l.forEach { a.put(JSONObject().put("k", it.k).put("v", it.v)) }
        sp.edit().putString("mem", a.toString()).apply()
    }
    /** Returns false (and stores nothing) if the text looks like a password or secret. */
    fun putMemory(k: String, v: String): Boolean {
        if (k.isBlank() || v.isBlank() || looksSecret(k) || looksSecret(v)) return false
        saveMem(memories().filter { !it.k.equals(k.trim(), true) } + Mem(k.trim(), v.trim()))
        return true
    }
    fun deleteMemory(k: String) = saveMem(memories().filter { !it.k.equals(k, true) })
    private fun looksSecret(s: String) = Regex("(?i)password|passcode|\\bpin\\b|otp|cvv|secret|api key|token").containsMatchIn(s)

    // ---- action history ----
    data class Log(val time: Long, val tool: String, val detail: String, val outcome: String)
    fun history(): List<Log> {
        val a = JSONArray(sp.getString("log", "[]"))
        return (0 until a.length()).map {
            val o = a.getJSONObject(it); Log(o.getLong("t"), o.getString("tool"), o.getString("d"), o.getString("o"))
        }.reversed()
    }
    fun addLog(tool: String, detail: String, outcome: String) {
        val a = JSONArray(sp.getString("log", "[]"))
        a.put(JSONObject().put("t", System.currentTimeMillis()).put("tool", tool).put("d", detail).put("o", outcome))
        val keep = JSONArray(); for (i in maxOf(0, a.length() - 200) until a.length()) keep.put(a.get(i))
        sp.edit().putString("log", keep.toString()).apply()
        Dec.logTick.value += 1
    }

    companion object {
        @Volatile private var inst: Prefs? = null
        fun get(ctx: Context): Prefs = inst ?: synchronized(this) { inst ?: Prefs(ctx.applicationContext).also { inst = it } }
    }
}
