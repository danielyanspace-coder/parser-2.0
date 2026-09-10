package com.example.messagesender

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * "Метод Форс" — an AccessibilityService that drives the Beeline (Билайн) app to
 * make a card transfer at one exact Moscow-time moment each hour, per device,
 * independently:
 *
 *   Preparation starts 5 min earlier (xx:54:58): open Beeline, walk the transfer
 *   screens, fill Номер and Сумма, reach the «Отправить» button, then hold until
 *   the exact second — xx:59:59 — and press it.
 *     – «Вернуться в финансы» → success: wait «символ» from 8464 (SmsReceiver
 *       auto-replies «Ок»), wait «успешно», report to the bot.
 *     – «Повторить» → tap it once, then the same «символ» → «Ок» → «успешно» →
 *       report. If no «символ» comes after «Повторить», do nothing and wait for
 *       the next hourly window.
 *
 * Everything about the flow (screen labels, package, rule time) comes from the
 * server via [MetodForsConfig], so it can be re-tuned without an APK rebuild. The
 * exact timing rides on [MskClock], which corrects the phone's clock against the
 * server's NTP-backed time and reads the wall clock through Europe/Moscow.
 */
class MetodForsService : AccessibilityService() {

    private var scheduler: ScheduledExecutorService? = null
    private var worker: ScheduledExecutorService? = null
    @Volatile private var running = false
    @Volatile private var busy = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        INSTANCE = this
        running = true
        if (scheduler == null) {
            scheduler = Executors.newSingleThreadScheduledExecutor()
            scheduler!!.scheduleWithFixedDelay(
                { runCatching { schedulerTick() }.onFailure { Log.e(TAG, "scheduler tick", it) } },
                2, 5, TimeUnit.SECONDS
            )
        }
        if (worker == null) worker = Executors.newSingleThreadScheduledExecutor()
        Log.i(TAG, "Метод Форс service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* driven imperatively */ }

    override fun onInterrupt() {}

    override fun onDestroy() {
        running = false
        scheduler?.shutdownNow(); scheduler = null
        worker?.shutdownNow(); worker = null
        if (INSTANCE === this) INSTANCE = null
        super.onDestroy()
    }

    // --- Scheduling: fire each rule at its own prep moment ---

    // Guard so a single prep window only fires once (keyed by the fire epoch).
    @Volatile private var lastFiredKey = ""

    private fun schedulerTick() {
        if (!running || busy) return
        if (!DeviceStore.isPaired(this) || !DeviceStore.metodFors(this)) return
        val cfg = MetodForsConfig.from(this)

        // On-demand test (button in the mini-app): dry-run the whole prep and
        // report what the screen shows, without pressing «Отправить». Lets us
        // debug the Beeline flow without waiting for xx:59:59.
        if (checkTestRequested()) {
            busy = true
            worker?.execute {
                try {
                    if (!MskClock.hasServerTime()) MskClock.syncHttp(DeviceStore.serverUrl(this))
                    runTest(cfg)
                } catch (e: Exception) { Log.e(TAG, "test error", e) }
                finally { busy = false }
            }
            return
        }

        if (!eligible()) return
        val nowTrue = MskClock.trueEpoch()

        // Each window has its own prep window [fireEpoch - prepLead, fireEpoch). Among
        // the windows we're currently inside, pick the one firing soonest and run it.
        // The device does one cycle at a time (the busy flag), so windows should be
        // spaced apart — a window whose moment passes while we're busy is simply missed.
        val windows = cfg.windows.ifEmpty { listOf(cfg.rule) }
        var bestFire = Long.MAX_VALUE
        for (w in windows) {
            val fireEpoch = MskClock.nextFireEpoch(w.fireSec, w.fireMs)
            val prepStart = fireEpoch - w.prepLeadSec * 1000L
            if (nowTrue in prepStart..fireEpoch && fireEpoch < bestFire) bestFire = fireEpoch
        }
        if (bestFire == Long.MAX_VALUE) return
        val key = "R@$bestFire"
        if (key == lastFiredKey) return
        lastFiredKey = key

        busy = true
        worker?.execute {
            try {
                if (!MskClock.hasServerTime()) MskClock.syncHttp(DeviceStore.serverUrl(this))
                runRule(bestFire, cfg)
            } catch (e: Exception) {
                Log.e(TAG, "runRule error", e)
            } finally {
                busy = false
            }
        }
    }

    private fun eligible(): Boolean =
        DeviceStore.isPaired(this) && DeviceStore.metodFors(this) &&
            DeviceStore.active(this) && DeviceStore.tokenValid(this) && DeviceStore.hasWork(this)

    /** True once when the mini-app requested a one-shot test (nonce changed). */
    private fun checkTestRequested(): Boolean {
        val req = DeviceStore.mfTestReq(this)
        if (req.isBlank() || req == DeviceStore.mfTestSeen(this)) return false
        DeviceStore.setMfTestSeen(this, req)
        return true
    }

    /**
     * Test run: does the FULL flow immediately, including pressing «Отправить» —
     * WITHOUT waiting for the fire moment. The server turns «Автоподтверждение» OFF
     * for the test, so «Ок» is never sent and NO real payment can complete: pressing
     * «Отправить» only reaches the confirmation screen, which is what we verify. A
     * single «mftest_result» is reported at the end so the server can restore
     * «Автоподтверждение».
     */
    private fun runTest(cfg: MetodForsConfig) {
        reportDebug("Тест начат. Автоподтверждение на время теста выключено — платёж не уйдёт.")
        val pay = DeviceStore.payments(this).firstOrNull { it.message().isNotBlank() }
        if (pay == null) { reportTestResult("Тест не пройден: не заданы Номер/Сумма."); return }
        if (!prepareTransfer(cfg, pay, verbose = true, stepTimeout = TEST_STEP_TIMEOUT)) {
            reportTestResult("Тест не пройден: не удалось подготовить экран Билайна."); return
        }
        reportDebug("Дошёл до «${cfg.sendLabel}», нажимаю…")
        pressSend(cfg, verbose = true)
        when (waitOutcome(cfg, RESULT_TIMEOUT)) {
            Outcome.BACK_TO_FINANCE ->
                reportTestResult("Тест пройден успешно. Экран «${cfg.backToFinanceLabel}» получен. Платёж НЕ отправлен.")
            Outcome.REPEAT ->
                reportTestResult("Тест пройден успешно. Появилось «${cfg.repeatLabel}». Платёж НЕ отправлен.")
            Outcome.NONE ->
                reportTestResult("Тест не пройден: после «${cfg.sendLabel}» не видно итогового экрана. Вижу: " + dumpVisibleTexts())
        }
    }

    /** Final one-line test outcome → the server relays it and restores autoconfirm. */
    private fun reportTestResult(msg: String) {
        val appCtx = applicationContext
        Thread { ControlClient.reportEvent(appCtx, "mftest_result", msg) }.apply { isDaemon = true }.start()
    }

    // --- One rule run ---

    private fun runRule(fireEpoch: Long, cfg: MetodForsConfig) {
        val payments = DeviceStore.payments(this).filter { it.message().isNotBlank() }
        if (payments.isEmpty()) return
        val idx = DeviceStore.mfBlockIndex(this) % payments.size
        val pay = payments[idx]
        Log.i(TAG, "Rule start (msk=${MskClock.mskHms()}), block=$idx req=${pay.requisites} amt=${pay.amount}")

        // 1) Prepare: open Beeline and walk to the «Отправить» button.
        if (!prepareTransfer(cfg, pay)) {
            Log.w(TAG, "prepare failed — aborting this window")
            return
        }

        // 2) Hold until the exact second, then press «Отправить».
        Log.i(TAG, "Reached Отправить; holding until the fire moment (msk=${MskClock.mskHms()})")
        MskClock.sleepUntil(fireEpoch)
        pressSend(cfg)
        Log.i(TAG, "Отправить pressed at msk=${MskClock.mskHms()}")

        // 3) After «Отправить», «Повторить» often keeps re-appearing. Tap it EVERY
        // time it shows for up to REPEAT_WINDOW_MS. Regardless of outcome, hand
        // control back to the scheduler RULE_MAX_MS after the fire moment so the
        // next hour starts perfectly clean (busy is released well before the next
        // prep window). If the символ→Ок→успешно handshake completes in between,
        // report the transfer once and finish early.
        val startedAt = System.currentTimeMillis()
        val repeatDeadline = startedAt + REPEAT_WINDOW_MS
        val hardDeadline = fireEpoch + RULE_MAX_MS
        val repeatCap = cfg.repeatMax // 0 = unlimited
        var repeatPresses = 0
        while (running && System.currentTimeMillis() < hardDeadline) {
            val underCap = repeatCap <= 0 || repeatPresses < repeatCap
            if (underCap && System.currentTimeMillis() < repeatDeadline && findTextAnywhere(cfg.repeatLabel) != null) {
                pressAll(cfg.repeatLabel, attempts = 2)
                repeatPresses++
                Log.i(TAG, "«Повторить» tapped ($repeatPresses/${if (repeatCap > 0) repeatCap else "∞"}) at msk=${MskClock.mskHms()}")
            }
            if (MetodFors.lastSuccessAt > startedAt) {
                Log.i(TAG, "«успешно» received — reporting to bot")
                reportMetodForsSuccess(pay)
                break
            }
            sleep(800)
        }
        Log.i(TAG, "Rule window finished (msk=${MskClock.mskHms()}) — back to scheduler")
    }

    /** Reports one completed transfer to the bot and rotates to the next block. */
    private fun reportMetodForsSuccess(pay: Payment) {
        val appCtx = applicationContext
        Thread { ControlClient.reportMetodFors(appCtx, pay.requisites, pay.amount) }
            .apply { isDaemon = true }.start()
        val payments = DeviceStore.payments(this).filter { it.message().isNotBlank() }
        if (payments.isNotEmpty()) DeviceStore.setMfBlockIndex(this, (DeviceStore.mfBlockIndex(this) + 1) % payments.size)
    }

    /**
     * Opens Beeline and walks: Сервисы → Перевести деньги → Перевод на карту
     * за рубеж → Таджикистан → По номеру карты → Мой номер → [card field]=Номер →
     * Продолжить → [amount field]=Сумма → Продолжить → wait «Отправить».
     *
     * Beeline sometimes fails to render a screen (a blank/stuck page). Every step
     * therefore has a recovery: if the needed control isn't seen within [stepTimeout],
     * pull the screen down to refresh and give it another [REFRESH_RETRY_WAIT]. If it
     * still doesn't appear, the whole flow restarts Beeline from scratch (up to
     * [MAX_BEELINE_RESTARTS] times) and walks the steps again.
     */
    private fun prepareTransfer(cfg: MetodForsConfig, pay: Payment,
                               verbose: Boolean = false, stepTimeout: Long = STEP_PRIMARY_WAIT): Boolean {
        var restarts = 0
        while (running) {
            if (!launchPackage(cfg.beelinePackage)) {
                Log.w(TAG, "cannot launch ${cfg.beelinePackage}")
                if (verbose) reportDebug("Не удалось открыть приложение ${cfg.beelinePackage}.")
                return false
            }
            sleep(1800) // let the first screen finish rendering after it's foreground
            if (walkPrep(cfg, pay, verbose, stepTimeout)) return true
            // A screen would not load even after a pull-to-refresh → restart Beeline.
            restarts++
            if (restarts > MAX_BEELINE_RESTARTS) {
                if (verbose) reportDebug("Экран не прогрузился даже после перезапусков Билайна.")
                return false
            }
            Log.i(TAG, "Beeline screen stuck — restarting the app (attempt ${restarts + 1})")
            if (verbose) reportDebug("Перезапускаю Билайн (попытка ${restarts + 1})…")
            sleep(800)
        }
        return false
    }

    /** One full pass through the transfer screens. Returns false if a control never
     *  loaded (even after a refresh) so the caller can restart Beeline and retry. */
    private fun walkPrep(cfg: MetodForsConfig, pay: Payment, verbose: Boolean, stepTimeout: Long): Boolean {
        for (step in cfg.steps) {
            if (!tapStepConfirmed(step, stepTimeout, verbose)) {
                if (verbose) reportDebug("Остановился на шаге «$step». Что вижу на экране: " +
                    (dumpVisibleTexts().ifBlank { "(пусто — не могу прочитать интерфейс)" }))
                return false
            }
            sleep(STEP_PAUSE)
        }
        // Card number field → Номер → Продолжить.
        if (!fillFieldWithRefresh(cfg.cardFieldHint, pay.requisites, verbose)) {
            if (verbose) reportDebug("Не нашёл поле карты «${cfg.cardFieldHint}». Вижу: " + dumpVisibleTexts())
            return false
        }
        sleep(STEP_PAUSE)
        awaitTappableWithRefresh(cfg.continueLabel, stepTimeout, verbose)?.let { clickNode(it) } ?: run {
            if (verbose) reportDebug("Не нашёл «${cfg.continueLabel}» после номера. Вижу: " + dumpVisibleTexts()); return false
        }
        sleep(STEP_PAUSE)
        // Amount field → Сумма → Продолжить.
        if (!fillFieldWithRefresh(null, pay.amount, verbose)) {
            if (verbose) reportDebug("Не нашёл поле суммы. Вижу: " + dumpVisibleTexts()); return false
        }
        sleep(STEP_PAUSE)
        awaitTappableWithRefresh(cfg.continueLabel, stepTimeout, verbose)?.let { clickNode(it) } ?: run {
            if (verbose) reportDebug("Не нашёл «${cfg.continueLabel}» после суммы. Вижу: " + dumpVisibleTexts()); return false
        }
        // Wait for «Отправить» to be present (do NOT press it yet).
        val ok = awaitTextWithRefresh(cfg.sendLabel, stepTimeout, verbose) != null
        if (!ok && verbose) reportDebug("Не нашёл кнопку «${cfg.sendLabel}». Вижу: " + dumpVisibleTexts())
        return ok
    }

    /**
     * Taps a navigation [label] and confirms the screen actually advanced (the label
     * disappeared); re-taps a few times otherwise. The very first tap right after
     * Beeline opens is the one that often gets swallowed — this makes the bot press
     * again instead of stalling, so it no longer "opens and does nothing".
     */
    private fun tapStepConfirmed(label: String, primaryWaitMs: Long, verbose: Boolean): Boolean {
        val node = awaitTappableWithRefresh(label, primaryWaitMs, verbose) ?: return false
        tapNodeHard(node)
        for (a in 1..STEP_TAP_ATTEMPTS) {
            val end = System.currentTimeMillis() + STEP_CONFIRM_WAIT
            while (System.currentTimeMillis() < end) {
                if (findTextAnywhere(label) == null) return true // screen advanced
                sleep(150)
            }
            val again = findTappable(label) ?: return true // gone between checks
            tapNodeHard(again)
            Log.i(TAG, "step «$label» re-tapped (attempt $a)")
        }
        return true // best-effort; the next step's own wait/refresh will catch a miss
    }

    /** Waits for a tappable [label]; on timeout pulls-to-refresh once and retries. */
    private fun awaitTappableWithRefresh(label: String, primaryWaitMs: Long, verbose: Boolean): AccessibilityNodeInfo? {
        waitForTappable(label, primaryWaitMs)?.let { return it }
        if (verbose) reportDebug("Не вижу «$label» — обновляю страницу (тяну вниз)…")
        Log.i(TAG, "«$label» not found in ${primaryWaitMs}ms — pull-to-refresh")
        pullToRefresh()
        sleep(1200)
        return waitForTappable(label, REFRESH_RETRY_WAIT)
    }

    /** Waits for [text] to be present; on timeout pulls-to-refresh once and retries. */
    private fun awaitTextWithRefresh(text: String, primaryWaitMs: Long, verbose: Boolean): AccessibilityNodeInfo? {
        waitForText(text, primaryWaitMs)?.let { return it }
        if (verbose) reportDebug("Не вижу «$text» — обновляю страницу (тяну вниз)…")
        Log.i(TAG, "«$text» not present in ${primaryWaitMs}ms — pull-to-refresh")
        pullToRefresh()
        sleep(1200)
        return waitForText(text, REFRESH_RETRY_WAIT)
    }

    /** Fills a field; on failure pulls-to-refresh once and retries. */
    private fun fillFieldWithRefresh(hint: String?, value: String, verbose: Boolean): Boolean {
        if (fillField(hint, value, FIELD_PRIMARY_WAIT)) return true
        if (verbose) reportDebug("Поле не прогрузилось — обновляю страницу (тяну вниз)…")
        pullToRefresh()
        sleep(1200)
        return fillField(hint, value, REFRESH_RETRY_WAIT)
    }

    /**
     * Pull-to-refresh: a firm swipe DOWN from the middle of the screen. Beeline
     * re-fetches a stuck screen on this gesture, which fixes the blank-page bug.
     */
    private fun pullToRefresh() {
        try {
            val dm = resources.displayMetrics
            val x = dm.widthPixels / 2f
            val y1 = dm.heightPixels * 0.35f
            val y2 = dm.heightPixels * 0.92f
            val path = Path().apply { moveTo(x, y1); lineTo(x, y2) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 350))
                .build()
            dispatchGesture(gesture, null, null)
            Log.i(TAG, "pull-to-refresh dispatched")
        } catch (e: Exception) { Log.e(TAG, "pullToRefresh failed", e) }
    }

