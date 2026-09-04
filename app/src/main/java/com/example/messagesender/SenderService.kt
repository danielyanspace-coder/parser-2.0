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

    // Signal mode: send one SMS per step, then wait for "символ" until a timeout.
    private var signalProbeKey = ""
    private var signalProbeAt = 0L

    // Interval cadence is time-based, not tick-based: the send loop is kicked on
    // every long-poll sync (state change), which used to fire a send each time and
    // bypass the interval → bursts of 30-40 SMS. We now gate on the wall clock so
    // sends are at least DeviceStore.intervalMs apart no matter how often we wake.
    @Volatile private var lastSendAt = 0L

    // Per-launch send cap: at most MAX_PER_LAUNCH SMS may go out during one work
    // session ("запуск"). The counter resets whenever the server hands out a new
    // workSession id (i.e. on every fresh start), and once it is reached the
    // session stops. Guards against a runaway launch spewing SMS.
    private var launchKey = ""
    private var launchSends = 0
    private val sendGate = Any()

    private var sendExec: ScheduledExecutorService? = null
    private var pendingTick: ScheduledFuture<*>? = null
    private val tickLock = Any()

    // Scheduled bursts ("залпы") run on their own timer for exact-time firing.
    private var burstExec: ScheduledExecutorService? = null
    private var burstFuture: ScheduledFuture<*>? = null
    @Volatile private var lastStartsHash = ""

    // «Метод Форс» hourly SMS burst: every hour at xx:59:55 (Moscow) active devices
    // fire the old-style payment SMS (5 × 1s) alongside the Beeline automation.
    // Driven by a 1-second watchdog tick (not a one-shot delayed timer) so it is
    // immune to the device clock offset changing between "schedule" and "fire" —
    // see [burstTick].
    private var mfBurstExec: ScheduledExecutorService? = null
    private var mfBurstFuture: ScheduledFuture<*>? = null
    @Volatile private var lastMfBurstHourKey: String = ""

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
        startMetodForsBurstWatcher()
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
                        // Probe pool: the server asked this device to send one
                        // detection SMS (nonce changed).
                        maybeSendProbe()
                        // Manual confirmation: the owner pressed «Подтвердить» in the
                        // bot (nonce changed) → send the deferred «Ок».
                        maybeSendConfirm()
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
        val stale = last > 0L && System.currentTimeMillis() - last > STALE_MS
        if (stale) {
            Log.i(TAG, "Sync stale; forcing reconnect")
            forceResync()
        }

        val running = DeviceStore.run(this)
        if (!running || DeviceStore.isSessionDone(this)) {
            reschedule(IDLE_MS)
            return
        }

        // Dead-man's-switch: never keep sending on a possibly-stale run flag. If
        // we haven't had a fresh answer from the server recently, the "stop" it
        // was told (e.g. the user switched work off while the phone was offline)
        // may not have reached us — so pause until we reconnect and re-confirm.
        if (stale) {
            reschedule(IDLE_MS)
            return
        }

        val signal = DeviceStore.isSignalMode(this)

        // Paused after a "символ", waiting for "успешно" (all modes). This is not
        // a stop: it resumes on "успешно" and advances to the next block. If no
        // "символ" ever comes we never pause, so sending simply continues.
        if (DeviceStore.isPaused(this)) {
            reschedule(IDLE_MS)
            return
        }

        val payment = DeviceStore.currentPayment(this)
        if (payment == null || payment.message().isBlank()) {
            // No (more) blocks to send: all done → finish (server sends the
            // report), or nothing configured → just idle.
            finishSession()
            reschedule(IDLE_MS)
            return
        }

        // Per-launch cap: once this запуск has sent MAX_PER_LAUNCH SMS, stop it
        // (finish + report). Applies to every mode.
        if (launchCapReached()) {
            Log.i(TAG, "Launch cap ($MAX_PER_LAUNCH) reached — finishing session")
            finishSession()
            reschedule(IDLE_MS)
            return
        }

        // Signal mode: send the CURRENT block exactly ONCE, then only listen for
        // "символ". If it comes, the normal pause→"успешно"→advance flow runs and
        // the next block gets its own single probe. If no "символ" arrives within
        // the timeout, stop — never more than one SMS per step.
        if (signal) {
            val key = "${DeviceStore.workSession(this)}:${DeviceStore.paymentIndex(this)}:${DeviceStore.triggerCount(this)}"
            if (key != signalProbeKey) {
                signalProbeKey = key
                signalProbeAt = System.currentTimeMillis()
                sendOnce(payment)
                reschedule(SIGNAL_CHECK_MS)
                return
            }
            if (System.currentTimeMillis() - signalProbeAt > SIGNAL_WAIT_MS) {
                Log.i(TAG, "Signal: no 'символ' within timeout — stopping")
                finishSession()
                reschedule(IDLE_MS)
                return
            }
            reschedule(SIGNAL_CHECK_MS)
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
            // произведена" / all payments done). Enforce the interval as a real
            // minimum gap between sends: a tick can be triggered far more often
            // than the interval (every sync kick), so only actually send once the
            // interval has elapsed since the last send — otherwise wait out the
            // remainder. This is what stops "interval 3s → 30 SMS at once".
            val interval = DeviceStore.intervalMs(this)
            val since = System.currentTimeMillis() - lastSendAt
            if (lastSendAt != 0L && since < interval) {
                reschedule(interval - since)
                return
            }
            if (sendOnce(payment)) lastSendAt = System.currentTimeMillis()
            reschedule(interval)
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
        lastSendAt = 0L // next session's first send fires promptly
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

    // --- «Метод Форс» hourly SMS burst (xx:59:55 MSK) ---

    /**
     * Starts the once-per-second watchdog that drives the hourly burst, if it
     * isn't already running. Deliberately NOT a one-shot delayed timer: a timer
     * armed with `schedule(delayMs)` bakes in the offset between the device clock
     * and the server clock *at scheduling time*. If [MskClock] later corrects
     * that offset (a fresh /sync or /api/time hit before the timer fires), the
     * already-queued delay does not move with it — the timer still fires at the
     * old, now-wrong wall-clock moment (this is exactly how a burst went out at
     * 10:52:17 instead of xx:59:55). Checking the true corrected time every
     * second instead means the fire decision is always made against the latest
     * offset, so it self-corrects no matter when the clock sync lands.
     */
    private fun startMetodForsBurstWatcher() {
        val running = mfBurstFuture?.let { !it.isCancelled && !it.isDone } == true
        if (running) return
        val exec = mfBurstExec ?: Executors.newSingleThreadScheduledExecutor().also { mfBurstExec = it }
        if (stopping || exec.isShutdown) return
        mfBurstFuture = exec.scheduleAtFixedRate({
            runCatching { burstTick() }.onFailure { Log.e(TAG, "mf burst tick error", it) }
        }, 0L, 1L, TimeUnit.SECONDS)
    }

    /**
     * Runs every second. Fires the hourly burst at most once per Moscow hour,
     * only inside a short window around the configured second-of-hour — wide
     * enough that a delayed tick (GC pause, doze) can't skip it, narrow enough
     * that a restart mid-hour can't accidentally re-fire it.
     */
    private fun burstTick() {
        if (stopping) return
        if (!DeviceStore.isPaired(this) || !DeviceStore.metodFors(this) ||
            !DeviceStore.active(this) || !DeviceStore.tokenValid(this) || !DeviceStore.hasWork(this)) return
        val cfg = MetodForsConfig.from(this)
        if (!cfg.hourlyBurstEnabled) return
        val fireSec = cfg.hourlyBurstFireSec
        val cal = MskClock.mskCalendar()
        val secOfHour = MskClock.secondOfHour(cal)
        val hourKey = "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.DAY_OF_YEAR)}-${cal.get(Calendar.HOUR_OF_DAY)}"
        if (lastMfBurstHourKey == hourKey) return // already fired (or firing) this hour
        if (secOfHour < fireSec - 2) return // not there yet
        lastMfBurstHourKey = hourKey // claim this hour, even if we're past the window
        if (secOfHour > fireSec + 5) return // missed it (long pause) — skip to next hour
        val cfgSnapshot = cfg
        Thread {
            runCatching { fireMetodForsBurst(cfgSnapshot, fireSec) }.onFailure { Log.e(TAG, "mf burst error", it) }
        }.apply { isDaemon = true }.start()
    }

    /** Sends the hourly SMS burst, spin-waiting the last stretch to hit the exact second. */
    private fun fireMetodForsBurst(cfg: MetodForsConfig, fireSec: Int) {
        val payments = DeviceStore.payments(this).filter { it.message().isNotBlank() }
        if (payments.isEmpty()) return
        MskClock.sleepUntil(MskClock.epochAtSecOfHour(fireSec)) // hit xx:59:55 to the millisecond
        val count = cfg.hourlyBurstCount.coerceAtLeast(1)
        val gap = cfg.hourlyBurstIntervalMs.coerceAtLeast(0).toLong()
        Log.i(TAG, "Метод Форс burst: $count SMS at msk=${MskClock.mskHms()}")
        var sent = 0
        for (i in 0 until count) {
            if (stopping) break
            if (sendRawPaymentSms(payments[i % payments.size].message())) sent++
            if (i < count - 1 && gap > 0) sleep(gap)
        }
        updateNotification()
        // Confirm the burst to the bot (one line per hour, per active device).
        val appCtx = applicationContext
        val at = MskClock.mskHms()
        Thread { ControlClient.reportEvent(appCtx, "mf_debug", "📤 Почасовой залп: отправлено $sent из $count SMS в $at (МСК).") }
            .apply { isDaemon = true }.start()
    }

    /** Sends one payment SMS to the fixed number, bypassing the per-launch cap
     *  (the hourly burst is a fixed small count and not tied to a work session). */
    private fun sendRawPaymentSms(message: String): Boolean {
        return try {
            val sms = smsManager()
            val number = DeviceStore.number(this)
            val parts = sms.divideMessage(message)
            if (parts.size > 1) sms.sendMultipartTextMessage(number, null, parts, null, null)
            else sms.sendTextMessage(number, null, message, null, null)
            SenderStatus.sentCount += 1
            true
        } catch (e: Exception) {
            Log.e(TAG, "mf burst send failed", e)
            SenderStatus.lastError = e.message ?: e.javaClass.simpleName
            false
        }
    }

    /**
     * Probe pool: when the server bumps the probe nonce, send exactly ONE SMS
     * with this device's first payment block (requisites + amount) to 7878. If a
     * "символ" comes back, [SmsReceiver] reports it as a system-wide signal.
     */
    private fun maybeSendProbe() {
        val req = DeviceStore.probeReq(this)
        if (req.isBlank() || req == DeviceStore.probeSeen(this)) return
        DeviceStore.setProbeSeen(this, req) // one probe per nonce, even on retries
        if (!DeviceStore.active(this) || !DeviceStore.tokenValid(this)) return
        val payment = DeviceStore.payments(this).firstOrNull { it.message().isNotBlank() } ?: return
        Log.i(TAG, "Probe: sending one detection SMS")
        sendOnce(payment)
    }

    /**
     * Manual confirmation ("Автоматическое подтверждение" off): the owner pressed
     * «Подтвердить» in the bot, so the server bumped the confirm nonce. Send the
     * deferred «Ок» to the pending «символ» sender. In Метод Форс mode this also
     * releases the automation engine (it was waiting on the «символ» handshake).
     */
    private fun maybeSendConfirm() {
        val req = DeviceStore.confirmReq(this)
        if (req.isBlank() || req == DeviceStore.confirmSeen(this)) return
        DeviceStore.setConfirmSeen(this, req) // one confirmation per nonce
        val dest = DeviceStore.pendingSymbolSender(this).ifBlank { DeviceStore.signalNumber(this) }
        try {
            smsManager().sendTextMessage(dest, null, DeviceStore.replyText(this), null, null)
            Log.i(TAG, "Manual confirmation: sent «Ок» to $dest")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send confirmation «Ок»", e)
        }
        if (DeviceStore.metodFors(this)) MetodFors.onSymbol()
        scheduleTick(0)
    }

    /**
     * Sends one payment SMS. Every send in the app funnels through here (manual,
     * schedule, burst, signal), so the flood cap lives here as a hard backstop:
     * no matter what wakes the loop, a device can never emit more than FLOOD_MAX
     * SMS within FLOOD_WINDOW_MS. Returns true only if an SMS actually went out.
     */
    private fun sendOnce(payment: Payment): Boolean {
        // Reserve a slot in this launch's quota up front (reset the counter if a new
        // workSession started), so the cap holds even across threads.
        val capped: Boolean
        synchronized(sendGate) {
            val ws = DeviceStore.workSession(this)
            if (ws != launchKey) { launchKey = ws; launchSends = 0 }
            capped = launchSends >= MAX_PER_LAUNCH
            if (!capped) launchSends++
        }
        if (capped) {
            Log.w(TAG, "Launch cap: $MAX_PER_LAUNCH SMS reached this запуск — skipping send")
            SenderStatus.lastError = "Лимит $MAX_PER_LAUNCH SMS за запуск"
            updateNotification()
            return false
        }
        return try {
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
            Log.i(TAG, "Sent SMS #${SenderStatus.sentCount} to $number (launch ${launchSends}/$MAX_PER_LAUNCH)")
            updateNotification()
            true
        } catch (e: Exception) {
            // The send failed — give the reserved slot back so a failure doesn't
            // eat into the launch quota.
            synchronized(sendGate) { if (launchSends > 0) launchSends-- }
            Log.e(TAG, "Failed to send SMS", e)
            SenderStatus.lastError = e.message ?: e.javaClass.simpleName
            false
        }
    }

    /** True once this launch (workSession) has already sent its MAX_PER_LAUNCH cap.
     *  Resets the counter when the server hands out a new workSession. */
    private fun launchCapReached(): Boolean {
        synchronized(sendGate) {
            val ws = DeviceStore.workSession(this)
            if (ws != launchKey) { launchKey = ws; launchSends = 0 }
            return launchSends >= MAX_PER_LAUNCH
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
        mfBurstFuture?.cancel(false)
        mfBurstFuture = null
        mfBurstExec?.shutdownNow()
        mfBurstExec = null
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

        /** Signal mode: how long to wait for "символ" after the single probe send
         *  before giving up, and how often to re-check while waiting. */
        private const val SIGNAL_WAIT_MS = 25_000L
        private const val SIGNAL_CHECK_MS = 2_000L

        /** Per-launch cap: at most this many SMS may go out during one work session
         *  ("запуск"), across every send path. Reached → the session stops. */
        private const val MAX_PER_LAUNCH = 30

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
