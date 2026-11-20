package com.dec.ai

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

data class BrainResult(val reply: String, val actions: List<Pair<String, JSONObject>>, val online: Boolean)

class Brain(private val ctx: Context) {
    private val prefs = Prefs.get(ctx)
    // Long read timeout: Render's free tier can take ~60s to wake from sleep.
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(75, TimeUnit.SECONDS).build()

    fun canUseCloud() = !prefs.forceOffline && Dec.isOnline(ctx) && prefs.backendUrl.startsWith("https://") && prefs.apiToken.isNotBlank()

    suspend fun ask(text: String, history: List<Msg>): BrainResult = withContext(Dispatchers.IO) {
        if (canUseCloud()) {
            try { return@withContext cloud(text, history) } catch (e: Exception) {
                Log.w("DEC", "cloud failed, using offline: ${e.message}")
                Dec.notice.value = "cloud failed: ${e.message}"
            }
        }
        offline(text)
    }

    private fun offline(text: String): BrainResult {
        val plan = OfflineParser.parse(text, prefs)
        val reply = if (plan.reply == "__battery__") Executor.batteryText(ctx) else plan.reply
        return BrainResult(reply, plan.actions, false)
    }

    private fun cloud(text: String, history: List<Msg>): BrainResult {
        val body = JSONObject()
            .put("text", text).put("session_id", prefs.sessionId)
            .put("personality", prefs.personality).put("custom_instructions", prefs.customInstructions)
            .put("history", JSONArray().also { a -> history.forEach { a.put(JSONObject().put("role", if (it.fromUser) "user" else "assistant").put("content", it.text)) } })
            .put("memories", JSONArray().also { a -> prefs.memories().forEach { a.put(JSONObject().put("k", it.k).put("v", it.v)) } })
            .put("device", deviceContext())
        val req = Request.Builder().url(prefs.backendUrl + "/v1/chat")
            .header("Authorization", "Bearer ${prefs.apiToken}")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(req).execute().use { r ->
            val s = r.body?.string() ?: ""
            if (!r.isSuccessful) throw RuntimeException("backend ${r.code}: ${s.take(120)}")
            val j = JSONObject(s)
            val acts = j.optJSONArray("actions") ?: JSONArray()
            return BrainResult(
                j.optString("reply"),
                (0 until acts.length()).map { acts.getJSONObject(it).let { o -> o.getString("tool") to (o.optJSONObject("args") ?: JSONObject()) } },
                true
            )
        }
    }

    private fun deviceContext(): JSONObject {
        val bat = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bat?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = bat?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = bat?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val net = when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
            else -> "other"
        }
        return JSONObject()
            .put("time_iso", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date()))
            .put("timezone", TimeZone.getDefault().id)
            .put("battery_percent", if (level >= 0) level * 100 / scale else JSONObject.NULL)
            .put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
            .put("network", net)
    }
}
