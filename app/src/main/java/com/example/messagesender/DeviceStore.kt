package com.example.messagesender

import android.content.Context
import android.provider.Settings
import org.json.JSONObject

/**
 * Single source of truth on the device. Holds three things in SharedPreferences:
 *
 *  1. Identity — the server URL, device id and secret obtained when the phone
 *     paired with a slot by scanning the mini-app QR code.
 *  2. Desired state — everything the server pushes on each /sync: whether the
 *     device should run, its active flag, token validity, the work session id
 *     and the full sending configuration (the same settings that used to live
 *     in the app, now entered in the Telegram mini-app).
 *  3. Runtime state for the current work session — sent/trigger counters, the
 *     "paused waiting for успешно" flag, the window-override flag and a
 *     "session finished" flag.
 *
 * [SenderService] and [SmsReceiver] both read/write it so they share one state
 * across process restarts.
 */
object DeviceStore {

    private const val PREFS = "device"

    // Identity
    private const val K_SERVER = "server_url"
    private const val K_DEVICE_ID = "device_id"
    private const val K_SECRET = "secret"
    private const val K_NAME = "device_name"

    // Desired state (from server)
    private const val K_VERSION = "version"
    private const val K_RUN = "run"
    private const val K_ACTIVE = "active"
    private const val K_GLOBAL = "global_on"
    private const val K_TOKEN_VALID = "token_valid"
    private const val K_WORK_SESSION = "work_session"

    // Config
    private const val K_PHONE = "cfg_phone"
    private const val K_MSG1 = "cfg_msg1"
    private const val K_MSG2 = "cfg_msg2"
    private const val K_INTERVAL = "cfg_interval_sec"
    private const val K_WINDOWS = "cfg_windows"
    private const val K_REPEAT = "cfg_repeat"
    private const val K_START_AT = "cfg_start_at"
    private const val K_LIMIT = "cfg_trigger_limit"
    private const val K_STOP_WORD = "cfg_stop_word"
    private const val K_RESUME_WORD = "cfg_resume_word"
    private const val K_REPLY = "cfg_reply"

