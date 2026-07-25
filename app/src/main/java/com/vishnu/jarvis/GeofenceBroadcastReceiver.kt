package com.vishnu.jarvis

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

class GeofenceBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        try {
            when (event.geofenceTransition) {
                Geofence.GEOFENCE_TRANSITION_ENTER -> {
                    // Changing ringer mode to silent requires "Do Not Disturb" access
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && notificationManager.isNotificationPolicyAccessGranted) {
                        audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
                    }
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                }
                Geofence.GEOFENCE_TRANSITION_EXIT -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && notificationManager.isNotificationPolicyAccessGranted) {
                        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
                    }
                    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume / 2, 0)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
