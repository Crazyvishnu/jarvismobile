package com.vishnu.jarvis

import android.content.Context
import android.content.Intent
import android.net.Uri

class MediaController(private val context: Context) {

    fun openYoutube() {
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://www.youtube.com")
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun searchYoutube(query: String) {
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun searchGoogle(query: String) {
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}")
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun openSpotify() {
        val launch =
            context.packageManager.getLaunchIntentForPackage("com.spotify.music")

        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launch)
        }
    }

    fun searchSpotify(song: String) {

        val intent = Intent(Intent.ACTION_VIEW)

        intent.data = Uri.parse("spotify:search:$song")

        intent.setPackage("com.spotify.music")

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        try {
            context.startActivity(intent)
        } catch (e: Exception) {
        }
    }

    fun openChrome() {

        val launch =
            context.packageManager.getLaunchIntentForPackage("com.android.chrome")

        if (launch != null) {

            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            context.startActivity(launch)

        }
    }

    fun openMaps(location: String) {

        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse(
                "geo:0,0?q=${Uri.encode(location)}"
            )
        )

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        context.startActivity(intent)
    }
}