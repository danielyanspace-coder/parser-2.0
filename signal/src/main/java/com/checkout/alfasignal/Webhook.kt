package com.checkout.alfasignal

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Fires the signal webhook via HTTP GET. Runs on a background thread. */
object Webhook {
    private const val TAG = "AlfaSignal"

    /** Blocking GET. Returns the HTTP status code, or -1 on a network error. */
    fun fireGet(url: String): Int {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 15000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "AlfaSignal/1.0")
            }
            val code = conn.responseCode
            // Drain so the connection can be reused/closed cleanly.
            runCatching { (if (code in 200..299) conn.inputStream else conn.errorStream)?.close() }
            conn.disconnect()
            Log.i(TAG, "Webhook GET $url -> $code")
            code
        } catch (e: Exception) {
            Log.e(TAG, "Webhook failed: ${e.message}", e)
            -1
        }
    }

    fun now(): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
}
