package com.sehirnotlari

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("id", -1)
        // Alarms scheduled by older versions carry their text directly and have no ledger ID.
        val reminder = if (id > 0) Reminders.take(context, id) ?: return else null
        if (reminder == null && !intent.hasExtra("city")) return
        val city = reminder?.optString("city") ?: intent.getStringExtra("city") ?: "Şehir"
        val message = reminder?.optString("message") ?: intent.getStringExtra("message") ?: "Notlarına bak!"

        val notification = NotificationCompat.Builder(context, "REMINDER_CHANNEL")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Şehir Notları: $city")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(if (id > 0) id else System.currentTimeMillis().toInt(), notification)
    }
}
