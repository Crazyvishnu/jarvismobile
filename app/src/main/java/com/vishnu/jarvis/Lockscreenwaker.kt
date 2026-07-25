package com.vishnu.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

// Handles "wake the phone up" when Jarvis hears the wake word while the
// screen is off / phone is locked - the same mechanism the Phone and Clock
// apps use to show an incoming-call or alarm screen over the lock screen.
//
// A plain startActivity() call from a background service is blocked on
// Android 10+, so the sanctioned path is:
//  1. Post a full-screen-intent notification. The system is allowed to
//     launch that straight over the keyguard even though we're in the
//     background.
//  2. The target activity (MainActivity) tells the system "show me over
//     the lock screen and turn the display on" via
//     setShowWhenLocked()/setTurnScreenOn() (done in MainActivity.onCreate).
object LockScreenWaker {

    private const val CHANNEL_ID = "jarvis_wake"
    private const val NOTIFICATION_ID = 9911

    fun wakeAndListen(context: Context) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

        // Belt-and-suspenders: some OEM skins are slow to honor the
        // full-screen intent alone, so nudge the screen on directly too.
        if (!powerManager.isInteractive) {
            @Suppress("DEPRECATION")
            val wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "Jarvis:WakeWordScreenOn"
            )
            wakeLock.acquire(10_000L)
            wakeLock.release()
        }

        ensureChannel(context)

        val contentIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("from_wake_word", true)
        }

        // If the screen is already on and the device is likely unlocked, 
        // try launching the activity directly for a faster response.
        if (powerManager.isInteractive) {
            try {
                context.startActivity(contentIntent)
            } catch (e: Exception) {
                // Fallback to notification if direct start fails
            }
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Jarvis")
            .setContentText("Listening...")
            .setPriority(Notification.PRIORITY_HIGH)
            .setCategory(Notification.CATEGORY_CALL)
            .setFullScreenIntent(pendingIntent, true)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Jarvis wake-up",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Wakes the screen when Jarvis hears its wake word"
            }
            manager.createNotificationChannel(channel)
        }
    }

    // Android 14+ only shows full-screen intents over the lock screen for
    // apps the user has explicitly allowed (Settings > Apps > Jarvis >
    // Full screen notifications). There is no way to auto-grant this -
    // call it once (e.g. from MainActivity.onCreate) so the user can.
    fun ensureFullScreenIntentPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager?.canUseFullScreenIntent() == false) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        }
    }
}