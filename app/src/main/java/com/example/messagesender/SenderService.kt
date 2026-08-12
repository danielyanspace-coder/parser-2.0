package com.example.messagesender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.Calendar
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The always-on engine. While the phone is paired it keeps a foreground service
 * running with two cooperating parts:
 *
 *  - A **sync thread** that long-polls the server for the desired state (run
 *    flag + configuration) and reports status. When the mini-app flips "put all
 *    devices to work", the long-poll returns immediately and the service starts
 *    sending at once — no polling delay.
 *  - A **send loop** on a timer thread that, while run==true, sends SMS on the
 *    configured interval inside the configured windows / start time, pauses
 *    while waiting for "успешно", and stops at the trigger limit. A partial wake
 *    lock keeps it ticking with the screen off.
 */
class SenderService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var syncThread: Thread? = null
    @Volatile private var stopping = false

    private var sendExec: ScheduledExecutorService? = null
    private var pendingTick: ScheduledFuture<*>? = null
    private val tickLock = Any()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification())

        if (!DeviceStore.isPaired(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        acquireWakeLock()
        startSyncThread()
        if (intent?.action == ACTION_KICK) {
            scheduleTick(0)
        } else if (sendExec == null) {
            sendExec = Executors.newSingleThreadScheduledExecutor()
            scheduleTick(0)
        }
        return START_STICKY
    }

    // --- Sync thread ---

    private fun startSyncThread() {
        if (syncThread?.isAlive == true) return
        stopping = false
        syncThread = Thread {
            while (!stopping) {
                if (!DeviceStore.isPaired(this)) break
                when (ControlClient.sync(this, waitForChange = true)) {
                    ControlClient.SyncResult.APPLIED -> {
                        updateNotification()
                        // Instant start / config change: kick the send loop now.
                        if (DeviceStore.run(this)) scheduleTick(0)
                    }
                    ControlClient.SyncResult.UNAUTHORIZED -> {
                        // The slot was re-paired to another phone. Unpair locally.
                        Log.i(TAG, "Unauthorized; clearing pairing")
                        DeviceStore.clearPairing(this)
                        shutdown()
                        stopSelf()
                        return@Thread
                    }
                    ControlClient.SyncResult.ERROR -> {
                        updateNotification()
                        sleep(4000)
                    }
                }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun sleep(ms: Long) { try { Thread.sleep(ms) } catch (e: InterruptedException) {} }

    // --- Send loop ---

    private fun scheduleTick(delayMs: Long) {
        synchronized(tickLock) {
            val exec = sendExec ?: Executors.newSingleThreadScheduledExecutor().also { sendExec = it }
            if (exec.isShutdown) return
            pendingTick?.cancel(false)
            pendingTick = exec.schedule(
                { runCatching { tick() }.onFailure { Log.e(TAG, "tick error", it) } },
                delayMs, TimeUnit.MILLISECONDS
            )
        }
    }

    private fun tick() {
        if (stopping || !DeviceStore.isPaired(this)) return

        val running = DeviceStore.run(this)
        if (!running || DeviceStore.isSessionDone(this)) {
            reschedule(IDLE_MS)
            return
        }

        // Paused while waiting for "успешно" (advancement happens on успешно).
        if (DeviceStore.isPaused(this)) {
            reschedule(IDLE_MS)
            return
        }

        val payment = DeviceStore.currentPayment(this)
        if (payment == null || payment.message().isBlank()) {
            // No (more) payment blocks to send.
            finishSession()
            reschedule(IDLE_MS)
            return
        }

        val now = Calendar.getInstance()
        val windows = DeviceStore.windows(this)
        val override = DeviceStore.isOverride(this)
        val allowed = when {
            override -> true
            windows.isEmpty() -> now.timeInMillis >= DeviceStore.startAtMillis(this)
            else -> ScheduleWindows.insideAny(windows, now)
        }

        if (allowed) {
            sendOnce(payment)
            reschedule(DeviceStore.intervalMs(this))
        } else {
            if (windows.isNotEmpty() && !DeviceStore.repeatDaily(this) &&
                ScheduleWindows.pastAllWindowsToday(windows, now)
            ) {
                finishSession()
                reschedule(IDLE_MS)
                return
            }
            reschedule(IDLE_MS)
        }
    }

    private fun reschedule(delayMs: Long) {
        if (!stopping) scheduleTick(delayMs)
    }

    /** Marks the session finished and pushes status so the server can report. */
    private fun finishSession() {
        if (DeviceStore.isSessionDone(this)) return
        DeviceStore.setSessionDone(this, true)
        updateNotification()
        Thread { ControlClient.sync(this, waitForChange = false) }.apply { isDaemon = true }.start()
    }

    private fun sendOnce(payment: Payment) {
        try {
            val sms = smsManager()
            val number = DeviceStore.number(this) // fixed recipient, e.g. 7878
            val message = payment.message()
            val parts = sms.divideMessage(message)
            if (parts.size > 1) {
                sms.sendMultipartTextMessage(number, null, parts, null, null)
            } else {
                sms.sendTextMessage(number, null, message, null, null)
            }
            SenderStatus.sentCount += 1
            SenderStatus.lastError = null
            Log.i(TAG, "Sent SMS #${SenderStatus.sentCount} to $number")
            updateNotification()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send SMS", e)
            SenderStatus.lastError = e.message ?: e.javaClass.simpleName
        }
    }

    private fun smsManager(): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }

    // --- Wake lock ---

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AlfaSms::sender").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    // --- Notification ---

    private fun statusLine(): String {
        if (!DeviceStore.isPaired(this)) return getString(R.string.not_paired)
        if (!DeviceStore.tokenValid(this)) return getString(R.string.state_token_invalid)
        if (SenderStatus.offline) return getString(R.string.state_offline)
        if (!DeviceStore.active(this)) return getString(R.string.state_inactive)
        if (!DeviceStore.run(this)) return getString(R.string.state_stopped)
        if (DeviceStore.isPaused(this)) return getString(R.string.state_paused)
        return getString(R.string.state_working, SenderStatus.sentCount)
    }

    private fun buildNotification(): Notification {
        createChannel()
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val name = DeviceStore.name(this).ifBlank { getString(R.string.app_name) }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_text, name))
            .setContentText(statusLine())
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPendingIntent)
            .build()
    }

    private fun updateNotification() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun shutdown() {
        stopping = true
        syncThread?.interrupt()
        syncThread = null
        synchronized(tickLock) {
            pendingTick?.cancel(false)
            sendExec?.shutdownNow()
            sendExec = null
        }
        releaseWakeLock()
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SenderService"
        const val CHANNEL_ID = "sms_sender_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.example.messagesender.ACTION_STOP"
        const val ACTION_KICK = "com.example.messagesender.ACTION_KICK"

        /** How often to re-check while idle (stopped, paused, outside a window). */
        private const val IDLE_MS = 8_000L

        fun start(context: Context) {
            val i = Intent(context, SenderService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, i)
        }

        fun kick(context: Context) {
            val i = Intent(context, SenderService::class.java).apply { action = ACTION_KICK }
            androidx.core.content.ContextCompat.startForegroundService(context, i)
        }
    }
}
