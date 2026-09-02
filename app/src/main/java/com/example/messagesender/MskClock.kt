package com.example.messagesender

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.TimeZone

/**
 * Precise Moscow-time source for "Метод Форс".
 *
 * The whole point is that xx:12:59 / xx:59:59 must be hit to the second even if
 * the phone's own clock is wrong. Two guarantees:
 *
 *  1. **Moscow wall-clock is derived, never trusted from the device time zone.**
 *     Epoch millis are absolute (time-zone independent), and Moscow is a fixed
 *     UTC+3 with no daylight saving since 2014 — so we always read the wall clock
 *     through the "Europe/Moscow" zone, regardless of how the phone is set.
 *
 *  2. **The epoch itself is corrected against the server's NTP-backed clock.**
 *     Every /sync carries `serverNowMs`, and [syncHttp] can also hit `/api/time`
 *     directly; we keep the offset between that authoritative "now" and the local
 *     one, so a phone whose clock drifted by minutes still fires on time.
 */
object MskClock {

    private const val TAG = "MskClock"
    private val MSK: TimeZone = TimeZone.getTimeZone("Europe/Moscow")

    // trueEpochMs = System.currentTimeMillis() + offsetMs
    @Volatile private var offsetMs: Long = 0L
    @Volatile private var haveOffset: Boolean = false

    /** Feed the server's wall clock (from /sync `serverNowMs` or /api/time). */
    fun updateFromServer(serverNowMs: Long, roundTripMs: Long = 0L) {
        if (serverNowMs <= 0L) return
        // Assume the server timestamp was sampled ~half a round-trip ago.
        val corrected = serverNowMs + roundTripMs / 2
        offsetMs = corrected - System.currentTimeMillis()
        haveOffset = true
    }

    /** The best estimate of the real epoch (server-corrected when we have it). */
    fun trueEpoch(): Long = System.currentTimeMillis() + offsetMs

    fun hasServerTime(): Boolean = haveOffset

    /** A Calendar positioned at the true epoch, in Moscow time. */
    fun mskCalendar(): Calendar = Calendar.getInstance(MSK).apply { timeInMillis = trueEpoch() }

    /** Seconds elapsed within the current Moscow hour (0..3599). */
    fun secondOfHour(cal: Calendar = mskCalendar()): Int =
        cal.get(Calendar.MINUTE) * 60 + cal.get(Calendar.SECOND)

    /** "HH:MM:SS" Moscow, for logs / status. */
    fun mskHms(cal: Calendar = mskCalendar()): String {
        fun p(n: Int) = n.toString().padStart(2, '0')
        return "${p(cal.get(Calendar.HOUR_OF_DAY))}:${p(cal.get(Calendar.MINUTE))}:${p(cal.get(Calendar.SECOND))}"
    }

    /**
     * The true-epoch millis of the next moment whose Moscow minute:second equals
     * [fireSecOfHour] (0..3599). If that moment already passed this hour, returns
     * the one in the next hour.
     */
    fun nextFireEpoch(fireSecOfHour: Int): Long {
        val cal = mskCalendar()
        val ms = cal.get(Calendar.MILLISECOND)
        val curSec = secondOfHour(cal)
        val topOfHour = trueEpoch() - (curSec * 1000L + ms)
        var target = topOfHour + fireSecOfHour * 1000L
        if (target <= trueEpoch()) target += 3_600_000L
        return target
    }

    /** Blocking, precise wait until [targetTrueEpoch]; returns false if interrupted. */
    fun sleepUntil(targetTrueEpoch: Long): Boolean {
        while (true) {
            val remaining = targetTrueEpoch - trueEpoch()
            if (remaining <= 0L) return true
            try {
                // Coarse sleep down to ~40ms, then a tight spin for the last stretch.
                if (remaining > 60L) Thread.sleep(remaining - 40L) else Thread.sleep(1L)
            } catch (e: InterruptedException) {
                return false
            }
        }
    }

    /** One-shot HTTP sync against the server's precise clock (`/api/time`). */
    fun syncHttp(serverUrl: String) {
        if (serverUrl.isBlank()) return
        try {
            val t0 = System.currentTimeMillis()
            val conn = URL(serverUrl.trimEnd('/') + "/api/time").openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.requestMethod = "GET"
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            conn.disconnect()
            if (code == 200 && !text.isNullOrBlank()) {
                val now = JSONObject(text).optLong("now", 0L)
                val rtt = System.currentTimeMillis() - t0
                updateFromServer(now, rtt)
                Log.i(TAG, "time synced: offset=${offsetMs}ms rtt=${rtt}ms msk=${mskHms()}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "time sync failed: ${e.message}")
        }
    }
}
