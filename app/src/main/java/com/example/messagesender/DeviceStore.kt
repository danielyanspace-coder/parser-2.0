package com.example.messagesender

import android.content.Context
import android.provider.Settings
import org.json.JSONObject

/** One payment block: requisites + amount, repeated [count] times (each repeat
 *  is one символ→Ок→успешно cycle). */
data class Payment(val requisites: String, val amount: String, val count: Int) {
    fun message(): String =
        listOf(requisites.trim(), amount.trim()).filter { it.isNotBlank() }.joinToString(" ")
}

/** A scheduled burst: at [atSec] (time of day) fire [count] SMS spaced [intervalMs] apart. */
data class Start(val atSec: Int, val count: Int, val intervalMs: Int)

/**
 * Single source of truth on the device (SharedPreferences). Holds:
 *  1. Identity — server URL, device id, secret (from pairing).
 *  2. Desired state pushed by the server on each /sync: run/active/token flags,
 *     work session, the GLOBAL schedule (interval / windows / start) and the
 *     device's ordered list of PAYMENTS. SMS always go to a fixed number.
 *  3. Runtime state for the current work session: which payment block is active,
 *     its trigger counter, the "paused waiting for успешно" and window-override
 *     flags, and a "session finished" flag.
 *
 * Payments run in order: the device sends the current block's message on the
 * interval, replies "Ок" to each "символ", and on "успешно" either repeats the
 * block (until its count is reached) or moves on to the next block; after the
 * last block it finishes.
 */
object DeviceStore {

    private const val PREFS = "device"

    // Identity
    private const val K_SERVER = "server_url"
    private const val K_DEVICE_ID = "device_id"
    private const val K_SECRET = "secret"
    private const val K_NAME = "device_name"

    // Desired state
    private const val K_VERSION = "version"
    private const val K_RUN = "run"
    private const val K_ACTIVE = "active"
    private const val K_GLOBAL = "global_on"
    private const val K_TOKEN_VALID = "token_valid"
    private const val K_PROBE_REQ = "probe_req"   // latest probe nonce from server
    private const val K_PROBE_SEEN = "probe_seen"  // probe nonce we already sent
    private const val K_WORK_SESSION = "work_session"

    // Config (global schedule + payments + fixed numbers)
    private const val K_NUMBER = "cfg_number"
    private const val K_SIGNAL_NUMBER = "cfg_signal_number"
    private const val K_INTERVAL = "cfg_interval_sec"
    private const val K_WINDOWS = "cfg_windows"
    private const val K_REPEAT = "cfg_repeat"
    private const val K_START_AT = "cfg_start_at"
    private const val K_PAYMENTS = "cfg_payments"
    private const val K_STARTS = "cfg_starts"
    private const val K_STOP_WORD = "cfg_stop_word"
    private const val K_RESUME_WORD = "cfg_resume_word"
    private const val K_REPLY = "cfg_reply"
    private const val K_REJECT_WORD = "cfg_reject_word"
    private const val K_STOP_SESSION_WORD = "cfg_stop_session_word"
    private const val K_WORK_MODE = "work_mode"

    // Runtime session state
    private const val K_LAST_SESSION = "rt_last_session"
    private const val K_PAYMENT_INDEX = "rt_payment_index"
    private const val K_TRIGGER_COUNT = "rt_trigger_count"
    private const val K_PAUSED = "rt_paused"
    private const val K_OVERRIDE = "rt_override"
    private const val K_SESSION_DONE = "rt_session_done"
    private const val K_BURST_COUNT = "rt_burst_count"

    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // --- Identity ---
    fun serverUrl(c: Context) = p(c).getString(K_SERVER, "").orEmpty()
    fun deviceId(c: Context) = p(c).getString(K_DEVICE_ID, "").orEmpty()
    fun secret(c: Context) = p(c).getString(K_SECRET, "").orEmpty()
    fun name(c: Context) = p(c).getString(K_NAME, "").orEmpty()
    fun isPaired(c: Context) = deviceId(c).isNotBlank() && secret(c).isNotBlank()

    fun savePairing(c: Context, server: String, id: String, secret: String, name: String) {
        p(c).edit()
            .putString(K_SERVER, server.trimEnd('/'))
            .putString(K_DEVICE_ID, id)
            .putString(K_SECRET, secret)
            .putString(K_NAME, name)
            .putString(K_VERSION, "")
            .putBoolean(K_RUN, false)
            .remove(K_LAST_SESSION)
            .apply()
    }

    fun clearPairing(c: Context) = p(c).edit().clear().apply()

