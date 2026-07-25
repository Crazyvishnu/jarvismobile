package com.vishnu.jarvis

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast

class AppController(private val context: Context) {

    fun openApp(name: String): Boolean {

        val packageName = when (name.lowercase()) {

            "chrome", "browser" -> "com.android.chrome"

            "youtube" -> "com.google.android.youtube"

            "whatsapp" -> "com.whatsapp"

            "instagram" -> "com.instagram.android"

            "telegram" -> "org.telegram.messenger"

            "spotify" -> "com.spotify.music"

            "gmail" -> "com.google.android.gm"

            "camera" -> {
                openCamera()
                return true
            }

            "gallery", "photos" -> {
                openGallery()
                return true
            }

            "settings" -> {
                openSettings()
                return true
            }

            "calculator" -> {
                openCalculator()
                return true
            }

            else -> null
        }

        if (packageName != null) {

            val intent =
                context.packageManager.getLaunchIntentForPackage(packageName)

            if (intent != null) {

                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                context.startActivity(intent)

                return true
            }
        }

        return false
    }

    fun googleSearch(query: String) {

        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}")
        )

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        context.startActivity(intent)
    }

    fun youtubeSearch(query: String) {

        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")
        )

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        context.startActivity(intent)
    }

    private fun openCamera() {

        val intent = Intent("android.media.action.IMAGE_CAPTURE")

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        context.startActivity(intent)
    }

    private fun openGallery() {

        val intent = Intent(Intent.ACTION_VIEW)

        intent.type = "image/*"

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        context.startActivity(intent)
    }

    private fun openSettings() {

        val intent = Intent(Settings.ACTION_SETTINGS)

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        context.startActivity(intent)
    }

    private fun openCalculator() {

        val intent =
            context.packageManager.getLaunchIntentForPackage("com.google.android.calculator")

        if (intent != null) {

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            context.startActivity(intent)

        } else {

            Toast.makeText(context, "Calculator not found", Toast.LENGTH_SHORT)
                .show()
        }
    }
}