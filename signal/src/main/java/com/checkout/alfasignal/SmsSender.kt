package com.checkout.alfasignal

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
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Foreground service that sends the configured SMS to the configured number on a
 * fixed interval (in seconds) until stopped. A partial wake lock keeps it ticking
 * with the screen off.
 */
class SmsSender : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var exec: ScheduledExecutorService? = null
    private var future: ScheduledFuture<*>? = null
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSending()
            return START_NOT_STICKY
        }
        startForegroundSafe()
        acquireWakeLock()
        Prefs.setSending(this, true)
        running = true
        if (exec == null) exec = Executors.newSingleThreadScheduledExecutor()
        scheduleNext(0)
        return START_STICKY
    }

    private fun scheduleNext(delaySec: Long) {
        val e = exec ?: return
        if (e.isShutdown || !running) return
        future?.cancel(false)
        future = e.schedule({
            runCatching { sendOnce() }.onFailure { Log.e(TAG, "send error", it) }
            if (running) scheduleNext(Prefs.intervalSec(this).toLong())
        }, delaySec, TimeUnit.SECONDS)
    }

    private fun sendOnce() {
        val number = Prefs.number(this)
        val message = Prefs.msg(this)
        if (number.isBlank() || message.isBlank()) return
        try {
            val sms = smsManager()
            val parts = sms.divideMessage(message)
            if (parts.size > 1) sms.sendMultipartTextMessage(number, null, parts, null, null)
            else sms.sendTextMessage(number, null, message, null, null)
            Prefs.addLog(this, "${Webhook.now()}  📤 SMS → $number: «$message»")
            updateNotification()
        } catch (e: Exception) {
            Log.e(TAG, "sendTextMessage failed", e)
            Prefs.addLog(this, "${Webhook.now()}  ⚠️ ошибка отправки: ${e.message}")
        }
    }

    private fun smsManager(): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) getSystemService(SmsManager::class.java)
        else @Suppress("DEPRECATION") SmsManager.getDefault()

    private fun stopSending() {
        running = false
        Prefs.setSending(this, false)
        future?.cancel(false)
        exec?.shutdownNow()
        exec = null
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AlfaSignal::sender").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun startForegroundSafe() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, n,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL, "Отправка SMS", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("ALFA Signal — отправка идёт")
            .setContentText("Каждые ${Prefs.intervalSec(this)} c → ${Prefs.number(this)}")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    private fun updateNotification() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(NOTIF_ID, buildNotification())
    }

    override fun onDestroy() {
        running = false
        future?.cancel(false)
        exec?.shutdownNow()
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AlfaSignal"
        private const val CHANNEL = "signal_sender"
        private const val NOTIF_ID = 200
        const val ACTION_STOP = "com.checkout.alfasignal.STOP_SENDING"

        fun start(context: Context) {
            val i = Intent(context, SmsSender::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            val i = Intent(context, SmsSender::class.java).apply { action = ACTION_STOP }
            androidx.core.content.ContextCompat.startForegroundService(context, i)
        }
    }
}
