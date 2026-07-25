package com.vishnu.jarvis

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity

class ApiKeysActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_api_keys)

        val youtube = findViewById<EditText>(R.id.youtubeKey)
        val spotifyId = findViewById<EditText>(R.id.spotifyId)
        val spotifySecret = findViewById<EditText>(R.id.spotifySecret)
        val gemini = findViewById<EditText>(R.id.geminiKey)
        val openai = findViewById<EditText>(R.id.openaiKey)
        val groq = findViewById<EditText>(R.id.groqKey)
        val weather = findViewById<EditText>(R.id.weatherKey)
        val maps = findViewById<EditText>(R.id.mapsKey)
        val eleven = findViewById<EditText>(R.id.elevenKey)
       

        youtube.setText(ProfileStore.getYoutubeApiKey(this))
        spotifyId.setText(ProfileStore.getSpotifyClientId(this))
        spotifySecret.setText(ProfileStore.getSpotifyClientSecret(this))
        gemini.setText(ProfileStore.getGeminiApiKey(this))
        openai.setText(ProfileStore.getOpenAiKey(this))
        groq.setText(ProfileStore.getGroqKey(this))
        weather.setText(ProfileStore.getWeatherApiKey(this))
        maps.setText(ProfileStore.getMapsApiKey(this))
        eleven.setText(ProfileStore.getElevenLabsKey(this))

        findViewById<Button>(R.id.saveApiButton).setOnClickListener {
            ProfileStore.setYoutubeApiKey(this, youtube.text.toString().trim())

            ProfileStore.setSpotifyCredentials(
                this,
                spotifyId.text.toString().trim(),
                spotifySecret.text.toString().trim()
            )

            ProfileStore.setGeminiApiKey(this, gemini.text.toString().trim())
            ProfileStore.setOpenAiKey(this, openai.text.toString().trim())
            ProfileStore.setGroqKey(this, groq.text.toString().trim())
            ProfileStore.setWeatherApiKey(this, weather.text.toString().trim())
            ProfileStore.setMapsApiKey(this, maps.text.toString().trim())
            ProfileStore.setElevenLabsKey(this, eleven.text.toString().trim())

            finish()
        }
    }
}