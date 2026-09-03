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
 * make a card transfer at two exact Moscow-time moments each hour, per device,
 * independently:
 *
 *   • Rule A — press «Отправить» exactly at xx:12:59. Preparation starts ~2.5 min
 *     earlier (xx:10:30): open Beeline, walk the transfer screens, fill Номер and
 *     Сумма, reach the «Отправить» button, then hold until the exact second.
 *       – «Вернуться в финансы» → success: wait «символ» from 8464 (SmsReceiver
 *         auto-replies «Ок»), wait «успешно», report to the bot.
 *       – «Повторить» → do nothing, wait for the next rule.
 *
 *   • Rule B — at xx:59:59. Preparation starts 5 min earlier (xx:54:59) and goes
 *     all the way (press «Отправить» during prep):
 *       – «Вернуться в финансы» → success branch → report. Does not stop others.
 *       – «Повторить» → wait until xx:59:59, tap «Повторить», then the same
 *         «символ» → «Ок» → «успешно» → report. If no «символ» comes after
 *         «Повторить», do nothing and wait for the next window.
 *
 * Everything about the flow (screen labels, package, rule times) comes from the
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
        // debug the Beeline flow without waiting for xx:12:59 / xx:59:59.
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

        // For each rule, the prep window is [fireEpoch - prepLead, fireEpoch).
        data class Due(val ruleB: Boolean, val fireEpoch: Long, val cfg: MetodForsConfig)
        val candidates = listOf(false to cfg.ruleA, true to cfg.ruleB).mapNotNull { (isB, rule) ->
            val fireEpoch = MskClock.nextFireEpoch(rule.fireSec)
            val prepStart = fireEpoch - rule.prepLeadSec * 1000L
            if (nowTrue in prepStart..fireEpoch) Due(isB, fireEpoch, cfg) else null
        }
        val due = candidates.minByOrNull { it.fireEpoch } ?: return
        val key = "${if (due.ruleB) "B" else "A"}@${due.fireEpoch}"
        if (key == lastFiredKey) return
        lastFiredKey = key

        busy = true
        worker?.execute {
            try {
                if (!MskClock.hasServerTime()) MskClock.syncHttp(DeviceStore.serverUrl(this))
                runRule(due.ruleB, due.fireEpoch, due.cfg)
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
     * WITHOUT waiting for the xx:12:59 / xx:59:59 moment. Used to verify the whole
     * scenario on demand. On «успешно» the normal report goes to the bot.
     */
    private fun runTest(cfg: MetodForsConfig) {
        val pay = DeviceStore.payments(this).firstOrNull { it.message().isNotBlank() } ?: return
        if (!prepareTransfer(cfg, pay)) return
        // Press «Отправить» right now (no timed wait).
        if (!tapText(cfg.sendLabel, TAP_TIMEOUT)) return
        Log.i(TAG, "TEST: Отправить tapped at msk=${MskClock.mskHms()}")
        when (waitOutcome(cfg, RESULT_TIMEOUT)) {
            Outcome.BACK_TO_FINANCE -> successBranch(cfg, pay, isRuleB = false)
            Outcome.REPEAT -> {
                // In a test we don't wait for xx:59:59 — tap «Повторить» right away.
                if (tapText(cfg.repeatLabel, TAP_TIMEOUT)) successBranch(cfg, pay, isRuleB = false, requireSymbol = true)
            }
            Outcome.NONE -> Log.w(TAG, "TEST: no outcome screen detected")
        }
    }

    // --- One rule run ---

    private fun runRule(isRuleB: Boolean, fireEpoch: Long, cfg: MetodForsConfig) {
        val payments = DeviceStore.payments(this).filter { it.message().isNotBlank() }
        if (payments.isEmpty()) return
        val idx = DeviceStore.mfBlockIndex(this) % payments.size
        val pay = payments[idx]
        Log.i(TAG, "Rule ${if (isRuleB) "B" else "A"} start (msk=${MskClock.mskHms()}), block=$idx req=${pay.requisites} amt=${pay.amount}")

        // 1) Prepare: open Beeline and walk to the «Отправить» button.
        if (!prepareTransfer(cfg, pay)) {
            Log.w(TAG, "prepare failed — aborting this window")
            return
        }

        // 2) Press «Отправить»: Rule A waits for the exact second; Rule B presses now.
        if (!isRuleB) {
            Log.i(TAG, "Reached Отправить; holding until ${cfg.ruleA.fireSec}s (msk=${MskClock.mskHms()})")
            MskClock.sleepUntil(fireEpoch)
        }
        if (!tapText(cfg.sendLabel, TAP_TIMEOUT)) {
            Log.w(TAG, "Отправить not tappable — aborting"); return
        }
        Log.i(TAG, "Отправить tapped at msk=${MskClock.mskHms()}")

        // 3) Evaluate the outcome screen.
        when (waitOutcome(cfg, RESULT_TIMEOUT)) {
            Outcome.BACK_TO_FINANCE -> successBranch(cfg, pay, isRuleB)
            Outcome.REPEAT -> {
                if (!isRuleB) {
                    Log.i(TAG, "«Повторить» on Rule A — doing nothing, waiting for next rule")
                    return
                }
                // Rule B: wait until xx:59:59, then tap «Повторить» and continue.
                val repeatAt = MskClock.nextFireEpoch(cfg.ruleB.fireSec)
                Log.i(TAG, "«Повторить» on Rule B — waiting until ${cfg.ruleB.fireSec}s to tap it")
                MskClock.sleepUntil(repeatAt)
                if (!tapText(cfg.repeatLabel, TAP_TIMEOUT)) { Log.w(TAG, "Повторить not tappable"); return }
                Log.i(TAG, "«Повторить» tapped at msk=${MskClock.mskHms()}")
                // After «Повторить» the same handshake must follow; if no «символ»
                // arrives, do nothing and wait for the next window.
                successBranch(cfg, pay, isRuleB, requireSymbol = true)
            }
            Outcome.NONE -> Log.w(TAG, "No outcome screen detected within timeout — aborting")
        }
    }

    /**
     * Opens Beeline and walks: Сервисы → Перевести деньги → Перевод на карту
     * за рубеж → Таджикистан → По номеру карты → Мой номер → [card field]=Номер →
     * Продолжить → [amount field]=Сумма → Продолжить → wait «Отправить».
     */
    private fun prepareTransfer(cfg: MetodForsConfig, pay: Payment): Boolean {
        if (!launchPackage(cfg.beelinePackage)) { Log.w(TAG, "cannot launch ${cfg.beelinePackage}"); return false }
        sleep(3500) // let the app come to the foreground and render

        // Each step simply WAITS until its label appears, then taps it — no early
        // give-up and no notifications. If the service is stopped, the waits abort.
        for (step in cfg.steps) {
            if (!tapText(step, STEP_TIMEOUT)) return false
            sleep(STEP_PAUSE)
        }
        // Card number field → Номер → Продолжить.
        if (!fillField(cfg.cardFieldHint, pay.requisites)) return false
        sleep(STEP_PAUSE)
        if (!tapText(cfg.continueLabel, STEP_TIMEOUT)) return false
        sleep(STEP_PAUSE)
        // Amount field → Сумма → Продолжить.
        if (!fillField(null, pay.amount)) return false
        sleep(STEP_PAUSE)
        if (!tapText(cfg.continueLabel, STEP_TIMEOUT)) return false
        // Wait for «Отправить» to be present (do NOT press it yet).
        return waitForText(cfg.sendLabel, SEND_WAIT_TIMEOUT) != null
    }

    /**
     * Success handshake: «символ» from 8464 → «Ок» (auto-replied by [SmsReceiver])
     * → «успешно» → report the transfer to the bot. When [requireSymbol] is set
     * (the Rule B «Повторить» path) a missing «символ» is a silent no-op.
     */
    private fun successBranch(cfg: MetodForsConfig, pay: Payment, isRuleB: Boolean, requireSymbol: Boolean = false) {
        val since = System.currentTimeMillis()
        Log.i(TAG, "«Вернуться в финансы» / repeat done — waiting for «${cfg.symbolWord}»")
        val symAt = MetodFors.awaitSymbol(since, SYMBOL_WAIT)
        if (symAt == 0L) {
            Log.i(TAG, if (requireSymbol) "No «символ» after «Повторить» — nothing to do"
                       else "No «символ» within timeout — nothing to report")
            return
        }
        // SmsReceiver already answered «Ок». Now wait for «успешно».
        val okAt = MetodFors.awaitSuccess(symAt, SUCCESS_WAIT)
        if (okAt == 0L) { Log.w(TAG, "«успешно» not received within timeout"); return }

        Log.i(TAG, "«успешно» received — reporting to bot")
        val rule = if (isRuleB) "B" else "A"
        val appCtx = applicationContext
        Thread { ControlClient.reportMetodFors(appCtx, pay.requisites, pay.amount, rule) }
            .apply { isDaemon = true }.start()
        // Rotate to the next configured block for the next window.
        val payments = DeviceStore.payments(this).filter { it.message().isNotBlank() }
        if (payments.isNotEmpty()) DeviceStore.setMfBlockIndex(this, (DeviceStore.mfBlockIndex(this) + 1) % payments.size)
    }

    // --- Outcome detection ---

    private enum class Outcome { BACK_TO_FINANCE, REPEAT, NONE }

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

    private fun launchPackage(pkg: String): Boolean {
        return try {
            val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return false
            // Always open the app FRESH from its root, not wherever the user last
            // was: CLEAR_TASK finishes the app's existing activity stack and starts
            // the launcher activity again, so every run begins on the home screen.
            intent.addFlags(
                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
            )
            startActivity(intent)
            true
        } catch (e: Exception) { Log.e(TAG, "launch error", e); false }
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
        val node = waitForText(text, timeoutMs) ?: return false
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

    /**
     * Fills a text field with [value]. Prefers a field whose text/hint contains
     * [hint]; otherwise the first editable field on screen.
     */
    private fun fillField(hint: String?, value: String): Boolean {
        val deadline = System.currentTimeMillis() + FIELD_TIMEOUT
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

        // We do NOT give up early on UI labels: each step waits until its label
        // actually appears (a very large cap only guards against an eternal lock).
        private const val UI_WAIT = 1_800_000L     // 30 min — effectively "wait until it shows"
        private const val STEP_TIMEOUT = UI_WAIT    // find each navigation label
        private const val STEP_PAUSE = 700L        // settle between screens
        private const val FIELD_TIMEOUT = UI_WAIT   // find a text field
        private const val TAP_TIMEOUT = UI_WAIT     // find a button to tap
        private const val SEND_WAIT_TIMEOUT = UI_WAIT // «Отправить» to appear after prep
        private const val RESULT_TIMEOUT = 180_000L // outcome screen after «Отправить» (3 min)
        private const val SYMBOL_WAIT = 90_000L    // «символ» from 8464
        private const val SUCCESS_WAIT = 120_000L  // «успешно» after «Ок»
    }
}
