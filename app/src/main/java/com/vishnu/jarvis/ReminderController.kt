package com.vishnu.jarvis

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock

class ReminderController(private val context: Context) {

    fun remindIn(minutes: Int, message: String) {

        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra("message", message)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarm =
            context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        alarm.setExactAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + minutes * 60 * 1000L,
            pendingIntent
        )
    }
}