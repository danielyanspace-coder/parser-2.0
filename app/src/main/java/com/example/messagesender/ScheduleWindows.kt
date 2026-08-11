package com.example.messagesender

import java.util.Calendar

/** A time-of-day window [startSec, endSec) measured in seconds since midnight. */
data class Window(val startSec: Int, val endSec: Int)

/** Serialization + "is now inside a window" helpers for the send windows. */
object ScheduleWindows {

    fun serialize(list: List<Window>): String =
        list.joinToString(";") { "${it.startSec}-${it.endSec}" }

    fun parse(s: String): List<Window> {
        if (s.isBlank()) return emptyList()
        return s.split(";").mapNotNull { part ->
            val nums = part.split("-")
            val a = nums.getOrNull(0)?.toIntOrNull()
            val b = nums.getOrNull(1)?.toIntOrNull()
            if (a != null && b != null) Window(a, b) else null
        }
    }

    fun secondOfDay(cal: Calendar): Int =
        cal.get(Calendar.HOUR_OF_DAY) * 3600 +
            cal.get(Calendar.MINUTE) * 60 +
            cal.get(Calendar.SECOND)

    fun insideAny(windows: List<Window>, cal: Calendar): Boolean {
        if (windows.isEmpty()) return false
        val now = secondOfDay(cal)
        return windows.any { w ->
            if (w.startSec <= w.endSec) {
                now >= w.startSec && now < w.endSec
            } else {
                // Wraps past midnight (e.g. 23:30 – 00:10).
                now >= w.startSec || now < w.endSec
            }
        }
    }

    /** True when the current time is after the end of every (non-wrapping) window. */
    fun pastAllWindowsToday(windows: List<Window>, cal: Calendar): Boolean {
        if (windows.isEmpty()) return false
        if (windows.any { it.startSec > it.endSec }) return false // has a wrap; never "past"
        val now = secondOfDay(cal)
        return windows.all { now >= it.endSec }
    }
}
