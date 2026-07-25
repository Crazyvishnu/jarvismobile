package com.vishnu.jarvis

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// Actually searches YouTube (via the YouTube Data API v3 - free tier,
// get a key from console.cloud.google.com and enable "YouTube Data API v3")
// and opens the top result directly, which autoplays it - unlike opening
// a search-results page, which just lists videos.
class YoutubeController(private val context: Context) {

    fun searchAndPlayFirst(query: String, onResult: (success: Boolean, message: String) -> Unit) {
        val apiKey = ProfileStore.getYoutubeApiKey(context)
        if (apiKey.isNullOrBlank()) {
            onResult(false, "Add a YouTube API key in Profile settings first.")
            return
        }

        Thread {
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val url = URL(
                    "https://www.googleapis.com/youtube/v3/search" +
                        "?part=snippet&type=video&maxResults=1&q=$encoded&key=$apiKey"
                )
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                }

                if (conn.responseCode !in 200..299) {
                    postResult(onResult, false, "YouTube API error (${conn.responseCode}). Check your API key/quota.")
                    return@Thread
                }

                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val items = JSONObject(body).optJSONArray("items")

                if (items == null || items.length() == 0) {
                    postResult(onResult, false, "No YouTube results for \"$query\".")
                    return@Thread
                }

                val videoId = items.getJSONObject(0).getJSONObject("id").getString("videoId")

                Handler(Looper.getMainLooper()).post {
                    val appIntent = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$videoId")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try {
                        context.startActivity(appIntent)
                    } catch (e: ActivityNotFoundException) {
                        // Fall back to browser if the YouTube app isn't installed
                        val webIntent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://www.youtube.com/watch?v=$videoId")
                        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                        context.startActivity(webIntent)
                    }
                    onResult(true, "Playing \"$query\" on YouTube.")
                }
            } catch (e: Exception) {
                postResult(onResult, false, "YouTube search failed: ${e.message}")
            }
        }.start()
    }

    private fun postResult(onResult: (Boolean, String) -> Unit, success: Boolean, message: String) {
        Handler(Looper.getMainLooper()).post { onResult(success, message) }
    }
}