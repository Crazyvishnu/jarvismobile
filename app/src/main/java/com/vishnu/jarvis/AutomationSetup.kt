package com.vishnu.jarvis

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

// Plain geofencing - purely coordinates and radius, nothing to do with AI.
// Call registerCollegeGeofence() once (e.g. from a "set up automation" button)
// after the user has granted location permission.
object AutomationSetup {

    const val COLLEGE_GEOFENCE_ID = "college_zone"

    @SuppressLint("MissingPermission")
    fun registerCollegeGeofence(
        context: Context,
        latitude: Double,
        longitude: Double,
        radiusMeters: Float = 150f
    ) {
        val geofence = Geofence.Builder()
            .setRequestId(COLLEGE_GEOFENCE_ID)
            .setCircularRegion(latitude, longitude, radiusMeters)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
            .build()

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofence(geofence)
            .build()

        val pendingIntent = PendingIntent.getBroadcast(
            context, 0,
            Intent(context, GeofenceBroadcastReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        val client: GeofencingClient = LocationServices.getGeofencingClient(context)
        client.addGeofences(request, pendingIntent)
    }
}
