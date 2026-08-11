package com.example.messagesender

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the control service after a reboot so a paired device reconnects. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && DeviceStore.isPaired(context)) {
            SenderService.start(context)
        }
    }
}