    // Runtime session state
    private const val K_LAST_SESSION = "rt_last_session"
    private const val K_TRIGGER_COUNT = "rt_trigger_count"
    private const val K_PAUSED = "rt_paused"
    private const val K_OVERRIDE = "rt_override"
    private const val K_SESSION_DONE = "rt_session_done"

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
            // Fresh pairing: clear any stale desired/runtime state.
            .putString(K_VERSION, "")
            .putBoolean(K_RUN, false)
            .remove(K_LAST_SESSION)
            .apply()
    }

    fun clearPairing(c: Context) {
        p(c).edit().clear().apply()
    }

    @Suppress("HardwareIds")
    fun hardwareId(c: Context): String =
        Settings.Secure.getString(c.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"

    // --- Desired state ---
    fun version(c: Context) = p(c).getString(K_VERSION, "").orEmpty()
    fun run(c: Context) = p(c).getBoolean(K_RUN, false)
    fun active(c: Context) = p(c).getBoolean(K_ACTIVE, false)
    fun globalOn(c: Context) = p(c).getBoolean(K_GLOBAL, false)
    fun tokenValid(c: Context) = p(c).getBoolean(K_TOKEN_VALID, false)

    // --- Config accessors ---
    fun phone(c: Context) = p(c).getString(K_PHONE, "").orEmpty()
    fun message(c: Context): String {
        val a = p(c).getString(K_MSG1, "").orEmpty().trim()
        val b = p(c).getString(K_MSG2, "").orEmpty().trim()
        return listOf(a, b).filter { it.isNotBlank() }.joinToString(" ")
    }
    fun intervalMs(c: Context): Long =
        (p(c).getInt(K_INTERVAL, 15).coerceAtLeast(1)) * 1000L
    fun windows(c: Context): List<Window> =
        ScheduleWindows.parse(p(c).getString(K_WINDOWS, "").orEmpty())
    fun repeatDaily(c: Context) = p(c).getBoolean(K_REPEAT, false)
    fun startAtMillis(c: Context) = p(c).getLong(K_START_AT, 0L)
    fun triggerLimit(c: Context) = p(c).getInt(K_LIMIT, 0)
    fun stopWord(c: Context) = p(c).getString(K_STOP_WORD, "символ").orEmpty()
    fun resumeWord(c: Context) = p(c).getString(K_RESUME_WORD, "успешно").orEmpty()
    fun replyText(c: Context) = p(c).getString(K_REPLY, "Ок").orEmpty()

    fun hasSendConfig(c: Context) = phone(c).isNotBlank() && message(c).isNotBlank()

    // --- Runtime session state ---
    fun triggerCount(c: Context) = p(c).getInt(K_TRIGGER_COUNT, 0)
    fun setTriggerCount(c: Context, v: Int) = p(c).edit().putInt(K_TRIGGER_COUNT, v).apply()
    fun isPaused(c: Context) = p(c).getBoolean(K_PAUSED, false)
    fun setPaused(c: Context, v: Boolean) = p(c).edit().putBoolean(K_PAUSED, v).apply()
    fun isOverride(c: Context) = p(c).getBoolean(K_OVERRIDE, false)
    fun setOverride(c: Context, v: Boolean) = p(c).edit().putBoolean(K_OVERRIDE, v).apply()
    fun isSessionDone(c: Context) = p(c).getBoolean(K_SESSION_DONE, false)
    fun setSessionDone(c: Context, v: Boolean) = p(c).edit().putBoolean(K_SESSION_DONE, v).apply()

    /**
     * Applies a /sync response. Returns true when the work session changed and a
     * fresh run started (so the caller can reset live counters and send at once).
     */
    fun applySync(c: Context, json: JSONObject): Boolean {
        val e = p(c).edit()
        e.putString(K_VERSION, json.optString("version"))
        val run = json.optBoolean("run", false)
        e.putBoolean(K_RUN, run)
        e.putBoolean(K_ACTIVE, json.optBoolean("active", false))
        e.putBoolean(K_GLOBAL, json.optBoolean("globalOn", false))
        e.putBoolean(K_TOKEN_VALID, json.optBoolean("tokenValid", false))
        val workSession = json.optString("workSession")
        e.putString(K_WORK_SESSION, workSession)
        e.putString(K_STOP_WORD, json.optString("stopWord", "символ"))
        e.putString(K_RESUME_WORD, json.optString("resumeWord", "успешно"))
        e.putString(K_REPLY, json.optString("replyText", "Ок"))

        val cfg = json.optJSONObject("config")
        if (cfg != null) {
            e.putString(K_PHONE, cfg.optString("phone"))
            e.putString(K_MSG1, cfg.optString("message1"))
            e.putString(K_MSG2, cfg.optString("message2"))
            e.putInt(K_INTERVAL, cfg.optInt("intervalSec", 15).coerceAtLeast(1))
            val wins = mutableListOf<Window>()
            val arr = cfg.optJSONArray("windows")
            if (arr != null) for (i in 0 until arr.length()) {
                val w = arr.optJSONObject(i) ?: continue
                wins.add(Window(w.optInt("startSec"), w.optInt("endSec")))
            }
            e.putString(K_WINDOWS, ScheduleWindows.serialize(wins))
            e.putBoolean(K_REPEAT, cfg.optBoolean("repeatDaily", false))
            e.putLong(K_START_AT, cfg.optLong("startAtMillis", 0L))
            e.putInt(K_LIMIT, cfg.optInt("triggerLimit", 0))
        }

        val lastSession = p(c).getString(K_LAST_SESSION, "").orEmpty()
        val startedFresh = run && workSession.isNotBlank() && workSession != lastSession
        if (startedFresh) {
            e.putString(K_LAST_SESSION, workSession)
            e.putInt(K_TRIGGER_COUNT, 0)
            e.putBoolean(K_PAUSED, false)
            e.putBoolean(K_OVERRIDE, false)
            e.putBoolean(K_SESSION_DONE, false)
        }
        e.apply()
        if (startedFresh) SenderStatus.reset()
        return startedFresh
    }
}
