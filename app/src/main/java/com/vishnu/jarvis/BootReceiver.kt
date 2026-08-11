package com.vishnu.jarvis

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> onBoot(context)

            // New app installed or updated — check if it's a financial app and learn it
            Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_REPLACED -> {
                val pkg = intent.data?.schemeSpecificPart ?: return
                if (PaymentAppGuard.isPaymentApp(context, pkg)) {
                    Log.d(TAG, "New payment app installed/updated: $pkg — already in guard list")
                } else {
                    // The keyword check inside isPaymentApp already calls learnPackage() when matched.
                    // Log unrecognized new installs for diagnostics.
                    Log.d(TAG, "New package installed: $pkg (not a recognized payment app)")
                }
            }

            // App uninstalled — remove from dynamic list if it was learned
            Intent.ACTION_PACKAGE_FULLY_REMOVED -> {
                val pkg = intent.data?.schemeSpecificPart ?: return
                Log.d(TAG, "Package removed: $pkg")
                // Dynamic cleanup is handled via SharedPreferences next time isPaymentApp is called
            }
        }
    }

    private fun onBoot(context: Context) {
        // Only attempt to start if we have the necessary permission.
        // Starting a microphone service from the background is highly restricted on Android 14+.
        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                context.startForegroundService(Intent(context, VoskWakeWordService::class.java))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
