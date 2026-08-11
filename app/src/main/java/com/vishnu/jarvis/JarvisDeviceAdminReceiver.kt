package com.vishnu.jarvis

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

// Empty receiver — the only capability we need from Device Admin is
// DevicePolicyManager.lockNow() to re-lock the phone after Jarvis
// finishes executing a voice command from the lock screen.
class JarvisDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {}
    override fun onDisabled(context: Context, intent: Intent) {}
}