    // --- Outcome detection ---

    private enum class Outcome { BACK_TO_FINANCE, REPEAT, NONE }

    /**
     * Presses «Отправить» and confirms it actually took effect: after each tap it
     * checks whether an outcome screen appeared or the button went away, and retries
     * a few times otherwise. This fixes "reached the button but it wasn't pressed".
     */
    private fun pressSend(cfg: MetodForsConfig, verbose: Boolean = false, attempts: Int = 6): Boolean {
        for (a in 1..attempts) {
            // Collect EVERY on-screen occurrence of «Отправить» and tap all of them
            // (the disclaimer text does nothing; the yellow button actually fires).
            val matches = ArrayList<AccessibilityNodeInfo>()
            for (r in allRoots()) collectMatches(r, cfg.sendLabel, matches)
            if (matches.isEmpty()) {
                if (waitForTappable(cfg.sendLabel, 5000) == null) {
                    if (verbose) reportDebug("Не вижу «${cfg.sendLabel}» на экране.")
                    return false
                }
                continue
            }
            if (verbose && a == 1) reportDebug("Нашёл «${cfg.sendLabel}»: ${matches.size} шт — жму все (попытка $a).")
            for (n in matches) tapNodeHard(n)
            Log.i(TAG, "Отправить: tapped ${matches.size} node(s), attempt #$a at msk=${MskClock.mskHms()}")
            val end = System.currentTimeMillis() + 3500
            while (System.currentTimeMillis() < end) {
                if (findTextAnywhere(cfg.backToFinanceLabel) != null ||
                    findTextAnywhere(cfg.repeatLabel) != null) return true // outcome shown → pressed
                sleep(250)
            }
            if (findTextAnywhere(cfg.sendLabel) == null) return true // screen changed → pressed
            // Still on the send screen — retry, tapping everything again.
        }
        return true
    }

