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
 * while the device is working, exactly as before — only the configuration now
 * comes from [DeviceStore] (pushed by the server) instead of local fields:
 *  - stop word ("символ"): reply "Ок", count it, pause until the resume word;
 *    inside a window with a limit set, mark an override so the job runs past the
 *    window end; when the count reaches the limit, finish the session.
 *  - resume word ("успешно"): resume sending.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        val sender = messages.first().originatingAddress ?: return
        val body = messages.joinToString(separator = "") { it.messageBody.orEmpty() }

        val working = DeviceStore.isPaired(context) &&
            DeviceStore.run(context) &&
            !DeviceStore.isSessionDone(context) &&
            DeviceStore.hasSendConfig(context)
        if (!working) return

        val stopWord = DeviceStore.stopWord(context)
        val resumeWord = DeviceStore.resumeWord(context)

        when {
            body.contains(stopWord, ignoreCase = true) -> handleTrigger(context, sender)
            body.contains(resumeWord, ignoreCase = true) -> {
                if (DeviceStore.isPaused(context)) {
                    Log.i(TAG, "Resume word received; continuing")
                    DeviceStore.setPaused(context, false)
                    SenderService.kick(context)
                }
            }
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

        val count = DeviceStore.triggerCount(context) + 1
        DeviceStore.setTriggerCount(context, count)

        val limit = DeviceStore.triggerLimit(context)
        if (windows.isNotEmpty() && insideWindow && limit > 0) {
            DeviceStore.setOverride(context, true)
        }

        if (limit > 0 && count >= limit) {
            Log.i(TAG, "Trigger limit reached ($count/$limit); finishing session")
            DeviceStore.setSessionDone(context, true)
        } else {
            DeviceStore.setPaused(context, true)
        }

        // Report the new counter to the server promptly (for the mini-app display).
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
    }
}