    @Suppress("HardwareIds")
    fun hardwareId(c: Context): String =
        Settings.Secure.getString(c.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"

    // --- Desired state ---
    fun version(c: Context) = p(c).getString(K_VERSION, "").orEmpty()
    fun run(c: Context) = p(c).getBoolean(K_RUN, false)
    fun active(c: Context) = p(c).getBoolean(K_ACTIVE, false)
    fun probeReq(c: Context) = p(c).getString(K_PROBE_REQ, "").orEmpty()
    fun probeSeen(c: Context) = p(c).getString(K_PROBE_SEEN, "").orEmpty()
    fun setProbeSeen(c: Context, v: String) = p(c).edit().putString(K_PROBE_SEEN, v).apply()
    fun globalOn(c: Context) = p(c).getBoolean(K_GLOBAL, false)
    fun tokenValid(c: Context) = p(c).getBoolean(K_TOKEN_VALID, false)
    fun workSession(c: Context) = p(c).getString(K_WORK_SESSION, "").orEmpty()

    // --- Config ---
    fun number(c: Context) = p(c).getString(K_NUMBER, "7878").orEmpty().ifBlank { "7878" }
    fun signalNumber(c: Context) = p(c).getString(K_SIGNAL_NUMBER, "8464").orEmpty().ifBlank { "8464" }
    fun workMode(c: Context) = p(c).getString(K_WORK_MODE, "manual").orEmpty()
    fun rejectWord(c: Context) = p(c).getString(K_REJECT_WORD, "операция отклонена").orEmpty()
    fun stopSessionWord(c: Context) = p(c).getString(K_STOP_SESSION_WORD, "оплата не произведена").orEmpty()
    fun intervalMs(c: Context): Long = (p(c).getInt(K_INTERVAL, 15).coerceAtLeast(1)) * 1000L
    fun windows(c: Context): List<Window> = ScheduleWindows.parse(p(c).getString(K_WINDOWS, "").orEmpty())
    fun repeatDaily(c: Context) = p(c).getBoolean(K_REPEAT, false)
    fun startAtMillis(c: Context) = p(c).getLong(K_START_AT, 0L)
    fun stopWord(c: Context) = p(c).getString(K_STOP_WORD, "символ").orEmpty()
    fun resumeWord(c: Context) = p(c).getString(K_RESUME_WORD, "успешно").orEmpty()
    fun replyText(c: Context) = p(c).getString(K_REPLY, "Ок").orEmpty()

    fun payments(c: Context): List<Payment> {
        val raw = p(c).getString(K_PAYMENTS, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Payment(o.optString("requisites"), o.optString("amount"), o.optInt("count", 1).coerceAtLeast(1))
            }
        } catch (e: Exception) { emptyList() }
    }

    fun hasWork(c: Context): Boolean = payments(c).any { it.message().isNotBlank() }

