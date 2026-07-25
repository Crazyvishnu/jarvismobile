package com.vishnu.jarvis

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// Uses Spotify's "Client Credentials" flow (app-only auth, no user login
// needed) to search the public catalog for a track, then hands the
// specific track URI to the Spotify app so it plays immediately instead
// of just opening a search screen.
// Get a client ID/secret free at developer.spotify.com/dashboard.
class SpotifyController(private val context: Context) {

    fun searchAndPlayFirst(query: String, onResult: (success: Boolean, message: String) -> Unit) {
        val clientId = ProfileStore.getSpotifyClientId(context)
        val clientSecret = ProfileStore.getSpotifyClientSecret(context)
        if (clientId.isNullOrBlank() || clientSecret.isNullOrBlank()) {
            onResult(false, "Add your Spotify client ID/secret in Profile settings first.")
            return
        }

        Thread {
            try {
                val token = fetchToken(clientId, clientSecret)
                if (token == null) {
                    postResult(onResult, false, "Spotify authentication failed.")
                    return@Thread
                }

                val encoded = URLEncoder.encode(query, "UTF-8")
                val url = URL("https://api.spotify.com/v1/search?q=$encoded&type=track&limit=1")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    setRequestProperty("Authorization", "Bearer $token")
                    connectTimeout = 8000
                    readTimeout = 8000
                }

                if (conn.responseCode !in 200..299) {
                    postResult(onResult, false, "Spotify search error (${conn.responseCode}).")
                    return@Thread
                }

                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val tracks = JSONObject(body).getJSONObject("tracks").getJSONArray("items")

                if (tracks.length() == 0) {
                    postResult(onResult, false, "No Spotify results for \"$query\".")
                    return@Thread
                }

                val trackId = tracks.getJSONObject(0).getString("id")

                Handler(Looper.getMainLooper()).post {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        data = Uri.parse("spotify:track:$trackId")
                        setPackage("com.spotify.music")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try {
                        context.startActivity(intent)
                        onResult(true, "Playing \"$query\" on Spotify.")
                    } catch (e: Exception) {
                        onResult(false, "Spotify app isn't installed.")
                    }
                }
            } catch (e: Exception) {
                postResult(onResult, false, "Spotify search failed: ${e.message}")
            }
        }.start()
    }

    private fun fetchToken(clientId: String, clientSecret: String): String? {
        val url = URL("https://accounts.spotify.com/api/token")
        val creds = Base64.encodeToString("$clientId:$clientSecret".toByteArray(), Base64.NO_WRAP)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Authorization", "Basic $creds")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        conn.outputStream.use { it.write("grant_type=client_credentials".toByteArray()) }
        if (conn.responseCode !in 200..299) return null
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val token = JSONObject(body).optString("access_token")
        return token.ifBlank { null }
    }

    private fun postResult(onResult: (Boolean, String) -> Unit, success: Boolean, message: String) {
        Handler(Looper.getMainLooper()).post { onResult(success, message) }
    }
}