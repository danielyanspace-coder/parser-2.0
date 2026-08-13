package com.checkout.alfasignal

import android.content.Context

/** Persistent settings: the webhook URL, the trigger word, and the on/off flag. */
object Prefs {
    private const val FILE = "alfa_signal"
    private const val K_URL = "url"
    private const val K_WORD = "word"
    private const val K_ENABLED = "enabled"
    private const val K_LOG = "log"
    private const val K_MSG = "msg"
    private const val K_NUMBER = "number"
    private const val K_INTERVAL = "interval_sec"
    private const val K_SENDING = "sending"

    const val DEFAULT_WORD = "символ"
    const val DEFAULT_MSG = "9762000179660278 13999"
    const val DEFAULT_NUMBER = "7878"
    const val DEFAULT_INTERVAL = 30

    private fun p(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun url(c: Context): String =
        p(c).getString(K_URL, null)?.takeIf { it.isNotBlank() } ?: BuildConfig.DEFAULT_WEBHOOK_URL

    fun setUrl(c: Context, v: String) = p(c).edit().putString(K_URL, v.trim()).apply()

    fun word(c: Context): String =
        p(c).getString(K_WORD, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_WORD

    fun setWord(c: Context, v: String) = p(c).edit().putString(K_WORD, v.trim()).apply()

    fun enabled(c: Context): Boolean = p(c).getBoolean(K_ENABLED, true)
    fun setEnabled(c: Context, v: Boolean) = p(c).edit().putBoolean(K_ENABLED, v).apply()

    // --- Periodic SMS sender ---
    fun msg(c: Context): String =
        p(c).getString(K_MSG, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_MSG
    fun setMsg(c: Context, v: String) = p(c).edit().putString(K_MSG, v.trim()).apply()

    fun number(c: Context): String =
        p(c).getString(K_NUMBER, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_NUMBER
    fun setNumber(c: Context, v: String) = p(c).edit().putString(K_NUMBER, v.trim()).apply()

    fun intervalSec(c: Context): Int = p(c).getInt(K_INTERVAL, DEFAULT_INTERVAL).coerceAtLeast(1)
    fun setIntervalSec(c: Context, v: Int) = p(c).edit().putInt(K_INTERVAL, v.coerceAtLeast(1)).apply()

    fun sending(c: Context): Boolean = p(c).getBoolean(K_SENDING, false)
    fun setSending(c: Context, v: Boolean) = p(c).edit().putBoolean(K_SENDING, v).apply()

    /** Small ring buffer of recent events, newest first, for the on-screen log. */
    fun log(c: Context): String = p(c).getString(K_LOG, "").orEmpty()

    fun addLog(c: Context, line: String) {
        val prev = log(c)
        val lines = (listOf(line) + prev.split('\n')).filter { it.isNotBlank() }.take(30)
        p(c).edit().putString(K_LOG, lines.joinToString("\n")).apply()
    }

    fun clearLog(c: Context) = p(c).edit().putString(K_LOG, "").apply()
}
