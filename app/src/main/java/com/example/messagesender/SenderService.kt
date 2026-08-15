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
    @Volatile private var resyncNow = false
    private var unauthorizedCount = 0

    private var sendExec: ScheduledExecutorService? = null
    private var pendingTick: ScheduledFuture<*>? = null
    private val tickLock = Any()

    // Scheduled bursts ("залпы") run on their own timer for exact-time firing.
    private var burstExec: ScheduledExecutorService? = null
    private var burstFuture: ScheduledFuture<*>? = null
    @Volatile private var lastStartsHash = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundSafe()

        if (!DeviceStore.isPaired(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        acquireWakeLock()
        startSyncThread()
        // Always keep the tick loop alive — it also runs the connectivity watchdog.
        if (sendExec == null) {
            sendExec = Executors.newSingleThreadScheduledExecutor()
            scheduleTick(0)
        }
        scheduleNextBurst()
        when (intent?.action) {
            ACTION_KICK -> scheduleTick(0)
            ACTION_SYNC_NOW -> forceResync()
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
                // After a forced resync do a quick (wait=false) refresh, then
                // resume long-polling.
                val wait = !resyncNow
                resyncNow = false
                when (ControlClient.sync(this, waitForChange = wait)) {
                    ControlClient.SyncResult.APPLIED -> {
                        unauthorizedCount = 0
                        updateNotification()
                        if (DeviceStore.run(this)) scheduleTick(0)
                        // Re-arm burst timers if the schedule's starts changed.
                        if (DeviceStore.startsHash(this) != lastStartsHash) scheduleNextBurst()
                    }
                    ControlClient.SyncResult.UNAUTHORIZED -> {
                        // A single 403 can be transient (deploy blip, proxy, brief
                        // DB reload). Only unpair after several in a row, so the
                        // device never silently loses its pairing in the background.
                        unauthorizedCount++
                        Log.i(TAG, "Unauthorized ($unauthorizedCount/$MAX_UNAUTHORIZED)")
                        if (unauthorizedCount >= MAX_UNAUTHORIZED) {
                            Log.i(TAG, "Confirmed unauthorized; clearing pairing")
                            DeviceStore.clearPairing(this)
                            shutdown()
                            stopSelf()
                            return@Thread
                        }
                        updateNotification()
                        sleep(ERROR_BACKOFF_MS)
                    }
                    ControlClient.SyncResult.ERROR -> {
                        updateNotification()
                        // Retry immediately if a resync was just requested,
                        // otherwise back off a little.
                        sleep(if (resyncNow) 200 else ERROR_BACKOFF_MS)
                    }
                }
            }
        }.apply { isDaemon = true; start() }
    }

    /**
     * Forces a fresh reconnect: aborts a possibly-stale long-poll (half-open
     * socket after the phone was asleep) and makes the loop reconnect at once.
     */
    private fun forceResync() {
        resyncNow = true
        ControlClient.abort()
        startSyncThread() // restart if it somehow died
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

        // Watchdog: if we haven't heard from the server in a while, the long-poll
        // socket is probably stale (Doze / lost radio). Force a reconnect so the
        // device recovers on its own — critical for signal / schedule to fire.
        val last = SenderStatus.lastSyncAt
        if (last > 0L && System.currentTimeMillis() - last > STALE_MS) {
            Log.i(TAG, "Sync stale; forcing reconnect")
            forceResync()
        }

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

        val signal = DeviceStore.isSignalMode(this)

        // Signal mode: probe the FIRST payment up to 3 times, 1s apart. If no
        // "символ" arrives, give up (this device did its part for the signal).
        if (signal && DeviceStore.paymentIndex(this) == 0 && DeviceStore.triggerCount(this) == 0) {
            val burst = DeviceStore.burstCount(this)
            if (burst >= SIGNAL_BURST_COUNT) {
                finishSession()
                reschedule(IDLE_MS)
                return
            }
            sendOnce(payment)
            DeviceStore.setBurstCount(this, burst + 1)
            reschedule(SIGNAL_BURST_INTERVAL_MS)
            return
        }

        val now = Calendar.getInstance()
        val windows = DeviceStore.windows(this)
        val override = DeviceStore.isOverride(this)
        val allowed = when {
            signal -> true // signal fired outside any window → work now, ignore windows
            override -> true
            windows.isEmpty() -> now.timeInMillis >= DeviceStore.startAtMillis(this)
            else -> ScheduleWindows.insideAny(windows, now)
        }

        if (allowed) {
            // Manual / schedule work keeps sending on the interval until the user
            // turns it off (or the session ends naturally on "оплата не
            // произведена" / all payments done). No automatic cap here.
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

    // --- Scheduled bursts ("залпы по времени") ---

    /**
     * Arms a one-shot timer for the *next* scheduled burst. Bursts fire on the
     * device's own clock so a start of 12:59:00 goes off exactly then, without a
     * server poll in the loop. After a burst runs, this re-arms for the next one.
     */
    private fun scheduleNextBurst() {
        val exec = burstExec ?: Executors.newSingleThreadScheduledExecutor().also { burstExec = it }
        lastStartsHash = DeviceStore.startsHash(this)
        burstFuture?.cancel(false)
        if (stopping || exec.isShutdown) return
        val starts = DeviceStore.starts(this)
        if (starts.isEmpty()) return

        val nowMs = System.currentTimeMillis()
        val startOfDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        var bestDelay = Long.MAX_VALUE
        var bestStart: Start? = null
        for (s in starts) {
            var target = startOfDay + s.atSec * 1000L
            if (target <= nowMs + 500L) target += 86_400_000L // already passed today → tomorrow
            val d = target - nowMs
            if (d < bestDelay) { bestDelay = d; bestStart = s }
        }
        val chosen = bestStart ?: return
        burstFuture = exec.schedule({
            runCatching { runBurst(chosen) }.onFailure { Log.e(TAG, "burst error", it) }
            scheduleNextBurst()
        }, bestDelay, TimeUnit.MILLISECONDS)
    }

    /** Fires one burst: `count` sends of the payments in order, `intervalMs` apart. */
    private fun runBurst(start: Start) {
        if (!DeviceStore.isPaired(this) || !DeviceStore.active(this) || !DeviceStore.tokenValid(this)) return
        val payments = DeviceStore.payments(this).filter { it.message().isNotBlank() }
        if (payments.isEmpty()) return
        for (i in 0 until start.count) {
            if (stopping) break
            sendOnce(payments[i % payments.size])
            if (i < start.count - 1 && start.intervalMs > 0) sleep(start.intervalMs.toLong())
        }
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

    /** Promote to foreground, passing the data-sync type explicitly on Android 10+. */
    private fun startForegroundSafe() {
        val n = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID, n,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            startForeground(NOTIFICATION_ID, n)
        }
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
        burstFuture?.cancel(false)
        burstFuture = null
        burstExec?.shutdownNow()
        burstExec = null
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
        const val ACTION_SYNC_NOW = "com.example.messagesender.ACTION_SYNC_NOW"

        /** How often to re-check while idle (stopped, paused, outside a window). */
        private const val IDLE_MS = 8_000L
        /** Back-off between failed sync attempts. */
        private const val ERROR_BACKOFF_MS = 3_000L
        /** If no server response for this long, the long-poll is stale → reconnect. */
        private const val STALE_MS = 35_000L
        /** Consecutive 403s before we treat the pairing as truly revoked. */
        private const val MAX_UNAUTHORIZED = 4

        /** Signal mode: number of probe sends of the first payment, and spacing. */
        private const val SIGNAL_BURST_COUNT = 3
        private const val SIGNAL_BURST_INTERVAL_MS = 1_000L

        fun start(context: Context) {
            val i = Intent(context, SenderService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, i)
        }

        fun kick(context: Context) {
            val i = Intent(context, SenderService::class.java).apply { action = ACTION_KICK }
            androidx.core.content.ContextCompat.startForegroundService(context, i)
        }

        /** Force an immediate reconnect + status refresh (e.g. when the app opens). */
        fun syncNow(context: Context) {
            val i = Intent(context, SenderService::class.java).apply { action = ACTION_SYNC_NOW }
            androidx.core.content.ContextCompat.startForegroundService(context, i)
        }
    }
}
