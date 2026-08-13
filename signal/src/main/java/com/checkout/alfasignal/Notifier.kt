package com.checkout.alfasignal

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/** Small heads-up so the user can see the signal fired even with the app closed. */
object Notifier {
    private const val CHANNEL = "signal_events"
    private var id = 100

    fun show(context: Context, ok: Boolean, code: Int) {
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL, "Сигналы", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val title = if (ok) "Сигнал отправлен ✅" else "Сигнал: ошибка ⚠️"
        val text = if (code >= 0) "Вебхук: HTTP $code" else "Нет связи с сервером"
        val n = androidx.core.app.NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        mgr.notify(id++, n)
    }
}
