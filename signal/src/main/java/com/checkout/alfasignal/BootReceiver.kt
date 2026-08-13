package com.checkout.alfasignal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * After a reboot or an app update, restart the periodic sender if it was running
 * when the device went down. The SMS watcher is a manifest receiver and needs no
 * restart — the system delivers SMS_RECEIVED to it directly.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val a = intent.action ?: return
        if (a == Intent.ACTION_BOOT_COMPLETED ||
            a == Intent.ACTION_MY_PACKAGE_REPLACED ||
            a == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            if (Prefs.sending(context)) {
                try {
                    SmsSender.start(context)
                    Log.i("AlfaSignal", "Sender restarted after $a")
                } catch (e: Exception) {
                    // Starting a foreground service from the background can be
                    // blocked on some versions — the user reopening the app will
                    // start it again. Best-effort only.
                    Log.e("AlfaSignal", "Boot restart failed: ${e.message}")
                }
            }
        }
    }
}