    /**
     * Robustly taps EVERY on-screen occurrence of [label] (both ways) and retries
     * until the label disappears — i.e. the tap took effect and the screen moved on.
     * Used for «Повторить», which intermittently missed with a single tap.
     */
    private fun pressAll(label: String, verbose: Boolean = false, attempts: Int = 6): Boolean {
        for (a in 1..attempts) {
            val matches = ArrayList<AccessibilityNodeInfo>()
            for (r in allRoots()) collectMatches(r, label, matches)
            if (matches.isEmpty()) {
                if (waitForTappable(label, 5000) == null) {
                    if (verbose) reportDebug("Не вижу «$label» на экране.")
                    return false
                }
                continue
            }
            if (verbose && a == 1) reportDebug("Нашёл «$label»: ${matches.size} шт — жму все (попытка $a).")
            for (n in matches) tapNodeHard(n)
            Log.i(TAG, "$label: tapped ${matches.size} node(s), attempt #$a at msk=${MskClock.mskHms()}")
            val end = System.currentTimeMillis() + 3500
            while (System.currentTimeMillis() < end) {
                if (findTextAnywhere(label) == null) return true // label gone → pressed
                sleep(250)
            }
            // Still visible — retry, tapping everything again.
        }
        return true
    }

    /** Presses a node BOTH ways: semantic click on a clickable ancestor AND a real
     *  finger-tap gesture at its centre — maximises the chance a custom button fires. */
    private fun tapNodeHard(node: AccessibilityNodeInfo) {
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && depth++ < 6) { if (n.isClickable) { n.performAction(AccessibilityNodeInfo.ACTION_CLICK); break }; n = n.parent }
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() > 0 && rect.height() > 0) tapAt(rect.exactCenterX(), rect.exactCenterY())
        sleep(200)
    }

    private fun waitOutcome(cfg: MetodForsConfig, timeoutMs: Long): Outcome {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (findTextAnywhere(cfg.backToFinanceLabel) != null) return Outcome.BACK_TO_FINANCE
            if (findTextAnywhere(cfg.repeatLabel) != null) return Outcome.REPEAT
            sleep(300)
        }
        return Outcome.NONE
    }

    // --- Node helpers ---

    /**
     * Opens the app FRESH and does not return until it is actually the foreground
     * window. Some devices silently drop a background-launched activity (OEM
     * restrictions / Doze), so we retry a few times and, between tries, tap HOME
     * first — launching from the launcher is far more reliable than a cold
     * background start. This fixes "doesn't open Beeline at all".
     */
    private fun launchPackage(pkg: String): Boolean {
        for (attempt in 1..LAUNCH_ATTEMPTS) {
            try {
                val intent = packageManager.getLaunchIntentForPackage(pkg)
                if (intent == null) { Log.w(TAG, "no launch intent for $pkg"); return false }
                // CLEAR_TASK finishes the app's existing stack so every run starts on
                // the home screen; RESET_TASK_IF_NEEDED helps on some launchers.
                intent.addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK or
                        android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                )
                startActivity(intent)
            } catch (e: Exception) { Log.e(TAG, "launch error (attempt $attempt)", e) }
            if (waitForForeground(pkg, LAUNCH_FOREGROUND_WAIT)) {
                Log.i(TAG, "$pkg is foreground (attempt $attempt)")
                return true
            }
            Log.w(TAG, "$pkg not foreground after attempt $attempt — HOME then retry")
            try { performGlobalAction(GLOBAL_ACTION_HOME) } catch (e: Exception) {}
            sleep(800)
        }
        return waitForForeground(pkg, LAUNCH_FOREGROUND_WAIT)
    }

    /** Blocks until [pkg] is the active foreground window (or timeout). */
    private fun waitForForeground(pkg: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (running && System.currentTimeMillis() < deadline) {
            val fg = try { rootInActiveWindow?.packageName?.toString() } catch (e: Exception) { null }
            if (fg == pkg) return true
            sleep(200)
        }
        return false
    }

    /** All window roots we can read (active window + every other interactive window). */
    private fun allRoots(): List<AccessibilityNodeInfo> {
        val roots = ArrayList<AccessibilityNodeInfo>()
        try { rootInActiveWindow?.let { roots.add(it) } } catch (e: Exception) {}
        try { for (w in windows) w.root?.let { r -> if (roots.none { it == r }) roots.add(r) } } catch (e: Exception) {}
        return roots
    }

    /** Finds a visible node whose text/description contains [text] (case-insensitive). */
    private fun nodeWithText(root: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        root ?: return null
        val hits = try { root.findAccessibilityNodeInfosByText(text) } catch (e: Exception) { null }
        if (hits != null) for (h in hits) if (h != null && h.isVisibleToUser) return h
        // Fallback BFS (findAccessibilityNodeInfosByText can miss content-desc-only nodes).
        return bfs(root) { n ->
            val t = (n.text?.toString() ?: "") + "\n" + (n.contentDescription?.toString() ?: "")
            n.isVisibleToUser && t.contains(text, ignoreCase = true)
        }
    }

    /** Search [text] across every readable window, not just the active one. */
    private fun findTextAnywhere(text: String): AccessibilityNodeInfo? {
        for (r in allRoots()) { val n = nodeWithText(r, text); if (n != null) return n }
        return null
    }

    /** True if the node or one of its close ancestors is clickable. */
    private fun isClickableChain(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && depth++ < 6) { if (n.isClickable) return true; n = n.parent }
        return false
    }

    private fun collectMatches(root: AccessibilityNodeInfo, label: String, out: ArrayList<AccessibilityNodeInfo>) {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var guard = 0
        while (queue.isNotEmpty() && guard++ < 4000) {
            val n = queue.removeFirst()
            val t = (n.text?.toString() ?: "") + "\n" + (n.contentDescription?.toString() ?: "")
            if (n.isVisibleToUser && t.contains(label, ignoreCase = true)) out.add(n)
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
    }

    /**
     * Finds the BEST node to TAP for [label] — crucial when a word appears both
     * on a button and inside a longer disclaimer ("Нажимая «Отправить» …"). Ranks:
     * clickable first, then exact-text match, then the shortest text (closest to
     * the label). So the real «Отправить» button wins over the agreement text.
     */
    private fun findTappable(label: String): AccessibilityNodeInfo? {
        val matches = ArrayList<AccessibilityNodeInfo>()
        for (r in allRoots()) collectMatches(r, label, matches)
        if (matches.isEmpty()) return null
        return matches.minByOrNull { n ->
            val text = (n.text?.toString() ?: n.contentDescription?.toString() ?: "").trim()
            val clickable = if (isClickableChain(n)) 0 else 1
            val exact = if (text.equals(label, ignoreCase = true)) 0 else 1
            clickable * 10000 + exact * 1000 + text.length.coerceAtMost(999)
        }
    }

    private fun waitForTappable(label: String, timeoutMs: Long): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (running && System.currentTimeMillis() < deadline) {
            val n = findTappable(label)
            if (n != null) return n
            sleep(300)
        }
        return null
    }

    private fun waitForText(text: String, timeoutMs: Long): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (running && System.currentTimeMillis() < deadline) {
            val n = findTextAnywhere(text)
            if (n != null) return n
            sleep(300)
        }
        return null
    }

    private fun tapText(text: String, timeoutMs: Long): Boolean {
        val node = waitForTappable(text, timeoutMs) ?: return false
        return clickNode(node)
    }

    /**
     * Taps a node. First tries the semantic ACTION_CLICK on a clickable ancestor;
     * if that isn't available or fails, dispatches a REAL touch gesture at the
     * node's on-screen centre. The gesture path is what makes it work on banking
     * apps whose buttons are custom-drawn and not marked clickable in the tree.
     */
    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && depth++ < 6) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            n = n.parent
        }
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() > 0 && rect.height() > 0) {
            return tapAt(rect.exactCenterX(), rect.exactCenterY())
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /** Dispatches a real one-finger tap at (x, y) in screen coordinates. */
    private fun tapAt(x: Float, y: Float): Boolean {
        return try {
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
                .build()
            dispatchGesture(gesture, null, null)
        } catch (e: Exception) { Log.e(TAG, "tapAt failed", e); false }
    }

    /** Collects every visible text / content-description on screen (for diagnostics). */
    private fun dumpVisibleTexts(): String {
        val out = LinkedHashSet<String>()
        for (r in allRoots()) collectTexts(r, out)
        return out.joinToString(" | ").take(700)
    }

    private fun collectTexts(root: AccessibilityNodeInfo, out: LinkedHashSet<String>) {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var guard = 0
        while (queue.isNotEmpty() && guard++ < 4000) {
            val n = queue.removeFirst()
            if (n.isVisibleToUser) {
                n.text?.toString()?.trim()?.let { if (it.isNotBlank()) out.add(it) }
                n.contentDescription?.toString()?.trim()?.let { if (it.isNotBlank()) out.add(it) }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
    }

    /** Sends a diagnostic line to the bot (used by the on-demand TEST only). */
    private fun reportDebug(msg: String) {
        val appCtx = applicationContext
        Thread { ControlClient.reportEvent(appCtx, "mf_debug", msg) }.apply { isDaemon = true }.start()
    }


    /**
     * Fills a text field with [value]. Prefers a field whose text/hint contains
     * [hint]; otherwise the first editable field on screen.
     */
    private fun fillField(hint: String?, value: String, timeoutMs: Long = FIELD_PRIMARY_WAIT): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (running && System.currentTimeMillis() < deadline) {
            val root = rootInActiveWindow
            val field = if (!hint.isNullOrBlank()) {
                nodeWithText(root, hint)?.takeIf { it.isEditable || it.className?.contains("EditText") == true }
                    ?: firstEditable(root)
            } else firstEditable(root)
            if (field != null) {
                clickNode(field)
                sleep(200)
                val args = Bundle()
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                if (field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true
            }
            sleep(250)
        }
        return false
    }

    private fun firstEditable(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        root ?: return null
        return bfs(root) { n -> n.isVisibleToUser && (n.isEditable || n.className?.contains("EditText") == true) }
    }

    private inline fun bfs(root: AccessibilityNodeInfo, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var guard = 0
        while (queue.isNotEmpty() && guard++ < 4000) {
            val n = queue.removeFirst()
            if (match(n)) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    private fun sleep(ms: Long) { try { Thread.sleep(ms) } catch (e: InterruptedException) {} }

    companion object {
        private const val TAG = "MetodForsService"
        @Volatile var INSTANCE: MetodForsService? = null

        fun isRunning(): Boolean = INSTANCE != null

        private const val STEP_PAUSE = 700L         // settle between screens
        // Launch: retry opening Beeline until it is actually foreground.
        private const val LAUNCH_ATTEMPTS = 4
        private const val LAUNCH_FOREGROUND_WAIT = 6_000L
        // First-tap robustness: re-tap a step until the screen advances.
        private const val STEP_TAP_ATTEMPTS = 4
        private const val STEP_CONFIRM_WAIT = 2_500L
        // Per screen: wait up to 1 min for the needed control; if it never shows,
        // pull-to-refresh and give it 10 s more; still nothing → restart Beeline.
        private const val STEP_PRIMARY_WAIT = 60_000L  // 1 min per navigation label
        private const val FIELD_PRIMARY_WAIT = 60_000L // 1 min per text field
        private const val REFRESH_RETRY_WAIT = 10_000L // after a pull-to-refresh
        private const val MAX_BEELINE_RESTARTS = 2     // full Beeline restarts before giving up
        // After «Отправить»: keep tapping «Повторить» for up to 3 min; always return
        // control to the scheduler 5 min after the fire moment.
        private const val REPEAT_WINDOW_MS = 180_000L  // 3 min of «Повторить» tapping
        private const val RULE_MAX_MS = 300_000L       // 5 min hard cap from fire moment
        private const val RESULT_TIMEOUT = 180_000L // (test) outcome screen after «Отправить»
        private const val TEST_STEP_TIMEOUT = 20_000L // test: wait each label up to 20 s, then report
    }
}