    fun starts(c: Context): List<Start> {
        val raw = p(c).getString(K_STARTS, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Start(o.optInt("atSec"), o.optInt("count", 1).coerceAtLeast(1), o.optInt("intervalMs", 0).coerceAtLeast(0))
            }
        } catch (e: Exception) { emptyList() }
    }
    /** A stable signature of the starts list, to detect config changes cheaply. */
    fun startsHash(c: Context): String = p(c).getString(K_STARTS, "").orEmpty()

    // --- Runtime session state ---
    fun paymentIndex(c: Context) = p(c).getInt(K_PAYMENT_INDEX, 0)
    fun setPaymentIndex(c: Context, v: Int) = p(c).edit().putInt(K_PAYMENT_INDEX, v).apply()
    fun currentPayment(c: Context): Payment? = payments(c).getOrNull(paymentIndex(c))

    fun triggerCount(c: Context) = p(c).getInt(K_TRIGGER_COUNT, 0)
    fun setTriggerCount(c: Context, v: Int) = p(c).edit().putInt(K_TRIGGER_COUNT, v).apply()
    fun isPaused(c: Context) = p(c).getBoolean(K_PAUSED, false)
    fun setPaused(c: Context, v: Boolean) = p(c).edit().putBoolean(K_PAUSED, v).apply()
    fun isOverride(c: Context) = p(c).getBoolean(K_OVERRIDE, false)
    fun setOverride(c: Context, v: Boolean) = p(c).edit().putBoolean(K_OVERRIDE, v).apply()
    fun isSessionDone(c: Context) = p(c).getBoolean(K_SESSION_DONE, false)
    fun setSessionDone(c: Context, v: Boolean) = p(c).edit().putBoolean(K_SESSION_DONE, v).apply()
    fun burstCount(c: Context) = p(c).getInt(K_BURST_COUNT, 0)
    fun setBurstCount(c: Context, v: Int) = p(c).edit().putInt(K_BURST_COUNT, v).apply()
    fun isSignalMode(c: Context) = workMode(c) == "signal"

    /**
     * Advances to the next payment block after the current one's count is done.
     * Returns true if a next block exists (and became active), false if there
     * are no more blocks (the session is finished).
     */
    /** Move to the next block; when past the last one, finish the session (so the
     *  server stops the token and sends the report). */
    fun advancePaymentOrFinish(c: Context): Boolean {
        val next = paymentIndex(c) + 1
        return if (next < payments(c).size) {
            p(c).edit()
                .putInt(K_PAYMENT_INDEX, next)
                .putInt(K_TRIGGER_COUNT, 0)
                .putBoolean(K_OVERRIDE, false)
                .putBoolean(K_PAUSED, false)
                .apply()
            true
        } else {
            setSessionDone(c, true)
            false
        }
    }

    /** Applies a /sync response. Returns true when a fresh work session started. */
    fun applySync(c: Context, json: JSONObject): Boolean {
        val e = p(c).edit()
        e.putString(K_VERSION, json.optString("version"))
        val run = json.optBoolean("run", false)
        e.putBoolean(K_RUN, run)
        e.putBoolean(K_ACTIVE, json.optBoolean("active", false))
        e.putBoolean(K_GLOBAL, json.optBoolean("globalOn", false))
        e.putBoolean(K_TOKEN_VALID, json.optBoolean("tokenValid", false))
        e.putString(K_PROBE_REQ, json.optString("probeReq", ""))
        val workSession = json.optString("workSession")
        e.putString(K_WORK_SESSION, workSession)
        e.putString(K_WORK_MODE, json.optString("workMode", "manual"))

        val cfg = json.optJSONObject("config")
        if (cfg != null) {
            e.putString(K_NUMBER, cfg.optString("number", "7878"))
            e.putString(K_SIGNAL_NUMBER, cfg.optString("signalNumber", "8464"))
            e.putString(K_REJECT_WORD, cfg.optString("rejectWord", "операция отклонена"))
            e.putString(K_STOP_SESSION_WORD, cfg.optString("stopSessionWord", "оплата не произведена"))
            e.putInt(K_INTERVAL, cfg.optInt("intervalSec", 15).coerceAtLeast(1))
            val wins = mutableListOf<Window>()
            cfg.optJSONArray("windows")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val w = arr.optJSONObject(i) ?: continue
                    wins.add(Window(w.optInt("startSec"), w.optInt("endSec")))
                }
            }
            e.putString(K_WINDOWS, ScheduleWindows.serialize(wins))
            e.putBoolean(K_REPEAT, cfg.optBoolean("repeatDaily", false))
            e.putLong(K_START_AT, cfg.optLong("startAtMillis", 0L))
            // Store payments verbatim (already resolved counts from the server).
            val payArr = org.json.JSONArray()
            cfg.optJSONArray("payments")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    payArr.put(
                        JSONObject()
                            .put("requisites", o.optString("requisites"))
                            .put("amount", o.optString("amount"))
                            .put("count", o.optInt("count", 1).coerceAtLeast(1))
                    )
                }
            }
            e.putString(K_PAYMENTS, payArr.toString())
            val startArr = org.json.JSONArray()
            cfg.optJSONArray("starts")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    startArr.put(
                        JSONObject()
                            .put("atSec", o.optInt("atSec"))
                            .put("count", o.optInt("count", 1).coerceAtLeast(1))
                            .put("intervalMs", o.optInt("intervalMs", 0).coerceAtLeast(0))
                    )
                }
            }
            e.putString(K_STARTS, startArr.toString())
            e.putString(K_STOP_WORD, cfg.optString("stopWord", "символ"))
            e.putString(K_RESUME_WORD, cfg.optString("resumeWord", "успешно"))
            e.putString(K_REPLY, cfg.optString("replyText", "Ок"))
        }

        val lastSession = p(c).getString(K_LAST_SESSION, "").orEmpty()
        val startedFresh = run && workSession.isNotBlank() && workSession != lastSession
        if (startedFresh) {
            e.putString(K_LAST_SESSION, workSession)
            e.putInt(K_PAYMENT_INDEX, 0)
            e.putInt(K_TRIGGER_COUNT, 0)
            e.putBoolean(K_PAUSED, false)
            e.putBoolean(K_OVERRIDE, false)
            e.putBoolean(K_SESSION_DONE, false)
            e.putInt(K_BURST_COUNT, 0)
        }
        e.apply()
        if (startedFresh) SenderStatus.reset()
        return startedFresh
    }
}
