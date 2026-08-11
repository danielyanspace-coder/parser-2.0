package com.example.messagesender

/** In-memory live status, surfaced in the notification and the main screen. */
object SenderStatus {
    @Volatile var sentCount: Int = 0
    @Volatile var lastError: String? = null
    @Volatile var offline: Boolean = false
    @Volatile var lastSyncAt: Long = 0L

    fun reset() {
        sentCount = 0
        lastError = null
    }
}
