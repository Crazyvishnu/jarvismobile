package com.vishnu.jarvis

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Only attempt to start if we have the necessary permission.
            // Starting a microphone service from the background is highly restricted on Android 14+.
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                try {
                    context.startForegroundService(Intent(context, OpenWakeWordService::class.java))
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
}