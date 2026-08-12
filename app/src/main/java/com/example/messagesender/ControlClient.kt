package com.example.messagesender

import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to the central control server. Two calls:
 *  - [pair]: exchange a scanned pairing code for a device id + secret.
 *  - [sync]: long-poll the desired state (run flag + configuration) and report
 *    the current status. The request blocks on the server until the state
 *    changes or a heartbeat timeout elapses, so "put all devices to work" in
 *    the mini-app reaches the phone within milliseconds.
 */
object ControlClient {

    private const val TAG = "ControlClient"

    enum class SyncResult { APPLIED, UNAUTHORIZED, ERROR }

    data class PairOutcome(val ok: Boolean, val deviceId: String = "", val secret: String = "",
                           val name: String = "", val error: String = "")

    fun pair(serverUrl: String, code: String, hardwareId: String): PairOutcome {
        return try {
            val body = JSONObject()
                .put("code", code)
                .put("hardwareId", hardwareId)
                .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
                .toString()
            val resp = post(serverUrl.trimEnd('/') + "/api/device/pair", body, 20000)
            if (resp.code == 200 && !resp.body.isNullOrBlank()) {
                val j = JSONObject(resp.body)
                PairOutcome(true, j.optString("deviceId"), j.optString("secret"), j.optString("name"))
            } else {
                val err = try { JSONObject(resp.body ?: "{}").optString("error") } catch (e: Exception) { "" }
                PairOutcome(false, error = if (err.isNotBlank()) err else "HTTP ${resp.code}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "pair error", e)
            PairOutcome(false, error = "network")
        }
    }

    /** Blocking long-poll. Applies the response to [DeviceStore]. */
    fun sync(c: Context, waitForChange: Boolean): SyncResult {
        val server = DeviceStore.serverUrl(c)
        val id = DeviceStore.deviceId(c)
        val secret = DeviceStore.secret(c)
        if (server.isBlank() || id.isBlank() || secret.isBlank()) return SyncResult.ERROR

        val status = JSONObject()
            .put("running", DeviceStore.run(c) && !DeviceStore.isPaused(c) && !DeviceStore.isSessionDone(c))
            .put("sentCount", SenderStatus.sentCount)
            .put("paymentIndex", DeviceStore.paymentIndex(c))
            .put("triggerCount", DeviceStore.triggerCount(c))
            .put("paused", DeviceStore.isPaused(c))
            .put("lastError", SenderStatus.lastError ?: JSONObject.NULL)
        val body = JSONObject()
            .put("deviceId", id)
            .put("secret", secret)
            .put("version", DeviceStore.version(c))
            .put("wait", waitForChange)
            .put("status", status)
            .toString()

        // Read timeout must exceed the server heartbeat (25s) when long-polling.
        val readTimeout = if (waitForChange) 40000 else 15000
        return try {
            val resp = post(server.trimEnd('/') + "/api/device/sync", body, readTimeout)
            SenderStatus.lastSyncAt = System.currentTimeMillis()
            when {
                resp.code == 200 && !resp.body.isNullOrBlank() -> {
                    SenderStatus.offline = false
                    DeviceStore.applySync(c, JSONObject(resp.body))
                    SyncResult.APPLIED
                }
                resp.code == 403 -> SyncResult.UNAUTHORIZED
                else -> { SenderStatus.offline = true; SyncResult.ERROR }
            }
        } catch (e: Exception) {
            Log.w(TAG, "sync error: ${e.message}")
            SenderStatus.offline = true
            SyncResult.ERROR
        }
    }

    private data class Resp(val code: Int, val body: String?)

    private fun post(urlStr: String, jsonBody: String, readTimeoutMs: Int): Resp {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = readTimeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { os: OutputStream -> os.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            Resp(code, text)
        } finally {
            conn.disconnect()
        }
    }
}
