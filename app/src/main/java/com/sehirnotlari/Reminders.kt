package com.sehirnotlari

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/** Small persistent alarm ledger. All mutations use commit so boot recovery sees confirmed alarms. */
object Reminders {
    private const val PREFS = "reminders"
    private const val ITEMS = "items"
    private const val NEXT_ID = "next_id"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun items(context: Context): JSONArray = try {
        JSONArray(prefs(context).getString(ITEMS, "[]"))
    } catch (_: Exception) {
        JSONArray()
    }

    private fun pending(context: Context, id: Int): PendingIntent = PendingIntent.getBroadcast(
        context, id, Intent(context, ReminderReceiver::class.java).putExtra("id", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun arm(context: Context, item: JSONObject): Boolean {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // A delayed alarm can still be pending when the app resumes or the device reboots.
        // Re-arm it promptly; the receiver removes the ledger entry before showing it.
        val originalTime = item.getLong("time")
        val time = if (originalTime <= System.currentTimeMillis()) System.currentTimeMillis() + 1_000L else originalTime
        val pi = pending(context, item.getInt("id"))
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarm.canScheduleExactAlarms()
        return try {
            if (exact) alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pi)
            else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pi)
            exact
        } catch (_: SecurityException) {
            alarm.set(AlarmManager.RTC_WAKEUP, time, pi)
            false
        }
    }

    @Synchronized fun add(context: Context, city: String, message: String, time: Long): JSONObject {
        if (time <= System.currentTimeMillis() || city.isBlank()) return JSONObject().put("error", "Geçerli bir şehir ve gelecek zaman seçin.")
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            return JSONObject().put("error", "Bildirim izni gerekli.")
        val p = prefs(context)
        val id = p.getInt(NEXT_ID, 0) + 1
        val item = JSONObject().put("id", id).put("city", city).put("message", message).put("time", time)
        val all = items(context).put(item)
        if (!p.edit().putInt(NEXT_ID, id).putString(ITEMS, all.toString()).commit())
            return JSONObject().put("error", "Hatırlatıcı kaydedilemedi.")
        return try {
            JSONObject().put("item", item).put("exact", arm(context, item))
        } catch (_: Exception) {
            remove(context, id)
            JSONObject().put("error", "Alarm kurulamadı.")
        }
    }

    @Synchronized fun list(context: Context): String = items(context).toString()

    @Synchronized fun remove(context: Context, id: Int): Boolean {
        val source = items(context)
        val kept = JSONArray()
        var found = false
        for (i in 0 until source.length()) {
            val item = source.optJSONObject(i) ?: continue
            if (item.optInt("id") == id) found = true else kept.put(item)
        }
        if (!found) return false
        if (!prefs(context).edit().putString(ITEMS, kept.toString()).commit()) return false
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending(context, id))
        return true
    }

    @Synchronized fun take(context: Context, id: Int): JSONObject? {
        val source = items(context)
        val item = (0 until source.length()).mapNotNull { source.optJSONObject(it) }.firstOrNull { it.optInt("id") == id } ?: return null
        if (!remove(context, id)) return null
        return item
    }

    @Synchronized fun restore(context: Context) {
        val source = items(context)
        for (i in 0 until source.length()) {
            val item = source.optJSONObject(i) ?: continue
            val id = item.optInt("id")
            if (id <= 0) continue
            try { arm(context, item) } catch (_: Exception) { /* Retain for next launch. */ }
        }
    }
}

class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED)
            Reminders.restore(context)
    }
}
